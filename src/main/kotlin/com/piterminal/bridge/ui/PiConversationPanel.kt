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
import com.piterminal.bridge.PiBridgeUi
import com.piterminal.bridge.PiFileRefs
import com.piterminal.bridge.conversations.PiConversation
import com.piterminal.bridge.conversations.PiConversationService
import com.piterminal.bridge.conversations.PiUserMessage
import com.piterminal.bridge.conversations.PiConversationService.ChangeKind
import com.piterminal.bridge.settings.PiSettings
import javax.swing.TransferHandler
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ToolTipManager
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
    private val messageRail = PiMessageRail()
    private val historyCards = mutableListOf<JComponent>()

    /** Messages the user expanded; identity-keyed because messages have no id. */
    private val expandedMessages: MutableSet<PiUserMessage> =
        Collections.newSetFromMap(IdentityHashMap<PiUserMessage, Boolean>())
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
        const val COMBO_DELETE_HIT_PX = 28
        const val COLLAPSE_CHARS = 480
        const val COLLAPSED_ROWS = 4
        const val LONG_TEXT_CHARS = 20_000
        const val LONG_TEXT_ROWS = 200
        const val RAIL_TOOLTIP_CHARS = 30
        const val FLASH_MS = 300
        const val PROP_BODY = "pi-history-text"
        const val PROP_FLASH = "pi-history-flash"

        val FLASH_BACKGROUND: java.awt.Color =
            JBColor.namedColor("PiBridge.HistoryCard.flash", JBColor(0xE3EDFF, 0x2F3946))
        val LINK_FOREGROUND: java.awt.Color =
            JBColor.namedColor("Link.activeForeground", JBColor(0x2A6ED6, 0x6BA6FF))
    }

    /** Cache key that decides whether the history cards have to be rebuilt. */
    private var historySignature: Any? = null
    private var historyConversationId: String? = null
    private var remeasurePending = false
    private var syncing = false
    private var comboDeleteClickInstalled = false
    private var suppressComboAction = false
    private var comboDeleteHoverIndex = -1
    private var reopenComboPopup = false
    private var deletingFromCombo = false
    @Volatile
    private var disposed = false

    private val listener = PiConversationService.Listener { event ->
        SwingUtilities.invokeLater {
            if (disposed) return@invokeLater
            refreshUi(event.kind)
            if (event.kind == ChangeKind.DRAFT_APPENDED) {
                inputArea.requestFocusInWindow()
                inputArea.caretPosition = inputArea.document.length
            } else if (event.kind == ChangeKind.FOCUS_INPUT) {
                inputArea.requestFocusInWindow()
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
        messageRail.onJump = { index -> jumpToMessage(index) }
        messageRail.titleProvider = { index -> railTitle(index) }
        applyConversationFont()
        refreshUi()
    }

    private fun buildUi(): JPanel {
        combo.renderer = ConversationComboRenderer()
            combo.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                installComboDeleteClickHandler()
                SwingUtilities.invokeLater { installComboDeleteClickHandler() }
            }
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                comboDeleteHoverIndex = -1
            }
            override fun popupMenuCanceled(e: PopupMenuEvent) {
                comboDeleteHoverIndex = -1
            }
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
            // No right inset: the message rail sits right next to the scroll bar.
            border = JBUI.Borders.empty(4, 8, 4, 0)
        }
        historyScroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = scheduleRemeasure()
        })
        historyScroll.verticalScrollBar.addAdjustmentListener {
            if (!disposed) messageRail.update(historyCards.size, currentTopMessageIndex())
        }
        installHistoryKeyBindings()

        inputArea.apply {
            putClientProperty(PiBridgeUi.PROMPT_FOCUS_PROPERTY, true)
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

        val historyPanel = JPanel(BorderLayout()).apply {
            minimumSize = Dimension(0, JBUI.scale(80))
            add(historyScroll, BorderLayout.CENTER)
            add(messageRail, BorderLayout.EAST)
        }
        messageRail.isVisible = false

        val splitter = JBSplitter(true, 0.52f).apply {
            firstComponent = historyPanel
            secondComponent = south
            setHonorComponentsMinimumSize(true)
        }

        return JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
    }

    private fun refreshUi(kind: ChangeKind? = null) {
        var reopen = false
        syncing = true
        try {
            val items = conversations.all()
            val active = conversations.active()
            val keepPopup = reopenComboPopup && combo.isPopupVisible
            reopen = reopenComboPopup && items.isNotEmpty()
            reopenComboPopup = false
            val existing = combo.model as? DefaultComboBoxModel<PiConversation>
            if (keepPopup && existing != null) {
                val nextIds = items.map { it.id }.toSet()
                var i = 0
                while (i < existing.size) {
                    if (existing.getElementAt(i).id !in nextIds) existing.removeElementAt(i)
                    else i++
                }
                existing.selectedItem = active
            } else {
                val model = DefaultComboBoxModel<PiConversation>()
                items.forEach { model.addElement(it) }
                combo.model = model
                combo.selectedItem = active
            }
            combo.isEnabled = items.isNotEmpty()

            refreshHistory(active, kind)

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
        if (reopen) {
            SwingUtilities.invokeLater {
                if (!disposed && combo.itemCount > 0) combo.showPopup()
            }
        }
    }

    private fun applyConversationFont() {
        val font = PiSettings.getInstance().conversationFont()
        inputArea.font = font
        var rowsDirty = false
        for (card in historyCards) {
            val body = card.getClientProperty(PROP_BODY) as? JBTextArea ?: continue
            if (body.font != font) {
                body.font = font
                rowsDirty = true
            }
        }
        if (rowsDirty) remeasureHistory()
    }

    /**
     * Labels refresh on every event; history cards are rebuilt only when the
     * message set actually changed. `STATE` (agent working/idle, model swap)
     * must not rebuild: it would drop the scroll anchor and the expand state
     * right when the user is looking back at older messages.
     */
    private fun refreshHistory(conversation: PiConversation?, kind: ChangeKind?) {
        val signature = messagesSignature(conversation)
        val conversationChanged = conversation?.id != historyConversationId
        val signatureChanged = signature != historySignature
        if (!conversationChanged && !signatureChanged && kind != ChangeKind.SENT) {
            updateRail()
            return
        }
        // Both are meaningless once the rebuild has happened.
        val stickToBottom = conversationChanged || kind == ChangeKind.SENT || isNearBottom()
        val anchor = if (stickToBottom) null else captureAnchor()
        rebuildHistory(conversation, stickToBottom, anchor)
        historySignature = signature
        historyConversationId = conversation?.id
    }

    private fun messagesSignature(conversation: PiConversation?): Any =
        if (conversation == null) "none"
        else listOf(
            conversation.id,
            conversation.messages.size,
            conversation.messages.lastOrNull()?.timestamp ?: 0L
        )

    private fun isNearBottom(): Boolean {
        val bar = historyScroll.verticalScrollBar
        val max = bar.maximum - bar.visibleAmount
        if (max <= 0) return true
        return bar.value >= max - historyScroll.viewport.extentSize.height / 4
    }

    private data class ScrollAnchor(val message: PiUserMessage?, val offset: Int)

    private fun captureAnchor(): ScrollAnchor {
        val viewY = historyScroll.viewport.viewPosition.y
        val index = historyCards.indexOfFirst { it.y + it.height > viewY }
        if (index < 0) return ScrollAnchor(null, viewY)
        val message = conversations.active()?.messages?.getOrNull(index)
        return ScrollAnchor(message, viewY - historyCards[index].y)
    }

    private fun restoreAnchor(anchor: ScrollAnchor) {
        val message = anchor.message
        if (message == null) {
            // No card was visible when captured: offset holds an absolute y.
            scrollToY(anchor.offset)
            return
        }
        val index = conversations.active()?.messages.orEmpty().indexOfFirst { it === message }
        val card = historyCards.getOrNull(index)
        if (card == null) {
            // The anchored message is gone (rewind truncates, delete removes).
            scrollToBottom()
            return
        }
        scrollToY(card.y + anchor.offset)
    }

    private fun scrollToY(y: Int) {
        val viewport = historyScroll.viewport
        val max = (historyList.height - viewport.extentSize.height).coerceAtLeast(0)
        viewport.viewPosition = Point(0, y.coerceIn(0, max))
    }

    private fun scrollToBottom() {
        val bar = historyScroll.verticalScrollBar
        bar.value = bar.maximum
    }

    private fun rebuildHistory(conversation: PiConversation?, stickToBottom: Boolean, anchor: ScrollAnchor?) {
        historyList.removeAll()
        historyCards.clear()
        val messages = conversation?.messages.orEmpty()
        pruneExpandedState(messages)
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
                val card = historyCard(conversationId, index, message)
                historyCards.add(card)
                historyList.add(card)
            }
        }
        historyList.revalidate()
        historyList.repaint()
        SwingUtilities.invokeLater {
            if (disposed) return@invokeLater
            validateHistoryLayout()
            if (stickToBottom) scrollToBottom() else anchor?.let { restoreAnchor(it) }
            updateRail()
        }
    }

    /**
     * Lay the history out synchronously: card offsets and the scroll maximum are
     * read right after a rebuild, before the next paint pass would validate.
     */
    private fun validateHistoryLayout() {
        val container = messageRail.parent as? JComponent ?: return
        container.revalidate()
        container.validate()
    }

    private fun pruneExpandedState(messages: List<PiUserMessage>) {
        if (expandedMessages.isEmpty()) return
        val alive = Collections.newSetFromMap(IdentityHashMap<PiUserMessage, Boolean>())
        alive.addAll(messages)
        val iterator = expandedMessages.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() !in alive) iterator.remove()
        }
    }

    private fun isCollapsed(message: PiUserMessage): Boolean =
        message.text.length > COLLAPSE_CHARS && message !in expandedMessages

    private fun toggleExpanded(index: Int, message: PiUserMessage) {
        val conversation = conversations.active() ?: return
        // Anchor on the toggled card so it stays under the cursor.
        val card = historyCards.getOrNull(index)
        val offset = if (card != null) historyScroll.viewport.viewPosition.y - card.y else 0
        if (!expandedMessages.remove(message)) expandedMessages.add(message)
        rebuildHistory(conversation, stickToBottom = false, anchor = ScrollAnchor(message, offset))
    }

    /** Width available to card body text, minus a safety margin for wrap drift. */
    private fun historyTextWidth(): Int {
        val available = historyScroll.viewport.extentSize.width - JBUI.scale(16) - JBUI.scale(2)
        return available.coerceAtLeast(JBUI.scale(40))
    }

    private fun expandedRows(message: PiUserMessage): Int {
        // Before the first layout the viewport has no width yet; measuring at a
        // tiny width would produce absurd row counts. Rely on the viewport
        // resize hook to measure once the real width is known.
        if (historyScroll.viewport.extentSize.width <= JBUI.scale(80)) return COLLAPSED_ROWS
        val lines = HistoryMetrics.visualLineCount(
            message.text,
            PiSettings.getInstance().conversationFont(),
            historyTextWidth()
        )
        return if (message.text.length > LONG_TEXT_CHARS) lines.coerceAtMost(LONG_TEXT_ROWS) else lines
    }

    private fun scheduleRemeasure() {
        if (remeasurePending) return
        remeasurePending = true
        SwingUtilities.invokeLater {
            remeasurePending = false
            if (!disposed) remeasureHistory()
        }
    }

    /** Re-wrap expanded cards after a font or viewport-width change. */
    private fun remeasureHistory() {
        val messages = conversations.active()?.messages ?: return
        var changed = false
        historyCards.forEachIndexed { index, card ->
            val message = messages.getOrNull(index) ?: return@forEachIndexed
            val body = card.getClientProperty(PROP_BODY) as? JBTextArea ?: return@forEachIndexed
            val rows = if (isCollapsed(message)) COLLAPSED_ROWS else expandedRows(message)
            if (body.rows != rows) {
                body.rows = rows
                changed = true
            }
        }
        if (changed) historyList.revalidate()
        updateRail()
    }

    private fun updateRail() {
        val viewport = historyScroll.viewport
        val scrollable = viewport.extentSize.height > 0 &&
            historyList.preferredSize.height > viewport.extentSize.height + JBUI.scale(2)
        val visible = historyCards.isNotEmpty() && scrollable
        if (messageRail.isVisible != visible) {
            messageRail.isVisible = visible
            messageRail.parent?.revalidate()
        }
        messageRail.update(historyCards.size, currentTopMessageIndex())
    }

    private fun currentTopMessageIndex(): Int {
        if (historyCards.isEmpty()) return -1
        val viewY = historyScroll.viewport.viewPosition.y
        val index = historyCards.indexOfFirst { it.y + it.height > viewY }
        return if (index < 0) historyCards.lastIndex else index
    }

    private fun jumpToMessage(index: Int) {
        val card = historyCards.getOrNull(index) ?: return
        scrollToY(card.y)
        flashHistoryCard(card)
        messageRail.setHighlight(index)
    }

    private fun jumpToAdjacentMessage(delta: Int) {
        if (historyCards.isEmpty()) return
        val top = currentTopMessageIndex().coerceAtLeast(0)
        jumpToMessage((top + delta).coerceIn(0, historyCards.lastIndex))
    }

    private fun flashHistoryCard(card: JComponent) {
        card.putClientProperty(PROP_FLASH, true)
        card.repaint()
        val timer = Timer(FLASH_MS) {
            card.putClientProperty(PROP_FLASH, false)
            card.repaint()
        }
        timer.isRepeats = false
        timer.start()
    }

    private fun railTitle(index: Int): String? {
        val message = conversations.active()?.messages?.getOrNull(index) ?: return null
        val head = message.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val preview = if (head.length > RAIL_TOOLTIP_CHARS) head.take(RAIL_TOOLTIP_CHARS) + "…" else head
        return timeFormat.format(Date(message.timestamp)) + " · " + preview
    }

    private fun installHistoryKeyBindings() {
        fun bind(shortcut: KeyStroke, delta: Int) {
            object : AnAction(), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    jumpToAdjacentMessage(delta)
                }
            }.registerCustomShortcutSet(CustomShortcutSet(shortcut), historyScroll, this)
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK), -1)
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), 1)
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
        val rewind = iconButton(AllIcons.Actions.Undo, "Rewind conversation to this message") {
            conversations.rewindConversation(conversationId, index)
        }
        val fork = iconButton(AllIcons.Vcs.Branch, "Fork conversation at this message") {
            conversations.forkConversation(conversationId, index)
        }
        val copy = iconButton(AllIcons.Actions.Copy, "Copy") {
            CopyPasteManager.getInstance().setContents(StringSelection(message.text))
        }
        val delete = iconButton(AllIcons.General.Delete, "Remove this card from the panel (does not touch the Pi session)") {
            conversations.deleteMessage(conversationId, index)
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(rewind)
            add(fork)
            add(copy)
            add(delete)
        }
        val collapsed = isCollapsed(message)
        body.rows = if (collapsed) COLLAPSED_ROWS else expandedRows(message)
        val bottom = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                if (message.text.length > COLLAPSE_CHARS) {
                    add(expandLink(index, message, collapsed))
                }
            }, BorderLayout.WEST)
            add(actions, BorderLayout.EAST)
        }
        return object : JPanel(BorderLayout()) {
            init {
                putClientProperty(PROP_BODY, body)
                alignmentX = Component.LEFT_ALIGNMENT
                isOpaque = true
                background = JBColor.namedColor("ToolWindow.background", JBColor(0xF2F2F2, 0x3C3F41))
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor.namedColor("Separator.separatorColor", JBColor.border()), 1),
                    JBUI.Borders.empty(6, 8, 2, 6)
                )
                add(time, BorderLayout.NORTH)
                add(body, BorderLayout.CENTER)
                add(bottom, BorderLayout.SOUTH)
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (getClientProperty(PROP_FLASH) == true) {
                    g.color = FLASH_BACKGROUND
                    g.fillRect(0, 0, width, height)
                }
            }
        }
    }

    /** Text-styled toggle: the card body itself stays a selectable text area. */
    private fun expandLink(index: Int, message: PiUserMessage, collapsed: Boolean): JButton {
        val label = if (collapsed) "Expand (${message.text.length} chars)" else "Collapse"
        return JButton(label).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            isOpaque = false
            isFocusable = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.emptyInsets()
            font = JBUI.Fonts.smallFont()
            foreground = LINK_FOREGROUND
            toolTipText = if (collapsed) "Show the whole message" else "Collapse the message"
            border = JBUI.Borders.empty(0, 0, 0, JBUI.scale(4))
            addActionListener { toggleExpanded(index, message) }
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
            preferredSize = Dimension(JBUI.scale(18), JBUI.scale(18))
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
            add(object : AnAction("Insert selection", "Append @path#L for the editor selection", AllIcons.Actions.Edit), DumbAware {
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
        val popup = combo.ui.getAccessibleChild(combo, 0) as? ComboPopup ?: return
        val list = popup.list
        if (list.getClientProperty("pi-combo-delete") == true) {
            comboDeleteClickInstalled = true
            return
        }
        list.putClientProperty("pi-combo-delete", true)
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (handleComboDeleteClick(list, e)) {
                    e.consume()
                    deletingFromCombo = true
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (deletingFromCombo || suppressComboAction) e.consume()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (deletingFromCombo || suppressComboAction) {
                    e.consume()
                    deletingFromCombo = false
                }
            }

            override fun mouseExited(e: MouseEvent) {
                if (comboDeleteHoverIndex != -1) {
                    comboDeleteHoverIndex = -1
                    list.cursor = Cursor.getDefaultCursor()
                    list.repaint()
                }
            }
        })
        list.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val hover = deleteHitIndex(list, e)
                list.cursor = if (hover >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                else Cursor.getDefaultCursor()
                if (hover != comboDeleteHoverIndex) {
                    comboDeleteHoverIndex = hover
                    list.repaint()
                }
            }
        })
        comboDeleteClickInstalled = true
    }

    /**
     * Width the combo popup's overlay scrollbar paints over the list cells.
     *
     * IntelliJ renders the popup's vertical scrollbar on top of the list, so a
     * long conversation list would hide the per-item `×` delete hotspot behind
     * it. Scrollbars that take layout space already narrow the list, so they
     * need no extra inset; only the overlay case is handled here.
     */
    private fun comboPopupScrollBarInset(list: JList<*>): Int {
        val scroll = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, list) as? JScrollPane ?: return 0
        val bar = scroll.verticalScrollBar ?: return 0
        if (!bar.isVisible) return 0
        if (list.width < scroll.width - JBUI.scale(2)) return 0
        return bar.preferredSize.width.coerceAtLeast(JBUI.scale(8))
    }

    private fun deleteHitIndex(list: JList<*>, e: MouseEvent): Int {
        val index = list.locationToIndex(e.point)
        if (index < 0) return -1
        val conversation = list.model.getElementAt(index) as? PiConversation ?: return -1
        if (conversations.isTerminalAlive(conversation.id)) return -1
        val cell = list.getCellBounds(index, index) ?: return -1
        if (!cell.contains(e.point)) return -1
        val right = cell.x + cell.width - comboPopupScrollBarInset(list)
        if (e.x < right - JBUI.scale(COMBO_DELETE_HIT_PX)) return -1
        return index
    }

    private fun handleComboDeleteClick(list: JList<*>, e: MouseEvent): Boolean {
        val index = deleteHitIndex(list, e)
        if (index < 0) return false
        val conversation = list.model.getElementAt(index) as? PiConversation ?: return false
        // Block the combo's select-to-activate path so a closed session is
        // not relaunched just to be deleted. Keep the popup open for batch delete.
        suppressComboAction = true
        reopenComboPopup = true
        comboDeleteHoverIndex = -1
        conversations.deleteConversation(conversation.id)
        SwingUtilities.invokeLater { suppressComboAction = false }
        return true
    }

    override fun dispose() {
        disposed = true
        persistDraft()
        conversations.removeListener(listener)
        PiSettings.getInstance().removeChangeListener(settingsListener)
        ToolTipManager.sharedInstance().unregisterComponent(messageRail)
    }

    private inner class ConversationComboRenderer : JPanel(BorderLayout()), javax.swing.ListCellRenderer<PiConversation> {
        private val title = JLabel()
        private val deleteHint = JLabel("×").apply {
            horizontalAlignment = JLabel.RIGHT
            verticalAlignment = JLabel.CENTER
            font = JBUI.Fonts.smallFont()
            isOpaque = false
        }

        init {
            isOpaque = true
            add(title, BorderLayout.CENTER)
            add(deleteHint, BorderLayout.EAST)
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
            val showDelete = index >= 0 && conversation != null && !alive
            val overDelete = showDelete && index == comboDeleteHoverIndex
            deleteHint.isVisible = showDelete
            if (showDelete) {
                deleteHint.isOpaque = overDelete
                deleteHint.background = if (overDelete) {
                    JBUI.CurrentTheme.ActionButton.hoverBackground()
                } else {
                    null
                }
                deleteHint.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            }
            val selectedBg = list.selectionBackground
            val selectedFg = list.selectionForeground
            val normalBg = list.background
            val normalFg = if (conversation != null && !alive && !working) JBColor.GRAY else list.foreground
            background = if (isSelected) selectedBg else normalBg
            title.foreground = if (isSelected) selectedFg else normalFg
            // Keep the delete hotspot clear of the popup's overlay scrollbar.
            border = JBUI.Borders.empty(2, 6, 2, 6 + comboPopupScrollBarInset(list))
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
