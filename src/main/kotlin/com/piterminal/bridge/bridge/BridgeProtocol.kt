package com.piterminal.bridge.bridge

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Wire types deliberately retain native entries, including unknown fields. */
object BridgeProtocol {
    val gson = GsonBuilder().serializeNulls().create()
    val maxFrameBytes: Int = Integer.getInteger("pi.bridge.maxFrameBytes", 32 * 1024 * 1024).coerceAtLeast(1024)
    val maxQueueBytes: Int = Integer.getInteger("pi.bridge.maxQueueBytes", 64 * 1024 * 1024).coerceAtLeast(maxFrameBytes)
    fun frame(type: String): JsonObject = JsonObject().apply {
        addProperty("v", 2)
        addProperty("type", type)
    }
    fun string(json: JsonObject?, key: String): String? =
        json?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    fun parse(line: String): JsonObject = JsonParser.parseString(line).asJsonObject
    fun terminal(response: JsonObject): Boolean = string(response, "status") in setOf("succeeded", "failed", "cancelled")
    fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** LF only, bounded before allocation; CRLF accepted, Unicode separators stay data. */
    fun readFrame(input: InputStream, limit: Int = maxFrameBytes): JsonObject? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                require(bytes.size() == 0) { "Incomplete JSONL frame" }
                return null
            }
            if (b == 10) break
            require(bytes.size() < limit) { "CONTEXT_TOO_LARGE" }
            bytes.write(b)
        }
        return parse(bytes.toString(Charsets.UTF_8).removeSuffix("\r"))
    }
}

data class SessionSnapshot(val raw: JsonObject) {
    val id: String get() = BridgeProtocol.string(raw, "snapshotId").orEmpty()
    val sessionId: String get() = BridgeProtocol.string(raw.getAsJsonObject("session"), "id").orEmpty()
    val runtimeId: String get() = BridgeProtocol.string(raw.getAsJsonObject("source"), "runtimeId").orEmpty()
    val usable: Boolean get() = runCatching {
        raw.get("complete")?.asJsonPrimitive?.isBoolean == true && raw.get("complete").asBoolean &&
            BridgeProtocol.string(raw, "consistency") == "stable" && id.isNotBlank() && sessionId.isNotBlank() &&
            runtimeId.isNotBlank() && !BridgeProtocol.string(raw.getAsJsonObject("source"), "piVersion").isNullOrBlank() &&
            !BridgeProtocol.string(raw, "capturedAt").isNullOrBlank() &&
            raw.getAsJsonObject("state").get("idle")?.asJsonPrimitive?.isBoolean == true && validBranch()
    }.getOrDefault(false)
    val entries: JsonArray get() = raw.getAsJsonObject("branch").getAsJsonArray("entries")
    private fun validBranch(): Boolean = runCatching {
        val branch = raw.getAsJsonObject("branch")
        if (!branch.has("leafId") || !(branch.get("leafId").isJsonNull ||
                branch.get("leafId").isJsonPrimitive && branch.get("leafId").asJsonPrimitive.isString)) return false
        val seen = mutableSetOf<String>()
        var parent: String? = null
        for (element in entries) {
            val e = element.asJsonObject
            val entryId = BridgeProtocol.string(e, "id") ?: return false
            if (entryId.isBlank() || BridgeProtocol.string(e, "type").isNullOrBlank() || !e.has("parentId") ||
                !(e.get("parentId").isJsonNull || e.get("parentId").isJsonPrimitive && e.get("parentId").asJsonPrimitive.isString) ||
                !seen.add(entryId) || BridgeProtocol.string(e, "parentId") != parent) return false
            if (BridgeProtocol.string(e, "type") == "message") {
                val message = e.getAsJsonObject("message") ?: return false
                val role = BridgeProtocol.string(message, "role")
                // Only LLM roles carry content; bashExecution/custom/summary messages use other fields.
                if (role.isNullOrBlank() || role in contentRoles && !message.has("content")) return false
            }
            parent = entryId
        }
        BridgeProtocol.string(branch, "leafId") == parent
    }.getOrDefault(false)

    /** Compare observations, excluding their capture id/time. */
    fun sameState(other: SessionSnapshot): Boolean = sessionId == other.sessionId &&
        raw.get("session") == other.raw.get("session") && raw.get("branch") == other.raw.get("branch") &&
        raw.get("state") == other.raw.get("state")

    data class UserEntry(val id: String, val text: String, val timestamp: Long)
    private companion object { val contentRoles = setOf("user", "assistant", "toolResult") }
    fun users(): List<UserEntry> = entries.mapNotNull { element ->
        val e = element.asJsonObject
        if (BridgeProtocol.string(e, "type") != "message") return@mapNotNull null
        val m = e.getAsJsonObject("message") ?: return@mapNotNull null
        if (BridgeProtocol.string(m, "role") != "user") return@mapNotNull null
        val content = m.get("content")
        val text = when {
            content == null -> ""
            content.isJsonPrimitive && content.asJsonPrimitive.isString -> content.asString
            content.isJsonArray -> content.asJsonArray.mapNotNull { part ->
                part.takeIf { it.isJsonObject }?.asJsonObject?.let {
                    if (BridgeProtocol.string(it, "type") == "text") BridgeProtocol.string(it, "text") else null
                }
            }.joinToString("")
            else -> ""
        }
        UserEntry(BridgeProtocol.string(e, "id").orEmpty(), text,
            runCatching { m.get("timestamp")?.asLong }.getOrNull() ?: 0L)
    }

    data class BranchCounts(val users: Int, val model: Int)
    /** User prompts versus assistant/tool-result entries on the active native branch. */
    fun counts(): BranchCounts {
        var users = 0
        var model = 0
        for (element in entries) {
            val e = element.asJsonObject
            val type = BridgeProtocol.string(e, "type")
            val role = BridgeProtocol.string(e.get("message")?.takeIf { it.isJsonObject }?.asJsonObject, "role")
            when {
                type == "message" && role == "user" -> users++
                type == "toolResult" || role == "assistant" || role == "toolResult" -> model++
            }
        }
        return BranchCounts(users, model)
    }
}
