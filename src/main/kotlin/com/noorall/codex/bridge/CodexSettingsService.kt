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

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "CodexBridgeSettings", storages = [Storage("codex-bridge.xml")])
class CodexSettingsService : PersistentStateComponent<CodexSettingsState> {
    private var state = CodexSettingsState()

    override fun getState(): CodexSettingsState = state

    override fun loadState(state: CodexSettingsState) {
        this.state = state
    }

    var codexCommand: String
        get() = state.codexCommand.ifBlank { DEFAULT_CODEX_COMMAND }
        set(value) {
            state.codexCommand = value.ifBlank { DEFAULT_CODEX_COMMAND }
        }

    var autoEnableIdeContext: Boolean
        get() = state.autoEnableIdeContext
        set(value) {
            state.autoEnableIdeContext = value
        }

    var autoRefreshDesktopAfterAppHandoff: Boolean
        get() = state.autoRefreshDesktopAfterAppHandoff
        set(value) {
            state.autoRefreshDesktopAfterAppHandoff = value
        }
}

data class CodexSettingsState(
    var codexCommand: String = DEFAULT_CODEX_COMMAND,
    var autoEnableIdeContext: Boolean = true,
    var autoRefreshDesktopAfterAppHandoff: Boolean = true,
)

const val DEFAULT_CODEX_COMMAND = "codex"
