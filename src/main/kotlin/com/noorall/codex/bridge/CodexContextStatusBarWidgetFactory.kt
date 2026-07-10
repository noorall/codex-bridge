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

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

class CodexContextStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = CODEX_CONTEXT_WIDGET_ID

    override fun getDisplayName(): String = "Codex IDE Context"

    override fun isAvailable(project: Project): Boolean = !project.isDisposed

    override fun createWidget(project: Project): StatusBarWidget {
        return CodexContextStatusBarWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    override fun isEnabledByDefault(): Boolean = true
}

private class CodexContextStatusBarWidget(
    private val project: Project,
) : CustomStatusBarWidget {
    private val contextService = project.service<CodexContextService>()
    private val label = JBLabel().apply {
        border = JBUI.CurrentTheme.StatusBar.Widget.border()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
        accessibleContext.accessibleName = "Codex IDE context"
    }
    private var statusBar: StatusBar? = null

    init {
        label.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(event) && contextService.currentState().sessionActive) {
                        showContextPopup(label)
                    }
                }
            },
        )
        contextService.addListener(
            CodexContextStateListener(::updateComponent),
            this,
        )
    }

    override fun ID(): String = CODEX_CONTEXT_WIDGET_ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        updateComponent(contextService.currentState())
    }

    override fun dispose() {
        statusBar = null
    }

    private fun updateComponent(state: CodexContextUiState) {
        val update = Runnable {
            label.text = state.statusBarText()
            label.toolTipText = if (state.sessionActive) "Inspect Codex IDE context" else null
            label.accessibleContext.accessibleDescription = label.toolTipText
            label.isVisible = state.sessionActive
            label.revalidate()
            label.repaint()
            statusBar?.component?.let {
                it.revalidate()
                it.repaint()
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            update.run()
        } else {
            SwingUtilities.invokeLater(update)
        }
    }

    private fun showContextPopup(component: JComponent) {
        val state = contextService.currentState()
        if (!state.sessionActive) {
            return
        }

        val popupReference = AtomicReference<JBPopup?>()
        val content = createPopupContent(state) {
            contextService.clearEditorSelection()
            popupReference.get()?.cancel()
            Unit
        }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setTitle("Codex context")
            .setProject(project)
            .setResizable(true)
            .setMovable(false)
            .setFocusable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setMinSize(JBUI.size(440, 220))
            .createPopup()
        popupReference.set(popup)
        popup.show(JBPopupFactory.getInstance().guessBestPopupLocation(component))
    }
}

private fun createPopupContent(
    state: CodexContextUiState,
    clearSelection: () -> Unit,
): JComponent {
    val context = state.context
    val activeFile = context?.activeFile
    val selectionRow = selectionRow(activeFile, clearSelection)
    val form = FormBuilder.createFormBuilder()
        .setHorizontalGap(JBUI.scale(12))
        .setVerticalGap(JBUI.scale(6))
        .addLabeledComponent("Selection:", selectionRow)

    if (activeFile != null && activeFile.selections.size == 1 && activeFile.activeSelectionContent.isNotEmpty()) {
        form.addLabeledComponentFillVertically(
            "Code:",
            selectionPreview(activeFile.activeSelectionContent),
        )
    }

    form.addLabeledComponent(
        "Active file:",
        pathLabel(activeFile?.path ?: "None", activeFile?.fsPath),
    )
    form.addLabeledComponentFillVertically(
        "Open tabs:",
        openTabsComponent(context?.openTabs.orEmpty()),
    )
    form.addLabeledComponent(
        "Last sent:",
        JBLabel(formatLastSent(state.lastSentAtMillis)),
    )

    return form.panel.apply {
        border = JBUI.Borders.empty(8, 12, 12, 12)
    }
}

private fun selectionRow(
    activeFile: ActiveFile?,
    clearSelection: () -> Unit,
): JComponent {
    if (activeFile == null || activeFile.selections.isEmpty()) {
        return JBLabel("None")
    }

    val selectionText = if (activeFile.selections.size == 1) {
        val range = activeFile.selections.single()
        val characterCount = activeFile.selectionCharacterCount
        "${compactPath(activeFile.path)}:${range.displayLines()} ($characterCount characters)"
    } else {
        "${compactPath(activeFile.path)} (${activeFile.selections.size} selections)"
    }
    val label = JBLabel(selectionText).apply {
        toolTipText = activeFile.fsPath
    }
    val clearButton = JButton(AllIcons.Actions.Close).apply {
        toolTipText = "Clear selection"
        accessibleContext.accessibleName = toolTipText
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        margin = JBUI.emptyInsets()
        preferredSize = JBUI.size(22, 22)
        addActionListener { clearSelection() }
    }
    return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        add(label, BorderLayout.CENTER)
        add(clearButton, BorderLayout.EAST)
    }
}

private fun selectionPreview(content: String): JComponent {
    val textArea = JBTextArea(content, 10, 72).apply {
        isEditable = false
        lineWrap = false
        font = JBFont.create(java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, font.size))
        caretPosition = 0
        border = JBUI.Borders.empty(6)
    }
    return JBScrollPane(
        textArea,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
    ).apply {
        preferredSize = JBUI.size(520, 190)
    }
}

private fun openTabsComponent(openTabs: List<FileDescriptor>): JComponent {
    if (openTabs.isEmpty()) {
        return JBLabel("None")
    }

    val list = JBList(openTabs.map { it.path }).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = openTabs.size.coerceIn(1, MAX_VISIBLE_OPEN_TABS)
        border = JBUI.Borders.empty(2)
        setExpandableItemsEnabled(true)
    }
    return JBScrollPane(
        list,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
    ).apply {
        val rowHeight = list.fixedCellHeight.takeIf { it > 0 } ?: JBUI.scale(20)
        preferredSize = Dimension(JBUI.scale(520), rowHeight * list.visibleRowCount + JBUI.scale(6))
    }
}

private fun pathLabel(path: String, tooltip: String?): JBLabel {
    return JBLabel(compactPath(path)).apply {
        toolTipText = tooltip
    }
}

private fun compactPath(path: String): String {
    if (path.length <= MAX_POPUP_PATH_CHARS) {
        return path
    }
    return "..." + path.takeLast(MAX_POPUP_PATH_CHARS - 3)
}

private fun formatLastSent(timestampMillis: Long?): String {
    if (timestampMillis == null) {
        return "Not yet"
    }
    return LAST_SENT_FORMATTER.format(
        Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()),
    )
}

private const val CODEX_CONTEXT_WIDGET_ID = "CodexIdeContext"
private const val MAX_VISIBLE_OPEN_TABS = 5
private const val MAX_POPUP_PATH_CHARS = 72
private val LAST_SENT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
