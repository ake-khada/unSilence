package com.unsilence.app.data.memory

import android.os.Trace
import android.util.Log
// FeedRow, EventEntity, UserEntity are in the same package (data.memory.Models)
import com.unsilence.app.data.relay.NostrJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import com.unsilence.app.data.relay.normalizeRelayUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val SNAPSHOT_VERSION = "SNAPSHOT_V2"
private const val PENDING_RELAYS_CAP = 1_000
private const val PENDING_RELAYS_TRIM = 200
private const val MAX_CONTENT_EVENTS = 10_000
private const val FEED_ROW_CACHE_CAP = 2000
private const val ACTOR_INDEX_CAP = 1_000
private const val ACTOR_TARGETS_CAP = 500
private const val PROFILE_CAP = 2_000
private const val PROFILE_ANCHOR_RECENT_EVENTS = 500
private val CONTENT_KINDS = setOf(1, 6, 7, 9734, 9735, 20, 21, 30023)
private val NOTIFICATION_KINDS = setOf(1, 6, 7, 9735)

@Singleton
class MemoryEventStore @Inject constructor() : com.unsilence.app.data.relay.RelayMetadataSource {

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

    // ─── LRU touch tracking (eviction priority) ──────────────────────────
    /**
     * Last-access timestamp per event id (epoch ms).
     * Updated on insert, snapshot restore, warm-zone hydration, and lookupEvent.
     * Eviction sorts candidates ascending by this value — least-recently-touched first.
     */
    private val lastTouchedAt = ConcurrentHashMap<String, Long>()

    // ─── Derived aggregates (incrementally maintained) ──────────────────────
    private val replyCounts = ConcurrentHashMap<String, Int>()
    private val repostCounts = ConcurrentHashMap<String, Int>()
    private val reactionCounts = ConcurrentHashMap<String, Int>()
    private val zapStatsByEventId = ConcurrentHashMap<String, ZapAggregate>()
    private val statsUpdatedAt = ConcurrentHashMap<String, Long>()

    // ─── Actor-side action indexes (Tier 4: "what have I done?") ───────────
    // Key: actor pubkey → Set<target event ID>
    private val reactedTargetsByActor = ConcurrentHashMap<String, MutableSet<String>>()
    private val repostedTargetsByActor = ConcurrentHashMap<String, MutableSet<String>>()
    private val zappedTargetsByActor = ConcurrentHashMap<String, MutableSet<String>>()
    private val actorAccessedAt = ConcurrentHashMap<String, Long>()

    /** Set by AppBootstrapper after login — used as anchor for LRU eviction. */
    @Volatile var ownPubkey: String? = null

    /** Currently viewed profile — single-slot anchor for content eviction.
     *  Set by UserProfileViewModel on loadProfile(), cleared on onCleared(). */
    @Volatile var viewedPubkey: String? = null

    // Cumulative eviction anchor counters (reset on snapshot via snapshotEvictionAnchors)
    private val evictionAnchoredOwn = AtomicLong(0)
    private val evictionAnchoredMentioned = AtomicLong(0)
    private val evictionAnchoredViewed = AtomicLong(0)

    // ─── Profile + relay routing (kind-derived state) ───────────────────────
    private val profilesByPubkey = ConcurrentHashMap<String, NostrEvent>()
    /** Local cache freshness — when each profile was last updated in MemoryEventStore (epoch ms).
     *  NOT the kind-0 event's original createdAt. Used by ProfileResolver. */
    private val profileUpdatedAt = ConcurrentHashMap<String, Long>()
    // ─── Cached profile fields (populated on profile insert, read during toFeedRow) ──
    private val profileFieldsCache = ConcurrentHashMap<String, Map<String, String?>>()
    private val profileAccessedAt = ConcurrentHashMap<String, Long>()
    // ─── FeedRow cache (R2: version-stamped, invalidated on profile/stats change) ──
    private data class CachedFeedRow(
        val row: FeedRow,
        val profileVersion: Long,
        val statsVersion: Long,
    )
    private val feedRowCache = ConcurrentHashMap<String, CachedFeedRow>()
    private val feedRowAccessedAt = ConcurrentHashMap<String, Long>()

    /** Get cached profile fields for a pubkey. Returns empty map if no profile stored. */
    private fun cachedProfileFields(pubkey: String): Map<String, String?> {
        profileAccessedAt[pubkey] = System.nanoTime()
        return profileFieldsCache[pubkey]
            ?: profilesByPubkey[pubkey]?.content?.let { content ->
                parseProfileJson(content).also { profileFieldsCache[pubkey] = it }
            }
            ?: emptyMap()
    }

    private val followsByPubkey = ConcurrentHashMap<String, Set<String>>()
    private val followsCreatedAt = ConcurrentHashMap<String, Long>()
    private val relayListsByPubkey = ConcurrentHashMap<String, RelayList>()
    private val muteListsByPubkey = ConcurrentHashMap<String, MuteList>()

    // ─── Trust scores (kind 30385) ────────────────────────────────────────────
    private val trustScoresByUrl = ConcurrentHashMap<String, RelayTrustScoreEntity>()

    // ─── Relay monitors (kind 30166 / NIP-66) ─────────────────────────────────
    private val relayMonitorsByUrl = ConcurrentHashMap<String, RelayMonitorEntity>()

    // ─── A.5.1 T5a: Relay config state (kinds 10002/10006/10007/10012) ──────
    private val blockedRelaysByPubkey = ConcurrentHashMap<String, List<String>>()
    private val searchRelaysByPubkey = ConcurrentHashMap<String, List<String>>()
    private val favoritesByPubkey = ConcurrentHashMap<String, List<FavoriteEntry>>()
    private val readWriteRelayConfigsByPubkey = ConcurrentHashMap<String, List<RelayConfig>>()
    /** Tracks createdAt of the latest accepted replaceable event per pubkey+kind.
     *  Key: "$pubkey:$kind". Used for replaceable dedup without scanning eventsById. */
    private val relayKindCreatedAt = ConcurrentHashMap<String, Long>()

    // ─── Follower count cache (NIP-45 COUNT results) ─────────────────────────
    // Key: pubkey → (count, updatedAtSeconds)
    private val followerCountCache = ConcurrentHashMap<String, Pair<Long, Long>>()

    // ─── A.5.1 T5b: Relay sets (kind 30002 materialized) ──────────────────
    // Key: "$ownerPubkey:$dTag" → RelaySet
    private val relaySetsByCoordinate = ConcurrentHashMap<String, RelaySet>()
    /** Tombstones for deleted relay sets. Key: "$ownerPubkey:$dTag" → deletedAtCreatedAt.
     *  Blocks re-materialization from older events. Cleared by a newer upsert. */
    private val deletedRelaySetTombstones = ConcurrentHashMap<String, Long>()

    // ─── Parameterized replaceable events (kind 30002 etc.) ─────────────────
    // Key: "$pubkey:$kind:$dTag" → event ID of the latest version
    private val replaceableByCoordinate = ConcurrentHashMap<String, String>()

    // ─── Media metadata sidecar cache (populated at insert time) ─────────
    // Key: event ID → pre-computed video render models from imeta tags.
    // Read-only after insert — zero cost during feedFlow scans.
    private val videoRenderModelsByEventId = ConcurrentHashMap<String, List<com.unsilence.app.data.model.VideoRenderModel>>()

    fun getVideoRenderModels(eventId: String): List<com.unsilence.app.data.model.VideoRenderModel> =
        videoRenderModelsByEventId[eventId] ?: emptyList()

    fun putVideoRenderModels(eventId: String, models: List<com.unsilence.app.data.model.VideoRenderModel>) {
        if (models.isNotEmpty()) videoRenderModelsByEventId[eventId] = models
    }

    // Image aspect ratios from imeta, keyed by event ID → (url → aspect ratio)
    private val imetaImageDimsByEventId = ConcurrentHashMap<String, Map<String, Float>>()

    fun getImetaImageDims(eventId: String): Map<String, Float> =
        imetaImageDimsByEventId[eventId] ?: emptyMap()

    fun putImetaImageDims(eventId: String, dims: Map<String, Float>) {
        if (dims.isNotEmpty()) imetaImageDimsByEventId[eventId] = dims
    }

    // ─── EventModel sidecar cache (populated at insert time by ContentParser) ─
    // Key: event ID → pre-parsed EventModel for direct UI consumption.
    // Read-only after insert — zero parsing cost during render.
    // NOT serialized to snapshot — reparsed on restore.
    private val eventModelsByEventId = ConcurrentHashMap<String, com.unsilence.app.data.model.EventModel>()

    fun getEventModel(eventId: String): com.unsilence.app.data.model.EventModel? = eventModelsByEventId[eventId]

    /**
     * Lazy parse: returns cached EventModel or parses on first access via computeIfAbsent.
     * Thread-safe — ConcurrentHashMap guarantees at-most-once parse per event ID.
     * Cache-first: survives eviction if model was already parsed.
     */
    fun getOrParseEventModel(eventId: String): com.unsilence.app.data.model.EventModel? {
        eventModelsByEventId[eventId]?.let { return it }
        val event = eventsById[eventId] ?: return null
        return eventModelsByEventId.computeIfAbsent(eventId) {
            com.unsilence.app.data.model.ContentParser.parse(
                id = event.id,
                pubkey = event.pubkey,
                kind = event.kind,
                content = event.content,
                tagsJson = event.tagsJson,
                createdAt = event.createdAt,
                relayUrl = event.relayUrl,
                replyToId = event.replyToId,
                rootId = event.rootId,
                hasContentWarning = event.hasContentWarning,
                contentWarningReason = event.contentWarningReason,
            )
        }
    }

    fun putEventModel(eventId: String, model: com.unsilence.app.data.model.EventModel) {
        eventModelsByEventId[eventId] = model
    }

    // ─── Reactive signals ───────────────────────────────────────────────────
    private val _feedSignal = MutableStateFlow(0L)
    private val _profileSignal = MutableStateFlow(0L)
    private val _statsSignal = MutableStateFlow(0L)
    private val _followsSignal = MutableStateFlow(0L)
    private val _actionSignal = MutableStateFlow(0L)

    /** Bumps when feed-relevant content (kinds 1/6/30023) is inserted. Consumers re-query to pick up new events. */
    val feedSignalFlow: kotlinx.coroutines.flow.StateFlow<Long> get() = _feedSignal

    /** Bumps when any kind-0 (profile metadata) is inserted. Consumers re-render to pick up new names/avatars. */
    val profileSignalFlow: kotlinx.coroutines.flow.StateFlow<Long> get() = _profileSignal

    /** Bumps when actor-side indexes (own reactions, reposts) change. Consumers re-render reaction state. */
    val actionSignalFlow: kotlinx.coroutines.flow.StateFlow<Long> get() = _actionSignal

    /** Bumps when engagement aggregates (kinds 7/9734/9735) change. Consumers re-render counts. */
    val statsSignalFlow: kotlinx.coroutines.flow.StateFlow<Long> get() = _statsSignal
    private val _relayConfigSignal = MutableStateFlow(0L)
    private val _relaySetSignal = MutableStateFlow(0L)
    private val _trustScoreSignal = MutableStateFlow(0L)
    private val _relayMonitorSignal = MutableStateFlow(0L)

    // ─── Eviction bookkeeping ─────────────────────────────────────────────
    private var insertsSinceLastEviction = 0

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
        lastTouchedAt[event.id] = System.currentTimeMillis()

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
            9734 -> handleZapRequest(event)
            9735 -> handleZapReceipt(event)
            10000 -> handleMuteList(event)
            10002 -> handleRelayList(event)
            10006 -> handleBlocked(event)
            10007 -> handleSearchRelays(event)
            10012 -> handleFavorites(event)
            30002 -> {
                handleParameterizedReplaceable(event)
                handleRelaySetMaterialized(event)
            }
            30166 -> handleRelayMonitor(event)
            30385 -> handleTrustScore(event)
        }

        // 5. Bump signals
        when (event.kind) {
            0 -> _profileSignal.value = System.nanoTime()
            3 -> _followsSignal.value = System.nanoTime()
            1, 6, 30023 -> _feedSignal.value = System.nanoTime()
            7, 9734, 9735 -> _statsSignal.value = System.nanoTime()
            // _actionSignal bumped below for actor-side indexes
        }
        // Actor-side signal: bumped for kinds that populate the action indexes
        if (event.kind == 7 || event.kind == 6 || event.kind == 9734) {
            _actionSignal.value = System.nanoTime()
        }

        // Periodic eviction check (every 500 inserts)
        if (++insertsSinceLastEviction >= 500) {
            insertsSinceLastEviction = 0
            evictOldContentEvents()
        }

        return true
    }

    // ─── Kind handlers ──────────────────────────────────────────────────────

    private fun handleProfile(event: NostrEvent) {
        profilesByPubkey.compute(event.pubkey) { _, existing ->
            if (existing == null || event.createdAt >= existing.createdAt) {
                profileUpdatedAt[event.pubkey] = System.currentTimeMillis()
                profileFieldsCache[event.pubkey] = parseProfileJson(event.content)
                profileAccessedAt[event.pubkey] = System.nanoTime()
                event
            } else {
                existing
            }
        }
        trimProfilesIfNeeded()
    }

    /**
     * Evict oldest-accessed profiles when over [PROFILE_CAP].
     * Anchors (never evicted): own pubkey, followed pubkeys, authors of recent events.
     * Cascades to profileUpdatedAt, profileFieldsCache, relayListsByPubkey.
     */
    private fun trimProfilesIfNeeded() {
        if (profilesByPubkey.size <= PROFILE_CAP) return

        // Build anchor set: own + followed + recent event authors
        val anchor = ownPubkey
        val anchors = buildSet {
            anchor?.let { add(it) }
            anchor?.let { followsByPubkey[it]?.let { follows -> addAll(follows) } }
            // Pubkeys from the most recent N content events
            var count = 0
            for (entry in recentByCreatedAt) {
                if (count >= PROFILE_ANCHOR_RECENT_EVENTS) break
                eventsById[entry.id]?.let { add(it.pubkey) }
                count++
            }
        }

        val candidates = profileAccessedAt.entries
            .filter { it.key !in anchors }
            .sortedBy { it.value }

        var removed = 0
        for (entry in candidates) {
            if (profilesByPubkey.size <= PROFILE_CAP * 4 / 5) break
            val pubkey = entry.key
            profilesByPubkey.remove(pubkey)
            profileUpdatedAt.remove(pubkey)
            profileFieldsCache.remove(pubkey)
            profileAccessedAt.remove(pubkey)
            relayListsByPubkey.remove(pubkey)
            removed++
        }
        if (removed > 0) {
            Log.d("MES", "Profiles trimmed $removed entries, remaining=${profilesByPubkey.size}")
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
        // Actor-side index: track what this pubkey has reposted
        addToActorIndex(repostedTargetsByActor, event.pubkey, targetId)
    }

    private fun handleReaction(event: NostrEvent) {
        // Last e-tag is the target
        val targetId = event.tags
            .lastOrNull { it.size >= 2 && it[0] == "e" }
            ?.get(1) ?: return
        reactionCounts.compute(targetId) { _, v -> (v ?: 0) + 1 }
        statsUpdatedAt[targetId] = System.currentTimeMillis()
        // Actor-side index: track what this pubkey has reacted to
        addToActorIndex(reactedTargetsByActor, event.pubkey, targetId)
    }

    private fun handleZapRequest(event: NostrEvent) {
        val targetId = event.rootId ?: return
        // Actor-side index: track what this pubkey has zapped (kind 9734, NOT 9735)
        addToActorIndex(zappedTargetsByActor, event.pubkey, targetId)
    }

    private fun addToActorIndex(
        index: ConcurrentHashMap<String, MutableSet<String>>,
        actorPubkey: String,
        targetId: String,
    ) {
        val targets = index.getOrPut(actorPubkey) { ConcurrentHashMap.newKeySet() }
        // Inner cap: skip add if this actor already has too many targets
        if (targets.size < ACTOR_TARGETS_CAP) {
            targets.add(targetId)
        }
        actorAccessedAt[actorPubkey] = System.nanoTime()
        trimActorIndexesIfNeeded()
    }

    private fun trimActorIndexesIfNeeded() {
        // Use max of the three as the trigger — they share the same actor keyspace
        val totalActors = maxOf(
            reactedTargetsByActor.size,
            repostedTargetsByActor.size,
            zappedTargetsByActor.size,
        )
        if (totalActors <= ACTOR_INDEX_CAP) return

        val anchor = ownPubkey
        val candidates = actorAccessedAt.entries
            .filter { it.key != anchor }
            .sortedBy { it.value }

        var removed = 0
        for (entry in candidates) {
            if (maxOf(
                    reactedTargetsByActor.size,
                    repostedTargetsByActor.size,
                    zappedTargetsByActor.size,
                ) <= ACTOR_INDEX_CAP * 4 / 5
            ) break
            val pubkey = entry.key
            reactedTargetsByActor.remove(pubkey)
            repostedTargetsByActor.remove(pubkey)
            zappedTargetsByActor.remove(pubkey)
            actorAccessedAt.remove(pubkey)
            removed++
        }
        if (removed > 0) {
            Log.d("MES", "Actor indexes trimmed $removed actors")
        }
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
        val key = "${event.pubkey}:10002"
        val existingTs = relayKindCreatedAt[key]
        if (existingTs != null && existingTs >= event.createdAt) return

        val readRelays = mutableListOf<String>()
        val writeRelays = mutableListOf<String>()
        val configs = mutableListOf<RelayConfig>()

        for (tag in event.tags) {
            if (tag.size < 2 || tag[0] != "r") continue
            val url = tag[1]
            val marker = tag.getOrNull(2)?.takeIf { it.isNotEmpty() }
            configs.add(RelayConfig(url, marker))
            when (marker) {
                "read" -> readRelays.add(url)
                "write" -> writeRelays.add(url)
                else -> {
                    readRelays.add(url)
                    writeRelays.add(url)
                }
            }
        }

        relayKindCreatedAt[key] = event.createdAt
        relayListsByPubkey[event.pubkey] = RelayList(readRelays, writeRelays)

        val existingConfigs = readWriteRelayConfigsByPubkey[event.pubkey]
        if (existingConfigs != configs) {
            readWriteRelayConfigsByPubkey[event.pubkey] = configs
            _relayConfigSignal.value = System.nanoTime()
        }
    }

    private fun handleBlocked(event: NostrEvent) {
        val key = "${event.pubkey}:10006"
        val existingTs = relayKindCreatedAt[key]
        if (existingTs != null && existingTs >= event.createdAt) return

        val urls = event.tags
            .filter { it.size >= 2 && it[0] == "relay" }
            .mapNotNull { normalizeRelayUrl(it[1]) }
            .distinct()

        relayKindCreatedAt[key] = event.createdAt
        val existing = blockedRelaysByPubkey[event.pubkey]
        if (existing != urls) {
            blockedRelaysByPubkey[event.pubkey] = urls
            _relayConfigSignal.value = System.nanoTime()
        }
    }

    private fun handleSearchRelays(event: NostrEvent) {
        val key = "${event.pubkey}:10007"
        val existingTs = relayKindCreatedAt[key]
        if (existingTs != null && existingTs >= event.createdAt) return

        val urls = event.tags
            .filter { it.size >= 2 && it[0] == "relay" }
            .mapNotNull { normalizeRelayUrl(it[1]) }
            .distinct()

        relayKindCreatedAt[key] = event.createdAt
        val existing = searchRelaysByPubkey[event.pubkey]
        if (existing != urls) {
            searchRelaysByPubkey[event.pubkey] = urls
            _relayConfigSignal.value = System.nanoTime()
        }
    }

    private fun handleFavorites(event: NostrEvent) {
        val key = "${event.pubkey}:10012"
        val existingTs = relayKindCreatedAt[key]
        if (existingTs != null && existingTs >= event.createdAt) return

        val entries = mutableListOf<FavoriteEntry>()
        val seenRelayUrls = mutableSetOf<String>()
        val seenSetRefs = mutableSetOf<String>()

        for (tag in event.tags) {
            if (tag.size < 2) continue
            when (tag[0]) {
                "relay" -> {
                    val url = normalizeRelayUrl(tag[1]) ?: continue
                    if (seenRelayUrls.add(url)) {
                        entries.add(FavoriteEntry(url = url, setRef = null))
                    }
                }
                "a" -> {
                    val ref = tag[1]
                    if (seenSetRefs.add(ref)) {
                        entries.add(FavoriteEntry(url = null, setRef = ref))
                    }
                }
            }
        }

        relayKindCreatedAt[key] = event.createdAt
        val existing = favoritesByPubkey[event.pubkey]
        if (existing != entries) {
            favoritesByPubkey[event.pubkey] = entries
            _relayConfigSignal.value = System.nanoTime()
        }
    }

    // ─── Kind 30385: Trusted Relay Assertions ─────────────────────────────

    private fun handleTrustScore(event: NostrEvent) {
        fun tag(name: String): String? = event.tags.firstOrNull {
            it.size >= 2 && it[0] == name
        }?.get(1)

        val rawUrl = tag("d") ?: return
        val relayUrl = normalizeRelayUrl(rawUrl) ?: return
        val score = tag("score")?.toIntOrNull() ?: return
        val reliability = tag("reliability")?.toIntOrNull() ?: return
        val quality = tag("quality")?.toIntOrNull() ?: return
        val accessibility = tag("accessibility")?.toIntOrNull() ?: return
        val confidence = tag("confidence") ?: return
        val observations = tag("observations")?.toIntOrNull() ?: 0

        trustScoresByUrl.compute(relayUrl) { _, existing ->
            if (existing != null && existing.updatedAt >= event.createdAt) existing
            else RelayTrustScoreEntity(
                relayUrl = relayUrl,
                score = score,
                reliability = reliability,
                quality = quality,
                accessibility = accessibility,
                confidence = confidence,
                observations = observations,
                policy = tag("policy"),
                countryCode = tag("country_code"),
                operatorVerified = tag("operator_verified"),
                updatedAt = event.createdAt,
            )
        }
        _trustScoreSignal.value = System.nanoTime()
    }

    // ─── Kind 30166: NIP-66 Relay Monitor (liveness / RTT) ───────────────

    private fun handleRelayMonitor(event: NostrEvent) {
        fun tag(name: String): String? = event.tags.firstOrNull {
            it.size >= 2 && it[0] == name
        }?.get(1)

        val rawUrl = tag("d") ?: return
        val relayUrl = normalizeRelayUrl(rawUrl) ?: return

        val rttOpen = tag("rtt-open")?.toIntOrNull()
        val rttRead = tag("rtt-read")?.toIntOrNull()
        val rttWrite = tag("rtt-write")?.toIntOrNull()

        val supportedNips = event.tags
            .filter { it.size >= 2 && it[0] == "N" }
            .mapNotNull { it[1].toIntOrNull() }

        val network = tag("network")
        val geohash = tag("g")

        // Extract relay icon from NIP-11 JSON in event content
        val iconUrl = try {
            if (event.content.isNotBlank()) {
                val obj = NostrJson.parseToJsonElement(event.content).jsonObject
                val icon = obj["icon"]
                if (icon != null && icon !is JsonNull && icon is JsonPrimitive) icon.content else null
            } else null
        } catch (_: Exception) { null }

        val requirements = event.tags
            .filter { it.size >= 2 && it[0] == "R" }
            .map { it[1] }

        relayMonitorsByUrl.compute(relayUrl) { _, existing ->
            if (existing != null && existing.createdAt >= event.createdAt) existing
            else RelayMonitorEntity(
                relayUrl = relayUrl,
                rttOpen = rttOpen,
                rttRead = rttRead,
                rttWrite = rttWrite,
                supportedNips = supportedNips,
                network = network,
                requirements = requirements,
                geohash = geohash,
                iconUrl = iconUrl,
                monitorPubkey = event.pubkey,
                createdAt = event.createdAt,
            )
        }
        _relayMonitorSignal.value = System.nanoTime()
    }

    private fun handleRelaySetMaterialized(event: NostrEvent) {
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
        val coordKey = "${event.pubkey}:$dTag"

        // Check tombstone — deleted sets block older events
        val tombstoneTs = deletedRelaySetTombstones[coordKey]
        if (tombstoneTs != null && event.createdAt <= tombstoneTs) return
        if (tombstoneTs != null) deletedRelaySetTombstones.remove(coordKey)

        // Replaceable dedup per coordinate (ownerPubkey:dTag)
        val dedupKey = "${event.pubkey}:30002:$dTag"
        val existingTs = relayKindCreatedAt[dedupKey]
        if (existingTs != null && existingTs >= event.createdAt) return

        val title = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)
        val description = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
        val image = event.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1)
        val members = event.tags
            .filter { it.size >= 2 && it[0] == "relay" }
            .map { it[1] }
            .distinct()

        val newSet = RelaySet(
            dTag = dTag,
            ownerPubkey = event.pubkey,
            title = title,
            description = description,
            image = image,
            members = members,
        )

        relayKindCreatedAt[dedupKey] = event.createdAt
        val existing = relaySetsByCoordinate[coordKey]
        if (existing != newSet) {
            relaySetsByCoordinate[coordKey] = newSet
            _relaySetSignal.value = System.nanoTime()
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

    /**
     * Type-aware eviction: each content kind has its own cap.
     * Root notes (kind 1) are most valuable — highest cap.
     * Engagement events (kind 7, 9734, 9735) are reconstructible from relays — lowest cap.
     * Mirrors Amethyst's pruning strategy.
     */
    /**
     * Eviction policy — band model:
     *
     *   Band A (anchored, never evicted, not counted against cap):
     *     - Events authored by ownPubkey
     *     - Events mentioning ownPubkey (notifications)
     *     - Events authored by currently viewed profile
     *
     *   Band C (LRU-managed pool):
     *     - Everything else; evicted by lastTouchedAt ASC when kind cap exceeded
     *
     * Recently-displayed events survive regardless of how old their created_at is.
     * An ancient quoted post being viewed today outranks a fresh flood-feed reaction.
     */
    private fun evictOldContentEvents() {
        val kindCaps = mapOf(
            1 to 5000,      // notes (roots + replies combined)
            6 to 1000,      // reposts
            7 to 1000,      // reactions (reconstructible)
            20 to 500,      // pictures
            21 to 500,      // videos
            9734 to 250,    // zap requests (reconstructible)
            9735 to 250,    // zap receipts (reconstructible)
            30023 to 500,   // articles
        )

        val ownPubkeyAnchor = ownPubkey
        val viewed = viewedPubkey

        // Pass 1: bucket events by kind. Anchored events are excluded entirely —
        // they don't count against the cap and can't be evicted.
        val toEvict = mutableListOf<EventEntry>()
        val candidatesByKind = mutableMapOf<Int, MutableList<EventEntry>>()
        var anchoredOwn = 0
        var anchoredMentioned = 0
        var anchoredViewed = 0

        for (entry in recentByCreatedAt) {
            val event = eventsById[entry.id] ?: continue
            val kind = event.kind
            if (kind !in kindCaps) continue

            // Band A: own pubkey — never evicted, never counted
            if (ownPubkeyAnchor != null && event.pubkey == ownPubkeyAnchor) {
                anchoredOwn++
                evictionAnchoredOwn.incrementAndGet()
                continue
            }
            // Band A: events mentioning own pubkey (notifications)
            if (ownPubkeyAnchor != null && event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == ownPubkeyAnchor }) {
                anchoredMentioned++
                evictionAnchoredMentioned.incrementAndGet()
                continue
            }
            // Band A: events authored by currently viewed profile
            if (viewed != null && event.pubkey == viewed) {
                anchoredViewed++
                evictionAnchoredViewed.incrementAndGet()
                continue
            }

            candidatesByKind.getOrPut(kind) { mutableListOf() }.add(entry)
        }

        // Pass 2: for each kind over its cap, sort candidates by lastTouchedAt
        // ascending and evict the least-recently-touched excess.
        for ((kind, candidates) in candidatesByKind) {
            val cap = kindCaps[kind] ?: continue
            if (candidates.size <= cap) continue
            val excess = candidates.size - cap
            candidates.sortBy { lastTouchedAt[it.id] ?: 0L }
            for (i in 0 until excess) {
                toEvict.add(candidates[i])
            }
        }

        if (toEvict.isEmpty()) {
            if (anchoredOwn + anchoredMentioned + anchoredViewed > 0) {
                Log.d("MES", "Eviction: 0 removed, anchored own=$anchoredOwn mentioned=$anchoredMentioned viewed=$anchoredViewed")
            }
            return
        }

        for (entry in toEvict) {
            val event = eventsById.remove(entry.id) ?: continue
            recentByCreatedAt.remove(entry)
            idsByKind[event.kind]?.remove(entry.id)
            idsByPubkey[event.pubkey]?.remove(entry.id)
            lastTouchedAt.remove(entry.id)
            if (event.replyToId != null) {
                idsByReplyTarget[event.replyToId]?.remove(entry.id)
            }
            if (event.rootId != null && event.rootId != event.replyToId) {
                idsByReplyTarget[event.rootId]?.remove(entry.id)
            }
            // Clean up caches and sidecar data
            feedRowCache.remove(entry.id)
            feedRowAccessedAt.remove(entry.id)
            videoRenderModelsByEventId.remove(entry.id)
            imetaImageDimsByEventId.remove(entry.id)
            eventModelsByEventId.remove(entry.id)
        }

        // Clean up aggregates that only reference removed events
        val removeIds = toEvict.map { it.id }.toSet()
        replyCounts.keys.removeAll(removeIds)
        repostCounts.keys.removeAll(removeIds)
        reactionCounts.keys.removeAll(removeIds)
        zapStatsByEventId.keys.removeAll(removeIds)
        statsUpdatedAt.keys.removeAll(removeIds)

        val summary = candidatesByKind
            .filter { (kind, candidates) -> candidates.size > (kindCaps[kind] ?: Int.MAX_VALUE) }
            .toSortedMap()
            .entries
            .joinToString(", ") { (kind, candidates) ->
                val cap = kindCaps[kind] ?: 0
                "k$kind: ${candidates.size - cap} evicted"
            }
        Log.d("MES", "Eviction: ${toEvict.size} removed (LRU-by-touch), anchored own=$anchoredOwn mentioned=$anchoredMentioned viewed=$anchoredViewed [$summary]")
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
            .filter { (it.kind == 1 || it.kind == 30023) && it.content.lowercase().contains(lowerQuery) }
            .sortedByDescending { it.createdAt }
            .take(50)
    }

    fun eventsByIds(ids: Set<String>): List<NostrEvent> {
        return ids.mapNotNull { eventsById[it] }
    }

    // ─── Profile / follows / relay queries ──────────────────────────────────

    fun getProfile(pubkey: String): NostrEvent? = profilesByPubkey[pubkey]
    fun hasProfile(pubkey: String): Boolean = profilesByPubkey.containsKey(pubkey)
    fun getFollows(pubkey: String): Set<String>? = followsByPubkey[pubkey]

    /** Maximum createdAt across all events in MES, or 0 if empty.
     *  Used by AppBootstrapper to inject `since` into the initial feed subscription. */
    fun getMaxEventCreatedAt(): Long {
        val first = recentByCreatedAt.firstOrNull() ?: return 0L
        return first.createdAt
    }

    /**
     * Mark event ids as recently accessed. Prevents eviction of events the user
     * is actively viewing/referencing. Called by TimelineConsumer warm zone + lookupEvent.
     */
    fun markTouched(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        val now = System.currentTimeMillis()
        for (id in eventIds) {
            lastTouchedAt[id] = now
        }
    }

    /** Convenience overload for single id. */
    fun markTouched(eventId: String) {
        lastTouchedAt[eventId] = System.currentTimeMillis()
    }

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
                Log.d("MES", "updateFollows: stale for ${pubkey.take(8)}… (existing=$existingTs > new=$createdAt)")
                return@compute existingTs // stale — ignore
            }
            val existing = followsByPubkey[pubkey]
            val changed = existing == null || existing != followedPubkeys
            followsByPubkey[pubkey] = followedPubkeys
            if (changed) {
                _followsSignal.value = System.nanoTime()
            }
            Log.d("MES", "updateFollows: ${pubkey.take(8)}… → ${followedPubkeys.size} follows (createdAt=$createdAt, changed=$changed)")
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

    // ─── A.5.1 T4: Actor-side action state flows ─────────────────────────

    /** Set of target event IDs the given [pubkey] has reacted to (kind 7). */
    fun reactedEventIdsFlow(pubkey: String): Flow<Set<String>> =
        _actionSignal
            .map { reactedTargetsByActor[pubkey]?.toSet() ?: emptySet() }
            .distinctUntilChanged()

    /** Set of target event IDs the given [pubkey] has reposted (kind 6). */
    fun repostedEventIdsFlow(pubkey: String): Flow<Set<String>> =
        _actionSignal
            .map { repostedTargetsByActor[pubkey]?.toSet() ?: emptySet() }
            .distinctUntilChanged()

    /** Set of target event IDs the given [pubkey] has zapped (kind 9734, NOT 9735). */
    fun zappedEventIdsFlow(pubkey: String): Flow<Set<String>> =
        _actionSignal
            .map { zappedTargetsByActor[pubkey]?.toSet() ?: emptySet() }
            .distinctUntilChanged()

    /**
     * Compatibility shim: optimistic zap sats bump.
     * This mutates recipient-side aggregate state (zapStatsByEventId)
     * for immediate UX feedback. The canonical recipient-side path is
     * kind-9735 via handleZapReceipt. This shim preserves the pre-Tier-4
     * behavior where eventStatsDao.incrementZapStats was called on
     * successful payment. Remove if/when A.5.2 reworks zap aggregation.
     */
    fun incrementZapStats(eventId: String, sats: Long) {
        zapStatsByEventId.compute(eventId) { _, existing ->
            val current = existing ?: ZapAggregate.EMPTY
            ZapAggregate(current.count + 1, current.totalSats + sats)
        }
        statsUpdatedAt[eventId] = System.currentTimeMillis()
    }

    /**
     * Invalidate cached FeedRows for the given event IDs so the next feedFlow scan
     * rebuilds them with fresh stats. Called after engagement batch completion.
     */
    fun invalidateFeedRowCache(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        for (id in eventIds) {
            feedRowCache.remove(id)
            feedRowAccessedAt.remove(id)
        }
        _statsSignal.value = System.nanoTime()
    }

    // ─── A.5.1 T3: Search flows ────────────────────────────────────────────

    /** Reactive note search: kind 1 + 30023, case-insensitive substring, createdAt DESC, limit 50. */
    fun searchNotesFlow(query: String): Flow<List<FeedRow>> =
        _feedSignal
            .map { searchNotes(query) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    private fun searchNotes(query: String): List<FeedRow> {
        val lq = query.lowercase()
        return eventsById.values
            .filter { (it.kind == 1 || it.kind == 30023) && it.content.lowercase().contains(lq) }
            .sortedByDescending { it.createdAt }
            .take(50)
            .map { toFeedRow(it) }
    }

    /** Reactive profile search: matches name, display_name, about; case-insensitive; display_name ASC; limit 50. */
    fun searchUsersFlow(query: String): Flow<List<UserEntity>> =
        _profileSignal
            .map { searchUsers(query) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    private fun searchUsers(query: String): List<UserEntity> {
        val lq = query.lowercase()
        return profilesByPubkey.values
            .mapNotNull { event ->
                val entity = getUserEntity(event.pubkey) ?: return@mapNotNull null
                val matches = listOfNotNull(entity.name, entity.displayName, entity.about)
                    .any { it.lowercase().contains(lq) }
                if (matches) entity else null
            }
            .sortedBy { it.displayName?.lowercase() ?: it.name?.lowercase() ?: "" }
            .take(50)
    }

    /** Exact ID lookup returning FeedRows, sorted by createdAt DESC. */
    fun feedRowsByIds(ids: Set<String>): List<FeedRow> =
        ids.mapNotNull { eventsById[it] }
            .sortedByDescending { it.createdAt }
            .map { toFeedRow(it) }

    // ─── Reactive flows ─────────────────────────────────────────────────────

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun userFeedFlow(
        pubkey: String,
        contentFilter: Int = 0,
        kinds: Set<Int> = setOf(1, 6, 30023),
        limit: Int = 200,
    ): Flow<List<FeedRow>> =
        combine(_feedSignal, _statsSignal, _profileSignal) { _, _, _ -> }
            .sample(200)
            .map { userFeedEvents(pubkey, contentFilter, kinds, limit).map { toFeedRow(it) } }
            .flowOn(Dispatchers.Default)

    fun followsFlow(pubkey: String): Flow<Set<String>> =
        _followsSignal.map { getFollows(pubkey) ?: emptySet() }
            .flowOn(Dispatchers.Default)

    fun profileFlow(pubkey: String): Flow<NostrEvent?> =
        _profileSignal.map { getProfile(pubkey) }
            .flowOn(Dispatchers.Default)

    /** Thread flow with fixpoint collection — re-emits when feed signal bumps. */
    fun threadFlow(rootId: String): Flow<List<NostrEvent>> =
        _feedSignal.map { collectThread(rootId) }
            .flowOn(Dispatchers.Default)

    /** Thread flow producing FeedRow for UI consumption (ThreadViewModel). */
    fun threadFeedRowFlow(rootId: String): Flow<List<FeedRow>> =
        combine(_feedSignal, _statsSignal, _profileSignal) { _, _, _ -> }
            .map { collectThread(rootId).map { toFeedRow(it) } }
            .flowOn(Dispatchers.Default)

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

    /** Return the raw NostrEvent for an event ID, or null if not stored. */
    fun getNostrEvent(eventId: String): NostrEvent? = eventsById[eventId]

    /** Convert profile NostrEvent to UserEntity. Returns null if no profile stored. */
    fun getUserEntity(pubkey: String): UserEntity? {
        val profile = profilesByPubkey[pubkey] ?: return null
        val fields = cachedProfileFields(pubkey)
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

    override fun writeRelaysFor(pubkey: String): List<String> =
        relayListsByPubkey[pubkey]?.write ?: emptyList()

    /** Write relays sorted by trust score (descending). Unknown relays get score 50 (middle rank). */
    fun writeRelaysForRanked(pubkey: String): List<String> {
        val relays = writeRelaysFor(pubkey)
        if (relays.size <= 1) return relays
        return relays.sortedByDescending { url ->
            trustScoresByUrl[url]?.score ?: 50
        }
    }

    fun readRelaysFor(pubkey: String): List<String> =
        relayListsByPubkey[pubkey]?.read ?: emptyList()

    /** Snapshot of all relay lists (pubkey → RelayList). Used by OutboxRouter. */
    fun allRelayListsSnapshot(): Map<String, RelayList> =
        HashMap(relayListsByPubkey)

    /** Reactive flow of all relay lists. Emits on every kind-10002 update. */
    fun allRelayListsFlow(): Flow<Map<String, RelayList>> =
        _relayConfigSignal
            .map { allRelayListsSnapshot() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // ─── A.5.1 T5a: Relay config query APIs ────────────────────────────────

    fun getBlockedRelayUrls(pubkey: String): List<String> =
        blockedRelaysByPubkey[pubkey] ?: emptyList()

    fun getSearchRelayUrls(pubkey: String): List<String> =
        searchRelaysByPubkey[pubkey] ?: emptyList()

    fun getFavoriteRelayConfigs(pubkey: String): List<FavoriteEntry> =
        favoritesByPubkey[pubkey] ?: emptyList()

    fun getReadWriteRelayConfigs(pubkey: String): List<RelayConfig> =
        readWriteRelayConfigsByPubkey[pubkey] ?: emptyList()

    // ─── A.5.1 T5a: Relay config reactive Flows ────────────────────────────

    fun blockedRelayUrlsFlow(pubkey: String): Flow<List<String>> =
        _relayConfigSignal
            .map { getBlockedRelayUrls(pubkey) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    fun searchRelayUrlsFlow(pubkey: String): Flow<List<String>> =
        _relayConfigSignal
            .map { getSearchRelayUrls(pubkey) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    fun readWriteRelayConfigsFlow(pubkey: String): Flow<List<RelayConfig>> =
        _relayConfigSignal
            .map { getReadWriteRelayConfigs(pubkey) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    fun favoriteRelayConfigsFlow(pubkey: String): Flow<List<FavoriteEntry>> =
        _relayConfigSignal
            .map { getFavoriteRelayConfigs(pubkey) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // ─── Trust score query APIs ───────────────────────────────────────────

    override fun getTrustScores(): Map<String, RelayTrustScoreEntity> =
        HashMap(trustScoresByUrl)

    fun trustScoresFlow(): Flow<Map<String, RelayTrustScoreEntity>> =
        _trustScoreSignal
            .map { getTrustScores() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // ─── Relay monitor query APIs (kind 30166 / NIP-66) ──────────────────

    fun getRelayMonitors(): Map<String, RelayMonitorEntity> =
        HashMap(relayMonitorsByUrl)

    fun relayMonitorCount(): Int = relayMonitorsByUrl.size

    fun relayMonitorsFlow(): Flow<Map<String, RelayMonitorEntity>> =
        _relayMonitorSignal
            .map { getRelayMonitors() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // ─── Combined relay health (trust + monitor) ─────────────────────────

    /**
     * Look up health info for a relay URL, trying both raw and normalized forms.
     * Handles the case where configured relay URLs (from kind-10002) aren't
     * normalized but health data keys are.
     */
    fun getRelayHealth(url: String): RelayHealthInfo? {
        val score = trustScoresByUrl[url] ?: normalizeRelayUrl(url)?.let { trustScoresByUrl[it] }
        val monitor = relayMonitorsByUrl[url] ?: normalizeRelayUrl(url)?.let { relayMonitorsByUrl[it] }
        if (score == null && monitor == null) return null
        return RelayHealthInfo(relayUrl = url, trustScore = score, monitor = monitor)
    }

    fun relayHealthFlow(): Flow<Map<String, RelayHealthInfo>> =
        combine(_trustScoreSignal, _relayMonitorSignal) { _, _ ->
            val result = mutableMapOf<String, RelayHealthInfo>()
            for ((url, score) in trustScoresByUrl) {
                result[url] = RelayHealthInfo(url, trustScore = score, monitor = relayMonitorsByUrl[url])
            }
            for ((url, monitor) in relayMonitorsByUrl) {
                val existing = result[url]
                if (existing != null) {
                    if (existing.monitor == null) {
                        result[url] = existing.copy(monitor = monitor)
                    }
                } else {
                    result[url] = RelayHealthInfo(url, monitor = monitor)
                }
            }
            result.toMap()
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    // ─── A.5.1 T5b: Relay set query APIs ───────────────────────────────────

    fun getAllRelaySets(ownerPubkey: String): List<RelaySet> =
        relaySetsByCoordinate.values.filter { it.ownerPubkey == ownerPubkey && it.members.isNotEmpty() }

    fun getSetMembers(ownerPubkey: String, dTag: String): List<String> =
        relaySetsByCoordinate["$ownerPubkey:$dTag"]?.members ?: emptyList()

    fun getAllSetsFlow(ownerPubkey: String): Flow<List<RelaySet>> =
        _relaySetSignal
            .map { getAllRelaySets(ownerPubkey) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    fun getSetMembersFlow(ownerPubkey: String, dTag: String): Flow<List<String>> =
        _relaySetSignal
            .map { getSetMembers(ownerPubkey, dTag) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // ─── A.5.1 T5b: Relay set CRUD mutations ──────────────────────────────

    fun upsertRelaySet(set: RelaySet) {
        val coordKey = "${set.ownerPubkey}:${set.dTag}"
        deletedRelaySetTombstones.remove(coordKey)
        val existing = relaySetsByCoordinate[coordKey]
        if (existing != set) {
            relaySetsByCoordinate[coordKey] = set
            _relaySetSignal.value = System.nanoTime()
        }
    }

    fun deleteRelaySet(ownerPubkey: String, dTag: String, deletedAtCreatedAt: Long) {
        val coordKey = "$ownerPubkey:$dTag"
        deletedRelaySetTombstones[coordKey] = deletedAtCreatedAt
        if (relaySetsByCoordinate.remove(coordKey) != null) {
            _relaySetSignal.value = System.nanoTime()
        }
    }

    fun addRelayToSet(ownerPubkey: String, dTag: String, url: String) {
        val coordKey = "$ownerPubkey:$dTag"
        val existing = relaySetsByCoordinate[coordKey] ?: return
        if (url in existing.members) return
        relaySetsByCoordinate[coordKey] = existing.copy(members = existing.members + url)
        _relaySetSignal.value = System.nanoTime()
    }

    fun removeRelayFromSet(ownerPubkey: String, dTag: String, url: String) {
        val coordKey = "$ownerPubkey:$dTag"
        val existing = relaySetsByCoordinate[coordKey] ?: return
        val newMembers = existing.members.filter { it != url }
        if (newMembers.size == existing.members.size) return
        relaySetsByCoordinate[coordKey] = existing.copy(members = newMembers)
        _relaySetSignal.value = System.nanoTime()
    }

    // ─── A.5.1 T5b: Optimistic relay config mutations ─────────────────────

    fun addReadWriteRelay(pubkey: String, config: RelayConfig) {
        val existing = readWriteRelayConfigsByPubkey[pubkey] ?: emptyList()
        if (existing.any { it.url == config.url }) return
        readWriteRelayConfigsByPubkey[pubkey] = existing + config
        _relayConfigSignal.value = System.nanoTime()
    }

    fun removeReadWriteRelay(pubkey: String, url: String) {
        val existing = readWriteRelayConfigsByPubkey[pubkey] ?: return
        val filtered = existing.filter { it.url != url }
        if (filtered.size == existing.size) return
        readWriteRelayConfigsByPubkey[pubkey] = filtered
        _relayConfigSignal.value = System.nanoTime()
    }

    fun updateRelayMarker(pubkey: String, url: String, newMarker: String?) {
        val existing = readWriteRelayConfigsByPubkey[pubkey] ?: return
        val updated = existing.map { if (it.url == url) it.copy(marker = newMarker) else it }
        if (updated == existing) return
        readWriteRelayConfigsByPubkey[pubkey] = updated
        _relayConfigSignal.value = System.nanoTime()
    }

    fun addBlockedRelay(pubkey: String, url: String) {
        val existing = blockedRelaysByPubkey[pubkey] ?: emptyList()
        if (url in existing) return
        blockedRelaysByPubkey[pubkey] = existing + url
        _relayConfigSignal.value = System.nanoTime()
    }

    fun removeBlockedRelay(pubkey: String, url: String) {
        val existing = blockedRelaysByPubkey[pubkey] ?: return
        val filtered = existing.filter { it != url }
        if (filtered.size == existing.size) return
        blockedRelaysByPubkey[pubkey] = filtered
        _relayConfigSignal.value = System.nanoTime()
    }

    fun addSearchRelay(pubkey: String, url: String) {
        val existing = searchRelaysByPubkey[pubkey] ?: emptyList()
        if (url in existing) return
        searchRelaysByPubkey[pubkey] = existing + url
        _relayConfigSignal.value = System.nanoTime()
    }

    fun removeSearchRelay(pubkey: String, url: String) {
        val existing = searchRelaysByPubkey[pubkey] ?: return
        val filtered = existing.filter { it != url }
        if (filtered.size == existing.size) return
        searchRelaysByPubkey[pubkey] = filtered
        _relayConfigSignal.value = System.nanoTime()
    }

    fun addFavoriteRelay(pubkey: String, entry: FavoriteEntry) {
        val existing = favoritesByPubkey[pubkey] ?: emptyList()
        if (entry.url != null && existing.any { it.url == entry.url }) return
        if (entry.setRef != null && existing.any { it.setRef == entry.setRef }) return
        favoritesByPubkey[pubkey] = existing + entry
        _relayConfigSignal.value = System.nanoTime()
    }

    fun removeFavoriteRelay(pubkey: String, url: String) {
        val existing = favoritesByPubkey[pubkey] ?: return
        val filtered = existing.filter { it.url != url }
        if (filtered.size == existing.size) return
        favoritesByPubkey[pubkey] = filtered
        _relayConfigSignal.value = System.nanoTime()
    }

    fun removeFavoriteBySetRef(pubkey: String, setRef: String) {
        val existing = favoritesByPubkey[pubkey] ?: return
        val filtered = existing.filter { it.setRef != setRef }
        if (filtered.size == existing.size) return
        favoritesByPubkey[pubkey] = filtered
        _relayConfigSignal.value = System.nanoTime()
    }

    // ─── A.5.2: Notification query APIs (scan-based, no insert-time index) ──

    /**
     * Scan-based notification query. Walks idsByKind for notification-eligible
     * kinds (1/6/7/9735), checks #p tags for recipient match, and returns up
     * to [limit] items sorted by createdAt DESC.
     *
     * This mirrors Room's UNION ALL approach: no pre-built index, computed at
     * query time. This ensures events loaded from snapshot, historical fetch,
     * or feed sub all appear — not just events arriving after the handler was
     * added.
     *
     * @param followedOnly If true, only show notifications from users the
     *   recipient follows.
     */
    fun getNotifications(
        recipientPubkey: String,
        limit: Int = 100,
        followedOnly: Boolean = false,
    ): List<NotificationItem> {
        val follows = if (followedOnly) followsByPubkey[recipientPubkey] else null
        // Collect candidate event IDs from kind indexes
        val candidateIds = mutableListOf<String>()
        for (kind in NOTIFICATION_KINDS) {
            idsByKind[kind]?.let { candidateIds.addAll(it) }
        }
        // Filter, resolve, sort, limit
        val items = mutableListOf<NotificationItem>()
        val sorted = candidateIds
            .mapNotNull { id -> eventsById[id]?.let { EventEntry(id, it.createdAt) } }
            .sortedWith(compareByDescending<EventEntry> { it.createdAt }.thenBy { it.id })

        for (entry in sorted) {
            if (items.size >= limit) break
            val event = eventsById[entry.id] ?: continue
            // Check #p tag for recipient match
            if (!event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == recipientPubkey }) continue
            // Exclude self-notifications
            if (event.pubkey == recipientPubkey) continue
            // Following filter
            if (follows != null && event.pubkey !in follows) continue
            val item = buildNotificationItem(event, recipientPubkey) ?: continue
            items.add(item)
        }
        return items
    }

    /**
     * Count notifications for [recipientPubkey] with createdAt > [since].
     */
    fun notificationCountSince(recipientPubkey: String, since: Long): Int {
        var count = 0
        for (kind in NOTIFICATION_KINDS) {
            val ids = idsByKind[kind] ?: continue
            for (id in ids) {
                val event = eventsById[id] ?: continue
                if (event.createdAt <= since) continue
                if (event.pubkey == recipientPubkey) continue
                if (!event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == recipientPubkey }) continue
                count++
            }
        }
        return count
    }

    /**
     * Reactive notification flow. Driven by _feedSignal (kinds 1/6) and
     * _statsSignal (kinds 7/9735) — the same signals that fire when
     * notification-eligible events are inserted.
     */
    fun notificationsFlow(
        recipientPubkey: String,
        limit: Int = 100,
        followedOnly: Boolean = false,
    ): Flow<List<NotificationItem>> =
        combine(_feedSignal, _statsSignal) { _, _ -> }
            .map { getNotifications(recipientPubkey, limit, followedOnly) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /**
     * Build a NotificationItem from a notification-eligible event.
     */
    private fun buildNotificationItem(event: NostrEvent, recipientPubkey: String): NotificationItem? {
        val notifType = deriveNotifType(event, recipientPubkey)
        val fields = cachedProfileFields(event.pubkey)

        val targetNoteId: String?
        val targetNoteContent: String
        val parentNoteContent: String

        when (notifType) {
            "reaction" -> {
                val targetId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                targetNoteId = targetId
                targetNoteContent = targetId?.let { eventsById[it]?.content } ?: ""
                parentNoteContent = ""
            }
            "reply" -> {
                targetNoteId = event.id
                targetNoteContent = event.content
                parentNoteContent = event.replyToId?.let { eventsById[it]?.content } ?: ""
            }
            "repost" -> {
                targetNoteId = event.rootId
                targetNoteContent = event.rootId?.let { eventsById[it]?.content } ?: ""
                parentNoteContent = ""
            }
            "zap" -> {
                val targetId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: event.rootId
                targetNoteId = targetId
                targetNoteContent = targetId?.let { eventsById[it]?.content } ?: ""
                parentNoteContent = ""
            }
            "mention" -> {
                targetNoteId = event.id
                targetNoteContent = event.content
                parentNoteContent = ""
            }
            else -> return null
        }

        return NotificationItem(
            id = event.id,
            notifType = notifType,
            actorPubkey = event.pubkey,
            actorName = fields["name"],
            actorDisplayName = fields["display_name"],
            actorPicture = fields["picture"],
            targetNoteId = targetNoteId,
            targetNoteContent = targetNoteContent,
            parentNoteContent = parentNoteContent,
            createdAt = event.createdAt,
        )
    }

    /**
     * Derive notification type from event kind and threading info.
     * Kind 1 disambiguates: if replyToId/rootId → recipient's event, it's "reply"; else "mention".
     */
    private fun deriveNotifType(event: NostrEvent, recipientPubkey: String): String = when (event.kind) {
        7 -> "reaction"
        6 -> "repost"
        9735 -> "zap"
        1 -> {
            val isReply = event.replyToId?.let { eventsById[it]?.pubkey == recipientPubkey } == true
                || event.rootId?.let { eventsById[it]?.pubkey == recipientPubkey } == true
            if (isReply) "reply" else "mention"
        }
        else -> "mention"
    }

    // ─── FeedRow conversion ─────────────────────────────────────────────────

    private fun toFeedRow(event: NostrEvent): FeedRow {
        val currentProfileVersion = _profileSignal.value
        val currentStatsVersion = _statsSignal.value

        val cached = feedRowCache[event.id]
        if (cached != null
            && cached.profileVersion == currentProfileVersion
            && cached.statsVersion == currentStatsVersion
        ) {
            feedRowAccessedAt[event.id] = System.nanoTime()
            return cached.row
        }

        val fields = cachedProfileFields(event.pubkey)
        val statsId = if (event.kind == 6) event.rootId ?: event.id else event.id
        val zap = zapStats(statsId)

        val row = FeedRow(
            id = event.id,
            pubkey = event.pubkey,
            kind = event.kind,
            content = event.content,
            createdAt = event.createdAt,
            tags = event.tagsJson,
            relayUrl = event.relayUrl,
            replyToId = event.replyToId,
            rootId = event.rootId,
            hasContentWarning = event.hasContentWarning,
            contentWarningReason = event.contentWarningReason,
            cachedAt = event.firstSeenAt,
            zapTotalSats = zap.totalSats,
            authorName = fields["name"],
            authorDisplayName = fields["display_name"],
            authorPicture = fields["picture"],
            authorNip05 = fields["nip05"],
            reactionCount = reactionCount(statsId),
            replyCount = replyCount(statsId),
            repostCount = repostCount(statsId),
            zapCount = zap.count,
        )

        feedRowCache[event.id] = CachedFeedRow(row, currentProfileVersion, currentStatsVersion)
        feedRowAccessedAt[event.id] = System.nanoTime()
        trimFeedRowCacheIfNeeded()
        return row
    }

    private fun trimFeedRowCacheIfNeeded() {
        if (feedRowCache.size <= FEED_ROW_CACHE_CAP) return
        val candidates = feedRowAccessedAt.entries.sortedBy { it.value }
        var removed = 0
        for (entry in candidates) {
            if (feedRowCache.size <= FEED_ROW_CACHE_CAP * 4 / 5) break
            feedRowCache.remove(entry.key)
            feedRowAccessedAt.remove(entry.key)
            removed++
        }
        if (removed > 0) {
            Log.d("MES", "FeedRowCache trimmed $removed entries, remaining=${feedRowCache.size}")
        }
    }

    // ─── Metrics ────────────────────────────────────────────────────────────

    /**
     * Produce a point-in-time size snapshot. No extra locking — relies on
     * ConcurrentHashMap's weakly-consistent iteration. Safe to call from any thread.
     */
    fun snapshotSize(): MesSizeSnapshot {
        // Per-kind breakdown of eventsById
        val kindCounts = mutableMapOf<Int, Int>()
        var eventBytes = 0L
        for ((_, event) in eventsById) {
            kindCounts[event.kind] = (kindCounts[event.kind] ?: 0) + 1
            // Estimate: content + tagsJson + id(64) + pubkey(64) + sig(128) + relayUrl + overhead
            eventBytes += event.content.length + event.tagsJson.length +
                (event.relayUrl.length) + (event.replyToId?.length ?: 0) +
                (event.rootId?.length ?: 0) + (event.contentWarningReason?.length ?: 0) +
                event.relaysSeen.sumOf { it.length } + 320L // fixed overhead
        }

        // Profile byte estimate
        var profileBytes = 0L
        for ((_, event) in profilesByPubkey) {
            profileBytes += event.content.length + 320L
        }

        // Actor index totals
        var reactedTotal = 0
        for ((_, set) in reactedTargetsByActor) reactedTotal += set.size
        var repostedTotal = 0
        for ((_, set) in repostedTargetsByActor) repostedTotal += set.size
        var zappedTotal = 0
        for ((_, set) in zappedTargetsByActor) zappedTotal += set.size

        return MesSizeSnapshot(
            eventCount = eventsById.size,
            eventBytes = eventBytes,
            eventsByKind = kindCounts.toMap(),
            profileCount = profilesByPubkey.size,
            profileBytes = profileBytes,
            followsEntries = followsByPubkey.size,
            followerCountEntries = followerCountCache.size,
            replyCountEntries = replyCounts.size,
            repostCountEntries = repostCounts.size,
            reactionCountEntries = reactionCounts.size,
            zapStatsEntries = zapStatsByEventId.size,
            statsUpdatedAtEntries = statsUpdatedAt.size,
            reactedActors = reactedTargetsByActor.size,
            reactedTargetsTotal = reactedTotal,
            repostedActors = repostedTargetsByActor.size,
            repostedTargetsTotal = repostedTotal,
            zappedActors = zappedTargetsByActor.size,
            zappedTargetsTotal = zappedTotal,
            videoRenderModelEntries = videoRenderModelsByEventId.size,
            imetaImageDimEntries = imetaImageDimsByEventId.size,
            eventModelEntries = eventModelsByEventId.size,
            feedRowCacheEntries = feedRowCache.size,
            relayListEntries = relayListsByPubkey.size,
            trustScoreEntries = trustScoresByUrl.size,
            relayMonitorEntries = relayMonitorsByUrl.size,
            relaySetEntries = relaySetsByCoordinate.size,
            pendingRelayEntries = pendingRelays.size,
        )
    }

    /** Snapshot + reset cumulative eviction anchor counters (for MesMetricsLogger). */
    fun snapshotEvictionAnchors(): Triple<Long, Long, Long> =
        Triple(evictionAnchoredOwn.getAndSet(0), evictionAnchoredMentioned.getAndSet(0), evictionAnchoredViewed.getAndSet(0))

    // ─── Snapshot persistence ───────────────────────────────────────────────

    suspend fun saveSnapshotTo(writer: BufferedWriter) {
        writer.write(SNAPSHOT_VERSION)
        writer.newLine()
        // Write follows FIRST — ~1KB, parsed in <30ms on restore.
        // FeedVM's 10s cold-start timeout needs follows before the 25s
        // event parse completes; placing follows first eliminates the race.
        writer.write("---FOLLOWS---")
        writer.newLine()
        for ((pubkey, follows) in followsByPubkey) {
            val createdAt = followsCreatedAt[pubkey] ?: continue
            writer.write("follows|$pubkey|$createdAt|${follows.joinToString(",")}")
            writer.newLine()
        }
        // Events section — explicit marker so reader can switch from follows.
        // Old snapshots (pre-marker) start events implicitly at section 0.
        writer.write("---EVENTS---")
        writer.newLine()
        val contentEvents = mutableListOf<NostrEvent>()
        val nonContentEvents = mutableListOf<NostrEvent>()
        for (event in eventsById.values) {
            if (event.kind in CONTENT_KINDS) contentEvents.add(event)
            else nonContentEvents.add(event)
        }
        contentEvents.sortByDescending { it.createdAt }
        val cappedContent = contentEvents.take(MAX_CONTENT_EVENTS)

        for (event in nonContentEvents) {
            if (event.kind == 3) continue // Follows persisted in ---FOLLOWS--- section
            writer.write(serializeEvent(event))
            writer.newLine()
        }
        for (event in cappedContent) {
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
        // Write relay health section
        writer.write("---RELAY_HEALTH---")
        writer.newLine()
        for ((url, ts) in trustScoresByUrl) {
            writer.write("trust|$url|${ts.score}|${ts.reliability}|${ts.quality}|${ts.accessibility}|${ts.confidence}|${ts.observations}|${ts.policy ?: ""}|${ts.countryCode ?: ""}|${ts.operatorVerified ?: ""}|${ts.updatedAt}")
            writer.newLine()
        }
        for ((url, m) in relayMonitorsByUrl) {
            writer.write("monitor|$url|${m.rttOpen ?: ""}|${m.rttRead ?: ""}|${m.rttWrite ?: ""}|${m.monitorPubkey}|${m.createdAt}|${m.network ?: ""}|${m.geohash ?: ""}|${m.iconUrl ?: ""}|${m.supportedNips.joinToString(",")}")
            writer.newLine()
        }
    }

    suspend fun restoreSnapshotFrom(reader: BufferedReader) {
        var section = 0  // 0=events, 1=aggregates, 2=relay_health, 3=follows
        var versionChecked = false
        var followsFiredEarly = false

        reader.useLines { lines ->
            for (line in lines) {
                if (!versionChecked) {
                    versionChecked = true
                    if (line != SNAPSHOT_VERSION) {
                        Log.w("MES", "Snapshot version mismatch (found=$line, expected=$SNAPSHOT_VERSION), discarding")
                        return
                    }
                    continue
                }
                if (line == "---EVENTS---") {
                    // Follows section complete (new-format snapshots write follows first).
                    // Fire signal immediately so FeedVM cold-start resolves before
                    // the 25s event parse. All 7 consumers are safe with early-fire.
                    if (followsByPubkey.isNotEmpty() && !followsFiredEarly) {
                        _followsSignal.value = System.nanoTime()
                        followsFiredEarly = true
                    }
                    section = 0; continue
                }
                if (line == "---AGGREGATES---") { section = 1; continue }
                if (line == "---RELAY_HEALTH---") { section = 2; continue }
                if (line == "---FOLLOWS---") { section = 3; continue }
                when (section) {
                    0 -> { val event = deserializeEvent(line) ?: continue; insertFromSnapshot(event) }
                    1 -> restoreAggregate(line)
                    2 -> restoreRelayHealth(line)
                    3 -> restoreFollows(line)
                }
            }
        }

        // Bump all signals once (follows signal fires again — idempotent,
        // consumers use distinctUntilChanged or one-shot .first())
        val now = System.nanoTime()
        _feedSignal.value = now
        _profileSignal.value = now
        _statsSignal.value = now
        _followsSignal.value = now
        _trustScoreSignal.value = now
        _relayMonitorSignal.value = now

        // Evict old content events from snapshot (may contain stale data)
        evictOldContentEvents()

        Log.d("MES", "Snapshot restore complete (EventModel parsing deferred to first read)")
    }

    private fun insertFromSnapshot(event: NostrEvent) {
        eventsById[event.id] = event
        idsByKind.getOrPut(event.kind) { ConcurrentHashMap.newKeySet() }.add(event.id)
        idsByPubkey.getOrPut(event.pubkey) { ConcurrentHashMap.newKeySet() }.add(event.id)
        recentByCreatedAt.add(EventEntry(event.id, event.createdAt))
        lastTouchedAt[event.id] = System.currentTimeMillis()

        if (event.replyToId != null) {
            idsByReplyTarget.getOrPut(event.replyToId) { ConcurrentHashMap.newKeySet() }.add(event.id)
        }
        if (event.rootId != null && event.rootId != event.replyToId) {
            idsByReplyTarget.getOrPut(event.rootId) { ConcurrentHashMap.newKeySet() }.add(event.id)
        }

        // Pre-compute media metadata at snapshot-restore time (sidecar caches).
        // ContentParser.parse is LAZY — deferred to first getOrParseEventModel() read.
        if (event.kind in setOf(1, 6, 20, 21)) {
            val imetaMedia = com.unsilence.app.data.relay.ImetaParser.parseFromList(event.tags)
            val models = com.unsilence.app.data.model.buildVideoRenderModels(
                event.kind, event.content, event.tags,
            )
            if (models.isNotEmpty()) videoRenderModelsByEventId[event.id] = models
            val imageDims = imetaMedia
                .filter { it.mimeType?.startsWith("image/") == true && it.width != null && it.height != null && it.height != 0 }
                .associate { it.url to (it.width!!.toFloat() / it.height!!) }
            if (imageDims.isNotEmpty()) imetaImageDimsByEventId[event.id] = imageDims
        }

        // Restore kind-derived state (profiles, follows, relay lists)
        when (event.kind) {
            0 -> handleProfile(event)
            3 -> handleFollows(event)
            10000 -> handleMuteList(event)
            10002 -> handleRelayList(event)
            10006 -> handleBlocked(event)
            10007 -> handleSearchRelays(event)
            10012 -> handleFavorites(event)
            30002 -> {
                handleParameterizedReplaceable(event)
                handleRelaySetMaterialized(event)
            }
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

    private fun restoreRelayHealth(line: String) {
        val parts = line.split("|")
        if (parts.size < 2) return
        when (parts[0]) {
            "trust" -> {
                if (parts.size < 12) return
                val url = parts[1]
                trustScoresByUrl[url] = RelayTrustScoreEntity(
                    relayUrl = url,
                    score = parts[2].toIntOrNull() ?: return,
                    reliability = parts[3].toIntOrNull() ?: return,
                    quality = parts[4].toIntOrNull() ?: return,
                    accessibility = parts[5].toIntOrNull() ?: return,
                    confidence = parts[6],
                    observations = parts[7].toIntOrNull() ?: 0,
                    policy = parts[8].ifEmpty { null },
                    countryCode = parts[9].ifEmpty { null },
                    operatorVerified = parts[10].ifEmpty { null },
                    updatedAt = parts[11].toLongOrNull() ?: 0L,
                )
            }
            "monitor" -> {
                if (parts.size < 11) return
                val url = parts[1]
                relayMonitorsByUrl[url] = RelayMonitorEntity(
                    relayUrl = url,
                    rttOpen = parts[2].toIntOrNull(),
                    rttRead = parts[3].toIntOrNull(),
                    rttWrite = parts[4].toIntOrNull(),
                    monitorPubkey = parts[5],
                    createdAt = parts[6].toLongOrNull() ?: 0L,
                    network = parts[7].ifEmpty { null },
                    geohash = parts[8].ifEmpty { null },
                    iconUrl = parts[9].ifEmpty { null },
                    supportedNips = parts[10].split(",").mapNotNull { it.toIntOrNull() },
                )
            }
        }
    }

    private fun restoreFollows(line: String) {
        val parts = line.split("|", limit = 4)
        if (parts.size < 4 || parts[0] != "follows") return
        val pubkey = parts[1]
        val createdAt = parts[2].toLongOrNull() ?: return
        val pks = parts[3].split(",").filterTo(mutableSetOf()) { it.isNotBlank() }
        if (pks.isEmpty()) return
        followsByPubkey[pubkey] = pks
        followsCreatedAt[pubkey] = createdAt
    }

    // ─── Serialization (NDJSON) ─────────────────────────────────────────────

    private fun serializeEvent(event: NostrEvent): String {
        val sb = StringBuilder()
        sb.append(event.id).append('\t')
        sb.append(event.pubkey).append('\t')
        sb.append(event.kind).append('\t')
        sb.append(event.content.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")).append('\t')
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
        val tags = deserializeTags(parts[5])
        return NostrEvent(
            id = parts[0],
            pubkey = parts[1],
            kind = parts[2].toIntOrNull() ?: return null,
            content = unescapeContent(parts[3]),
            createdAt = parts[4].toLongOrNull() ?: return null,
            tags = tags,
            tagsJson = tagsToJson(tags),
            sig = parts[6],
            relayUrl = parts[7],
            replyToId = parts[8].ifEmpty { null },
            rootId = parts[9].ifEmpty { null },
            hasContentWarning = parts[10].toBooleanStrictOrNull() ?: false,
            contentWarningReason = parts[11].ifEmpty { null },
            firstSeenAt = parts[12].toLongOrNull() ?: 0L,
            relaysSeen = ConcurrentHashMap.newKeySet<String>().apply {
                addAll(parts[13].split(",").filter { it.isNotEmpty() })
            },
        )
    }

    private fun serializeTags(tags: List<List<String>>): String {
        return tags.joinToString(";") { tag ->
            tag.joinToString(",") {
                it.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;")
            }
        }
    }

    private fun deserializeTags(s: String): List<List<String>> {
        if (s.isEmpty()) return emptyList()
        return escapedSplit(s, ';').map { tagStr ->
            escapedSplit(tagStr, ',').map(::unescapeTagValue)
        }
    }

    /** Split [s] on [delimiter] but skip occurrences preceded by a backslash. */
    private fun escapedSplit(s: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '\\' && i + 1 < s.length -> { buf.append(s[i]); buf.append(s[i + 1]); i += 2 }
                s[i] == delimiter -> { result.add(buf.toString()); buf.clear(); i++ }
                else -> { buf.append(s[i]); i++ }
            }
        }
        result.add(buf.toString())
        return result
    }

    /** Unescape \\, \, and \; back to their literal characters. */
    private fun unescapeTagValue(s: String): String {
        val buf = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) { buf.append(s[i + 1]); i += 2 }
            else { buf.append(s[i]); i++ }
        }
        return buf.toString()
    }

    /** Unescape content field: single-pass handling of \\, \n, \t sequences. */
    private fun unescapeContent(s: String): String {
        val buf = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> buf.append('\n')
                    't' -> buf.append('\t')
                    '\\' -> buf.append('\\')
                    else -> { buf.append(s[i]); buf.append(s[i + 1]) }
                }
                i += 2
            } else {
                buf.append(s[i])
                i++
            }
        }
        return buf.toString()
    }

    // ─── Maintenance ────────────────────────────────────────────────────────

    fun trimToLast(events: Int = 5000) {
        evictOldContentEvents()
    }

    /**
     * Clear user-specific state (follows, relay configs, actions) but preserve
     * the event/profile/stats cache. Used during logout so the next user
     * benefits from already-cached public Nostr data (events, profiles, stats).
     */
    fun clearUserState() {
        followsByPubkey.clear()
        followsCreatedAt.clear()
        followerCountCache.clear()
        relayListsByPubkey.clear()
        muteListsByPubkey.clear()
        blockedRelaysByPubkey.clear()
        searchRelaysByPubkey.clear()
        favoritesByPubkey.clear()
        readWriteRelayConfigsByPubkey.clear()
        relayKindCreatedAt.clear()
        relaySetsByCoordinate.clear()
        deletedRelaySetTombstones.clear()
        trustScoresByUrl.clear()
        relayMonitorsByUrl.clear()
        reactedTargetsByActor.clear()
        repostedTargetsByActor.clear()
        zappedTargetsByActor.clear()
        actorAccessedAt.clear()
        _followsSignal.value++
        _actionSignal.value++
        _relayConfigSignal.value++
        _relaySetSignal.value++
        _trustScoreSignal.value++
        _relayMonitorSignal.value++
    }

    fun clear() {
        eventsById.clear()
        pendingRelays.clear()
        idsByKind.clear()
        idsByPubkey.clear()
        idsByReplyTarget.clear()
        recentByCreatedAt.clear()
        lastTouchedAt.clear()
        replyCounts.clear()
        repostCounts.clear()
        reactionCounts.clear()
        zapStatsByEventId.clear()
        statsUpdatedAt.clear()
        profilesByPubkey.clear()
        profileUpdatedAt.clear()
        profileFieldsCache.clear()
        profileAccessedAt.clear()
        feedRowCache.clear()
        feedRowAccessedAt.clear()
        followsByPubkey.clear()
        followsCreatedAt.clear()
        followerCountCache.clear()
        relayListsByPubkey.clear()
        muteListsByPubkey.clear()
        blockedRelaysByPubkey.clear()
        searchRelaysByPubkey.clear()
        favoritesByPubkey.clear()
        readWriteRelayConfigsByPubkey.clear()
        relayKindCreatedAt.clear()
        replaceableByCoordinate.clear()
        reactedTargetsByActor.clear()
        repostedTargetsByActor.clear()
        zappedTargetsByActor.clear()
        actorAccessedAt.clear()
        _feedSignal.value = 0L
        _profileSignal.value = 0L
        _statsSignal.value = 0L
        _followsSignal.value = 0L
        _actionSignal.value = 0L
        _relayConfigSignal.value = 0L
        relaySetsByCoordinate.clear()
        deletedRelaySetTombstones.clear()
        videoRenderModelsByEventId.clear()
        imetaImageDimsByEventId.clear()
        eventModelsByEventId.clear()
        trustScoresByUrl.clear()
        relayMonitorsByUrl.clear()
        _relaySetSignal.value = 0L
        _trustScoreSignal.value = 0L
        _relayMonitorSignal.value = 0L
    }
}

// ─── Utilities ──────────────────────────────────────────────────────────────

/** Serialize tags to JSON format matching Room's storage: [["tag","val"],["tag","val"]] */
internal fun tagsToJson(tags: List<List<String>>): String {
    return tags.joinToString(",", "[", "]") { tag ->
        tag.joinToString(",", "[", "]") { value ->
            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
    }
}

internal fun NostrEvent.toEventEntity(): EventEntity = EventEntity(
    id = id,
    pubkey = pubkey,
    kind = kind,
    content = content,
    createdAt = createdAt,
    tags = tagsJson,
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
