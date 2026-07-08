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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import javax.swing.SwingUtilities

class LaunchCodexAction : AnAction() {
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
    val workingDirectory = project.basePath ?: System.getProperty("user.home")
    val widget = createTerminalWidget(manager, workingDirectory)
    if (!invokeFirstStringMethod(widget, listOf("sendCommandToExecute", "executeCommand"), command)) {
        throw IllegalStateException("The JetBrains terminal API does not expose a command execution method")
    }
    if (autoEnableIdeContext) {
        enableIdeModeWhenReady(project, widget)
    }
}

private fun enableIdeModeWhenReady(project: Project, widget: Any) {
    ApplicationManager.getApplication().executeOnPooledThread {
        if (!waitForCodexReady(project, widget) || project.isDisposed) {
            return@executeOnPooledThread
        }
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                tryEnableIdeMode(widget)
            }
        }
    }
}

private fun waitForCodexReady(project: Project, widget: Any): Boolean {
    val deadlineMillis = AtomicLong(System.currentTimeMillis() + CODEX_READY_TIMEOUT_MILLIS)
    var nonMainScreenTracker: NonMainScreenKeyActivityTracker? = null
    try {
        while (!project.isDisposed) {
            val nowMillis = System.currentTimeMillis()
            val terminalState = codexTerminalState(widget)
            when (terminalState) {
                CodexTerminalState.NON_MAIN -> {
                    if (nonMainScreenTracker == null) {
                        deadlineMillis.set(nowMillis + CODEX_READY_TIMEOUT_MILLIS)
                        nonMainScreenTracker = installNonMainScreenKeyActivityTracker(widget, deadlineMillis)
                    }
                }

                CodexTerminalState.READY -> {
                    return true
                }
            }

            if (nowMillis >= deadlineMillis.get()) {
                return false
            }
            val pollMillis = minOf(
                CODEX_READY_POLL_MILLIS,
                (deadlineMillis.get() - nowMillis).coerceAtLeast(1L),
            )
            if (!waitForNextReadyCheck(nonMainScreenTracker, pollMillis)) {
                return false
            }
        }
        return false
    } finally {
        nonMainScreenTracker?.close()
    }
}

private fun installNonMainScreenKeyActivityTracker(
    widget: Any,
    deadlineMillis: AtomicLong,
): NonMainScreenKeyActivityTracker? {
    val window = terminalWindow(widget) ?: return null
    val tracker = NonMainScreenKeyActivityTracker(window, deadlineMillis)
    runOnEdtAndWait {
        tracker.install()
    }
    return tracker
}

private class NonMainScreenKeyActivityTracker(
    private val window: Window,
    private val deadlineMillis: AtomicLong,
) : AutoCloseable {
    private val monitor = Object()
    private var installed = false
    private val dispatcher = KeyEventDispatcher { event ->
        if (event.id == KeyEvent.KEY_PRESSED && eventWindow(event.component) == window) {
            deadlineMillis.set(System.currentTimeMillis() + CODEX_READY_TIMEOUT_MILLIS)
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

private fun waitForNextReadyCheck(
    nonMainScreenTracker: NonMainScreenKeyActivityTracker?,
    delayMillis: Long,
): Boolean {
    return if (nonMainScreenTracker != null) {
        nonMainScreenTracker.awaitOrSleep(delayMillis)
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

private fun codexTerminalState(widget: Any): CodexTerminalState {
    val text = readTerminalText(widget) ?: return CodexTerminalState.NON_MAIN
    val hasCodexSessionText = CODEX_READY_MARKERS.any { marker ->
        text.contains(marker, ignoreCase = true)
    }
    return if (hasCodexSessionText && CODEX_READY_PROMPT_REGEX.containsMatchIn(text)) {
        CodexTerminalState.READY
    } else {
        CodexTerminalState.NON_MAIN
    }
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

private fun tryEnableIdeMode(widget: Any) {
    try {
        if (!sendTerminalCommand(widget, CODEX_IDE_ENABLE_COMMAND)) {
            invokeFirstStringMethod(
                widget,
                listOf("executeCommand", "sendCommandToExecute"),
                CODEX_IDE_ENABLE_COMMAND,
            )
        }
    } catch (ignored: Throwable) {
        // If terminal injection fails, the Codex session is still usable and /ide can be run manually.
    }
}

private fun sendTerminalCommand(widget: Any, command: String): Boolean {
    if (sendRawTerminalInput(widget, command)) {
        return submitTerminalCommandAfterEcho(widget, command) {
            sendRawTerminalInput(widget, TERMINAL_ENTER_INPUT) || sendTerminalEnterKey(widget)
        }
    }
    if (sendWithTerminalStarter(widget, command)) {
        return submitTerminalCommandAfterEcho(widget, command) {
            sendWithTerminalStarter(widget, TERMINAL_ENTER_INPUT) || sendTerminalEnterKey(widget)
        }
    }
    return false
}

private fun submitTerminalCommandAfterEcho(
    widget: Any,
    command: String,
    submit: () -> Boolean,
): Boolean {
    if (!waitForTerminalText(widget, command, CODEX_READY_TIMEOUT_MILLIS)) {
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
        TerminalMethodCall("createLocalShellWidget", arrayOf(workingDirectory, "Codex")),
        TerminalMethodCall("createLocalShellWidget", arrayOf(workingDirectory, "Codex", true)),
        TerminalMethodCall("createShellWidget", arrayOf(workingDirectory, "Codex", true, true)),
        TerminalMethodCall("createShellWidget", arrayOf(workingDirectory, "Codex", true)),
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

private const val CODEX_IDE_ENABLE_COMMAND = "/ide on"
private const val TERMINAL_ENTER_INPUT = "\r"
private const val CODEX_READY_POLL_MILLIS = 250L
private const val CODEX_COMMAND_SUBMIT_DELAY_MILLIS = 250L
private const val CODEX_READY_TIMEOUT_MILLIS = 180000L
private val CODEX_READY_PROMPT_REGEX = Regex("(?m)^\\s*(\\x{203a}\\s|Ask Codex\\b)")
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
