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

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
internal class CodexContextService(
    private val project: Project,
) : Disposable {
    private val listeners = CopyOnWriteArrayList<CodexContextStateListener>()

    @Volatile
    private var state = CodexContextUiState()

    private var sessionToken: Any? = null
    private var sessionDisposable: Disposable? = null
    private var refreshAlarm: Alarm? = null

    fun sessionStarted(token: Any) {
        runOnEdt {
            if (sessionToken === token) {
                return@runOnEdt
            }

            stopTrackingOnEdt()
            sessionToken = token
            val disposable = Disposer.newDisposable("Codex context session")
            Disposer.register(this, disposable)
            sessionDisposable = disposable
            refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

            EditorFactory.getInstance().eventMulticaster.addSelectionListener(
                object : SelectionListener {
                    override fun selectionChanged(event: SelectionEvent) {
                        if (event.editor.project === project) {
                            requestRefresh()
                        }
                    }
                },
                disposable,
            )
            project.messageBus.connect(disposable).subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                        requestRefresh()
                    }

                    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                        requestRefresh()
                    }

                    override fun selectionChanged(event: FileEditorManagerEvent) {
                        requestRefresh()
                    }
                },
            )

            updateState(
                CodexContextUiState(
                    sessionActive = true,
                    context = collectIdeContext(project),
                ),
            )
        }
    }

    fun sessionStopped(token: Any) {
        runOnEdt {
            if (sessionToken !== token) {
                return@runOnEdt
            }
            stopTrackingOnEdt()
            updateState(CodexContextUiState())
        }
    }

    fun captureForSend(): IdeContext {
        val context = collectOnEdt()
        val sentAtMillis = System.currentTimeMillis()
        runOnEdt {
            if (state.sessionActive) {
                updateState(
                    state.copy(
                        context = context,
                        lastSentAtMillis = sentAtMillis,
                    ),
                )
            }
        }
        return context
    }

    fun currentState(): CodexContextUiState = state

    fun addListener(listener: CodexContextStateListener, parentDisposable: Disposable) {
        listeners += listener
        Disposer.register(parentDisposable) {
            listeners -= listener
        }
        runOnEdt {
            listener.stateChanged(state)
        }
    }

    fun clearEditorSelection() {
        runOnEdt {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@runOnEdt
            editor.caretModel.allCarets.forEach { it.removeSelection() }
            requestRefresh()
        }
    }

    private fun requestRefresh() {
        runOnEdt {
            val alarm = refreshAlarm ?: return@runOnEdt
            alarm.cancelAllRequests()
            alarm.addRequest(
                {
                    if (state.sessionActive && !project.isDisposed) {
                        updateState(state.copy(context = collectIdeContext(project)))
                    }
                },
                CONTEXT_REFRESH_DELAY_MILLIS,
            )
        }
    }

    private fun collectOnEdt(): IdeContext {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            return collectIdeContext(project)
        }

        var result: IdeContext? = null
        var failure: Throwable? = null
        app.invokeAndWait(
            {
                try {
                    result = collectIdeContext(project)
                } catch (error: Throwable) {
                    failure = error
                }
            },
            ModalityState.any(),
        )
        failure?.let { throw it }
        return requireNotNull(result)
    }

    private fun runOnEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            if (!project.isDisposed) {
                action()
            }
            return
        }
        app.invokeLater(
            {
                if (!project.isDisposed) {
                    action()
                }
            },
            ModalityState.any(),
        )
    }

    private fun updateState(newState: CodexContextUiState) {
        state = newState
        listeners.forEach { it.stateChanged(newState) }
    }

    private fun stopTrackingOnEdt() {
        sessionToken = null
        refreshAlarm = null
        sessionDisposable?.let(Disposer::dispose)
        sessionDisposable = null
    }

    override fun dispose() {
        sessionToken = null
        refreshAlarm = null
        sessionDisposable = null
        listeners.clear()
    }
}

internal fun interface CodexContextStateListener {
    fun stateChanged(state: CodexContextUiState)
}

internal data class CodexContextUiState(
    val sessionActive: Boolean = false,
    val context: IdeContext? = null,
    val lastSentAtMillis: Long? = null,
)

internal data class IdeContext(
    val activeFile: ActiveFile?,
    val openTabs: List<FileDescriptor>,
    val processEnv: Map<String, String> = emptyMap(),
)

internal data class ActiveFile(
    val label: String,
    val path: String,
    val fsPath: String,
    val language: String?,
    val selection: Range,
    val activeSelectionContent: String,
    val selections: List<Range>,
    @Transient val selectionCharacterCount: Int = activeSelectionContent.length,
)

internal data class FileDescriptor(
    val label: String,
    val path: String,
    val fsPath: String,
)

internal data class Range(
    val start: Position,
    val end: Position,
)

internal data class Position(
    val line: Int,
    val character: Int,
)

internal fun CodexContextUiState.statusBarText(): String {
    if (!sessionActive) {
        return ""
    }
    val activeFile = context?.activeFile ?: return "Codex: No selection"
    if (activeFile.selections.isEmpty()) {
        return "Codex: No selection"
    }

    val label = compactFileLabel(activeFile.label)
    if (activeFile.selections.size > 1) {
        return "Codex: $label (${activeFile.selections.size} selections)"
    }
    return "Codex: $label:${activeFile.selections.single().displayLines()}"
}

internal fun Range.displayLines(): String {
    val startLine = start.line + 1
    val endLine = end.line + 1
    return if (startLine == endLine) startLine.toString() else "$startLine-$endLine"
}

private fun compactFileLabel(label: String): String {
    if (label.length <= MAX_STATUS_FILE_LABEL_CHARS) {
        return label
    }
    return "..." + label.takeLast(MAX_STATUS_FILE_LABEL_CHARS - 3)
}

private fun collectIdeContext(project: Project): IdeContext = ReadAction.compute<IdeContext, RuntimeException> {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor
    val activeFile = editor?.let { currentEditor ->
        val file = FileDocumentManager.getInstance().getFile(currentEditor.document)
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
    editor: Editor,
    file: VirtualFile,
): ActiveFile {
    val document = editor.document
    val primaryCaret = editor.caretModel.primaryCaret
    val primaryRange = rangeForOffsets(document, primaryCaret.selectionStart, primaryCaret.selectionEnd)
    val selectedCarets = editor.caretModel.allCarets.filter { it.hasSelection() }
    val selections = selectedCarets.map {
        rangeForOffsets(document, it.selectionStart, it.selectionEnd)
    }
    val selectionCharacterCount = selectedCarets.sumOf {
        maxOf(it.selectionStart, it.selectionEnd) - minOf(it.selectionStart, it.selectionEnd)
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
        language = PsiDocumentManager.getInstance(project).getPsiFile(document)?.language?.displayName,
        selection = primaryRange,
        activeSelectionContent = content,
        selections = selections,
        selectionCharacterCount = selectionCharacterCount,
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

private fun rangeForOffsets(document: Document, startOffset: Int, endOffset: Int): Range {
    return Range(
        start = positionForOffset(document, startOffset),
        end = positionForOffset(document, endOffset),
    )
}

private fun positionForOffset(document: Document, rawOffset: Int): Position {
    val offset = rawOffset.coerceIn(0, document.textLength)
    if (document.lineCount == 0) {
        return Position(0, 0)
    }
    val line = document.getLineNumber(offset)
    val character = offset - document.getLineStartOffset(line)
    return Position(line, character)
}

private const val CONTEXT_REFRESH_DELAY_MILLIS = 200
private const val MAX_ACTIVE_SELECTION_CHARS = 40_000
private const val MAX_STATUS_FILE_LABEL_CHARS = 32
