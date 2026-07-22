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
import com.noorall.codex.bridge.fixture.hiddenTerminalWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.event.KeyEvent
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.JTextField

class TerminalProcessDetectionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `uses the shared Codex home IPC socket for the desktop router`() {
        assertEquals(
            Path.of("/home/test/.codex/ipc/ipc.sock"),
            codexHomeIpcSocketPath(Path.of("/home/test/.codex")),
        )
    }

    @Test
    fun `keeps the native system temp IPC socket independent`() {
        assertEquals(
            Path.of("/system/tmp/codex-ipc/ipc-1000.sock"),
            systemTempIpcSocketPath(Path.of("/system/tmp"), 1000L),
        )
    }

    @Test
    fun `detects a Codex home IPC socket created after startup`() {
        val codexHome = temporaryFolder.root.toPath().resolve("created-later").resolve(".codex")
        val socketPath = codexHomeIpcSocketPath(codexHome)
        val watcher = UnixSocketFileWatcher(socketPath, retryMillis = 10L)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val detected = executor.submit<Boolean> { watcher.awaitSocket() }
            Files.createDirectories(socketPath.parent)
            Files.createFile(socketPath)

            assertTrue(detected.get(5L, TimeUnit.SECONDS))
        } finally {
            watcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `closing the Codex home watcher releases a blocked wait`() {
        val socketPath = temporaryFolder.root.toPath().resolve("missing").resolve("ipc.sock")
        val watcher = UnixSocketFileWatcher(socketPath, retryMillis = 10L)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val detected = executor.submit<Boolean> { watcher.awaitSocket() }
            watcher.close()

            assertFalse(detected.get(5L, TimeUnit.SECONDS))
        } finally {
            watcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `does not override Codex temp environment when launching`() {
        val launch = buildTerminalLaunch("codex", "/workspace")

        assertFalse(launch.command.contains("TMPDIR"))
        assertFalse(launch.fallbackCommand.contains("TMPDIR"))
        assertFalse(launch.command.contains("CODEX_JETBRAINS_TMPDIR"))
        assertFalse(launch.fallbackCommand.contains("CODEX_JETBRAINS_TMPDIR"))
    }

    @Test
    fun `joins an existing Codex IPC router as an IDE context provider`() {
        Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val socketPath = temporaryFolder.newFolder("codex-router").toPath().resolve("ipc.sock")
        val executor = Executors.newSingleThreadExecutor()

        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socketPath))
                val initializeRequest = executor.submit<JsonObject> {
                    server.accept().use { client ->
                        val request = readTestFrame(client)
                        writeTestFrame(
                            client,
                            mapOf(
                                "type" to "response",
                                "requestId" to request.get("requestId").asString,
                                "resultType" to "success",
                                "result" to mapOf("clientId" to "jetbrains-router-client"),
                            ),
                        )
                        request
                    }
                }

                val initialized = initializeUnixRouterConnection(socketPath, timeoutMillis = 2_000L)
                initialized.channel.close()

                val request = initializeRequest.get()
                assertEquals("jetbrains-router-client", initialized.clientId)
                assertEquals("initialize", request.get("method").asString)
                assertEquals(
                    "jetbrains-ide-context",
                    request.getAsJsonObject("params").get("clientType").asString,
                )
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `detects command state from modern terminal widget`() {
        assertEquals(
            TerminalProcessState.RUNNING,
            detectTerminalProcessState(ModernTerminalWidget(true)),
        )
        assertEquals(
            TerminalProcessState.STOPPED,
            detectTerminalProcessState(ModernTerminalWidget(false)),
        )
    }

    @Test
    fun `detects command state from classic terminal widget`() {
        assertEquals(
            TerminalProcessState.RUNNING,
            detectTerminalProcessState(ClassicTerminalWidget(true)),
        )
        assertEquals(
            TerminalProcessState.STOPPED,
            detectTerminalProcessState(ClassicTerminalWidget(false)),
        )
    }

    @Test
    fun `detects command state from reworked terminal shell integration`() {
        assertEquals(
            TerminalProcessState.RUNNING,
            detectTerminalProcessState(TerminalView("ExecutingCommand")),
        )
        assertEquals(
            TerminalProcessState.STOPPED,
            detectTerminalProcessState(TerminalView("TypingCommand")),
        )
        assertEquals(
            TerminalProcessState.UNKNOWN,
            detectTerminalProcessState(TerminalView("WaitingForPrompt")),
        )
    }

    @Test
    fun `keeps an unavailable shell integration state unknown`() {
        assertEquals(
            TerminalProcessState.UNKNOWN,
            detectTerminalProcessState(TerminalViewWithPendingShellIntegration()),
        )
    }

    @Test
    fun `prefers an available shell state over stale command flags`() {
        assertEquals(
            TerminalProcessState.STOPPED,
            detectTerminalProcessState(TerminalViewWithRunningFlag("TypingCommand", running = true)),
        )
        assertEquals(
            TerminalProcessState.RUNNING,
            detectTerminalProcessState(TerminalViewWithRunningFlag("ExecutingCommand", running = false)),
        )
    }

    @Test
    fun `wraps a command with an exit and interrupt marker trap`() {
        val command = appendCodexExitMarker("codex --model test", "__CODEX_BRIDGE_EXIT_test")

        assertTrue(command.startsWith("( trap \"trap - EXIT HUP INT TERM; printf"))
        assertTrue(command.contains("'__CODEX_BRIDGE_EXIT_test'\" EXIT HUP INT TERM"))
        assertTrue(command.endsWith("; ( codex --model test\n) )"))
    }

    @Test
    fun `uses the exit marker only for shells with compatible traps`() {
        assertTrue(supportsShellExitTrap("/bin/zsh -l"))
        assertTrue(supportsShellExitTrap("'/opt/custom shell/bash' --noprofile"))
        assertFalse(supportsShellExitTrap("/usr/bin/fish"))
        assertFalse(supportsShellExitTrap(null))
    }

    @Test
    fun `detects only the current exit marker on its own line`() {
        val exitMarker = "__CODEX_BRIDGE_EXIT_current"

        assertTrue(hasCodexExitMarker("Codex output\n$exitMarker\nshell prompt", exitMarker))
        assertTrue(hasCodexExitMarker("Codex output\r\n  $exitMarker  \r\n", exitMarker))
        assertFalse(hasCodexExitMarker("echo '$exitMarker'", exitMarker))
        assertFalse(hasCodexExitMarker("__CODEX_BRIDGE_EXIT_previous", exitMarker))
    }

    @Test
    fun `checks the new widget adapter when present`() {
        assertEquals(
            TerminalProcessState.STOPPED,
            detectTerminalProcessState(WidgetAdapter(ModernTerminalWidget(false))),
        )
    }

    @Test
    fun `keeps unknown terminal implementations reusable`() {
        assertEquals(
            TerminalProcessState.UNKNOWN,
            detectTerminalProcessState(Any()),
        )
    }

    @Test
    fun `invokes a public interface method implemented by a nonpublic class`() {
        assertEquals(
            TerminalProcessState.RUNNING,
            detectTerminalProcessState(hiddenTerminalWidget(running = true)),
        )
    }

    @Test
    fun `tracks a stopped process only after it was running`() {
        val tracker = TerminalProcessTracker()

        assertEquals(TerminalProcessState.UNKNOWN, tracker.observe(TerminalProcessState.STOPPED))
        assertEquals(TerminalProcessState.RUNNING, tracker.observe(TerminalProcessState.RUNNING))
        assertEquals(TerminalProcessState.STOPPED, tracker.observe(TerminalProcessState.STOPPED))
        assertEquals(TerminalProcessState.STOPPED, tracker.observe(TerminalProcessState.RUNNING))
    }

    @Test
    fun `stopping a session cancels its attached monitor task`() {
        val monitorControl = TerminalMonitorControl()
        val task = FutureTask<Unit> {}

        monitorControl.attach(task)
        monitorControl.stop()

        assertFalse(monitorControl.isActive())
        assertTrue(task.isCancelled)
    }

    @Test
    fun `a monitor task attached after session stop is cancelled`() {
        val monitorControl = TerminalMonitorControl()
        val task = FutureTask<Unit> {}

        monitorControl.stop()
        monitorControl.attach(task)

        assertTrue(task.isCancelled)
    }

    @Test
    fun `a monitor task attached after monitor completion is cancelled`() {
        val monitorControl = TerminalMonitorControl()
        val task = FutureTask<Unit> {}

        monitorControl.completed()
        monitorControl.attach(task)

        assertFalse(monitorControl.isActive())
        assertTrue(task.isCancelled)
    }

    @Test
    fun `detects only an exact desktop handoff line`() {
        assertTrue(isDesktopHandoffLine("Opened this session in Codex Desktop."))
        assertTrue(isDesktopHandoffLine("  • Opened this session in Codex Desktop.  "))
        assertFalse(isDesktopHandoffLine("› Opened this session in Codex Desktop."))
        assertFalse(isDesktopHandoffLine("prefix Opened this session in Codex Desktop."))
        assertFalse(isDesktopHandoffLine("Opened this session in Codex Desktop"))
    }

    @Test
    fun `detects only an exact ide context enable failure line`() {
        assertTrue(isIdeContextEnableFailureLine("IDE context could not be enabled."))
        assertTrue(isIdeContextEnableFailureLine("  • IDE context could not be enabled.  "))
        assertFalse(isIdeContextEnableFailureLine("› IDE context could not be enabled."))
        assertFalse(isIdeContextEnableFailureLine("prefix IDE context could not be enabled."))
        assertFalse(isIdeContextEnableFailureLine("IDE context could not be enabled"))
        assertTrue(
            hasIdeContextEnableFailure(
                """
                    • IDE context could not be enabled.

                    › Write tests for @filename
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `consumes each rendered desktop handoff only once`() {
        val tracker = DesktopHandoffTracker()
        val firstScreen = """
            - Did not push anything.

            ─ Worked for 6m 57s ─

            • Opened this session in Codex Desktop.

            › Write tests for @filename
        """.trimIndent()

        assertTrue(tracker.observe(firstScreen))
        assertFalse(tracker.observe(firstScreen))
        assertFalse(tracker.observe("$firstScreen\n› a new user message"))
        assertTrue(tracker.observe("$firstScreen\n• Opened this session in Codex Desktop."))
        assertFalse(tracker.observe("$firstScreen\n• Opened this session in Codex Desktop."))
    }

    @Test
    fun `allows a later handoff after old terminal output scrolls away`() {
        val tracker = DesktopHandoffTracker()

        assertTrue(tracker.observe("• Opened this session in Codex Desktop."))
        assertFalse(tracker.observe("Only newer terminal output remains"))
        assertTrue(tracker.observe("• Opened this session in Codex Desktop."))
    }

    @Test
    fun `orders desktop refresh before ide recovery without making them exclusive`() {
        assertEquals(
            listOf(TerminalMonitorAction.REFRESH_DESKTOP, TerminalMonitorAction.ENABLE_IDE),
            terminalMonitorActions(
                newDesktopHandoff = true,
                desktopRefreshEnabled = true,
                enableIdeMode = true,
            ),
        )
    }

    @Test
    fun `rapidly polls for five seconds after enter`() {
        val interaction = TerminalEnterInteraction(
            enteredAtMillis = 1_000L,
            fastPollDurationMillis = 5_000L,
            readyTimeoutMillis = 180_000L,
        )

        interaction.observe(1_000L, terminalReady = true, ideContextOn = true, manageIdeMode = true)

        assertEquals(250L, interaction.pollMillis(1_000L))
        assertFalse(interaction.isComplete(5_999L, terminalReady = true, ideContextOn = true, manageIdeMode = true))
        assertTrue(interaction.isComplete(6_000L, terminalReady = true, ideContextOn = true, manageIdeMode = true))
    }

    @Test
    fun `waits indefinitely while idle when enter tracking is available`() {
        assertEquals(
            0L,
            monitorPollMillis(
                initialIdeMonitoring = false,
                initialEnableDeadlineMillis = 0L,
                enterInteraction = null,
                hasKeyActivityTracker = true,
                nowMillis = 0L,
            ),
        )
        assertEquals(
            1_500L,
            monitorPollMillis(
                initialIdeMonitoring = false,
                initialEnableDeadlineMillis = 0L,
                enterInteraction = null,
                hasKeyActivityTracker = false,
                nowMillis = 0L,
            ),
        )
    }

    @Test
    fun `waits up to 180 seconds when terminal leaves ready state`() {
        val interaction = TerminalEnterInteraction(
            enteredAtMillis = 1_000L,
            fastPollDurationMillis = 5_000L,
            readyTimeoutMillis = 180_000L,
        )

        interaction.observe(2_000L, terminalReady = false, ideContextOn = false, manageIdeMode = true)

        assertFalse(interaction.isComplete(6_000L, terminalReady = false, ideContextOn = false, manageIdeMode = true))
        assertEquals(1_500L, interaction.pollMillis(6_000L))
        assertFalse(interaction.isComplete(181_999L, terminalReady = false, ideContextOn = false, manageIdeMode = true))
        assertTrue(interaction.isComplete(182_000L, terminalReady = false, ideContextOn = false, manageIdeMode = true))
    }

    @Test
    fun `requests ide recovery after ready returns without ide indicator`() {
        val interaction = TerminalEnterInteraction(
            enteredAtMillis = 1_000L,
            fastPollDurationMillis = 5_000L,
            readyTimeoutMillis = 180_000L,
        )
        interaction.observe(2_000L, terminalReady = false, ideContextOn = false, manageIdeMode = true)
        interaction.observe(4_000L, terminalReady = true, ideContextOn = false, manageIdeMode = true)

        assertTrue(
            interaction.shouldEnableIdeMode(
                terminalReady = true,
                ideContextOn = false,
                manageIdeMode = true,
            ),
        )
        assertFalse(interaction.isComplete(6_000L, terminalReady = true, ideContextOn = false, manageIdeMode = true))
        assertTrue(interaction.isComplete(6_000L, terminalReady = true, ideContextOn = true, manageIdeMode = true))
    }

    @Test
    fun `does not wait or retry ide recovery after ide management is disabled`() {
        val interaction = TerminalEnterInteraction(
            enteredAtMillis = 1_000L,
            fastPollDurationMillis = 5_000L,
            readyTimeoutMillis = 180_000L,
        )

        interaction.observe(2_000L, terminalReady = false, ideContextOn = false, manageIdeMode = false)

        assertFalse(
            interaction.shouldEnableIdeMode(
                terminalReady = true,
                ideContextOn = false,
                manageIdeMode = false,
            ),
        )
        assertTrue(
            interaction.isComplete(
                6_000L,
                terminalReady = false,
                ideContextOn = false,
                manageIdeMode = false,
            ),
        )
    }

    @Test
    fun `accepts enter only from current terminal component tree`() {
        val terminal = JPanel()
        val terminalInput = JTextField()
        val editor = JPanel()
        val editorInput = JTextField()
        terminal.add(terminalInput)
        editor.add(editorInput)

        assertTrue(isTerminalEnterKeyPress(keyPress(terminalInput, KeyEvent.VK_ENTER), listOf(terminal)))
        assertFalse(isTerminalEnterKeyPress(keyPress(terminalInput, KeyEvent.VK_A), listOf(terminal)))
        assertFalse(isTerminalEnterKeyPress(keyPress(editorInput, KeyEvent.VK_ENTER), listOf(terminal)))
        assertFalse(
            isTerminalEnterKeyPress(
                KeyEvent(
                    terminalInput,
                    KeyEvent.KEY_RELEASED,
                    0L,
                    0,
                    KeyEvent.VK_ENTER,
                    KeyEvent.CHAR_UNDEFINED,
                ),
                listOf(terminal),
            ),
        )
    }

    private fun readTestFrame(channel: SocketChannel): JsonObject {
        val length = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        readTestFully(channel, length)
        length.flip()
        val payload = ByteBuffer.allocate(length.int)
        readTestFully(channel, payload)
        payload.flip()
        return JsonParser.parseString(StandardCharsets.UTF_8.decode(payload).toString()).asJsonObject
    }

    private fun writeTestFrame(channel: SocketChannel, message: Any) {
        val payload = Gson().toJson(message).toByteArray(StandardCharsets.UTF_8)
        val frame = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        frame.putInt(payload.size).put(payload).flip()
        while (frame.hasRemaining()) {
            channel.write(frame)
        }
    }

    private fun readTestFully(channel: SocketChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            check(channel.read(buffer) >= 0) { "IPC test connection closed early" }
        }
    }

    private fun keyPress(source: JTextField, keyCode: Int): KeyEvent {
        return KeyEvent(
            source,
            KeyEvent.KEY_PRESSED,
            0L,
            0,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )
    }

    private class ModernTerminalWidget(private val running: Boolean) {
        fun isCommandRunning(): Boolean = running
    }

    private class ClassicTerminalWidget(private val running: Boolean) {
        fun hasRunningCommands(): Boolean = running
    }

    private class WidgetAdapter(private val widget: Any) {
        fun asNewWidget(): Any = widget
    }

    private class TerminalView(state: String) {
        private val shellIntegrationDeferred = CompletedDeferred(ShellIntegration(state))

        fun getShellIntegrationDeferred(): CompletedDeferred = shellIntegrationDeferred
    }

    private class TerminalViewWithPendingShellIntegration {
        fun getShellIntegrationDeferred(): PendingDeferred = PendingDeferred()
    }

    private class TerminalViewWithRunningFlag(state: String, private val running: Boolean) {
        private val shellIntegrationDeferred = CompletedDeferred(ShellIntegration(state))

        fun getShellIntegrationDeferred(): CompletedDeferred = shellIntegrationDeferred

        fun isCommandRunning(): Boolean = running
    }

    private class CompletedDeferred(private val value: Any) {
        fun isCompleted(): Boolean = true

        fun getCompleted(): Any = value
    }

    private class PendingDeferred {
        fun isCompleted(): Boolean = false
    }

    private class ShellIntegration(state: String) {
        private val outputStatus = StateFlow(NamedState(state))

        fun getOutputStatus(): StateFlow = outputStatus
    }

    private class StateFlow(private val state: Any) {
        fun getValue(): Any = state
    }

    private class NamedState(private val name: String) {
        override fun toString(): String = name
    }
}
