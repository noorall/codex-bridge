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
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
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
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

@Service
class CodexIpcService : Disposable {
    private val log = logger<CodexIpcService>()
    private val gson = Gson()
    private val projectsByPath = ConcurrentHashMap<String, Project>()
    private val executor = Executors.newCachedThreadPool(CodexThreadFactory())

    @Volatile
    private var sharedBridge: IpcBridge? = null

    fun registerProject(project: Project) {
        val basePath = project.basePath ?: return
        projectsByPath[normalizePath(basePath)] = project
        ensureListening(project)
    }

    fun unregisterProject(project: Project) {
        val basePath = project.basePath ?: return
        val projectKey = normalizePath(basePath)
        projectsByPath.remove(projectKey, project)
        if (projectsByPath.isEmpty()) {
            closeSharedBridge()
        }
    }

    @Synchronized
    fun ensureListening(project: Project) {
        if (project.basePath == null) {
            throw IOException("Project has no base path")
        }
        sharedBridge?.takeIf { it.isOpen }?.let {
            return
        }
        sharedBridge?.close()

        val bridge = bindSharedBridge()
        sharedBridge = bridge
        log.info("Codex IDE context bridge active on ${bridge.endpointDescription}")
        executor.execute {
            try {
                runBridge(bridge, project)
            } finally {
                clearSharedBridge(bridge)
            }
        }
    }

    private fun bindSharedBridge(): IpcBridge {
        if (isWindows()) {
            return WindowsNamedPipeBridge()
        }

        val codexHome = defaultCodexHome()
        val systemTempDir = baseTempDir()
        val uid = currentUid()
        val codexHomeSocketExists = codexHome.resolve("ipc").resolve("ipc.sock").normalize().exists()
        val socketPath = selectUnixIpcSocketPath(
            codexHome = codexHome,
            systemTempDir = systemTempDir,
            uid = uid,
            codexHomeSocketExists = codexHomeSocketExists,
        )
        if (codexHomeSocketExists) {
            try {
                return connectToUnixRouter(socketPath)
            } catch (error: IOException) {
                if (isLiveUnixSocket(socketPath)) {
                    throw IOException("Could not join the active Codex IPC router at $socketPath", error)
                }
            }
        }

        val fallbackSocketPath = if (codexHomeSocketExists) {
            systemTempIpcSocketPath(systemTempDir, uid)
        } else {
            socketPath
        }
        if (fallbackSocketPath.exists()) {
            try {
                return connectToUnixRouter(fallbackSocketPath)
            } catch (error: IOException) {
                if (isLiveUnixSocket(fallbackSocketPath)) {
                    throw IOException("Could not join the active Codex IPC router at $fallbackSocketPath", error)
                }
            }
        }
        prepareSocketPath(fallbackSocketPath)
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(fallbackSocketPath))
        return UnixSocketBridge(fallbackSocketPath, channel)
    }

    private fun connectToUnixRouter(socketPath: Path): UnixRouterClientBridge {
        val initialized = initializeUnixRouterConnection(socketPath)
        return UnixRouterClientBridge(
            socketPath,
            SocketChannelConnection(initialized.channel),
            initialized.clientId,
        )
    }

    private fun runBridge(bridge: IpcBridge, project: Project) {
        when (bridge) {
            is UnixSocketBridge -> acceptUnixSocketLoop(bridge.channel, project)
            is UnixRouterClientBridge -> handleRouterConnection(bridge, project)
            is WindowsNamedPipeBridge -> acceptWindowsPipeLoop(bridge, project)
        }
    }

    private fun handleRouterConnection(bridge: UnixRouterClientBridge, project: Project) {
        try {
            handleConnection(bridge.connection, project)
        } catch (error: IOException) {
            if (bridge.isOpen) {
                log.warn("Lost the Codex IPC router connection", error)
            }
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
            "client-discovery-request" -> discoveryResponse(message)
            else -> null
        }
    }

    private fun handleRequest(message: JsonObject, defaultProject: Project?): String {
        val requestId = message.stringField("requestId")
        if (message.stringField("method") != "ide-context") {
            return errorResponse(requestId, "no-handler-for-request")
        }

        val workspaceRoot = message.objectField("params")?.stringField("workspaceRoot")
        val project = selectProject(workspaceRoot)
            ?: defaultProject
                ?.takeIf { workspaceRoot.isNullOrBlank() }
                ?.takeUnless { it.isDisposed }
            ?: return errorResponse(requestId, "no-client-found")

        val context = try {
            project.service<CodexContextService>().captureForSend()
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

    private fun discoveryResponse(message: JsonObject): String {
        val request = message.objectField("request")
        val canHandle = if (request == null) {
            projectsByPath.values.any { !it.isDisposed }
        } else {
            request.stringField("method") == "ide-context" &&
                selectProject(request.objectField("params")?.stringField("workspaceRoot")) != null
        }
        return gson.toJson(
            mapOf(
                "type" to "client-discovery-response",
                "requestId" to message.stringField("requestId"),
                "response" to mapOf("canHandle" to canHandle),
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

    @Synchronized
    private fun closeSharedBridge() {
        sharedBridge?.close()
        sharedBridge = null
    }

    @Synchronized
    private fun clearSharedBridge(bridge: IpcBridge) {
        if (sharedBridge === bridge) {
            bridge.close()
            sharedBridge = null
        }
    }

    override fun dispose() {
        closeSharedBridge()
        executor.shutdownNow()
    }
}

internal data class InitializedRouterConnection(
    val channel: SocketChannel,
    val clientId: String,
)

internal fun initializeUnixRouterConnection(
    socketPath: Path,
    timeoutMillis: Long = ROUTER_CONNECT_TIMEOUT_MILLIS,
): InitializedRouterConnection {
    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    try {
        channel.configureBlocking(false)
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val requestId = UUID.randomUUID().toString()
        Selector.open().use { selector ->
            val connected = channel.connect(UnixDomainSocketAddress.of(socketPath))
            val key = channel.register(selector, 0)
            if (!connected) {
                awaitChannelReady(selector, key, SelectionKey.OP_CONNECT, deadlineNanos)
                if (!channel.finishConnect()) {
                    throw IOException("Codex IPC router connection did not complete")
                }
            }

            val initializeRequest = Gson().toJson(
                mapOf(
                    "type" to "request",
                    "requestId" to requestId,
                    "sourceClientId" to "initializing-client",
                    "version" to 0,
                    "method" to "initialize",
                    "params" to mapOf("clientType" to "jetbrains-ide-context"),
                ),
            )
            writeFrameBeforeDeadline(channel, selector, key, initializeRequest, deadlineNanos)

            while (true) {
                val response = JsonParser.parseString(
                    readFrameBeforeDeadline(channel, selector, key, deadlineNanos),
                ).asJsonObject
                if (
                    response.stringField("type") != "response" ||
                    response.stringField("requestId") != requestId
                ) {
                    continue
                }
                if (response.stringField("resultType") != "success") {
                    throw IOException(
                        "Codex IPC router initialize failed: " +
                            (response.stringField("error") ?: "unknown error"),
                    )
                }
                val clientId = response.objectField("result")?.stringField("clientId")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Codex IPC router initialize returned no client ID")
                key.cancel()
                selector.selectNow()
                channel.configureBlocking(true)
                return InitializedRouterConnection(channel, clientId)
            }
        }
    } catch (error: Throwable) {
        try {
            channel.close()
        } catch (_: IOException) {
        }
        if (error is IOException) {
            throw error
        }
        throw IOException("Could not initialize the Codex IPC router connection", error)
    }
}

private const val MAX_FRAME_BYTES = 256 * 1024 * 1024
private const val CLIENT_ID = "jetbrains-client"
private const val WINDOWS_PIPE_NAME = "\\\\.\\pipe\\codex-ipc"
private const val ROUTER_CONNECT_TIMEOUT_MILLIS = 5000L

private interface IpcBridge : Closeable {
    val endpointDescription: String
    val isOpen: Boolean
}

private data class UnixSocketBridge(
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

private class UnixRouterClientBridge(
    val socketPath: Path,
    val connection: SocketChannelConnection,
    val clientId: String,
) : IpcBridge {
    override val endpointDescription: String = "$socketPath (router client $clientId)"
    override val isOpen: Boolean
        get() = connection.isOpen

    override fun close() {
        connection.close()
    }
}

private class WindowsNamedPipeBridge : IpcBridge {
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

private fun readFrameBeforeDeadline(
    channel: SocketChannel,
    selector: Selector,
    key: SelectionKey,
    deadlineNanos: Long,
): String {
    val lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
    readFullyBeforeDeadline(channel, selector, key, lengthBuffer, deadlineNanos)
    lengthBuffer.flip()
    val length = lengthBuffer.int
    if (length < 0 || length > MAX_FRAME_BYTES) {
        throw IOException("Codex IPC router frame is too large: $length")
    }
    val payload = ByteBuffer.allocate(length)
    readFullyBeforeDeadline(channel, selector, key, payload, deadlineNanos)
    payload.flip()
    return StandardCharsets.UTF_8.decode(payload).toString()
}

private fun writeFrameBeforeDeadline(
    channel: SocketChannel,
    selector: Selector,
    key: SelectionKey,
    message: String,
    deadlineNanos: Long,
) {
    val payload = message.toByteArray(StandardCharsets.UTF_8)
    if (payload.size > MAX_FRAME_BYTES) {
        throw IOException("Codex IPC router frame is too large: ${payload.size}")
    }
    val frame = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
    frame.putInt(payload.size).put(payload).flip()
    while (frame.hasRemaining()) {
        if (channel.write(frame) == 0) {
            awaitChannelReady(selector, key, SelectionKey.OP_WRITE, deadlineNanos)
        }
    }
}

private fun readFullyBeforeDeadline(
    channel: SocketChannel,
    selector: Selector,
    key: SelectionKey,
    buffer: ByteBuffer,
    deadlineNanos: Long,
) {
    while (buffer.hasRemaining()) {
        val count = channel.read(buffer)
        if (count < 0) {
            throw EOFException("Codex IPC router closed the connection")
        }
        if (count == 0) {
            awaitChannelReady(selector, key, SelectionKey.OP_READ, deadlineNanos)
        }
    }
}

private fun awaitChannelReady(
    selector: Selector,
    key: SelectionKey,
    operation: Int,
    deadlineNanos: Long,
) {
    while (true) {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) {
            throw IOException("Timed out waiting for the Codex IPC router")
        }
        key.interestOps(operation)
        val timeoutMillis = maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
        val selected = selector.select(timeoutMillis)
        val ready = selector.selectedKeys().any { selectedKey ->
            selectedKey === key && (selectedKey.readyOps() and operation) != 0
        }
        selector.selectedKeys().clear()
        key.interestOps(0)
        if (selected > 0 && ready) {
            return
        }
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

private fun JsonObject.stringField(name: String): String? {
    return get(name)?.takeIf { !it.isJsonNull }?.asString
}

private fun JsonObject.objectField(name: String): JsonObject? {
    return get(name)?.takeIf { it.isJsonObject }?.asJsonObject
}

internal fun selectUnixIpcSocketPath(
    codexHome: Path,
    systemTempDir: Path,
    uid: Long,
    codexHomeSocketExists: Boolean,
): Path {
    return if (codexHomeSocketExists) {
        codexHome.resolve("ipc").resolve("ipc.sock").normalize()
    } else {
        systemTempIpcSocketPath(systemTempDir, uid)
    }
}

private fun systemTempIpcSocketPath(systemTempDir: Path, uid: Long): Path {
    return systemTempDir.resolve("codex-ipc").resolve("ipc-$uid.sock").normalize()
}

private fun defaultCodexHome(): Path {
    return System.getenv("CODEX_HOME")
        ?.takeIf { it.isNotBlank() }
        ?.let(Paths::get)
        ?: Paths.get(System.getProperty("user.home"), ".codex")
}

private fun baseTempDir(): Path {
    return System.getenv("TMPDIR")
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("java.io.tmpdir"))
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
