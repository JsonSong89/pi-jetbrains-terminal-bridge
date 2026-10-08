package com.piterminal.bridge.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.Clipboard
import com.piterminal.bridge.PiFileRefs
import com.piterminal.bridge.conversations.PiConversation
import com.piterminal.bridge.conversations.PiConversationService
import com.piterminal.bridge.conversations.PiUserMessage
import com.piterminal.bridge.conversations.PiConversationService.ChangeKind
import com.piterminal.bridge.settings.PiSettings
import javax.swing.TransferHandler
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.basic.ComboPopup

/**
 * Right-side conversation manager: history dropdown, user send log, draft input.
 */
class PiConversationPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val conversations = PiConversationService.getInstance(project)
    private val combo = ComboBox<PiConversation>()
    private val statusLabel = JBLabel()
    private val historyList = JPanel()
    private val historyScroll = JBScrollPane()
    private val inputArea = JBTextArea()
    private val sendButton = JButton("Send")
    private val copyInputButton = JButton(AllIcons.Actions.Copy).apply {
        toolTipText = "Copy input to clipboard"
    }
    private val copyWorkspaceButton = JButton(AllIcons.General.OpenDisk).apply {
        toolTipText = "Append open workspace files to input"
    }
    private val timeFormat = SimpleDateFormat("HH:mm")
    private companion object {
        const val COMBO_DELETE_HIT_PX = 22
    }
    private var syncing = false
    private var comboDeleteClickInstalled = false
    private var suppressComboAction = false
    @Volatile
    private var disposed = false

    private val listener = PiConversationService.Listener { event ->
        SwingUtilities.invokeLater {
            if (disposed) return@invokeLater
            refreshUi()
            if (event.kind == ChangeKind.DRAFT_APPENDED) {
                inputArea.requestFocusInWindow()
                inputArea.caretPosition = inputArea.document.length
            }
        }
    }

    private val settingsListener = PiSettings.ChangeListener {
        SwingUtilities.invokeLater {
            if (!disposed) applyConversationFont()
        }
    }

    init {
        conversations.addListener(listener)
        PiSettings.getInstance().addChangeListener(settingsListener)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
                SwingUtilities.invokeLater {
                    if (!disposed) applyConversationFont()
                }
            })
        setContent(buildUi())
        applyConversationFont()
        refreshUi()
    }

    private fun buildUi(): JPanel {
        combo.renderer = ConversationComboRenderer()
        combo.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                installComboDeleteClickHandler()
                if (!comboDeleteClickInstalled) {
                    SwingUtilities.invokeLater { installComboDeleteClickHandler() }
                }
            }
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}
            override fun popupMenuCanceled(e: PopupMenuEvent) {}
        })
        combo.addActionListener {
            if (syncing || suppressComboAction) return@addActionListener
            val selected = combo.selectedItem as? PiConversation ?: return@addActionListener
            persistDraft()
            conversations.setActive(selected.id)
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "PiConversationsToolbar",
            DefaultActionGroup().apply {
                add(NewConversationAction())
                add(RenameConversationAction())
                add(DeleteConversationAction())
                addSeparator()
                add(CheckTerminalAction())
                add(FocusTerminalAction())
            },
            true
        )
        toolbar.targetComponent = this

        val top = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(6, 8, 4, 8)
            add(combo, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }

        statusLabel.border = JBUI.Borders.empty(0, 8, 6, 8)
        statusLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        statusLabel.font = JBUI.Fonts.smallFont()

        val header = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.SOUTH)
        }

        historyList.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        historyScroll.apply {
            setViewportView(historyList)
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            minimumSize = Dimension(0, JBUI.scale(80))
            border = JBUI.Borders.empty(4, 8, 4, 8)
        }

        inputArea.apply {
            lineWrap = true
            wrapStyleWord = true
            emptyText.text = "Message the active conversation…"
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = persistDraft()
                override fun removeUpdate(e: DocumentEvent) = persistDraft()
                override fun changedUpdate(e: DocumentEvent) = persistDraft()
            })
            val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
            val ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)
            val shiftEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
            for (map in listOf(
                getInputMap(JComponent.WHEN_FOCUSED),
                getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            )) {
                map.put(enter, "pi-enter")
                map.put(ctrlEnter, "pi-ctrl-enter")
                map.put(shiftEnter, "insert-break")
            }
            actionMap.put("pi-enter", object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) {
                    if (PiSettings.getInstance().state.sendWithCtrlEnter) {
                        inputArea.replaceSelection("\n")
                    } else {
                        sendDraft()
                    }
                }
            })
            actionMap.put("pi-ctrl-enter", object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) {
                    sendDraft()
                }
            })
        }
        // IdeEventQueue handles keymap actions before Swing InputMap; bind
        // Ctrl+Enter on this component so the IDE does not swallow it.
        object : AnAction(), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                sendDraft()
            }
        }.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)),
            inputArea,
            this
        )
        installInputContextMenu()
        installInputFileDrop()

        sendButton.addActionListener { sendDraft() }
        copyInputButton.addActionListener { copyInputToClipboard() }
        copyWorkspaceButton.addActionListener { appendOpenWorkspaceFiles() }

        val buttonRow = JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                add(copyWorkspaceButton)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
                add(copyInputButton)
                add(sendButton)
            }, BorderLayout.EAST)
        }

        val inputPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.namedColor("Separator.separatorColor", JBColor.border()), 1, 0, 0, 0),
                JBUI.Borders.empty(10, 8, 8, 8)
            )
            preferredSize = Dimension(0, JBUI.scale(350))
            minimumSize = Dimension(0, JBUI.scale(140))
            add(JBLabel("Input").apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                border = JBUI.Borders.emptyBottom(4)
            }, BorderLayout.NORTH)
            add(JBScrollPane(inputArea), BorderLayout.CENTER)
            add(buttonRow, BorderLayout.SOUTH)
        }

        val south = JPanel(BorderLayout()).apply {
            add(javax.swing.JSeparator(), BorderLayout.NORTH)
            add(inputPanel, BorderLayout.CENTER)
        }

        val splitter = JBSplitter(true, 0.52f).apply {
            firstComponent = historyScroll
            secondComponent = south
            setHonorComponentsMinimumSize(true)
        }

        return JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
    }

    private fun refreshUi() {
        syncing = true
        try {
            val items = conversations.all()
            val active = conversations.active()
            val model = DefaultComboBoxModel<PiConversation>()
            items.forEach { model.addElement(it) }
            combo.model = model
            combo.selectedItem = active
            combo.isEnabled = items.isNotEmpty()

            rebuildHistory(active)

            if (inputArea.text != (active?.draft ?: "")) {
                inputArea.text = active?.draft ?: ""
            }

            val alive = active != null && conversations.isTerminalAlive(active.id)
            val working = active != null && conversations.isAgentWorking(active.id)
            val currentModel = active?.let { conversations.currentModel(it.id) }
            statusLabel.text = when {
                active == null -> "No active conversation"
                working -> "${active.title} · agent working${currentModel?.let { " · $it" } ?: ""}"
                alive -> "${active.title} · terminal running${currentModel?.let { " · $it" } ?: ""}"
                else -> "${active.title} · terminal closed"
            }
            val hasActive = active != null
            sendButton.isEnabled = hasActive
            copyInputButton.isEnabled = hasActive
            copyWorkspaceButton.isEnabled = hasActive
            inputArea.isEnabled = hasActive
            applyConversationFont()
        } finally {
            syncing = false
        }
    }

    private fun applyConversationFont() {
        val font = PiSettings.getInstance().conversationFont()
        inputArea.font = font
        for (component in historyList.components) {
            val text = (component as? JComponent)?.getClientProperty("pi-history-text") as? JBTextArea
            text?.font = font
        }
    }

    private fun rebuildHistory(conversation: PiConversation?) {
        historyList.removeAll()
        val messages = conversation?.messages.orEmpty()
        if (messages.isEmpty()) {
            historyList.add(JBLabel("User messages will appear here").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(8, 2)
            })
        } else {
            val conversationId = conversation!!.id
            messages.forEachIndexed { index, message ->
                if (index > 0) {
                    historyList.add(Box.createVerticalStrut(JBUI.scale(8)))
                }
                historyList.add(historyCard(conversationId, index, message))
            }
        }
        historyList.revalidate()
        historyList.repaint()
        SwingUtilities.invokeLater {
            val bar = historyScroll.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun historyCard(conversationId: String, index: Int, message: PiUserMessage): JComponent {
        val time = JBLabel(timeFormat.format(Date(message.timestamp))).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }
        val body = JBTextArea(message.text).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = PiSettings.getInstance().conversationFont()
            border = JBUI.Borders.empty(4, 0, 2, 0)
        }
        val copy = iconButton(AllIcons.Actions.Copy, "Copy") {
            CopyPasteManager.getInstance().setContents(StringSelection(message.text))
        }
        val delete = iconButton(AllIcons.General.Remove, "Delete") {
            conversations.deleteMessage(conversationId, index)
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(delete)
            add(copy)
        }
        body.rows = message.text.lines().size.coerceIn(1, 20)
        return object : JPanel(BorderLayout()) {
            init {
                putClientProperty("pi-history-text", body)
                alignmentX = Component.LEFT_ALIGNMENT
                isOpaque = true
                background = JBColor.namedColor("ToolWindow.background", JBColor(0xF2F2F2, 0x3C3F41))
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor.namedColor("Separator.separatorColor", JBColor.border()), 1),
                    JBUI.Borders.empty(6, 8, 2, 6)
                )
                add(time, BorderLayout.NORTH)
                add(body, BorderLayout.CENTER)
                add(actions, BorderLayout.SOUTH)
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun iconButton(icon: javax.swing.Icon, tip: String, onClick: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tip
            isBorderPainted = false
            isContentAreaFilled = false
            isOpaque = false
            isFocusable = false
            margin = JBUI.emptyInsets()
            preferredSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    isOpaque = true
                    isContentAreaFilled = true
                    background = JBUI.CurrentTheme.ActionButton.hoverBackground()
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    isOpaque = false
                    isContentAreaFilled = false
                    background = null
                    repaint()
                }
            })
            addActionListener { onClick() }
        }
    }

    private fun persistDraft() {
        if (syncing) return
        val id = conversations.active()?.id ?: return
        conversations.updateDraft(id, inputArea.text)
    }

    private fun sendDraft() {
        persistDraft()
        conversations.sendDraft()
    }

    private fun copyInputToClipboard() {
        persistDraft()
        CopyPasteManager.getInstance().setContents(StringSelection(inputArea.text))
    }

    private fun installInputContextMenu() {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Cut", "Cut selected text", AllIcons.Actions.MenuCut), DumbAware {
                override fun actionPerformed(e: AnActionEvent) { inputArea.cut() }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = inputArea.isEnabled && !inputArea.selectedText.isNullOrEmpty()
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
            add(object : AnAction("Copy", "Copy selected text", AllIcons.Actions.Copy), DumbAware {
                override fun actionPerformed(e: AnActionEvent) { inputArea.copy() }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = inputArea.isEnabled && !inputArea.selectedText.isNullOrEmpty()
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
            add(object : AnAction("Paste", "Paste clipboard text", AllIcons.Actions.MenuPaste), DumbAware {
                override fun actionPerformed(e: AnActionEvent) { inputArea.paste() }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = inputArea.isEnabled
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
            addSeparator()
            add(object : AnAction("Insert current file", "Append @path for the active editor file", AllIcons.FileTypes.Text), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val file = currentEditorFile() ?: return
                    conversations.appendToDraft(PiFileRefs.atFile(project, file))
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = inputArea.isEnabled && currentEditorFile() != null
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
            add(object : AnAction("Insert selection", "Append @path#L for the editor selection", AllIcons.Actions.ShowSource), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
                    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
                    conversations.appendToDraft(PiFileRefs.atSelection(project, editor, file))
                }
                override fun update(e: AnActionEvent) {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    e.presentation.isEnabled = inputArea.isEnabled &&
                        editor != null &&
                        FileDocumentManager.getInstance().getFile(editor.document) != null
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
            add(object : AnAction("Insert open files", "Append the workspace-open-files block", AllIcons.General.OpenDisk), DumbAware {
                override fun actionPerformed(e: AnActionEvent) { appendOpenWorkspaceFiles() }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = inputArea.isEnabled
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
            addSeparator()
            add(object : AnAction("Send", "Send the draft to Pi", AllIcons.Actions.Execute), DumbAware {
                override fun actionPerformed(e: AnActionEvent) { sendDraft() }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = inputArea.isEnabled && inputArea.text.isNotBlank()
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
            add(object : AnAction("Clear draft", "Clear the input", AllIcons.General.Reset), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    inputArea.text = ""
                    persistDraft()
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = inputArea.isEnabled && inputArea.text.isNotEmpty()
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
        }
        PopupHandler.installPopupMenu(inputArea, group, "PiBridge.InputArea")
    }

    private fun installInputFileDrop() {
        val textHandler = inputArea.transferHandler
        inputArea.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                if (!inputArea.isEnabled) return false
                if (droppedFiles(support.transferable).isNotEmpty()) return true
                return textHandler?.canImport(support) == true
            }

            override fun importData(support: TransferSupport): Boolean {
                val files = droppedFiles(support.transferable)
                if (files.isNotEmpty()) {
                    val refs = PiFileRefs.atFiles(project, files)
                    if (refs.isNotBlank()) {
                        conversations.appendToDraft(refs, block = true)
                        return true
                    }
                }
                return textHandler?.importData(support) == true
            }

            override fun canImport(comp: JComponent, flavors: Array<DataFlavor>): Boolean {
                if (!inputArea.isEnabled) return false
                if (FileCopyPasteUtil.isFileListFlavorAvailable(flavors)) return true
                return textHandler?.canImport(comp, flavors) == true
            }

            override fun importData(comp: JComponent, t: Transferable): Boolean {
                val files = droppedFiles(t)
                if (files.isNotEmpty()) {
                    val refs = PiFileRefs.atFiles(project, files)
                    if (refs.isNotBlank()) {
                        conversations.appendToDraft(refs, block = true)
                        return true
                    }
                }
                return textHandler?.importData(comp, t) == true
            }

            override fun getSourceActions(c: JComponent): Int =
                textHandler?.getSourceActions(c) ?: COPY

            override fun exportToClipboard(comp: JComponent, clip: Clipboard, action: Int) {
                textHandler?.exportToClipboard(comp, clip, action)
            }
        }
    }

    private fun droppedFiles(transferable: Transferable): List<VirtualFile> {
        val local = LocalFileSystem.getInstance()
        val fromList = try {
            val ioFiles = FileCopyPasteUtil.getFileList(transferable) ?: emptyList()
            ioFiles.mapNotNull { io ->
                local.refreshAndFindFileByIoFile(io) ?: local.findFileByIoFile(io)
            }
        } catch (_: Exception) {
            emptyList()
        }
        if (fromList.isNotEmpty()) return fromList

        val found = mutableListOf<VirtualFile>()
        for (flavor in transferable.transferDataFlavors) {
            val data = try {
                transferable.getTransferData(flavor)
            } catch (_: Exception) {
                continue
            }
            when (data) {
                is VirtualFile -> found.add(data)
                is Array<*> -> found.addAll(data.filterIsInstance<VirtualFile>())
                is Collection<*> -> found.addAll(data.filterIsInstance<VirtualFile>())
            }
        }
        return found
    }

    private fun currentEditorFile(): VirtualFile? {
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let { return it }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return FileDocumentManager.getInstance().getFile(editor.document)
    }

    private fun appendOpenWorkspaceFiles() {
        persistDraft()
        val projectPath = project.basePath ?: ""
        val paths = FileEditorManager.getInstance(project).openFiles
            .filter { it.isValid && !it.isDirectory }
            .map { file ->
                if (file.path.startsWith(projectPath)) {
                    file.path.removePrefix(projectPath).removePrefix("/")
                } else {
                    file.path
                }
            }
            .distinct()
        if (paths.isEmpty()) {
            Messages.showInfoMessage(project, "No open workspace files.", "Pi Bridge")
            return
        }
        conversations.appendWorkspaceFiles(paths)
    }

    private fun installComboDeleteClickHandler() {
        if (comboDeleteClickInstalled) return
        val popup = combo.ui.getAccessibleChild(combo, 0) as? ComboPopup ?: return
        val list = popup.list
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (handleComboDeleteClick(list, e)) e.consume()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (suppressComboAction) e.consume()
            }
        })
        comboDeleteClickInstalled = true
    }

    private fun handleComboDeleteClick(list: JList<*>, e: MouseEvent): Boolean {
        val index = list.locationToIndex(e.point)
        if (index < 0) return false
        val conversation = list.model.getElementAt(index) as? PiConversation ?: return false
        if (conversations.isTerminalAlive(conversation.id)) return false
        val cell = list.getCellBounds(index, index) ?: return false
        if (e.x < cell.x + cell.width - JBUI.scale(COMBO_DELETE_HIT_PX)) return false
        // Block the combo's select-to-activate path so a closed session is
        // not relaunched just to be deleted.
        suppressComboAction = true
        combo.hidePopup()
        conversations.deleteConversation(conversation.id)
        SwingUtilities.invokeLater { suppressComboAction = false }
        return true
    }

    override fun dispose() {
        disposed = true
        persistDraft()
        conversations.removeListener(listener)
        PiSettings.getInstance().removeChangeListener(settingsListener)
    }

    private inner class ConversationComboRenderer : JPanel(BorderLayout()), javax.swing.ListCellRenderer<PiConversation> {
        private val title = JLabel()
        private val deleteHint = JLabel("×").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            border = JBUI.Borders.emptyLeft(6)
        }

        init {
            isOpaque = true
            add(title, BorderLayout.CENTER)
            add(deleteHint, BorderLayout.EAST)
            border = JBUI.Borders.empty(2, 6)
        }

        override fun getListCellRendererComponent(
            list: JList<out PiConversation>,
            value: PiConversation?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val conversation = value
            val alive = conversation != null && conversations.isTerminalAlive(conversation.id)
            val working = conversation != null && conversations.isAgentWorking(conversation.id)
            val suffix = when {
                conversation == null -> ""
                working -> " ●"
                !alive -> " (closed)"
                else -> ""
            }
            title.text = (conversation?.title ?: "No conversations") + suffix
            deleteHint.isVisible = index >= 0 && conversation != null && !alive
            val selectedBg = list.selectionBackground
            val selectedFg = list.selectionForeground
            val normalBg = list.background
            val normalFg = if (conversation != null && !alive && !working) JBColor.GRAY else list.foreground
            background = if (isSelected) selectedBg else normalBg
            title.foreground = if (isSelected) selectedFg else normalFg
            deleteHint.foreground = if (isSelected) selectedFg else JBUI.CurrentTheme.ContextHelp.FOREGROUND
            return this
        }
    }

    private inner class NewConversationAction : AnAction("New Conversation", "Start a new Pi conversation", AllIcons.General.Add), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            persistDraft()
            conversations.createConversation()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class RenameConversationAction : AnAction("Rename Conversation", "Rename the active conversation", AllIcons.Actions.Edit), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            val active = conversations.active() ?: return
            val newTitle = Messages.showInputDialog(
                project,
                "Conversation name",
                "Rename Conversation",
                Messages.getQuestionIcon(),
                active.title,
                null
            )?.trim() ?: return
            if (newTitle.isEmpty()) return
            // Uniqueness enforced by the service; it warns on duplicates.
            conversations.rename(active.id, newTitle)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = conversations.active() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class DeleteConversationAction : AnAction("Delete Conversation", "Close the terminal and delete this conversation", AllIcons.General.Remove), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            val id = conversations.active()?.id ?: return
            conversations.deleteConversation(id)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = conversations.active() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class CheckTerminalAction : AnAction(
        "Restore Terminal",
        "Reopen a closed Pi terminal and resume its session",
        AllIcons.Actions.Refresh
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            val id = conversations.active()?.id ?: return
            conversations.checkTerminal(id)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = conversations.active() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class FocusTerminalAction : AnAction("Focus Terminal", "Focus the terminal bound to this conversation", AllIcons.General.Locate), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            val id = conversations.active()?.id ?: return
            conversations.focusTerminal(id)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = conversations.active() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }
}
