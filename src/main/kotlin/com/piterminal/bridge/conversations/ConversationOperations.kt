package com.piterminal.bridge.conversations

import com.google.gson.JsonObject
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.piterminal.bridge.PiBridgeUi
import com.piterminal.bridge.bridge.BridgeOperations
import com.piterminal.bridge.bridge.BridgeProtocol
import com.piterminal.bridge.bridge.PiBridgeServer
import com.piterminal.bridge.bridge.SessionSnapshot

/** Business commit policy. Transport and remote execution states stay below this layer. */
class ConversationOperations(private val project: Project, private val service: PiConversationService) {
    private val bridge get() = BridgeOperations.getInstance(project)
    private val committing = mutableSetOf<String>()
    private val restored = mutableSetOf<String>()

    fun rewind(tab: String, index: Int): Boolean {
        val c = service.get(tab) ?: return false
        val message = c.messages.getOrNull(index) ?: return false
        val snapshot = bridge.snapshot(tab)
        if (bridge.locked(tab)) return warn("A prior operation is unresolved. Its original request is being queried; keep editing your draft or reconcile a confirmed result.")
        if (PiBridgeServer.getInstance(project).connection(tab)?.supports("conversation.rewind") != true || snapshot?.usable != true) {
            return warn("Rewind needs a ready bridge and verified pi 1.5.0. Restart the Pi terminal after updating the extension.")
        }
        if (message.entryId == null || message.sessionId != snapshot.sessionId || c.piSessionId != snapshot.sessionId ||
            snapshot.users().none { it.id == message.entryId }) return warn("This cached message is not on the current Pi branch. Wait for session synchronization before rewinding.")
        val business = JsonObject().apply {
            addProperty("sessionId", c.piSessionId); addProperty("text", message.text)
            addProperty("draftRevision", c.draftRevision); addProperty("viewRevision", c.viewRevision)
        }
        val id = bridge.submit(tab, "conversation.rewind", JsonObject().apply { addProperty("targetEntryId", message.entryId) }, snapshot, business)
            ?: return warn("The operation channel is unavailable or another operation is unresolved.")
        service.operationChanged(tab)
        return id.id.isNotEmpty()
    }

    /** Replace the display cache with native user entries; never infer entry identities. */
    fun project(c: PiConversation, snapshot: SessionSnapshot): Boolean = ConversationProjection.apply(c, snapshot)

    fun receive(record: JsonObject) {
        val tab = BridgeProtocol.string(record, "tabKey") ?: return
        val c = service.get(tab) ?: return
        val id = BridgeProtocol.string(record, "id") ?: return
        val local = BridgeProtocol.string(record, "localState")
        if (local in setOf("applied", "reconciled", "conflict") && restored.add(id)) {
            record.getAsJsonObject("checkpoint")?.let { checkpoint ->
                val business = record.getAsJsonObject("business")
                if (id !in c.appliedOperations && checkpoint.get("viewRevision").asLong >= c.viewRevision &&
                    (BridgeProtocol.string(business, "kind") == "fork" || business.get("forkReplacement")?.asBoolean == true) &&
                    c.piSessionId == BridgeProtocol.string(business, "sessionId")) {
                    BridgeProtocol.string(checkpoint, "piSessionId")?.let { service.adoptForkSession(c, it) }
                }
                restoreCheckpoint(c, checkpoint, id)
            }
        }
        if (local in setOf("applied", "reconciled", "rejected")) {
            committing.remove(id)
            // A snapshot may have arrived while this operation still held the lock.
            // Once all locks are released, display the latest native branch.
            if (!bridge.locked(tab)) bridge.snapshot(tab)?.let { service.onBridgeSnapshot(tab, it) }
            service.operationChanged(tab)
            return
        }
        if (local == "not_sent") {
            if (committing.add(id)) warn("Operation was not sent. The panel and draft were preserved.")
            service.operationChanged(tab)
            bridge.snapshot(tab)?.let { service.onBridgeSnapshot(tab, it) }
            return
        }
        if (local == "conflict" || id in committing) { service.operationChanged(tab); return }
        if (id in c.appliedOperations) { bridge.commit(id, "applied", checkpoint(c)); return }
        val response = record.getAsJsonObject("response") ?: run { service.operationChanged(tab); return }
        if (!BridgeProtocol.terminal(response)) { service.operationChanged(tab); return }
        committing.add(id)
        val business = record.getAsJsonObject("business")
        if (BridgeProtocol.string(record.getAsJsonObject("request"), "method") == "operation.reconcile") {
            finishReconciliation(c, id, business, response)
            return
        }
        if (BridgeProtocol.string(response, "status") != "succeeded") {
            val none = BridgeProtocol.string(response, "effect") == "none"
            bridge.commit(id, if (none) "rejected" else "conflict")
            warn(BridgeProtocol.string(response.getAsJsonObject("error"), "message") ?: "Operation could not be confirmed; the panel was preserved.")
            return
        }
        if (BridgeProtocol.string(record.getAsJsonObject("request"), "method") == ForkWorkflow.METHOD) {
            finishFork(c, id, record, response)
            return
        }
        val context = response.getAsJsonObject("context")
        val after = context?.getAsJsonObject("afterSnapshot")?.let(::SessionSnapshot)
        val current = bridge.snapshot(tab)
        if (current == null) {
            // Recovery waits for a fresh observation; an old result alone cannot commit a view.
            committing.remove(id); service.operationChanged(tab); return
        }
        val valid = response.getAsJsonObject("data")?.get("targetEntryId") == record.getAsJsonObject("request").getAsJsonObject("params").get("targetEntryId") &&
            ConversationCommitPolicy.mayApplyRewind(response,
            BridgeProtocol.string(business, "sessionId").orEmpty(), c.piSessionId,
            business.get("viewRevision").asLong, c.viewRevision, after, current)
        if (!valid) {
            c.recoveryText = BridgeProtocol.string(business, "text").orEmpty()
            service.saveOperationView(tab)
            bridge.commit(id, "conflict", checkpoint(c))
            warn("Pi reported a result, but the session or panel has moved on. The panel was preserved; use the toolbar's Sync current session action to adopt the confirmed current state.")
            return
        }
        project(c, after!!)
        val text = BridgeProtocol.string(business, "text").orEmpty()
        if (ConversationCommitPolicy.mayRestoreDraft(business.get("draftRevision").asLong, c.draftRevision)) {
            c.draft = text; c.draftRevision++
        } else c.recoveryText = text
        c.appliedOperations.add(id)
        service.saveOperationView(tab, draft = true)
        bridge.commit(id, "applied", checkpoint(c))
    }

    private fun finishFork(c: PiConversation, id: String, record: JsonObject, response: JsonObject) {
        val business = record.getAsJsonObject("business")
        val after = response.getAsJsonObject("context")?.getAsJsonObject("afterSnapshot")?.let(::SessionSnapshot)
        val current = bridge.snapshot(c.id)
        if (current == null) { committing.remove(id); return }
        val params = record.getAsJsonObject("request").getAsJsonObject("params")
        val data = response.getAsJsonObject("data")
        val valid = data?.get("targetEntryId") == params.get("targetEntryId") && data?.get("turnLeafId") == params.get("turnLeafId") &&
            BridgeProtocol.string(data, "sessionId") == after?.sessionId && c.piSessionId == BridgeProtocol.string(business, "sessionId") &&
            ForkPolicy.mayCommit(response, c.piSessionId, business.getAsJsonObject("expectedBranch"), after, current,
                business.get("viewRevision").asLong, c.viewRevision)
        if (!valid) {
            bridge.commit(id, "conflict")
            warn("Fork returned a result, but the copied session or panel has moved on. Use the toolbar's Sync current session action to reconcile; the fork will not be repeated.")
            return
        }
        service.adoptForkSession(c, after!!.sessionId)
        project(c, after)
        c.appliedOperations.add(id)
        service.saveOperationView(c.id)
        bridge.commit(id, "applied", checkpoint(c))
    }

    private fun unresolvedRecords(tab: String): List<JsonObject> = bridge.recordsFor(tab).filter {
        BridgeProtocol.string(it, "localState") !in setOf("applied", "reconciled", "rejected", "not_sent")
    }

    private fun executionEnded(record: JsonObject): Boolean {
        val response = record.getAsJsonObject("response") ?: return false
        return BridgeProtocol.terminal(response) || response.get("executionEnded")?.asBoolean == true
    }

    /** Recovery is offered only when a current observation and ended executions make it actionable. */
    fun canReconcile(tab: String): Boolean {
        if (bridge.snapshot(tab)?.usable != true ||
            PiBridgeServer.getInstance(project).connection(tab)?.supports("operation.reconcile") != true) return false
        val records = unresolvedRecords(tab)
        return records.isNotEmpty() && records.all(::executionEnded)
    }

    /** A separate durable metadata operation confirms termination; it never reexecutes the old intent. */
    fun reconcile(tab: String): Boolean {
        val snapshot = bridge.snapshot(tab) ?: return warn("No current full snapshot is available yet.")
        val records = unresolvedRecords(tab)
        if (records.isEmpty()) { bridge.requestSnapshot(tab); return true }
        if (records.any { !executionEnded(it) }) {
            return warn("An old execution has not been confirmed ended. Queries will continue without releasing its lock.")
        }
        val c = service.get(tab) ?: return false
        val originalIds = records.mapNotNull { BridgeProtocol.string(it, "id") }.toSet()
        val ids = BridgeProtocol.gson.toJsonTree(originalIds).asJsonArray
        val business = JsonObject().apply {
            add("originalIds", ids)
            addProperty("text", records.firstNotNullOfOrNull { BridgeProtocol.string(it.getAsJsonObject("business"), "text") })
            addProperty("viewRevision", c.viewRevision)
            addProperty("sessionId", c.piSessionId)
            addProperty("forkReplacement", records.any { BridgeProtocol.string(it.getAsJsonObject("business"), "kind") == "fork" ||
                it.getAsJsonObject("business").get("forkReplacement")?.asBoolean == true })
        }
        return bridge.submit(tab, "operation.reconcile", JsonObject().apply { add("operationIds", ids.deepCopy()) }, snapshot, business, reconcileOf = originalIds) != null
    }

    private fun finishReconciliation(c: PiConversation, id: String, business: JsonObject, response: JsonObject) {
        if (BridgeProtocol.string(response, "status") != "succeeded") {
            bridge.commit(id, if (BridgeProtocol.string(response, "effect") == "none") "rejected" else "conflict")
            warn(BridgeProtocol.string(response.getAsJsonObject("error"), "message") ?: "Reconciliation was not confirmed.")
            return
        }
        val after = response.getAsJsonObject("context")?.getAsJsonObject("afterSnapshot")?.let(::SessionSnapshot)
        val current = bridge.snapshot(c.id)
        if (current == null) { committing.remove(id); return }
        if (BridgeProtocol.string(response, "effect") != "none" || after?.usable != true || !after.sameState(current) || c.viewRevision != business.get("viewRevision").asLong ||
            response.getAsJsonObject("data")?.get("operationIds") != business.get("originalIds")) {
            bridge.commit(id, "conflict")
            warn("The state moved during reconciliation. The panel was preserved.")
            return
        }
        val originalIds = business.getAsJsonArray("originalIds").map { it.asString }
        if (business.get("forkReplacement")?.asBoolean == true) service.adoptForkSession(c, after.sessionId)
        else service.onBridgeSessionChanged(c.id, after.sessionId, "v2")
        project(c, after)
        c.recoveryText = BridgeProtocol.string(business, "text").orEmpty()
        c.appliedOperations.addAll(originalIds); c.appliedOperations.add(id)
        service.saveOperationView(c.id)
        val checkpoint = checkpoint(c)
        bridge.commit(id, "applied", checkpoint)
        originalIds.forEach { bridge.commit(it, "reconciled", checkpoint) }
    }

    private fun checkpoint(c: PiConversation): JsonObject = BridgeProtocol.gson.toJsonTree(c).asJsonObject
    private fun restoreCheckpoint(c: PiConversation, value: JsonObject, id: String) {
        if (id in c.appliedOperations || BridgeProtocol.string(value, "piSessionId") != c.piSessionId) return
        val saved = BridgeProtocol.gson.fromJson(value, PiConversation::class.java)
        // Historical checkpoints are operation evidence, not current session history.
        // Only a fresh native snapshot may replace the display cache.
        if (saved.draftRevision >= c.draftRevision) {
            c.draft = saved.draft; c.draftRevision = saved.draftRevision
            c.recoveryText = saved.recoveryText
        }
        c.appliedOperations.addAll(saved.appliedOperations)
        service.saveOperationView(c.id)
    }
    private fun warn(text: String): Boolean { PiBridgeUi.notify(project, text, NotificationType.WARNING); return false }
}
