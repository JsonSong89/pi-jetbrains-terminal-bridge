package com.piterminal.bridge.bridge

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.piterminal.bridge.conversations.PiConversationService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class OperationHandle internal constructor(val id: String, private val runtime: BridgeOperations) {
    val lastConfirmedStatus: String? get() = runtime.record(id)?.getAsJsonObject("response")?.let { BridgeProtocol.string(it, "status") }
    val observation: String? get() = runtime.record(id)?.let { BridgeProtocol.string(it, "observation") }
    fun query() = runtime.query(id)
    fun subscribe(listener: (JsonObject) -> Unit): AutoCloseable = runtime.subscribe(id, listener)
}

/** Correlation, durable receipt and query-only recovery. No Swing or conversation editing here. */
@Service(Service.Level.PROJECT)
class BridgeOperations(private val project: Project) : Disposable {
    val store = OperationStore(project)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "PiBridgeOperations").apply { isDaemon = true }
    }
    private val records = ConcurrentHashMap<String, JsonObject>()
    // Immutable copies published to EDT readers; mutable journal records stay on the executor.
    private val recordViews = ConcurrentHashMap<String, JsonObject>()
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<(JsonObject) -> Unit>>()
    private val recordScheduled = ConcurrentHashMap.newKeySet<String>()
    internal fun record(id: String): JsonObject? = recordViews[id]?.deepCopy()
    fun handle(id: String): OperationHandle? = if (recordViews.containsKey(id)) OperationHandle(id, this) else null
    internal fun query(id: String) {
        val record = recordViews[id] ?: return
        if (!record.has("request")) return
        val tab = BridgeProtocol.string(record, "tabKey") ?: return
        PiBridgeServer.getInstance(project).connection(tab)?.send(BridgeProtocol.frame("query").apply { addProperty("id", id) })
    }
    internal fun subscribe(id: String, listener: (JsonObject) -> Unit): AutoCloseable {
        val listeners = subscribers.computeIfAbsent(id) { CopyOnWriteArrayList() }
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }
    private val snapshots = ConcurrentHashMap<String, SessionSnapshot>()
    private val snapshotScheduled = ConcurrentHashMap.newKeySet<String>()
    private val contextErrors = ConcurrentHashMap<String, String>()
    private val readRequests = ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false
    @Volatile private var damaged = false

    init {
        executor.execute {
            try {
                store.records().forEach { record ->
                    if (record.get("sent")?.asBoolean == false && !record.has("response")) record.addProperty("localState", "not_sent")
                    if (record.get("sent")?.asBoolean == true && !terminal(record)) record.addProperty("observation", "unknown")
                    records[BridgeProtocol.string(record, "id")!!] = record
                }
            } catch (_: Exception) { damaged = true }
            records.values.forEach { recordViews[BridgeProtocol.string(it, "id")!!] = it.deepCopy() }
            loaded = true
            records.values.forEach(::publish)
        }
        executor.scheduleWithFixedDelay({
            if (!project.isDisposed) records.values.filter { unresolved(it) && it.has("request") }.forEach { record ->
                val tab = BridgeProtocol.string(record, "tabKey") ?: return@forEach
                val connection = PiBridgeServer.getInstance(project).connection(tab)
                if (connection != null) {
                    val elapsed = System.currentTimeMillis() - (record.get("lastResponseAt")?.asLong ?: 0L)
                    if (elapsed > 15_000 && BridgeProtocol.string(record, "observation") != "unknown") {
                        record.addProperty("observation", "unknown")
                        runCatching { saveAndPublish(record) }.onFailure { publish(record) }
                    }
                    connection.send(BridgeProtocol.frame("query").apply { addProperty("id", BridgeProtocol.string(record, "id")) })
                }
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    fun preparing(tab: String): Boolean = recordViews.values.any {
        BridgeProtocol.string(it, "tabKey") == tab && BridgeProtocol.string(it, "localState") == "preparing"
    }
    fun snapshot(tab: String): SessionSnapshot? = snapshots[tab]
    fun locked(tab: String): Boolean = !loaded || damaged || recordViews.values.any {
        BridgeProtocol.string(it, "tabKey") == tab && unresolved(it)
    }
    fun status(tab: String): String? {
        if (damaged) return "Operation journal needs reconciliation"
        val r = recordViews.values.firstOrNull { BridgeProtocol.string(it, "tabKey") == tab && unresolved(it) }
            ?: return contextErrors[tab]?.let { "Session context unavailable · $it" }
        return when (BridgeProtocol.string(r, "localState")) {
            "preparing" -> "Preparing conversation · waiting for bridge"
            "conflict" -> "Result needs reconciliation"
            else -> when (BridgeProtocol.string(r, "observation")) {
                "unknown" -> "Operation result unknown · checking"
                "disconnected" -> "Operation result unknown · bridge disconnected"
                else -> "Operation awaiting confirmation"
            }
        }
    }
    private fun terminal(record: JsonObject): Boolean = record.getAsJsonObject("response")?.let(BridgeProtocol::terminal) == true
    private fun unresolved(record: JsonObject): Boolean = BridgeProtocol.string(record, "localState") !in setOf("applied", "reconciled", "rejected", "not_sent")

    fun connected(connection: BridgeConnection) = executor.execute {
        records.values.filter { BridgeProtocol.string(it, "tabKey") == connection.tabKey && unresolved(it) && it.has("request") }.forEach {
            connection.send(BridgeProtocol.frame("query").apply { addProperty("id", BridgeProtocol.string(it, "id")) })
        }
        requestSnapshot(connection.tabKey)
        onEdt { PiConversationService.getInstance(project).onBridgeOperationState(connection.tabKey) }
    }
    fun disconnected(connection: BridgeConnection) = executor.execute {
        snapshots.remove(connection.tabKey)
        records.values.filter { BridgeProtocol.string(it, "tabKey") == connection.tabKey && unresolved(it) }.forEach { record ->
            record.addProperty("observation", "disconnected")
            runCatching { saveAndPublish(record) }.onFailure { publish(record) }
        }
        onEdt { PiConversationService.getInstance(project).onBridgeOperationState(connection.tabKey) }
    }

    /** Read-only requests can be recreated. Mutation execution has no retry API. */
    fun requestSnapshot(tab: String) {
        val connection = PiBridgeServer.getInstance(project).connection(tab) ?: return
        if (!connection.supports("session.snapshot")) return
        val id = UUID.randomUUID().toString()
        // Bound bookkeeping if a peer loses replies.
        if (readRequests.size > 128) readRequests.clear()
        readRequests[id] = tab
        connection.send(BridgeProtocol.frame("request").apply {
            addProperty("id", id); addProperty("method", "session.snapshot"); addProperty("methodVersion", 1)
            add("params", JsonObject()); add("context", JsonObject())
        })
    }

    private fun canSubmit(tab: String, reconcileOf: Set<String>): Boolean {
        if (!loaded || damaged) return false
        val pending = recordViews.values.filter { BridgeProtocol.string(it, "tabKey") == tab && unresolved(it) }
        return pending.isEmpty() || (reconcileOf.isNotEmpty() && pending.mapNotNull { BridgeProtocol.string(it, "id") }.toSet() == reconcileOf)
    }

    /** Reserve a local workflow before its external preparation starts. No request exists yet. */
    fun prepare(tab: String, business: JsonObject): OperationHandle? {
        val id = UUID.randomUUID().toString()
        val record = JsonObject().apply {
            addProperty("id", id); addProperty("tabKey", tab)
            addProperty("localState", "preparing"); addProperty("sent", false)
            add("business", business.deepCopy())
        }
        synchronized(records) {
            if (!canSubmit(tab, emptySet())) return null
            records[id] = record; recordViews[id] = record.deepCopy()
        }
        executor.execute {
            try {
                store.save(record)
                record.addProperty("preparationReady", true)
                publish(record)
            }
            catch (_: Exception) {
                record.addProperty("localState", "not_sent"); publish(record)
            }
        }
        return OperationHandle(id, this)
    }

    /** Convert a durable preparation into its only execution request, on the same stable id. */
    fun dispatchPrepared(id: String, method: String, params: JsonObject, snapshot: SessionSnapshot): Boolean {
        val previous = recordViews[id] ?: return false
        if (BridgeProtocol.string(previous, "localState") != "preparing") return false
        val tab = BridgeProtocol.string(previous, "tabKey") ?: return false
        return submit(tab, method, params, snapshot, previous.getAsJsonObject("business"), preparedId = id) != null
    }

    fun stopPreparation(id: String) = executor.execute {
        val record = records[id] ?: return@execute
        if (BridgeProtocol.string(record, "localState") == "preparing" && !record.has("request")) {
            record.addProperty("localState", "not_sent")
            runCatching { saveAndPublish(record) }.onFailure { publish(record) }
        }
    }

    private fun preparationReady(id: String): Boolean = BridgeProtocol.string(recordViews[id], "localState") == "preparing" &&
        recordViews[id]?.get("preparationReady")?.asBoolean == true

    fun submit(tab: String, method: String, params: JsonObject, snapshot: SessionSnapshot, business: JsonObject,
               reconcileOf: Set<String> = emptySet(), preparedId: String? = null): OperationHandle? {
        val allowed = if (preparedId != null) setOf(preparedId) else reconcileOf
        if (!canSubmit(tab, allowed)) return null
        if (preparedId != null && !preparationReady(preparedId)) return null
        val connection = PiBridgeServer.getInstance(project).connection(tab) ?: return null
        if (!connection.supports(method) || connection.runtimeId != snapshot.runtimeId) return null
        val id = preparedId ?: UUID.randomUUID().toString()
        val request = BridgeProtocol.frame("request").apply {
            addProperty("id", id); addProperty("method", method); addProperty("methodVersion", 1)
            add("params", params.deepCopy())
            add("context", JsonObject().apply {
                addProperty("sessionId", snapshot.sessionId); addProperty("observedSnapshotId", snapshot.id)
            })
            addProperty("startBefore", System.currentTimeMillis() + 10_000)
        }
        val record = JsonObject().apply {
            addProperty("id", id); addProperty("tabKey", tab); addProperty("runtimeId", connection.runtimeId)
            addProperty("localState", "pending"); addProperty("observation", "awaiting")
            addProperty("sent", false); addProperty("lastResponseAt", System.currentTimeMillis())
            add("request", request); add("business", business.deepCopy())
        }
        // Reserve the stable resource synchronously before another UI action can submit.
        synchronized(records) {
            if (!canSubmit(tab, allowed) || preparedId != null && !preparationReady(preparedId)) return null
            records[id] = record
            recordViews[id] = record.deepCopy()
        }
        executor.execute {
            try {
                store.save(record)
                record.addProperty("sent", true)
                store.save(record) // durable uncertainty marker before first possible write
                if (!connection.send(request)) {
                    record.addProperty("sent", false)
                    record.addProperty("localState", "not_sent")
                }
                saveAndPublish(record)
            } catch (_: Exception) {
                // If persistence/send ordering is uncertain, keep the lock and only query.
                record.addProperty("observation", "unknown")
                publish(record)
            }
        }
        return OperationHandle(id, this)
    }

    // Each socket reader waits for its frame to be consumed. This bounds queued
    // full snapshots and lets TCP provide backpressure without ever blocking EDT.
    private fun execute(action: () -> Unit) { executor.submit { action() }.get() }

    fun receive(connection: BridgeConnection, frame: JsonObject) = execute {
        try {
            when (BridgeProtocol.string(frame, "type")) {
                "event" -> {
                    val snapshot = frame.getAsJsonObject("snapshot")
                    if (snapshot != null) acceptSnapshot(connection, snapshot)
                    else if (BridgeProtocol.string(frame, "event") == "context_unavailable") contextUnavailable(connection.tabKey, frame.getAsJsonObject("error"))
                }
                "response" -> {
                    val id = BridgeProtocol.string(frame, "id") ?: return@execute
                    val snapshot = frame.getAsJsonObject("context")?.getAsJsonObject("currentSnapshot")
                    if (readRequests[id] == connection.tabKey) {
                        if (snapshot != null) acceptSnapshot(connection, snapshot)
                        else if (BridgeProtocol.string(frame, "status") == "failed") contextUnavailable(connection.tabKey, frame.getAsJsonObject("error"))
                        if (BridgeProtocol.terminal(frame)) readRequests.remove(id)
                        return@execute
                    }
                    val record = records[id] ?: return@execute
                    if (BridgeProtocol.string(record, "tabKey") != connection.tabKey) return@execute
                    val next = record.deepCopy()
                    if (BridgeProtocol.string(frame, "lookup") == "not_found") {
                        next.addProperty("observation", "unknown")
                    } else {
                        val request = record.getAsJsonObject("request")
                        if (frame.get("method") != request.get("method") || frame.get("methodVersion") != request.get("methodVersion")) return@execute
                        // A delayed accepted/running frame cannot regress a durable terminal.
                        if (terminal(record)) {
                            if (snapshot != null) acceptSnapshot(connection, snapshot)
                            publish(record)
                            connection.send(BridgeProtocol.frame("ack").apply { addProperty("id", id) })
                            return@execute
                        }
                        val status = BridgeProtocol.string(frame, "status")
                        require(status in setOf("accepted", "running", "succeeded", "failed", "cancelled"))
                        if (BridgeProtocol.terminal(frame)) {
                            require(BridgeProtocol.string(frame, "effect") in setOf("none", "applied", "unknown"))
                            require(status != "succeeded" || BridgeProtocol.string(frame, "effect") != "unknown")
                        }
                        if (BridgeProtocol.string(record.getAsJsonObject("response"), "status") == "running" && status == "accepted") return@execute
                        next.add("response", frame.deepCopy())
                        next.addProperty("lastResponseAt", System.currentTimeMillis())
                        next.addProperty("observation", if (frame.get("executionEnded")?.asBoolean == true && !BridgeProtocol.terminal(frame)) "unknown" else "reachable")
                    }
                    // Publish the new state only after persistence: failed fsync must not be acked on reconnect.
                    store.save(next)
                    records[id] = next
                    if (snapshot != null) acceptSnapshot(connection, snapshot)
                    publish(next)
                    if (terminal(next)) connection.send(BridgeProtocol.frame("ack").apply { addProperty("id", id) })
                }
            }
        } catch (_: Exception) {
            // Invalid snapshots or failed disk writes cannot cause business commits/acks.
            connection.close()
        }
    }

    private fun acceptSnapshot(connection: BridgeConnection, raw: JsonObject) {
        val snapshot = SessionSnapshot(raw.deepCopy())
        if (!snapshot.usable || snapshot.runtimeId != connection.runtimeId ||
            PiBridgeServer.getInstance(project).connection(connection.tabKey)?.id != connection.id) return
        snapshots[connection.tabKey] = snapshot
        contextErrors.remove(connection.tabKey)
        if (snapshotScheduled.add(connection.tabKey)) onEdt {
            snapshotScheduled.remove(connection.tabKey)
            snapshots[connection.tabKey]?.let { PiConversationService.getInstance(project).onBridgeSnapshot(connection.tabKey, it) }
        }
    }
    private fun contextUnavailable(tab: String, error: JsonObject?) {
        snapshots.remove(tab)
        contextErrors[tab] = BridgeProtocol.string(error, "code") ?: "INTERNAL_ERROR"
        onEdt { PiConversationService.getInstance(project).onBridgeOperationState(tab) }
    }

    /** Business checkpoint and idempotency marker are atomically durable together. */
    fun commit(id: String, localState: String, checkpoint: JsonObject? = null) = executor.execute {
        val record = records[id] ?: return@execute
        record.addProperty("localState", localState)
        if (checkpoint != null) record.add("checkpoint", checkpoint.deepCopy())
        if (localState in setOf("applied", "reconciled", "rejected")) record.getAsJsonObject("response")?.remove("context")
        try {
            saveAndPublish(record)
            // A newer durable checkpoint supersedes earlier checkpoints for this resource.
            // Terminal/digest tombstones stay; pending/conflicting records are never evicted.
            if (checkpoint != null && localState in setOf("applied", "reconciled")) {
                records.values.filter { old -> old !== record &&
                    old.get("tabKey") == record.get("tabKey") &&
                    BridgeProtocol.string(old, "localState") in setOf("applied", "reconciled") &&
                    old.getAsJsonObject("checkpoint")?.get("viewRevision")?.asLong?.let {
                        it <= checkpoint.get("viewRevision").asLong
                    } == true
                }.forEach { old ->
                    runCatching {
                        val compact = old.deepCopy().apply { remove("checkpoint") }
                        store.save(compact)
                        records[BridgeProtocol.string(compact, "id")!!] = compact
                        publish(compact)
                    }
                }
            }
        } catch (_: Exception) {
            record.addProperty("localState", "conflict")
            publish(record)
        }
    }
    fun recordsFor(tab: String): List<JsonObject> = recordViews.values.filter { BridgeProtocol.string(it, "tabKey") == tab }.map { it.deepCopy() }
    private fun saveAndPublish(record: JsonObject) { store.save(record); publish(record) }
    private fun publish(record: JsonObject) {
        val copy = record.deepCopy()
        val id = BridgeProtocol.string(copy, "id")!!
        recordViews[id] = copy
        if (recordScheduled.add(id)) onEdt {
            recordScheduled.remove(id)
            val latest = recordViews[id] ?: return@onEdt
            subscribers[id]?.forEach { it(latest.deepCopy()) }
            PiConversationService.getInstance(project).onBridgeOperation(latest)
        }
    }
    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeLater {
        if (!project.isDisposed) action()
    }
    override fun dispose() { executor.shutdownNow() }
    companion object { fun getInstance(project: Project): BridgeOperations = project.service() }
}
