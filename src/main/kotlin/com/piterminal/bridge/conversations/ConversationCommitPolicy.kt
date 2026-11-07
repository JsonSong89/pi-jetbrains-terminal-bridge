package com.piterminal.bridge.conversations

import com.google.gson.JsonObject
import com.piterminal.bridge.bridge.BridgeProtocol
import com.piterminal.bridge.bridge.SessionSnapshot

/** Shared business preconditions kept independent of Swing and disk I/O. */
object ConversationCommitPolicy {
    fun mayApplyRewind(
        response: JsonObject,
        expectedSessionId: String,
        currentSessionId: String,
        expectedViewRevision: Long,
        currentViewRevision: Long,
        after: SessionSnapshot?,
        current: SessionSnapshot?
    ): Boolean = BridgeProtocol.string(response, "status") == "succeeded" &&
        BridgeProtocol.string(response, "effect") == "applied" &&
        after != null && after.usable && current != null && current.usable &&
        after.sessionId == expectedSessionId && after.sessionId == currentSessionId &&
        after.sameState(current) && expectedViewRevision == currentViewRevision

    fun mayRestoreDraft(expectedRevision: Long, currentRevision: Long): Boolean = expectedRevision == currentRevision
}
