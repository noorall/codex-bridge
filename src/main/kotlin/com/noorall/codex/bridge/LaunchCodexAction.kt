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

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.awt.Component
import java.awt.Container
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import javax.swing.SwingUtilities

private val activeCodexTerminals = Collections.synchronizedMap(mutableMapOf<String, CodexTerminalSession>())

class LaunchCodexAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.getData(CommonDataKeys.PROJECT) != null
        if (event.isFromActionToolbar) {
            event.presentation.setText("")
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT) ?: return
        val settings = service<CodexSettingsService>()
        val command = settings.codexCommand.trim()
        val autoEnableIdeContext = settings.autoEnableIdeContext
        val bridge = service<CodexIpcService>()
        val tmpDir = try {
            bridge.ensureListening(project)
        } catch (error: Throwable) {
            Messages.showErrorDialog(project, "Could not start Codex IDE bridge:\n${error.message}", "Codex")
            return
        }

        val terminalLaunch = buildTerminalLaunch(command, tmpDir, project.basePath)
        try {
            openIdeTerminal(project, terminalLaunch, autoEnableIdeContext)
        } catch (error: Throwable) {
            Messages.showErrorDialog(project, "Could not open the JetBrains terminal:\n${error.message}", "Codex")
        }
    }
}

private data class TerminalLaunch(
    val command: String,
    val fallbackCommand: String,
    val workingDirectory: String,
    val env: Map<String, String>,
)

private fun buildTerminalLaunch(command: String, tmpDir: Path, basePath: String?): TerminalLaunch {
    val codexCommand = command.ifBlank { DEFAULT_CODEX_COMMAND }
    val workingDirectory = basePath ?: System.getProperty("user.home")
    val env = mapOf(
        "TMPDIR" to tmpDir.toString(),
        "CODEX_JETBRAINS_TMPDIR" to tmpDir.toString(),
    )
    if (isWindows()) {
        return TerminalLaunch(codexCommand, codexCommand, workingDirectory, env)
    }

    val parts = mutableListOf<String>()
    parts += "export TMPDIR=${shellQuote(tmpDir.toString())}"
    parts += "export CODEX_JETBRAINS_TMPDIR=${shellQuote(tmpDir.toString())}"
    if (!basePath.isNullOrBlank()) {
        parts += "cd ${shellQuote(basePath)}"
    }
    parts += CLEAR_TERMINAL_COMMAND
    parts += codexCommand
    return TerminalLaunch(
        command = listOf(CLEAR_TERMINAL_COMMAND, codexCommand).joinToString("; "),
        fallbackCommand = parts.joinToString("; "),
        workingDirectory = workingDirectory,
        env = env,
    )
}

private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

private fun openIdeTerminal(
    project: Project,
    launch: TerminalLaunch,
    autoEnableIdeContext: Boolean,
) {
    val managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
    val manager = managerClass.getMethod("getInstance", Project::class.java)
        .invoke(null, project)
    findReusableCodexTerminal(project, manager)?.let { existingSession ->
        if (activateCodexTerminal(manager, existingSession)) {
            project.service<CodexContextService>().sessionStarted(existingSession.lifecycleToken)
            return
        }
        forgetCodexTerminal(project, existingSession)
    }

    val reworkedSession = createReworkedTerminalSession(project, launch)
    if (reworkedSession != null) {
        rememberCodexTerminal(project, reworkedSession)
        monitorCodexSession(project, reworkedSession, autoEnableIdeContext)
        return
    }

    val widget = createTerminalWidget(manager, launch.workingDirectory)
    if (!invokeFirstStringMethod(widget, listOf("sendCommandToExecute", "executeCommand"), launch.fallbackCommand)) {
        throw IllegalStateException("The JetBrains terminal API does not expose a command execution method")
    }
    val session = CodexTerminalSession(
        widget = widget,
        content = findTerminalContent(manager, widget),
    )
    rememberCodexTerminal(project, session)
    monitorCodexSession(project, session, autoEnableIdeContext)
}

private data class CodexTerminalSession(
    val widget: Any,
    val content: Any?,
    val launchedAtNanos: Long = System.nanoTime(),
    val monitorControl: TerminalMonitorControl = TerminalMonitorControl(),
    val processTracker: TerminalProcessTracker = TerminalProcessTracker(),
    val lifecycleToken: Any = Any(),
)

internal enum class TerminalProcessState {
    RUNNING,
    STOPPED,
    UNKNOWN,
}

internal class TerminalProcessTracker {
    private val state = AtomicReference(TerminalProcessState.UNKNOWN)

    fun current(): TerminalProcessState = state.get()

    fun observe(observedState: TerminalProcessState): TerminalProcessState {
        when (observedState) {
            TerminalProcessState.RUNNING -> state.compareAndSet(TerminalProcessState.UNKNOWN, TerminalProcessState.RUNNING)
            TerminalProcessState.STOPPED -> state.compareAndSet(TerminalProcessState.RUNNING, TerminalProcessState.STOPPED)
            TerminalProcessState.UNKNOWN -> Unit
        }
        return state.get()
    }

    fun markStopped() {
        state.set(TerminalProcessState.STOPPED)
    }
}

internal class TerminalMonitorControl {
    private val active = AtomicBoolean(true)
    private val task = AtomicReference<Future<*>?>()

    fun isActive(): Boolean = active.get()

    fun attach(task: Future<*>) {
        if (!active.get()) {
            task.cancel(true)
            return
        }
        if (!this.task.compareAndSet(null, task)) {
            task.cancel(true)
            return
        }
        if (!active.get() && this.task.compareAndSet(task, null)) {
            task.cancel(true)
        }
    }

    fun stop() {
        active.set(false)
        task.getAndSet(null)?.cancel(true)
    }

    fun completed() {
        active.set(false)
        task.set(null)
    }
}

private fun findReusableCodexTerminal(project: Project, manager: Any): CodexTerminalSession? {
    registeredCodexTerminal(project)?.let { session ->
        val refreshedSession = refreshTerminalSession(project, manager, session)
        if (isOpenTerminalSession(manager, refreshedSession) && isCodexProcessActive(refreshedSession)) {
            return refreshedSession
        }
        forgetCodexTerminal(project, refreshedSession)
    }
    return null
}

private fun registeredCodexTerminal(project: Project): CodexTerminalSession? {
    return synchronized(activeCodexTerminals) {
        activeCodexTerminals[projectTerminalKey(project)]
    }
}

private fun rememberCodexTerminal(project: Project, session: CodexTerminalSession) {
    val previousSession = synchronized(activeCodexTerminals) {
        activeCodexTerminals.put(projectTerminalKey(project), session)
    }
    if (previousSession != null && previousSession.monitorControl !== session.monitorControl) {
        previousSession.monitorControl.stop()
        project.service<CodexContextService>().sessionStopped(previousSession.lifecycleToken)
    }
    project.service<CodexContextService>().sessionStarted(session.lifecycleToken)
}

private fun forgetCodexTerminal(project: Project, session: CodexTerminalSession) {
    val removed = synchronized(activeCodexTerminals) {
        val projectKey = projectTerminalKey(project)
        if (activeCodexTerminals[projectKey]?.lifecycleToken === session.lifecycleToken) {
            activeCodexTerminals.remove(projectKey)
            true
        } else {
            false
        }
    }
    if (removed) {
        session.monitorControl.stop()
        project.service<CodexContextService>().sessionStopped(session.lifecycleToken)
    }
}

private fun projectTerminalKey(project: Project): String {
    return project.locationHash.ifBlank {
        project.basePath ?: project.name
    }
}

private fun createReworkedTerminalSession(
    project: Project,
    launch: TerminalLaunch,
): CodexTerminalSession? {
    val result = AtomicReference<CodexTerminalSession?>()
    runOnEdtAndWait {
        try {
            result.set(createReworkedTerminalSessionOnEdt(project, launch))
        } catch (ignored: Throwable) {
            result.set(null)
        }
    }
    return result.get()
}

private fun createReworkedTerminalSessionOnEdt(
    project: Project,
    launch: TerminalLaunch,
): CodexTerminalSession? {
    val tabsManagerClass = try {
        Class.forName(REWORKED_TERMINAL_TABS_MANAGER_CLASS)
    } catch (ignored: Throwable) {
        return null
    }
    val tabsManager = try {
        tabsManagerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
    } catch (ignored: Throwable) {
        return null
    } ?: return null

    val builder = invokeNoArgMethod(tabsManager, "createTabBuilder") ?: return null
    if (!invokeMethodIfPresent(builder, "workingDirectory", arrayOf(launch.workingDirectory))) {
        return null
    }
    invokeMethodIfPresent(builder, "envVariables", arrayOf(launch.env))
    invokeMethodIfPresent(builder, "tabName", arrayOf(CODEX_TERMINAL_TITLE))
    invokeMethodIfPresent(builder, "requestFocus", arrayOf(true))

    val tab = invokeNoArgMethod(builder, "createTab") ?: return null
    val view = invokeNoArgMethod(tab, "getView") ?: return null
    val content = invokeNoArgMethod(tab, "getContent")
    if (!sendTerminalViewCommand(view, launch.command)) {
        invokeMethodIfPresent(tabsManager, "closeTab", arrayOf(tab))
        return null
    }
    return CodexTerminalSession(
        widget = view,
        content = content,
    )
}

private fun refreshTerminalSession(
    project: Project,
    manager: Any,
    session: CodexTerminalSession,
): CodexTerminalSession {
    if (session.content != null) {
        return session
    }
    val content = findTerminalContent(manager, session.widget) ?: return session
    return session.copy(content = content).also { refreshedSession ->
        rememberCodexTerminal(project, refreshedSession)
    }
}

private fun isOpenTerminalSession(manager: Any, session: CodexTerminalSession): Boolean {
    val content = session.content ?: findTerminalContent(manager, session.widget) ?: return false
    return isValidTerminalContent(manager, content)
}

private fun isCodexProcessActive(session: CodexTerminalSession): Boolean {
    if (session.processTracker.current() == TerminalProcessState.STOPPED) {
        return false
    }

    val state = detectTerminalProcessState(session.widget)
    if (session.processTracker.observe(state) == TerminalProcessState.STOPPED) {
        session.monitorControl.stop()
        return false
    }
    if (state != TerminalProcessState.STOPPED) {
        return true
    }

    val elapsedNanos = System.nanoTime() - session.launchedAtNanos
    if (elapsedNanos >= 0L && elapsedNanos < CODEX_PROCESS_DETECTION_GRACE_NANOS) {
        return true
    }
    session.processTracker.markStopped()
    session.monitorControl.stop()
    return false
}

internal fun detectTerminalProcessState(widget: Any): TerminalProcessState {
    val targets = terminalProcessStateTargets(widget)
    for (target in targets) {
        for (methodName in COMMAND_RUNNING_METHOD_NAMES) {
            invokeNoArgBooleanMethod(target, methodName)?.let { running ->
                return if (running) TerminalProcessState.RUNNING else TerminalProcessState.STOPPED
            }
        }
    }
    for (target in targets) {
        terminalShellCommandState(target)?.let { return it }
    }
    return TerminalProcessState.UNKNOWN
}

private fun terminalProcessStateTargets(widget: Any): List<Any> {
    val targets = mutableListOf(widget)
    val newWidget = invokeNoArgMethodSafely(widget, "asNewWidget")
    if (newWidget != null && targets.none { it === newWidget }) {
        targets += newWidget
    }
    return targets
}

private fun terminalShellCommandState(target: Any): TerminalProcessState? {
    val shellIntegrationDeferred = invokeNoArgMethodSafely(target, "getShellIntegrationDeferred") ?: return null
    if (invokeNoArgBooleanMethod(shellIntegrationDeferred, "isCompleted") != true) {
        return TerminalProcessState.UNKNOWN
    }
    val shellIntegration = invokeNoArgMethodSafely(shellIntegrationDeferred, "getCompleted")
        ?: return TerminalProcessState.UNKNOWN
    val statusFlow = invokeNoArgMethodSafely(shellIntegration, "getOutputStatus")
        ?: return TerminalProcessState.UNKNOWN
    val state = invokeNoArgMethodSafely(statusFlow, "getValue")
        ?: return TerminalProcessState.UNKNOWN
    val stateName = ((state as? Enum<*>)?.name ?: state.toString())
        .filter { it.isLetter() }
        .lowercase()
    return when {
        stateName.endsWith("executingcommand") -> TerminalProcessState.RUNNING
        stateName.endsWith("typingcommand") -> TerminalProcessState.STOPPED
        else -> TerminalProcessState.UNKNOWN
    }
}

private fun invokeNoArgBooleanMethod(target: Any, name: String): Boolean? {
    val method = findInvocableInstanceMethod(target, name, emptyArray()) ?: return null
    if (method.returnType != java.lang.Boolean.TYPE && method.returnType != java.lang.Boolean::class.java) {
        return null
    }
    return try {
        method.invoke(target) as? Boolean
    } catch (ignored: Throwable) {
        null
    }
}

private fun invokeNoArgMethodSafely(target: Any, name: String): Any? {
    return try {
        invokeNoArgMethod(target, name)
    } catch (ignored: Throwable) {
        null
    }
}

private fun isValidTerminalContent(manager: Any, content: Any): Boolean {
    if (invokeNoArgMethod(content, "isValid") as? Boolean == false) {
        return false
    }

    val toolWindow = invokeNoArgMethod(manager, "getToolWindow") ?: return true
    val contentManager = invokeNoArgMethod(toolWindow, "getContentManager") ?: return true
    val contentIndex = invokeMethod(contentManager, "getIndexOfContent", arrayOf(content)) as? Int ?: return true
    return contentIndex >= 0
}

private fun terminalContent(manager: Any, widget: Any): Any? {
    val container = invokeMethod(manager, "getContainer", arrayOf(widget)) ?: return null
    return invokeNoArgMethod(container, "getContent")
}

private fun findTerminalContent(manager: Any, widget: Any): Any? {
    val result = AtomicReference<Any?>()
    runOnEdtAndWait {
        result.set(terminalContent(manager, widget) ?: findTerminalContentInToolWindow(manager, widget))
    }
    return result.get()
}

private fun findTerminalContentInToolWindow(manager: Any, widget: Any): Any? {
    val toolWindow = invokeNoArgMethod(manager, "getToolWindow") ?: return null
    val contentManager = invokeNoArgMethod(toolWindow, "getContentManager") ?: return null
    return contentItems(contentManager).firstOrNull { content ->
        terminalContentMatchesWidget(manager, content, widget)
    }
}

private fun contentItems(contentManager: Any): List<Any> {
    return when (val contents = invokeNoArgMethod(contentManager, "getContents")) {
        is Array<*> -> contents.filterNotNull()
        is Iterable<*> -> contents.filterNotNull()
        else -> emptyList()
    }
}

private fun terminalContentMatchesWidget(manager: Any, content: Any, widget: Any): Boolean {
    val contentWidget = invokeStaticMethod(manager.javaClass, "findWidgetByContent", arrayOf(content))
        ?: invokeStaticMethod(manager.javaClass, "getWidgetByContent", arrayOf(content))
        ?: return false
    return widgetsReferToSameTerminal(contentWidget, widget)
}

private fun widgetsReferToSameTerminal(left: Any, right: Any): Boolean {
    if (left === right) {
        return true
    }

    val leftAsNewWidget = invokeNoArgMethod(left, "asNewWidget")
    val rightAsNewWidget = invokeNoArgMethod(right, "asNewWidget")
    if (leftAsNewWidget != null && leftAsNewWidget === right) {
        return true
    }
    if (rightAsNewWidget != null && rightAsNewWidget === left) {
        return true
    }
    if (leftAsNewWidget != null && rightAsNewWidget != null && leftAsNewWidget === rightAsNewWidget) {
        return true
    }

    return terminalComponents(left).any { leftComponent ->
        terminalComponents(right).any { rightComponent -> leftComponent === rightComponent }
    }
}

private fun terminalComponents(widget: Any): List<Component> {
    return terminalKeyTargetCandidates(widget).filterIsInstance<Component>()
}

private fun activateCodexTerminal(manager: Any, session: CodexTerminalSession): Boolean {
    val toolWindow = invokeNoArgMethod(manager, "getToolWindow") ?: return false
    val content = session.content ?: findTerminalContent(manager, session.widget) ?: return false
    val activated = AtomicReference(false)
    runOnEdtAndWait {
        val contentManager = invokeNoArgMethod(toolWindow, "getContentManager")
        if (contentManager != null) {
            if (!invokeMethodIfPresent(contentManager, "setSelectedContent", arrayOf(content, true))) {
                invokeMethodIfPresent(contentManager, "setSelectedContent", arrayOf(content))
            }
        }

        val focusTerminal = Runnable { requestTerminalFocus(session.widget) }
        val didActivate = invokeMethodIfPresent(toolWindow, "activate", arrayOf(focusTerminal, true, true)) ||
            invokeMethodIfPresent(toolWindow, "activate", arrayOf(focusTerminal, true)) ||
            invokeMethodIfPresent(toolWindow, "activate", arrayOf(focusTerminal)) ||
            invokeMethodIfPresent(toolWindow, "show", arrayOf(focusTerminal)) ||
            invokeMethodIfPresent(toolWindow, "show", arrayOf<Any?>())
        if (!didActivate) {
            requestTerminalFocus(session.widget)
        }
        activated.set(true)
    }
    return activated.get()
}

private fun requestTerminalFocus(widget: Any) {
    invokeNoArgMethod(widget, "requestFocus")
    terminalKeyTargetCandidates(widget)
        .filterIsInstance<Component>()
        .firstOrNull { it.isShowing }
        ?.requestFocusInWindow()
}

private fun monitorCodexSession(
    project: Project,
    session: CodexTerminalSession,
    autoEnableIdeContext: Boolean,
) {
    val task = ApplicationManager.getApplication().executeOnPooledThread {
        val widget = session.widget
        var shouldManageIdeMode = autoEnableIdeContext
        var codexSessionSeen = false
        var ideContextSeen = false
        var nextEnableAttemptMillis = 0L
        var initialEnableDeadlineMillis = System.currentTimeMillis() + CODEX_READY_TIMEOUT_MILLIS
        var missingTextPolls = 0
        var keyActivityTracker: TerminalKeyActivityTracker? = null
        try {
            if (autoEnableIdeContext) {
                keyActivityTracker = installTerminalKeyActivityTracker(widget)
            }
            while (!project.isDisposed && session.monitorControl.isActive()) {
                if (!isCodexProcessActive(session)) {
                    forgetCodexTerminal(project, session)
                    return@executeOnPooledThread
                }

                if (!shouldManageIdeMode) {
                    if (!waitForNextMonitorCheck(keyActivityTracker, CODEX_MONITOR_IDLE_POLL_MILLIS)) {
                        return@executeOnPooledThread
                    }
                    continue
                }

                val nowMillis = System.currentTimeMillis()
                if (!ideContextSeen && nowMillis >= initialEnableDeadlineMillis) {
                    if (!waitForInitialEnableKeyActivity(project, keyActivityTracker, session)) {
                        shouldManageIdeMode = false
                        continue
                    }
                    initialEnableDeadlineMillis = System.currentTimeMillis() + CODEX_READY_TIMEOUT_MILLIS
                    nextEnableAttemptMillis = 0L
                    continue
                }

                val terminalText = readTerminalTail(widget, CODEX_MONITOR_TAIL_CHARS)
                if (terminalText == null) {
                    missingTextPolls += 1
                    if (missingTextPolls >= CODEX_TERMINAL_MISSING_MAX_POLLS) {
                        shouldManageIdeMode = false
                    }
                    if (!waitForNextMonitorCheck(keyActivityTracker, CODEX_MONITOR_IDLE_POLL_MILLIS)) {
                        return@executeOnPooledThread
                    }
                    continue
                }

                missingTextPolls = 0
                val ideContextOn = hasIdeContextIndicator(terminalText)
                ideContextSeen = ideContextSeen || ideContextOn
                if (codexTerminalState(terminalText, codexSessionSeen) == CodexTerminalState.READY) {
                    codexSessionSeen = true
                    if (shouldEnableIdeMode(ideContextOn, ideContextSeen, keyActivityTracker, nowMillis, nextEnableAttemptMillis)) {
                        val submitted = tryEnableIdeMode(widget)
                        val retryDelayMillis = if (ideContextSeen && submitted) {
                            CODEX_IDE_ENABLE_COOLDOWN_MILLIS
                        } else {
                            CODEX_IDE_ENABLE_RETRY_MILLIS
                        }
                        nextEnableAttemptMillis = System.currentTimeMillis() + retryDelayMillis
                    }
                }

                val pollMillis = monitorPollMillis(ideContextSeen, initialEnableDeadlineMillis)
                if (!waitForNextMonitorCheck(keyActivityTracker, pollMillis)) {
                    return@executeOnPooledThread
                }
            }
        } finally {
            keyActivityTracker?.close()
            session.monitorControl.completed()
        }
    }
    session.monitorControl.attach(task)
}

private fun waitForInitialEnableKeyActivity(
    project: Project,
    keyActivityTracker: TerminalKeyActivityTracker?,
    session: CodexTerminalSession,
): Boolean {
    val baselineKeyPressCount = keyActivityTracker?.keyPressCount() ?: return false
    while (!project.isDisposed && session.monitorControl.isActive()) {
        if (!isCodexProcessActive(session)) {
            return false
        }
        val nextKeyPressCount = keyActivityTracker.awaitNextKeyPress(
            baselineKeyPressCount,
            CODEX_INITIAL_ENABLE_KEY_WAIT_POLL_MILLIS,
        )
        if (nextKeyPressCount != null) {
            return true
        }
    }
    return false
}

private fun monitorPollMillis(
    ideContextSeen: Boolean,
    initialEnableDeadlineMillis: Long,
): Long {
    if (ideContextSeen) {
        return CODEX_MONITOR_IDLE_POLL_MILLIS
    }
    return minOf(
        CODEX_READY_POLL_MILLIS,
        (initialEnableDeadlineMillis - System.currentTimeMillis()).coerceAtLeast(1L),
    )
}

private fun shouldEnableIdeMode(
    ideContextOn: Boolean,
    ideContextSeen: Boolean,
    keyActivityTracker: TerminalKeyActivityTracker?,
    nowMillis: Long,
    nextEnableAttemptMillis: Long,
): Boolean {
    if (ideContextOn) {
        return false
    }
    if (nowMillis < nextEnableAttemptMillis) {
        return false
    }
    if (!ideContextSeen) {
        return true
    }
    return keyActivityTracker?.isQuiet(nowMillis, CODEX_TERMINAL_INPUT_QUIET_MILLIS) ?: true
}

private fun installTerminalKeyActivityTracker(
    widget: Any,
): TerminalKeyActivityTracker? {
    val window = terminalWindow(widget) ?: return null
    val tracker = TerminalKeyActivityTracker(window)
    runOnEdtAndWait {
        tracker.install()
    }
    return tracker
}

private class TerminalKeyActivityTracker(
    private val window: Window,
) : AutoCloseable {
    private val monitor = Object()
    private val lastKeyPressMillis = AtomicLong(0L)
    private val keyPressCount = AtomicLong(0L)
    private var installed = false
    private val dispatcher = KeyEventDispatcher { event ->
        if (event.id == KeyEvent.KEY_PRESSED && eventWindow(event.component) == window) {
            lastKeyPressMillis.set(System.currentTimeMillis())
            keyPressCount.incrementAndGet()
            synchronized(monitor) {
                monitor.notifyAll()
            }
        }
        false
    }

    fun install() {
        if (!installed) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
            installed = true
        }
    }

    fun isQuiet(nowMillis: Long, quietMillis: Long): Boolean {
        return nowMillis - lastKeyPressMillis.get() >= quietMillis
    }

    fun keyPressCount(): Long {
        return keyPressCount.get()
    }

    fun awaitNextKeyPress(lastObservedCount: Long, delayMillis: Long): Long? {
        if (keyPressCount.get() > lastObservedCount) {
            return keyPressCount.get()
        }
        return try {
            synchronized(monitor) {
                if (keyPressCount.get() <= lastObservedCount) {
                    monitor.wait(delayMillis)
                }
            }
            keyPressCount.get().takeIf { it > lastObservedCount }
        } catch (ignored: InterruptedException) {
            null
        }
    }

    fun awaitOrSleep(delayMillis: Long): Boolean {
        return try {
            synchronized(monitor) {
                monitor.wait(delayMillis)
            }
            true
        } catch (ignored: InterruptedException) {
            false
        }
    }

    override fun close() {
        if (installed) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
            installed = false
        }
    }
}

private fun waitForNextMonitorCheck(
    keyActivityTracker: TerminalKeyActivityTracker?,
    delayMillis: Long,
): Boolean {
    return if (keyActivityTracker != null) {
        keyActivityTracker.awaitOrSleep(delayMillis)
    } else {
        sleepQuietly(delayMillis)
    }
}

private fun terminalWindow(widget: Any): Window? {
    val window = AtomicReference<Window?>()
    runOnEdtAndWait {
        val component = terminalKeyTargetCandidates(widget).firstNotNullOfOrNull { it as? Component }
        window.set(eventWindow(component))
    }
    return window.get()
}

private fun eventWindow(component: Component?): Window? {
    return when (component) {
        null -> null
        is Window -> component
        else -> SwingUtilities.getWindowAncestor(component)
    }
}

private fun runOnEdtAndWait(action: () -> Unit) {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) {
        action()
    } else {
        app.invokeAndWait {
            action()
        }
    }
}

private fun codexTerminalState(text: String, codexSessionSeen: Boolean): CodexTerminalState {
    val hasCodexSessionText = CODEX_READY_MARKERS.any { marker ->
        text.contains(marker, ignoreCase = true)
    }
    return if ((codexSessionSeen || hasCodexSessionText) && hasCodexReadyPrompt(text)) {
        CodexTerminalState.READY
    } else {
        CodexTerminalState.NON_MAIN
    }
}

private fun hasCodexReadyPrompt(text: String): Boolean {
    val recentLines = text.lines()
        .filter { it.isNotBlank() }
        .takeLast(CODEX_PROMPT_SCAN_LINES)
    val promptLineIndex = recentLines.indexOfLast { CODEX_READY_PROMPT_REGEX.containsMatchIn(it) }
    if (promptLineIndex < 0) {
        return false
    }
    val trailingLines = recentLines.drop(promptLineIndex + 1)
    return trailingLines.isEmpty() || trailingLines.all { CODEX_STATUS_LINE_REGEX.containsMatchIn(it) }
}

private fun hasIdeContextIndicator(text: String): Boolean {
    return text.lines()
        .filter { it.isNotBlank() }
        .takeLast(CODEX_IDE_STATUS_SCAN_LINES)
        .any { CODEX_IDE_CONTEXT_INDICATOR_REGEX.containsMatchIn(it) }
}

private fun readTerminalTail(widget: Any, maxChars: Int): String? {
    val text = readTerminalText(widget) ?: return null
    return if (text.length <= maxChars) text else text.takeLast(maxChars)
}

private fun readTerminalText(widget: Any): String? {
    return try {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            readTerminalViewTextOnEdt(widget) ?: invokeNoArgMethod(widget, "getText") as? String
        } else {
            val text = AtomicReference<String?>()
            app.invokeAndWait {
                text.set(readTerminalViewTextOnEdt(widget) ?: invokeNoArgMethod(widget, "getText") as? String)
            }
            text.get()
        }
    } catch (ignored: Throwable) {
        null
    }
}

private fun readTerminalViewTextOnEdt(widget: Any): String? {
    val outputModels = invokeNoArgMethod(widget, "getOutputModels") ?: return null
    val activeFlow = invokeNoArgMethod(outputModels, "getActive") ?: return null
    val outputModel = invokeNoArgMethod(activeFlow, "getValue") ?: return null
    val snapshot = invokeNoArgMethod(outputModel, "takeSnapshot") ?: return null
    val startOffset = invokeNoArgMethod(snapshot, "getStartOffset") ?: return null
    val endOffset = invokeNoArgMethod(snapshot, "getEndOffset") ?: return null
    return invokeMethod(snapshot, "getText", arrayOf(startOffset, endOffset))?.toString()
}

private fun sleepQuietly(delayMillis: Long): Boolean {
    if (delayMillis <= 0L) {
        return true
    }
    return try {
        Thread.sleep(delayMillis)
        true
    } catch (ignored: InterruptedException) {
        false
    }
}

private fun tryEnableIdeMode(widget: Any): Boolean {
    return try {
        sendTerminalCommand(widget, CODEX_IDE_ENABLE_COMMAND, CODEX_IDE_ENABLE_ECHO_TIMEOUT_MILLIS) ||
            executeTerminalCommand(widget, CODEX_IDE_ENABLE_COMMAND)
    } catch (ignored: Throwable) {
        // If terminal injection fails, the Codex session is still usable and /ide can be run manually.
        false
    }
}

private fun sendTerminalCommand(
    widget: Any,
    command: String,
    echoTimeoutMillis: Long,
): Boolean {
    if (sendTerminalViewCommand(widget, command)) {
        return true
    }
    if (sendRawTerminalInput(widget, command)) {
        return submitTerminalCommandAfterEcho(widget, command, echoTimeoutMillis) {
            sendRawTerminalInput(widget, TERMINAL_ENTER_INPUT) || sendTerminalEnterKey(widget)
        }
    }
    if (sendWithTerminalStarter(widget, command)) {
        return submitTerminalCommandAfterEcho(widget, command, echoTimeoutMillis) {
            sendWithTerminalStarter(widget, TERMINAL_ENTER_INPUT) || sendTerminalEnterKey(widget)
        }
    }
    return false
}

private fun sendTerminalViewCommand(widget: Any, command: String): Boolean {
    val builder = invokeNoArgMethod(widget, "createSendTextBuilder") ?: return false
    invokeMethodIfPresent(builder, "useBracketedPasteMode", arrayOf<Any?>())
    if (!invokeMethodIfPresent(builder, "shouldExecute", arrayOf<Any?>())) {
        invokeMethodIfPresent(builder, "shouldExecute", arrayOf(true))
    }
    return invokeMethodIfPresent(builder, "send", arrayOf(command))
}

private fun submitTerminalCommandAfterEcho(
    widget: Any,
    command: String,
    echoTimeoutMillis: Long,
    submit: () -> Boolean,
): Boolean {
    if (!waitForTerminalText(widget, command, echoTimeoutMillis)) {
        return false
    }
    if (!sleepQuietly(CODEX_COMMAND_SUBMIT_DELAY_MILLIS)) {
        return false
    }
    return submit()
}

private fun waitForTerminalText(
    widget: Any,
    expectedText: String,
    timeoutMillis: Long,
): Boolean {
    val deadlineMillis = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadlineMillis) {
        if (readTerminalText(widget)?.contains(expectedText) == true) {
            return true
        }
        val sleepMillis = minOf(
            CODEX_READY_POLL_MILLIS,
            (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(1L),
        )
        if (!sleepQuietly(sleepMillis)) {
            return false
        }
    }
    return readTerminalText(widget)?.contains(expectedText) == true
}

private fun sendWithTerminalStarter(widget: Any, text: String): Boolean {
    val starter = invokeNoArgMethod(widget, "getTerminalStarter") ?: return false
    return invokeMethodIfPresent(starter, "sendString", arrayOf(text, true))
}

private fun sendTerminalEnterKey(widget: Any): Boolean {
    val result = AtomicReference(false)
    runOnEdtAndWait {
        result.set(sendTerminalEnterKeyOnEdt(widget))
    }
    return result.get()
}

private fun sendTerminalEnterKeyOnEdt(widget: Any): Boolean {
    val target = findTerminalKeyTarget(widget) ?: return false
    val component = (target as? Component) ?: findComponent(target) ?: return false
    val event = KeyEvent(
        component,
        KeyEvent.KEY_PRESSED,
        System.currentTimeMillis(),
        0,
        KeyEvent.VK_ENTER,
        KeyEvent.CHAR_UNDEFINED,
    )
    if (invokeMethodIfPresent(target, "handleKeyEvent", arrayOf(event))) {
        return true
    }
    component.dispatchEvent(event)
    component.dispatchEvent(
        KeyEvent(
            component,
            KeyEvent.KEY_RELEASED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_ENTER,
            KeyEvent.CHAR_UNDEFINED,
        ),
    )
    return true
}

private fun executeTerminalCommand(widget: Any, command: String): Boolean {
    val result = AtomicReference(false)
    runOnEdtAndWait {
        result.set(
            invokeFirstStringMethod(
                widget,
                listOf("executeCommand", "sendCommandToExecute"),
                command,
            ),
        )
    }
    return result.get()
}

private fun findTerminalKeyTarget(widget: Any): Any? {
    return terminalKeyTargetCandidates(widget).firstOrNull { candidate ->
        hasMethod(candidate, "handleKeyEvent", KeyEvent::class.java)
    } ?: terminalKeyTargetCandidates(widget).firstOrNull { it is Component }
}

private fun terminalKeyTargetCandidates(widget: Any): List<Any> {
    val direct = listOfNotNull(
        widget,
        invokeNoArgMethod(widget, "getTerminalPanel"),
        invokeNoArgMethod(widget, "getPreferredFocusableComponent"),
        invokeNoArgMethod(widget, "getComponent"),
    )
    return direct + direct.filterIsInstance<Container>().flatMap { flattenComponents(it) }
}

private fun flattenComponents(container: Container): List<Component> {
    return container.components.flatMap { component ->
        listOf(component) + ((component as? Container)?.let { flattenComponents(it) } ?: emptyList())
    }
}

private fun findComponent(target: Any): Component? {
    return invokeNoArgMethod(target, "getComponent") as? Component
}

private fun hasMethod(target: Any, name: String, vararg parameterTypes: Class<*>): Boolean {
    return target.javaClass.methods.any { method ->
        method.name == name &&
            method.parameterCount == parameterTypes.size &&
            method.parameterTypes.zip(parameterTypes).all { (actual, expected) -> actual.isAssignableFrom(expected) }
    }
}

private fun sendRawTerminalInput(widget: Any, command: String): Boolean {
    return executeWithTtyConnector(widget) { connector ->
        writeToTtyConnector(connector, command)
    }
}

private fun executeWithTtyConnector(target: Any, action: (Any) -> Unit): Boolean {
    val consumer = Consumer<Any> { connector -> action(connector) }
    if (invokeConsumerMethodIfPresent(target, "executeWithTtyConnector", consumer)) {
        return true
    }

    val accessor = invokeNoArgMethod(target, "getTtyConnectorAccessor") ?: return false
    return invokeConsumerMethodIfPresent(accessor, "executeWithTtyConnector", consumer)
}

private fun invokeConsumerMethodIfPresent(target: Any, name: String, consumer: Consumer<Any>): Boolean {
    val method = findInvocableInstanceMethod(target, name, arrayOf(consumer)) ?: return false
    method.invoke(target, consumer)
    return true
}

private fun invokeNoArgMethod(target: Any, name: String): Any? {
    val method = findInvocableInstanceMethod(target, name, emptyArray()) ?: return null
    return method.invoke(target)
}

private fun writeToTtyConnector(connector: Any, payload: String) {
    val stringMethod = findInvocableInstanceMethod(connector, "write", arrayOf(payload))
    if (stringMethod != null) {
        stringMethod.invoke(connector, payload)
        return
    }

    val bytes = payload.toByteArray(StandardCharsets.UTF_8)
    val bytesMethod = findInvocableInstanceMethod(connector, "write", arrayOf(bytes as Any))
        ?: throw IllegalStateException("The terminal TTY connector does not expose a supported write method")
    bytesMethod.invoke(connector, bytes as Any)
}

private fun createTerminalWidget(manager: Any, workingDirectory: String): Any {
    val attempts = listOf(
        TerminalMethodCall("createLocalShellWidget", arrayOf(workingDirectory, CODEX_TERMINAL_TITLE)),
        TerminalMethodCall("createLocalShellWidget", arrayOf(workingDirectory, CODEX_TERMINAL_TITLE, true)),
        TerminalMethodCall("createShellWidget", arrayOf(workingDirectory, CODEX_TERMINAL_TITLE, true, true)),
        TerminalMethodCall("createShellWidget", arrayOf(workingDirectory, CODEX_TERMINAL_TITLE, true)),
    )

    for (attempt in attempts) {
        invokeMethod(manager, attempt.name, attempt.args)?.let {
            return it
        }
    }

    throw IllegalStateException("The JetBrains terminal API does not expose a supported shell widget factory")
}

private data class TerminalMethodCall(
    val name: String,
    val args: Array<Any?>,
)

private fun invokeFirstStringMethod(target: Any, names: List<String>, argument: String): Boolean {
    for (name in names) {
        if (invokeMethodIfPresent(target, name, arrayOf(argument))) {
            return true
        }
    }
    return false
}

private fun invokeMethod(target: Any, name: String, args: Array<Any?>): Any? {
    val method = findInvocableInstanceMethod(target, name, args) ?: return null
    return method.invoke(target, *args)
}

private fun invokeStaticMethod(targetClass: Class<*>, name: String, args: Array<Any?>): Any? {
    val method = targetClass.methods.firstOrNull {
        Modifier.isStatic(it.modifiers) &&
            it.name == name &&
            it.parameterCount == args.size &&
            parametersAccept(it, args)
    } ?: return null
    return method.invoke(null, *args)
}

private fun invokeMethodIfPresent(target: Any, name: String, args: Array<Any?>): Boolean {
    val method = findInvocableInstanceMethod(target, name, args) ?: return false
    method.invoke(target, *args)
    return true
}

private fun findInvocableInstanceMethod(target: Any, name: String, args: Array<Any?>): Method? {
    val visitedTypes = mutableSetOf<Class<*>>()
    val pendingTypes = ArrayDeque<Class<*>>()
    pendingTypes.add(target.javaClass)
    while (pendingTypes.isNotEmpty()) {
        val type = pendingTypes.removeFirst()
        if (!visitedTypes.add(type)) {
            continue
        }
        if (Modifier.isPublic(type.modifiers)) {
            type.declaredMethods.firstOrNull { method ->
                Modifier.isPublic(method.modifiers) &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.name == name &&
                    method.parameterCount == args.size &&
                    parametersAccept(method, args)
            }?.let { return it }
        }
        type.interfaces.forEach(pendingTypes::addLast)
        type.superclass?.let(pendingTypes::addLast)
    }

    val implementationMethod = target.javaClass.methods.firstOrNull { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.name == name &&
            method.parameterCount == args.size &&
            parametersAccept(method, args)
    } ?: return null
    return try {
        implementationMethod.takeIf { it.trySetAccessible() }
    } catch (ignored: Throwable) {
        null
    }
}

private fun parametersAccept(method: Method, args: Array<Any?>): Boolean {
    return method.parameterTypes.zip(args).all { (parameterType, arg) ->
        when {
            arg == null -> !parameterType.isPrimitive
            parameterType.isPrimitive -> primitiveWrapper(parameterType).isInstance(arg)
            else -> parameterType.isInstance(arg)
        }
    }
}

private fun primitiveWrapper(type: Class<*>): Class<*> {
    return when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }
}

private fun isWindows(): Boolean {
    return System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}

private enum class CodexTerminalState {
    NON_MAIN,
    READY,
}

private const val CODEX_TERMINAL_TITLE = "Codex"
private const val CODEX_IDE_ENABLE_COMMAND = "/ide on"
private const val TERMINAL_ENTER_INPUT = "\r"
private const val CODEX_READY_POLL_MILLIS = 250L
private const val CODEX_MONITOR_IDLE_POLL_MILLIS = 1500L
private const val CODEX_MONITOR_TAIL_CHARS = 16000
private const val CODEX_TERMINAL_MISSING_MAX_POLLS = 80
private const val CODEX_TERMINAL_INPUT_QUIET_MILLIS = 1000L
private const val CODEX_IDE_ENABLE_COOLDOWN_MILLIS = 10000L
private const val CODEX_IDE_ENABLE_RETRY_MILLIS = 1000L
private const val CODEX_IDE_ENABLE_ECHO_TIMEOUT_MILLIS = 5000L
private const val CODEX_COMMAND_SUBMIT_DELAY_MILLIS = 250L
private const val CODEX_PROCESS_DETECTION_GRACE_NANOS = 2_000_000_000L
private const val CODEX_READY_TIMEOUT_MILLIS = 180000L
private const val CODEX_INITIAL_ENABLE_KEY_WAIT_POLL_MILLIS = 1000L
private const val CODEX_PROMPT_SCAN_LINES = 8
private const val CODEX_IDE_STATUS_SCAN_LINES = 8
private const val REWORKED_TERMINAL_TABS_MANAGER_CLASS =
    "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager"
private val COMMAND_RUNNING_METHOD_NAMES = listOf("isCommandRunning", "hasRunningCommands")
private val CODEX_READY_PROMPT_REGEX = Regex("(?m)^\\s*(\\x{203a}\\s|Ask Codex\\b)")
private val CODEX_STATUS_LINE_REGEX = Regex("\\s\\u00b7\\s|\\bIDE context\\b", RegexOption.IGNORE_CASE)
private val CODEX_IDE_CONTEXT_INDICATOR_REGEX = Regex("\\bIDE context\\b", RegexOption.IGNORE_CASE)
private val CODEX_READY_MARKERS = listOf(
    "OpenAI Codex",
    "/model to change",
    "model:",
    "directory:",
    "Tip: Try the Codex App",
    "@filename",
    "usage limit resets",
    "Run /usage",
)
private const val CLEAR_TERMINAL_COMMAND = "printf '\\033[3J\\033[H\\033[2J'"
