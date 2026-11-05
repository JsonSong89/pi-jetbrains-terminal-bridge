package com.piterminal.bridge.conversations

import com.google.gson.JsonObject
import com.piterminal.bridge.bridge.BridgeProtocol
import com.piterminal.bridge.bridge.SessionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ForkPolicyTest {
    private fun snapshot(session: String = "copy", tail: Boolean = true): SessionSnapshot {
        val entries = listOf(
            entry("u1", null, "user", "repeat"), entry("a1", "u1", "assistant", "reply"),
            entry("u2", "a1", "user", "repeat"), entry("call", "u2", "assistant", "tool call"),
            entry("tool", "call", "toolResult", "tool result"), entry("a2", "tool", "assistant", "final reply")
        ) + if (tail) listOf(entry("u3", "a2", "user", "later")) else emptyList()
        return SessionSnapshot(BridgeProtocol.gson.toJsonTree(mapOf(
            "snapshotId" to "observed", "capturedAt" to "diagnostic", "complete" to true, "consistency" to "stable",
            "session" to mapOf("id" to session), "branch" to mapOf("entries" to entries, "leafId" to if (tail) "u3" else "a2"),
            "state" to mapOf("idle" to true), "source" to mapOf("runtimeId" to "runtime", "piVersion" to "1.5.0")
        )).asJsonObject)
    }
    private fun entry(id: String, parent: String?, role: String, text: String) = mapOf(
        "type" to "message", "id" to id, "parentId" to parent,
        "message" to mapOf("role" to role, "content" to text), "unknownNativeField" to mapOf("keep" to true))

    @Test fun `turn includes all tool messages and duplicate prompts use native ids`() {
        val source = snapshot()
        val first = assertNotNull(ForkPolicy.turnBranch(source, "u1"))
        val second = assertNotNull(ForkPolicy.turnBranch(source, "u2"))
        assertEquals("a1", BridgeProtocol.string(first, "leafId"))
        assertEquals("a2", BridgeProtocol.string(second, "leafId"))
        assertEquals(6, second.getAsJsonArray("entries").size())
        assertTrue(ForkPolicy.matchesCopy(snapshot("other"), second, "u2"))
        val changed = snapshot("other")
        changed.entries[4].asJsonObject.addProperty("unknownNativeField", "changed")
        assertFalse(ForkPolicy.matchesCopy(changed, second, "u2"))
        changed.entries[2].asJsonObject.addProperty("id", "new-id")
        assertFalse(ForkPolicy.matchesCopy(changed, second, "u2"))
    }

    @Test fun `startup metadata may follow copied turn but additional replies cannot`() {
        val original = snapshot(tail = false)
        val expected = assertNotNull(ForkPolicy.turnBranch(original, "u2"))
        val copy = snapshot(tail = false)
        val metadata = JsonObject().apply { addProperty("id", "name"); addProperty("parentId", "a2"); addProperty("type", "session_info") }
        copy.entries.add(metadata)
        copy.raw.getAsJsonObject("branch").addProperty("leafId", "name")
        assertTrue(ForkPolicy.matchesCopy(copy, expected, "u2"))
        val result = JsonObject().apply { addProperty("status", "succeeded"); addProperty("effect", "applied") }
        val after = SessionSnapshot(copy.raw.deepCopy())
        after.raw.getAsJsonObject("session").addProperty("id", "replacement")
        assertTrue(ForkPolicy.mayCommit(result, "copy", expected, after, after, 0, 0))
        metadata.addProperty("type", "custom")
        assertFalse(ForkPolicy.matchesCopy(copy, expected, "u2"))
    }

    @Test fun `only confirmed matching replacement can commit while local draft stays independent`() {
        val before = snapshot()
        val branch = assertNotNull(ForkPolicy.turnBranch(before, "u2"))
        val after = snapshot("replacement", tail = false)
        val result = JsonObject().apply { addProperty("status", "accepted") }
        assertFalse(ForkPolicy.mayCommit(result, "copy", branch, after, after, 3, 3))
        result.addProperty("status", "succeeded"); result.addProperty("effect", "applied")
        assertFalse(ForkPolicy.mayCommit(result, "copy", branch, null, after, 3, 3))
        assertFalse(ForkPolicy.mayCommit(result, "replacement", branch, after, after, 3, 3))
        assertFalse(ForkPolicy.mayCommit(result, "copy", branch, after, snapshot("replacement"), 3, 3))
        assertFalse(ForkPolicy.mayCommit(result, "copy", branch, after, after, 3, 4))
        assertTrue(ForkPolicy.mayCommit(result, "copy", branch, after, after, 3, 3))
        val target = PiConversation("tab", "fork", "Pi", "copy", messages = mutableListOf(
            PiUserMessage("repeat", 1, "copy", "u1"), PiUserMessage("repeat", 2, "copy", "u2"), PiUserMessage("later", 3, "copy", "u3")),
            draft = "typed while waiting", draftRevision = 7, hiddenEntries = mutableSetOf("copy:u1"), projectionInitialized = true)
        ForkPolicy.rebind(target, after.sessionId)
        ConversationProjection.apply(target, after)
        assertEquals(listOf("u2"), target.messages.map { it.entryId })
        assertTrue("replacement:u1" in target.hiddenEntries)
        assertEquals("typed while waiting", target.draft)
        assertEquals(7L, target.draftRevision)
        assertEquals("copy", before.sessionId)
    }
}
