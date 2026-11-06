package com.piterminal.bridge.conversations

import com.piterminal.bridge.bridge.SessionSnapshot

/** Pure projection policy; local hidden cards and legacy uncertainty survive full snapshots. */
object ConversationProjection {
    data class Result(val changed: Boolean, val ambiguous: Boolean)
    fun apply(c: PiConversation, snapshot: SessionSnapshot): Result {
        if (!snapshot.usable || snapshot.sessionId != c.piSessionId) return Result(false, false)
        val users = snapshot.users()
        // Confirmed bindings constrain both ends of the old ordered sequence.
        val sequence = (c.messages + c.hiddenLegacy).filter { old -> old.entryId == null ||
            (old.sessionId == snapshot.sessionId && users.any { it.id == old.entryId }) }.sortedBy { it.timestamp }
        fun matches(old: PiUserMessage, native: SessionSnapshot.UserEntry): Boolean =
            if (old.entryId != null) old.entryId == native.id else old.text == native.text
        // One unmatched card (e.g. kept from before a fork) proves nothing about the
        // rest: skip it instead of abandoning every later binding.
        val earliest = IntArray(sequence.size) { -1 }
        val latest = IntArray(sequence.size) { -1 }
        var cursor = 0
        for (i in sequence.indices) {
            val at = (cursor until users.size).firstOrNull { matches(sequence[i], users[it]) } ?: continue
            earliest[i] = at; cursor = at + 1
        }
        cursor = users.lastIndex
        for (i in sequence.indices.reversed()) {
            val at = (cursor downTo 0).firstOrNull { matches(sequence[i], users[it]) } ?: continue
            latest[i] = at; cursor = at - 1
        }
        // Identity keys matter: two old cards may have identical text and timestamp.
        val bindings = java.util.IdentityHashMap<PiUserMessage, SessionSnapshot.UserEntry>()
        sequence.indices.forEach { i ->
            if (sequence[i].entryId == null && earliest[i] >= 0 && earliest[i] == latest[i]) bindings[sequence[i]] = users[earliest[i]]
        }
        var metadataChanged = false
        for (message in c.hiddenLegacy.toList()) bindings[message]?.let {
            c.hiddenEntries.add(key(snapshot.sessionId, it.id)); c.hiddenLegacy.remove(message); metadataChanged = true
        }
        val existing = c.messages.filter { it.sessionId == snapshot.sessionId && it.entryId != null }.associateBy { it.entryId }.toMutableMap()
        c.messages.filter { it.entryId == null }.forEach { old -> bindings[old]?.let { native ->
            existing[native.id] = old.copy(sessionId = snapshot.sessionId, entryId = native.id)
        } }
        val unresolved = c.messages.filter { it.entryId == null && !bindings.containsKey(it) }
        val reservedTexts = (unresolved + c.hiddenLegacy).map { it.text }.toSet()
        if (!c.projectionInitialized) {
            // Legacy XML never recorded deletions. Preserve its card visibility on the
            // first migration, but only up to the newest known card: a later native
            // prompt cannot be the result of an old deletion. A conversation without
            // cards adopts the whole branch instead of hiding it.
            val cutoff = c.messages.maxOfOrNull { it.timestamp }
            if (cutoff != null) users.filter {
                it.id !in existing && it.text !in reservedTexts && it.timestamp <= cutoff
            }.forEach { c.hiddenEntries.add(key(snapshot.sessionId, it.id)) }
            c.projectionInitialized = true; metadataChanged = true
        }
        val next = users.filter { key(snapshot.sessionId, it.id) !in c.hiddenEntries &&
            (it.id in existing || it.text !in reservedTexts) }.map { native ->
            existing[native.id]?.takeIf { it.text == native.text } ?: PiUserMessage(native.text, native.timestamp, snapshot.sessionId, native.id)
        } + unresolved
        // Unresolved cards keep their chronological slot; stacking them last would
        // make a stale card look like the newest one.
        val ordered = if (next.all { it.timestamp > 0 }) next.sortedBy { it.timestamp } else next
        val changed = metadataChanged || ordered != c.messages
        if (changed) {
            c.messages.clear(); c.messages.addAll(ordered); c.viewRevision++
        }
        return Result(changed, unresolved.isNotEmpty() || c.hiddenLegacy.isNotEmpty())
    }
    fun key(sessionId: String, entryId: String): String = "$sessionId:$entryId"
}
