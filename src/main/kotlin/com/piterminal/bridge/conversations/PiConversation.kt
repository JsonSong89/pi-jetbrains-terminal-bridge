package com.piterminal.bridge.conversations

/**
 * A conversation bound to one Pi terminal tab.
 *
 * [id] is the stable plugin-side primary key (also the bridge tabKey).
 * [piSessionId] is the currently bound pi session id; it changes when the
 * user runs /new, /fork or /resume inside the terminal (bridge rebinds it).
 */
class PiConversation(
    val id: String,
    var title: String,
    val tabName: String,
    var piSessionId: String = id,
    val createdAt: Long = System.currentTimeMillis(),
    val messages: MutableList<PiUserMessage> = mutableListOf(),
    var draft: String = "",
    var draftRevision: Long = 0,
    var viewRevision: Long = 0,
    val hiddenEntries: MutableSet<String> = mutableSetOf(),
    val hiddenLegacy: MutableList<PiUserMessage> = mutableListOf(),
    var recoveryText: String = "",
    val appliedOperations: MutableSet<String> = mutableSetOf(),
    var projectionInitialized: Boolean = false
) {
    override fun equals(other: Any?): Boolean = other is PiConversation && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = title
}

data class PiUserMessage(
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String? = null,
    val entryId: String? = null,
    /** Sent from the panel, not yet matched to a native user entry. Never treated as legacy. */
    val pending: Boolean = false
)
