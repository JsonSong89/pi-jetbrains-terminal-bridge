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
    /** Agent working state per conversation id (bridge agent_state). */
    private val workingIds = mutableSetOf<String>()
    /** Tabs whose pi session id was confirmed by the bridge at least once. */
    private val boundTabs = mutableSetOf<String>()
    /** Latest model per conversation id (bridge model_changed). */
    private val models = mutableMapOf<String, String>()
    /** Conversation ids with an in-flight message fork (no archive on rebind). */
    private val forkingIds = mutableSetOf<String>()
    /** In-flight /bridge-fork command: message text (base64) + panel index. */
    private data class ForkCommand(val encodedText: String, val messageIndex: Int)

    /** Conversation id -> command awaiting the terminal's first session bind. */
    private val pendingForkCommands = java.util.concurrent.ConcurrentHashMap<String, ForkCommand>()

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
     * Forks [id] at user message [messageIndex] using pi's fork semantics:
     * the new conversation "<source>-fork" carries the history BEFORE that
     * message, and the message text is pre-filled in the draft so the user
     * can edit and re-send it (mirroring pi's /fork putting the text back in
     * the editor). The new terminal starts from a full --fork copy of the
     * source session; once the bridge reports the first bind, the plugin sends
     * the extension's /bridge-fork command to truncate at that message.
     */
    fun forkConversation(id: String, messageIndex: Int): PiConversation? {
        val source = get(id) ?: return null
        if (messageIndex !in source.messages.indices) return null
        if (PiBridgeServer.getInstance(project).endpoint() == null) {
            showNotification(
                "The Pi bridge channel is unavailable; forking needs it. " +
                    "Check the Terminal shell (PowerShell / POSIX, not cmd.exe).",
                NotificationType.WARNING
            )
            return null
        }
        val workingDir = project.basePath ?: System.getProperty("user.home")
        val hasSession = source.piSessionId.isNotBlank() &&
            (source.piSessionId != source.id ||
                PiTerminalService.findSessionFile(source.piSessionId, workingDir) != null)
        if (!hasSession) {
            showNotification(
                "${source.title} has no Pi session yet; nothing to fork.",
                NotificationType.WARNING
            )
            return null
        }
        val forkMessage = source.messages[messageIndex]
        val conversation = PiConversation(
            id = UUID.randomUUID().toString(),
            title = uniqueForkTitle(source.title),
            tabName = source.tabName,
            piSessionId = UUID.randomUUID().toString(),
            // Keep history up to and including the forked message; nothing
            // after it, and nothing pre-filled — fork continues from that
            // point, the user types the next question.
            messages = source.messages.subList(0, messageIndex + 1).toMutableList()
        )
        synchronized(conversations) {
            conversations[conversation.id] = conversation
            activeId = conversation.id
        }
        persist()
        synchronized(forkingIds) { forkingIds.add(conversation.id) }
        pendingForkCommands[conversation.id] = ForkCommand(
            base64(forkMessage.text),
            messageIndex
        )
        terminal().launch(
            conversation.id,
            conversation.tabName,
            conversation.piSessionId,
            conversation.title,
            forkOf = source.piSessionId
        )
        PiStatusWidget.update(project)
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversation.id))
        notifyListeners(ChangeEvent(ChangeKind.DRAFT_APPENDED, conversation.id))
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
        val next = mergeDraft(conversation.draft, text, block)
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
        val conversation = get(tabKey) ?: run {
            logger.info("Bridge session_changed for unknown tab $tabKey; dropping")
            return
        }
        if (conversation.piSessionId == sessionId) {
            // Id already matches (typical launch: we passed --session-id and pi
            // echoed it). Still dispatch a pending fork command if one waits —
            // the id check above would otherwise swallow it and the session
            // would stay on the full --fork copy.
            if (pendingForkCommands.containsKey(tabKey)) {
                logger.info("Bridge session_changed matched pre-set id for $tabKey; dispatching pending fork")
                schedulePendingForkCommand(tabKey)
            }
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
            schedulePendingForkCommand(conversation.id)
            return
        }
        val inFlightFork = synchronized(forkingIds) { forkingIds.remove(tabKey) }
        if (inFlightFork) {
            // The /bridge-fork command truncated the copied session: adopt the
            // branched session id without archiving — the intermediate full
            // copy was a transient transport, not a conversation worth keeping.
            conversation.piSessionId = sessionId
            persist()
            logger.info("Fork completed: conversation ${conversation.id} rebound to pi session $sessionId")
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

    /**
     * Rewinds the active branch of [id] back to user message [messageIndex]
     * using pi's navigateTree (the same semantics as selecting the message in
     * `/tree`): same session, same conversation — everything after the message
     * (it included, plus the AI reply) leaves the active branch and thus the
     * model context. The message text is put back in the panel's draft, like
     * /tree returning the text to the editor, so it can be edited and re-sent.
     * Needs a live terminal and the bridge channel.
     */
    fun rewindConversation(id: String, messageIndex: Int): Boolean {
        val conversation = get(id) ?: return false
        if (messageIndex !in conversation.messages.indices) return false
        if (PiBridgeServer.getInstance(project).endpoint() == null) {
            showNotification(
                "The Pi bridge channel is unavailable; rewinding needs it. " +
                    "Check the Terminal shell (PowerShell / POSIX, not cmd.exe).",
                NotificationType.WARNING
            )
            return false
        }
        if (!terminal().isAlive(conversation.id)) {
            showNotification(
                "${conversation.title} terminal is closed; restore it first, then rewind.",
                NotificationType.WARNING
            )
            return false
        }
        val text = conversation.messages[messageIndex].text
        // messages is a constructor val: trim in place instead of reassigning.
        conversation.messages.subList(messageIndex, conversation.messages.size).clear()
        conversation.draft = text
        persist()
        val encoded = base64(text)
        terminal().sendText(conversation.id, "/bridge-rewind $messageIndex $encoded")
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversation.id))
        notifyListeners(ChangeEvent(ChangeKind.DRAFT_APPENDED, conversation.id))
        return true
    }

    /** Once pi booted (first bind), tell the extension to fork at the message. */
    private fun schedulePendingForkCommand(conversationId: String) {
        val command = pendingForkCommands.remove(conversationId) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            // Watchdog: the first send may race the TUI becoming ready (the
            // command would be swallowed) — retry until the session rebind
            // (fork happened) or the terminal is gone. A repeat command after
            // a successful fork is a no-op: the entry no longer matches on
            // the new active branch... unless the text matches an earlier
            // message, so also stop once the session id differs from the
            // one bound at first-send time.
            val fullCommand = "/bridge-fork ${command.messageIndex} ${command.encodedText}"
            var sentSessionId: String? = null
            var attempts = 0
            logger.info("Fork watchdog armed for $conversationId: index=${command.messageIndex}")
            while (attempts < 20 && !project.isDisposed) {
                attempts++
                if (!terminal().isAlive(conversationId)) {
                    logger.info("Fork watchdog: terminal gone for $conversationId after $attempts attempts")
                    return@executeOnPooledThread
                }
                val bound = get(conversationId)?.piSessionId
                if (sentSessionId != null && bound != null && bound != sentSessionId) {
                    // Rebind observed: the fork completed.
                    logger.info("Fork watchdog: rebind observed for $conversationId ($sentSessionId -> $bound) after $attempts attempts")
                    return@executeOnPooledThread
                }
                logger.info("Fork watchdog: sending /bridge-fork attempt $attempts for $conversationId (bound=$bound)")
                terminal().sendText(conversationId, fullCommand)
                sentSessionId = bound ?: sentSessionId
                try {
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@executeOnPooledThread
                }
            }
            logger.warn("Fork watchdog: giving up for $conversationId after $attempts attempts — session stays on the full copy")
        }
    }

    private fun base64(text: String): String =
        java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

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
