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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.FutureTask

class TerminalProcessDetectionTest {
    @Test
    fun `detects command state from modern terminal widget`() {
        assertEquals(
            TerminalProcessState.RUNNING,
            detectTerminalProcessState(ModernTerminalWidget(true), directProcess = false),
        )
        assertEquals(
            TerminalProcessState.STOPPED,
            detectTerminalProcessState(ModernTerminalWidget(false), directProcess = false),
        )
    }

    @Test
    fun `detects command state from classic terminal widget`() {
        assertEquals(
            TerminalProcessState.RUNNING,
            detectTerminalProcessState(ClassicTerminalWidget(true), directProcess = false),
        )
        assertEquals(
            TerminalProcessState.STOPPED,
            detectTerminalProcessState(ClassicTerminalWidget(false), directProcess = false),
        )
    }

    @Test
    fun `detects direct process state from terminal view`() {
        assertEquals(
            TerminalProcessState.RUNNING,
            detectTerminalProcessState(TerminalView("Running"), directProcess = true),
        )
        assertEquals(
            TerminalProcessState.RUNNING,
            detectTerminalProcessState(TerminalView("NotStarted"), directProcess = true),
        )
        assertEquals(
            TerminalProcessState.STOPPED,
            detectTerminalProcessState(TerminalView("Terminated"), directProcess = true),
        )
    }

    @Test
    fun `checks the new widget adapter when present`() {
        assertEquals(
            TerminalProcessState.STOPPED,
            detectTerminalProcessState(WidgetAdapter(ModernTerminalWidget(false)), directProcess = false),
        )
    }

    @Test
    fun `keeps unknown terminal implementations reusable`() {
        assertEquals(
            TerminalProcessState.UNKNOWN,
            detectTerminalProcessState(Any(), directProcess = false),
        )
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
        private val stateFlow = StateFlow(NamedState(state))

        fun getSessionState(): StateFlow = stateFlow
    }

    private class StateFlow(private val state: Any) {
        fun getValue(): Any = state
    }

    private class NamedState(private val name: String) {
        override fun toString(): String = name
    }
}
