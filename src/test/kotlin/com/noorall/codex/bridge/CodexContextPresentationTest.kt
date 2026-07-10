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
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexContextPresentationTest {
    @Test
    fun `hides status text without an active Codex session`() {
        val state = CodexContextUiState(
            sessionActive = false,
            context = contextWithSelection("Example.kt", Range(Position(4, 2), Position(8, 1))),
        )

        assertEquals("", state.statusBarText())
    }

    @Test
    fun `shows when the active Codex session has no selection`() {
        val context = IdeContext(
            activeFile = activeFile("Example.kt", emptyList()),
            openTabs = emptyList(),
        )

        assertEquals(
            "Codex: No selection",
            CodexContextUiState(sessionActive = true, context = context).statusBarText(),
        )
    }

    @Test
    fun `shows selected file and one based line range`() {
        val context = contextWithSelection(
            "Example.kt",
            Range(Position(4, 2), Position(8, 1)),
        )

        assertEquals(
            "Codex: Example.kt:5-9",
            CodexContextUiState(sessionActive = true, context = context).statusBarText(),
        )
    }

    @Test
    fun `shows the number of multiple selections`() {
        val context = IdeContext(
            activeFile = activeFile(
                "Example.kt",
                listOf(
                    Range(Position(1, 0), Position(1, 4)),
                    Range(Position(3, 0), Position(3, 4)),
                ),
            ),
            openTabs = emptyList(),
        )

        assertEquals(
            "Codex: Example.kt (2 selections)",
            CodexContextUiState(sessionActive = true, context = context).statusBarText(),
        )
    }

    @Test
    fun `keeps the IDE context JSON contract unchanged`() {
        val json = Gson().toJsonTree(
            contextWithSelection(
                "Example.kt",
                Range(Position(4, 2), Position(8, 1)),
            ),
        ).asJsonObject

        assertEquals(setOf("activeFile", "openTabs", "processEnv"), json.keySet())
        assertEquals(
            setOf("label", "path", "fsPath", "language", "selection", "activeSelectionContent", "selections"),
            json.getAsJsonObject("activeFile").keySet(),
        )
    }

    private fun contextWithSelection(label: String, selection: Range): IdeContext {
        return IdeContext(
            activeFile = activeFile(label, listOf(selection)),
            openTabs = listOf(FileDescriptor(label, "src/$label", "/project/src/$label")),
        )
    }

    private fun activeFile(label: String, selections: List<Range>): ActiveFile {
        val selection = selections.firstOrNull() ?: Range(Position(0, 0), Position(0, 0))
        return ActiveFile(
            label = label,
            path = "src/$label",
            fsPath = "/project/src/$label",
            language = "Kotlin",
            selection = selection,
            activeSelectionContent = if (selections.size == 1) "selected code" else "",
            selections = selections,
        )
    }
}
