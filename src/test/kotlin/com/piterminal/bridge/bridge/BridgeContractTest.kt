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
    private fun conversation(vararg messages: PiUserMessage) = PiConversation("tab", "Pi", "Pi", "session", messages = messages.toMutableList(), projectionInitialized = true)

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
        assertFalse(ConversationProjection.apply(c, snapshot(complete = false)).changed)
        val broken = snapshot("other")
        broken.raw.getAsJsonObject("branch").addProperty("leafId", "missing")
        assertFalse(ConversationProjection.apply(c, broken).changed)
        val malformed = snapshot("other")
        malformed.raw.addProperty("state", "invalid state")
        assertFalse(ConversationProjection.apply(c, malformed).changed)
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
    @Test fun `unique ordered migration binds duplicate text only when the whole sequence proves identity`() {
        val c = conversation(PiUserMessage("repeat", 1), PiUserMessage("repeat", 2))
        assertFalse(ConversationProjection.apply(c, snapshot("repeat", "repeat")).ambiguous)
        assertEquals(listOf("u0", "u1"), c.messages.map { it.entryId })
        val ambiguous = conversation(PiUserMessage("repeat", 1))
        assertTrue(ConversationProjection.apply(ambiguous, snapshot("repeat", "repeat")).ambiguous)
        assertEquals(1, ambiguous.messages.size)
        assertNull(ambiguous.messages.single().entryId)
    }
    @Test fun `confirmed anchors prevent mapping an older legacy card to a later prompt`() {
        val c = conversation(PiUserMessage("later", 1), PiUserMessage("anchor", 2, "session", "u0"))
        assertTrue(ConversationProjection.apply(c, snapshot("anchor", "later")).ambiguous)
        assertNull(c.messages.first { it.text == "later" }.entryId)
        assertEquals("u0", c.messages.first { it.text == "anchor" }.entryId)
    }
    @Test fun `first legacy migration preserves removed cards while later TUI messages appear`() {
        val c = conversation(PiUserMessage("one", 1))
        c.projectionInitialized = false
        ConversationProjection.apply(c, snapshot("one", "old removed card"))
        assertEquals(listOf("one"), c.messages.map { it.text })
        assertTrue("session:u1" in c.hiddenEntries)
        ConversationProjection.apply(c, snapshot("one", "old removed card", "new TUI prompt"))
        assertEquals(listOf("one", "new TUI prompt"), c.messages.map { it.text })
    }

    @Test fun `hidden native and legacy cards do not resurrect on refresh`() {
        val c = conversation(PiUserMessage("one", 1, "session", "u0"))
        c.hiddenEntries.add(ConversationProjection.key("session", "u1"))
        ConversationProjection.apply(c, snapshot("one", "two"))
        assertEquals(listOf("one"), c.messages.map { it.text })
        val old = conversation()
        old.hiddenLegacy.add(PiUserMessage("hidden", 1))
        assertTrue(ConversationProjection.apply(old, snapshot("hidden")).changed)
        assertTrue(old.messages.isEmpty())
        assertTrue(old.hiddenLegacy.isEmpty())
        assertTrue("session:u0" in old.hiddenEntries)
        ConversationProjection.apply(old, snapshot("hidden"))
        assertTrue(old.messages.isEmpty())
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

    @Test fun `projection preserves draft and restores no off-branch bound messages`() {
        val old = PiUserMessage("one", 1, "session", "u0")
        val c = conversation(old, PiUserMessage("two", 2, "session", "u1"))
        c.draft = "new input"; c.draftRevision = 4
        ConversationProjection.apply(c, snapshot("one"))
        assertEquals(listOf(old), c.messages)
        assertTrue(c.messages.single() === old)
        assertEquals("new input", c.draft); assertEquals(4L, c.draftRevision)
    }
}
