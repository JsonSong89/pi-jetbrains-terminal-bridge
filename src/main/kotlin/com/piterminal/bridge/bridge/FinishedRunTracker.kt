package com.piterminal.bridge.bridge

import com.google.gson.JsonObject

/** Bounded recent-event memory, confined to the notification consumer's EDT. */
internal class FinishedRunTracker {
    private val runtimes = LinkedHashMap<Pair<String, String>, LinkedHashSet<String>>()

    fun accept(tabKey: String, runtimeId: String, event: JsonObject): Boolean {
        if (BridgeProtocol.string(event, "event") != "agent.finished") return false
        val runId = BridgeProtocol.string(event, "runId")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return false
        if (BridgeProtocol.string(event, "sessionId").isNullOrBlank()) return false
        val outcome = BridgeProtocol.string(event, "outcome")
        if (outcome !in setOf("completed", "error", "aborted")) return false
        val key = tabKey to runtimeId
        val runs = runtimes.getOrPut(key) { LinkedHashSet() }
        if (!runs.add(runId)) return false
        while (runs.size > 128) runs.remove(runs.first())
        while (runtimes.size > 128) runtimes.remove(runtimes.keys.first())
        return outcome != "aborted"
    }
}
