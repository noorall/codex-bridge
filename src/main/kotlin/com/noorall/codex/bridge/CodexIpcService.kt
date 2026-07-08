/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.noorall.codex.bridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.io.path.exists

@Service
class CodexIpcService : Disposable {
    private val log = logger<CodexIpcService>()
    private val gson = Gson()
    private val projectsByPath = ConcurrentHashMap<String, Project>()
    private val executor = Executors.newCachedThreadPool(CodexThreadFactory())
    private val projectBridges = ConcurrentHashMap<String, IpcBridge>()

    fun registerProject(project: Project) {
        val basePath = project.basePath ?: return
        projectsByPath[normalizePath(basePath)] = project
        ensureListening(project)
    }

    fun unregisterProject(project: Project) {
        val basePath = project.basePath ?: return
        val projectKey = normalizePath(basePath)
        projectsByPath.remove(projectKey, project)
        if (isWindows()) {
            if (projectsByPath.isEmpty()) {
                projectBridges.remove(WINDOWS_PIPE_BRIDGE_KEY)?.close()
            }
        } else {
            projectBridges.remove(projectKey)?.close()
        }
    }

    fun tmpDirForProject(project: Project): Path = projectTmpDir(project)

    @Synchronized
    fun ensureListening(project: Project): Path {
        val basePath = project.basePath ?: throw IOException("Project has no base path")
        val projectKey = normalizePath(basePath)
        val bridgeKey = if (isWindows()) WINDOWS_PIPE_BRIDGE_KEY else projectKey
        val tmpDir = projectTmpDir(project)

        projectBridges[bridgeKey]?.takeIf { it.isOpen }?.let {
            return tmpDir
        }
        projectBridges.remove(bridgeKey)

        val bridge = bindProjectBridge(tmpDir)
        val existing = projectBridges.putIfAbsent(bridgeKey, bridge)
        if (existing != null) {
            bridge.close()
            return tmpDir
        }

        log.info("Codex IDE context bridge listening on ${bridge.endpointDescription} for ${project.name}")
        executor.execute {
            acceptLoop(bridge, project)
        }
        return tmpDir
    }

    private fun bindProjectBridge(tmpDir: Path): IpcBridge {
        if (isWindows()) {
            return WindowsNamedPipeBridge(tmpDir)
        }

        val socketPath = socketPathForTmpDir(tmpDir)
        prepareSocketPath(socketPath)
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(socketPath))
        return UnixSocketBridge(tmpDir, socketPath, channel)
    }

    private fun acceptLoop(bridge: IpcBridge, project: Project) {
        when (bridge) {
            is UnixSocketBridge -> acceptUnixSocketLoop(bridge.channel, project)
            is WindowsNamedPipeBridge -> acceptWindowsPipeLoop(bridge, project)
        }
    }

    private fun acceptUnixSocketLoop(channel: ServerSocketChannel, project: Project) {
        while (!Thread.currentThread().isInterrupted && channel.isOpen) {
            try {
                val client = channel.accept()
                executor.execute {
                    handleConnection(SocketChannelConnection(client), project)
                }
            } catch (_: ClosedByInterruptException) {
                return
            } catch (error: IOException) {
                if (channel.isOpen) {
                    log.warn("Failed to accept Codex IDE context connection", error)
                }
            }
        }
    }

    private fun acceptWindowsPipeLoop(bridge: WindowsNamedPipeBridge, project: Project) {
        while (!Thread.currentThread().isInterrupted && bridge.isOpen) {
            try {
                val client = bridge.accept()
                handleConnection(client, project)
            } catch (error: IOException) {
                if (bridge.isOpen) {
                    log.warn("Failed to accept Codex IDE context named pipe connection", error)
                }
            }
        }
    }

    private fun handleConnection(connection: IpcConnection, project: Project) {
        connection.use {
            while (it.isOpen) {
                val request = try {
                    it.readFrame()
                } catch (_: EOFException) {
                    return
                }

                val response = handleMessage(request, project)
                if (response != null) {
                    it.writeFrame(response)
                }
            }
        }
    }

    private fun handleMessage(rawMessage: String, project: Project): String? {
        val message = try {
            JsonParser.parseString(rawMessage).asJsonObject
        } catch (error: Throwable) {
            return errorResponse(null, "invalid-request")
        }

        return when (message.stringField("type")) {
            "request" -> handleRequest(message, project)
            "client-discovery-request" -> discoveryResponse(message.stringField("requestId"))
            else -> null
        }
    }

    private fun handleRequest(message: JsonObject, defaultProject: Project): String {
        val requestId = message.stringField("requestId")
        if (message.stringField("method") != "ide-context") {
            return errorResponse(requestId, "no-handler-for-request")
        }

        val workspaceRoot = message.objectField("params")?.stringField("workspaceRoot")
        val project = selectProject(workspaceRoot) ?: defaultProject.takeUnless { it.isDisposed }
            ?: return errorResponse(requestId, "no-client-found")

        val context = try {
            snapshotOnEdt(project)
        } catch (error: Throwable) {
            log.warn("Failed to collect Codex IDE context", error)
            return errorResponse(requestId, "context-unavailable")
        }

        return gson.toJson(
            mapOf(
                "type" to "response",
                "requestId" to requestId,
                "resultType" to "success",
                "method" to "ide-context",
                "handledByClientId" to CLIENT_ID,
                "result" to mapOf(
                    "type" to "broadcast",
                    "ideContext" to context,
                ),
            ),
        )
    }

    private fun selectProject(workspaceRoot: String?): Project? {
        val liveProjects = projectsByPath.entries
            .mapNotNull { (basePath, project) ->
                if (project.isDisposed) null else basePath to project
            }

        if (liveProjects.isEmpty()) {
            return null
        }

        if (workspaceRoot.isNullOrBlank()) {
            return liveProjects.singleOrNull()?.second
        }

        val root = normalizePath(workspaceRoot)
        return liveProjects
            .mapNotNull { (basePath, project) ->
                val score = projectMatchScore(root, basePath)
                if (score == null) null else score to project
            }
            .maxByOrNull { it.first }
            ?.second
            ?: liveProjects.singleOrNull()?.second
    }

    private fun projectMatchScore(workspaceRoot: String, projectRoot: String): Int? {
        if (workspaceRoot == projectRoot) {
            return Int.MAX_VALUE
        }
        if (workspaceRoot.startsWith("$projectRoot/")) {
            return projectRoot.length
        }
        if (projectRoot.startsWith("$workspaceRoot/")) {
            return workspaceRoot.length / 2
        }
        return null
    }

    private fun snapshotOnEdt(project: Project): IdeContext {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            return snapshot(project)
        }

        var result: IdeContext? = null
        var failure: Throwable? = null
        app.invokeAndWait(
            {
                try {
                    result = snapshot(project)
                } catch (error: Throwable) {
                    failure = error
                }
            },
            ModalityState.any(),
        )
        failure?.let { throw it }
        return requireNotNull(result)
    }

    private fun snapshot(project: Project): IdeContext = ReadAction.compute<IdeContext, RuntimeException> {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val activeFile = editor?.let { currentEditor ->
            val file = com.intellij.openapi.fileEditor.FileDocumentManager
                .getInstance()
                .getFile(currentEditor.document)
            if (file == null) null else activeFile(project, currentEditor, file)
        }

        IdeContext(
            activeFile = activeFile,
            openTabs = FileEditorManager.getInstance(project)
                .openFiles
                .map { fileDescriptor(project, it) }
                .distinctBy { it.path },
        )
    }

    private fun activeFile(
        project: Project,
        editor: com.intellij.openapi.editor.Editor,
        file: VirtualFile,
    ): ActiveFile {
        val document = editor.document
        val primaryCaret = editor.caretModel.primaryCaret
        val primaryRange = rangeForOffsets(document, primaryCaret.selectionStart, primaryCaret.selectionEnd)
        val selectedCarets = editor.caretModel.allCarets.filter { it.hasSelection() }
        val selections = selectedCarets.map {
            rangeForOffsets(document, it.selectionStart, it.selectionEnd)
        }
        val content = if (selectedCarets.size <= 1 && primaryCaret.hasSelection()) {
            selectedText(document.charsSequence, primaryCaret.selectionStart, primaryCaret.selectionEnd)
        } else {
            ""
        }

        val descriptor = fileDescriptor(project, file)
        return ActiveFile(
            label = descriptor.label,
            path = descriptor.path,
            fsPath = descriptor.fsPath,
            language = language(project, document),
            selection = primaryRange,
            activeSelectionContent = content,
            selections = selections,
        )
    }

    private fun selectedText(chars: CharSequence, startOffset: Int, endOffset: Int): String {
        val start = minOf(startOffset, endOffset).coerceIn(0, chars.length)
        val end = maxOf(startOffset, endOffset).coerceIn(0, chars.length)
        val selectedLength = end - start
        if (selectedLength <= MAX_ACTIVE_SELECTION_CHARS) {
            return chars.subSequence(start, end).toString()
        }

        return chars.subSequence(start, start + MAX_ACTIVE_SELECTION_CHARS).toString() +
            "\n[Selection truncated by Codex CLI Bridge to $MAX_ACTIVE_SELECTION_CHARS characters.]"
    }

    private fun language(project: Project, document: com.intellij.openapi.editor.Document): String? {
        return PsiDocumentManager.getInstance(project).getPsiFile(document)?.language?.displayName
    }

    private fun fileDescriptor(project: Project, file: VirtualFile): FileDescriptor {
        val fsPath = file.path
        return FileDescriptor(
            label = file.name,
            path = relativePath(project, fsPath),
            fsPath = fsPath,
        )
    }

    private fun relativePath(project: Project, fsPath: String): String {
        val basePath = project.basePath ?: return fsPath
        val base = Paths.get(basePath).toAbsolutePath().normalize()
        val file = Paths.get(fsPath).toAbsolutePath().normalize()
        return if (file.startsWith(base)) {
            base.relativize(file).toString().replace('\\', '/')
        } else {
            fsPath
        }
    }

    private fun rangeForOffsets(
        document: com.intellij.openapi.editor.Document,
        startOffset: Int,
        endOffset: Int,
    ): Range {
        return Range(
            start = positionForOffset(document, startOffset),
            end = positionForOffset(document, endOffset),
        )
    }

    private fun positionForOffset(document: com.intellij.openapi.editor.Document, rawOffset: Int): Position {
        val offset = rawOffset.coerceIn(0, document.textLength)
        if (document.lineCount == 0) {
            return Position(0, 0)
        }
        val line = document.getLineNumber(offset)
        val character = offset - document.getLineStartOffset(line)
        return Position(line, character)
    }

    private fun discoveryResponse(requestId: String?): String {
        return gson.toJson(
            mapOf(
                "type" to "client-discovery-response",
                "requestId" to requestId,
                "response" to mapOf("canHandle" to true),
            ),
        )
    }

    private fun errorResponse(requestId: String?, error: String): String {
        return gson.toJson(
            mapOf(
                "type" to "response",
                "requestId" to requestId,
                "resultType" to "error",
                "error" to error,
            ),
        )
    }

    override fun dispose() {
        projectBridges.values.forEach { it.close() }
        projectBridges.clear()
        executor.shutdownNow()
    }
}

private const val MAX_ACTIVE_SELECTION_CHARS = 40_000
private const val MAX_FRAME_BYTES = 256 * 1024 * 1024
private const val CLIENT_ID = "jetbrains-client"
private const val WINDOWS_PIPE_BRIDGE_KEY = "windows-named-pipe"
private const val WINDOWS_PIPE_NAME = "\\\\.\\pipe\\codex-ipc"

private interface IpcBridge : Closeable {
    val tmpDir: Path
    val endpointDescription: String
    val isOpen: Boolean
}

private data class UnixSocketBridge(
    override val tmpDir: Path,
    val socketPath: Path,
    val channel: ServerSocketChannel,
) : IpcBridge {
    override val endpointDescription: String = socketPath.toString()
    override val isOpen: Boolean
        get() = channel.isOpen

    override fun close() {
        try {
            channel.close()
        } catch (_: IOException) {
        }
        try {
            Files.deleteIfExists(socketPath)
        } catch (_: IOException) {
        }
    }
}

private class WindowsNamedPipeBridge(
    override val tmpDir: Path,
) : IpcBridge {
    @Volatile
    private var closed = false
    private val lock = Any()
    private val activeHandles = ConcurrentHashMap.newKeySet<Pointer>()
    private var pendingHandle: Pointer? = WindowsNamedPipes.createPipeInstance()
    override val endpointDescription: String = WINDOWS_PIPE_NAME
    override val isOpen: Boolean
        get() = !closed

    fun accept(): WindowsPipeConnection {
        val handle = synchronized(lock) {
            if (closed) {
                throw IOException("Codex IDE context named pipe bridge is closed")
            }
            (pendingHandle ?: WindowsNamedPipes.createPipeInstance()).also {
                pendingHandle = null
            }
        }

        return try {
            activeHandles.add(handle)
            WindowsNamedPipes.connect(handle)
            WindowsPipeConnection(handle) {
                activeHandles.remove(handle)
            }
        } catch (error: IOException) {
            activeHandles.remove(handle)
            WindowsNamedPipes.closeHandle(handle)
            throw error
        }
    }

    override fun close() {
        closed = true
        synchronized(lock) {
            pendingHandle?.let { WindowsNamedPipes.closeHandle(it) }
            pendingHandle = null
        }
        activeHandles.forEach { WindowsNamedPipes.closeHandle(it) }
        activeHandles.clear()
    }
}

private interface IpcConnection : Closeable {
    val isOpen: Boolean
    fun readFrame(): String
    fun writeFrame(message: String)
}

private class SocketChannelConnection(
    private val channel: SocketChannel,
) : IpcConnection {
    override val isOpen: Boolean
        get() = channel.isOpen

    override fun readFrame(): String {
        val lenBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        readFully(lenBuffer)
        lenBuffer.flip()
        val length = lenBuffer.int
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw IOException("Codex IDE context frame is too large: $length")
        }

        val payload = ByteBuffer.allocate(length)
        readFully(payload)
        payload.flip()
        return StandardCharsets.UTF_8.decode(payload).toString()
    }

    override fun writeFrame(message: String) {
        val payload = message.toByteArray(StandardCharsets.UTF_8)
        val lenBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size)
        lenBuffer.flip()
        writeFully(lenBuffer)
        writeFully(ByteBuffer.wrap(payload))
    }

    private fun readFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer)
            if (count < 0) {
                throw EOFException("Codex IDE context connection closed")
            }
        }
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    override fun close() {
        channel.close()
    }
}

private class WindowsPipeConnection(
    private val handle: Pointer,
    private val onClose: () -> Unit,
) : IpcConnection {
    @Volatile
    private var closed = false
    override val isOpen: Boolean
        get() = !closed

    override fun readFrame(): String {
        val lenBytes = ByteArray(4)
        readFully(lenBytes)
        val length = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).int
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw IOException("Codex IDE context frame is too large: $length")
        }

        val payload = ByteArray(length)
        readFully(payload)
        return payload.toString(StandardCharsets.UTF_8)
    }

    override fun writeFrame(message: String) {
        val payload = message.toByteArray(StandardCharsets.UTF_8)
        val lenBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array()
        writeFully(lenBytes)
        writeFully(payload)
    }

    private fun readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = WindowsNamedPipes.read(handle, target, offset, target.size - offset)
            if (count <= 0) {
                throw EOFException("Codex IDE context named pipe closed")
            }
            offset += count
        }
    }

    private fun writeFully(source: ByteArray) {
        var offset = 0
        while (offset < source.size) {
            val count = WindowsNamedPipes.write(handle, source, offset, source.size - offset)
            if (count <= 0) {
                throw IOException("Codex IDE context named pipe wrote zero bytes")
            }
            offset += count
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        WindowsNamedPipes.disconnect(handle)
        WindowsNamedPipes.closeHandle(handle)
        onClose()
    }
}

private data class IdeContext(
    val activeFile: ActiveFile?,
    val openTabs: List<FileDescriptor>,
    val processEnv: Map<String, String> = emptyMap(),
)

private data class ActiveFile(
    val label: String,
    val path: String,
    val fsPath: String,
    val language: String?,
    val selection: Range,
    val activeSelectionContent: String,
    val selections: List<Range>,
)

private data class FileDescriptor(
    val label: String,
    val path: String,
    val fsPath: String,
)

private data class Range(
    val start: Position,
    val end: Position,
)

private data class Position(
    val line: Int,
    val character: Int,
)

private fun JsonObject.stringField(name: String): String? {
    return get(name)?.takeIf { !it.isJsonNull }?.asString
}

private fun JsonObject.objectField(name: String): JsonObject? {
    return get(name)?.takeIf { it.isJsonObject }?.asJsonObject
}

private fun socketPathForTmpDir(tmpDir: Path): Path {
    return tmpDir.resolve("codex-ipc").resolve("ipc-${currentUid()}.sock").normalize()
}

private fun projectTmpDir(project: Project): Path {
    val basePath = project.basePath ?: project.name
    return baseTempDir()
        .resolve("codex-bridge")
        .resolve(shortHash(basePath))
        .normalize()
}

private fun baseTempDir(): Path {
    return System.getenv("TMPDIR")
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("java.io.tmpdir"))
}

private fun shortHash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
}

private fun currentUid(): Long {
    return try {
        val unixSystem = Class.forName("com.sun.security.auth.module.UnixSystem")
            .getDeclaredConstructor()
            .newInstance()
        unixSystem.javaClass.getMethod("getUid").invoke(unixSystem) as Long
    } catch (error: Throwable) {
        throw IllegalStateException("Cannot determine current Unix uid for Codex IPC socket", error)
    }
}

private fun prepareSocketPath(path: Path) {
    val parent = requireNotNull(path.parent) { "Codex IPC socket path has no parent: $path" }
    Files.createDirectories(parent)
    setOwnerOnlyDirectoryPermissions(parent)

    if (!path.exists()) {
        return
    }

    if (isLiveUnixSocket(path)) {
        throw IOException("Codex IPC socket is already in use: $path")
    }

    Files.deleteIfExists(path)
}

private fun isLiveUnixSocket(path: Path): Boolean {
    return try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(path))
            true
        }
    } catch (_: IOException) {
        false
    }
}

private fun setOwnerOnlyDirectoryPermissions(path: Path) {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }
}

private fun normalizePath(rawPath: String): String {
    return Paths.get(rawPath).toAbsolutePath().normalize().toString().replace('\\', '/')
}

private fun isWindows(): Boolean {
    return System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}

private object WindowsNamedPipes {
    private const val PIPE_ACCESS_DUPLEX = 0x00000003
    private const val FILE_FLAG_FIRST_PIPE_INSTANCE = 0x00080000
    private const val PIPE_TYPE_BYTE = 0x00000000
    private const val PIPE_READMODE_BYTE = 0x00000000
    private const val PIPE_WAIT = 0x00000000
    private const val PIPE_BUFFER_SIZE = 64 * 1024
    private const val ERROR_PIPE_CONNECTED = 535
    private const val ERROR_BROKEN_PIPE = 109
    private const val ERROR_NO_DATA = 232
    private val invalidHandleValue = Pointer.createConstant(-1)

    private val kernel32 = Native.load("kernel32", Kernel32Api::class.java)

    fun createPipeInstance(): Pointer {
        val handle = kernel32.CreateNamedPipeW(
            WString(WINDOWS_PIPE_NAME),
            PIPE_ACCESS_DUPLEX or FILE_FLAG_FIRST_PIPE_INSTANCE,
            PIPE_TYPE_BYTE or PIPE_READMODE_BYTE or PIPE_WAIT,
            1,
            PIPE_BUFFER_SIZE,
            PIPE_BUFFER_SIZE,
            0,
            null,
        )
        if (handle == null || Pointer.nativeValue(handle) == Pointer.nativeValue(invalidHandleValue)) {
            throw windowsError("CreateNamedPipeW")
        }
        return handle
    }

    fun connect(handle: Pointer) {
        if (kernel32.ConnectNamedPipe(handle, null)) {
            return
        }
        val error = Native.getLastError()
        if (error != ERROR_PIPE_CONNECTED) {
            throw windowsError("ConnectNamedPipe", error)
        }
    }

    fun read(handle: Pointer, target: ByteArray, offset: Int, length: Int): Int {
        val chunkSize = minOf(length, PIPE_BUFFER_SIZE)
        val chunk = ByteArray(chunkSize)
        val bytesRead = IntByReference()
        if (!kernel32.ReadFile(handle, chunk, chunk.size, bytesRead, null)) {
            val error = Native.getLastError()
            if (error == ERROR_BROKEN_PIPE || error == ERROR_NO_DATA) {
                throw EOFException("Codex IDE context named pipe closed")
            }
            throw windowsError("ReadFile", error)
        }
        val count = bytesRead.value
        if (count > 0) {
            System.arraycopy(chunk, 0, target, offset, count)
        }
        return count
    }

    fun write(handle: Pointer, source: ByteArray, offset: Int, length: Int): Int {
        val chunkSize = minOf(length, PIPE_BUFFER_SIZE)
        val chunk = source.copyOfRange(offset, offset + chunkSize)
        val bytesWritten = IntByReference()
        if (!kernel32.WriteFile(handle, chunk, chunk.size, bytesWritten, null)) {
            val error = Native.getLastError()
            if (error == ERROR_BROKEN_PIPE || error == ERROR_NO_DATA) {
                throw EOFException("Codex IDE context named pipe closed")
            }
            throw windowsError("WriteFile", error)
        }
        return bytesWritten.value
    }

    fun disconnect(handle: Pointer) {
        kernel32.FlushFileBuffers(handle)
        kernel32.DisconnectNamedPipe(handle)
    }

    fun closeHandle(handle: Pointer) {
        kernel32.CloseHandle(handle)
    }

    private fun windowsError(function: String, error: Int = Native.getLastError()): IOException {
        return IOException("$function failed with Windows error $error")
    }
}

private interface Kernel32Api : StdCallLibrary {
    fun CreateNamedPipeW(
        lpName: WString,
        dwOpenMode: Int,
        dwPipeMode: Int,
        nMaxInstances: Int,
        nOutBufferSize: Int,
        nInBufferSize: Int,
        nDefaultTimeOut: Int,
        lpSecurityAttributes: Pointer?,
    ): Pointer?

    fun ConnectNamedPipe(hNamedPipe: Pointer, lpOverlapped: Pointer?): Boolean

    fun DisconnectNamedPipe(hNamedPipe: Pointer): Boolean

    fun FlushFileBuffers(hFile: Pointer): Boolean

    fun ReadFile(
        hFile: Pointer,
        lpBuffer: ByteArray,
        nNumberOfBytesToRead: Int,
        lpNumberOfBytesRead: IntByReference,
        lpOverlapped: Pointer?,
    ): Boolean

    fun WriteFile(
        hFile: Pointer,
        lpBuffer: ByteArray,
        nNumberOfBytesToWrite: Int,
        lpNumberOfBytesWritten: IntByReference,
        lpOverlapped: Pointer?,
    ): Boolean

    fun CloseHandle(hObject: Pointer): Boolean
}

private class CodexThreadFactory : ThreadFactory {
    private val counter = java.util.concurrent.atomic.AtomicInteger()

    override fun newThread(runnable: Runnable): Thread {
        return Thread(runnable, "Codex IDE Bridge ${counter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}
