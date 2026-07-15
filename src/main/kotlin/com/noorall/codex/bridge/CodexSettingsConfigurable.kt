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

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JPanel

class CodexSettingsConfigurable : Configurable {
    private var commandField: JBTextField? = null
    private var autoEnableIdeContextBox: JBCheckBox? = null
    private var autoRefreshDesktopBox: JBCheckBox? = null
    private var component: JPanel? = null

    override fun getDisplayName(): String = "Codex CLI Bridge"

    override fun createComponent(): JComponent {
        val field = JBTextField()
        commandField = field
        val autoEnableBox = JBCheckBox(
            "Run /ide on automatically after Codex is ready.",
        )
        autoEnableIdeContextBox = autoEnableBox
        val autoRefreshBox = JBCheckBox(
            "Refresh Codex Desktop automatically after /app handoff.",
        ).apply {
            toolTipText = "Uses Codex Desktop local IPC. Failures are ignored silently."
        }
        autoRefreshDesktopBox = autoRefreshBox

        val panel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(12)
        }

        val labelConstraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = JBUI.insetsRight(12)
        }
        panel.add(JBLabel("Codex command:"), labelConstraints)

        val fieldConstraints = GridBagConstraints().apply {
            gridx = 1
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
        }
        panel.add(field, fieldConstraints)

        val examplesConstraints = GridBagConstraints().apply {
            gridx = 1
            gridy = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insetsTop(8)
        }
        panel.add(
            JBLabel("Examples: codex, /usr/local/bin/codex, npx @openai/codex, wsl -d Ubuntu -- codex"),
            examplesConstraints,
        )

        val autoEnableLabelConstraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 2
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(12, 0, 0, 12)
        }
        panel.add(JBLabel("IDE context:"), autoEnableLabelConstraints)

        val autoEnableBoxConstraints = GridBagConstraints().apply {
            gridx = 1
            gridy = 2
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insetsTop(12)
        }
        panel.add(autoEnableBox, autoEnableBoxConstraints)

        val desktopHandoffLabelConstraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 3
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(12, 0, 0, 12)
        }
        panel.add(JBLabel("Desktop handoff:"), desktopHandoffLabelConstraints)

        val desktopHandoffBoxConstraints = GridBagConstraints().apply {
            gridx = 1
            gridy = 3
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insetsTop(12)
        }
        panel.add(autoRefreshBox, desktopHandoffBoxConstraints)

        val fillerConstraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 4
            gridwidth = 2
            weightx = 1.0
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        }
        panel.add(Box.createGlue(), fillerConstraints)

        component = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val settings = service<CodexSettingsService>()
        return commandField?.text?.trim() != settings.codexCommand ||
            autoEnableIdeContextBox?.isSelected != settings.autoEnableIdeContext ||
            autoRefreshDesktopBox?.isSelected != settings.autoRefreshDesktopAfterAppHandoff
    }

    override fun apply() {
        val settings = service<CodexSettingsService>()
        settings.codexCommand = commandField?.text?.trim().orEmpty()
        settings.autoEnableIdeContext = autoEnableIdeContextBox?.isSelected ?: true
        settings.autoRefreshDesktopAfterAppHandoff = autoRefreshDesktopBox?.isSelected ?: true
    }

    override fun reset() {
        val settings = service<CodexSettingsService>()
        commandField?.text = settings.codexCommand
        autoEnableIdeContextBox?.isSelected = settings.autoEnableIdeContext
        autoRefreshDesktopBox?.isSelected = settings.autoRefreshDesktopAfterAppHandoff
    }

    override fun disposeUIResources() {
        commandField = null
        autoEnableIdeContextBox = null
        autoRefreshDesktopBox = null
        component = null
    }
}
