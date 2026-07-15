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

internal class DesktopHandoffTracker {
    private var observedCount = 0

    fun observe(terminalText: String): Boolean {
        val currentCount = terminalText.lineSequence().count(::isDesktopHandoffLine)
        val newHandoffSeen = currentCount > observedCount
        observedCount = currentCount
        return newHandoffSeen
    }
}

internal fun isDesktopHandoffLine(line: String): Boolean {
    return when (line.trim()) {
        CODEX_DESKTOP_HANDOFF_MESSAGE,
        "• $CODEX_DESKTOP_HANDOFF_MESSAGE",
        -> true

        else -> false
    }
}

internal const val CODEX_DESKTOP_HANDOFF_MESSAGE = "Opened this session in Codex Desktop."
