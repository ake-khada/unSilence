package com.unsilence.app.data.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import javax.inject.Inject
import javax.inject.Singleton

private const val SNAPSHOT_VERSION = "SNAPSHOT_V1"

@Singleton
class MemoryEventStore @Inject constructor() {

    // ─── Primary store ──────────────────────────────────────────────────────
    private val eventsById = ConcurrentHashMap<String, NostrEvent>()

    // ─── Indexes (X-plus, maintained on insert) ─────────────────────────────
    private val idsByKind = ConcurrentHashMap<Int, MutableSet<String>>()
    private val idsByPubkey = ConcurrentHashMap<String, MutableSet<String>>()
    private val idsByReplyTarget = ConcurrentHashMap<String, MutableSet<String>>()
    private val recentByCreatedAt = ConcurrentSkipListSet<EventEntry>(
        compareByDescending<EventEntry> { it.createdAt }.thenBy { it.id },
    )

    // ─── Derived aggregates (incrementally maintained) ──────────────────────
    private val replyCounts = ConcurrentHashMap<String, Int>()
    private val repostCounts = ConcurrentHashMap<String, Int>()
    private val reactionCounts = ConcurrentHashMap<String, Int>()
    private val zapStatsByEventId = ConcurrentHashMap<String, ZapAggregate>()
    private val statsUpdatedAt = ConcurrentHashMap<String, Long>()

    // ─── Profile + relay routing (kind-derived state) ───────────────────────
    private val profilesByPubkey = ConcurrentHashMap<String, NostrEvent>()
    private val followsByPubkey = ConcurrentHashMap<String, Set<String>>()
    private val relayListsByPubkey = ConcurrentHashMap<String, RelayList>()
    private val muteListsByPubkey = ConcurrentHashMap<String, MuteList>()

    // ─── Parameterized replaceable events (kind 30002 etc.) ─────────────────
    // Key: "$pubkey:$kind:$dTag" → event ID of the latest version
    private val replaceableByCoordinate = ConcurrentHashMap<String, String>()

    // ─── Reactive signals ───────────────────────────────────────────────────
    private val _feedSignal = MutableStateFlow(0L)
    private val _profileSignal = MutableStateFlow(0L)
    private val _statsSignal = MutableStateFlow(0L)
    private val _followsSignal = MutableStateFlow(0L)

    // ─── Insert (called by EventProcessor.flushBatch) ───────────────────────

    fun insert(event: NostrEvent): Boolean {
        // 1. Dedup: putIfAbsent returns null if novel
        val existing = eventsById.putIfAbsent(event.id, event)
        if (existing != null) {
            // Duplicate — just record the relay
            existing.relaysSeen.addAll(event.relaysSeen)
            return false
        }

        // 2. Update indexes
        idsByKind.getOrPut(event.kind) { ConcurrentHashMap.newKeySet() }.add(event.id)
        idsByPubkey.getOrPut(event.pubkey) { ConcurrentHashMap.newKeySet() }.add(event.id)
        recentByCreatedAt.add(EventEntry(event.id, event.createdAt))

        if (event.replyToId != null) {
            idsByReplyTarget.getOrPut(event.replyToId) { ConcurrentHashMap.newKeySet() }.add(event.id)
        }
        if (event.rootId != null && event.rootId != event.replyToId) {
            idsByReplyTarget.getOrPut(event.rootId) { ConcurrentHashMap.newKeySet() }.add(event.id)
        }

        // 3. Update derived aggregates based on kind
        when (event.kind) {
            0 -> handleProfile(event)
            1 -> handleNote(event)
            3 -> handleFollows(event)
            6 -> handleRepost(event)
            7 -> handleReaction(event)
            9735 -> handleZapReceipt(event)
            10000 -> handleMuteList(event)
            10002 -> handleRelayList(event)
            30002 -> handleParameterizedReplaceable(event)
        }

        // 5. Bump signals
        when (event.kind) {
            0 -> _profileSignal.value = System.nanoTime()
            3 -> _followsSignal.value = System.nanoTime()
            1, 6, 30023 -> _feedSignal.value = System.nanoTime()
            7, 9735 -> _statsSignal.value = System.nanoTime()
        }

        return true
    }

    // ─── Kind handlers ──────────────────────────────────────────────────────

    private fun handleProfile(event: NostrEvent) {
        profilesByPubkey.compute(event.pubkey) { _, existing ->
            if (existing == null || event.createdAt >= existing.createdAt) event else existing
        }
    }

    private fun handleNote(event: NostrEvent) {
        // Increment reply counts for targets
        event.replyToId?.let { targetId ->
            replyCounts.compute(targetId) { _, v -> (v ?: 0) + 1 }
            statsUpdatedAt[targetId] = System.currentTimeMillis()
        }
        // If rootId differs from replyToId, root also gets a reply count
        if (event.rootId != null && event.rootId != event.replyToId) {
            replyCounts.compute(event.rootId) { _, v -> (v ?: 0) + 1 }
            statsUpdatedAt[event.rootId] = System.currentTimeMillis()
        }
    }

    private fun handleFollows(event: NostrEvent) {
        val pubkeys = event.tags
            .filter { it.size >= 2 && it[0] == "p" }
            .map { it[1] }
            .toSet()
        followsByPubkey.compute(event.pubkey) { _, existing ->
            val existingEvent = eventsById.values.firstOrNull { it.pubkey == event.pubkey && it.kind == 3 && it.id != event.id }
            if (existingEvent == null || event.createdAt >= existingEvent.createdAt) pubkeys
            else followsByPubkey[event.pubkey] ?: pubkeys
        }
    }

    private fun handleRepost(event: NostrEvent) {
        val targetId = event.rootId ?: return
        repostCounts.compute(targetId) { _, v -> (v ?: 0) + 1 }
        statsUpdatedAt[targetId] = System.currentTimeMillis()
    }

    private fun handleReaction(event: NostrEvent) {
        // Last e-tag is the target
        val targetId = event.tags
            .lastOrNull { it.size >= 2 && it[0] == "e" }
            ?.get(1) ?: return
        reactionCounts.compute(targetId) { _, v -> (v ?: 0) + 1 }
        statsUpdatedAt[targetId] = System.currentTimeMillis()
    }

    private fun handleZapReceipt(event: NostrEvent) {
        val targetId = event.tags
            .firstOrNull { it.size >= 2 && it[0] == "e" }
            ?.get(1) ?: return

        val sats = extractSatsFromZap(event)
        zapStatsByEventId.compute(targetId) { _, existing ->
            val current = existing ?: ZapAggregate.EMPTY
            ZapAggregate(current.count + 1, current.totalSats + sats)
        }
        statsUpdatedAt[targetId] = System.currentTimeMillis()
    }

    /**
     * Extract sats from a kind-9735 zap receipt.
     *
     * Priority: bolt11 tag (parsed via BOLT11 amount spec), then "amount"
     * tag (millisatoshis, NIP-57).
     *
     * Note: Quartz's LnInvoiceUtil.getAmountInSats() is the canonical
     * parser used by EventProcessor, but it's compiled for Java 21 and
     * can't run in JVM 17 unit tests (UnsupportedClassVersionError).
     * This parser implements the same BOLT11 amount extraction:
     * lnbc<digits><multiplier>1<data> where m=milli, u=micro, n=nano, p=pico.
     */
    private fun extractSatsFromZap(event: NostrEvent): Long {
        // Primary: parse bolt11 tag
        val bolt11 = event.tags
            .firstOrNull { it.size >= 2 && it[0] == "bolt11" }
            ?.get(1)
        if (bolt11 != null) {
            val sats = parseBolt11Amount(bolt11)
            if (sats > 0) return sats
        }

        // Fallback: "amount" tag in millisatoshis (NIP-57)
        val amountMsats = event.tags
            .firstOrNull { it.size >= 2 && it[0] == "amount" }
            ?.get(1)?.toLongOrNull()
        if (amountMsats != null) return amountMsats / 1000

        return 0L
    }

    private fun parseBolt11Amount(bolt11: String): Long {
        // BOLT11 HRP: lnbc<amount><multiplier>  Separator: 1  Data: bech32
        val prefix = "lnbc"
        val lower = bolt11.lowercase()
        val idx = lower.indexOf(prefix)
        if (idx < 0) return 0L

        val afterPrefix = lower.substring(idx + prefix.length)
        val numStr = afterPrefix.takeWhile { it.isDigit() }
        if (numStr.isEmpty()) return 0L

        val amount = numStr.toLongOrNull() ?: return 0L
        val multiplier = afterPrefix.getOrNull(numStr.length)

        // BTC multipliers → sats (1 BTC = 100_000_000 sats)
        return when (multiplier) {
            'm' -> amount * 100_000       // milli-BTC
            'u' -> amount * 100           // micro-BTC
            'n' -> amount / 10            // nano-BTC (1 nBTC = 0.1 sat)
            'p' -> amount / 10_000        // pico-BTC
            else -> amount                // no multiplier = BTC (rare for zaps)
        }
    }

    private fun handleMuteList(event: NostrEvent) {
        val pubkeys = event.tags
            .filter { it.size >= 2 && it[0] == "p" }
            .map { it[1] }
            .toSet()
        val words = event.tags
            .filter { it.size >= 2 && it[0] == "word" }
            .map { it[1] }
            .toSet()
        muteListsByPubkey.compute(event.pubkey) { _, existing ->
            // Check if we already have a newer mute list
            if (existing != null) {
                val existingEvent = eventsById.values.firstOrNull {
                    it.pubkey == event.pubkey && it.kind == 10000 && it.id != event.id
                }
                if (existingEvent != null && existingEvent.createdAt > event.createdAt) {
                    return@compute existing
                }
            }
            MuteList(pubkeys, words)
        }
    }

    private fun handleRelayList(event: NostrEvent) {
        val readRelays = mutableListOf<String>()
        val writeRelays = mutableListOf<String>()

        for (tag in event.tags) {
            if (tag.size < 2 || tag[0] != "r") continue
            val url = tag[1]
            val marker = tag.getOrNull(2)
            when (marker) {
                "read" -> readRelays.add(url)
                "write" -> writeRelays.add(url)
                else -> {
                    // No marker or empty string = both read and write
                    readRelays.add(url)
                    writeRelays.add(url)
                }
            }
        }

        relayListsByPubkey.compute(event.pubkey) { _, existing ->
            if (existing != null) {
                val existingEvent = eventsById.values.firstOrNull {
                    it.pubkey == event.pubkey && it.kind == 10002 && it.id != event.id
                }
                if (existingEvent != null && existingEvent.createdAt > event.createdAt) {
                    return@compute existing
                }
            }
            RelayList(readRelays, writeRelays)
        }
    }

    private fun handleParameterizedReplaceable(event: NostrEvent) {
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
        val coordinate = "${event.pubkey}:${event.kind}:$dTag"

        replaceableByCoordinate.compute(coordinate) { _, existingId ->
            if (existingId != null) {
                val existingEvent = eventsById[existingId]
                if (existingEvent != null && existingEvent.createdAt > event.createdAt) {
                    // Existing is newer — remove the new one from indexes
                    removeFromIndexes(event)
                    return@compute existingId
                }
                // New one is newer — remove old from indexes
                if (existingEvent != null) {
                    removeFromIndexes(existingEvent)
                }
            }
            event.id
        }
    }

    private fun removeFromIndexes(event: NostrEvent) {
        eventsById.remove(event.id)
        idsByKind[event.kind]?.remove(event.id)
        idsByPubkey[event.pubkey]?.remove(event.id)
        recentByCreatedAt.remove(EventEntry(event.id, event.createdAt))
    }

    // ─── Query API ──────────────────────────────────────────────────────────

    fun feedEvents(filter: FeedFilter, limit: Int = 300): List<NostrEvent> {
        val result = mutableListOf<NostrEvent>()
        for (entry in recentByCreatedAt) {
            if (result.size >= limit) break
            val event = eventsById[entry.id] ?: continue
            if (event.kind !in filter.kinds) continue
            if (filter.followedPubkeys != null && event.pubkey !in filter.followedPubkeys) continue
            result.add(event)
        }
        return result
    }

    fun userEvents(pubkey: String, kinds: Set<Int>, limit: Int = 200): List<NostrEvent> {
        val ids = idsByPubkey[pubkey] ?: return emptyList()
        return ids
            .mapNotNull { eventsById[it] }
            .filter { it.kind in kinds }
            .sortedByDescending { it.createdAt }
            .take(limit)
    }

    fun threadEvents(rootId: String): List<NostrEvent> {
        val result = mutableListOf<NostrEvent>()

        // Include the root itself
        eventsById[rootId]?.let { result.add(it) }

        // All events that reference this root
        val replyIds = idsByReplyTarget[rootId] ?: emptySet()
        for (id in replyIds) {
            val event = eventsById[id] ?: continue
            result.add(event)
        }

        return result.sortedBy { it.createdAt }
    }

    fun searchEvents(query: String): List<NostrEvent> {
        val lowerQuery = query.lowercase()
        return eventsById.values
            .filter { it.kind == 1 && it.content.lowercase().contains(lowerQuery) }
            .sortedByDescending { it.createdAt }
            .take(100)
    }

    fun eventsByIds(ids: Set<String>): List<NostrEvent> {
        return ids.mapNotNull { eventsById[it] }
    }

    // ─── Profile / follows / relay queries ──────────────────────────────────

    fun getProfile(pubkey: String): NostrEvent? = profilesByPubkey[pubkey]
    fun hasProfile(pubkey: String): Boolean = profilesByPubkey.containsKey(pubkey)
    fun getFollows(pubkey: String): Set<String>? = followsByPubkey[pubkey]
    fun getRelayList(pubkey: String): RelayList? = relayListsByPubkey[pubkey]
    fun getMuteList(pubkey: String): MuteList? = muteListsByPubkey[pubkey]

    // ─── O(1) stat reads ────────────────────────────────────────────────────

    fun replyCount(eventId: String): Int = replyCounts[eventId] ?: 0
    fun repostCount(eventId: String): Int = repostCounts[eventId] ?: 0
    fun reactionCount(eventId: String): Int = reactionCounts[eventId] ?: 0
    fun zapStats(eventId: String): ZapAggregate = zapStatsByEventId[eventId] ?: ZapAggregate.EMPTY
    fun statsLastUpdated(eventId: String): Long = statsUpdatedAt[eventId] ?: 0L

    // ─── Reactive flows ─────────────────────────────────────────────────────

    fun feedFlow(filter: FeedFilter, limit: Int = 300): Flow<List<FeedRow>> =
        _feedSignal.map { feedEvents(filter, limit).map { toFeedRow(it) } }

    fun profileFlow(pubkey: String): Flow<NostrEvent?> =
        _profileSignal.map { getProfile(pubkey) }

    // ─── Outbox routing ─────────────────────────────────────────────────────

    fun writeRelaysFor(pubkey: String): List<String> =
        relayListsByPubkey[pubkey]?.write ?: emptyList()

    fun readRelaysFor(pubkey: String): List<String> =
        relayListsByPubkey[pubkey]?.read ?: emptyList()

    // ─── FeedRow conversion ─────────────────────────────────────────────────

    private fun toFeedRow(event: NostrEvent): FeedRow {
        val profile = profilesByPubkey[event.pubkey]
        val profileContent = profile?.content
        // Parse basic profile fields from JSON content
        val authorName = profileContent?.extractJsonString("name")
        val authorDisplayName = profileContent?.extractJsonString("display_name")
        val authorPicture = profileContent?.extractJsonString("picture")
        val authorNip05 = profileContent?.extractJsonString("nip05")

        val statsId = if (event.kind == 6) event.rootId ?: event.id else event.id
        val zap = zapStats(statsId)

        return FeedRow(
            id = event.id,
            pubkey = event.pubkey,
            kind = event.kind,
            content = event.content,
            createdAt = event.createdAt,
            tags = event.tags,
            relayUrl = event.relayUrl,
            replyToId = event.replyToId,
            rootId = event.rootId,
            hasContentWarning = event.hasContentWarning,
            contentWarningReason = event.contentWarningReason,
            firstSeenAt = event.firstSeenAt,
            zapTotalSats = zap.totalSats,
            authorName = authorName,
            authorDisplayName = authorDisplayName,
            authorPicture = authorPicture,
            authorNip05 = authorNip05,
            reactionCount = reactionCount(statsId),
            replyCount = replyCount(statsId),
            repostCount = repostCount(statsId),
            zapCount = zap.count,
        )
    }

    // ─── Snapshot persistence ───────────────────────────────────────────────

    suspend fun saveSnapshot(file: File) {
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        tmpFile.bufferedWriter().use { writer ->
            writer.write(SNAPSHOT_VERSION)
            writer.newLine()
            for (event in eventsById.values) {
                writer.write(serializeEvent(event))
                writer.newLine()
            }
            // Write aggregates section
            writer.write("---AGGREGATES---")
            writer.newLine()
            for ((id, count) in replyCounts) {
                writer.write("reply|$id|$count")
                writer.newLine()
            }
            for ((id, count) in repostCounts) {
                writer.write("repost|$id|$count")
                writer.newLine()
            }
            for ((id, count) in reactionCounts) {
                writer.write("reaction|$id|$count")
                writer.newLine()
            }
            for ((id, zap) in zapStatsByEventId) {
                writer.write("zap|$id|${zap.count}|${zap.totalSats}")
                writer.newLine()
            }
        }
        tmpFile.renameTo(file)
    }

    suspend fun restoreFromSnapshot(file: File) {
        if (!file.exists()) return
        var inAggregates = false
        var versionChecked = false

        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                // First line must be the version header
                if (!versionChecked) {
                    versionChecked = true
                    if (line != SNAPSHOT_VERSION) {
                        // Unknown format — treat as missing snapshot
                        return
                    }
                    continue
                }
                if (line == "---AGGREGATES---") {
                    inAggregates = true
                    continue
                }
                if (inAggregates) {
                    restoreAggregate(line)
                } else {
                    val event = deserializeEvent(line) ?: continue
                    insertFromSnapshot(event)
                }
            }
        }

        // Bump all signals once
        val now = System.nanoTime()
        _feedSignal.value = now
        _profileSignal.value = now
        _statsSignal.value = now
        _followsSignal.value = now
    }

    private fun insertFromSnapshot(event: NostrEvent) {
        eventsById[event.id] = event
        idsByKind.getOrPut(event.kind) { ConcurrentHashMap.newKeySet() }.add(event.id)
        idsByPubkey.getOrPut(event.pubkey) { ConcurrentHashMap.newKeySet() }.add(event.id)
        recentByCreatedAt.add(EventEntry(event.id, event.createdAt))

        if (event.replyToId != null) {
            idsByReplyTarget.getOrPut(event.replyToId) { ConcurrentHashMap.newKeySet() }.add(event.id)
        }
        if (event.rootId != null && event.rootId != event.replyToId) {
            idsByReplyTarget.getOrPut(event.rootId) { ConcurrentHashMap.newKeySet() }.add(event.id)
        }

        // Restore kind-derived state (profiles, follows, relay lists)
        when (event.kind) {
            0 -> handleProfile(event)
            3 -> handleFollows(event)
            10000 -> handleMuteList(event)
            10002 -> handleRelayList(event)
            30002 -> handleParameterizedReplaceable(event)
        }
    }

    private fun restoreAggregate(line: String) {
        val parts = line.split("|")
        if (parts.size < 3) return
        when (parts[0]) {
            "reply" -> replyCounts[parts[1]] = parts[2].toIntOrNull() ?: return
            "repost" -> repostCounts[parts[1]] = parts[2].toIntOrNull() ?: return
            "reaction" -> reactionCounts[parts[1]] = parts[2].toIntOrNull() ?: return
            "zap" -> {
                if (parts.size >= 4) {
                    val count = parts[2].toIntOrNull() ?: return
                    val total = parts[3].toLongOrNull() ?: return
                    zapStatsByEventId[parts[1]] = ZapAggregate(count, total)
                }
            }
        }
    }

    // ─── Serialization (NDJSON) ─────────────────────────────────────────────

    private fun serializeEvent(event: NostrEvent): String {
        val sb = StringBuilder()
        sb.append(event.id).append('\t')
        sb.append(event.pubkey).append('\t')
        sb.append(event.kind).append('\t')
        sb.append(event.content.replace("\t", "\\t").replace("\n", "\\n")).append('\t')
        sb.append(event.createdAt).append('\t')
        sb.append(serializeTags(event.tags)).append('\t')
        sb.append(event.sig).append('\t')
        sb.append(event.relayUrl).append('\t')
        sb.append(event.replyToId ?: "").append('\t')
        sb.append(event.rootId ?: "").append('\t')
        sb.append(event.hasContentWarning).append('\t')
        sb.append(event.contentWarningReason ?: "").append('\t')
        sb.append(event.firstSeenAt).append('\t')
        sb.append(event.relaysSeen.joinToString(","))
        return sb.toString()
    }

    private fun deserializeEvent(line: String): NostrEvent? {
        val parts = line.split('\t')
        if (parts.size < 14) return null
        return NostrEvent(
            id = parts[0],
            pubkey = parts[1],
            kind = parts[2].toIntOrNull() ?: return null,
            content = parts[3].replace("\\t", "\t").replace("\\n", "\n"),
            createdAt = parts[4].toLongOrNull() ?: return null,
            tags = deserializeTags(parts[5]),
            sig = parts[6],
            relayUrl = parts[7],
            replyToId = parts[8].ifEmpty { null },
            rootId = parts[9].ifEmpty { null },
            hasContentWarning = parts[10].toBooleanStrictOrNull() ?: false,
            contentWarningReason = parts[11].ifEmpty { null },
            firstSeenAt = parts[12].toLongOrNull() ?: 0L,
            relaysSeen = parts[13].split(",").filter { it.isNotEmpty() }.toMutableSet(),
        )
    }

    private fun serializeTags(tags: List<List<String>>): String {
        return tags.joinToString(";") { tag ->
            tag.joinToString(",") { it.replace(",", "\\,").replace(";", "\\;") }
        }
    }

    private fun deserializeTags(s: String): List<List<String>> {
        if (s.isEmpty()) return emptyList()
        return s.split(";").map { tagStr ->
            tagStr.split(",").map { it.replace("\\,", ",").replace("\\;", ";") }
        }
    }

    // ─── Maintenance ────────────────────────────────────────────────────────

    fun trimToLast(events: Int = 5000) {
        val allEntries = recentByCreatedAt.toList() // already sorted desc by createdAt
        if (allEntries.size <= events) return

        val toRemove = allEntries.subList(events, allEntries.size)
        val removeIds = toRemove.map { it.id }.toSet()

        for (entry in toRemove) {
            val event = eventsById.remove(entry.id) ?: continue
            recentByCreatedAt.remove(entry)
            idsByKind[event.kind]?.remove(event.id)
            idsByPubkey[event.pubkey]?.remove(event.id)
            event.replyToId?.let { idsByReplyTarget[it]?.remove(event.id) }
            if (event.rootId != null && event.rootId != event.replyToId) {
                idsByReplyTarget[event.rootId]?.remove(event.id)
            }
        }

        // Clean up aggregates that only reference removed events
        replyCounts.keys.removeAll(removeIds)
        repostCounts.keys.removeAll(removeIds)
        reactionCounts.keys.removeAll(removeIds)
        zapStatsByEventId.keys.removeAll(removeIds)
        statsUpdatedAt.keys.removeAll(removeIds)
    }

    fun clear() {
        eventsById.clear()
        idsByKind.clear()
        idsByPubkey.clear()
        idsByReplyTarget.clear()
        recentByCreatedAt.clear()
        replyCounts.clear()
        repostCounts.clear()
        reactionCounts.clear()
        zapStatsByEventId.clear()
        statsUpdatedAt.clear()
        profilesByPubkey.clear()
        followsByPubkey.clear()
        relayListsByPubkey.clear()
        muteListsByPubkey.clear()
        replaceableByCoordinate.clear()
        _feedSignal.value = 0L
        _profileSignal.value = 0L
        _statsSignal.value = 0L
        _followsSignal.value = 0L
    }
}

// ─── Utilities ──────────────────────────────────────────────────────────────

private fun String.extractJsonString(key: String): String? {
    val searchKey = "\"$key\""
    val idx = indexOf(searchKey)
    if (idx < 0) return null
    val afterColon = indexOf(':', idx + searchKey.length)
    if (afterColon < 0) return null
    val quoteStart = indexOf('"', afterColon + 1)
    if (quoteStart < 0) return null
    val quoteEnd = indexOf('"', quoteStart + 1)
    if (quoteEnd < 0) return null
    return substring(quoteStart + 1, quoteEnd)
}
