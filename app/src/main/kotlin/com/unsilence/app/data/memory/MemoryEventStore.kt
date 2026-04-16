package com.unsilence.app.data.memory

import android.os.Trace
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.NostrJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import javax.inject.Inject
import javax.inject.Singleton

private const val SNAPSHOT_VERSION = "SNAPSHOT_V1"
private const val PENDING_RELAYS_CAP = 1_000
private const val PENDING_RELAYS_TRIM = 200

@Singleton
class MemoryEventStore @Inject constructor() {

    companion object {
        const val FOLLOWER_COUNT_TTL_SECONDS = 86_400L
    }

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
    /** Local cache freshness — when each profile was last updated in MemoryEventStore (epoch ms).
     *  NOT the kind-0 event's original createdAt. Used by ProfileResolver. */
    private val profileUpdatedAt = ConcurrentHashMap<String, Long>()
    private val followsByPubkey = ConcurrentHashMap<String, Set<String>>()
    private val followsCreatedAt = ConcurrentHashMap<String, Long>()
    private val relayListsByPubkey = ConcurrentHashMap<String, RelayList>()
    private val muteListsByPubkey = ConcurrentHashMap<String, MuteList>()

    // ─── Follower count cache (NIP-45 COUNT results) ─────────────────────────
    // Key: pubkey → (count, updatedAtSeconds)
    private val followerCountCache = ConcurrentHashMap<String, Pair<Long, Long>>()

    // ─── Parameterized replaceable events (kind 30002 etc.) ─────────────────
    // Key: "$pubkey:$kind:$dTag" → event ID of the latest version
    private val replaceableByCoordinate = ConcurrentHashMap<String, String>()

    // ─── Reactive signals ───────────────────────────────────────────────────
    private val _feedSignal = MutableStateFlow(0L)
    private val _profileSignal = MutableStateFlow(0L)
    private val _statsSignal = MutableStateFlow(0L)
    private val _followsSignal = MutableStateFlow(0L)

    // ─── Relay provenance (called by EventProcessor for seenIds duplicates) ──

    // Buffer for relay URLs that arrive via addRelaySeen before the event
    // is flushed from EventProcessor's channel into eventsById. Applied
    // during insert() when the event finally arrives.
    private val pendingRelays = ConcurrentHashMap<String, MutableSet<String>>()

    internal val pendingRelayCount: Int get() = pendingRelays.size

    fun addRelaySeen(eventId: String, relayUrl: String) {
        val event = eventsById[eventId]
        if (event != null) {
            event.relaysSeen.add(relayUrl)
        } else {
            // Event not yet flushed from channel — buffer for insert()
            pendingRelays.getOrPut(eventId) { ConcurrentHashMap.newKeySet() }.add(relayUrl)
            trimPendingRelaysIfNeeded()
        }
    }

    internal fun trimPendingRelaysIfNeeded() {
        if (pendingRelays.size <= PENDING_RELAYS_CAP) return
        repeat(PENDING_RELAYS_TRIM) {
            val key = pendingRelays.keys.firstOrNull() ?: return
            pendingRelays.remove(key)
        }
    }

    // ─── Insert (called by EventProcessor.flushBatch) ───────────────────────

    fun insert(event: NostrEvent): Boolean {
        // 1. Dedup: putIfAbsent returns null if novel
        val existing = eventsById.putIfAbsent(event.id, event)
        if (existing != null) {
            // Duplicate — just record the relay
            existing.relaysSeen.addAll(event.relaysSeen)
            return false
        }

        // Apply any relay URLs that arrived via addRelaySeen before this insert
        pendingRelays.remove(event.id)?.let { pending ->
            event.relaysSeen.addAll(pending)
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
            if (existing == null || event.createdAt >= existing.createdAt) {
                profileUpdatedAt[event.pubkey] = System.currentTimeMillis()
                event
            } else {
                existing
            }
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
        updateFollows(event.pubkey, pubkeys, event.createdAt)
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
        Trace.beginSection("MemoryEventStore.feedEvents")
        var scanned = 0
        try {
            val result = mutableListOf<NostrEvent>()
            for (entry in recentByCreatedAt) {
                scanned++
                if (result.size >= limit) break
                val event = eventsById[entry.id] ?: continue
                if (event.kind !in filter.kinds) continue
                if (filter.followedPubkeys != null && event.pubkey !in filter.followedPubkeys) continue
                // Content filter: 1 = notes only, 2 = replies only
                if (filter.contentFilter == 1 && event.kind != 6) {
                    if (event.replyToId != null || event.rootId != null) continue
                }
                if (filter.contentFilter == 2) {
                    if ((event.replyToId == null && event.rootId == null) || event.kind == 6) continue
                }
                // Relay URL scoping — null or empty means no relay filter (all relays pass)
                if (!filter.relayUrls.isNullOrEmpty() && event.relaysSeen.none { it in filter.relayUrls }) continue
                result.add(event)
            }
            return result
        } finally {
            Trace.endSection()
        }
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

    /** Local cache freshness — when this profile was last updated in MemoryEventStore (epoch ms).
     *  NOT the kind-0 event's original createdAt. Used by ProfileResolver to decide re-fetch. */
    fun getProfileLastUpdated(pubkey: String): Long = profileUpdatedAt[pubkey] ?: 0L

    /**
     * Direct-path update for kind-3 contact lists. Called by EventProcessor
     * instead of channeling kind 3 through the feed-content batching system.
     * Does NOT insert the kind-3 event into the main store — only updates
     * the follows index. Stale updates (lower createdAt) are ignored.
     */
    fun updateFollows(pubkey: String, followedPubkeys: Set<String>, createdAt: Long) {
        followsCreatedAt.compute(pubkey) { _, existingTs ->
            if (existingTs != null && existingTs > createdAt) {
                return@compute existingTs // stale — ignore
            }
            followsByPubkey[pubkey] = followedPubkeys
            _followsSignal.value = System.nanoTime()
            createdAt
        }
    }
    fun getRelayList(pubkey: String): RelayList? = relayListsByPubkey[pubkey]
    fun getMuteList(pubkey: String): MuteList? = muteListsByPubkey[pubkey]

    // ─── O(1) stat reads ────────────────────────────────────────────────────

    fun replyCount(eventId: String): Int = replyCounts[eventId] ?: 0
    fun repostCount(eventId: String): Int = repostCounts[eventId] ?: 0
    fun reactionCount(eventId: String): Int = reactionCounts[eventId] ?: 0
    fun zapStats(eventId: String): ZapAggregate = zapStatsByEventId[eventId] ?: ZapAggregate.EMPTY
    fun statsLastUpdated(eventId: String): Long = statsUpdatedAt[eventId] ?: 0L

    // ─── A.5.1 T1: Relay browse queries ───────────────────────────────────

    /**
     * Returns the max `createdAt` across all events where `event.relaysSeen`
     * overlaps with [relayUrls]. Used by RelayBrowseSession for REQ `since` cursors.
     *
     * Scan-based implementation — called infrequently (once per browse session start).
     * // TODO(A.5.1 perf): per-relay index if profiling shows hot
     */
    fun maxCreatedAtForRelays(relayUrls: Set<String>): Long? {
        if (relayUrls.isEmpty()) return null
        var max: Long? = null
        for (event in eventsById.values) {
            if (event.relaysSeen.any { it in relayUrls }) {
                val ts = event.createdAt
                if (max == null || ts > max) max = ts
            }
        }
        return max
    }

    /**
     * Returns the subset of [eventIds] whose `statsUpdatedAt` is newer than [threshold].
     * Preserves input order. Replaces `EventStatsDao.getFreshEngagementIds`.
     */
    fun filterFreshEngagement(eventIds: List<String>, threshold: Long): List<String> =
        eventIds.filter { (statsUpdatedAt[it] ?: 0L) > threshold }

    // ─── A.5.1 T2: User feed queries ───────────────────────────────────────

    /**
     * User-scoped feed query: returns events authored by [pubkey], filtered by
     * [contentFilter] and [kinds], sorted by createdAt DESC, capped at [limit].
     *
     * contentFilter semantics (match FeedFilter contract):
     *   0 = all: all kinds in the set, no filter
     *   1 = notes only: kind-1 roots (no replies, no kind-6, no kind-30023)
     *   2 = replies only: kind-1 with replyToId or rootId (no kind-6, no kind-30023)
     */
    private fun userFeedEvents(
        pubkey: String,
        contentFilter: Int,
        kinds: Set<Int>,
        limit: Int,
    ): List<NostrEvent> {
        val ids = idsByPubkey[pubkey] ?: return emptyList()
        val events = ids.mapNotNull { eventsById[it] }.filter { it.kind in kinds }

        val filtered = when (contentFilter) {
            // Notes tab: kind-1 roots (no replies) + kind-6 reposts — matches Room userNotesFlow
            1 -> events.filter {
                (it.kind == 1 && it.replyToId == null && it.rootId == null) || it.kind == 6
            }
            // Replies tab: kind-1 replies only (has replyToId or rootId)
            2 -> events.filter { it.kind == 1 && (it.replyToId != null || it.rootId != null) }
            else -> events
        }

        return filtered.sortedByDescending { it.createdAt }.take(limit)
    }

    // ─── A.5.1 T2: Batch user entity lookup ───────────────────────────────

    /** Batch variant of [getUserEntity]. Returns entities in input order, skipping unknown pubkeys. */
    fun getUserEntities(pubkeys: List<String>): List<UserEntity> =
        pubkeys.mapNotNull { getUserEntity(it) }

    // ─── A.5.1 T2: Follower count cache ───────────────────────────────────

    /** Cache a NIP-45 COUNT result. Timestamp stored in seconds (parity with Room path). */
    fun cacheFollowerCount(pubkey: String, count: Long) {
        followerCountCache[pubkey] = Pair(count, System.currentTimeMillis() / 1000)
    }

    /** Returns (count, updatedAtSeconds) or (null, null) if not cached. */
    fun getFollowerCount(pubkey: String): Pair<Long?, Long?> {
        val cached = followerCountCache[pubkey] ?: return Pair(null, null)
        return Pair(cached.first, cached.second)
    }

    // ─── A.5.1 T2: Optimistic follow mutations ───────────────────────────

    /** Optimistic add: appends [targetPubkey] to [ownPubkey]'s follows set. */
    fun addFollow(ownPubkey: String, targetPubkey: String) {
        val current = followsByPubkey[ownPubkey] ?: emptySet()
        updateFollows(ownPubkey, current + targetPubkey, System.currentTimeMillis() / 1000)
    }

    /** Optimistic remove: drops [targetPubkey] from [ownPubkey]'s follows set. */
    fun removeFollow(ownPubkey: String, targetPubkey: String) {
        val current = followsByPubkey[ownPubkey] ?: return
        updateFollows(ownPubkey, current - targetPubkey, System.currentTimeMillis() / 1000)
    }

    // ─── A.5.1 T2: Profile freshness queries ─────────────────────────────

    /** Returns subset of [pubkeys] whose profileUpdatedAt is newer than [threshold] (millis). Preserves input order. */
    fun filterFreshPubkeys(pubkeys: List<String>, threshold: Long): List<String> =
        pubkeys.filter { (profileUpdatedAt[it] ?: 0L) > threshold }

    /** Returns pubkeys whose profileUpdatedAt is older than [olderThan] (millis) or never set. */
    fun stalePubkeys(olderThan: Long): List<String> {
        val allKnown = HashSet<String>()
        allKnown.addAll(profilesByPubkey.keys)
        allKnown.addAll(idsByPubkey.keys)
        return allKnown.filter { (profileUpdatedAt[it] ?: 0L) < olderThan }
    }

    // ─── Reactive flows ─────────────────────────────────────────────────────

    fun feedFlow(filter: FeedFilter, limit: Int = 300): Flow<List<FeedRow>> =
        combine(_feedSignal, _statsSignal, _profileSignal) { _, _, _ -> }
            .map { feedEvents(filter, limit).map { toFeedRow(it) } }

    fun userFeedFlow(
        pubkey: String,
        contentFilter: Int = 0,
        kinds: Set<Int> = setOf(1, 6, 30023),
        limit: Int = 200,
    ): Flow<List<FeedRow>> =
        combine(_feedSignal, _statsSignal, _profileSignal) { _, _, _ -> }
            .map { userFeedEvents(pubkey, contentFilter, kinds, limit).map { toFeedRow(it) } }

    fun followsFlow(pubkey: String): Flow<Set<String>> =
        _followsSignal.map { getFollows(pubkey) ?: emptySet() }

    fun profileFlow(pubkey: String): Flow<NostrEvent?> =
        _profileSignal.map { getProfile(pubkey) }

    /** Thread flow with fixpoint collection — re-emits when feed signal bumps. */
    fun threadFlow(rootId: String): Flow<List<NostrEvent>> =
        _feedSignal.map { collectThread(rootId) }

    /** Thread flow producing FeedRow for UI consumption (ThreadViewModel). */
    fun threadFeedRowFlow(rootId: String): Flow<List<FeedRow>> =
        combine(_feedSignal, _statsSignal, _profileSignal) { _, _, _ -> }
            .map { collectThread(rootId).map { toFeedRow(it) } }

    private fun collectThread(rootId: String): List<NostrEvent> {
        val results = mutableListOf<NostrEvent>()
        val included = mutableSetOf<String>()

        eventsById[rootId]?.let {
            results.add(it)
            included.add(rootId)
        }

        // Add events that explicitly mark rootId as their thread root
        for (event in eventsById.values) {
            if (event.id in included) continue
            if (event.rootId == rootId) {
                results.add(event)
                included.add(event.id)
            }
        }

        // Fixpoint loop: add events whose replyToId points to anything
        // already in the thread, until no new additions
        var changed = true
        while (changed) {
            changed = false
            for (event in eventsById.values) {
                if (event.id in included) continue
                if (event.replyToId != null && event.replyToId in included) {
                    results.add(event)
                    included.add(event.id)
                    changed = true
                }
            }
        }

        return results.sortedBy { it.createdAt }
    }

    // ─── Entity adapters (bridge for consumers still expecting Room types) ──

    /** Convert NostrEvent to EventEntity. Returns null if event not found. */
    fun getEventEntity(eventId: String): EventEntity? {
        val event = eventsById[eventId] ?: return null
        return event.toEventEntity()
    }

    /** Convert profile NostrEvent to UserEntity. Returns null if no profile stored. */
    fun getUserEntity(pubkey: String): UserEntity? {
        val profile = profilesByPubkey[pubkey] ?: return null
        val fields = parseProfileJson(profile.content)
        return UserEntity(
            pubkey = pubkey,
            name = fields["name"],
            displayName = fields["display_name"],
            about = fields["about"],
            picture = fields["picture"],
            nip05 = fields["nip05"],
            lud16 = fields["lud16"],
            banner = fields["banner"],
            createdAt = profile.createdAt,
            updatedAt = profileUpdatedAt[pubkey] ?: 0L,
        )
    }

    /** Observe a single event by ID. Emits null until the event arrives. */
    fun eventEntityFlow(eventId: String): Flow<EventEntity?> =
        _feedSignal.map { getEventEntity(eventId) }

    /** Observe a single user profile by pubkey. Emits null until the profile arrives. */
    fun userEntityFlow(pubkey: String): Flow<UserEntity?> =
        _profileSignal.map { getUserEntity(pubkey) }

    // ─── Outbox routing ─────────────────────────────────────────────────────

    fun writeRelaysFor(pubkey: String): List<String> =
        relayListsByPubkey[pubkey]?.write ?: emptyList()

    fun readRelaysFor(pubkey: String): List<String> =
        relayListsByPubkey[pubkey]?.read ?: emptyList()

    // ─── FeedRow conversion ─────────────────────────────────────────────────

    private fun toFeedRow(event: NostrEvent): FeedRow {
        val profile = profilesByPubkey[event.pubkey]
        val fields = profile?.content?.let { parseProfileJson(it) } ?: emptyMap()
        val authorName = fields["name"]
        val authorDisplayName = fields["display_name"]
        val authorPicture = fields["picture"]
        val authorNip05 = fields["nip05"]

        val statsId = if (event.kind == 6) event.rootId ?: event.id else event.id
        val zap = zapStats(statsId)

        return FeedRow(
            id = event.id,
            pubkey = event.pubkey,
            kind = event.kind,
            content = event.content,
            createdAt = event.createdAt,
            tags = tagsToJson(event.tags),
            relayUrl = event.relayUrl,
            replyToId = event.replyToId,
            rootId = event.rootId,
            hasContentWarning = event.hasContentWarning,
            contentWarningReason = event.contentWarningReason,
            cachedAt = event.firstSeenAt,
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

    /** Serialize tags to JSON format matching Room's storage: [["tag","val"],["tag","val"]] */
    private fun tagsToJson(tags: List<List<String>>): String {
        return tags.joinToString(",", "[", "]") { tag ->
            tag.joinToString(",", "[", "]") { value ->
                "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
        }
    }

    // ─── Snapshot persistence ───────────────────────────────────────────────

    suspend fun saveSnapshotTo(writer: BufferedWriter) {
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

    suspend fun restoreSnapshotFrom(reader: BufferedReader) {
        var inAggregates = false
        var versionChecked = false

        reader.useLines { lines ->
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
        pendingRelays.clear()
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
        profileUpdatedAt.clear()
        followsByPubkey.clear()
        followsCreatedAt.clear()
        followerCountCache.clear()
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

internal fun NostrEvent.toEventEntity(): EventEntity = EventEntity(
    id = id,
    pubkey = pubkey,
    kind = kind,
    content = content,
    createdAt = createdAt,
    tags = tags.joinToString(",", "[", "]") { tag ->
        tag.joinToString(",", "[", "]") { value ->
            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
    },
    sig = sig,
    relayUrl = relayUrl,
    replyToId = replyToId,
    rootId = rootId,
    hasContentWarning = hasContentWarning,
    contentWarningReason = contentWarningReason,
    cachedAt = firstSeenAt,
    firstSeenAt = firstSeenAt,
)

/** Parse profile JSON once and return all string fields. Handles escaped chars, nulls, etc. */
private fun parseProfileJson(content: String): Map<String, String?> {
    return try {
        val obj = NostrJson.parseToJsonElement(content).jsonObject
        buildMap {
            for (key in PROFILE_JSON_KEYS) {
                val element = obj[key]
                put(key, if (element != null && element !is JsonNull && element is JsonPrimitive) element.content else null)
            }
        }
    } catch (_: Exception) { emptyMap() }
}

private val PROFILE_JSON_KEYS = listOf("name", "display_name", "about", "picture", "nip05", "lud16", "banner")
