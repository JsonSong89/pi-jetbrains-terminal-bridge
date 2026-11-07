package com.piterminal.bridge.conversations

import com.google.gson.JsonObject
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.piterminal.bridge.PiBridgeUi
import com.piterminal.bridge.bridge.BridgeOperations
import com.piterminal.bridge.bridge.BridgeProtocol
import com.piterminal.bridge.bridge.PiBridgeServer
import com.piterminal.bridge.bridge.SessionSnapshot
import com.piterminal.bridge.services.PiTerminalService

/** Event-driven preparation of a separate terminal; execution/recovery stays in BridgeOperations. */
class ForkWorkflow(private val project: Project, private val service: PiConversationService) {
    private val bridge get() = BridgeOperations.getInstance(project)
    // Only preparations created in this IDE runtime may launch/dispatch. Reloaded intents never execute.
    private val active = mutableSetOf<String>()
    private val launched = mutableSetOf<String>()

    fun start(sourceId: String, index: Int): PiConversation? {
        val source = service.get(sourceId) ?: return null
        val message = source.messages.getOrNull(index) ?: return null
        val snapshot = bridge.snapshot(sourceId)
        if (bridge.locked(sourceId) || snapshot?.usable != true || snapshot.sessionId != source.piSessionId ||
            PiBridgeServer.getInstance(project).connection(sourceId)?.supports(METHOD) != true) {
            warn("Fork needs a ready bridge with fork capability and no unresolved source operation."); return null
        }
        val state = snapshot.raw.getAsJsonObject("state")
        if (state.get("idle")?.asBoolean != true || state.get("pendingMessages")?.asBoolean == true) {
            warn("Pi is busy. Fork was not started."); return null
        }
        val targetEntryId = message.entryId?.takeIf { message.sessionId == snapshot.sessionId }
        val expected = targetEntryId?.let { ForkPolicy.turnBranch(snapshot, it) }
        if (expected == null || snapshot.entries.any { BridgeProtocol.string(it.asJsonObject, "type") == "label" }) {
            warn("Fork needs an exact native message binding and a branch without labels."); return null
        }
        val sourceFile = BridgeProtocol.string(snapshot.raw.getAsJsonObject("session"), "file")
        if (sourceFile.isNullOrBlank()) { warn("Fork needs a persisted source session."); return null }
        val target = service.newForkTarget(source)
        val business = JsonObject().apply {
            addProperty("kind", "fork"); addProperty("sourceSessionFile", sourceFile)
            addProperty("targetEntryId", targetEntryId); add("expectedBranch", expected)
            addProperty("sessionId", target.piSessionId); addProperty("viewRevision", target.viewRevision)
            add("target", BridgeProtocol.gson.toJsonTree(target))
        }
        val handle = bridge.prepare(target.id, business) ?: return null
        active.add(handle.id)
        service.addForkTarget(target, activate = true)
        return target
    }

    /** Returns true only for preparation; submitted requests use the normal business result handler. */
    fun receive(record: JsonObject): Boolean {
        if (BridgeProtocol.string(record.getAsJsonObject("business"), "kind") != "fork") return false
        val id = BridgeProtocol.string(record, "id") ?: return true
        val local = BridgeProtocol.string(record, "localState")
        val tab = BridgeProtocol.string(record, "tabKey") ?: return true
        if (service.get(tab) == null && local !in setOf("applied", "reconciled", "rejected", "not_sent")) {
            val target = BridgeProtocol.gson.fromJson(record.getAsJsonObject("business").get("target"), PiConversation::class.java)
            service.addForkTarget(target, activate = false)
        }
        if (local != "preparing") { active.remove(id); launched.remove(id); return false }
        if (id !in active) return true
        val c = service.get(tab) ?: return true
        val sourceFile = BridgeProtocol.string(record.getAsJsonObject("business"), "sourceSessionFile")
        if (sourceFile.isNullOrBlank()) { stop(id, "Fork preparation is missing its source file; nothing was launched."); return true }
        if (launched.add(id)) {
            PiTerminalService.getInstance(project).launch(c.id, c.tabName, c.piSessionId, c.title, forkSourceFile = sourceFile)
        }
        bridge.snapshot(tab)?.let { advance(record, it) }
        service.operationChanged(tab)
        return true
    }

    fun onSnapshot(tab: String, snapshot: SessionSnapshot) {
        bridge.recordsFor(tab).filter { BridgeProtocol.string(it, "localState") == "preparing" &&
            BridgeProtocol.string(it, "id") in active }.forEach { advance(it, snapshot) }
    }

    private fun advance(record: JsonObject, snapshot: SessionSnapshot) {
        val tab = BridgeProtocol.string(record, "tabKey") ?: return
        val id = BridgeProtocol.string(record, "id") ?: return
        val connection = PiBridgeServer.getInstance(project).connection(tab) ?: return
        if (connection.runtimeId != snapshot.runtimeId || !snapshot.usable) return
        val business = record.getAsJsonObject("business")
        if (!connection.supports(METHOD) || snapshot.sessionId != BridgeProtocol.string(business, "sessionId") ||
            !ForkPolicy.matchesCopy(snapshot, business.getAsJsonObject("expectedBranch"), BridgeProtocol.string(business, "targetEntryId").orEmpty())) {
            stop(id, "Fork preparation could not verify the copied turn. The copy was kept; no fork request was sent.")
            return
        }
        if (snapshot.raw.getAsJsonObject("state").get("idle")?.asBoolean != true ||
            snapshot.raw.getAsJsonObject("state").get("pendingMessages")?.asBoolean == true) {
            stop(id, "The target Pi is busy. The copy was kept; no fork request was queued."); return
        }
        val params = JsonObject().apply {
            add("targetEntryId", business.get("targetEntryId").deepCopy())
            add("turnLeafId", business.getAsJsonObject("expectedBranch").get("leafId").deepCopy())
        }
        bridge.dispatchPrepared(id, METHOD, params, snapshot)
    }

    /** Stop a preparation whose execution request does not exist. */
    fun stopPreparing(tab: String): Boolean {
        val records = bridge.recordsFor(tab).filter { BridgeProtocol.string(it, "localState") == "preparing" }
        records.forEach { stop(BridgeProtocol.string(it, "id")!!, "Fork preparation stopped. The copy was kept; no fork request was sent.") }
        return records.isNotEmpty()
    }

    private fun stop(id: String, message: String) {
        active.remove(id); launched.remove(id); bridge.stopPreparation(id); warn(message)
    }
    private fun warn(message: String) = PiBridgeUi.notify(project, message, NotificationType.WARNING)
    companion object { const val METHOD = "conversation.forkAtTurn" }
}
