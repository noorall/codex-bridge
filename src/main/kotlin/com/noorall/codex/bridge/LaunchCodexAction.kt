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
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import javax.swing.SwingUtilities

private val activeCodexTerminals = Collections.synchronizedMap(WeakHashMap<Project, Any>())

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

        val terminalCommand = buildTerminalCommand(command, tmpDir, project.basePath)
        try {
            openIdeTerminal(project, terminalCommand, autoEnableIdeContext)
        } catch (error: Throwable) {
            Messages.showErrorDialog(project, "Could not open the JetBrains terminal:\n${error.message}", "Codex")
        }
    }
}

private fun buildTerminalCommand(command: String, tmpDir: Path, basePath: String?): String {
    val codexCommand = command.ifBlank { DEFAULT_CODEX_COMMAND }
    if (isWindows()) {
        return codexCommand
    }

    val parts = mutableListOf<String>()
    parts += "export TMPDIR=${shellQuote(tmpDir.toString())}"
    parts += "export CODEX_JETBRAINS_TMPDIR=${shellQuote(tmpDir.toString())}"
    if (!basePath.isNullOrBlank()) {
        parts += "cd ${shellQuote(basePath)}"
    }
    parts += CLEAR_TERMINAL_COMMAND
    parts += codexCommand
    return parts.joinToString("; ")
}

private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

private fun openIdeTerminal(
    project: Project,
    command: String,
    autoEnableIdeContext: Boolean,
) {
    val managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
    val manager = managerClass.getMethod("getInstance", Project::class.java)
        .invoke(null, project)
    findReusableCodexTerminal(project, manager)?.let { existingWidget ->
        if (activateCodexTerminal(manager, existingWidget)) {
            return
        }
        forgetCodexTerminal(project, existingWidget)
    }

    val workingDirectory = project.basePath ?: System.getProperty("user.home")
    val widget = createTerminalWidget(manager, workingDirectory)
    if (!invokeFirstStringMethod(widget, listOf("sendCommandToExecute", "executeCommand"), command)) {
        throw IllegalStateException("The JetBrains terminal API does not expose a command execution method")
    }
    rememberCodexTerminal(project, widget)
    if (autoEnableIdeContext) {
        monitorIdeMode(project, widget)
    }
}

private fun findReusableCodexTerminal(project: Project, manager: Any): Any? {
    registeredCodexTerminal(project)?.let { widget ->
        if (isOpenTerminalWidget(manager, widget)) {
            return widget
        }
        forgetCodexTerminal(project, widget)
    }
    return null
}

private fun registeredCodexTerminal(project: Project): Any? {
    return synchronized(activeCodexTerminals) {
        activeCodexTerminals[project]
    }
}

private fun rememberCodexTerminal(project: Project, widget: Any) {
    synchronized(activeCodexTerminals) {
        activeCodexTerminals[project] = widget
    }
}

private fun forgetCodexTerminal(project: Project, widget: Any) {
    synchronized(activeCodexTerminals) {
        if (activeCodexTerminals[project] === widget) {
            activeCodexTerminals.remove(project)
        }
    }
}

private fun isOpenTerminalWidget(manager: Any, widget: Any): Boolean {
    val content = terminalContent(manager, widget) ?: return false
    return invokeNoArgMethod(content, "isValid") as? Boolean ?: true
}

private fun terminalContent(manager: Any, widget: Any): Any? {
    val container = invokeMethod(manager, "getContainer", arrayOf(widget)) ?: return null
    return invokeNoArgMethod(container, "getContent")
}

private fun activateCodexTerminal(manager: Any, widget: Any): Boolean {
    val toolWindow = invokeNoArgMethod(manager, "getToolWindow") ?: return false
    val content = terminalContent(manager, widget) ?: return false
    val activated = AtomicReference(false)
    runOnEdtAndWait {
        val contentManager = invokeNoArgMethod(toolWindow, "getContentManager")
        if (contentManager != null) {
            if (!invokeMethodIfPresent(contentManager, "setSelectedContent", arrayOf(content, true))) {
                invokeMethodIfPresent(contentManager, "setSelectedContent", arrayOf(content))
            }
        }

        val focusTerminal = Runnable { requestTerminalFocus(widget) }
        val didActivate = invokeMethodIfPresent(toolWindow, "activate", arrayOf(focusTerminal, true, true)) ||
            invokeMethodIfPresent(toolWindow, "activate", arrayOf(focusTerminal, true)) ||
            invokeMethodIfPresent(toolWindow, "activate", arrayOf(focusTerminal)) ||
            invokeMethodIfPresent(toolWindow, "show", arrayOf(focusTerminal)) ||
            invokeMethodIfPresent(toolWindow, "show", arrayOf<Any?>())
        if (!didActivate) {
            requestTerminalFocus(widget)
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

private fun monitorIdeMode(project: Project, widget: Any) {
    ApplicationManager.getApplication().executeOnPooledThread {
        var codexSessionSeen = false
        var lastEnableAttemptMillis = 0L
        var missingTextPolls = 0
        val keyActivityTracker = installTerminalKeyActivityTracker(widget)
        try {
            while (!project.isDisposed) {
                val nowMillis = System.currentTimeMillis()
                val terminalText = readTerminalTail(widget, CODEX_MONITOR_TAIL_CHARS)
                if (terminalText == null) {
                    missingTextPolls += 1
                    if (missingTextPolls >= CODEX_TERMINAL_MISSING_MAX_POLLS) {
                        return@executeOnPooledThread
                    }
                    if (!waitForNextMonitorCheck(keyActivityTracker, CODEX_MONITOR_IDLE_POLL_MILLIS)) {
                        return@executeOnPooledThread
                    }
                    continue
                }

                missingTextPolls = 0
                if (codexTerminalState(terminalText, codexSessionSeen) == CodexTerminalState.READY) {
                    codexSessionSeen = true
                    if (shouldEnableIdeMode(terminalText, keyActivityTracker, nowMillis, lastEnableAttemptMillis)) {
                        lastEnableAttemptMillis = nowMillis
                        tryEnableIdeMode(widget)
                    }
                }

                val pollMillis = if (codexSessionSeen) {
                    CODEX_MONITOR_IDLE_POLL_MILLIS
                } else {
                    CODEX_READY_POLL_MILLIS
                }
                if (!waitForNextMonitorCheck(keyActivityTracker, pollMillis)) {
                    return@executeOnPooledThread
                }
            }
        } finally {
            keyActivityTracker?.close()
            forgetCodexTerminal(project, widget)
        }
    }
}

private fun shouldEnableIdeMode(
    terminalText: String,
    keyActivityTracker: TerminalKeyActivityTracker?,
    nowMillis: Long,
    lastEnableAttemptMillis: Long,
): Boolean {
    if (hasIdeContextIndicator(terminalText)) {
        return false
    }
    if (nowMillis - lastEnableAttemptMillis < CODEX_IDE_ENABLE_COOLDOWN_MILLIS) {
        return false
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
    private var installed = false
    private val dispatcher = KeyEventDispatcher { event ->
        if (event.id == KeyEvent.KEY_PRESSED && eventWindow(event.component) == window) {
            lastKeyPressMillis.set(System.currentTimeMillis())
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
            invokeNoArgMethod(widget, "getText") as? String
        } else {
            val text = AtomicReference<String?>()
            app.invokeAndWait {
                text.set(invokeNoArgMethod(widget, "getText") as? String)
            }
            text.get()
        }
    } catch (ignored: Throwable) {
        null
    }
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
    val method = target.javaClass.methods.firstOrNull {
        it.name == name &&
            it.parameterCount == 1 &&
            it.parameterTypes[0].isAssignableFrom(Consumer::class.java)
    } ?: return false
    method.invoke(target, consumer)
    return true
}

private fun invokeNoArgMethod(target: Any, name: String): Any? {
    val method = target.javaClass.methods.firstOrNull {
        it.name == name && it.parameterCount == 0
    } ?: return null
    return method.invoke(target)
}

private fun writeToTtyConnector(connector: Any, payload: String) {
    val stringMethod = connector.javaClass.methods.firstOrNull {
        it.name == "write" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
    }
    if (stringMethod != null) {
        stringMethod.invoke(connector, payload)
        return
    }

    val bytesMethod = connector.javaClass.methods.firstOrNull {
        it.name == "write" && it.parameterCount == 1 && it.parameterTypes[0] == ByteArray::class.java
    } ?: throw IllegalStateException("The terminal TTY connector does not expose a supported write method")
    bytesMethod.invoke(connector, payload.toByteArray(StandardCharsets.UTF_8) as Any)
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
    val method = target.javaClass.methods.firstOrNull {
        it.name == name && it.parameterCount == args.size && parametersAccept(it, args)
    } ?: return null
    return method.invoke(target, *args)
}

private fun invokeMethodIfPresent(target: Any, name: String, args: Array<Any?>): Boolean {
    val method = target.javaClass.methods.firstOrNull {
        it.name == name && it.parameterCount == args.size && parametersAccept(it, args)
    } ?: return false
    method.invoke(target, *args)
    return true
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
private const val CODEX_IDE_ENABLE_ECHO_TIMEOUT_MILLIS = 5000L
private const val CODEX_COMMAND_SUBMIT_DELAY_MILLIS = 250L
private const val CODEX_PROMPT_SCAN_LINES = 8
private const val CODEX_IDE_STATUS_SCAN_LINES = 8
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
