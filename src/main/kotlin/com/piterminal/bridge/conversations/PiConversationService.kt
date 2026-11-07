package com.piterminal.bridge.conversations

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.piterminal.bridge.PiBridgeUi
import com.google.gson.JsonObject
import com.piterminal.bridge.bridge.BridgeOperations
import com.piterminal.bridge.bridge.SessionSnapshot
import com.piterminal.bridge.bridge.PiBridgeServer
import com.piterminal.bridge.services.PiStatusWidget
import com.piterminal.bridge.services.PiTerminalService
import com.piterminal.bridge.settings.PiSettings
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project-level conversation map. One conversation <-> one Pi terminal.
 * Conversations are persisted via [PiConversationStore]; pi's own session
 * jsonl files stay authoritative for conversation data. Deleting a
 * conversation never deletes pi-side files.
 */
@Service(Service.Level.PROJECT)
class PiConversationService(private val project: Project) {

    private val logger = Logger.getInstance(PiConversationService::class.java)
    private val operations by lazy { ConversationOperations(project, this) }
    private val forks by lazy { ForkWorkflow(project, this) }
    private val bridge get() = BridgeOperations.getInstance(project)

    fun operationStatus(id: String): String? = bridge.status(id)
    fun hasPendingOperation(id: String): Boolean = bridge.locked(id)
    enum class OperationRecovery { STOP_PREPARATION, SYNC_CURRENT_STATE }
    fun operationRecovery(id: String): OperationRecovery? = when {
        bridge.preparing(id) -> OperationRecovery.STOP_PREPARATION
        operations.canReconcile(id) -> OperationRecovery.SYNC_CURRENT_STATE
        else -> null
    }
    fun reconcileOperation(id: String): Boolean = if (forks.stopPreparing(id)) true else operations.reconcile(id)
    fun restoreRecoveryText(id: String) {
        val c = get(id) ?: return
        if (c.recoveryText.isEmpty()) return
        c.draft = mergeDraft(c.draft, c.recoveryText, true)
        c.recoveryText = ""; c.draftRevision++
        saveOperationView(id, draft = true)
    }
    fun onBridgeOperation(record: JsonObject) {
        if (!forks.receive(record)) operations.receive(record)
    }
    fun onBridgeOperationState(tabKey: String) = operationChanged(tabKey)
    internal fun operationChanged(id: String) = notifyListeners(ChangeEvent(ChangeKind.STATE, id))
    internal fun saveOperationView(id: String, draft: Boolean = false) {
        persist()
        notifyListeners(ChangeEvent(if (draft) ChangeKind.DRAFT_APPENDED else ChangeKind.STRUCTURE, id))
    }
    fun onBridgeSnapshot(tabKey: String, snapshot: SessionSnapshot) {
        val c = get(tabKey) ?: return
        forks.onSnapshot(tabKey, snapshot)
        // Observations during UNKNOWN are diagnostic only, never implicit operation success.
        if (bridge.locked(tabKey)) { operationChanged(tabKey); return }
        onBridgeSessionChanged(tabKey, snapshot.sessionId, "v2")
        if (operations.project(c, snapshot)) saveOperationView(tabKey)
        val working = snapshot.raw.getAsJsonObject("state").get("idle")?.asBoolean != true
        synchronized(workingIds) { if (working) workingIds.add(tabKey) else workingIds.remove(tabKey) }
        operationChanged(tabKey)
        PiStatusWidget.update(project)
    }

    fun interface Listener {
        fun onChanged(event: ChangeEvent)
    }

    enum class ChangeKind {
        STRUCTURE,
        DRAFT_APPENDED,
        SENT,
        STATE,
        FOCUS_INPUT
    }

    data class ChangeEvent(
        val kind: ChangeKind,
        val conversationId: String? = null
    )

    private val conversations = LinkedHashMap<String, PiConversation>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile
    private var activeId: String? = null
    /** Working badges. Owner: v2 snapshots once the bridge owns the tab; v1 agent_state before that. */
    private val workingIds = mutableSetOf<String>()
    /** Edge detection for the agent-finish notification (v1 agent_state telemetry). */
    private val telemetryWorkingIds = mutableSetOf<String>()
    /** Tabs whose pi session id was confirmed by the bridge at least once. */
    private val boundTabs = mutableSetOf<String>()
    /** Latest model per conversation id (bridge model_changed). */
    private val models = mutableMapOf<String, String>()

    init {
        loadFromStore()

        val terminal = terminal()
        terminal.addListener(object : PiTerminalService.SessionListener {
            override fun onSessionEnded(conversationId: String) {
                forks.stopPreparing(conversationId)
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
            if (conversation != null && !bridge.preparing(conversationId)) {
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

    /** Latest subscribed v2 branch counts; null until a usable snapshot for this session arrives. */
    fun branchCounts(id: String): SessionSnapshot.BranchCounts? {
        val snapshot = bridge.snapshot(id) ?: return null
        if (!snapshot.usable || snapshot.sessionId != get(id)?.piSessionId) return null
        return snapshot.counts()
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

    /** Prepare a separate copy; only a verified operation result commits its final branch. */
    fun forkConversation(id: String, messageIndex: Int): PiConversation? = forks.start(id, messageIndex)

    internal fun newForkTarget(source: PiConversation): PiConversation {
        val sessionId = UUID.randomUUID().toString()
        return PiConversation(
            id = UUID.randomUUID().toString(), title = uniqueForkTitle(source.title), tabName = source.tabName,
            piSessionId = sessionId,
            messages = source.messages.map { if (it.sessionId == source.piSessionId) it.copy(sessionId = sessionId) else it }.toMutableList()
        )
    }

    internal fun addForkTarget(target: PiConversation, activate: Boolean) {
        synchronized(conversations) {
            if (target.id !in conversations) conversations[target.id] = target
            if (activate) activeId = target.id
        }
        saveOperationView(target.id)
        PiStatusWidget.update(project)
    }

    /** The intermediate copy is workflow state, so a confirmed replacement does not archive it. */
    internal fun adoptForkSession(c: PiConversation, sessionId: String) {
        ForkPolicy.rebind(c, sessionId)
        synchronized(boundTabs) { boundTabs.add(c.id) }
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
            bridge.requestSnapshot(conversation.id)
            notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, id))
        }
    }

    fun deleteConversation(id: String) {
        if (bridge.locked(id)) {
            showNotification("This conversation has an unresolved operation; retain it for result queries.", NotificationType.WARNING)
            return
        }
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
        conversation.draftRevision++
        persist()
    }

    fun appendToDraft(text: String, block: Boolean = false) {
        val conversation = ensureActiveConversation()
        val next = mergeDraft(conversation.draft, text, block)
        conversation.draft = if (next.endsWith("\n")) next else next + "\n"
        conversation.draftRevision++
        persist()
        showToolWindow(focus = true)
        notifyListeners(ChangeEvent(ChangeKind.DRAFT_APPENDED, conversation.id))
    }

    fun appendWorkspaceFiles(paths: List<String>) {
        val conversation = ensureActiveConversation()
        conversation.draft = replaceWorkspaceBlock(conversation.draft, formatWorkspaceBlock(paths))
        conversation.draftRevision++
        persist()
        showToolWindow(focus = true)
        notifyListeners(ChangeEvent(ChangeKind.DRAFT_APPENDED, conversation.id))
    }

    fun sendDraft(): Boolean {
        val conversation = active() ?: return false
        if (bridge.locked(conversation.id)) {
            showNotification("Rewind is unresolved. Your draft is saved; wait for its result or reconcile before sending.", NotificationType.WARNING)
            return false
        }
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
        // Sending to the TUI is not evidence of a native user entry. History is
        // updated only by session snapshots (commands may create no user entry).
        conversation.draft = ""
        conversation.draftRevision++
        persist()
        terminal().selectTab(conversation.id, requestFocus = false)
        notifyListeners(ChangeEvent(ChangeKind.SENT, conversation.id))
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
        if (bridge.preparing(id)) return false
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

    /** Show the Pi panel and put caret in the prompt. */
    fun focusInput(conversationId: String? = null) {
        if (conversationId != null) {
            synchronized(conversations) {
                if (conversations.containsKey(conversationId)) activeId = conversationId
            }
            persist()
            notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversationId))
        }
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            notifyListeners(ChangeEvent(ChangeKind.FOCUS_INPUT, activeId))
        }
    }

    // ---- Bridge event handlers (called from PiBridgeServer threads) ----

    /** /new, /fork, /clone or /resume switched the session inside the terminal. */
    fun onBridgeSessionChanged(tabKey: String, sessionId: String, extensionVersion: String? = null) {
        if (bridge.preparing(tabKey)) return
        val conversation = get(tabKey) ?: run {
            logger.info("Bridge session_changed for unknown tab $tabKey; dropping")
            return
        }
        if (conversation.piSessionId == sessionId) {
            synchronized(boundTabs) { boundTabs.add(tabKey) }
            return
        }
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
            logger.info("Bound conversation ${conversation.id} to pi session $sessionId (extension v$extensionVersion)")
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
            draft = "",
            viewRevision = conversation.viewRevision
        )
        conversation.piSessionId = sessionId
        if (PiBridgeServer.getInstance(project).owns(tabKey)) {
            conversation.messages.clear()
            conversation.viewRevision++
        }
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
        synchronized(telemetryWorkingIds) { telemetryWorkingIds.remove(tabKey) }
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, tabKey))
        PiStatusWidget.update(project)
    }

    fun onBridgeAgentState(tabKey: String, state: String, stopReason: String? = null) {
        val working = state == "working"
        synchronized(workingIds) { if (working) workingIds.add(tabKey) else workingIds.remove(tabKey) }
        notifyListeners(ChangeEvent(ChangeKind.STATE, tabKey))
        PiStatusWidget.update(project)
        onBridgeAgentStateTelemetry(tabKey, state, stopReason)
    }

    /** Agent-finish notification telemetry only; never touches the working badge. */
    fun onBridgeAgentStateTelemetry(tabKey: String, state: String, stopReason: String? = null) {
        val conversation = get(tabKey) ?: return
        val working = state == "working"
        val becameIdle = synchronized(telemetryWorkingIds) {
            val wasWorking = tabKey in telemetryWorkingIds
            if (working) telemetryWorkingIds.add(tabKey) else telemetryWorkingIds.remove(tabKey)
            wasWorking && !working
        }
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

    /** Pi wrote a file; sync the VFS immediately so the editor doesn't wait on the ~10s poll. */
    fun onBridgeFileModified(tabKey: String, path: String) {
        logger.debug("file_modified tab=$tabKey path=$path")
        val openAfter = PiSettings.getInstance().state.openModifiedFiles
        val app = ApplicationManager.getApplication()
        // Refresh off-EDT. Never inside a write action — VFS refresh + openFile
        // in runWriteAction can freeze the IDE.
        app.executeOnPooledThread {
            try {
                val nioPath = Paths.get(path).let {
                    if (!it.isAbsolute && project.basePath != null) {
                        Paths.get(project.basePath).resolve(it)
                    } else it
                }
                val ioFile = nioPath.toFile()
                val lfs = LocalFileSystem.getInstance()
                VfsUtil.markDirtyAndRefresh(false, false, false, ioFile)
                var file = lfs.findFileByIoFile(ioFile)
                if (file == null) {
                    ioFile.parentFile?.let { parent ->
                        lfs.refreshAndFindFileByIoFile(parent)?.refresh(false, false)
                    }
                    file = lfs.refreshAndFindFileByIoFile(ioFile)
                } else {
                    file.refresh(false, false)
                }
                val vf = file ?: run {
                    logger.debug("file_modified refresh missed: $nioPath")
                    return@executeOnPooledThread
                }
                logger.debug("file_modified refreshing ${vf.path} (openAfter=$openAfter)")
                app.invokeLater {
                    if (project.isDisposed || !vf.isValid) return@invokeLater
                    val documents = FileDocumentManager.getInstance()
                    val doc = documents.getCachedDocument(vf)
                    if (doc != null && !documents.isFileModified(vf)) {
                        documents.reloadFromDisk(doc)
                    }
                    if (openAfter) {
                        FileEditorManager.getInstance(project).openFile(vf, false)
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to refresh modified file $path: ${e.message}")
            }
        }
    }

    // ---- internals ----

    /** Rewind is committed only from a verified operation result and full pi snapshot. */
    fun rewindConversation(id: String, messageIndex: Int): Boolean = operations.rewind(id, messageIndex)

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
                    draft = s.draft,
                    draftRevision = s.draftRevision,
                    viewRevision = s.viewRevision,
                    recoveryText = s.recoveryText,
                    appliedOperations = s.appliedOperations.toMutableSet()
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

    /** Fork titles must stay unique too — fork twice and the second gets a suffix. */
    private fun uniqueForkTitle(baseTitle: String): String {
        val existing = all().map { it.title }.toSet()
        var candidate = "$baseTitle-fork"
        var n = 2
        while (candidate in existing) {
            candidate = "$baseTitle-fork ($n)"
            n++
        }
        return candidate
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
        private val WORKSPACE_BLOCK_REGEX = Regex(
            """$WORKSPACE_START[\s\S]*?$WORKSPACE_END"""
        )

        fun getInstance(project: Project): PiConversationService = project.service()
    }
}
