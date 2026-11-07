package com.piterminal.bridge.conversations

import com.piterminal.bridge.bridge.SessionSnapshot

/** The message list is a cache of Pi's native active branch, never a second history. */
object ConversationProjection {
    fun apply(c: PiConversation, snapshot: SessionSnapshot): Boolean {
        if (!snapshot.usable || snapshot.sessionId != c.piSessionId) return false
        // Preserve native branch order and identity, including identical prompts.
        val next = snapshot.users().map { PiUserMessage(it.text, it.timestamp, snapshot.sessionId, it.id) }
        if (next == c.messages) return false
        c.messages.clear()
        c.messages.addAll(next)
        c.viewRevision++
        return true
    }
}
