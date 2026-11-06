package com.piterminal.bridge.conversations

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.piterminal.bridge.bridge.BridgeProtocol
import com.piterminal.bridge.bridge.SessionSnapshot

/** Native identity checks shared by preparation and confirmed result commits. */
object ForkPolicy {
    private val startupEntries = setOf("session_info", "model_change", "thinking_level_change")

    fun turnBranch(snapshot: SessionSnapshot, targetEntryId: String): JsonObject? {
        if (!snapshot.usable) return null
        val entries = snapshot.entries.toList()
        val start = entries.indexOfFirst { BridgeProtocol.string(it.asJsonObject, "id") == targetEntryId && isUser(it.asJsonObject) }
        if (start < 0) return null
        val end = (start + 1 until entries.size).firstOrNull { isUser(entries[it].asJsonObject) } ?: entries.size
        return JsonObject().apply {
            addProperty("leafId", BridgeProtocol.string(entries[end - 1].asJsonObject, "id"))
            add("entries", JsonArray().apply { entries.take(end).forEach { add(it.deepCopy()) } })
        }
    }

    fun matchesCopy(snapshot: SessionSnapshot, expected: JsonObject, targetEntryId: String): Boolean {
        val turn = turnBranch(snapshot, targetEntryId) ?: return false
        return matchesEntries(turn.getAsJsonArray("entries"), expected)
    }

    private fun matchesEntries(entries: JsonArray, expected: JsonObject): Boolean {
        val actual = entries.toList()
        val wanted = expected.getAsJsonArray("entries").toList()
        return wanted.isNotEmpty() && actual.take(wanted.size) == wanted &&
            actual.drop(wanted.size).all { BridgeProtocol.string(it.asJsonObject, "type") in startupEntries }
    }

    fun mayCommit(response: JsonObject, beforeSessionId: String, expectedBranch: JsonObject,
                  after: SessionSnapshot?, current: SessionSnapshot?, expectedRevision: Long, revision: Long): Boolean =
        BridgeProtocol.string(response, "status") == "succeeded" && BridgeProtocol.string(response, "effect") == "applied" &&
            after?.usable == true && current?.usable == true && after.sameState(current) &&
            after.sessionId != beforeSessionId && matchesEntries(after.entries, expectedBranch) && expectedRevision == revision

    fun rebind(c: PiConversation, sessionId: String) {
        val previous = c.piSessionId
        c.messages.replaceAll { if (it.sessionId == previous) it.copy(sessionId = sessionId) else it }
        c.piSessionId = sessionId
    }

    private fun isUser(entry: JsonObject): Boolean = BridgeProtocol.string(entry, "type") == "message" &&
        BridgeProtocol.string(entry.getAsJsonObject("message"), "role") == "user"
}
