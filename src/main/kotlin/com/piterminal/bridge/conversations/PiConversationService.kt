package com.piterminal.bridge.conversations

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindowManager
import com.piterminal.bridge.PiBridgeUi
import com.piterminal.bridge.services.PiStatusWidget
import com.piterminal.bridge.services.PiTerminalService
import com.piterminal.bridge.settings.PiSettings
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-level conversation map. One conversation <-> one Pi terminal.
 * Conversations are persisted via [PiConversationStore]; pi's own session
 * jsonl files stay authoritative for conversation data. Deleting a
 * conversation never deletes pi-side files.
 */
@Service(Service.Level.PROJECT)
class PiConversationService(private val project: Project) {

    private val logger = Logger.getInstance(PiConversationService::class.java)

    fun interface Listener {
        fun onChanged(event: ChangeEvent)
    }

    enum class ChangeKind {
        STRUCTURE,
        DRAFT_APPENDED,
        SENT,
        STATE
    }

    data class ChangeEvent(
        val kind: ChangeKind,
        val conversationId: String? = null
    )

    private val conversations = LinkedHashMap<String, PiConversation>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile
    private var activeId: String? = null
    /** Agent working state per conversation id (bridge agent_state). */
    private val workingIds = mutableSetOf<String>()
    /** Tabs whose pi session id was confirmed by the bridge at least once. */
    private val boundTabs = mutableSetOf<String>()
    /** Latest model per conversation id (bridge model_changed). */
    private val models = mutableMapOf<String, String>()

    init {
        loadFromStore()

        val terminal = terminal()
        terminal.addListener(object : PiTerminalService.SessionListener {
            override fun onSessionEnded(conversationId: String) {
                notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversationId))
                PiStatusWidget.update(project)
            }

            override fun onSessionStarted(conversationId: String) {
                notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversationId))
                PiStatusWidget.update(project)
            }
        })
        terminal.onRestartRequested = { conversationId, tabName ->
            val conversation = get(conversationId)
            if (conversation != null) {
                terminal.launch(conversationId, tabName, conversation.piSessionId, conversation.title)
            }
        }
    }

    fun addListener(listener: Listener) { listeners.add(listener) }

    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun all(): List<PiConversation> = synchronized(conversations) { conversations.values.toList() }

    fun active(): PiConversation? = synchronized(conversations) {
        activeId?.let { conversations[it] }
    }

    fun get(id: String): PiConversation? = synchronized(conversations) { conversations[id] }

    fun isAgentWorking(id: String): Boolean = synchronized(workingIds) { id in workingIds }

    fun workingCount(): Int = synchronized(workingIds) { workingIds.size }

    fun currentModel(id: String? = null): String? = synchronized(models) {
        models[id ?: activeId]
    }

    fun createConversation(): PiConversation {
        val n = store().nextTitleSeq()
        val title = "Pi-$n-${LocalDateTime.now().format(TITLE_TIME_FORMAT)}"
        val conversation = PiConversation(
            id = UUID.randomUUID().toString(),
            title = title,
            tabName = title
        )
        synchronized(conversations) {
            conversations[conversation.id] = conversation
            activeId = conversation.id
        }
        persist()
        terminal().launch(conversation.id, conversation.tabName, conversation.piSessionId, conversation.title)
        PiStatusWidget.update(project)
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversation.id))
        return conversation
    }

    /**
     * With persistence, prefer restoring an existing conversation over
     * creating a new one on IDE startup.
     */
    fun ensureActiveConversation(): PiConversation {
        return active() ?: all().lastOrNull() ?: createConversation()
    }

    fun setActive(id: String) {
        val conversation = synchronized(conversations) {
            if (!conversations.containsKey(id) || activeId == id) return
            activeId = id
            conversations[id]
        } ?: return
        persist()
        if (!terminal().isAlive(conversation.id)) {
            restoreTerminal(conversation.id)
        } else {
            terminal().selectTab(conversation.id, requestFocus = false)
            notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, id))
        }
    }

    fun deleteConversation(id: String) {
        val removed = synchronized(conversations) {
            val conversation = conversations.remove(id) ?: return
            if (activeId == id) {
                activeId = conversations.keys.lastOrNull()
            }
            synchronized(workingIds) { workingIds.remove(id) }
            conversation
        }
        persist()
        terminal().close(removed.id)
        active()?.let { terminal().selectTab(it.id, requestFocus = false) }
        PiStatusWidget.update(project)
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, activeId))
    }

    /** Titles must stay unique; returns false and warns when the name is taken. */
    fun rename(id: String, newTitle: String): Boolean {
        val conversation = get(id) ?: return false
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty() || trimmed == conversation.title) return false
        val duplicate = all().any { it.id != id && it.title == trimmed }
        if (duplicate) {
            showNotification("A conversation named \"$trimmed\" already exists.", NotificationType.WARNING)
            return false
        }
        conversation.title = trimmed
        persist()
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, id))
        return true
    }

    fun updateDraft(id: String, draft: String) {
        val conversation = get(id) ?: return
        if (conversation.draft == draft) return
        conversation.draft = draft
        persist()
    }

    fun appendToDraft(text: String, block: Boolean = false) {
        val conversation = ensureActiveConversation()
        // Upsert same-path @file refs first; trailing newline is only a typing aid.
        val next = upsertFileRefs(conversation.draft, text) ?: mergeDraft(conversation.draft, text, block)
        conversation.draft = if (next.endsWith("\n")) next else next + "\n"
        persist()
        showToolWindow(focus = true)
        notifyListeners(ChangeEvent(ChangeKind.DRAFT_APPENDED, conversation.id))
    }

    fun appendWorkspaceFiles(paths: List<String>) {
        val conversation = ensureActiveConversation()
        conversation.draft = replaceWorkspaceBlock(conversation.draft, formatWorkspaceBlock(paths))
        persist()
        showToolWindow(focus = true)
        notifyListeners(ChangeEvent(ChangeKind.DRAFT_APPENDED, conversation.id))
    }

    fun sendDraft(): Boolean {
        val conversation = active() ?: return false
        val text = conversation.draft.trim()
        if (text.isEmpty()) return false

        if (!terminal().isAlive(conversation.id)) {
            terminal().launch(conversation.id, conversation.tabName, conversation.piSessionId, conversation.title)
            showNotification(
                "Pi terminal was restarted for ${conversation.title}. Send again after Pi is ready.",
                NotificationType.WARNING
            )
            notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversation.id))
            return false
        }

        terminal().sendText(conversation.id, text)
        conversation.messages.add(PiUserMessage(text))
        conversation.draft = ""
        persist()
        terminal().selectTab(conversation.id, requestFocus = false)
        notifyListeners(ChangeEvent(ChangeKind.SENT, conversation.id))
        return true
    }

    fun deleteMessage(conversationId: String, index: Int): Boolean {
        val conversation = get(conversationId) ?: return false
        if (index !in conversation.messages.indices) return false
        conversation.messages.removeAt(index)
        persist()
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversationId))
        return true
    }

    fun isTerminalAlive(id: String): Boolean = terminal().isAlive(id)

    fun focusTerminal(id: String) {
        val conversation = get(id) ?: return
        if (!terminal().isAlive(id)) {
            showNotification("${conversation.title} terminal is closed.", NotificationType.WARNING)
            notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, id))
            return
        }
        terminal().selectTab(id, requestFocus = true)
    }

    /** Select the conversation and focus its Terminal tab, relaunching if needed. */
    fun revealTerminal(id: String) {
        get(id) ?: return
        synchronized(conversations) {
            if (conversations.containsKey(id)) activeId = id
        }
        persist()
        if (!terminal().isAlive(id)) {
            restoreTerminal(id)
        }
        terminal().selectTab(id, requestFocus = true)
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, id))
    }

    fun checkTerminal(id: String): Boolean {
        val conversation = get(id) ?: return false
        if (terminal().isAlive(id)) {
            showNotification("${conversation.title} terminal is running.", NotificationType.INFORMATION)
            notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, id))
            return true
        }
        restoreTerminal(id)
        showNotification("Restarting ${conversation.title} and resuming the session…", NotificationType.INFORMATION)
        return false
    }

    /** Reopen a closed Pi terminal and resume its bound session when possible. */
    fun restoreTerminal(id: String): Boolean {
        val conversation = get(id) ?: return false
        if (terminal().isAlive(id)) {
            terminal().selectTab(id, requestFocus = false)
            notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, id))
            return true
        }
        if (conversation.title.endsWith(ARCHIVED_SUFFIX)) {
            conversation.title = conversation.title.removeSuffix(ARCHIVED_SUFFIX)
        }
        persist()
        terminal().launch(conversation.id, conversation.tabName, conversation.piSessionId, conversation.title)
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, id))
        return true
    }

    fun showToolWindow(focus: Boolean = true) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        if (focus) {
            toolWindow.activate(null)
        } else {
            toolWindow.show()
        }
    }

    // ---- Bridge event handlers (called from PiBridgeServer threads) ----

    /** /new, /fork, /clone or /resume switched the session inside the terminal. */
    fun onBridgeSessionChanged(tabKey: String, sessionId: String) {
        val conversation = get(tabKey) ?: run {
            logger.info("Bridge session_changed for unknown tab $tabKey; dropping")
            return
        }
        if (conversation.piSessionId == sessionId) return
        val previousSessionId = conversation.piSessionId
        val firstBind = synchronized(boundTabs) {
            if (tabKey !in boundTabs) {
                boundTabs.add(tabKey)
                true
            } else false
        }
        if (firstBind || previousSessionId.isBlank()) {
            // First confirmation from pi: adopt its session id verbatim. pi may
            // normalize or regenerate ids, and archiving on first bind would
            // create a spurious entry on every launch.
            conversation.piSessionId = sessionId
            persist()
            logger.info("Bound conversation ${conversation.id} to pi session $sessionId")
            notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversation.id))
            return
        }
        // Rebind: the tab keeps its stable id and now represents the new pi
        // session. The old session is preserved as a separate closed entry
        // (its jsonl stays on disk), so the history stays reachable.
        val archived = PiConversation(
            id = UUID.randomUUID().toString(),
            title = uniqueArchiveTitle(conversation.title),
            tabName = conversation.title,
            piSessionId = previousSessionId,
            createdAt = conversation.createdAt,
            messages = conversation.messages.toMutableList(),
            draft = ""
        )
        conversation.piSessionId = sessionId
        synchronized(conversations) {
            // Keep insertion order stable: archived right before the live tab.
            val reordered = LinkedHashMap<String, PiConversation>()
            for ((key, value) in conversations) {
                if (key == conversation.id) reordered[archived.id] = archived
                reordered[key] = value
            }
            conversations.clear()
            conversations.putAll(reordered)
        }
        persist()
        logger.info("Rebound conversation ${conversation.id} to pi session $sessionId (archived $previousSessionId)")
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversation.id))
    }

    fun onBridgeSessionStopped(tabKey: String) {
        synchronized(workingIds) { workingIds.remove(tabKey) }
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, tabKey))
        PiStatusWidget.update(project)
    }

    fun onBridgeAgentState(tabKey: String, state: String, stopReason: String? = null) {
        val conversation = get(tabKey) ?: return
        val working = state == "working"
        val becameIdle = synchronized(workingIds) {
            val wasWorking = tabKey in workingIds
            if (working) workingIds.add(tabKey) else workingIds.remove(tabKey)
            wasWorking && !working
        }
        notifyListeners(ChangeEvent(ChangeKind.STATE, tabKey))
        PiStatusWidget.update(project)
        if (becameIdle && PiSettings.getInstance().state.notifyOnAgentEnd && !PiBridgeUi.isIdeActive(project)) {
            val detail = when (stopReason) {
                "error" -> " (stopped with an error)"
                "aborted" -> " (aborted)"
                else -> ""
            }
            PiBridgeUi.notifyOpenTerminal(
                project,
                conversation.title,
                "Agent finished$detail.",
                NotificationType.INFORMATION
            ) {
                revealTerminal(conversation.id)
            }
        }
    }

    fun onBridgeModelChanged(tabKey: String, modelId: String) {
        synchronized(models) { models[tabKey] = modelId }
        notifyListeners(ChangeEvent(ChangeKind.STATE, tabKey))
    }

    /** Pi edited a file; optionally refresh + open it in the editor. */
    fun onBridgeFileModified(tabKey: String, path: String) {
        if (!PiSettings.getInstance().state.openModifiedFiles) return
        val app = ApplicationManager.getApplication()
        // Refresh off-EDT, then open on EDT. Never inside a write action —
        // VFS refresh + openFile in runWriteAction can freeze the IDE.
        app.executeOnPooledThread {
            try {
                val nioPath = Paths.get(path).let {
                    // pi may report project-relative paths; resolve against the project root.
                    if (!it.isAbsolute && project.basePath != null) {
                        Paths.get(project.basePath).resolve(it)
                    } else it
                }
                VirtualFileManager.getInstance().refreshAndFindFileByNioPath(nioPath)?.let { file ->
                    app.invokeLater {
                        if (!project.isDisposed && file.isValid) {
                            FileEditorManager.getInstance(project).openFile(file, false)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to open modified file $path: ${e.message}")
            }
        }
    }

    // ---- internals ----

    private fun loadFromStore() {
        val stored = store().snapshot()
        synchronized(conversations) {
            for (s in stored) {
                conversations[s.id] = PiConversation(
                    id = s.id,
                    title = s.title,
                    tabName = s.tabName,
                    piSessionId = s.piSessionId,
                    createdAt = s.createdAt,
                    messages = s.messages.toMutableList(),
                    draft = s.draft
                )
            }
            activeId = store().activeId()?.takeIf { conversations.containsKey(it) }
                ?: conversations.keys.lastOrNull()
        }
        // Terminals are not auto-revived; the user clicks a conversation to
        // relaunch (`pi --session`). No startup file reconciliation: the
        // resume-vs-new decision happens at launch time (sessionFileExists),
        // when the jsonl is guaranteed to be flushed — clearing ids at
        // startup would kill ids whose file merely was not written yet.
    }

    private fun persist() {
        store().save(all(), activeId)
    }

    /** Archive titles must stay unique too — rename forbids duplicates. */
    private fun uniqueArchiveTitle(baseTitle: String): String {
        val existing = all().map { it.title }.toSet()
        var candidate = "$baseTitle$ARCHIVED_SUFFIX"
        var n = 2
        while (candidate in existing) {
            candidate = "$baseTitle$ARCHIVED_SUFFIX ($n)"
            n++
        }
        return candidate
    }

    private fun mergeDraft(draft: String, text: String, block: Boolean): String {
        if (draft.isBlank()) return text
        return if (block) {
            draft.trimEnd() + "\n\n" + text
        } else if (draft.endsWith(" ") || draft.endsWith("\n")) {
            draft + text
        } else {
            draft + " " + text
        }
    }

    /**
     * Later @file / @file#L refs replace earlier ones for the same path.
     * The workspace-open-files block is left intact.
     * Returns null when [incoming] has no file references.
     */
    private fun upsertFileRefs(draft: String, incoming: String): String? {
        val matches = FILE_REF_REGEX.findAll(incoming).toList()
        if (matches.isEmpty()) return null

        val latestByPath = LinkedHashMap<String, String>()
        for (match in matches) {
            latestByPath[match.groupValues[1]] = match.value
        }

        val workspace = WORKSPACE_BLOCK_REGEX.find(draft)?.value
        var result = WORKSPACE_BLOCK_REGEX.replace(draft, "")
        for (path in latestByPath.keys) {
            result = result.replace(Regex("""@${Regex.escape(path)}(?:#L\d+(?:-\d+)?)?"""), "")
        }
        result = result
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex(" {2,}"), " ")
            .trim()

        val block = latestByPath.values.joinToString("\n")
        val merged = if (result.isEmpty()) block else result + "\n" + block
        return if (workspace.isNullOrBlank()) merged else "$merged\n\n$workspace"
    }

    private fun replaceWorkspaceBlock(draft: String, newBlock: String): String {
        val without = WORKSPACE_BLOCK_REGEX.replace(draft, "").trim()
        return if (without.isEmpty()) newBlock else "$without\n\n$newBlock"
    }

    private fun formatWorkspaceBlock(paths: List<String>): String {
        return buildString {
            appendLine(WORKSPACE_START)
            appendLine("The user currently has these files open in their IDE workspace.")
            appendLine("They may be contextually related to the current question. Read them on demand if needed.")
            paths.forEach { appendLine(it) }
            append(WORKSPACE_END)
        }
    }

    private fun terminal(): PiTerminalService = PiTerminalService.getInstance(project)

    private fun store(): PiConversationStore = PiConversationStore.getInstance(project)

    private fun notifyListeners(event: ChangeEvent) {
        listeners.forEach { it.onChanged(event) }
    }

    private fun showNotification(message: String, type: NotificationType) {
        PiBridgeUi.notify(project, message, type)
    }

    companion object {
        const val TOOL_WINDOW_ID = PiBridgeUi.TOOL_WINDOW_ID
        const val ARCHIVED_SUFFIX = " (archived)"
        const val WORKSPACE_START = "<workspace-open-files>"
        const val WORKSPACE_END = "</workspace-open-files>"
        private val TITLE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        private val FILE_REF_REGEX = Regex("""@([^\s#]+)(?:#L\d+(?:-\d+)?)?""")
        private val WORKSPACE_BLOCK_REGEX = Regex(
            """$WORKSPACE_START[\s\S]*?$WORKSPACE_END"""
        )

        fun getInstance(project: Project): PiConversationService = project.service()
    }
}
