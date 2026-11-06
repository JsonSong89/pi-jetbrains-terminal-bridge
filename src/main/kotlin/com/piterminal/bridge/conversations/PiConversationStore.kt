package com.piterminal.bridge.conversations

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Persistent index of conversations (project level). Pi's own session jsonl
 * files remain the authoritative store of conversation data; this only keeps
 * the plugin-side index, draft and an offline cache of native user messages.
 * Obsolete hidden/pending XML fields are ignored; they never filter Pi history.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "PiTerminalBridgeConversations",
    storages = [Storage("PiTerminalBridgeConversations.xml")]
)
class PiConversationStore(private val project: Project) : PersistentStateComponent<PiConversationStore.State> {

    class Entry {
        var id: String = ""
        var title: String = ""
        var tabName: String = ""
        var piSessionId: String = ""
        var createdAt: Long = 0
        var draft: String = ""
        var messages: MutableList<Msg> = mutableListOf()
        var draftRevision: Long = 0
        var viewRevision: Long = 0
        var recoveryText: String = ""
        var appliedOperations: MutableList<String> = mutableListOf()
    }

    class Msg {
        var text: String = ""
        var timestamp: Long = 0
        var sessionId: String? = null
        var entryId: String? = null
    }

    class State {
        var entries: MutableList<Entry> = mutableListOf()
        var activeId: String? = null
        var titleSeq: Int = 1
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        // Drop entries that lost required fields (corrupt/legacy XML).
        // Blank piSessionId is valid: it marks a stale entry (session file
        // deleted on the pi side) that gets a fresh id on next launch.
        state.entries = state.entries.filter {
            it.id.isNotBlank() && it.title.isNotBlank()
        }.toMutableList()
        if (state.activeId != null && state.entries.none { it.id == state.activeId }) {
            state.activeId = null
        }
        myState = state
    }

    fun snapshot(): List<StoredConversation> = myState.entries.map { e ->
        StoredConversation(
            id = e.id,
            title = e.title,
            tabName = e.tabName,
            piSessionId = e.piSessionId,
            createdAt = e.createdAt,
            draft = e.draft,
            messages = e.messages.map { PiUserMessage(it.text, it.timestamp, it.sessionId, it.entryId) },
            draftRevision = e.draftRevision, viewRevision = e.viewRevision,
            recoveryText = e.recoveryText, appliedOperations = e.appliedOperations.toSet()
        )
    }

    fun activeId(): String? = myState.activeId

    fun titleSeq(): Int = myState.titleSeq

    fun nextTitleSeq(): Int = myState.titleSeq++

    fun save(conversations: List<PiConversation>, activeId: String?) {
        myState.entries = conversations.map { c ->
            Entry().apply {
                id = c.id
                title = c.title
                tabName = c.tabName
                piSessionId = c.piSessionId
                createdAt = c.createdAt
                draft = c.draft
                draftRevision = c.draftRevision
                viewRevision = c.viewRevision
                recoveryText = c.recoveryText
                appliedOperations = c.appliedOperations.toMutableList()
                messages = c.messages.map { message(it) }.toMutableList()
            }
        }.toMutableList()
        myState.activeId = activeId
    }

    private fun message(m: PiUserMessage): Msg = Msg().apply {
        text = m.text; timestamp = m.timestamp; sessionId = m.sessionId; entryId = m.entryId
    }

    data class StoredConversation(
        val id: String,
        val title: String,
        val tabName: String,
        val piSessionId: String,
        val createdAt: Long,
        val draft: String,
        val messages: List<PiUserMessage>,
        val draftRevision: Long,
        val viewRevision: Long,
        val recoveryText: String,
        val appliedOperations: Set<String>
    )

    companion object {
        fun getInstance(project: Project): PiConversationStore = project.service()
    }
}
