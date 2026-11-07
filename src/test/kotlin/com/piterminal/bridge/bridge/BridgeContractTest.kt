package com.piterminal.bridge.bridge

import com.piterminal.bridge.conversations.ConversationProjection
import com.piterminal.bridge.conversations.ConversationCommitPolicy
import com.piterminal.bridge.conversations.PiConversation
import com.piterminal.bridge.conversations.PiUserMessage
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeContractTest {
    private fun snapshot(vararg texts: String, complete: Boolean = true): SessionSnapshot {
        val entries = texts.mapIndexed { index, text -> mapOf(
            "id" to "u$index", "parentId" to if (index == 0) null else "u${index - 1}", "type" to "message",
            "futureNativeField" to mapOf("preserved" to true, "explicitNull" to null),
            "message" to mapOf("role" to "user", "content" to text, "timestamp" to (index + 1).toLong())
        ) }
        return SessionSnapshot(BridgeProtocol.gson.toJsonTree(mapOf(
            "snapshotId" to "snapshot", "capturedAt" to "diagnostic", "complete" to complete, "consistency" to "stable",
            "session" to mapOf("id" to "session"), "branch" to mapOf("leafId" to texts.indices.lastOrNull()?.let { "u$it" }, "entries" to entries),
            "state" to mapOf("idle" to true), "source" to mapOf("runtimeId" to "runtime", "piVersion" to "1.5.0")
        )).asJsonObject)
    }
    private fun conversation(vararg messages: PiUserMessage) = PiConversation("tab", "Pi", "Pi", "session", messages = messages.toMutableList())

    @Test fun `LF framing preserves Unicode separators and enforces budget before allocation`() {
        val input = ByteArrayInputStream("{\"text\":\"你好\u2028\u2029\"}\r\n{\"next\":true}\n".toByteArray())
        assertEquals("你好\u2028\u2029", BridgeProtocol.readFrame(input)!!.get("text").asString)
        assertTrue(BridgeProtocol.readFrame(input)!!.get("next").asBoolean)
        assertNull(BridgeProtocol.readFrame(input))
        assertFailsWith<IllegalArgumentException> { BridgeProtocol.readFrame(ByteArrayInputStream("{\"large\":123456789}\n".toByteArray()), 8) }
        assertFailsWith<IllegalArgumentException> { BridgeProtocol.readFrame(ByteArrayInputStream("{\"partial\":true}".toByteArray())) }
    }
    @Test fun `incomplete or inconsistent snapshots cannot delete history`() {
        val c = conversation(PiUserMessage("keep"))
        assertFalse(ConversationProjection.apply(c, snapshot(complete = false)))
        val broken = snapshot("other")
        broken.raw.getAsJsonObject("branch").addProperty("leafId", "missing")
        assertFalse(ConversationProjection.apply(c, broken))
        val malformed = snapshot("other")
        malformed.raw.addProperty("state", "invalid state")
        assertFalse(ConversationProjection.apply(c, malformed))
        assertEquals(listOf("keep"), c.messages.map { it.text })
    }
    @Test fun `native unknown fields survive codec and text projection`() {
        val s = snapshot("one")
        assertTrue(s.usable)
        assertTrue(s.entries[0].asJsonObject.getAsJsonObject("futureNativeField").get("preserved").asBoolean)
        val restored = SessionSnapshot(BridgeProtocol.parse(BridgeProtocol.gson.toJson(s.raw)))
        assertTrue(restored.usable)
        assertTrue(restored.entries[0].asJsonObject.get("parentId").isJsonNull)
        assertTrue(restored.entries[0].asJsonObject.getAsJsonObject("futureNativeField").get("explicitNull").isJsonNull)
        val c = conversation()
        ConversationProjection.apply(c, s)
        assertEquals("u0", c.messages.single().entryId)
        assertEquals("session", c.messages.single().sessionId)
    }
    @Test fun `native branch replaces stale legacy and duplicate local cards`() {
        val c = conversation(PiUserMessage("stale from parent", 1), PiUserMessage("repeat", 2),
            PiUserMessage("repeat", 3), PiUserMessage("/model x", 4))
        assertTrue(ConversationProjection.apply(c, snapshot("repeat", "repeat", "only in tui")))
        assertEquals(listOf("repeat", "repeat", "only in tui"), c.messages.map { it.text })
        assertEquals(listOf("u0", "u1", "u2"), c.messages.map { it.entryId })
        assertTrue(c.messages.all { it.sessionId == "session" })
        val revision = c.viewRevision
        assertFalse(ConversationProjection.apply(c, snapshot("repeat", "repeat", "only in tui")))
        assertEquals(revision, c.viewRevision)
    }
    @Test fun `old hidden and pending metadata cannot suppress or add native history`() {
        val legacy = BridgeProtocol.gson.toJsonTree(conversation(PiUserMessage("/model x", 1))).asJsonObject
        legacy.add("hiddenEntries", BridgeProtocol.gson.toJsonTree(listOf("session:u0")))
        legacy.add("hiddenLegacy", BridgeProtocol.gson.toJsonTree(listOf(PiUserMessage("native", 1))))
        legacy.addProperty("projectionInitialized", true)
        legacy.getAsJsonArray("messages")[0].asJsonObject.addProperty("pending", true)
        val restored = BridgeProtocol.gson.fromJson(legacy, PiConversation::class.java)
        assertTrue(ConversationProjection.apply(restored, snapshot("native")))
        assertEquals(listOf(PiUserMessage("native", 1, "session", "u0")), restored.messages)
    }
    @Test fun `empty native branch clears history but other session cannot replace it`() {
        val c = conversation(PiUserMessage("keep", 1))
        val other = snapshot("other")
        other.raw.getAsJsonObject("session").addProperty("id", "another-session")
        assertFalse(ConversationProjection.apply(c, other))
        assertEquals("keep", c.messages.single().text)
        assertTrue(ConversationProjection.apply(c, snapshot()))
        assertTrue(c.messages.isEmpty())
    }

    @Test fun `accepted timeouts and current state resembling success cannot commit an operation`() {
        val after = snapshot("one")
        val response = BridgeProtocol.frame("response").apply { addProperty("status", "accepted") }
        assertFalse(ConversationCommitPolicy.mayApplyRewind(response, "session", "session", 2, 2, after, after))
        response.addProperty("status", "succeeded"); response.addProperty("effect", "applied")
        assertFalse(ConversationCommitPolicy.mayApplyRewind(response, "session", "session", 2, 2, null, after))
        assertFalse(ConversationCommitPolicy.mayApplyRewind(response, "session", "other", 2, 2, after, after))
        assertFalse(ConversationCommitPolicy.mayApplyRewind(response, "session", "session", 2, 3, after, after))
        assertFalse(ConversationCommitPolicy.mayApplyRewind(response, "session", "session", 2, 2, after, snapshot("one", "continued")))
        assertTrue(ConversationCommitPolicy.mayApplyRewind(response, "session", "session", 2, 2, after, after))
        assertFalse(ConversationCommitPolicy.mayRestoreDraft(2, 3))
        assertTrue(ConversationCommitPolicy.mayRestoreDraft(2, 2))
    }

    @Test fun `branch counts split user prompts from assistant and tool entries`() {
        val entries = listOf(
            mapOf("id" to "u0", "parentId" to null, "type" to "message", "message" to mapOf("role" to "user", "content" to "one")),
            mapOf("id" to "a0", "parentId" to "u0", "type" to "message", "message" to mapOf("role" to "assistant", "content" to "ok")),
            mapOf("id" to "u1", "parentId" to "a0", "type" to "message", "message" to mapOf("role" to "user", "content" to "two")),
            mapOf("id" to "call", "parentId" to "u1", "type" to "message", "message" to mapOf("role" to "assistant", "content" to "tool")),
            mapOf("id" to "tool", "parentId" to "call", "type" to "message", "message" to mapOf("role" to "toolResult", "content" to "out")),
            mapOf("id" to "a1", "parentId" to "tool", "type" to "message", "message" to mapOf("role" to "assistant", "content" to "done")),
            mapOf("id" to "note", "parentId" to "a1", "type" to "custom_message", "content" to "hidden")
        )
        val s = SessionSnapshot(BridgeProtocol.gson.toJsonTree(mapOf(
            "snapshotId" to "snapshot", "capturedAt" to "diagnostic", "complete" to true, "consistency" to "stable",
            "session" to mapOf("id" to "session"), "branch" to mapOf("leafId" to "note", "entries" to entries),
            "state" to mapOf("idle" to true), "source" to mapOf("runtimeId" to "runtime", "piVersion" to "1.5.0")
        )).asJsonObject)
        assertTrue(s.usable)
        assertEquals(SessionSnapshot.BranchCounts(2, 4), s.counts())
        assertEquals(SessionSnapshot.BranchCounts(1, 0), snapshot("one").counts())
    }

    private fun timed(vararg prompts: Pair<String, Long>, extra: List<Map<String, Any?>> = emptyList()): SessionSnapshot {
        val entries = prompts.mapIndexed { i, (text, ts) -> mapOf<String, Any?>("id" to "u$i", "type" to "message",
            "message" to mapOf("role" to "user", "content" to text, "timestamp" to ts)) } + extra
        val chained = entries.mapIndexed { i, e -> e + ("parentId" to entries.getOrNull(i - 1)?.get("id")) }
        return SessionSnapshot(BridgeProtocol.gson.toJsonTree(mapOf(
            "snapshotId" to "snapshot", "capturedAt" to "diagnostic", "complete" to true, "consistency" to "stable",
            "session" to mapOf("id" to "session"), "branch" to mapOf("leafId" to chained.lastOrNull()?.get("id"), "entries" to chained),
            "state" to mapOf("idle" to true), "source" to mapOf("runtimeId" to "runtime", "piVersion" to "1.5.0")
        )).asJsonObject)
    }
    @Test fun `bash execution messages without content keep the snapshot usable`() {
        val bash = mapOf<String, Any?>("id" to "bash", "type" to "message", "message" to mapOf("role" to "bashExecution",
            "command" to "ls", "output" to "", "exitCode" to 0, "cancelled" to false, "truncated" to false, "timestamp" to 2))
        assertTrue(timed("one" to 1L, extra = listOf(bash)).usable)
        val broken = mapOf<String, Any?>("id" to "a", "type" to "message", "message" to mapOf("role" to "assistant"))
        assertFalse(timed("one" to 1L, extra = listOf(broken)).usable)
    }
    @Test fun `native text and branch order override local text and timestamps`() {
        val c = conversation(PiUserMessage("a\tb\rc", 1), PiUserMessage("/skill:review", 2))
        val expanded = "<skill name=\"review\">body</skill>"
        assertTrue(ConversationProjection.apply(c, timed("a    b\nc" to 90L, expanded to 10L)))
        assertEquals(listOf("a    b\nc", expanded), c.messages.map { it.text })
        assertEquals(listOf("u0", "u1"), c.messages.map { it.entryId })
        assertEquals(listOf(90L, 10L), c.messages.map { it.timestamp })
    }

    @Test fun `projection preserves draft and restores no off-branch bound messages`() {
        val old = PiUserMessage("one", 1, "session", "u0")
        val c = conversation(old, PiUserMessage("two", 2, "session", "u1"))
        c.draft = "new input"; c.draftRevision = 4
        ConversationProjection.apply(c, snapshot("one"))
        assertEquals(listOf(old), c.messages)
        assertEquals("new input", c.draft); assertEquals(4L, c.draftRevision)
    }
}
