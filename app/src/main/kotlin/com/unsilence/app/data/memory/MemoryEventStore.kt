package com.unsilence.app.data.memory

import android.util.Log
// FeedRow, EventEntity, UserEntity are in the same package (data.memory.Models)
import com.unsilence.app.data.DEFAULT_WOT_PROVIDER_PUBKEY
import com.unsilence.app.data.DEFAULT_WOT_RELAY
import com.unsilence.app.data.TRUST_SCORE_PROVIDER_PUBKEY
import com.unsilence.app.data.network.NIP05_CACHE_CAP
import com.unsilence.app.data.network.NIP05_CACHE_RECORD_MAX_BYTES
import com.unsilence.app.data.network.Nip05VerificationCacheEntry
import com.unsilence.app.data.network.Nip05VerificationCacheKey
import com.unsilence.app.data.network.Nip05VerificationStatus
import com.unsilence.app.data.network.isValidAt
import com.unsilence.app.data.network.nip05VerificationCacheKey
import com.unsilence.app.data.relay.NostrJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.sample
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.NostrFilter
import com.unsilence.app.data.relay.PROFILE_NOTE_REPLY_EVENT_KIND_SET
import com.unsilence.app.data.relay.withParsedRepostMetadata
import com.unsilence.app.data.relay.TimelineRef
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.relay.boundedSeenRelayHints
import com.unsilence.app.data.relay.boundedIdentitySearchCandidates
import com.unsilence.app.data.relay.deriveProfileRelayCount
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.parseNip51RelayTags
import com.unsilence.app.data.relay.parseNip65RelayTags
import com.unsilence.app.data.relay.profileDerivedBridgeOutbox
import com.unsilence.app.data.relay.shouldAcceptProfileRelayEvent
import com.unsilence.app.data.relay.wotProviderDescriptorFromTags
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip04Dm.crypto.Nip04
import com.vitorpamplona.quartz.nip44Encryption.Nip44
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** V2 (TSV) header — kept for one-shot migration read of pre-V3 snapshots. */
private const val SNAPSHOT_VERSION = "SNAPSHOT_V2"

/** V3 binary snapshot magic — first 4 bytes of every V3 file. */
internal val SNAPSHOT_BINARY_MAGIC = byteArrayOf(0x55, 0x53, 0x4E, 0x53) // "USNS"
/** V13: event records carry a redundant tagsJson wire slot. V19 keeps that
 *  slot byte-compatible but discards it after restore; parsed tags are the
 *  sole in-memory representation.
 *  V14: a length-prefixed owner pubkey is stamped immediately after the 32-byte
 *  header. Restore rejects a snapshot whose owner differs from the current
 *  ownPubkey (foreign-account bleed guard). ≤V13 have no owner field and are
 *  trusted as legacy, restamped to V14 on next save. */
/** V15 appends the own-anon-zap pubkey set (private-zap self-recognition).
 *  V16 appends bounded device-authoritative NIP-11 relay identities.
 *  V17 retains the owner's raw kind-3 inside the FOLLOWS section.
 *  V18 appends the unacknowledged own kind-10000 mutation journal.
 *  V19 appends the bounded NIP-05 verification cache. */
private const val SNAPSHOT_BINARY_VERSION = 19
/** Max retained own outgoing private-zap anon pubkeys (bounded, insertion-order eviction). */
private const val OWN_ANON_ZAP_CAP = 500
private const val RELAY_IDENTITY_CAP = 3_000
private const val PENDING_MUTE_OWNER_CAP = 4
private const val PENDING_MUTE_CHANGE_CAP = 100_000
/** Thrown by restoreSnapshotBinary when a V14+ snapshot's stamped owner pubkey
 *  differs from the current session's ownPubkey — a foreign-account snapshot must
 *  not bleed into this user's MES. Thrown before any MES insertion, so the store
 *  stays empty; SnapshotScheduler catches this, deletes the file, and starts fresh. */
class SnapshotOwnerMismatchException(
    val snapshotOwner: String,
    val currentOwner: String,
) : IOException(
    "Snapshot owner ${snapshotOwner.take(8)}… != current ${currentOwner.take(8)}…",
)

/** Max engaged event IDs persisted per action type (react/repost/zap). */
private const val PERSISTED_ENGAGED_CAP = 10_000
private const val SNAPSHOT_HEADER_SIZE = 32 // bytes
/** Defensive cap on per-string length read from snapshot (1 MB). */
private const val MAX_SNAPSHOT_STR_LEN = 1024 * 1024

private const val PENDING_RELAYS_CAP = 1_000
private const val PENDING_RELAYS_TRIM = 200
/** Disk warm-cache limits are deliberately lower than the in-memory kind caps. */
internal const val PERSISTED_CONTENT_EVENT_CAP = 5_000
/** Owner profile-timeline content is anchored ahead of the shared warm-cache band. */
internal const val PERSISTED_OWN_CONTENT_CAP = 750
internal const val PERSISTED_NON_CONTENT_LRU_CAP = 1_000
internal const val PERSISTED_FOLLOWS_LRU_CAP = 500
internal const val PERSISTED_FOLLOWS_PAYLOAD_BYTE_CAP = 3 * 1024 * 1024
internal const val FOLLOWS_ACCESS_INDEX_CAP = 1_000
internal const val FOLLOWS_ACCESS_INDEX_TRIM = 200

/** Atomic view of the materialized kind-3 state used by safe local mutations. */
internal data class FollowsSnapshot(
    val follows: Set<String>,
    val createdAt: Long?,
    val retainedContactList: NostrEvent? = null,
)

private const val FEED_ROW_CACHE_CAP = 2000
private const val ACTOR_INDEX_CAP = 1_000
private const val ACTOR_TARGETS_CAP = 500
private const val PROFILE_CAP = 2_000
/** Defensive cap on matching candidates materialized for the final trust-aware ranking pass. */
private const val SEARCH_PROFILE_MATCH_CAP = 5_000
private const val PROFILE_ANCHOR_RECENT_EVENTS = 500
/** Backoff after a profile trim pass evicts nothing. When everything over
 *  [PROFILE_CAP] is anchored (typical mid-restore: every restored profile's
 *  author has events in idsByPubkey), candidates stay empty until anchors or
 *  access patterns change — re-scanning per kind-0 insert is a quadratic
 *  livelock (7.5min cold restore on a 37MB snapshot, validated on device). */
private const val PROFILE_TRIM_NOOP_BACKOFF_MS = 60_000L
private const val MAX_FUTURE_DRIFT_SECONDS = 60L
private const val EVICTION_SAFETY_SWEEP_INTERVAL = 5_000
private const val WOT_ASSERTION_CAP = 5_000
private const val WOT_ASSERTION_TRIM = 500
private val CONTENT_KINDS = setOf(
    1, 6, 7, 1018, 1068, 9734, 9735, 16, 20, 21, 22, 34235, 34236, 30023, 1111,
)
private val OWN_PROFILE_CONTENT_KINDS = PROFILE_NOTE_REPLY_EVENT_KIND_SET + 30023

/** Max comments surfaced per article (bounds the rendered list + scan). */
private const val ARTICLE_COMMENT_CAP = 200
private val NOTIFICATION_KINDS = setOf(1, 6, 7, 1018, 9735, 16, 1111)
private val DERIVED_ONLY_KINDS = setOf(30166, 30382)
private val COUNTED_NIP22_PARENT_KINDS = setOf(21, 22, 34235, 34236, 1111)

/** Live-memory caps. Snapshot persistence has separate, lower bounds. */
internal val CONTENT_EVENT_KIND_CAPS = mapOf(
    1 to 5_000,      // notes (roots + replies combined)
    6 to 1_000,      // reposts
    16 to 1_000,     // generic reposts (NIP-18)
    7 to 1_000,      // reactions (reconstructible)
    1018 to 1_000,   // poll responses (reconstructible)
    20 to 500,       // pictures
    21 to 500,       // videos
    22 to 500,       // short-form videos
    34235 to 500,    // addressable videos
    34236 to 500,    // addressable short-form videos
    9734 to 250,     // zap requests (reconstructible)
    9735 to 250,     // zap receipts (reconstructible)
    30023 to 500,    // articles
)

/** Bounded protection tiers. Every tier still converges to its kind cap. */
internal enum class ContentEvictionTier(val number: Int) {
    FOLLOWED_AUTHOR(1),
    TIMELINE_REFERENCED(2),
    ORDINARY(3),
}

internal data class ContentEvictionCandidate(
    val entry: EventEntry,
    val tier: ContentEvictionTier,
    val lastTouchedAt: Long,
)

internal data class ContentAdmissionVictim(
    val eventId: String,
    val tier: ContentEvictionTier,
)

/**
 * Bounded access-order index used to choose one victim before a novel content
 * event is fully indexed. Tier membership is revalidated lazily at removal,
 * so follows and timeline changes cannot evict a newly-protected event.
 */
internal class ContentAdmissionIndex {
    private class KindQueues {
        val tierById = HashMap<String, ContentEvictionTier>()
        val followed = java.util.LinkedHashMap<String, Unit>(16, 0.75f, true)
        val timeline = java.util.LinkedHashMap<String, Unit>(16, 0.75f, true)
        val ordinary = java.util.LinkedHashMap<String, Unit>(16, 0.75f, true)

        fun queue(tier: ContentEvictionTier): java.util.LinkedHashMap<String, Unit> =
            when (tier) {
                ContentEvictionTier.FOLLOWED_AUTHOR -> followed
                ContentEvictionTier.TIMELINE_REFERENCED -> timeline
                ContentEvictionTier.ORDINARY -> ordinary
            }

        fun track(eventId: String, tier: ContentEvictionTier) {
            val previous = tierById.put(eventId, tier)
            if (previous != null && previous != tier) queue(previous).remove(eventId)
            queue(tier)[eventId] = Unit
        }

        fun remove(eventId: String) {
            tierById.remove(eventId)?.let { queue(it).remove(eventId) }
        }
    }

    private val lock = Any()
    private val queuesByKind = HashMap<Int, KindQueues>()

    fun track(eventId: String, kind: Int, tier: ContentEvictionTier?) {
        synchronized(lock) {
            val queues = queuesByKind[kind]
            if (tier == null) {
                queues?.remove(eventId)
                return
            }
            (queues ?: KindQueues().also { queuesByKind[kind] = it }).track(eventId, tier)
        }
    }

    fun touch(eventId: String, kind: Int) {
        synchronized(lock) {
            val queues = queuesByKind[kind] ?: return
            val tier = queues.tierById[eventId] ?: return
            // Access-order LinkedHashMap.get relinks without allocating a node.
            queues.queue(tier)[eventId]
        }
    }

    fun remove(eventId: String, kind: Int) {
        synchronized(lock) { queuesByKind[kind]?.remove(eventId) }
    }

    fun clear() {
        synchronized(lock) { queuesByKind.clear() }
    }

    fun size(kind: Int): Int = synchronized(lock) {
        queuesByKind[kind]?.tierById?.size ?: 0
    }

    /**
     * Reserve the next victim and remove it from the index. [classify] returns
     * null for missing or currently-untouchable events. A stale tier is moved
     * once and selection restarts at ordinary, making reclassification O(1)
     * amortized without eagerly walking the store when timelines change.
     */
    fun pollVictim(
        kind: Int,
        cap: Int,
        classify: (String) -> ContentEvictionTier?,
    ): ContentAdmissionVictim? {
        require(cap >= 0)
        synchronized(lock) {
            val queues = queuesByKind[kind] ?: return null
            while (queues.tierById.size > cap) {
                var reclassified = false
                for (tier in EVICTION_ORDER) {
                    val queue = queues.queue(tier)
                    val eventId = queue.entries.iterator().let { iterator ->
                        if (iterator.hasNext()) iterator.next().key else null
                    } ?: continue
                    val currentTier = classify(eventId)
                    when {
                        currentTier == null -> {
                            queues.remove(eventId)
                            reclassified = true
                        }
                        currentTier != tier -> {
                            queues.track(eventId, currentTier)
                            reclassified = true
                        }
                        else -> {
                            queues.remove(eventId)
                            return ContentAdmissionVictim(eventId, tier)
                        }
                    }
                    break
                }
                if (!reclassified) return null
            }
            return null
        }
    }

    private companion object {
        val EVICTION_ORDER = listOf(
            ContentEvictionTier.ORDINARY,
            ContentEvictionTier.TIMELINE_REFERENCED,
            ContentEvictionTier.FOLLOWED_AUTHOR,
        )
    }
}

/**
 * Chooses the excess entries to evict. All mutable inputs must be snapshots.
 * Candidate fields are materialized before sorting so the comparator never
 * reads a concurrently-mutated map (which can violate TimSort's contract).
 */
internal fun selectContentEvictionCandidates(
    entries: List<EventEntry>,
    cap: Int,
    authorsByEventId: Map<String, String>,
    followedPubkeys: Set<String>,
    timelineReferencedIds: Set<String>,
    lastTouchedAt: Map<String, Long>,
): List<ContentEvictionCandidate> {
    require(cap >= 0)
    val excess = entries.size - cap
    if (excess <= 0) return emptyList()

    val materialized = entries.map { entry ->
        val author = authorsByEventId[entry.id]
        val tier = when {
            author != null && author in followedPubkeys -> ContentEvictionTier.FOLLOWED_AUTHOR
            entry.id in timelineReferencedIds -> ContentEvictionTier.TIMELINE_REFERENCED
            else -> ContentEvictionTier.ORDINARY
        }
        ContentEvictionCandidate(
            entry = entry,
            tier = tier,
            lastTouchedAt = lastTouchedAt[entry.id] ?: 0L,
        )
    }

    // Evict ordinary first, then timeline-backed, then followed-author;
    // least-recently-touched first within each tier. ID is a stable final key.
    return materialized
        .sortedWith(
            compareByDescending<ContentEvictionCandidate> { it.tier.number }
                .thenBy { it.lastTouchedAt }
                .thenBy { it.entry.id },
        )
        .take(excess)
}

internal data class SnapshotEventSelection(
    val nonContentEvents: List<NostrEvent>,
    val contentEvents: List<NostrEvent>,
    val nonContentCandidateCount: Int,
    val contentCandidateCount: Int,
    val anchoredNonContentCount: Int,
    val ownProfileContentCandidateCount: Int,
    val anchoredOwnProfileContentCount: Int,
)

internal fun selectSnapshotEventsForPersistence(
    events: Collection<NostrEvent>,
    ownPubkey: String?,
    followedPubkeys: Set<String>,
    lastTouchedAt: Map<String, Long>,
    contentCap: Int = PERSISTED_CONTENT_EVENT_CAP,
    ownContentCap: Int = PERSISTED_OWN_CONTENT_CAP,
    nonContentLruCap: Int = PERSISTED_NON_CONTENT_LRU_CAP,
): SnapshotEventSelection {
    require(contentCap >= 0)
    require(ownContentCap >= 0)
    require(nonContentLruCap >= 0)

    val anchoredPubkeys = HashSet<String>(followedPubkeys.size + 1).apply {
        addAll(followedPubkeys)
        ownPubkey?.let(::add)
    }
    val ownProfileContentCandidates = ArrayList<NostrEvent>()
    val sharedContentCandidates = ArrayList<NostrEvent>()
    val anchoredNonContent = ArrayList<NostrEvent>()
    val lruNonContent = ArrayList<NostrEvent>()

    for (event in events) {
        when {
            event.kind == 3 -> Unit // Persisted in the dedicated follows section.
            event.kind in OWN_PROFILE_CONTENT_KINDS && event.pubkey == ownPubkey ->
                ownProfileContentCandidates.add(event)
            event.kind in CONTENT_KINDS -> sharedContentCandidates.add(event)
            event.pubkey in anchoredPubkeys -> anchoredNonContent.add(event)
            else -> lruNonContent.add(event)
        }
    }

    val newestFirst = compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id }
    ownProfileContentCandidates.sortWith(newestFirst)
    sharedContentCandidates.sortWith(newestFirst)
    anchoredNonContent.sortWith(newestFirst)

    // Profile-timeline content has an independent owner anchor so old posts
    // cannot be displaced by feed traffic or the owner's own high-volume
    // reactions/zap requests. The anchor still counts against the total
    // content budget, and owner profile overflow is deliberately excluded
    // from the shared band so [ownContentCap] remains a real upper bound.
    val anchoredOwnProfileContent =
        ownProfileContentCandidates.take(minOf(ownContentCap, contentCap))
    val sharedContentBudget = (contentCap - anchoredOwnProfileContent.size).coerceAtLeast(0)

    // Snapshot mutable touch values before sorting. Reading the live CHM from a
    // comparator can violate TimSort's ordering contract while hydration updates it.
    val touchSnapshot = HashMap<String, Long>(lruNonContent.size)
    for (event in lruNonContent) {
        touchSnapshot[event.id] = lastTouchedAt[event.id] ?: event.firstSeenAt
    }
    lruNonContent.sortWith(
        compareByDescending<NostrEvent> { touchSnapshot[it.id] ?: it.firstSeenAt }
            .thenByDescending { it.createdAt }
            .thenBy { it.id },
    )

    return SnapshotEventSelection(
        nonContentEvents = anchoredNonContent + lruNonContent.take(nonContentLruCap),
        contentEvents = anchoredOwnProfileContent + sharedContentCandidates.take(sharedContentBudget),
        nonContentCandidateCount = anchoredNonContent.size + lruNonContent.size,
        contentCandidateCount = ownProfileContentCandidates.size + sharedContentCandidates.size,
        anchoredNonContentCount = anchoredNonContent.size,
        ownProfileContentCandidateCount = ownProfileContentCandidates.size,
        anchoredOwnProfileContentCount = anchoredOwnProfileContent.size,
    )
}

internal data class SnapshotFollowsEntry(
    val pubkey: String,
    val followedPubkeys: Set<String>,
    val createdAt: Long,
)

internal data class SnapshotFollowsSelection(
    val entries: List<SnapshotFollowsEntry>,
    val candidateCount: Int,
    val anchoredCount: Int,
)

internal fun selectSnapshotFollowsForPersistence(
    followsByPubkey: Map<String, Set<String>>,
    followsCreatedAt: Map<String, Long>,
    followsAccessedAt: Map<String, Long>,
    ownPubkey: String?,
    followedPubkeys: Set<String>,
    lruCap: Int = PERSISTED_FOLLOWS_LRU_CAP,
    payloadByteCap: Int = PERSISTED_FOLLOWS_PAYLOAD_BYTE_CAP,
): SnapshotFollowsSelection {
    require(lruCap >= 0)
    require(payloadByteCap >= 0)
    val anchored = ArrayList<SnapshotFollowsEntry>()
    val lru = ArrayList<SnapshotFollowsEntry>()
    for ((pubkey, follows) in followsByPubkey) {
        val entry = SnapshotFollowsEntry(
            pubkey = pubkey,
            followedPubkeys = follows,
            createdAt = followsCreatedAt[pubkey] ?: 0L,
        )
        if (pubkey == ownPubkey) anchored.add(entry) else lru.add(entry)
    }
    anchored.sortBy { it.pubkey }

    val accessSnapshot = HashMap<String, Long>(lru.size)
    for (entry in lru) {
        accessSnapshot[entry.pubkey] =
            followsAccessedAt[entry.pubkey] ?: (entry.createdAt * 1_000L)
    }
    lru.sortWith(
        compareByDescending<SnapshotFollowsEntry> { it.pubkey in followedPubkeys }
            .thenByDescending {
            accessSnapshot[it.pubkey] ?: (it.createdAt * 1_000L)
        }.thenByDescending { it.createdAt }
            .thenBy { it.pubkey },
    )
    var selectedPayloadBytes = 0
    val selectedLru = ArrayList<SnapshotFollowsEntry>(minOf(lruCap, lru.size))
    for (entry in lru) {
        if (selectedLru.size >= lruCap) break
        val entryBytes = snapshotFollowsEntryBinarySize(entry)
        if (entryBytes > payloadByteCap - selectedPayloadBytes) continue
        selectedLru.add(entry)
        selectedPayloadBytes += entryBytes
    }
    return SnapshotFollowsSelection(
        entries = anchored + selectedLru,
        candidateCount = followsByPubkey.size,
        anchoredCount = anchored.size,
    )
}

internal fun snapshotFollowsEntryBinarySize(entry: SnapshotFollowsEntry): Int {
    var bytes = Int.SIZE_BYTES + entry.pubkey.toByteArray(Charsets.UTF_8).size +
        Long.SIZE_BYTES + Int.SIZE_BYTES
    for (followedPubkey in entry.followedPubkeys) {
        bytes += Int.SIZE_BYTES + followedPubkey.toByteArray(Charsets.UTF_8).size
    }
    return bytes
}

internal fun selectFollowsAccessKeysToPrune(
    accessTimes: Map<String, Long>,
    livePubkeys: Set<String>,
    ownPubkey: String?,
    cap: Int = FOLLOWS_ACCESS_INDEX_CAP,
    trim: Int = FOLLOWS_ACCESS_INDEX_TRIM,
): Set<String> {
    require(cap > 0)
    require(trim in 1..cap)

    val stale = accessTimes.keys.filterTo(mutableSetOf()) { it !in livePubkeys }
    val liveCount = accessTimes.size - stale.size
    val overflow = (liveCount - (cap - trim)).coerceAtLeast(0)
    if (overflow == 0) return stale

    accessTimes.entries.asSequence()
        .filter { (pubkey, _) -> pubkey in livePubkeys && pubkey != ownPubkey }
        .sortedWith(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .take(overflow)
        .mapTo(stale) { it.key }
    return stale
}

internal data class SnapshotSectionSizes(
    val headerBytes: Int,
    val followsBytes: Int,
    val eventsBytes: Int,
    val aggregatesBytes: Int,
    val relayHealthBytes: Int,
    val timelinesBytes: Int,
    val tailBytes: Int,
    val totalBytes: Int,
    val eventCount: Int,
    val nonContentEventCount: Int,
    val nonContentCandidateCount: Int,
    val anchoredNonContentCount: Int,
    val contentEventCount: Int,
    val contentCandidateCount: Int,
    val anchoredOwnProfileContentCount: Int,
    val ownProfileContentCandidateCount: Int,
    val followsEntryCount: Int,
    val followsCandidateCount: Int,
    val anchoredFollowsCount: Int,
)

@Singleton
class MemoryEventStore @Inject constructor(
    private val keyProvider: MuteKeyProvider,
    private val timelineServiceProvider: javax.inject.Provider<TimelineService>,
) : com.unsilence.app.data.relay.RelayMetadataSource {

    companion object {
        const val FOLLOWER_COUNT_TTL_SECONDS = 86_400L
    }

    // ─── Primary store ──────────────────────────────────────────────────────
    private val eventsById = ConcurrentHashMap<String, NostrEvent>()

    // ─── Indexes (X-plus, maintained on insert) ─────────────────────────────
    private val idsByKind = ConcurrentHashMap<Int, MutableSet<String>>()
    private val idsByPubkey = ConcurrentHashMap<String, MutableSet<String>>()
    private val idsByReplyTarget = ConcurrentHashMap<String, MutableSet<String>>()
    /** Addressable coordinate ⇄ content event id. The article-era names are retained
     *  to avoid a risky migration; maps are kind-agnostic and also cover NIP-71 video. */
    private val articleIdByCoord = ConcurrentHashMap<String, String>()
    private val articleCoordById = ConcurrentHashMap<String, String>()
    /** Addressable content coordinate → ids of NIP-22 kind-1111 comments rooted by `A`. */
    private val commentIdsByCoord = ConcurrentHashMap<String, MutableSet<String>>()
    private val recentByCreatedAt = ConcurrentSkipListSet<EventEntry>(
        compareByDescending<EventEntry> { it.createdAt }.thenBy { it.id },
    )

    // ─── Relay hints index (populated on insert from e-tag hints + provenance) ──
    /** Per-event-ID set of relay URLs where the event might be found.
     *  Populated from (1) the source relay that delivered an event referencing
     *  this ID via an e-tag, and (2) explicit NIP-10/NIP-18 relay hints in
     *  e-tag position [2]. Read via [relayHintsForEvent]. */
    private val relayHintsForEvent = ConcurrentHashMap<String, MutableSet<String>>()

    /** Immutable snapshot of relay hints for [eventId], or empty. */
    fun relayHintsForEvent(eventId: String): Set<String> =
        relayHintsForEvent[eventId]?.toSet() ?: emptySet()

    /** Index e-tag relay hints + provenance for an event's referenced targets. */
    private fun indexRelayHints(event: NostrEvent) {
        val sourceRelay = event.relaysSeen.firstOrNull() ?: event.relayUrl
        if (sourceRelay.isBlank()) return
        for (tag in event.tags) {
            if (tag.size < 2 || tag[0] != "e") continue
            val targetId = tag[1]
            if (targetId.isBlank()) continue
            val hints = relayHintsForEvent.computeIfAbsent(targetId) { ConcurrentHashMap.newKeySet() }
            hints += sourceRelay
            val explicit = tag.getOrNull(2)
                ?.takeIf { it.startsWith("wss://") || it.startsWith("ws://") }
                ?.let { normalizeRelayUrl(it) }
            if (explicit != null) hints += explicit
        }
    }

    // ─── LRU touch tracking (eviction priority) ──────────────────────────
    /**
     * Last-access timestamp per event id (epoch ms).
     * Updated on insert, snapshot restore, warm-zone hydration, and lookupEvent.
     * Eviction sorts candidates ascending by this value — least-recently-touched first.
     */
    private val lastTouchedAt = ConcurrentHashMap<String, Long>()
    private val contentAdmissionIndex = ContentAdmissionIndex()
    /**
     * Admission and secondary indexing form one mutation transaction. Hot and
     * cold EventProcessor drainers run concurrently; without a per-kind lock,
     * one drainer can reserve another drainer's just-tracked event before that
     * event has reached its secondary indexes, leaving orphan index entries.
     */
    private val contentMutationLocks = CONTENT_EVENT_KIND_CAPS.keys.associateWith { Any() }

    // ─── Derived aggregates (incrementally maintained) ──────────────────────
    private val repostCounts = ConcurrentHashMap<String, Int>()
    private val zapStatsByEventId = ConcurrentHashMap<String, ZapAggregate>()
    private val statsUpdatedAt = ConcurrentHashMap<String, Long>()

    // ─── Engagement contributor indexes (per-target breakdowns for drawer) ──
    private val repostPubkeysByTarget = ConcurrentHashMap<String, MutableSet<String>>()
    private val reactionsByTarget = ConcurrentHashMap<String, MutableSet<ReactionInfo>>()
    private val zapDetailsByTarget = ConcurrentHashMap<String, MutableList<ZapDetail>>()

    // Anon pubkeys of OUR OWN outgoing private zaps. A private zap to another
    // user is anon-signed, so its kind-9735 receipt carries the anon pubkey, not
    // ours; we record it here so handleZapReceipt can promote that receipt's
    // sender → own (sender-local only; never published). Bounded, insertion-order
    // eviction, persisted (V15) since the optimistic drawer row also persists and
    // the receipt can arrive in a later session. Entries are removed once matched.
    private val ownAnonZapPubkeys = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private fun addOwnAnonZap(anon: String) {
        synchronized(ownAnonZapPubkeys) {
            ownAnonZapPubkeys.add(anon)
            while (ownAnonZapPubkeys.size > OWN_ANON_ZAP_CAP) {
                ownAnonZapPubkeys.remove(ownAnonZapPubkeys.iterator().next())
            }
        }
    }

    /** Check-and-consume: true (and removes) if [anon] is one of our pending own anon zaps. */
    private fun consumeOwnAnonZap(anon: String): Boolean =
        synchronized(ownAnonZapPubkeys) { ownAnonZapPubkeys.remove(anon) }

    // ─── Notification recipient index (M4) ────────────────────────────────

    private data class NotifEntry(val createdAt: Long, val eventId: String)
        : Comparable<NotifEntry> {
        override fun compareTo(other: NotifEntry): Int {
            val c = other.createdAt.compareTo(createdAt)
            return if (c != 0) c else eventId.compareTo(other.eventId)
        }
    }

    /**
     * Per-recipient sorted notification index. Populated at insert time
     * (insertCore + insertFromSnapshot, both via indexNotificationRecipients)
     * for any kind-1/6/7/9735 event carrying p-tags. Iteration is in
     * createdAt-DESC order; sort tiebreak by eventId for stability.
     */
    private val notifIdsByRecipient =
        ConcurrentHashMap<String, ConcurrentSkipListSet<NotifEntry>>()

    /**
     * Per-recipient notification version signal. Drives notificationsFlow
     * without bumping when irrelevant events arrive.
     */
    private val notificationSignalByRecipient =
        ConcurrentHashMap<String, MutableStateFlow<Long>>()

    private fun notificationSignalFor(recipient: String): MutableStateFlow<Long> =
        notificationSignalByRecipient.computeIfAbsent(recipient) {
            MutableStateFlow(0L)
        }

    private fun bumpNotificationSignal(recipient: String) {
        notificationSignalFor(recipient).value = System.nanoTime()
    }

    private fun pollIdForResponse(event: NostrEvent): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)

    private fun notificationRecipients(event: NostrEvent): Set<String> {
        if (event.kind == 1018) {
            val pollId = pollIdForResponse(event) ?: return emptySet()
            val poll = eventsById[pollId]?.takeIf { it.kind == 1068 } ?: return emptySet()
            val optionIds = poll.tags.asSequence()
                .filter { it.size >= 2 && it[0] == "option" }
                .map { it[1] }
                .toSet()
            val hasValidChoice = event.tags.any {
                it.size >= 2 && it[0] == "response" && it[1] in optionIds
            }
            if (!hasValidChoice) return emptySet()
            return setOf(poll.pubkey)
        }
        return event.tags.asSequence()
            .filter { it.size >= 2 && it[0] == "p" && it[1].length == 64 }
            .map { it[1] }
            .toSet()
    }

    /** Index notification recipients and bump only the affected recipient flows. */
    private fun indexNotificationRecipients(
        event: NostrEvent,
        backfillPollResponses: Boolean = true,
    ) {
        // Responses can arrive before their poll. Once the poll lands, backfill
        // those response indexes so e-tag-only vote notifications are not lost.
        if (event.kind == 1068 && backfillPollResponses) {
            for (responseId in idsByKind[1018].orEmpty()) {
                val response = eventsById[responseId] ?: continue
                if (pollIdForResponse(response) == event.id) indexNotificationRecipients(response)
            }
            return
        }
        if (event.kind !in NOTIFICATION_KINDS) return
        for (recipient in notificationRecipients(event)) {
            notifIdsByRecipient
                .computeIfAbsent(recipient) { ConcurrentSkipListSet() }
                .add(NotifEntry(event.createdAt, event.id))
            bumpNotificationSignal(recipient)
        }
    }

    /**
     * Remove [event] from any recipient indexes it currently appears in.
     * Called during eviction.
     */
    private fun deindexNotificationRecipients(event: NostrEvent) {
        if (event.kind !in NOTIFICATION_KINDS) return
        val entry = NotifEntry(event.createdAt, event.id)
        for (recipient in notificationRecipients(event)) {
            val set = notifIdsByRecipient[recipient] ?: continue
            if (set.remove(entry)) bumpNotificationSignal(recipient)
        }
    }

    /**
     * Rebuild [notifIdsByRecipient] from [eventsById]. Called once at the
     * end of snapshot restore. Idempotent.
     */
    fun rebuildNotificationIndex() {
        notifIdsByRecipient.clear()
        for ((_, event) in eventsById) {
            if (event.kind !in NOTIFICATION_KINDS) continue
            for (recipient in notificationRecipients(event)) {
                notifIdsByRecipient
                    .computeIfAbsent(recipient) { ConcurrentSkipListSet() }
                    .add(NotifEntry(event.createdAt, event.id))
            }
        }
        for (recipient in notifIdsByRecipient.keys) {
            bumpNotificationSignal(recipient)
        }
    }

    // ─── NIP-57 private zap decrypt sidecar ─────────────────────────────────
    /**
     * Decrypted NIP-57 private zaps, keyed by kind-9735 event id.
     * Populated by PrivateZapRepository after async decrypt completes.
     * Memory-only — re-decrypted on cold start via rescanPendingPrivateZapDecrypts.
     */
    private val privateZapDecryptedById = ConcurrentHashMap<String, DecryptedPrivateZap>()

    /** Fires when a kind-9735 with anon tag arrives addressed to own pubkey. */
    private val _pendingPrivateZapDecrypts = MutableSharedFlow<PendingPrivateZapDecrypt>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pendingPrivateZapDecrypts: Flow<PendingPrivateZapDecrypt> get() = _pendingPrivateZapDecrypts

    // ─── Actor-side action indexes (Tier 4: "what have I done?") ───────────
    // Key: actor pubkey → Set<target event ID>
    private val reactedTargetsByActor = ConcurrentHashMap<String, MutableSet<String>>()
    private val repostedTargetsByActor = ConcurrentHashMap<String, MutableSet<String>>()
    private val zappedTargetsByActor = ConcurrentHashMap<String, MutableSet<String>>()
    private data class ActorTargetKey(val actor: String, val target: String)
    private val reactionEventIdsByActorTarget = ConcurrentHashMap<ActorTargetKey, MutableSet<String>>()
    private val repostEventIdsByActorTarget = ConcurrentHashMap<ActorTargetKey, MutableSet<String>>()
    private val actorAccessedAt = ConcurrentHashMap<String, Long>()

    /** Posts where engagement download hit the limit — cards show "N+" for these. */
    private val engagementCapped: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private data class DeletionTombstone(val pubkey: String, val createdAt: Long)
    private val deletedEventTombstones = ConcurrentHashMap<String, DeletionTombstone>()
    private val deletedAddressableTombstones = ConcurrentHashMap<String, DeletionTombstone>()

    /** Set by AppBootstrapper after login — used as anchor for LRU eviction. */
    @Volatile var ownPubkey: String? = null

    /** Currently viewed profile — single-slot anchor for content eviction.
     *  Set by UserProfileViewModel on loadProfile(), cleared on onCleared(). */
    @Volatile var viewedPubkey: String? = null
        set(value) {
            val previous = field
            if (previous == value) return
            field = value
            reconcileAdmissionForAuthors(previous, value)
        }

    /** Ref IDs anchored by the OWN profile pipeline — quoted notes, repost targets,
     *  thread parents of own-authored events. OWN-scope only (populated at cold-start,
     *  rebuilt from MES own-notes on every app startup). Flat set, no LRU, no per-profile
     *  partitioning. Cleared on logout via [clear]. */
    val profileAnchoredIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private data class EvictionAnchorSnapshot(
        val own: Long = 0,
        val mentioned: Long = 0,
        val viewed: Long = 0,
        val profileRefs: Long = 0,
        val liveTimelineRefs: Long = 0,
    )

    // Interval eviction work is reset by the 60-second release probe.
    private val evictionPasses = AtomicLong(0)
    private val evictionEvicted = AtomicLong(0)
    private val evictionTier1 = AtomicLong(0)
    private val evictionTier2 = AtomicLong(0)
    private val evictionTier3 = AtomicLong(0)
    private val evictionAdmissionReplaced = AtomicLong(0)
    private val evictionAdmissionRejected = AtomicLong(0)
    private val evictionByKind = ConcurrentHashMap<Int, AtomicLong>()
    private val evictionAdmissionRejectedByKind = ConcurrentHashMap<Int, AtomicLong>()
    // Anchors are unique counts from one complete pass, not cumulative visits.
    @Volatile private var lastEvictionAnchors = EvictionAnchorSnapshot()

    // ─── Profile + relay routing (kind-derived state) ───────────────────────
    private val profilesByPubkey = ConcurrentHashMap<String, NostrEvent>()
    /** Local cache freshness — when each profile was last updated in MemoryEventStore (epoch ms).
     *  NOT the kind-0 event's original createdAt. Used by ProfileResolver. */
    private val profileUpdatedAt = ConcurrentHashMap<String, Long>()
    // ─── Cached profile fields (populated on profile insert, read during toFeedRow) ──
    private val profileFieldsCache = ConcurrentHashMap<String, Map<String, String?>>()
    private val profileAccessedAt = ConcurrentHashMap<String, Long>()
    private val nip05VerificationCache =
        ConcurrentHashMap<Nip05VerificationCacheKey, Nip05VerificationCacheEntry>()
    private val nip05VerificationSignal = MutableStateFlow(0L)
    // ─── FeedRow cache (per-author / per-event keys) ───────────────────────
    // Cache hit requires the row's author profile timestamp AND the row's
    // own stats timestamp to be unchanged. Profile/stats updates for OTHER
    // pubkeys/events leave this row's cache valid — only the affected rows
    // recompute. Replaces the old global-signal-version key that invalidated
    // every cached row whenever any profile/stat changed anywhere.
    private data class CachedFeedRow(
        val row: FeedRow,
        val authorProfileTs: Long,
        val statsTs: Long,
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
    /**
     * Full NIP-02 payload for the signed-in user's latest kind-3 only. Other
     * users remain derived-only so contact-list hydration stays memory-bounded.
     */
    @Volatile private var ownContactListEvent: NostrEvent? = null
    /** Epoch-ms recency for bounding non-anchored contact lists on disk.
     *  Independently LRU-bounded so the side index cannot outgrow its purpose. */
    private val followsAccessedAt = ConcurrentHashMap<String, Long>()
    private val relayListsByPubkey = ConcurrentHashMap<String, RelayList>()
    /** Lookup-only bootstrap outboxes derived from accepted profile metadata.
     *  Kept separate from kind-10002 so feed subscription resolution never sees them. */
    private val profileDerivedLookupRelaysByPubkey = ConcurrentHashMap<String, List<String>>()
    private val muteListsByPubkey = ConcurrentHashMap<String, MuteList>()
    /** Serializes own kind-10000 insertion, local intent mutation, and publish CAS. */
    private val muteStateLock = Any()
    /** Accepted/raw event identity per owner; materialized mute state may include pending intent. */
    private val latestMuteEventIdByPubkey = ConcurrentHashMap<String, String>()
    /** Durable local intent, cleared only after at least one relay accepts its signed event. */
    private val pendingMutePublishesByPubkey = ConcurrentHashMap<String, PendingMutePublish>()

    /** Callback fired for every accepted own kind-10000 (including empty content).
     *  AppBootstrapper verifies/decrypts the exact event before reopening publishing. */
    @Volatile internal var ownMuteListEventCallback: ((NostrEvent) -> Unit)? = null

    /** Checks if an event was self-published by MuteListRepository.
     *  Wired by AppBootstrapper to avoid re-processing our own echoes. */
    @Volatile internal var isSelfPublishedCheck: ((String) -> Boolean) = { false }

    // ─── Blossom servers (kind 10063 / NIP-B7) ─────────────────────────────────
    // Key: pubkey → ordered list of server URLs (replaceable event, last-write-wins)
    private val blossomServersByPubkey = ConcurrentHashMap<String, List<String>>()

    // ─── Trust scores (kind 30385) ────────────────────────────────────────────
    private val trustScoresByUrl = ConcurrentHashMap<String, RelayTrustScoreEntity>()

    // ─── NIP-85 user-level WoT assertions (kind 30382) ───────────────────────
    private val wotBySubject = ConcurrentHashMap<String, WotAssertionEntity>()
    private val wotQueriedSubjects = ConcurrentHashMap.newKeySet<String>()
    private val wotAccessedAt = ConcurrentHashMap<String, Long>()
    private val wotProviderLock = Any()
    @Volatile private var activeWotProviderPubkey: String = DEFAULT_WOT_PROVIDER_PUBKEY
    @Volatile private var activeWotProviderRelay: String = DEFAULT_WOT_RELAY
    @Volatile private var ownWotProviderRegistry: WotProviderDescriptor? = null
    @Volatile private var ownWotProviderEncryptedContent: String? = null
    @Volatile private var ownWotProviderEncryptedUpdatedAt: Long = 0L

    // ─── Relay monitors (kind 30166 / NIP-66) ─────────────────────────────────
    private val relayMonitorsByUrl = ConcurrentHashMap<String, RelayMonitorEntity>()
    // Device-fetched NIP-11 identity is authoritative and intentionally separate from monitors.
    private val relayIdentitiesByUrl = ConcurrentHashMap<String, RelayIdentityEntity>()

    // ─── Custom emoji (NIP-30) ───────────────────────────────────────────────
    // Kind-30030 emoji sets, keyed by (authorPubkey, setName) coordinate
    private val emojiSetsByCoordinate = ConcurrentHashMap<Pair<String, String>, EmojiSetEntity>()
    // Kind-10030 user emoji lists, keyed by pubkey
    private val userEmojiListByPubkey = ConcurrentHashMap<String, UserEmojiListEntity>()
    // Replaceable dedup: "$pubkey:$kind:$dTag" → createdAt
    private val emojiKindCreatedAt = ConcurrentHashMap<String, Long>()

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
        // Admission can reject or concurrently evict a content event before
        // the relay drainer finishes deriving its sidecars. Never retain an
        // orphan sidecar for a payload the store does not own.
        if (models.isEmpty()) return
        eventsById.computeIfPresent(eventId) { _, retained ->
            videoRenderModelsByEventId[eventId] = models
            retained
        }
    }

    // Image aspect ratios from imeta, keyed by event ID → (url → aspect ratio)
    private val imetaImageDimsByEventId = ConcurrentHashMap<String, Map<String, Float>>()

    fun getImetaImageDims(eventId: String): Map<String, Float> =
        imetaImageDimsByEventId[eventId] ?: emptyMap()

    fun putImetaImageDims(eventId: String, dims: Map<String, Float>) {
        if (dims.isEmpty()) return
        eventsById.computeIfPresent(eventId) { _, retained ->
            imetaImageDimsByEventId[eventId] = dims
            retained
        }
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
        eventModelsByEventId[eventId]?.let {
            putVideoRenderModels(eventId, it.media.videos.map { video -> video.model })
            return it
        }
        val event = eventsById[eventId] ?: return null
        return eventModelsByEventId.computeIfAbsent(eventId) {
            val model = com.unsilence.app.data.model.ContentParser.parse(
                id = event.id,
                pubkey = event.pubkey,
                kind = event.kind,
                content = event.content,
                tags = event.tags,
                createdAt = event.createdAt,
                relayUrl = event.relayUrl,
                replyToId = event.replyToId,
                rootId = event.rootId,
                hasContentWarning = event.hasContentWarning,
                contentWarningReason = event.contentWarningReason,
                preparsedRepost = event.repostInfo,
            )
            putVideoRenderModels(eventId, model.media.videos.map { video -> video.model })
            model
        }
    }

    fun putEventModel(eventId: String, model: com.unsilence.app.data.model.EventModel) {
        eventModelsByEventId[eventId] = model
        putVideoRenderModels(eventId, model.media.videos.map { video -> video.model })
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

    /** Bumps when any materialized kind-3 contact list changes. */
    val followsSignalFlow: kotlinx.coroutines.flow.StateFlow<Long> get() = _followsSignal

    /** Bumps when engagement aggregates (kinds 7/9734/9735) change. Consumers re-render counts. */
    val statsSignalFlow: kotlinx.coroutines.flow.StateFlow<Long> get() = _statsSignal
    val wotSignalFlow: kotlinx.coroutines.flow.StateFlow<Long> get() = _wotSignal

    /** Targeted invalidation for per-event-id stats observation. */
    sealed class StatsInvalidation {
        data class Targeted(val ids: Set<String>) : StatsInvalidation()
        data object Broadcast : StatsInvalidation()
    }

    private val _statsInvalidations = MutableSharedFlow<StatsInvalidation>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _muteListSignal = MutableStateFlow(0L)
    private val _relayConfigSignal = MutableStateFlow(0L)
    private val _relaySetSignal = MutableStateFlow(0L)
    private val _trustScoreSignal = MutableStateFlow(0L)
    private val _wotSignal = MutableStateFlow(0L)
    private val _relayMonitorSignal = MutableStateFlow(0L)
    private val _relayIdentitySignal = MutableStateFlow(0L)
    private val _emojiSetSignal = MutableStateFlow(0L)
    private val _snapshotRestoredSignal = MutableStateFlow(0L)
    val snapshotRestoredFlow: StateFlow<Long> = _snapshotRestoredSignal

    /**
     * Emits the target event ID when a kind-9735 zap receipt arrives whose
     * embedded kind-9734 zap request was authored by [ownPubkey].
     * NoteActionsViewModel uses this to clear the optimistic sats overlay
     * only when our own receipt lands — not on someone else's zap.
     */
    private val _ownZapReceived = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val ownZapReceivedFlow: Flow<String> get() = _ownZapReceived

    // ─── Eviction bookkeeping ─────────────────────────────────────────────
    private val insertsSinceLastEviction = java.util.concurrent.atomic.AtomicInteger(0)
    // Gated maintenance counters (mirror evictionTickAfterInsert): run the
    // size/sort trim check every Nth call instead of on every hot-path call.
    private val actorIndexAddsSinceTrimCheck = java.util.concurrent.atomic.AtomicInteger(0)
    private val feedRowPutsSinceTrimCheck = java.util.concurrent.atomic.AtomicInteger(0)

    // ─── Signal-bump coalescing ───────────────────────────────────────────
    // Kind handlers (handleRelayList, handleRelayMonitor, handleTrustScore,
    // ...) each bump their own signal. During a cold-start burst (244
    // kind-10002, 1000+ kind-30166) per-event bumps drive downstream Flow
    // re-emits — each allocating a fresh HashMap snapshot — and trigger
    // 6-8s Daveys via GC pressure.
    //
    // InsertDirty defers those bumps. When non-null, handlers set the flag
    // and the batch caller flushes once at end. When null (snapshot restore
    // and a few legacy paths), handlers self-bump as before.
    internal class InsertDirty {
        var feed = false
        var profile = false
        var stats = false
        var follows = false
        var action = false
        var relayConfig = false
        var trustScore = false
        var wot = false
        var relayMonitor = false
        var relaySet = false
        var emojiSet = false
        var admissionRejected = 0
        val invalidatedStatsIds: MutableSet<String> = mutableSetOf()
    }

    private fun flushDirty(d: InsertDirty) {
        val now = System.nanoTime()
        if (d.feed) _feedSignal.value = now
        if (d.profile) _profileSignal.value = now
        if (d.stats) _statsSignal.value = now
        if (d.follows) _followsSignal.value = now
        if (d.action) _actionSignal.value = now
        if (d.relayConfig) _relayConfigSignal.value = now
        if (d.trustScore) _trustScoreSignal.value = now
        if (d.wot) _wotSignal.value = now
        if (d.relayMonitor) _relayMonitorSignal.value = now
        if (d.relaySet) _relaySetSignal.value = now
        if (d.emojiSet) _emojiSetSignal.value = now
        if (d.invalidatedStatsIds.isNotEmpty()) {
            _statsInvalidations.tryEmit(
                StatsInvalidation.Targeted(d.invalidatedStatsIds.toSet())
            )
        }
    }

    private fun reconcileAdmissionForAuthors(vararg pubkeys: String?) {
        for (pubkey in pubkeys.filterNotNull().distinct()) {
            val eventIds = idsByPubkey[pubkey]?.toList().orEmpty()
            for (eventId in eventIds) {
                val event = eventsById[eventId] ?: continue
                if (event.kind !in CONTENT_EVENT_KIND_CAPS) continue
                contentAdmissionIndex.track(
                    eventId = event.id,
                    kind = event.kind,
                    tier = contentAdmissionTier(event),
                )
            }
        }
    }

    private fun contentAdmissionTier(event: NostrEvent): ContentEvictionTier? {
        if (event.kind !in CONTENT_EVENT_KIND_CAPS) return null
        val owner = ownPubkey
        if (owner != null) {
            if (event.pubkey == owner) return null
            val mentionsOwner = if (event.kind in NOTIFICATION_KINDS) {
                owner in notificationRecipients(event)
            } else {
                event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == owner }
            }
            if (mentionsOwner) return null
        }
        if (event.pubkey == viewedPubkey) return null
        if (event.id in profileAnchoredIds) return null
        val followed = owner?.let { followsByPubkey[it] }.orEmpty()
        return when {
            event.pubkey in followed -> ContentEvictionTier.FOLLOWED_AUTHOR
            timelineServiceProvider.get().isLiveReferenced(event.id) ->
                ContentEvictionTier.TIMELINE_REFERENCED
            else -> ContentEvictionTier.ORDINARY
        }
    }

    private fun currentContentAdmissionTier(eventId: String): ContentEvictionTier? =
        eventsById[eventId]?.let(::contentAdmissionTier)

    /**
     * Reserve space before the event reaches secondary indexes and handlers.
     * If the novel event itself is the lowest-priority LRU victim, reject it at
     * the door; otherwise remove the reserved existing victim first.
     */
    private fun admitNovelContent(event: NostrEvent, dirty: InsertDirty): Boolean {
        val cap = CONTENT_EVENT_KIND_CAPS[event.kind] ?: return true
        contentAdmissionIndex.track(event.id, event.kind, contentAdmissionTier(event))
        val invalidatedTargets = mutableSetOf<String>()
        val removedIds = mutableSetOf<String>()

        while (contentAdmissionIndex.size(event.kind) > cap) {
            val victim = contentAdmissionIndex.pollVictim(
                kind = event.kind,
                cap = cap,
                classify = ::currentContentAdmissionTier,
            ) ?: break
            if (victim.eventId == event.id) {
                eventsById.remove(event.id, event)
                dirty.admissionRejected++
                recordAdmissionRejected(event.kind)
                publishEvictionInvalidations(invalidatedTargets, removedIds)
                return false
            }
            val removed = removeContentEventForEviction(
                eventId = victim.eventId,
                invalidatedReplyTargets = invalidatedTargets,
            ) ?: continue
            removedIds += removed.id
            markKindDirty(removed.kind, dirty)
            recordAdmissionReplacement(victim.tier, removed.kind)
        }

        publishEvictionInvalidations(invalidatedTargets, removedIds)
        return true
    }

    // ─── Relay provenance (called by EventProcessor for seenIds duplicates) ──

    // Buffer for relay URLs that arrive via addRelaySeen before the event
    // is flushed from EventProcessor's channel into eventsById. Applied
    // during insert() when the event finally arrives.
    private val pendingRelays = ConcurrentHashMap<String, MutableSet<String>>()

    internal val pendingRelayCount: Int get() = pendingRelays.size

    fun addRelaySeen(eventId: String, relayUrl: String) {
        val event = eventsById[eventId]
        if (event != null) {
            if (event.relaysSeen.add(relayUrl)) {
                feedRowCache.remove(eventId)
            }
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

    // ─── Insert (called by EventProcessor flushBatch / flushControlBatch via insertBatch) ──────

    /**
     * Insert a single event with coalesced end-of-call signal bumps and
     * eviction check. Used for direct-path inserts (control-plane kinds
     * from EventProcessor). For batched inserts from channel drainers,
     * use [insertBatch].
     */
    fun insert(event: NostrEvent): Boolean {
        val nowSec = System.currentTimeMillis() / 1000L
        if (event.createdAt > nowSec + MAX_FUTURE_DRIFT_SECONDS) return false

        val dirty = InsertDirty()
        val inserted = insertCore(event, dirty)
        if (inserted) {
            markKindDirty(event.kind, dirty)
        }
        evictionTickAfterInsert((if (inserted) 1 else 0) + dirty.admissionRejected)
        flushDirty(dirty)
        return inserted
    }

    /**
     * Insert a batch of events with coalesced signal bumps.
     * Instead of N signal bumps for N events, bumps each dirty signal type
     * exactly once at the end. Returns the number of novel events inserted.
     *
     * Kind handlers (handleRelayList, handleRelayMonitor, handleTrustScore,
     * ...) populate the same [InsertDirty] accumulator instead of bumping
     * directly, so a 1000-event control-plane burst produces one bump per
     * affected signal — not 1000.
     */
    fun insertBatch(events: List<NostrEvent>): Int {
        if (events.isEmpty()) return 0
        val nowSec = System.currentTimeMillis() / 1000L
        val dirty = InsertDirty()
        var inserted = 0

        for (event in events) {
            if (event.createdAt > nowSec + MAX_FUTURE_DRIFT_SECONDS) continue
            if (!insertCore(event, dirty)) continue
            inserted++
            markKindDirty(event.kind, dirty)
        }

        flushDirty(dirty)
        evictionTickAfterInsert(inserted + dirty.admissionRejected)
        return inserted
    }

    /**
     * WoT chunk insert path: coalesces assertion insertion and EOSE-only queried
     * marking into one dirty flush, so a fetched chunk produces at most one
     * _wotSignal bump while preserving Pending vs Absent.
     */
    fun insertWotAssertionChunk(
        providerPubkey: String,
        events: List<NostrEvent>,
        queriedSubjects: Collection<String>,
    ): Int {
        val chunkProvider = normalizeHexPubkey(providerPubkey) ?: return 0
        if (events.isEmpty() && queriedSubjects.isEmpty()) return 0
        val nowSec = System.currentTimeMillis() / 1000L
        val dirty = InsertDirty()
        var inserted = 0

        synchronized(wotProviderLock) {
            if (chunkProvider != activeWotProviderPubkey) return 0

            for (event in events) {
                if (event.createdAt > nowSec + MAX_FUTURE_DRIFT_SECONDS) continue
                if (!insertCore(event, dirty)) continue
                inserted++
                markKindDirty(event.kind, dirty)
            }

            var queriedChanged = false
            for (subject in queriedSubjects.mapNotNull { normalizeHexPubkey(it) }) {
                wotAccessedAt[subject] = System.nanoTime()
                if (wotQueriedSubjects.add(subject)) queriedChanged = true
            }
            if (queriedChanged) {
                trimWotAssertionsIfNeeded()
                dirty.wot = true
            }
        }

        flushDirty(dirty)
        evictionTickAfterInsert(inserted + dirty.admissionRejected)
        return inserted
    }

    /** Map a kind to its corresponding feed/profile/stats/follows/action dirty flags. */
    private fun markKindDirty(kind: Int, d: InsertDirty) {
        when (kind) {
            0 -> d.profile = true
            3 -> d.follows = true
            1, 6, 20, 21, 22, 34235, 34236, 1068, 30023, 1111 -> d.feed = true
            7, 9734, 9735 -> d.stats = true
        }
        if (kind == 7 || kind == 6 || kind == 16 || kind == 1018 || kind == 9734) d.action = true
    }

    /**
     * Core insert logic shared by [insert] and [insertBatch].
     * Handles dedup, indexing, and kind handlers. Does NOT bump signals or
     * check eviction — callers are responsible for those.
     *
     * [dirty] is the accumulator for deferred signal bumps. Kind handlers
     * that would normally bump _relayConfigSignal / _trustScoreSignal /
     * _relayMonitorSignal / _relaySetSignal set the corresponding flag
     * here instead. The caller (insert / insertBatch) flushes once at end.
     */
    private fun insertCore(event: NostrEvent, dirty: InsertDirty): Boolean {
        // One storage-boundary invariant for every source (relay, snapshot,
        // optimistic local insert): repost protocol JSON is parsed and any
        // embedded event is verified before indexes or UI projections see it.
        val normalizedEvent = event.withParsedRepostMetadata()
        contentMutationLocks[normalizedEvent.kind]?.let { lock ->
            return synchronized(lock) { insertCoreUnlocked(normalizedEvent, dirty) }
        }
        return if (normalizedEvent.kind == 10000) {
            synchronized(muteStateLock) { insertCoreUnlocked(normalizedEvent, dirty) }
        } else {
            insertCoreUnlocked(normalizedEvent, dirty)
        }
    }

    /** Caller holds [muteStateLock] for kind-10000 so publish snapshots cannot
     * observe the raw event index before its materialized mute state is ready. */
    private fun insertCoreUnlocked(event: NostrEvent, dirty: InsertDirty): Boolean {
        if (event.kind == 30385 && !isTrustScoreProvider(event.pubkey)) return false
        if (event.kind in DERIVED_ONLY_KINDS) {
            return insertDerivedOnly(event, dirty)
        }
        if (event.kind != 5 && isDeletedByTombstone(event)) return false

        // 1. Dedup: putIfAbsent returns null if novel
        val existing = eventsById.putIfAbsent(event.id, event)
        if (existing != null) {
            // Duplicate — just record the relay
            if (existing.relaysSeen.addAll(event.relaysSeen)) {
                feedRowCache.remove(event.id)
            }
            if (event.kind == 10040 && ownWotProviderRegistry == null) {
                handleWotProviderRegistry(existing, dirty)
            }
            return false
        }

        // Apply any relay URLs that arrived via addRelaySeen before this insert
        pendingRelays.remove(event.id)?.let { pending ->
            event.relaysSeen.addAll(pending)
        }

        if (!admitNovelContent(event, dirty)) return false

        // 2. Update indexes
        idsByKind.getOrPut(event.kind) { ConcurrentHashMap.newKeySet() }.add(event.id)
        idsByPubkey.getOrPut(event.pubkey) { ConcurrentHashMap.newKeySet() }.add(event.id)
        recentByCreatedAt.add(EventEntry(event.id, event.createdAt))
        lastTouchedAt[event.id] = System.currentTimeMillis()

        forEachReplyIndexTarget(event) { targetId ->
            idsByReplyTarget.getOrPut(targetId) { ConcurrentHashMap.newKeySet() }.add(event.id)
        }

        // 2a. Addressable content coordinate ⇄ event id. Superseded revisions are
        // retained in v1, matching the standing article limitation. Our own actions
        // still e-tag video event ids; publishing a-tags is a separate follow-up.
        if (event.kind == 30023 || event.kind == 34235 || event.kind == 34236) {
            val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
            registerArticleCoord(event.id, "${event.kind}:${event.pubkey}:$d")
        }
        // Article comment index (kind-1/1111 referencing an article by a/A coord).
        indexArticleComment(event, dirty)

        // 2b. Index relay hints from e-tags (provenance + explicit NIP-10/18 hints)
        indexRelayHints(event)

        // 2c. Notification recipient index (M4)
        indexNotificationRecipients(event)

        // 3. Update derived aggregates based on kind
        when (event.kind) {
            0 -> handleProfile(event)
            1 -> handleNote(event, dirty)
            1111 -> handleNip22Comment(event, dirty)
            3 -> handleFollows(event, dirty)
            6, 16 -> handleRepost(event, dirty)
            7 -> handleReaction(event, dirty)
            5 -> handleDeletion(event, dirty)
            9734 -> handleZapRequest(event)
            9735 -> handleZapReceipt(event, dirty)
            10000 -> handleMuteList(event)
            10002 -> handleRelayList(event, dirty)
            10006 -> handleBlocked(event, dirty)
            10007 -> handleSearchRelays(event, dirty)
            10012 -> handleFavorites(event, dirty)
            10040 -> handleWotProviderRegistry(event, dirty)
            10063 -> handleBlossomServers(event)
            30002 -> {
                handleParameterizedReplaceable(event)
                handleRelaySetMaterialized(event, dirty)
            }
            10030 -> handleUserEmojiList(event, dirty)
            30030 -> handleEmojiSet(event, dirty)
            30166 -> handleRelayMonitor(event, dirty)
            30385 -> handleTrustScore(event, dirty)
        }

        return true
    }

    private fun insertDerivedOnly(event: NostrEvent, dirty: InsertDirty): Boolean {
        pendingRelays.remove(event.id)
        return when (event.kind) {
            30166 -> handleRelayMonitor(event, dirty)
            30382 -> handleWotAssertion(event, dirty)
            else -> false
        }
    }

    private fun evictionTickAfterInsert(count: Int = 1) {
        if (count <= 0) return
        if (insertsSinceLastEviction.addAndGet(count) >= EVICTION_SAFETY_SWEEP_INTERVAL) {
            insertsSinceLastEviction.set(0)
            evictOldContentEvents()
        }
    }

    // ─── Kind handlers ──────────────────────────────────────────────────────

    private fun handleProfile(event: NostrEvent) {
        val incomingFields = parseProfileJson(event.content)
        profilesByPubkey.compute(event.pubkey) { _, existing ->
            if (existing == null || event.createdAt >= existing.createdAt) {
                markProfileUpdated(event.pubkey)
                profileFieldsCache[event.pubkey] = incomingFields.withIdentityFallback(profileFieldsCache[event.pubkey])
                event
            } else {
                fillMissingProfileIdentity(event.pubkey, incomingFields)
                existing
            }
        }
        retainNip05VerificationForCurrentProfile(
            event.pubkey,
            profileFieldsCache[event.pubkey]?.get("nip05"),
        )
        val derivedLookupRelays = profileDerivedBridgeOutbox(
            profileFieldsCache[event.pubkey]?.get("nip05"),
        )
        if (derivedLookupRelays.isEmpty()) {
            profileDerivedLookupRelaysByPubkey.remove(event.pubkey)
        } else {
            profileDerivedLookupRelaysByPubkey[event.pubkey] = derivedLookupRelays
        }
        trimProfilesIfNeeded()
    }

    private fun fillMissingProfileIdentity(pubkey: String, candidateFields: Map<String, String?>) {
        val currentFields = cachedProfileFields(pubkey)
        val mergedFields = currentFields.withIdentityFallback(candidateFields)
        if (mergedFields == currentFields) return
        markProfileUpdated(pubkey)
        profileFieldsCache[pubkey] = mergedFields
    }

    private fun markProfileUpdated(pubkey: String) {
        val now = System.currentTimeMillis()
        profileUpdatedAt.compute(pubkey) { _, previous ->
            if (previous == null || now > previous) now else previous + 1
        }
        profileAccessedAt[pubkey] = System.nanoTime()
    }

    /**
     * Evict oldest-accessed profiles when over [PROFILE_CAP].
     * Anchors (never evicted): own pubkey, followed pubkeys, authors of recent events.
     * Cascades to profileUpdatedAt, profileFieldsCache, relayListsByPubkey.
     */
    @Volatile private var profileTrimBackoffUntilMs = 0L

    private fun trimProfilesIfNeeded() {
        if (profilesByPubkey.size <= PROFILE_CAP) return
        if (System.currentTimeMillis() < profileTrimBackoffUntilMs) return

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

        // Snapshot profileAccessedAt values before sorting — same TimSort
        // contract bug as evictOldContentEvents. profileAccessedAt is mutated
        // concurrently by handleProfile and cachedProfileFields. See CLAUDE.md
        // rule #24.
        // Also anchor pubkeys that have events in MES — don't evict a profile
        // if its author's content is still stored (evict-then-refetch is wasted work).
        val candidateKeys = profileAccessedAt.keys.filter { it !in anchors && !idsByPubkey.containsKey(it) }
        val accessSnapshot = HashMap<String, Long>(candidateKeys.size)
        for (k in candidateKeys) accessSnapshot[k] = profileAccessedAt[k] ?: 0L
        val candidates = candidateKeys.sortedBy { accessSnapshot[it] ?: 0L }

        var removed = 0
        for (pubkey in candidates) {
            if (profilesByPubkey.size <= PROFILE_CAP * 4 / 5) break
            profilesByPubkey.remove(pubkey)
            profileUpdatedAt.remove(pubkey)
            profileFieldsCache.remove(pubkey)
            profileAccessedAt.remove(pubkey)
            relayListsByPubkey.remove(pubkey)
            profileDerivedLookupRelaysByPubkey.remove(pubkey)
            removed++
        }
        if (removed > 0) {
            Log.d("MES", "Profiles trimmed $removed entries, remaining=${profilesByPubkey.size}")
        } else {
            // Everything over cap is anchored — nothing can change until
            // anchors/access patterns do. Back off instead of re-scanning
            // on every subsequent kind-0 insert.
            profileTrimBackoffUntilMs = System.currentTimeMillis() + PROFILE_TRIM_NOOP_BACKOFF_MS
        }
    }

    private fun handleNote(event: NostrEvent, dirty: InsertDirty) {
        invalidateReplyTarget(event.replyToId, dirty)
        if (event.rootId != null && event.rootId != event.replyToId) {
            invalidateReplyTarget(event.rootId, dirty)
        }
    }

    private fun handleNip22Comment(event: NostrEvent, dirty: InsertDirty) {
        // Addressable-root counts come from articleCommentIds(coord). Event-addressed
        // video roots (21/22) and kind-1111 parents use the ordinary live reply index.
        val parentKind = event.tags
            .firstOrNull { it.size >= 2 && it[0] == "k" }
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (parentKind !in COUNTED_NIP22_PARENT_KINDS) return

        val parentId = event.replyToId
            ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.getOrNull(1)
            ?: return
        if (parentId == event.id) return

        invalidateReplyTarget(parentId, dirty)
    }

    private fun handleFollows(event: NostrEvent, dirty: InsertDirty? = null) {
        val pubkeys = event.tags
            .filter { it.size >= 2 && it[0] == "p" }
            .map { it[1] }
            .toSet()
        updateFollowsInternal(
            pubkey = event.pubkey,
            followedPubkeys = pubkeys,
            createdAt = event.createdAt,
            dirty = dirty,
            retainedEvent = event,
        )
    }

    private fun handleRepost(event: NostrEvent, dirty: InsertDirty) {
        val targetId = repostTargetId(event) ?: return
        repostCounts.compute(targetId) { _, v -> (v ?: 0) + 1 }
        repostPubkeysByTarget
            .computeIfAbsent(targetId) { ConcurrentHashMap.newKeySet() }
            .add(event.pubkey)
        statsUpdatedAt[targetId] = System.currentTimeMillis()
        dirty.invalidatedStatsIds.add(targetId)
        // Actor-side index: track what this pubkey has reposted
        addToActorIndex(repostedTargetsByActor, event.pubkey, targetId)
        addActionEventId(repostEventIdsByActorTarget, event.pubkey, targetId, event.id)
    }

    /**
     * Parses a kind-7 reaction content string into [ReactionContent].
     * If content is `:shortcode:` and the event has a matching `["emoji", shortcode, url]` tag,
     * returns [ReactionContent.Custom]. No regex needed — handles any characters in shortcode
     * (spaces, hyphens, dots, etc.).
     */
    private fun parseReactionContent(content: String, tags: List<List<String>>): ReactionContent {
        if (content.length >= 3 && content.startsWith(':') && content.endsWith(':')) {
            val shortcode = content.substring(1, content.length - 1)
            val url = tags.firstOrNull { tag ->
                tag.size >= 3 && tag[0] == "emoji" && tag[1] == shortcode
            }?.get(2)
            if (url != null) return ReactionContent.Custom(shortcode, url)
        }
        return ReactionContent.Standard(content)
    }

    private fun handleReaction(event: NostrEvent, dirty: InsertDirty) {
        // Last e-tag is the target; for a reaction to an addressable event (e.g. a
        // long-form article) there may be no e-tag — fall back to the a/A coordinate
        // so article likes are counted (reactionCount merges the coord key).
        val targetId = reactionTargetId(event) ?: return
        val contentStr = event.content.ifBlank { "+" }
        val reactionContent = parseReactionContent(contentStr, event.tags)
        // NIP-25 "-" is a downvote — don't index as a displayable reaction
        if (reactionContent != ReactionContent.Standard("-")) {
            reactionsByTarget
                .computeIfAbsent(targetId) { ConcurrentHashMap.newKeySet() }
                .add(ReactionInfo(event.pubkey, reactionContent))
        }
        statsUpdatedAt[targetId] = System.currentTimeMillis()
        invalidateStatsForTarget(targetId, dirty)
        // Actor-side index: track what this pubkey has reacted to (skip dislikes)
        if (reactionContent != ReactionContent.Standard("-")) {
            addToActorIndex(reactedTargetsByActor, event.pubkey, targetId)
            addActionEventId(reactionEventIdsByActorTarget, event.pubkey, targetId, event.id)
        }
    }

    private fun handleDeletion(event: NostrEvent, dirty: InsertDirty) {
        for (tag in event.tags) {
            if (tag.size < 2) continue
            when (tag[0]) {
                "e" -> deleteReferencedEvent(
                    eventId = tag[1],
                    deletionPubkey = event.pubkey,
                    deletionCreatedAt = event.createdAt,
                    dirty = dirty,
                )
                "a" -> deleteReferencedAddressable(
                    coordinate = tag[1],
                    deletionPubkey = event.pubkey,
                    deletionCreatedAt = event.createdAt,
                    dirty = dirty,
                )
            }
        }
    }

    /**
     * Rebuilds [reactionsByTarget] from raw kind-7 events in [eventsById].
     * Called after snapshot restore so [parseReactionContent] reclassifies reactions
     * that were persisted with the old narrower regex.
     */
    private fun reindexReactionsFromEvents() {
        reactionsByTarget.clear()
        val kind7Ids = idsByKind[7] ?: return
        if (kind7Ids.isEmpty()) return
        var customCount = 0
        var standardCount = 0
        var dislikeCount = 0
        var noETagCount = 0
        for (id in kind7Ids) {
            val event = eventsById[id] ?: continue
            val targetId = (event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }
                ?: event.tags.lastOrNull { it.size >= 2 && (it[0] == "a" || it[0] == "A") })
                ?.get(1) ?: run { noETagCount++; continue }
            val contentStr = event.content.ifBlank { "+" }
            val reactionContent = parseReactionContent(contentStr, event.tags)
            // NIP-25 "-" is a downvote — don't index as a displayable reaction
            if (reactionContent == ReactionContent.Standard("-")) { dislikeCount++; continue }
            if (reactionContent is ReactionContent.Custom) customCount++ else standardCount++
            reactionsByTarget
                .computeIfAbsent(targetId) { ConcurrentHashMap.newKeySet() }
                .add(ReactionInfo(event.pubkey, reactionContent))
        }
        Log.d("MES", "Reindexed ${kind7Ids.size} kind-7 reactions (custom=$customCount, standard=$standardCount, dislikes=$dislikeCount, noETag=$noETagCount)")
    }

    private fun rebuildActionEventIndexesFromEvents() {
        reactionEventIdsByActorTarget.clear()
        repostEventIdsByActorTarget.clear()
        for (event in eventsById.values) {
            when (event.kind) {
                7 -> {
                    val targetId = reactionTargetId(event) ?: continue
                    val reactionContent = parseReactionContent(event.content.ifBlank { "+" }, event.tags)
                    if (reactionContent != ReactionContent.Standard("-")) {
                        addActionEventId(reactionEventIdsByActorTarget, event.pubkey, targetId, event.id)
                    }
                }
                6, 16 -> {
                    val targetId = repostTargetId(event) ?: continue
                    addActionEventId(repostEventIdsByActorTarget, event.pubkey, targetId, event.id)
                }
            }
        }
    }

    private fun reactionTargetId(event: NostrEvent): String? =
        (event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }
            ?: event.tags.lastOrNull { it.size >= 2 && (it[0] == "a" || it[0] == "A") })
            ?.get(1)

    private fun repostTargetId(event: NostrEvent): String? =
        event.rootId
            ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.getOrNull(1)
            ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "a" }?.getOrNull(1)

    private fun actionKey(actorPubkey: String, targetId: String) =
        ActorTargetKey(actorPubkey, targetId)

    private fun addActionEventId(
        index: ConcurrentHashMap<ActorTargetKey, MutableSet<String>>,
        actorPubkey: String,
        targetId: String,
        eventId: String,
    ) {
        index.getOrPut(actionKey(actorPubkey, targetId)) { ConcurrentHashMap.newKeySet() }.add(eventId)
    }

    private fun removeActionEventId(
        index: ConcurrentHashMap<ActorTargetKey, MutableSet<String>>,
        actorPubkey: String,
        targetId: String,
        eventId: String,
    ) {
        val key = actionKey(actorPubkey, targetId)
        val set = index[key] ?: return
        set.remove(eventId)
        if (set.isEmpty()) index.remove(key)
    }

    private fun removeFromActorIndex(
        index: ConcurrentHashMap<String, MutableSet<String>>,
        actorPubkey: String,
        targetId: String,
    ) {
        val targets = index[actorPubkey] ?: return
        targets.remove(targetId)
        if (targets.isEmpty()) index.remove(actorPubkey)
        actorAccessedAt[actorPubkey] = System.nanoTime()
    }

    private fun addressableCoordinate(event: NostrEvent): String? {
        if (event.kind !in 10000..39999) return null
        val dTag = if (event.kind in 30000..39999) {
            event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.getOrNull(1).orEmpty()
        } else {
            ""
        }
        return "${event.kind}:${event.pubkey}:$dTag"
    }

    private fun isDeletedByTombstone(event: NostrEvent): Boolean {
        val eventTombstone = deletedEventTombstones[event.id]
        if (eventTombstone != null && eventTombstone.pubkey == event.pubkey) return true
        val coord = addressableCoordinate(event) ?: return false
        val addressableTombstone = deletedAddressableTombstones[coord]
        return addressableTombstone != null &&
            addressableTombstone.pubkey == event.pubkey &&
            event.createdAt <= addressableTombstone.createdAt
    }

    fun isDeleted(event: NostrEvent): Boolean = isDeletedByTombstone(event)

    private fun deleteReferencedAddressable(
        coordinate: String,
        deletionPubkey: String,
        deletionCreatedAt: Long,
        dirty: InsertDirty,
    ) {
        val parts = coordinate.split(":", limit = 3)
        if (parts.size < 3 || parts[1] != deletionPubkey) return
        deletedAddressableTombstones[coordinate] = DeletionTombstone(deletionPubkey, deletionCreatedAt)

        val storedKey = "${parts[1]}:${parts[0]}:${parts[2]}"
        val storedId = replaceableByCoordinate[storedKey] ?: articleIdByCoord[coordinate] ?: return
        deleteReferencedEvent(storedId, deletionPubkey, deletionCreatedAt, dirty)
    }

    private fun deleteReferencedEvent(
        eventId: String,
        deletionPubkey: String,
        deletionCreatedAt: Long,
        dirty: InsertDirty,
    ) {
        val existing = eventsById[eventId]
        if (existing == null) {
            deletedEventTombstones[eventId] = DeletionTombstone(deletionPubkey, deletionCreatedAt)
            return
        }
        if (existing.kind == 5 || existing.pubkey != deletionPubkey) return
        deletedEventTombstones[eventId] = DeletionTombstone(deletionPubkey, deletionCreatedAt)
        deleteStoredEvent(existing, dirty)
    }

    private fun deleteStoredEvent(event: NostrEvent, dirty: InsertDirty) {
        deindexDerivedForDeletion(event, dirty)
        removeFromIndexes(event)
        lastTouchedAt.remove(event.id)
        feedRowCache.remove(event.id)
        feedRowAccessedAt.remove(event.id)
        videoRenderModelsByEventId.remove(event.id)
        imetaImageDimsByEventId.remove(event.id)
        eventModelsByEventId.remove(event.id)
        forEachReplyIndexTarget(event) { targetId ->
            removeReplyIndexEntry(targetId, event.id)
        }
        if (event.kind == 30023 || event.kind == 34235 || event.kind == 34236) {
            addressableCoordinate(event)?.let { coord ->
                articleIdByCoord.remove(coord)
                articleCoordById.remove(event.id)
                commentIdsByCoord[coord]?.forEach { commentId ->
                    feedRowCache.remove(commentId)
                }
            }
        }
        deindexArticleComment(event, dirty)
        if (event.kind in setOf(1, 6, 16, 20, 21, 22, 34235, 34236, 1068, 30023, 1111)) {
            dirty.feed = true
        }
    }

    private fun deindexArticleComment(event: NostrEvent, dirty: InsertDirty) {
        if (event.kind != 1 && event.kind != 1111) return
        for (tag in event.tags) {
            if (tag.size < 2 || (tag[0] != "a" && tag[0] != "A")) continue
            val ids = commentIdsByCoord[tag[1]] ?: continue
            if (ids.remove(event.id)) {
                if (ids.isEmpty()) commentIdsByCoord.remove(tag[1])
                statsUpdatedAt[tag[1]] = System.currentTimeMillis()
                invalidateStatsForTarget(tag[1], dirty)
                dirty.stats = true
            }
        }
    }

    private fun deindexDerivedForDeletion(event: NostrEvent, dirty: InsertDirty) {
        when (event.kind) {
            1 -> {
                invalidateRemovedReplyTarget(event.replyToId, dirty)
                if (event.rootId != null && event.rootId != event.replyToId) {
                    invalidateRemovedReplyTarget(event.rootId, dirty)
                }
            }
            1111 -> {
                val parentKind = event.tags
                    .firstOrNull { it.size >= 2 && it[0] == "k" }
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                if (parentKind in COUNTED_NIP22_PARENT_KINDS) {
                    val parentId = event.replyToId
                        ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.getOrNull(1)
                    if (parentId != event.id) invalidateRemovedReplyTarget(parentId, dirty)
                }
            }
            6, 16 -> {
                val targetId = repostTargetId(event) ?: return
                decrementCounter(repostCounts, targetId)
                removeActionEventId(repostEventIdsByActorTarget, event.pubkey, targetId, event.id)
                val hasMore = repostEventIdsByActorTarget[actionKey(event.pubkey, targetId)]?.isNotEmpty() == true
                if (!hasMore) {
                    repostPubkeysByTarget[targetId]?.remove(event.pubkey)
                    if (repostPubkeysByTarget[targetId]?.isEmpty() == true) repostPubkeysByTarget.remove(targetId)
                    removeFromActorIndex(repostedTargetsByActor, event.pubkey, targetId)
                }
                invalidateStatsForTarget(targetId, dirty)
                dirty.action = true
            }
            7 -> {
                val targetId = reactionTargetId(event) ?: return
                val reactionContent = parseReactionContent(event.content.ifBlank { "+" }, event.tags)
                if (reactionContent != ReactionContent.Standard("-")) {
                    reactionsByTarget[targetId]?.remove(ReactionInfo(event.pubkey, reactionContent))
                    if (reactionsByTarget[targetId]?.isEmpty() == true) reactionsByTarget.remove(targetId)
                    removeActionEventId(reactionEventIdsByActorTarget, event.pubkey, targetId, event.id)
                    val hasMore = reactionEventIdsByActorTarget[actionKey(event.pubkey, targetId)]?.isNotEmpty() == true
                    if (!hasMore) removeFromActorIndex(reactedTargetsByActor, event.pubkey, targetId)
                    invalidateStatsForTarget(targetId, dirty)
                    dirty.action = true
                }
            }
            9734 -> {
                val targetId = event.rootId ?: return
                removeFromActorIndex(zappedTargetsByActor, event.pubkey, targetId)
                dirty.action = true
            }
        }
    }

    private fun invalidateReplyTarget(targetId: String?, dirty: InsertDirty) {
        if (targetId == null) return
        statsUpdatedAt[targetId] = System.currentTimeMillis()
        dirty.invalidatedStatsIds.add(targetId)
    }

    private fun invalidateRemovedReplyTarget(targetId: String?, dirty: InsertDirty) {
        invalidateReplyTarget(targetId, dirty)
        dirty.stats = true
    }

    private fun decrementCounter(index: ConcurrentHashMap<String, Int>, key: String) {
        index.computeIfPresent(key) { _, value ->
            val next = value - 1
            if (next > 0) next else null
        }
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
        // Gated: three CHM .size reads (plus a sort when trimming) on every
        // add is wasteful — check every 64th add. Worst-case overshoot past
        // ACTOR_INDEX_CAP between checks is 64 actors.
        if (actorIndexAddsSinceTrimCheck.incrementAndGet() >= 64) {
            actorIndexAddsSinceTrimCheck.set(0)
            trimActorIndexesIfNeeded()
        }
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

    private fun handleZapReceipt(event: NostrEvent, dirty: InsertDirty) {
        // e-tag target; for a zap to an addressable event (article) there may be no
        // e-tag — fall back to the a/A coordinate (zapStats merges the coord key).
        val targetId = (event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }
            ?: event.tags.firstOrNull { it.size >= 2 && (it[0] == "a" || it[0] == "A") })
            ?.get(1) ?: return

        val sats = extractSatsFromZap(event)
        // Parse embedded kind-9734 zap request for sender pubkey + comment.
        val desc = parseZapDescription(event)

        // Patch-as-own: a private zap WE sent is anon-signed, so this receipt's
        // embedded sender is the anon pubkey, not ours. If it matches a pending
        // own-anon mapping, promote the stored sender → own so the existing
        // (sender, sats) dedup collapses it against the optimistic own row. This is
        // sender-local only — the receipt on the wire / for other clients stays
        // anonymous; we never publish or expose the anon→own link.
        val own = ownPubkey
        val rawSender = desc?.senderPubkey
        val promotedToOwn = rawSender != null && own != null && rawSender != own && consumeOwnAnonZap(rawSender)
        val effectiveSender = if (promotedToOwn) own else rawSender

        // Detail row FIRST and idempotent by receipt id: the per-zap rows are the
        // durable source of truth (zapStats derives from them, and the restore
        // repair pass rebuilds them from receipt events). A receipt re-seen across
        // relays, or replayed by repair, must not add a duplicate row or double-count.
        val list = zapDetailsByTarget
            .computeIfAbsent(targetId) { java.util.Collections.synchronizedList(mutableListOf()) }
        val added = synchronized(list) {
            if (list.any { it.eventId == event.id }) false
            else { list.add(ZapDetail(effectiveSender, sats, desc?.comment, eventId = event.id)); true }
        }
        if (!added) return  // duplicate receipt — already counted

        zapStatsByEventId.compute(targetId) { _, existing ->
            val current = existing ?: ZapAggregate.EMPTY
            ZapAggregate(current.count + 1, current.totalSats + sats)
        }
        statsUpdatedAt[targetId] = System.currentTimeMillis()
        invalidateStatsForTarget(targetId, dirty)

        // Own-zap detection: signal VM to clear optimistic sats overlay. Uses the
        // effective sender, so a promoted private zap (anon→own) clears the overlay
        // just like a public own zap. effectiveSender == null → anonymous, never ours.
        // When the receipt targets an article COORDINATE, also emit the resolved
        // article id — the optimistic overlay was placed on the event id, so clearing
        // only the coord would leave a duplicate. Emit both.
        if (own != null && effectiveSender == own) {
            _ownZapReceived.tryEmit(targetId)
            if (':' in targetId) articleIdByCoord[targetId]?.let { _ownZapReceived.tryEmit(it) }
        }

        // NIP-57 private zap detection. The embedded kind-9734's anon tag carries
        // an encrypted blob (NIP-04 wire format from Quartz's PrivateZapRequestBuilder
        // or PrivateZapEncryption). Decrypting with our key reveals the inner kind-9733
        // containing the real sender + real message. We only attempt decrypt for
        // receipts addressed to our own pubkey — private zaps for others can't be
        // decrypted by us.
        if (own != null) {
            val recipientP = event.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
            if (recipientP == own) {
                val (anonCt, anonSigner) = parseAnonTagAndSigner(event)
                if (anonCt != null && anonSigner != null &&
                    !privateZapDecryptedById.containsKey(event.id)) {
                    _pendingPrivateZapDecrypts.tryEmit(
                        PendingPrivateZapDecrypt(event.id, anonCt, anonSigner)
                    )
                }
            }
        }
    }

    private data class ZapDescription(val senderPubkey: String, val comment: String?)

    /** Parse the kind-9734 zap request embedded in a kind-9735 receipt's description tag. */
    private fun parseZapDescription(event: NostrEvent): ZapDescription? {
        val descJson = event.tags
            .firstOrNull { it.size >= 2 && it[0] == "description" }
            ?.get(1) ?: return null
        return try {
            val obj = NostrJson.parseToJsonElement(descJson).jsonObject
            val pubkey = obj["pubkey"]?.jsonPrimitive?.content
                ?.takeIf { it.length == 64 } ?: return null
            val comment = obj["content"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ZapDescription(pubkey, comment)
        } catch (_: Exception) { null }
    }

    /**
     * Extract the encrypted ciphertext from the kind-9734's anon tag, along
     * with the kind-9734 signer's pubkey (publicKey_a) — needed as the
     * peerPubkey for decrypt. The wire format is NIP-04, but SigningManager.decrypt
     * tries NIP-44 first as a defensive fallback for legacy senders.
     *
     * Returns (null, null) if no anon tag, no description tag, or malformed JSON.
     */
    private fun parseAnonTagAndSigner(event: NostrEvent): Pair<String?, String?> {
        val descJson = event.tags
            .firstOrNull { it.size >= 2 && it[0] == "description" }
            ?.get(1) ?: return null to null
        return try {
            val obj = NostrJson.parseToJsonElement(descJson).jsonObject
            val signerPubkey = obj["pubkey"]?.jsonPrimitive?.content ?: return null to null
            val tagsArr = obj["tags"]?.jsonArray ?: return null to null
            val anonTag = tagsArr.firstOrNull { el ->
                val arr = el as? JsonArray ?: return@firstOrNull false
                arr.size >= 2 && arr[0].jsonPrimitive.content == "anon"
            } as? JsonArray ?: return null to null
            val ct = anonTag[1].jsonPrimitive.content.takeIf { it.isNotBlank() }
                ?: return null to null
            ct to signerPubkey
        } catch (_: Exception) {
            null to null
        }
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
        if (numStr.length > 18) return 0L

        val amount = numStr.toLongOrNull() ?: return 0L
        val multiplier = afterPrefix.getOrNull(numStr.length)

        // BTC multipliers → sats (1 BTC = 100_000_000 sats)
        return try {
            when (multiplier) {
                'm' -> Math.multiplyExact(amount, 100_000L)       // milli-BTC
                'u' -> Math.multiplyExact(amount, 100L)           // micro-BTC
                'n' -> amount / 10                                // nano-BTC (1 nBTC = 0.1 sat)
                'p' -> amount / 10_000                            // pico-BTC
                else -> Math.multiplyExact(amount, 100_000_000L)  // BTC
            }
        } catch (_: ArithmeticException) {
            0L
        }
    }

    private fun handleMuteList(event: NostrEvent) {
        // Newest-known kind-10000 createdAt per pubkey (replaceable dedup index,
        // shared with the other replaceable handlers). Read the prior value for
        // the guard below, then merge this event's createdAt for every
        // non-local-echo event, mirroring the eventsById scan this replaces.
        val dedupKey = "${event.pubkey}:10000"
        val isOwn = event.pubkey == ownPubkey

        // An echo is not the transaction's commit point: only an explicit relay
        // OK is. Do not advance the authoritative base here, otherwise an echo
        // could hide a concurrent event from the commit-time CAS below.
        if (isOwn && isSelfPublishedCheck(event.id)) {
            return
        }

        val newestKnown = relayKindCreatedAt[dedupKey]
        relayKindCreatedAt.merge(dedupKey, event.createdAt) { a, b -> maxOf(a, b) }

        // Replaceable event guard: skip if a newer kind-10000 for this pubkey
        // already exists in MES. Prevents older relay echoes from clobbering
        // the newest mute list (especially the async Amber decrypt callback).
        // Strictly-older only: an event equal in createdAt to the newest known
        // (including this event re-processing itself) passes, matching the
        // old eventsById scan's `it.id != event.id && it.createdAt > ...`.
        val newerExists = newestKnown != null && event.createdAt < newestKnown
        if (newerExists) return

        // Parse public tags from this event
        val pubkeys = mutableSetOf<String>()
        val hashtags = mutableSetOf<String>()
        val words = mutableSetOf<String>()
        val eventIds = mutableSetOf<String>()
        for (tag in event.tags) {
            if (tag.size < 2) continue
            when (tag[0]) {
                "p" -> pubkeys.add(tag[1])
                "t" -> hashtags.add(tag[1].lowercase())
                "word" -> words.add(tag[1].lowercase())
                "e" -> eventIds.add(tag[1])
            }
        }

        // For nsec mode ONLY: decrypt inline (Amber needs async decrypt via callback)
        var inlinePrivPubkeys: Set<String>? = null
        var inlinePrivHashtags: Set<String>? = null
        var inlinePrivWords: Set<String>? = null
        var inlinePrivEventIds: Set<String>? = null
        if (isOwn && event.content.isEmpty()) {
            // Empty content is an authoritative empty private half, not a
            // temporary decrypt failure. Carrying the previous private fields
            // here would resurrect mutes another client deliberately cleared
            // the next time unSilence republishes the list.
            inlinePrivPubkeys = emptySet()
            inlinePrivHashtags = emptySet()
            inlinePrivWords = emptySet()
            inlinePrivEventIds = emptySet()
        } else if (isOwn && !keyProvider.isAmberMode) {
            val decryptedTags = decryptMuteContent(event.content, event.pubkey)
            if (decryptedTags != null) {
                val pp = mutableSetOf<String>()
                val ph = mutableSetOf<String>()
                val pw = mutableSetOf<String>()
                val pe = mutableSetOf<String>()
                for (tag in decryptedTags) {
                    if (tag.size < 2) continue
                    when (tag[0]) {
                        "p" -> pp.add(tag[1])
                        "t" -> ph.add(tag[1].lowercase())
                        "word" -> pw.add(tag[1].lowercase())
                        "e" -> pe.add(tag[1])
                    }
                }
                inlinePrivPubkeys = pp
                inlinePrivHashtags = ph
                inlinePrivWords = pw
                inlinePrivEventIds = pe
                Log.i("MES", "MuteList: decrypted ${pp.size}p ${ph.size}t ${pw.size}word ${pe.size}e private entries")
            }
        }

        val authoritative = MuteList(
            pubkeys = pubkeys,
            hashtags = hashtags,
            words = words,
            eventIds = eventIds,
            privatePubkeys = inlinePrivPubkeys ?: muteListsByPubkey[event.pubkey]?.privatePubkeys ?: emptySet(),
            privateHashtags = inlinePrivHashtags ?: muteListsByPubkey[event.pubkey]?.privateHashtags ?: emptySet(),
            privateWords = inlinePrivWords ?: muteListsByPubkey[event.pubkey]?.privateWords ?: emptySet(),
            privateEventIds = inlinePrivEventIds ?: muteListsByPubkey[event.pubkey]?.privateEventIds ?: emptySet(),
        )
        val materialized = if (isOwn) {
            pendingMutePublishesByPubkey[event.pubkey]?.applyTo(authoritative) ?: authoritative
        } else {
            authoritative
        }
        muteListsByPubkey[event.pubkey] = materialized
        latestMuteEventIdByPubkey[event.pubkey] = event.id
        if (isOwn) _muteListSignal.value = System.nanoTime()

        // Verify the exact accepted event before publishing can reopen. This runs
        // for nsec and Amber, and for empty content, so a late EOSE/event can
        // recover an initially unsafe bootstrap without a fixed delay.
        if (isOwn) {
            ownMuteListEventCallback?.invoke(event)
        }
    }

    /**
     * Decrypt kind-10000 .content (private mute tags encrypted to self).
     * Tries NIP-44 first, falls back to NIP-04 for legacy clients.
     * Returns parsed tag arrays, or null on failure.
     */
    private fun decryptMuteContent(content: String, pubkeyHex: String): List<List<String>>? {
        val privKeyHex = keyProvider.getPrivateKeyHex()
        if (privKeyHex == null) {
            Log.i("MES", "MuteList: Amber mode — skipping private mute decrypt")
            return null
        }
        val privKeyBytes = privKeyHex.hexToByteArray()
        val pubKeyBytes = pubkeyHex.hexToByteArray()

        // Try NIP-44 first (modern clients)
        val plaintext: String? = runCatching {
            Nip44.decrypt(content, privKeyBytes, pubKeyBytes)
        }.getOrNull() ?: runCatching {
            // Fallback to NIP-04 (legacy clients like older Amethyst)
            Nip04.decrypt(content, privKeyBytes, pubKeyBytes)
        }.getOrNull()

        if (plaintext == null) {
            Log.w("MES", "MuteList: failed to decrypt private entries (NIP-44 + NIP-04)")
            return null
        }

        return runCatching {
            val arr = NostrJson.parseToJsonElement(plaintext) as? JsonArray
                ?: return null
            arr.map { tagArr ->
                (tagArr as JsonArray).map { it.jsonPrimitive.content }
            }
        }.getOrElse { e ->
            Log.w("MES", "MuteList: failed to parse decrypted tags: ${e.message}")
            null
        }
    }

    private fun handleRelayList(event: NostrEvent, dirty: InsertDirty? = null) {
        val key = "${event.pubkey}:10002"
        val existingTs = relayKindCreatedAt[key]
        if (!shouldAcceptProfileRelayEvent(existingTs, event.createdAt)) return

        val configs = parseNip65RelayTags(event.tags)
        val readRelays = configs.filter { it.marker != "write" }.map(RelayConfig::url)
        val writeRelays = configs.filter { it.marker != "read" }.map(RelayConfig::url)

        relayKindCreatedAt[key] = event.createdAt
        relayListsByPubkey[event.pubkey] = RelayList(readRelays, writeRelays)

        val existingConfigs = readWriteRelayConfigsByPubkey[event.pubkey]
        if (existingConfigs != configs) {
            readWriteRelayConfigsByPubkey[event.pubkey] = configs
            if (dirty != null) dirty.relayConfig = true
            else _relayConfigSignal.value = System.nanoTime()
        }
    }

    private fun handleBlocked(event: NostrEvent, dirty: InsertDirty? = null) {
        val key = "${event.pubkey}:10006"
        val existingTs = relayKindCreatedAt[key]
        if (!shouldAcceptProfileRelayEvent(existingTs, event.createdAt)) return

        val urls = parseNip51RelayTags(event.tags)

        relayKindCreatedAt[key] = event.createdAt
        val existing = blockedRelaysByPubkey[event.pubkey]
        if (existing != urls) {
            blockedRelaysByPubkey[event.pubkey] = urls
            if (dirty != null) dirty.relayConfig = true
            else _relayConfigSignal.value = System.nanoTime()
        }
    }

    private fun handleSearchRelays(event: NostrEvent, dirty: InsertDirty? = null) {
        val key = "${event.pubkey}:10007"
        val existingTs = relayKindCreatedAt[key]
        if (!shouldAcceptProfileRelayEvent(existingTs, event.createdAt)) return

        val urls = parseNip51RelayTags(event.tags)

        relayKindCreatedAt[key] = event.createdAt
        val existing = searchRelaysByPubkey[event.pubkey]
        if (existing != urls) {
            searchRelaysByPubkey[event.pubkey] = urls
            if (dirty != null) dirty.relayConfig = true
            else _relayConfigSignal.value = System.nanoTime()
        }
    }

    private fun handleFavorites(event: NostrEvent, dirty: InsertDirty? = null) {
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
            if (dirty != null) dirty.relayConfig = true
            else _relayConfigSignal.value = System.nanoTime()
        }
    }

    // ─── Kind 30382: NIP-85 user-level WoT assertions ─────────────────────

    private fun handleWotAssertion(event: NostrEvent, dirty: InsertDirty? = null): Boolean {
        val provider = normalizeHexPubkey(event.pubkey) ?: return false

        fun tag(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
            event.tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
        }

        val subject = normalizeHexPubkey(tag("d")) ?: return false
        val rank = tag("rank")?.toIntOrNull()?.takeIf { it in 0..100 } ?: return false

        var changed = false
        synchronized(wotProviderLock) {
            if (provider != activeWotProviderPubkey) return false

            wotQueriedSubjects.add(subject)
            wotAccessedAt[subject] = System.nanoTime()

            wotBySubject.compute(subject) { _, existing ->
                if (existing != null && existing.updatedAt >= event.createdAt) {
                    existing
                } else {
                    changed = true
                    WotAssertionEntity(
                        subjectPubkey = subject,
                        providerPubkey = provider,
                        rank = rank,
                        hops = tag("hops", "dos")?.toIntOrNull(),
                        influence = tag(
                            "personalizedGrapeRank_influence",
                            "influence",
                            "personalized_grapeRank",
                        )?.toDoubleOrNull(),
                        average = tag("personalizedGrapeRank_average")?.toDoubleOrNull()
                            ?: tag("average")?.toDoubleOrNull(),
                        confidence = tag("personalizedGrapeRank_confidence")?.toDoubleOrNull()
                            ?: tag("confidence")?.toDoubleOrNull(),
                        input = tag("personalizedGrapeRank_input")?.toDoubleOrNull()
                            ?: tag("input")?.toDoubleOrNull(),
                        pageRank = tag(
                            "personalizedPageRank",
                            "pageRank",
                            "personalized_pageRank",
                        )?.toDoubleOrNull(),
                        verifiedFollowers = tag("verifiedFollowerCount")?.toLongOrNull()
                            ?: tag("followers")?.toLongOrNull(),
                        verifiedMuters = tag("verifiedMuterCount")?.toLongOrNull(),
                        verifiedReporters = tag("verifiedReporterCount")?.toLongOrNull(),
                        updatedAt = event.createdAt,
                    )
                }
            }
            if (changed) trimWotAssertionsIfNeeded()
        }

        if (changed) {
            if (dirty != null) dirty.wot = true
            else _wotSignal.value = System.nanoTime()
        }
        return changed
    }

    // ─── Kind 10040: NIP-85 provider registry ─────────────────────────────

    private fun handleWotProviderRegistry(event: NostrEvent, dirty: InsertDirty? = null): Boolean {
        val own = ownPubkey ?: return false
        if (event.pubkey != own) {
            Log.i("MES", "WoT 10040 ignored foreign author=${event.pubkey.take(8)} own=${own.take(8)}")
            return false
        }

        var changed = false
        if (event.createdAt >= ownWotProviderEncryptedUpdatedAt) {
            val encrypted = event.content.takeIf { it.isNotBlank() }
            if (ownWotProviderEncryptedContent != encrypted) changed = true
            ownWotProviderEncryptedContent = encrypted
            ownWotProviderEncryptedUpdatedAt = event.createdAt
        }

        val descriptor = wotProviderDescriptorFromTags(event.tags, event.createdAt)
        val existing = ownWotProviderRegistry
        if (descriptor != null) {
            if (existing == null || existing.updatedAt < event.createdAt) {
                ownWotProviderRegistry = descriptor
                Log.i(
                    "MES",
                    "WoT 10040 accepted provider=${descriptor.providerPubkey.take(8)} relay=${descriptor.relayHint} createdAt=${event.createdAt}",
                )
                changed = true
            }
        } else if (existing != null && event.createdAt > existing.updatedAt) {
            ownWotProviderRegistry = null
            Log.i("MES", "WoT 10040 cleared provider from newer registry without public rank row")
            changed = true
        } else if (event.tags.isNotEmpty()) {
            Log.w("MES", "WoT 10040 has no usable 30382:rank row tags=${event.tags.mapNotNull { it.firstOrNull() }.take(8)}")
        }

        if (changed) {
            if (dirty != null) dirty.wot = true
            else _wotSignal.value = System.nanoTime()
        }
        return changed
    }

    private fun isTrustScoreProvider(pubkey: String): Boolean =
        normalizeHexPubkey(pubkey) == TRUST_SCORE_PROVIDER_PUBKEY

    private fun normalizeHexPubkey(pubkey: String?): String? {
        val normalized = pubkey?.trim()?.lowercase() ?: return null
        if (normalized.length != 64) return null
        return normalized.takeIf { value -> value.all { it in '0'..'9' || it in 'a'..'f' } }
    }

    private fun trimWotAssertionsIfNeeded() {
        if (wotAccessedAt.size <= WOT_ASSERTION_CAP && wotBySubject.size <= WOT_ASSERTION_CAP) return
        val toRemove = wotAccessedAt.entries
            .sortedBy { it.value }
            .take(WOT_ASSERTION_TRIM)
            .map { it.key }
        for (subject in toRemove) {
            wotAccessedAt.remove(subject)
            wotBySubject.remove(subject)
            wotQueriedSubjects.remove(subject)
        }
    }

    // ─── Kind 30385: Trusted Relay Assertions ─────────────────────────────

    private fun handleTrustScore(event: NostrEvent, dirty: InsertDirty? = null) {
        if (!isTrustScoreProvider(event.pubkey)) return

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
        if (dirty != null) dirty.trustScore = true
        else _trustScoreSignal.value = System.nanoTime()
    }

    // ─── Kind 10063: Blossom server list (NIP-B7 / BUD-03) ────────────────

    private fun handleBlossomServers(event: NostrEvent) {
        val key = "${event.pubkey}:10063"
        val existingTs = relayKindCreatedAt[key]
        if (existingTs != null && existingTs >= event.createdAt) return

        val servers = event.tags
            .filter { it.size >= 2 && it[0] == "server" }
            .map { it[1] }
            .filter { it.startsWith("https://") || it.startsWith("http://") }

        relayKindCreatedAt[key] = event.createdAt
        blossomServersByPubkey[event.pubkey] = servers
    }

    /** Ordered blossom server URLs for [pubkey], or empty if no kind-10063 seen. */
    fun blossomServersFor(pubkey: String): List<String> =
        blossomServersByPubkey[pubkey] ?: emptyList()

    // ─── Kind 30030: NIP-30 Emoji Set ────────────────────────────────────

    private fun handleEmojiSet(event: NostrEvent, dirty: InsertDirty? = null) {
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return
        val dedupKey = "${event.pubkey}:30030:$dTag"
        val existingTs = emojiKindCreatedAt[dedupKey]
        if (existingTs != null && existingTs >= event.createdAt) return

        val title = event.tags.firstOrNull {
            it.size >= 2 && it[0] == "title"
        }?.get(1)?.takeIf { it.isNotBlank() }

        val emojis = event.tags.mapNotNull { tag ->
            if (tag.size < 3 || tag[0] != "emoji") return@mapNotNull null
            val shortcode = tag[1].takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = tag[2].takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CustomEmoji(shortcode, url)
        }
        if (emojis.isEmpty()) return

        emojiKindCreatedAt[dedupKey] = event.createdAt
        emojiSetsByCoordinate[event.pubkey to dTag] = EmojiSetEntity(
            authorPubkey = event.pubkey,
            setName = dTag,
            title = title,
            emojis = emojis,
            updatedAt = event.createdAt,
        )
        if (dirty != null) dirty.emojiSet = true
        else _emojiSetSignal.value = System.nanoTime()
    }

    // ─── Kind 10030: NIP-30 User Emoji List ──────────────────────────────

    private fun handleUserEmojiList(event: NostrEvent, dirty: InsertDirty? = null) {
        val dedupKey = "${event.pubkey}:10030"
        val existingTs = emojiKindCreatedAt[dedupKey]
        if (existingTs != null && existingTs >= event.createdAt) return

        val setRefs = mutableListOf<EmojiSetRef>()
        val inlineEmojis = mutableListOf<CustomEmoji>()

        for (tag in event.tags) {
            if (tag.size < 2) continue
            when (tag[0]) {
                "a" -> {
                    val parts = tag[1].split(":")
                    if (parts.size != 3 || parts[0] != "30030") continue
                    val authorPubkey = parts[1].lowercase()
                    val setName = parts[2]
                    val hintRelay = tag.getOrNull(2)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { normalizeRelayUrl(it) }
                    setRefs.add(EmojiSetRef(authorPubkey, setName, hintRelay))
                }
                "emoji" -> {
                    if (tag.size < 3) continue
                    val shortcode = tag[1].takeIf { it.isNotBlank() } ?: continue
                    val url = tag[2].takeIf { it.isNotBlank() } ?: continue
                    inlineEmojis.add(CustomEmoji(shortcode, url))
                }
            }
        }

        emojiKindCreatedAt[dedupKey] = event.createdAt
        userEmojiListByPubkey[event.pubkey] = UserEmojiListEntity(
            pubkey = event.pubkey,
            setRefs = setRefs,
            inlineEmojis = inlineEmojis,
            updatedAt = event.createdAt,
        )
        if (dirty != null) dirty.emojiSet = true
        else _emojiSetSignal.value = System.nanoTime()
    }

    // ─── NIP-30 query APIs ───────────────────────────────────────────────

    fun getEmojiSet(authorPubkey: String, setName: String): EmojiSetEntity? =
        emojiSetsByCoordinate[authorPubkey to setName]

    fun getUserEmojiList(pubkey: String): UserEmojiListEntity? =
        userEmojiListByPubkey[pubkey]

    /** All emoji available to [pubkey]: inline + all subscribed sets, deduped by shortcode. */
    fun resolvedEmojisFor(pubkey: String): List<CustomEmoji> {
        val list = userEmojiListByPubkey[pubkey] ?: return emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<CustomEmoji>()
        for (e in list.inlineEmojis) {
            if (seen.add(e.shortcode)) out.add(e)
        }
        for (ref in list.setRefs) {
            val set = emojiSetsByCoordinate[ref.authorPubkey to ref.setName] ?: continue
            for (e in set.emojis) {
                if (seen.add(e.shortcode)) out.add(e)
            }
        }
        return out
    }

    /** All known emoji sets (for discover surface). Snapshot-safe: values copied. */
    fun allEmojiSets(): List<EmojiSetEntity> =
        emojiSetsByCoordinate.values.toList()

    fun allEmojiSetsFlow(): Flow<List<EmojiSetEntity>> =
        _emojiSetSignal.map { allEmojiSets() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /** Emojis grouped by set name — inline first, then subscribed sets in order. */
    fun resolvedEmojisBySet(pubkey: String): List<Pair<String, List<CustomEmoji>>> {
        val list = userEmojiListByPubkey[pubkey] ?: return emptyList()
        val result = mutableListOf<Pair<String, List<CustomEmoji>>>()
        if (list.inlineEmojis.isNotEmpty()) {
            result.add("Inline" to list.inlineEmojis)
        }
        for (ref in list.setRefs) {
            val set = emojiSetsByCoordinate[ref.authorPubkey to ref.setName] ?: continue
            val title = set.title ?: set.setName
            if (set.emojis.isNotEmpty()) result.add(title to set.emojis)
        }
        return result
    }

    fun resolvedEmojisBySetFlow(pubkey: String): Flow<List<Pair<String, List<CustomEmoji>>>> =
        _emojiSetSignal
            .map { resolvedEmojisBySet(pubkey) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    fun resolvedEmojisFlow(pubkey: String): Flow<List<CustomEmoji>> =
        _emojiSetSignal
            .map { resolvedEmojisFor(pubkey) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // ─── Kind 30166: NIP-66 Relay Monitor (liveness / RTT) ───────────────

    /** @return true iff the monitor was novel or newer than the existing entry. */
    // LEGACY-NIP66: partial 30166 parser feeding the health UI (dots/ping/getRelayHealth
    // read relayMonitorsByUrl). Phase 1 of the Relay Directory adds a fuller, verified
    // firehose parser (RelayDirectory.parseMonitorEvent). Convergence plan: Phase 2 makes
    // the directory the SINGLE 30166 parse path and health reads from it — then delete this.
    private fun handleRelayMonitor(event: NostrEvent, dirty: InsertDirty? = null): Boolean {
        fun tag(name: String): String? = event.tags.firstOrNull {
            it.size >= 2 && it[0] == name
        }?.get(1)

        val rawUrl = tag("d") ?: return false
        val relayUrl = normalizeRelayUrl(rawUrl) ?: return false

        val rttOpen = tag("rtt-open")?.toIntOrNull()
        val rttRead = tag("rtt-read")?.toIntOrNull()
        val rttWrite = tag("rtt-write")?.toIntOrNull()

        val supportedNips = event.tags
            .filter { it.size >= 2 && it[0] == "N" }
            .mapNotNull { it[1].toIntOrNull() }

        // Network tag is `n` ("clearnet"/"tor"), NOT "network" — the latter has been silently
        // null since shipping (real monitors publish `n`). Fixed here per directory audit (A4).
        val network = tag("n")
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

        var changed = false
        relayMonitorsByUrl.compute(relayUrl) { _, existing ->
            if (existing != null && existing.createdAt >= event.createdAt) existing
            else {
                changed = true
                RelayMonitorEntity(
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
        }
        if (changed) {
            if (dirty != null) dirty.relayMonitor = true
            else _relayMonitorSignal.value = System.nanoTime()
        }
        return changed
    }

    private fun handleRelaySetMaterialized(event: NostrEvent, dirty: InsertDirty? = null) {
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
        val relayTags = event.tags
            .filter { it.size >= 2 && it[0] == "relay" }
            .map { it.toList() }
        val members = relayTags
            .map { it[1] }
            .distinct()
        val modeledTags = setOf("d", "title", "description", "image", "relay")
        val foreignTags = event.tags
            .filter { it.firstOrNull() !in modeledTags }
            .map { it.toList() }

        val newSet = RelaySet(
            dTag = dTag,
            ownerPubkey = event.pubkey,
            title = title,
            description = description,
            image = image,
            members = members,
            relayTags = relayTags,
            foreignTags = foreignTags,
        )

        relayKindCreatedAt[dedupKey] = event.createdAt
        val existing = relaySetsByCoordinate[coordKey]
        if (existing != newSet) {
            relaySetsByCoordinate[coordKey] = newSet
            if (dirty != null) dirty.relaySet = true
            else _relaySetSignal.value = System.nanoTime()
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
        contentAdmissionIndex.remove(event.id, event.kind)
        deindexNotificationRecipients(event)
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
    private fun evictOldContentEvents(kindCaps: Map<Int, Int> = CONTENT_EVENT_KIND_CAPS) {
        val ownPubkeyAnchor = ownPubkey
        val viewed = viewedPubkey
        val followedPubkeysSnapshot =
            ownPubkeyAnchor?.let { followsByPubkey[it]?.toSet() }.orEmpty()
        val profileAnchoredIdsSnapshot = profileAnchoredIds.toSet()
        // One immutable union per pass. Never consult TimelineService from a
        // candidate comparator: its refs change as subscriptions deliver.
        val timelineReferencedIds = timelineServiceProvider.get().liveReferencedIds()

        // Own-mention lookup built once from the notification recipient index —
        // replaces a per-candidate tag scan for notification kinds (1/6/7/9735).
        // Kinds outside NOTIFICATION_KINDS aren't indexed and keep the tag scan.
        val ownMentionIds: Set<String> = if (ownPubkeyAnchor != null) {
            notifIdsByRecipient[ownPubkeyAnchor]?.mapTo(HashSet()) { it.eventId } ?: emptySet()
        } else {
            emptySet()
        }

        // Pass 1: bucket events by kind. Anchored events are excluded entirely —
        // they don't count against the cap and can't be evicted.
        val toEvict = mutableListOf<ContentEvictionCandidate>()
        val candidatesByKind = mutableMapOf<Int, MutableList<EventEntry>>()
        var anchoredOwn = 0
        var anchoredMentioned = 0
        var anchoredViewed = 0
        var anchoredProfileRefs = 0

        for (entry in recentByCreatedAt) {
            val event = eventsById[entry.id] ?: continue
            val kind = event.kind
            if (kind !in kindCaps) continue

            // Band A: own pubkey — never evicted, never counted
            if (ownPubkeyAnchor != null && event.pubkey == ownPubkeyAnchor) {
                anchoredOwn++
                continue
            }
            // Band A: events mentioning own pubkey (notifications)
            if (ownPubkeyAnchor != null) {
                val mentionsOwn = if (kind in NOTIFICATION_KINDS) {
                    entry.id in ownMentionIds
                } else {
                    event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == ownPubkeyAnchor }
                }
                if (mentionsOwn) {
                    anchoredMentioned++
                    continue
                }
            }
            // Band A: events authored by currently viewed profile
            if (viewed != null && event.pubkey == viewed) {
                anchoredViewed++
                continue
            }
            // Band A: ref events anchored by own-profile pipeline (quoted notes,
            // repost targets, thread parents of own-authored events)
            if (entry.id in profileAnchoredIdsSnapshot) {
                anchoredProfileRefs++
                continue
            }

            candidatesByKind.getOrPut(kind) { mutableListOf() }.add(entry)
        }

        // Pass 2: for each kind over its cap, materialize all mutable policy
        // inputs, then choose ordinary LRU first, timeline refs second, and
        // followed-author content only when those pools are exhausted.
        //
        // CRITICAL: snapshot lastTouchedAt values before sorting. The map is
        // concurrently mutated by the hot drainer (insertCore), CardHydrator
        // (lookupEvent), and other insert paths. Reading from it inside the
        // sort comparator triggers TimSort's contract violation check:
        //   IllegalArgumentException: Comparison method violates its general contract!
        // See CLAUDE.md rule #24.
        for ((kind, candidates) in candidatesByKind) {
            val cap = kindCaps[kind] ?: continue
            if (candidates.size <= cap) continue
            val touchSnapshot = HashMap<String, Long>(candidates.size)
            val authorSnapshot = HashMap<String, String>(candidates.size)
            for (candidate in candidates) {
                touchSnapshot[candidate.id] = lastTouchedAt[candidate.id] ?: 0L
                eventsById[candidate.id]?.pubkey?.let { authorSnapshot[candidate.id] = it }
            }
            toEvict += selectContentEvictionCandidates(
                entries = candidates,
                cap = cap,
                authorsByEventId = authorSnapshot,
                followedPubkeys = followedPubkeysSnapshot,
                timelineReferencedIds = timelineReferencedIds,
                lastTouchedAt = touchSnapshot,
            )
        }

        val invalidatedReplyTargets = mutableSetOf<String>()
        val removedIds = HashSet<String>(toEvict.size)
        var evictedTier1 = 0
        var evictedTier2 = 0
        var evictedTier3 = 0
        val evictedByKind = mutableMapOf<Int, Int>()
        for (candidate in toEvict) {
            val event = removeContentEventForEviction(
                eventId = candidate.entry.id,
                invalidatedReplyTargets = invalidatedReplyTargets,
            ) ?: continue
            removedIds.add(event.id)
            when (candidate.tier) {
                ContentEvictionTier.FOLLOWED_AUTHOR -> evictedTier1++
                ContentEvictionTier.TIMELINE_REFERENCED -> evictedTier2++
                ContentEvictionTier.ORDINARY -> evictedTier3++
            }
            evictedByKind[event.kind] = (evictedByKind[event.kind] ?: 0) + 1
        }

        publishEvictionInvalidations(invalidatedReplyTargets, removedIds)

        recordEvictionPass(
            evictedTier1 = evictedTier1,
            evictedTier2 = evictedTier2,
            evictedTier3 = evictedTier3,
            evictedByKind = evictedByKind,
            anchoredOwn = anchoredOwn,
            anchoredMentioned = anchoredMentioned,
            anchoredViewed = anchoredViewed,
            anchoredProfileRefs = anchoredProfileRefs,
            liveTimelineRefs = timelineReferencedIds.size,
        )
        Log.d(
            "MES",
            "Eviction: ${removedIds.size} removed " +
                "tier1=$evictedTier1 tier2=$evictedTier2 tier3=$evictedTier3; " +
                "byKind=${evictedByKind.toSortedMap()} " +
                "anchored own=$anchoredOwn mentioned=$anchoredMentioned " +
                "viewed=$anchoredViewed profileRefs=$anchoredProfileRefs " +
                "liveTimelineRefs=${timelineReferencedIds.size}",
        )
    }

    private fun removeContentEventForEviction(
        eventId: String,
        invalidatedReplyTargets: MutableSet<String>,
    ): NostrEvent? {
        val kind = eventsById[eventId]?.kind ?: return null
        val lock = contentMutationLocks[kind]
        return if (lock == null) {
            removeContentEventForEvictionUnlocked(eventId, invalidatedReplyTargets)
        } else {
            synchronized(lock) {
                removeContentEventForEvictionUnlocked(eventId, invalidatedReplyTargets)
            }
        }
    }

    private fun removeContentEventForEvictionUnlocked(
        eventId: String,
        invalidatedReplyTargets: MutableSet<String>,
    ): NostrEvent? {
        val event = eventsById.remove(eventId) ?: return null
        contentAdmissionIndex.remove(event.id, event.kind)
        deindexNotificationRecipients(event)
        recentByCreatedAt.remove(EventEntry(event.id, event.createdAt))
        idsByKind[event.kind]?.remove(event.id)
        idsByPubkey[event.pubkey]?.remove(event.id)
        lastTouchedAt.remove(event.id)
        forEachReplyIndexTarget(event) { targetId ->
            if (removeReplyIndexEntry(targetId, event.id)) {
                invalidatedReplyTargets += targetId
            }
        }
        // Article comment index: drop this id from each coord it referenced.
        if (event.kind == 1 || event.kind == 1111) {
            for (tag in event.tags) {
                if (tag.size >= 2 && (tag[0] == "a" || tag[0] == "A")) {
                    val coord = tag[1]
                    if (commentIdsByCoord[coord]?.remove(event.id) == true) {
                        invalidatedReplyTargets += coord
                        articleIdByCoord[coord]?.let(invalidatedReplyTargets::add)
                    }
                }
            }
        }
        // Addressable coordinate maps must not retain an ID whose payload was
        // removed at admission; otherwise readers observe a permanent dead ref.
        articleCoordById.remove(event.id)?.let { coord ->
            articleIdByCoord.remove(coord, event.id)
        }
        if (event.kind in 30000..39999) {
            val dTag = event.tags
                .firstOrNull { it.size >= 2 && it[0] == "d" }
                ?.getOrNull(1)
                .orEmpty()
            replaceableByCoordinate.remove("${event.pubkey}:${event.kind}:$dTag", event.id)
        }
        feedRowCache.remove(event.id)
        feedRowAccessedAt.remove(event.id)
        videoRenderModelsByEventId.remove(event.id)
        imetaImageDimsByEventId.remove(event.id)
        eventModelsByEventId.remove(event.id)
        repostCounts.remove(event.id)
        zapStatsByEventId.remove(event.id)
        statsUpdatedAt.remove(event.id)
        repostPubkeysByTarget.remove(event.id)
        reactionsByTarget.remove(event.id)
        zapDetailsByTarget.remove(event.id)
        return event
    }

    private fun publishEvictionInvalidations(
        invalidatedReplyTargets: MutableSet<String>,
        removedIds: Set<String>,
    ) {
        invalidatedReplyTargets.removeAll(removedIds)
        if (invalidatedReplyTargets.isEmpty()) return
        val updatedAt = System.currentTimeMillis()
        invalidatedReplyTargets.forEach { statsUpdatedAt[it] = updatedAt }
        _statsInvalidations.tryEmit(StatsInvalidation.Targeted(invalidatedReplyTargets))
    }

    /** Rebuild access order after snapshot records arrive in persistence order. */
    private fun rebuildContentAdmissionIndex() {
        val candidates = eventsById.values
            .asSequence()
            .filter { it.kind in CONTENT_EVENT_KIND_CAPS }
            .map { event -> Triple(event, contentAdmissionTier(event), lastTouchedAt[event.id] ?: 0L) }
            .sortedWith(compareBy<Triple<NostrEvent, ContentEvictionTier?, Long>> { it.third }.thenBy { it.first.id })
            .toList()
        contentAdmissionIndex.clear()
        for ((event, tier) in candidates) {
            contentAdmissionIndex.track(event.id, event.kind, tier)
        }
    }

    private fun recordEvictionPass(
        evictedTier1: Int,
        evictedTier2: Int,
        evictedTier3: Int,
        evictedByKind: Map<Int, Int>,
        anchoredOwn: Int,
        anchoredMentioned: Int,
        anchoredViewed: Int,
        anchoredProfileRefs: Int,
        liveTimelineRefs: Int,
    ) {
        evictionPasses.incrementAndGet()
        evictionEvicted.addAndGet((evictedTier1 + evictedTier2 + evictedTier3).toLong())
        evictionTier1.addAndGet(evictedTier1.toLong())
        evictionTier2.addAndGet(evictedTier2.toLong())
        evictionTier3.addAndGet(evictedTier3.toLong())
        for ((kind, count) in evictedByKind) {
            evictionByKind.computeIfAbsent(kind) { AtomicLong(0) }.addAndGet(count.toLong())
        }
        lastEvictionAnchors = EvictionAnchorSnapshot(
            own = anchoredOwn.toLong(),
            mentioned = anchoredMentioned.toLong(),
            viewed = anchoredViewed.toLong(),
            profileRefs = anchoredProfileRefs.toLong(),
            liveTimelineRefs = liveTimelineRefs.toLong(),
        )
    }

    private fun recordAdmissionReplacement(tier: ContentEvictionTier, kind: Int) {
        evictionAdmissionReplaced.incrementAndGet()
        evictionEvicted.incrementAndGet()
        when (tier) {
            ContentEvictionTier.FOLLOWED_AUTHOR -> evictionTier1.incrementAndGet()
            ContentEvictionTier.TIMELINE_REFERENCED -> evictionTier2.incrementAndGet()
            ContentEvictionTier.ORDINARY -> evictionTier3.incrementAndGet()
        }
        evictionByKind.computeIfAbsent(kind) { AtomicLong(0) }.incrementAndGet()
    }

    private fun recordAdmissionRejected(kind: Int) {
        evictionAdmissionRejected.incrementAndGet()
        evictionAdmissionRejectedByKind
            .computeIfAbsent(kind) { AtomicLong(0) }
            .incrementAndGet()
    }

    private fun resetEvictionMetrics() {
        evictionPasses.set(0)
        evictionEvicted.set(0)
        evictionTier1.set(0)
        evictionTier2.set(0)
        evictionTier3.set(0)
        evictionAdmissionReplaced.set(0)
        evictionAdmissionRejected.set(0)
        evictionByKind.clear()
        evictionAdmissionRejectedByKind.clear()
        lastEvictionAnchors = EvictionAnchorSnapshot()
    }

    /** Small-cap entry point for deterministic policy integration tests. */
    internal fun evictOldContentEventsForTest(kindCaps: Map<Int, Int>) {
        evictOldContentEvents(kindCaps)
    }

    // ─── Query API ──────────────────────────────────────────────────────────

    fun feedEvents(filter: FeedQuery, limit: Int = 300): List<NostrEvent> {
        val result = mutableListOf<NostrEvent>()
        for (entry in recentByCreatedAt) {
            if (result.size >= limit) break
            val event = eventsById[entry.id] ?: continue
            if (event.kind !in filter.kinds) continue
            if (filter.followedPubkeys != null && event.pubkey !in filter.followedPubkeys) continue
            // Content filter: 1 = notes only, 2 = replies only.
            // kind-6 AND kind-16 reposts carry a rootId (the reposted event) and
            // must NOT be treated as replies — admit them to notes, exclude from replies.
            val isRepostKind = event.kind == 6 || event.kind == 16
            if (filter.contentFilter == 1 && !isRepostKind) {
                if (event.replyToId != null || event.rootId != null) continue
            }
            if (filter.contentFilter == 2) {
                if ((event.replyToId == null && event.rootId == null) || isRepostKind) continue
            }
            // Relay URL scoping — null or empty means no relay filter (all relays pass)
            if (!filter.relayUrls.isNullOrEmpty() && event.relaysSeen.none { it in filter.relayUrls }) continue
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

    /** Latest createdAt for any event by [pubkey] in the given [kinds], or null if MES has none. */
    fun latestEventTimestampForAuthor(pubkey: String, kinds: Set<Int>): Long? {
        val ids = idsByPubkey[pubkey] ?: return null
        var latest: Long? = null
        for (id in ids) {
            val event = eventsById[id] ?: continue
            if (event.kind !in kinds) continue
            if (latest == null || event.createdAt > latest) latest = event.createdAt
        }
        return latest
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

    /** Events by author set, sorted by createdAt desc. Used for Following feed cache hydration. */
    fun eventsByAuthors(authors: Set<String>, kinds: Set<Int>, limit: Int = 300): List<NostrEvent> {
        if (authors.isEmpty()) return emptyList()
        val result = mutableListOf<NostrEvent>()
        for (entry in recentByCreatedAt) {
            if (result.size >= limit) break
            val event = eventsById[entry.id] ?: continue
            if (event.kind !in kinds) continue
            if (event.pubkey !in authors) continue
            result.add(event)
        }
        return result
    }

    /** Recent events across all authors, sorted by createdAt desc. Used for Global feed cache hydration. */
    fun recentEvents(kinds: Set<Int>, limit: Int = 300): List<NostrEvent> {
        val result = mutableListOf<NostrEvent>()
        for (entry in recentByCreatedAt) {
            if (result.size >= limit) break
            val event = eventsById[entry.id] ?: continue
            if (event.kind !in kinds) continue
            result.add(event)
        }
        return result
    }

    /**
     * Recent events for Global, scanning recentByCreatedAt until [floor] *displayable*
     * roots are collected OR [scanCap] events examined OR the index is exhausted.
     * Returns the full scanned slice (roots + interleaved replies, muted included) so
     * the NOTES↔REPLIES toggle and mute-reactivity in feedRows still work.
     *
     * "Displayable root" = (kind-6 OR no reply/root markers) AND isDisplayable(event).
     * isDisplayable is injected by the caller (mute + sensitive-if-HIDE) so MES holds
     * no view state. Zero network: reads only events already in MES.
     */
    fun recentEventsWithDisplayableFloor(
        kinds: Set<Int>,
        isDisplayable: (NostrEvent) -> Boolean,
        floor: Int = 100,
        scanCap: Int = 1000,
    ): List<NostrEvent> {
        val result = mutableListOf<NostrEvent>()
        var displayable = 0
        var scanned = 0
        var hitFloor = false
        var hitCap = false
        for (entry in recentByCreatedAt) {
            if (displayable >= floor) { hitFloor = true; break }
            if (scanned >= scanCap) { hitCap = true; break }
            val event = eventsById[entry.id] ?: continue
            if (event.kind !in kinds) continue
            scanned++
            result.add(event)
            val isRoot = event.kind == 6 || event.kind == 16 || (event.replyToId == null && event.rootId == null)
            if (isRoot && isDisplayable(event)) displayable++
        }
        val bound = when { hitFloor -> "floor"; hitCap -> "cap"; else -> "exhausted" }
        Log.d("MES", "displayableFloor: ${result.size} events ($displayable displayable, $scanned scanned, " +
            "$bound-bound, displayable-ratio ${if (scanned > 0) displayable * 100 / scanned else 0}%)")
        return result
    }

    /** Events seen on a specific relay, sorted by createdAt desc. Used for SingleRelay feed cache hydration. */
    fun eventsByRelay(relayUrl: String, kinds: Set<Int>, limit: Int = 300): List<NostrEvent> {
        val result = mutableListOf<NostrEvent>()
        for (entry in recentByCreatedAt) {
            if (result.size >= limit) break
            val event = eventsById[entry.id] ?: continue
            if (event.kind !in kinds) continue
            if (relayUrl !in event.relaysSeen) continue
            result.add(event)
        }
        return result
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
    fun getFollows(pubkey: String): Set<String>? {
        val follows = followsByPubkey[pubkey]
        if (follows != null) recordFollowsAccess(pubkey, System.currentTimeMillis())
        return follows
    }
    fun getFollowsCreatedAt(pubkey: String): Long? = followsCreatedAt[pubkey]

    /**
     * Returns a set+version pair under the same per-pubkey lock used by all live
     * kind-3 writes. A null result means unresolved; an empty set is known-empty.
     */
    internal fun getFollowsSnapshot(pubkey: String): FollowsSnapshot? {
        var snapshot: FollowsSnapshot? = null
        followsCreatedAt.compute(pubkey) { _, createdAt ->
            followsByPubkey[pubkey]?.let { follows ->
                snapshot = FollowsSnapshot(
                    follows = follows.toSet(),
                    createdAt = createdAt,
                    retainedContactList = if (pubkey == ownPubkey) {
                        ownContactListEvent?.copyForContactListRetention()
                    } else {
                        null
                    },
                )
            }
            createdAt
        }
        if (snapshot != null) recordFollowsAccess(pubkey, System.currentTimeMillis())
        return snapshot
    }

    /**
     * A non-empty owner list may be republished only when its materialized set
     * and retained raw kind-3 are the same replaceable-event revision. Legacy
     * snapshots contain only the set; treating that state as publishable would
     * silently strip relay hints, petnames, non-p tags, and content on the first
     * follow after upgrade. Known-empty remains a valid new-account state.
     */
    internal fun getPublishableFollowsSnapshot(pubkey: String): FollowsSnapshot? {
        val snapshot = getFollowsSnapshot(pubkey) ?: return null
        if (pubkey != ownPubkey || snapshot.follows.isEmpty()) return snapshot

        val retained = snapshot.retainedContactList ?: return null
        val retainedFollows = retained.tags
            .asSequence()
            .filter { it.size >= 2 && it[0] == "p" }
            .map { it[1] }
            .toSet()
        return snapshot.takeIf {
            retained.createdAt == snapshot.createdAt && retainedFollows == snapshot.follows
        }
    }

    /** Test/persistence view; callers cannot mutate the retained provenance set. */
    internal fun getOwnContactListEvent(): NostrEvent? =
        ownContactListEvent?.copyForContactListRetention()

    fun followersOf(pubkey: String): Set<String> = followsByPubkey.entries
        .asSequence()
        .filter { (_, follows) -> pubkey in follows }
        .mapTo(linkedSetOf()) { it.key }

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
            eventsById[id]?.let { contentAdmissionIndex.touch(id, it.kind) }
        }
    }

    /** Convenience overload for single id. */
    fun markTouched(eventId: String) {
        lastTouchedAt[eventId] = System.currentTimeMillis()
        eventsById[eventId]?.let { contentAdmissionIndex.touch(eventId, it.kind) }
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
        updateFollowsInternal(pubkey, followedPubkeys, createdAt, dirty = null)
    }

    /**
     * Raw kind-3 direct path. The derived set is retained for every author, but
     * the full event is retained only when it belongs to the signed-in account.
     */
    internal fun updateFollows(event: NostrEvent): Int {
        if (event.kind != 3) return 0
        val followedPubkeys = event.tags
            .asSequence()
            .filter { it.size >= 2 && it[0] == "p" }
            .map { it[1] }
            .toSet()
        updateFollowsInternal(
            pubkey = event.pubkey,
            followedPubkeys = followedPubkeys,
            createdAt = event.createdAt,
            dirty = null,
            retainedEvent = event,
        )
        return followedPubkeys.size
    }

    /**
     * Applies a locally signed kind-3 only if the exact state captured before
     * signing is still current. This is an atomic post-sign re-read + write.
     */
    internal fun applyOptimisticFollows(
        pubkey: String,
        previous: FollowsSnapshot,
        updatedFollows: Set<String>,
        updatedCreatedAt: Long,
    ): Boolean = compareAndSetFollows(
        pubkey = pubkey,
        expectedFollows = previous.follows,
        expectedCreatedAt = previous.createdAt,
        expectedContactListEventId = previous.retainedContactList?.id,
        updatedFollows = updatedFollows,
        updatedCreatedAt = updatedCreatedAt,
    )

    /**
     * Restores the state that preceded an unacknowledged optimistic publish.
     * Unlike [updateFollows], this deliberately permits moving createdAt backward,
     * but only while our exact optimistic set+version is still current. A newer
     * relay event therefore wins and can never be overwritten by a late rollback.
     */
    internal fun revertOptimisticFollows(
        pubkey: String,
        optimisticFollows: Set<String>,
        optimisticCreatedAt: Long,
        previous: FollowsSnapshot,
    ): Boolean = compareAndSetFollows(
        pubkey = pubkey,
        expectedFollows = optimisticFollows,
        expectedCreatedAt = optimisticCreatedAt,
        expectedContactListEventId = previous.retainedContactList?.id,
        updatedFollows = previous.follows,
        updatedCreatedAt = previous.createdAt,
    )

    /**
     * Commits metadata from a locally signed kind-3 only after a relay accepts
     * it, and only while its optimistic set+version is still authoritative.
     */
    internal fun retainAcceptedOwnContactList(event: NostrEvent): Boolean {
        val owner = ownPubkey
        if (event.kind != 3 || owner == null || event.pubkey != owner) return false
        val eventFollows = event.tags
            .asSequence()
            .filter { it.size >= 2 && it[0] == "p" }
            .map { it[1] }
            .toSet()
        var retained = false
        followsCreatedAt.compute(owner) { _, currentCreatedAt ->
            if (currentCreatedAt == event.createdAt && followsByPubkey[owner] == eventFollows) {
                ownContactListEvent = event.copyForContactListRetention()
                retained = true
            }
            currentCreatedAt
        }
        return retained
    }

    private fun compareAndSetFollows(
        pubkey: String,
        expectedFollows: Set<String>,
        expectedCreatedAt: Long?,
        expectedContactListEventId: String?,
        updatedFollows: Set<String>,
        updatedCreatedAt: Long?,
    ): Boolean {
        var applied = false
        followsCreatedAt.compute(pubkey) { _, currentCreatedAt ->
            val currentFollows = followsByPubkey[pubkey]
            val currentContactListEventId =
                if (pubkey == ownPubkey) ownContactListEvent?.id else null
            if (currentCreatedAt != expectedCreatedAt ||
                currentFollows != expectedFollows ||
                currentContactListEventId != expectedContactListEventId
            ) {
                return@compute currentCreatedAt
            }
            followsByPubkey[pubkey] = updatedFollows.toSet()
            recordFollowsAccess(pubkey, System.currentTimeMillis())
            applied = true
            updatedCreatedAt
        }
        if (applied) _followsSignal.value = System.nanoTime()
        return applied
    }

    private fun updateFollowsInternal(
        pubkey: String,
        followedPubkeys: Set<String>,
        createdAt: Long,
        dirty: InsertDirty?,
        retainedEvent: NostrEvent? = null,
    ): Boolean {
        var accepted = false
        followsCreatedAt.compute(pubkey) { _, existingTs ->
            if (existingTs != null && existingTs > createdAt) {
                Log.d("MES", "updateFollows: stale for ${pubkey.take(8)}… (existing=$existingTs > new=$createdAt)")
                return@compute existingTs // stale — ignore
            }
            val existing = followsByPubkey[pubkey]
            val changed = existing == null || existing != followedPubkeys
            followsByPubkey[pubkey] = followedPubkeys
            recordFollowsAccess(pubkey, System.currentTimeMillis())
            if (retainedEvent?.kind == 3 && retainedEvent.pubkey == ownPubkey) {
                ownContactListEvent = retainedEvent.copyForContactListRetention()
            }
            accepted = true
            if (changed) {
                if (dirty != null) dirty.follows = true
                else _followsSignal.value = System.nanoTime()
            }
            Log.d("MES", "updateFollows: ${pubkey.take(8)}… → ${followedPubkeys.size} follows (createdAt=$createdAt, changed=$changed)")
            createdAt
        }
        return accepted
    }

    private fun NostrEvent.copyForContactListRetention(): NostrEvent = copy(
        tags = tags.map { it.toList() },
        relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { addAll(this@copyForContactListRetention.relaysSeen) },
    )
    fun getRelayList(pubkey: String): RelayList? = relayListsByPubkey[pubkey]
    fun getMuteList(pubkey: String): MuteList? = muteListsByPubkey[pubkey]

    /**
     * Flow that emits the current user's MuteList whenever it changes.
     * Driven by _muteListSignal, bumped in handleMuteList and updateMuteListPrivateTags.
     */
    fun ownMuteListFlow(): Flow<MuteList?> =
        _muteListSignal.map { muteListsByPubkey[ownPubkey] }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /**
     * Update the private (decrypted) portion of an existing MuteList.
     * Called from bootstrap after Amber/signer-based decrypt completes.
     */
    /**
     * Update ONLY private fields. Called by async decryptor when Amber returns plaintext.
     * Preserves all public fields untouched.
     */
    fun updateMuteListPrivateTags(
        pubkey: String,
        privatePubkeys: Set<String>,
        privateHashtags: Set<String>,
        privateWords: Set<String>,
        privateEventIds: Set<String>,
    ) {
        updateMuteListPrivateTagsIfCurrent(
            eventId = null,
            pubkey = pubkey,
            privatePubkeys = privatePubkeys,
            privateHashtags = privateHashtags,
            privateWords = privateWords,
            privateEventIds = privateEventIds,
        )
    }

    /** Apply decrypted private tags only if [eventId] is still the accepted
     * replaceable event, then reapply any durable local mutation journal. */
    internal fun updateMuteListPrivateTagsIfCurrent(
        eventId: String?,
        pubkey: String,
        privatePubkeys: Set<String>,
        privateHashtags: Set<String>,
        privateWords: Set<String>,
        privateEventIds: Set<String>,
    ): Boolean {
        synchronized(muteStateLock) {
            if (eventId != null && latestMuteEventIdByPubkey[pubkey] != eventId) return false
            val existing = muteListsByPubkey[pubkey]
            val decrypted = if (existing == null) {
                MuteList(
                    pubkeys = emptySet(), hashtags = emptySet(),
                    words = emptySet(), eventIds = emptySet(),
                    privatePubkeys = privatePubkeys,
                    privateHashtags = privateHashtags,
                    privateWords = privateWords,
                    privateEventIds = privateEventIds,
                )
            } else {
                existing.copy(
                    privatePubkeys = privatePubkeys,
                    privateHashtags = privateHashtags,
                    privateWords = privateWords,
                    privateEventIds = privateEventIds,
                )
            }
            muteListsByPubkey[pubkey] =
                pendingMutePublishesByPubkey[pubkey]?.applyTo(decrypted) ?: decrypted
        }
        Log.i("MES", "MuteList private update: ${privatePubkeys.size}p ${privateHashtags.size}t ${privateWords.size}word ${privateEventIds.size}e | owner=${pubkey.take(8)}…")
        if (pubkey == ownPubkey) _muteListSignal.value = System.nanoTime()
        return true
    }

    /**
     * Find the kind-10000 event content for a pubkey (for external decrypt).
     * Returns content from the NEWEST kind-10000 event for that pubkey.
     */
    fun getMuteListContent(pubkey: String): String? = getLatestMuteListEvent(pubkey)?.content

    /** Latest accepted kind-10000, including an intentionally empty content field. */
    fun getLatestMuteListEvent(pubkey: String): NostrEvent? = synchronized(muteStateLock) {
        latestMuteEventIdByPubkey[pubkey]?.let(eventsById::get)
    }

    fun ownMuteListEventFlow(): Flow<NostrEvent?> =
        _muteListSignal.map { ownPubkey?.let(::getLatestMuteListEvent) }
            .distinctUntilChangedBy { it?.id }
            .flowOn(Dispatchers.Default)

    internal fun isCurrentMuteListEvent(pubkey: String, eventId: String): Boolean =
        synchronized(muteStateLock) { latestMuteEventIdByPubkey[pubkey] == eventId }

    /**
     * Execute a short base-state decision under the same lock used by kind-10000
     * insertion. This closes the check-then-open race where a newer relay event
     * could close the publish gate between an identity check and the gate write,
     * only for the stale check to reopen it afterward.
     */
    internal fun <T> inspectMuteListBaseAtomically(
        pubkey: String,
        block: (currentEventId: String?) -> T,
    ): T = synchronized(muteStateLock) {
        block(latestMuteEventIdByPubkey[pubkey])
    }

    /** True if [eventId] is the newest kind-10000 for [pubkey] in eventsById. */
    fun isNewestMuteEvent(eventId: String, pubkey: String): Boolean {
        val target = eventsById[eventId] ?: return false
        // Newest-known createdAt from the replaceable dedup index (maintained
        // at the top of handleMuteList for every inserted kind-10000). Equal
        // createdAt means no STRICTLY newer event exists — same semantics as
        // the old `none { it.id != eventId && it.createdAt > target.createdAt }`.
        val newestKnown = relayKindCreatedAt["$pubkey:10000"] ?: return true
        return target.createdAt >= newestKnown
    }

    /**
     * Apply one local edit and durably journal its intent. No optimistic createdAt
     * floor is installed here: while publishing is unsafe, a complete older relay
     * list must still be allowed to arrive and become the base under this overlay.
     */
    internal fun recordPendingMuteMutation(mutation: MuteMutation): PendingMutePublish? {
        val ownPk = ownPubkey ?: return null
        val updated = synchronized(muteStateLock) {
            val previous = pendingMutePublishesByPubkey[ownPk]
                ?: PendingMutePublish(ownerPubkey = ownPk, revision = 0L)
            val next = previous.withMutation(mutation)
            if (next == previous) return null
            if (next.changeCount > PENDING_MUTE_CHANGE_CAP) return null
            pendingMutePublishesByPubkey[ownPk] = next
            muteListsByPubkey[ownPk] = next.applyTo(muteListsByPubkey[ownPk] ?: emptyMuteList())
            next
        }
        _muteListSignal.value = System.nanoTime()
        return updated
    }

    // Compatibility wrappers keep one mutation implementation for every caller.
    fun addPrivateMute(targetPubkey: String) {
        recordPendingMuteMutation(MuteMutation(MuteMutationKind.User, targetPubkey, muted = true))
    }

    fun addPrivateWord(word: String) {
        recordPendingMuteMutation(MuteMutation(MuteMutationKind.Word, word, muted = true))
    }

    fun removePrivateWord(word: String) {
        recordPendingMuteMutation(MuteMutation(MuteMutationKind.Word, word, muted = false))
    }

    fun addPrivateHashtag(tag: String) {
        recordPendingMuteMutation(MuteMutation(MuteMutationKind.Hashtag, tag, muted = true))
    }

    fun removePrivateHashtag(tag: String) {
        recordPendingMuteMutation(MuteMutation(MuteMutationKind.Hashtag, tag, muted = false))
    }

    fun removePrivateMute(targetPubkey: String) {
        recordPendingMuteMutation(MuteMutation(MuteMutationKind.User, targetPubkey, muted = false))
    }

    internal fun getPendingMutePublish(pubkey: String): PendingMutePublish? =
        synchronized(muteStateLock) { pendingMutePublishesByPubkey[pubkey] }

    /** Preserve valid in-process mute intent if a corrupt snapshot restore must
     * clear partially materialized MES state. */
    internal fun pendingMutePublishesSnapshot(): List<PendingMutePublish> =
        synchronized(muteStateLock) { pendingMutePublishesByPubkey.values.toList() }

    internal fun restorePendingMutePublishesAfterReset(
        pendingPublishes: Collection<PendingMutePublish>,
    ) {
        val owner = ownPubkey ?: return
        val pending = pendingPublishes.firstOrNull { it.ownerPubkey == owner } ?: return
        synchronized(muteStateLock) {
            val live = pendingMutePublishesByPubkey[owner]
            val merged = if (live == null) pending else mergePendingMutePublishes(pending, live)
            pendingMutePublishesByPubkey[owner] = merged
            muteListsByPubkey[owner] = merged.applyTo(muteListsByPubkey[owner] ?: emptyMuteList())
        }
        _muteListSignal.value = System.nanoTime()
    }

    internal fun getMutePublishSnapshot(pubkey: String): MutePublishSnapshot? =
        synchronized(muteStateLock) {
            val pending = pendingMutePublishesByPubkey[pubkey] ?: return@synchronized null
            MutePublishSnapshot(
                pending = pending,
                muteList = muteListsByPubkey[pubkey] ?: pending.applyTo(emptyMuteList()),
                baseEventId = latestMuteEventIdByPubkey[pubkey],
                baseCreatedAt = relayKindCreatedAt["$pubkey:10000"],
            )
        }

    /** CAS the exact journal/base captured before an external signing round-trip. */
    internal fun beginMutePublish(snapshot: MutePublishSnapshot): Boolean =
        synchronized(muteStateLock) {
            val pubkey = snapshot.pending.ownerPubkey
            if (pendingMutePublishesByPubkey[pubkey] != snapshot.pending) return@synchronized false
            if (muteListsByPubkey[pubkey] != snapshot.muteList) return@synchronized false
            if (latestMuteEventIdByPubkey[pubkey] != snapshot.baseEventId) return@synchronized false
            if (relayKindCreatedAt["$pubkey:10000"] != snapshot.baseCreatedAt) return@synchronized false
            true
        }

    /**
     * Relay acceptance is the commit point. Store the signed event before
     * clearing the journal; the binary writer snapshots the journal before its
     * event selection, making every concurrent save ordering recoverable.
     */
    internal fun commitAcceptedMutePublish(
        snapshot: MutePublishSnapshot,
        event: NostrEvent,
    ): Boolean {
        val committed = synchronized(muteStateLock) {
            val pending = pendingMutePublishesByPubkey[event.pubkey] ?: return@synchronized false
            if (pending != snapshot.pending) return@synchronized false

            val key = "${event.pubkey}:10000"
            // The relay base may change during the network round-trip. Preserve
            // the journal and retry against that new base instead of clearing it
            // after a stale event happened to receive an OK.
            if (latestMuteEventIdByPubkey[event.pubkey] != snapshot.baseEventId) {
                return@synchronized false
            }
            if (relayKindCreatedAt[key] != snapshot.baseCreatedAt) {
                return@synchronized false
            }
            val newestCreatedAt = relayKindCreatedAt[key]
            val newestEventId = latestMuteEventIdByPubkey[event.pubkey]
            val superseded = newestCreatedAt != null && (
                newestCreatedAt > event.createdAt ||
                    (newestCreatedAt == event.createdAt &&
                        newestEventId != null && newestEventId != event.id)
                )
            if (superseded) return@synchronized false

            // Re-entrant monitor: storeLocalEvent itself does not run handlers.
            storeLocalEvent(event)
            relayKindCreatedAt.merge(key, event.createdAt) { a, b -> maxOf(a, b) }
            latestMuteEventIdByPubkey[event.pubkey] = event.id
            pendingMutePublishesByPubkey.remove(event.pubkey, pending)
            true
        }
        if (committed) _muteListSignal.value = System.nanoTime()
        return committed
    }

    /**
     * Store a locally-signed event in eventsById without triggering kind handlers.
     * Used after relay acceptance so the committed kind-10000 reaches the next
     * snapshot even when its relay echo has not arrived yet.
     *
     * When the relay echo arrives, [insertCore] sees the existing event via
     * putIfAbsent and merges relaysSeen only (no double-processing).
     */
    fun storeLocalEvent(event: NostrEvent) {
        val existing = eventsById.putIfAbsent(event.id, event)
        if (existing != null) return // already stored
        idsByKind.getOrPut(event.kind) { ConcurrentHashMap.newKeySet() }.add(event.id)
        idsByPubkey.getOrPut(event.pubkey) { ConcurrentHashMap.newKeySet() }.add(event.id)
    }

    // ─── O(1) stat reads ────────────────────────────────────────────────────

    fun replyCount(eventId: String): Int {
        val coord = articleCoordForEvent(eventId)
        // Count the EXACT same unique, live reply rows the corresponding thread can
        // render. The persisted scalar is only a legacy invalidation aid: payload
        // eviction + later refetch can increment it repeatedly for the same event ID.
        return if (coord != null) {
            articleCommentIds(coord).size
        } else {
            replyEventIdsForTarget(eventId).count()
        }
    }

    /**
     * Unique live replies whose insertion semantics contribute to [replyCount].
     * idsByReplyTarget also contains repost/zap/video events because those kinds
     * parse NIP-10 e-tags, so using the raw set size would count engagement as replies.
     */
    private fun replyEventIdsForTarget(targetId: String): Sequence<String> =
        idsByReplyTarget[targetId]
            .orEmpty()
            .asSequence()
            .filter { id ->
                if (id == targetId) return@filter false
                val event = eventsById[id] ?: return@filter false
                when (event.kind) {
                    1 -> event.replyToId == targetId || event.rootId == targetId
                    1111 -> {
                        val parentKind = event.tags
                            .firstOrNull { it.size >= 2 && it[0] == "k" }
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                        val parentId = event.replyToId
                            ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.getOrNull(1)
                        parentKind in COUNTED_NIP22_PARENT_KINDS && parentId == targetId
                    }
                    else -> false
                }
            }

    /** Keep the reply index symmetric even for legacy kind-1111 rows whose
     * parsed parent field is absent but whose lowercase e-tag is authoritative. */
    private inline fun forEachReplyIndexTarget(
        event: NostrEvent,
        action: (String) -> Unit,
    ) {
        val direct = event.replyToId ?: if (event.kind == 1111) {
            event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.getOrNull(1)
        } else {
            null
        }
        direct?.takeIf { it != event.id }?.let(action)
        event.rootId
            ?.takeIf { it != event.id && it != direct }
            ?.let(action)
    }

    private fun removeReplyIndexEntry(targetId: String, eventId: String): Boolean {
        val ids = idsByReplyTarget[targetId] ?: return false
        // Do not prune an empty set here: a concurrent inserter can already hold
        // this set reference and add after a check-then-remove, orphaning its ID.
        return ids.remove(eventId)
    }
    fun repostCount(eventId: String): Int {
        val pubkeys = repostPubkeysForEvent(eventId)
        if (pubkeys.isNotEmpty()) return pubkeys.size
        val coord = articleCoordForEvent(eventId)
        val direct = repostCounts[eventId] ?: 0
        val viaCoord = if (coord != null) repostCounts[coord] ?: 0 else 0
        return direct + viaCoord
    }
    /**
     * Displayed reaction count for [eventId]: distinct (pubkey, content) entries,
     * excluding NIP-25 "-" dislikes. Equals the total avatars the engagement
     * drawer renders (grouped by emoji) — same source, by construction.
     * Per-entry, NOT per-reactor: one pubkey reacting with two emoji counts as 2.
     */
    fun reactionCount(eventId: String): Int {
        val coord = articleCoordForEvent(eventId)
        val direct = reactionsByTarget[eventId]
        val viaCoord = if (coord != null) reactionsByTarget[coord] else null
        return when {
            viaCoord == null -> direct?.size ?: 0
            direct == null   -> viaCoord.size
            else             -> (direct + viaCoord).size   // Set union dedups
        }
    }

    /** For supported addressable content, its `kind:pubkey:d` coordinate. */
    fun articleCoordForEvent(eventId: String): String? =
        articleCoordById[eventId]
            ?: eventsById[eventId]
                ?.takeIf { it.kind == 30023 || it.kind == 34235 || it.kind == 34236 }
                ?.let { e ->
                    val d = e.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1).orEmpty()
                    "${e.kind}:${e.pubkey}:$d"
                }

    /** Register an addressable content id⇄coordinate mapping and invalidate its stats. */
    fun registerArticleCoord(eventId: String, coord: String) {
        val novel = articleCoordById.put(eventId, coord) != coord
        articleIdByCoord[coord] = eventId
        // A coordinate-only NIP-22 comment can now project its parent event id.
        // Drop any rows built before the addressable target arrived.
        commentIdsByCoord[coord]?.forEach(feedRowCache::remove)
        if (novel) {
            statsUpdatedAt[eventId] = maxOf(
                statsUpdatedAt[eventId] ?: 0L,
                statsUpdatedAt[coord] ?: System.currentTimeMillis(),
            )
            _statsInvalidations.tryEmit(StatsInvalidation.Targeted(setOf(eventId)))
        }
    }

    /** Invalidate a target plus the addressable event id its coordinate resolves to. */
    private fun invalidateStatsForTarget(targetId: String, dirty: InsertDirty) {
        dirty.invalidatedStatsIds.add(targetId)
        if (':' in targetId) articleIdByCoord[targetId]?.let { dirty.invalidatedStatsIds.add(it) }
    }

    /**
     * Index a kind-1111 event under the addressable content coordinate it is
     * ROOTED at, not one it merely quotes or mentions.
     *
     * - kind-1111 (NIP-22): the article is the comment's root iff an uppercase `A`
     *   tag matches the coordinate. (Quotes inside a comment use `q`, not `A`.)
     * - kind-1 (legacy): a lowercase `a` to the coord, but only when the event is
     *   not a quote (`q` tag) and isn't actually a reply to a different event.
     */
    private fun indexArticleComment(event: NostrEvent, dirty: InsertDirty?) {
        // Only NIP-22 kind-1111 is coordinate-indexed here, by its uppercase `A`
        // root scope. Legacy kind-1 article comments are NOT indexed by tag — they
        // flow through the normal reply index (idsByReplyTarget),
        // keyed by the article EVENT id, which inherently counts only genuine replies
        // and excludes quote/mention posts (they reply elsewhere). Indexing legacy
        // kind-1 here too would double-count it (handleNote + this index).
        if (event.kind != 1111) return
        val coord = event.tags.firstOrNull {
            it.size >= 2 && it[0] == "A" && (
                it[1].startsWith("30023:") ||
                    it[1].startsWith("34235:") ||
                    it[1].startsWith("34236:")
                )
        }?.get(1) ?: return
        commentIdsByCoord.getOrPut(coord) { ConcurrentHashMap.newKeySet() }.add(event.id)
        dirty?.let { invalidateStatsForTarget(coord, it) }
    }

    /** Comments (FeedRow) for an article coordinate, oldest-first (chronological
     *  comment-section ordering, NOT the newest-first main feed), id tie-break.
     *  Bounded by [ARTICLE_COMMENT_CAP]. */
    fun articleCommentsFlow(coord: String): Flow<List<FeedRow>> =
        _feedSignal
            .map { articleComments(coord) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /** The article FeedRow for a coordinate (`30023:pubkey:d`), or null if not in
     *  MES. Resolves via the coord⇄id index — used to render a quoted/embedded
     *  article as the canonical card. */
    fun articleRowByCoord(coord: String): FeedRow? =
        articleIdByCoord[coord]?.let { feedRowsByIds(setOf(it)).firstOrNull() }

    /** Reactive version — re-emits when the referenced article arrives. */
    fun articleRowByCoordFlow(coord: String): Flow<FeedRow?> =
        _feedSignal
            .map { articleRowByCoord(coord) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /** Latest locally-known event id for an addressable coordinate. */
    fun eventIdForAddress(kind: Int, pubkey: String, dTag: String): String? =
        idsByPubkey[pubkey]
            .orEmpty()
            .asSequence()
            .mapNotNull(eventsById::get)
            .filter { event ->
                event.kind == kind && event.tags.any { tag ->
                    tag.size >= 2 && tag[0] == "d" && tag[1] == dTag
                }
            }
            .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
            ?.id

    fun eventIdForAddressFlow(kind: Int, pubkey: String, dTag: String): Flow<String?> =
        _feedSignal
            .map { eventIdForAddress(kind, pubkey, dTag) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /**
     * The source of truth for coordinate-rooted comments and descendants.
     * NIP-22 kind-1111 (uppercase `A`, filtered to kind 1111 so stale legacy
     * coord-index entries can't leak) + genuine kind-1 replies to the article event
     * (idsByReplyTarget, excluding quote-posts).
     */
    private fun articleCommentIds(coord: String): Set<String> {
        val ids = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        // Seed with DIRECT article comments (the false-attribution guard lives here —
        // never an arbitrary #a mention).
        commentIdsByCoord[coord]?.forEach { id ->                 // NIP-22 kind-1111 (uppercase A)
            if (eventsById[id]?.kind == 1111 && ids.add(id)) queue.add(id)
        }
        articleIdByCoord[coord]?.let { articleId ->               // legacy kind-1 replies to the article event
            idsByReplyTarget[articleId]?.forEach { id ->
                val e = eventsById[id]
                if (e?.kind == 1 && e.tags.none { it.size >= 2 && it[0] == "q" } && ids.add(id)) queue.add(id)
            }
        }
        // BFS-expand descendants (replies to comments) through idsByReplyTarget — a
        // reply to an accepted comment is an article-comment descendant even if it
        // carries no #a/#A tag. Bounded by ARTICLE_COMMENT_CAP.
        while (queue.isNotEmpty() && ids.size < ARTICLE_COMMENT_CAP) {
            val parent = queue.removeFirst()
            idsByReplyTarget[parent]?.forEach { childId ->
                val c = eventsById[childId] ?: return@forEach
                val ok = c.kind == 1111 || (c.kind == 1 && c.tags.none { it.size >= 2 && it[0] == "q" })
                if (ok && ids.add(childId)) queue.add(childId)
            }
        }
        return ids
    }

    private fun articleComments(coord: String): List<FeedRow> =
        articleCommentIds(coord)
            .mapNotNull { eventsById[it] }
            .sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
            .take(ARTICLE_COMMENT_CAP)
            .map { toFeedRow(it) }
    fun zapStats(eventId: String): ZapAggregate {
        // Source of truth = the SAME deduped, receipt-backed rows the drawer shows,
        // so the action-bar summary and the drawer never disagree. Optimistic rows
        // are excluded here (extraZapSats overlays those). Fall back to the raw
        // aggregate only when there are no receipt-backed details (legacy snapshots
        // that persisted aggregates without per-zap detail rows).
        val receipts = zapDetailsForEvent(eventId).filter { it.eventId != null }
        if (receipts.isNotEmpty()) {
            return ZapAggregate(receipts.size, receipts.sumOf { it.sats })
        }
        val direct = zapStatsByEventId[eventId] ?: ZapAggregate.EMPTY
        val coord = articleCoordForEvent(eventId)
        val viaCoord = if (coord != null) zapStatsByEventId[coord] ?: ZapAggregate.EMPTY else ZapAggregate.EMPTY
        return if (viaCoord === ZapAggregate.EMPTY) direct
        else ZapAggregate(direct.count + viaCoord.count, direct.totalSats + viaCoord.totalSats)
    }
    fun statsLastUpdated(eventId: String): Long = statsUpdatedAt[eventId] ?: 0L

    // ─── Engagement contributor queries (drawer) ────────────────────────────

    /** Deduplicated pubkeys of users who replied to [eventId]. For articles, uses
     *  the same comment-id source as the list/count. */
    fun replyPubkeysForEvent(eventId: String): List<String> {
        val coord = articleCoordForEvent(eventId)
        val ids = if (coord != null) {
            articleCommentIds(coord).asSequence()
        } else {
            replyEventIdsForTarget(eventId)
        }
        val seen = HashSet<String>()
        for (id in ids) eventsById[id]?.pubkey?.let { seen.add(it) }
        return seen.toList()
    }

    /** Deduplicated pubkeys of users who reposted [eventId]. */
    fun repostPubkeysForEvent(eventId: String): List<String> {
        val coord = articleCoordForEvent(eventId)
        val direct = repostPubkeysByTarget[eventId]
        val viaCoord = if (coord != null) repostPubkeysByTarget[coord] else null
        return when {
            viaCoord == null -> direct?.toList() ?: emptyList()
            direct == null -> viaCoord.toList()
            else -> (direct + viaCoord).toList()
        }
    }

    /** Reaction info for all reactions to [eventId] — merges id-keyed and
     *  (for addressable events) coordinate-keyed reactions. */
    fun reactionsForEvent(eventId: String): List<ReactionInfo> {
        val coord = articleCoordForEvent(eventId)
        val direct = reactionsByTarget[eventId]
        val viaCoord = if (coord != null) reactionsByTarget[coord] else null
        return when {
            viaCoord == null -> direct?.toList() ?: emptyList()
            direct == null   -> viaCoord.toList()
            else             -> (direct + viaCoord).toList()
        }
    }

    /** Per-zap breakdown for [eventId]: sender, sats, optional comment. Merges
     *  id-keyed and (addressable) coordinate-keyed zaps, and collapses optimistic
     *  rows (eventId == null) once a matching receipt-backed row exists — so a
     *  self-zap shows ONE row, not an optimistic + receipt duplicate. */
    fun zapDetailsForEvent(eventId: String): List<ZapDetail> {
        val coord = articleCoordForEvent(eventId)
        val direct = zapDetailsByTarget[eventId]?.toList() ?: emptyList()
        val viaCoord = if (coord != null) zapDetailsByTarget[coord]?.toList() ?: emptyList() else emptyList()
        val deduped = dedupeZapDetails(direct + viaCoord)
        if (deduped.isNotEmpty()) return deduped
        // Legacy/bad-snapshot fallback: a raw aggregate persisted without detail
        // rows and no surviving receipt event to repair from → synthesize one
        // anonymous row so the drawer is never empty while the summary shows sats.
        // eventId == null keeps it out of zapStats' receipt-backed path, so it
        // never double-counts; a real receipt row always supersedes it.
        val agg = zapStatsByEventId[eventId]
            ?: coord?.let { zapStatsByEventId[it] }
            ?: ZapAggregate.EMPTY
        if (agg.count > 0 || agg.totalSats > 0L) {
            return listOf(ZapDetail(senderPubkey = null, sats = agg.totalSats, comment = null, eventId = null))
        }
        return deduped
    }

    /** De-dup zap rows: first by receipt id (a receipt merged from both the id and
     *  coordinate keys appears once), then drop optimistic placeholders (no receipt
     *  id) superseded by a receipt with the same sender + sats. Matching on
     *  (sender, sats) — NOT the comment — since the optimistic comment can differ
     *  from the receipt's (e.g. blank vs decrypted). */
    private fun dedupeZapDetails(rows: List<ZapDetail>): List<ZapDetail> {
        if (rows.size < 2) return rows
        val seenReceiptIds = HashSet<String>()
        val receipts = ArrayList<ZapDetail>()
        val optimistic = ArrayList<ZapDetail>()
        for (r in rows) {
            if (r.eventId != null) {
                if (seenReceiptIds.add(r.eventId)) receipts.add(r)
            } else {
                optimistic.add(r)
            }
        }
        if (optimistic.isEmpty()) return receipts
        val receiptKeys = receipts.map { it.senderPubkey to it.sats }.toSet()
        val keptOptimistic = optimistic.filter { (it.senderPubkey to it.sats) !in receiptKeys }
        return receipts + keptOptimistic
    }

    /** Lookup decrypted private-zap result. Returns null if not (yet) decrypted. */
    fun getDecryptedPrivateZap(zapReceiptId: String): DecryptedPrivateZap? =
        privateZapDecryptedById[zapReceiptId]

    /**
     * Called by PrivateZapRepository when async decrypt completes successfully.
     * Writes the result and bumps stats so the affected event's notification
     * flow re-emits (notification row rebuilds with real sender).
     */
    fun updateDecryptedPrivateZap(
        zapReceiptId: String,
        decrypted: DecryptedPrivateZap,
        targetId: String,
    ) {
        privateZapDecryptedById[zapReceiptId] = decrypted

        // Patch the drawer entry — swap anon pubkey for real sender,
        // upgrade comment from "" to the decrypted content. Matched by
        // receipt id (the eventId we wrote in handleZapReceipt). V10
        // entries have eventId=null and won't match — they stay anon.
        val list = zapDetailsByTarget[targetId]
        if (list != null) {
            synchronized(list) {
                val idx = list.indexOfFirst { it.eventId == zapReceiptId }
                if (idx >= 0) {
                    val old = list[idx]
                    list[idx] = old.copy(
                        senderPubkey = decrypted.senderPubkey,
                        comment = decrypted.comment ?: old.comment,
                    )
                }
            }
        }

        statsUpdatedAt[targetId] = System.currentTimeMillis()
        _statsInvalidations.tryEmit(StatsInvalidation.Targeted(setOf(targetId)))
        _statsSignal.value = System.nanoTime()
        // Bump the recipient's notification signal so the row re-emits
        // with the resolved real sender instead of "Anonymous".
        val ownPk = ownPubkey
        if (ownPk != null) bumpNotificationSignal(ownPk)
    }

    /**
     * Public scan trigger — call after snapshot restore + ownPubkey is set.
     * Walks kind-9735 events addressed to own pubkey and re-fires pending
     * decrypts for any with anon tags that aren't already in the sidecar.
     * Idempotent: PrivateZapRepository skips entries already decrypted.
     */
    fun rescanPendingPrivateZapDecrypts() {
        val own = ownPubkey ?: return
        val zapReceiptIds = idsByKind[9735] ?: return
        for (id in zapReceiptIds) {
            if (privateZapDecryptedById.containsKey(id)) continue
            val event = eventsById[id] ?: continue
            val recipientP = event.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
            if (recipientP != own) continue
            val (anonCt, anonSigner) = parseAnonTagAndSigner(event)
            if (anonCt != null && anonSigner != null) {
                _pendingPrivateZapDecrypts.tryEmit(
                    PendingPrivateZapDecrypt(id, anonCt, anonSigner)
                )
            }
        }
    }

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
     * contentFilter semantics (match FeedQuery contract):
     *   0 = all: all kinds in the set, no filter
     *   1 = notes only: roots/reposts, excluding replies and NIP-22 comments
     *   2 = replies only: kind-1 replies plus all NIP-22 kind-1111 comments
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
            // Notes tab: kind-1 roots (no replies) + kind-6/16 reposts — matches userNotesFlow
            1 -> events.filter {
                (it.kind == 1 && it.replyToId == null && it.rootId == null) ||
                    it.kind == 6 || it.kind == 16 || it.kind == 1068
            }
            // NIP-22 addressable comments can have only A/a tags and no event-id parent.
            2 -> events.filter {
                it.kind == 1111 ||
                    (it.kind == 1 && (it.replyToId != null || it.rootId != null))
            }
            else -> events
        }

        return filtered.sortedByDescending { it.createdAt }.take(limit)
    }

    // ─── A.5.1 T2: Batch user entity lookup ───────────────────────────────

    /** Batch variant of [getUserEntity]. Returns entities in input order, skipping unknown pubkeys. */
    fun getUserEntities(pubkeys: List<String>): List<UserEntity> =
        pubkeys.mapNotNull { getUserEntity(it) }

    // ─── A.5.1 T2: Follower count cache ───────────────────────────────────

    /** Cache a NIP-45 COUNT result. Timestamp stored in seconds (parity with legacy storage). */
    fun cacheFollowerCount(pubkey: String, count: Long) {
        followerCountCache[pubkey] = Pair(count, System.currentTimeMillis() / 1000)
    }

    /** Returns (count, updatedAtSeconds) or (null, null) if not cached. */
    fun getFollowerCount(pubkey: String): Pair<Long?, Long?> {
        val cached = followerCountCache[pubkey] ?: return Pair(null, null)
        return Pair(cached.first, cached.second)
    }

    /**
     * Trending hashtags: frequency scan of `t` tags across recent kind-1 events.
     * Returns up to [limit] (tag, count) pairs sorted by count DESC.
     * Scans the most recent 500 kind-1 events by createdAt.
     */
    fun trendingHashtags(limit: Int = 8): List<Pair<String, Int>> {
        val freq = HashMap<String, Int>()
        // recentByCreatedAt is already createdAt-DESC ordered — walk it and
        // stop after 500 kind-1 events. No full materialization, no full sort.
        var counted = 0
        for (entry in recentByCreatedAt) {
            if (counted >= 500) break
            val event = eventsById[entry.id] ?: continue
            if (event.kind != 1) continue
            counted++
            event.tags.forEach { tag ->
                if (tag.size >= 2 && tag[0] == "t" && tag[1].isNotBlank()) {
                    val value = tag[1].lowercase()
                    freq[value] = (freq[value] ?: 0) + 1
                }
            }
        }
        return freq.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }

    /**
     * Trending users: profiles with the highest cached follower counts.
     * Returns up to [limit] UserEntity objects with followerCount populated.
     */
    fun trendingUsers(limit: Int = 8): List<UserEntity> {
        return followerCountCache.entries
            .filter { it.value.first > 0 && profilesByPubkey.containsKey(it.key) }
            .sortedByDescending { it.value.first }
            .take(limit)
            .mapNotNull { (pubkey, countPair) ->
                getUserEntity(pubkey)?.copy(
                    followerCount = countPair.first,
                    followerCountUpdatedAt = countPair.second,
                )
            }
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

    /** Expand a raw actor-target set so coordinate targets (#a/#A on an article)
     *  also include the resolved article event id — otherwise `articleId in reacted`
     *  misses for a like keyed by coordinate. */
    private fun withResolvedCoords(targets: Set<String>?): Set<String> {
        if (targets.isNullOrEmpty()) return emptySet()
        // Always return a fresh snapshot — never the live mutable index set, or
        // distinctUntilChanged would compare a reference against its own mutation
        // and stop emitting (breaks reactive icon updates).
        return buildSet {
            for (t in targets) {
                add(t)
                if (':' in t) articleIdByCoord[t]?.let { add(it) }
            }
        }
    }

    /** Set of target event IDs the given [pubkey] has reacted to (kind 7). */
    fun reactedEventIdsFlow(pubkey: String): Flow<Set<String>> =
        _actionSignal
            .map { withResolvedCoords(reactedTargetsByActor[pubkey]) }
            .distinctUntilChanged()

    /** Set of target event IDs the given [pubkey] has reposted (kind 6). */
    fun repostedEventIdsFlow(pubkey: String): Flow<Set<String>> =
        _actionSignal
            .map { withResolvedCoords(repostedTargetsByActor[pubkey]) }
            .distinctUntilChanged()

    fun reactionEventIdsForTarget(pubkey: String, targetId: String): Set<String> =
        actionEventIdsForTarget(reactionEventIdsByActorTarget, pubkey, targetId, setOf(7))

    fun repostEventIdsForTarget(pubkey: String, targetId: String): Set<String> =
        actionEventIdsForTarget(repostEventIdsByActorTarget, pubkey, targetId, setOf(6, 16))

    private fun actionEventIdsForTarget(
        index: ConcurrentHashMap<ActorTargetKey, MutableSet<String>>,
        pubkey: String,
        targetId: String,
        kinds: Set<Int>,
    ): Set<String> {
        val indexed = index[actionKey(pubkey, targetId)]?.toSet().orEmpty()
        if (indexed.isNotEmpty()) return indexed
        val eventIds = idsByPubkey[pubkey]?.toList() ?: return emptySet()
        return eventIds.mapNotNull { eventsById[it] }
            .filter { event ->
                event.kind in kinds && when (event.kind) {
                    7 -> reactionTargetId(event) == targetId
                    6, 16 -> repostTargetId(event) == targetId
                    else -> false
                }
            }
            .mapTo(mutableSetOf()) { it.id }
    }

    /** Set of target event IDs the given [pubkey] has zapped (kind 9734, NOT 9735). */
    fun zappedEventIdsFlow(pubkey: String): Flow<Set<String>> =
        _actionSignal
            .map { withResolvedCoords(zappedTargetsByActor[pubkey]) }
            .distinctUntilChanged()

    /** NIP-88 responses for a poll. Consumers apply the poll's time bounds and vote mode. */
    fun pollResponsesFlow(pollId: String): Flow<List<NostrEvent>> =
        _actionSignal
            .map { pollResponses(pollId) }
            .distinctUntilChanged()

    private fun pollResponses(pollId: String): List<NostrEvent> {
        val result = ArrayList<NostrEvent>()
        for (id in idsByKind[1018].orEmpty()) {
            val event = eventsById[id] ?: continue
            val target = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.getOrNull(1)
            if (target != pollId) continue
            if (event.tags.none { it.size >= 2 && it[0] == "response" }) continue
            result.add(event)
        }
        return result.sortedWith(compareBy<NostrEvent> { it.pubkey }.thenBy { it.createdAt }.thenBy { it.id })
    }

    /** Synchronous check: has the current user reacted to or reposted [eventId]?
     *  Used by CardHydrator to skip backfill for already-lit posts. Also checks the
     *  article coordinate so a coordinate-keyed own like/repost counts. */
    fun isOwnEngaged(eventId: String): Boolean {
        val pk = ownPubkey ?: return false
        val coord = articleCoordForEvent(eventId)
        val reacted = reactedTargetsByActor[pk]
        val reposted = repostedTargetsByActor[pk]
        return reacted?.contains(eventId) == true || reposted?.contains(eventId) == true ||
            (coord != null && (reacted?.contains(coord) == true || reposted?.contains(coord) == true))
    }

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
        _statsInvalidations.tryEmit(StatsInvalidation.Targeted(setOf(eventId)))
    }

    /**
     * Insert an optimistic zap detail so the engagement drawer shows the
     * user's chip immediately after payment, before the kind-9735 receipt
     * arrives from the LNURL service.
     */
    fun addOptimisticZapDetail(targetId: String, senderPubkey: String, sats: Long, comment: String?) {
        zapDetailsByTarget
            .computeIfAbsent(targetId) { java.util.Collections.synchronizedList(mutableListOf()) }
            .add(ZapDetail(senderPubkey, sats, comment, eventId = null))
        statsUpdatedAt[targetId] = System.currentTimeMillis()
        _statsInvalidations.tryEmit(StatsInvalidation.Targeted(setOf(targetId)))
    }

    /**
     * Restore repair: rebuild per-zap detail rows from retained kind-9735 receipt
     * events, then recompute the aggregate from those rows. insertFromSnapshot does
     * NOT run handleZapReceipt, and the detail section persists independently of the
     * aggregate section, so a restored snapshot can hold a zap aggregate with no
     * detail rows — the action-bar count shows but the drawer is empty. This pass
     * makes the receipt events the source of truth and is idempotent (matched by
     * receipt id). It deliberately does NOT emit ownZapReceived — there is no VM
     * optimistic overlay to clear during restore. Legacy aggregate-only entries
     * with no surviving receipt event are left untouched (the drawer fallback in
     * [zapDetailsForEvent] covers those).
     */
    internal fun repairZapDetailsFromReceipts() {
        val receiptIds = idsByKind[9735]?.toList() ?: emptyList()
        val touchedTargets = HashSet<String>()
        for (rid in receiptIds) {
            val event = eventsById[rid] ?: continue
            val targetId = (event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }
                ?: event.tags.firstOrNull { it.size >= 2 && (it[0] == "a" || it[0] == "A") })
                ?.get(1) ?: continue
            val list = zapDetailsByTarget
                .computeIfAbsent(targetId) { java.util.Collections.synchronizedList(mutableListOf()) }
            synchronized(list) {
                if (list.none { it.eventId == rid }) {
                    val sats = extractSatsFromZap(event)
                    val desc = parseZapDescription(event)
                    // Promote our own private-zap receipt (anon-signed) → own, same as
                    // the live path, so a restored-but-lost detail row dedups against
                    // the persisted optimistic own row instead of doubling.
                    val rawSender = desc?.senderPubkey
                    val own = ownPubkey
                    val effectiveSender =
                        if (rawSender != null && own != null && rawSender != own && consumeOwnAnonZap(rawSender)) own
                        else rawSender
                    list.add(ZapDetail(effectiveSender, sats, desc?.comment, eventId = rid))
                }
            }
            touchedTargets.add(targetId)
        }
        // Recompute the aggregate from receipt-backed rows for targets that have
        // them, so summary == drawer. Targets with only the optimistic/legacy
        // aggregate (no receipt rows) keep their existing raw aggregate.
        for (targetId in touchedTargets) {
            val list = zapDetailsByTarget[targetId] ?: continue
            val receipts = synchronized(list) { list.filter { it.eventId != null } }
            if (receipts.isNotEmpty()) {
                zapStatsByEventId[targetId] = ZapAggregate(receipts.size, receipts.sumOf { it.sats })
            }
        }
    }

    /**
     * Register the anon pubkey of an outgoing private zap to [targetId] so a
     * later kind-9735 receipt (anon-signed) is recognized as ours and its drawer
     * row is promoted to own. Also reconciles a receipt that ALREADY arrived
     * (before this call): any receipt-backed row under [targetId]/its coord whose
     * sender == [anonPubkey] is patched to own in place, and the existing
     * (sender, sats) dedup then collapses it against the optimistic own row.
     * Returns true iff it patched an already-present receipt — the caller uses
     * that to clear the optimistic overlay (the live emit was missed because the
     * receipt was processed before the mapping existed). Sender-local only.
     */
    internal fun registerPendingPrivateZap(targetId: String, anonPubkey: String): Boolean {
        val own = ownPubkey
        if (own == null || anonPubkey == own) return false
        addOwnAnonZap(anonPubkey)
        var matched = false
        val keys = listOfNotNull(targetId, articleCoordForEvent(targetId))
        for (key in keys) {
            val list = zapDetailsByTarget[key] ?: continue
            synchronized(list) {
                val it = list.listIterator()
                while (it.hasNext()) {
                    val row = it.next()
                    if (row.eventId != null && row.senderPubkey == anonPubkey) {
                        it.set(row.copy(senderPubkey = own))
                        matched = true
                    }
                }
            }
        }
        if (matched) {
            consumeOwnAnonZap(anonPubkey) // matched → no longer pending
            statsUpdatedAt[targetId] = System.currentTimeMillis()
            _statsInvalidations.tryEmit(StatsInvalidation.Targeted(setOf(targetId)))
        }
        return matched
    }

    /** True if a receipt-backed zap by OUR pubkey is present for [eventId] (or its
     *  article coord) — the state predicate behind the race-safe optimistic-overlay
     *  clear (covers a receipt processed before the VM's flow collector subscribed). */
    fun hasOwnZapReceipt(eventId: String): Boolean {
        val own = ownPubkey ?: return false
        fun anyOwnReceipt(key: String?): Boolean {
            val list = key?.let { zapDetailsByTarget[it] } ?: return false
            return synchronized(list) { list.any { it.eventId != null && it.senderPubkey == own } }
        }
        return anyOwnReceipt(eventId) || anyOwnReceipt(articleCoordForEvent(eventId))
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
        _statsInvalidations.tryEmit(StatsInvalidation.Targeted(eventIds.toSet()))
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

    /** Reactive hashtag search: finds kind-1 events with a matching `t` tag. */
    fun searchNotesByHashtagFlow(tag: String): Flow<List<FeedRow>> =
        _feedSignal
            .map { searchNotesByHashtag(tag) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    private fun searchNotesByHashtag(tag: String): List<FeedRow> {
        val lower = tag.lowercase()
        return eventsById.values
            .filter { event ->
                event.kind == 1 && event.tags.any { t ->
                    t.size >= 2 && t[0] == "t" && t[1].lowercase() == lower
                }
            }
            .sortedByDescending { it.createdAt }
            .take(50)
            .map { toFeedRow(it) }
    }

    /**
     * Reactive profile candidate search over identity fields only.
     *
     * This deliberately does not rank or apply the 50-row display limit. The
     * consumer applies the single WoT-aware ranking pass before truncation.
     */
    fun searchUsersFlow(query: String): Flow<List<UserEntity>> =
        _profileSignal
            .map { searchUsers(query) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    private fun searchUsers(query: String): List<UserEntity> {
        return boundedIdentitySearchCandidates(
            users = profilesByPubkey.values.asSequence().mapNotNull { event ->
                getUserEntity(event.pubkey)
            },
            query = query,
            limit = SEARCH_PROFILE_MATCH_CAP,
        )
    }

    /** Exact ID lookup returning FeedRows, sorted by createdAt DESC. */
    fun feedRowsByIds(ids: Set<String>): List<FeedRow> =
        ids.mapNotNull { eventsById[it] }
            .sortedByDescending { it.createdAt }
            .map { toFeedRow(it) }

    /**
     * Synthesize a FeedRow from an event that may or may not be in this store.
     * Used by FeedViewModel.feedRows when an event has arrived in the consumer's
     * timeline state but the event-processor hasn't inserted it into MES yet.
     *
     * The returned FeedRow has un-hydrated metadata (no author profile fields,
     * no parent reference data); CardHydrator fills these in once visible.
     */
    fun synthesizeFeedRow(event: NostrEvent): FeedRow = toFeedRow(event)

    // ─── Reactive flows ─────────────────────────────────────────────────────

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun userFeedFlow(
        pubkey: String,
        contentFilter: Int = 0,
        kinds: Set<Int> = PROFILE_NOTE_REPLY_EVENT_KIND_SET + 30023,
        limit: Int = 200,
    ): Flow<List<FeedRow>> =
        // No _statsSignal: stats changes don't alter feed membership/order, and
        // per-card statsFlow carries the counts. _profileSignal stays — the
        // emitted rows embed author name/picture resolved at emission time
        // with no per-card reactive profile path through this flow.
        combine(_feedSignal, _profileSignal) { _, _ -> }
            .sample(200)
            .map { userFeedEvents(pubkey, contentFilter, kinds, limit).map { toFeedRow(it) } }
            .flowOn(Dispatchers.Default)

    fun followsFlow(pubkey: String): Flow<Set<String>> =
        _followsSignal.map { getFollows(pubkey) ?: emptySet() }
            .flowOn(Dispatchers.Default)

    fun followersFlow(pubkey: String): Flow<Set<String>> =
        _followsSignal.map { followersOf(pubkey) }
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
        // _feedSignal only: ThreadScreen wires per-card statsFlow/profileFlow
        // through ThreadViewModel, so stats/profile bumps don't need to
        // recompute the whole thread list.
        _feedSignal
            .map { collectThread(rootId).map { toFeedRow(it) } }
            .flowOn(Dispatchers.Default)

    private fun collectThread(rootId: String): List<NostrEvent> {
        val results = mutableListOf<NostrEvent>()
        val included = mutableSetOf<String>()
        // BFS frontier — every admitted id gets its repliers expanded.
        val queue = ArrayDeque<String>()

        fun admit(id: String, event: NostrEvent) {
            if (!included.add(id)) return
            results.add(event)
            queue.add(id)
        }

        eventsById[rootId]?.let { admit(rootId, it) }

        // Events that explicitly mark rootId as their thread root.
        // idsByReplyTarget[rootId] indexes BOTH rootId and replyToId references
        // (insertCore/insertFromSnapshot); the rootId filter here keeps the
        // direct-mark predicate exact — replyToId-only references are admitted
        // by the BFS below, which requires their parent to be in the thread.
        idsByReplyTarget[rootId]?.forEach { id ->
            if (id !in included) {
                val event = eventsById[id] ?: return@forEach
                if (event.rootId == rootId) admit(id, event)
            }
        }

        // BFS over the reply index: admit events whose replyToId points to
        // anything already in the thread, expanding replies-of-replies until
        // the frontier drains (replaces the O(N)-per-iteration fixpoint scan).
        while (queue.isNotEmpty()) {
            val parentId = queue.removeFirst()
            idsByReplyTarget[parentId]?.forEach { id ->
                if (id !in included) {
                    val event = eventsById[id] ?: return@forEach
                    if (event.replyToId == parentId) admit(id, event)
                }
            }
        }

        return results.sortedBy { it.createdAt }
    }

    // ─── Event/User entity getters ──

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
            website = fields["website"],
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

    internal fun nip05VerificationFlow(
        pubkey: String,
        nip05: String,
    ): Flow<Nip05VerificationStatus> {
        val key = nip05VerificationCacheKey(pubkey, nip05)
            ?: return kotlinx.coroutines.flow.flowOf(Nip05VerificationStatus.UNKNOWN)
        return nip05VerificationSignal
            .map { currentNip05Verification(key, nowMs = System.currentTimeMillis()) }
            .distinctUntilChanged()
    }

    internal fun currentNip05Verification(
        key: Nip05VerificationCacheKey,
        nowMs: Long = System.currentTimeMillis(),
    ): Nip05VerificationStatus {
        val entry = nip05VerificationCache[key] ?: return Nip05VerificationStatus.UNKNOWN
        if (!isCurrentProfileNip05Claim(key)) {
            if (nip05VerificationCache.remove(key, entry)) bumpNip05VerificationSignal()
            return Nip05VerificationStatus.UNKNOWN
        }
        if (entry.isValidAt(nowMs)) return entry.status
        if (nip05VerificationCache.remove(key, entry)) bumpNip05VerificationSignal()
        return Nip05VerificationStatus.UNKNOWN
    }

    internal fun storeNip05Verification(
        entry: Nip05VerificationCacheEntry,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!entry.isValidAt(nowMs)) return false
        if (!isCurrentProfileNip05Claim(entry.key)) return false

        nip05VerificationCache[entry.key] = entry
        trimNip05VerificationCache()
        bumpNip05VerificationSignal()
        return true
    }

    private fun retainNip05VerificationForCurrentProfile(pubkey: String, nip05: String?) {
        val normalizedPubkey = pubkey.lowercase()
        val currentKey = nip05?.let { nip05VerificationCacheKey(normalizedPubkey, it) }
        val staleKeys = nip05VerificationCache.keys
            .filter { it.pubkey == normalizedPubkey && it != currentKey }
        var changed = false
        for (staleKey in staleKeys) {
            changed = nip05VerificationCache.remove(staleKey) != null || changed
        }
        if (changed) bumpNip05VerificationSignal()
    }

    internal fun isCurrentProfileNip05Claim(key: Nip05VerificationCacheKey): Boolean {
        if (!profilesByPubkey.containsKey(key.pubkey)) return true
        return profileFieldsCache[key.pubkey]?.get("nip05")?.trim() == key.nip05
    }

    internal fun nip05VerificationSnapshot(
        nowMs: Long = System.currentTimeMillis(),
    ): List<Nip05VerificationCacheEntry> {
        val valid = ArrayList<Nip05VerificationCacheEntry>(nip05VerificationCache.size)
        var changed = false
        for ((key, entry) in nip05VerificationCache) {
            if (entry.isValidAt(nowMs) && isCurrentProfileNip05Claim(key)) {
                valid.add(entry)
            } else {
                changed = nip05VerificationCache.remove(key, entry) || changed
            }
        }
        if (changed) bumpNip05VerificationSignal()
        return valid.sortedByDescending { it.checkedAtMs }.take(NIP05_CACHE_CAP)
    }

    private fun trimNip05VerificationCache() {
        if (nip05VerificationCache.size <= NIP05_CACHE_CAP) return
        val overflow = nip05VerificationCache.entries
            .sortedBy { it.value.checkedAtMs }
            .take(nip05VerificationCache.size - NIP05_CACHE_CAP)
        for ((key, entry) in overflow) {
            nip05VerificationCache.remove(key, entry)
        }
    }

    private fun clearNip05VerificationCache() {
        nip05VerificationCache.clear()
        bumpNip05VerificationSignal()
    }

    private fun bumpNip05VerificationSignal() {
        nip05VerificationSignal.value = nip05VerificationSignal.value + 1L
    }

    /**
     * Observe per-event engagement counts. Used by EventActionBar so individual
     * cards update their counts without going through a list-wide signal trigger.
     *
     * Targeted invalidations fire when a reply/reaction/repost/zap changes this
     * event. [EventStats] equality suppresses emission when THIS event's counts
     * didn't change — so a kind-7 reaction on an unrelated event doesn't
     * recompose 100 visible cards, only the affected card.
     */
    fun statsFlow(eventId: String): Flow<EventStats> =
        _statsInvalidations
            // Register with the hot invalidation stream before reading the
            // initial value. onStart reads first and subscribes second, leaving
            // a gap where an admission/eviction invalidation can be lost.
            .onSubscription {
                emit(StatsInvalidation.Targeted(setOf(eventId)))
            }
            .filter { inv ->
                when (inv) {
                    is StatsInvalidation.Targeted -> eventId in inv.ids
                    StatsInvalidation.Broadcast -> true
                }
            }
            .map { currentStats(eventId) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    private fun currentStats(eventId: String): EventStats {
        val zap = zapStats(eventId)
        return EventStats(
            replyCount = replyCount(eventId),
            repostCount = repostCount(eventId),
            reactionCount = reactionCount(eventId),
            zapCount = zap.count,
            zapTotalSats = zap.totalSats,
        )
    }

    /** Non-Flow snapshot of current engagement counts for a post. */
    fun currentStatsSnapshot(eventId: String): EventStats = currentStats(eventId)

    // ─── Engagement cap ──────────────────────────────────────────────────────

    fun markEngagementCapped(eventId: String) { engagementCapped.add(eventId) }
    fun isEngagementCapped(eventId: String): Boolean = eventId in engagementCapped

    // ─── Outbox routing ─────────────────────────────────────────────────────

    /** Relay URLs that have delivered events by [pubkey]. For profile fallback
     *  routing — a relay serving an author's kind-1s likely has their kind-0.
     *  Snapshots the concurrent [idsByPubkey] set before iteration (Rule #23). */
    fun relaysSeenForPubkey(pubkey: String): Set<String> {
        val eventIds = idsByPubkey[pubkey]?.toList() ?: return emptySet()
        val relays = mutableSetOf<String>()
        for (id in eventIds.take(20)) {
            val event = eventsById[id] ?: continue
            relays.addAll(event.relaysSeen)
            if (relays.size >= 8) break
        }
        return relays
    }

    override fun writeRelaysFor(pubkey: String): List<String> =
        relayListsByPubkey[pubkey]?.write ?: emptyList()

    /**
     * Author outboxes for on-demand resolution. A real kind-10002 always wins;
     * only authors whose accepted kind-0 identifies them as a mostr bridge get
     * the recorded bootstrap relay when no relay list exists.
     */
    fun lookupWriteRelaysFor(pubkey: String): List<String> =
        relayListsByPubkey[pubkey]?.write
            ?: profileDerivedLookupRelaysByPubkey[pubkey].orEmpty()

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

    /** Snapshot of all relay lists (pubkey → RelayList). Used by OutboxRelayResolver. */
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

    fun getProfileRelayFacts(pubkey: String): ProfileRelayFacts = ProfileRelayFacts(
        relays = getReadWriteRelayConfigs(pubkey),
        searchRelays = getSearchRelayUrls(pubkey),
        blockedRelays = getBlockedRelayUrls(pubkey),
        publishedKinds = setOf(10002, 10006, 10007).filterTo(mutableSetOf()) { kind ->
            relayKindCreatedAt.containsKey("$pubkey:$kind")
        },
    )

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

    fun profileRelayFactsFlow(pubkey: String): Flow<ProfileRelayFacts> =
        _relayConfigSignal
            .map { getProfileRelayFacts(pubkey) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    fun profileRelayCountFlow(pubkey: String): Flow<Int?> =
        profileRelayFactsFlow(pubkey)
            .map { facts -> deriveProfileRelayCount(10002 in facts.publishedKinds, facts.relays) }
            .distinctUntilChanged()

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

    // ─── NIP-85 WoT query APIs (kind 30382 / kind 10040) ─────────────────

    fun isActiveWotProvider(pubkey: String): Boolean =
        normalizeHexPubkey(pubkey) == activeWotProviderPubkey

    fun setActiveWotProvider(providerPubkey: String, relayHint: String = DEFAULT_WOT_RELAY) {
        val normalizedProvider = normalizeHexPubkey(providerPubkey) ?: DEFAULT_WOT_PROVIDER_PUBKEY
        val normalizedRelay = normalizeRelayUrl(relayHint) ?: DEFAULT_WOT_RELAY
        var changed = false
        synchronized(wotProviderLock) {
            changed = normalizedProvider != activeWotProviderPubkey
            activeWotProviderPubkey = normalizedProvider
            activeWotProviderRelay = normalizedRelay
            if (changed) {
                clearWotAssertionsLocked()
            }
        }
        if (changed) _wotSignal.value = System.nanoTime()
    }

    fun activeWotProvider(): WotProviderDescriptor =
        WotProviderDescriptor(
            providerPubkey = activeWotProviderPubkey,
            relayHint = activeWotProviderRelay,
            updatedAt = 0L,
        )

    fun ownWotProviderFromRegistry(): WotProviderDescriptor? = ownWotProviderRegistry

    fun ownWotProviderEncryptedContent(): String? = ownWotProviderEncryptedContent

    fun latestOwnWotProviderRegistryEvent(): NostrEvent? {
        val own = ownPubkey ?: return null
        return eventsById.values
            .asSequence()
            .filter { it.kind == 10040 && it.pubkey == own }
            .maxByOrNull { it.createdAt }
    }

    fun getWotAssertions(): Map<String, WotAssertionEntity> = HashMap(wotBySubject)

    fun hasWotData(): Boolean = wotBySubject.isNotEmpty() || wotQueriedSubjects.isNotEmpty()

    fun wotFor(pubkey: String): WotLookup {
        val subject = normalizeHexPubkey(pubkey) ?: return WotLookup.Pending
        val assertion = wotBySubject[subject]
        return when {
            assertion != null -> {
                wotAccessedAt[subject] = System.nanoTime()
                WotLookup.Scored(assertion)
            }
            subject in wotQueriedSubjects -> {
                wotAccessedAt[subject] = System.nanoTime()
                WotLookup.Absent
            }
            else -> WotLookup.Pending
        }
    }

    fun wotFlow(pubkey: String): Flow<WotLookup> =
        _wotSignal
            .map { wotFor(pubkey) }
            .onStart { emit(wotFor(pubkey)) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    fun markWotSubjectsQueried(subjects: Collection<String>) {
        var changed = false
        synchronized(wotProviderLock) {
            for (subject in subjects.mapNotNull { normalizeHexPubkey(it) }) {
                wotAccessedAt[subject] = System.nanoTime()
                if (wotQueriedSubjects.add(subject)) changed = true
            }
            if (changed) {
                trimWotAssertionsIfNeeded()
            }
        }
        if (changed) _wotSignal.value = System.nanoTime()
    }

    fun clearWotAssertions() {
        val changed = synchronized(wotProviderLock) {
            clearWotAssertionsLocked()
        }
        if (changed) _wotSignal.value = System.nanoTime()
    }

    private fun clearWotAssertionsLocked(): Boolean {
        if (wotBySubject.isEmpty() && wotQueriedSubjects.isEmpty() && wotAccessedAt.isEmpty()) return false
        wotBySubject.clear()
        wotQueriedSubjects.clear()
        wotAccessedAt.clear()
        return true
    }

    // ─── Relay monitor query APIs (kind 30166 / NIP-66) ──────────────────

    override fun getRelayMonitors(): Map<String, RelayMonitorEntity> =
        HashMap(relayMonitorsByUrl)

    fun relayMonitorCount(): Int = relayMonitorsByUrl.size

    fun relayMonitorsFlow(): Flow<Map<String, RelayMonitorEntity>> =
        _relayMonitorSignal
            .map { getRelayMonitors() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    fun getRelayIdentity(url: String): RelayIdentityEntity? {
        val normalized = normalizeRelayUrl(url) ?: return null
        return relayIdentitiesByUrl[normalized]
    }

    /** Store a successful device NIP-11 identity read. Newer device reads win. */
    fun putRelayIdentity(
        url: String,
        name: String?,
        iconUrl: String?,
        fetchedAt: Long,
    ): Boolean {
        val normalized = normalizeRelayUrl(url) ?: return false
        val incoming = RelayIdentityEntity(
            relayUrl = normalized,
            name = name?.trim()?.takeIf(String::isNotBlank),
            iconUrl = iconUrl?.trim()?.takeIf(String::isNotBlank),
            fetchedAt = fetchedAt,
        )
        var changed = false
        relayIdentitiesByUrl.compute(normalized) { _, existing ->
            when {
                existing != null && existing.fetchedAt > fetchedAt -> existing
                existing == incoming -> existing
                else -> incoming.also { changed = true }
            }
        }
        if (!changed) return false

        val overflow = relayIdentitiesByUrl.size - RELAY_IDENTITY_CAP
        if (overflow > 0) {
            relayIdentitiesByUrl.entries
                .sortedBy { it.value.fetchedAt }
                .take(overflow)
                .forEach { relayIdentitiesByUrl.remove(it.key, it.value) }
        }
        _relayIdentitySignal.value = System.nanoTime()
        return true
    }

    // ─── Combined relay health (trust + monitor + device identity) ───────

    /**
     * Look up health info for a relay URL, trying both raw and normalized forms.
     * Handles the case where configured relay URLs (from kind-10002) aren't
     * normalized but health data keys are.
     */
    fun getRelayHealth(url: String): RelayHealthInfo? {
        val normalized = normalizeRelayUrl(url) ?: return null
        val score = trustScoresByUrl[normalized]
        val monitor = relayMonitorsByUrl[normalized]
        val identity = relayIdentitiesByUrl[normalized]
        if (score == null && monitor == null && identity == null) return null
        return RelayHealthInfo(
            relayUrl = normalized,
            trustScore = score,
            monitor = monitor,
            identity = identity,
        )
    }

    fun relayHealthFlow(): Flow<Map<String, RelayHealthInfo>> =
        combine(_trustScoreSignal, _relayMonitorSignal, _relayIdentitySignal) { _, _, _ ->
            val result = mutableMapOf<String, RelayHealthInfo>()
            for ((url, score) in trustScoresByUrl) {
                result[url] = RelayHealthInfo(
                    relayUrl = url,
                    trustScore = score,
                    monitor = relayMonitorsByUrl[url],
                    identity = relayIdentitiesByUrl[url],
                )
            }
            for ((url, monitor) in relayMonitorsByUrl) {
                val existing = result[url]
                if (existing != null) {
                    if (existing.monitor == null) {
                        result[url] = existing.copy(monitor = monitor)
                    }
                } else {
                    result[url] = RelayHealthInfo(
                        relayUrl = url,
                        monitor = monitor,
                        identity = relayIdentitiesByUrl[url],
                    )
                }
            }
            for ((url, identity) in relayIdentitiesByUrl) {
                val existing = result[url]
                if (existing != null) {
                    if (existing.identity == null) result[url] = existing.copy(identity = identity)
                } else {
                    result[url] = RelayHealthInfo(url, identity = identity)
                }
            }
            result.toMap()
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    // ─── A.5.1 T5b: Relay set query APIs ───────────────────────────────────

    fun getAllRelaySets(ownerPubkey: String): List<RelaySet> =
        relaySetsByCoordinate.values.filter { it.ownerPubkey == ownerPubkey && it.members.isNotEmpty() }

    fun getSetMembers(ownerPubkey: String, dTag: String): List<String> =
        relaySetsByCoordinate["$ownerPubkey:$dTag"]?.members ?: emptyList()

    fun getRelaySet(ownerPubkey: String, dTag: String): RelaySet? =
        relaySetsByCoordinate["$ownerPubkey:$dTag"]

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
     * Notification query backed by the recipient-pubkey reverse index.
     * Iterates the per-recipient sorted set in createdAt-DESC order until
     * [limit] items are collected, applying self-exclusion (parses
     * kind-9735 description for the real sender) and an optional
     * followed-only filter at read time.
     *
     * @param limit Maximum rows to return. null = unlimited.
     */
    /**
     * Notifications for [recipientPubkey], grouped for display.
     *
     * Reactions, reposts (kind 6 & 16) and zaps fold by (targetNoteId, notifType)
     * into [NotificationRow.Grouped]; replies and mentions stay individual
     * [NotificationRow.Single] rows. Rows are returned newest-first by their most
     * recent contributing event.
     *
     * [limit] caps the number of VISIBLE ROWS returned (groups + singles combined),
     * NOT raw events. The recipient index is scanned DESC and bounded by
     * [NOTIF_RAW_SCAN_CAP] raw events; a group's member count / summed sats reflect
     * only events within that window. The bell/unread count stays event-based in
     * [notificationCountSince] and is unaffected by grouping.
     *
     * Filtering (self-exclusion, followed-only, NIP-25 "-" dislikes) is applied per
     * raw event BEFORE folding, so group counts are correct.
     */
    fun getNotifications(
        recipientPubkey: String,
        followedOnly: Boolean = false,
        limit: Int? = null,
    ): List<NotificationRow> {
        val entries = notifIdsByRecipient[recipientPubkey] ?: return emptyList()
        val follows = if (followedOnly) followsByPubkey[recipientPubkey] else null
        val cap = limit ?: Int.MAX_VALUE
        val latestPollVoteIds = latestPollVoteNotificationIds(entries)

        val singles = ArrayList<NotificationRow.Single>()
        val groups = LinkedHashMap<String, NotifGroupAcc>()
        fun slots() = singles.size + groups.size
        var scanned = 0

        for (entry in entries) {
            if (scanned >= NOTIF_RAW_SCAN_CAP) break
            val event = eventsById[entry.eventId] ?: continue
            scanned++

            // Resolve kind-9735 zap identity ONCE per event (parseZapDescription is a
            // JSON parse) — reused for self-exclusion, follows filter, and the actor.
            val decryptedZap = if (event.kind == 9735) privateZapDecryptedById[event.id] else null
            val zapDesc = if (event.kind == 9735 && decryptedZap == null) parseZapDescription(event) else null
            val effectivePubkey = if (event.kind == 9735) {
                decryptedZap?.senderPubkey ?: zapDesc?.senderPubkey ?: event.pubkey
            } else event.pubkey
            if (effectivePubkey == recipientPubkey) continue
            if (follows != null && effectivePubkey !in follows) continue

            if (event.kind == 1018) {
                if (event.id !in latestPollVoteIds) continue
            }

            val notifType = deriveNotifType(event, recipientPubkey)

            // NIP-25 "-" dislikes are not displayable notifications (mirrors the card path).
            val reaction = if (notifType == "reaction") {
                val rc = parseReactionContent(event.content.ifBlank { "+" }, event.tags)
                if (rc == ReactionContent.Standard("-")) continue
                rc
            } else null

            when (notifType) {
                "reply", "mention", "poll_vote" -> {
                    if (slots() >= cap) continue
                    singles.add(buildSingleNotification(event, notifType))
                }
                "reaction", "repost", "zap" -> {
                    val targetId = notifTargetId(event, notifType)
                    val gkey = "$notifType|$targetId"
                    var g = groups[gkey]
                    if (g == null) {
                        if (slots() >= cap) continue
                        g = NotifGroupAcc(notifType, targetId, targetId?.let { eventsById[it]?.content } ?: "")
                        groups[gkey] = g
                    }
                    val sats = if (event.kind == 9735) extractSatsFromZap(event) else 0L
                    val actorPubkey: String?
                    val actorFields: Map<String, String?>
                    if (event.kind == 9735) {
                        when {
                            decryptedZap != null -> { actorPubkey = decryptedZap.senderPubkey; actorFields = cachedProfileFields(decryptedZap.senderPubkey) }
                            zapDesc != null -> { actorPubkey = zapDesc.senderPubkey; actorFields = cachedProfileFields(zapDesc.senderPubkey) }
                            else -> { actorPubkey = null; actorFields = emptyMap() }   // anonymous aggregate
                        }
                    } else {
                        actorPubkey = event.pubkey
                        actorFields = cachedProfileFields(event.pubkey)
                    }
                    g.fold(actorPubkey, actorFields, event.createdAt, sats, reaction)
                }
            }
        }

        val rows = ArrayList<NotificationRow>(slots())
        rows.addAll(singles)
        groups.values.forEach { rows.add(it.toGrouped()) }
        rows.sortByDescending { it.mostRecentAt }
        return if (limit != null && rows.size > limit) rows.subList(0, limit).toList() else rows
    }

    /**
     * Count notifications for [recipientPubkey] with createdAt > [since].
     * Walks the sorted set and breaks early when entries drop below [since].
     */
    fun notificationCountSince(recipientPubkey: String, since: Long): Int {
        val entries = notifIdsByRecipient[recipientPubkey] ?: return 0
        var count = 0
        val latestPollVoteIds = latestPollVoteNotificationIds(entries)
        for (entry in entries) {
            if (entry.createdAt <= since) break  // sorted DESC — done
            val event = eventsById[entry.eventId] ?: continue
            if (event.kind == 9735) {
                val decrypted = privateZapDecryptedById[event.id]
                val effectiveSender = decrypted?.senderPubkey
                    ?: parseZapDescription(event)?.senderPubkey
                if (effectiveSender == recipientPubkey) continue
            } else {
                if (event.pubkey == recipientPubkey) continue
            }
            if (event.kind == 1018) {
                if (event.id !in latestPollVoteIds) continue
            }
            count++
        }
        return count
    }

    /**
     * Reactive notification flow driven by per-recipient signal.
     * Also listens to profile changes so actor avatars/names fetched after the
     * row was built repaint without waiting for another notification event.
     */
    fun notificationsFlow(
        recipientPubkey: String,
        followedOnly: Boolean = false,
        limit: Int? = null,
    ): Flow<List<NotificationRow>> =
        combine(notificationSignalFor(recipientPubkey), _profileSignal) { _, _ ->
            getNotifications(recipientPubkey, followedOnly, limit)
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /** Defensive bound on raw events scanned per notification query (see getNotifications). */
    private val NOTIF_RAW_SCAN_CAP = 1500

    /** Same deterministic latest-wins rule as poll tally: createdAt, then event id. */
    private fun latestPollVoteNotificationIds(entries: Iterable<NotifEntry>): Set<String> {
        val latestByVoterAndPoll = HashMap<String, NostrEvent>()
        var scanned = 0
        for (entry in entries) {
            if (scanned++ >= NOTIF_RAW_SCAN_CAP) break
            val event = eventsById[entry.eventId]?.takeIf { it.kind == 1018 } ?: continue
            val pollId = pollIdForResponse(event) ?: continue
            val key = "$pollId|${event.pubkey}"
            val previous = latestByVoterAndPoll[key]
            if (previous == null || event.createdAt > previous.createdAt ||
                (event.createdAt == previous.createdAt && event.id > previous.id)
            ) {
                latestByVoterAndPoll[key] = event
            }
        }
        return latestByVoterAndPoll.values.mapTo(HashSet()) { it.id }
    }

    /** Target note id a grouped notification folds under (mirrors the old per-type logic). */
    private fun notifTargetId(event: NostrEvent, notifType: String): String? = when (notifType) {
        "reaction" -> event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        "repost" -> event.rootId
        "zap" -> event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: event.rootId
        else -> null
    }

    /** Build a [NotificationRow.Single] for a reply/mention (kind-1/1111). */
    private fun buildSingleNotification(event: NostrEvent, notifType: String): NotificationRow.Single {
        val fields = cachedProfileFields(event.pubkey)
        val pollId = pollIdForResponse(event).takeIf { notifType == "poll_vote" }
        return NotificationRow.Single(
            id = event.id,
            notifType = notifType,
            actorPubkey = event.pubkey,
            actorName = fields["name"],
            actorDisplayName = fields["display_name"],
            actorPicture = fields["picture"],
            targetNoteId = pollId ?: event.id,
            targetNoteContent = pollId?.let { eventsById[it]?.content } ?: event.content,
            parentNoteContent = if (notifType == "reply") event.replyToId?.let { eventsById[it]?.content } ?: "" else "",
            createdAt = event.createdAt,
        )
    }

    /**
     * Accumulator that folds many contributing events into one
     * [NotificationRow.Grouped]. Named actors dedup by pubkey (per-actor sats
     * summed, most-recent timestamp kept); anonymous zaps collapse into a single
     * count/sats aggregate; the dominant reaction is the modal emoji.
     */
    private class NotifGroupAcc(
        private val notifType: String,
        private val targetNoteId: String?,
        private val targetNoteContent: String,
    ) {
        private val actorsByPubkey = LinkedHashMap<String, NotificationActor>()
        private val reactionCounts = HashMap<ReactionContent, Int>()
        private var anonymousCount = 0
        private var anonymousSats = 0L
        private var sumSats = 0L
        private var mostRecentAt = 0L

        fun fold(
            actorPubkey: String?,
            fields: Map<String, String?>,
            createdAt: Long,
            sats: Long,
            reaction: ReactionContent?,
        ) {
            if (createdAt > mostRecentAt) mostRecentAt = createdAt
            sumSats += sats
            if (reaction != null) reactionCounts[reaction] = (reactionCounts[reaction] ?: 0) + 1
            if (actorPubkey == null) {
                anonymousCount++
                anonymousSats += sats
                return
            }
            val prev = actorsByPubkey[actorPubkey]
            actorsByPubkey[actorPubkey] = NotificationActor(
                pubkey = actorPubkey,
                name = fields["name"] ?: prev?.name,
                displayName = fields["display_name"] ?: prev?.displayName,
                picture = fields["picture"] ?: prev?.picture,
                sats = (prev?.sats ?: 0L) + sats,
                reaction = reaction ?: prev?.reaction,
                createdAt = maxOf(prev?.createdAt ?: 0L, createdAt),
            )
        }

        fun toGrouped(): NotificationRow.Grouped {
            val actors = actorsByPubkey.values.sortedByDescending { it.createdAt }
            return NotificationRow.Grouped(
                notifType = notifType,
                targetNoteId = targetNoteId,
                targetNoteContent = targetNoteContent,
                actors = actors,
                people = actors.size + anonymousCount,
                sumSats = sumSats,
                dominantReaction = reactionCounts.maxByOrNull { it.value }?.key,
                anonymousCount = anonymousCount,
                anonymousSats = anonymousSats,
                mostRecentAt = mostRecentAt,
            )
        }
    }

    /**
     * Derive notification type from event kind and threading info.
     * Kind 1 disambiguates: if replyToId/rootId → recipient's event, it's "reply"; else "mention".
     */
    private fun deriveNotifType(event: NostrEvent, recipientPubkey: String): String = when (event.kind) {
        7 -> "reaction"
        1018 -> "poll_vote"
        6, 16 -> "repost"
        9735 -> "zap"
        1, 1111 -> {
            // kind-1111 (NIP-22 comment) threads via lowercase e (parseNip22Threading
            // → replyToId); a reply to the recipient's note/comment/article is a reply.
            val isReply = event.replyToId?.let { eventsById[it]?.pubkey == recipientPubkey } == true
                || event.rootId?.let { eventsById[it]?.pubkey == recipientPubkey } == true
            if (isReply) "reply" else "mention"
        }
        else -> "mention"
    }

    /** Newest authored poll IDs used by the notification relay #e filter. */
    fun authoredPollIds(pubkey: String, limit: Int = 200): List<String> =
        idsByKind[1068].orEmpty()
            .asSequence()
            .mapNotNull(eventsById::get)
            .filter { it.pubkey == pubkey }
            .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenByDescending { it.id })
            .take(limit.coerceAtLeast(0))
            .map { it.id }
            .toList()

    // ─── FeedRow conversion ─────────────────────────────────────────────────

    private fun toFeedRow(event: NostrEvent): FeedRow {
        // Per-author + per-event cache keys. A profile update for author X
        // bumps profileUpdatedAt[X] but leaves other authors' timestamps
        // unchanged — rows for those authors stay cached. A stat update for
        // event Y bumps statsUpdatedAt[Y] but leaves other rows alone.
        // kind-16 (NIP-18 generic repost) keys stats off the reposted event too,
        // not just kind-6 — so a reposted article's counts/cache-invalidation track
        // the original, matching EventModel.engagementId.
        val statsId = if (event.kind == 6 || event.kind == 16) event.rootId ?: event.id else event.id
        val authorProfileTs = profileUpdatedAt[event.pubkey] ?: 0L
        val statsTs = statsUpdatedAt[statsId] ?: 0L

        val cached = feedRowCache[event.id]
        if (cached != null
            && cached.authorProfileTs == authorProfileTs
            && cached.statsTs == statsTs
        ) {
            feedRowAccessedAt[event.id] = System.nanoTime()
            return cached.row
        }

        val fields = cachedProfileFields(event.pubkey)
        val zap = zapStats(statsId)

        val projectedRootId = event.rootId ?: if (event.kind == 1111) {
            event.tags.firstOrNull { tag ->
                tag.size >= 2 && tag[0] == "A"
            }?.get(1)?.let(articleIdByCoord::get)
        } else {
            null
        }

        val row = FeedRow(
            id = event.id,
            pubkey = event.pubkey,
            kind = event.kind,
            content = event.content,
            createdAt = event.createdAt,
            tags = tagsToJson(event.tags),
            relayUrl = event.relayUrl,
            replyToId = event.replyToId,
            rootId = projectedRootId,
            hasContentWarning = event.hasContentWarning,
            contentWarningReason = event.contentWarningReason,
            zapTotalSats = zap.totalSats,
            authorName = fields["name"],
            authorDisplayName = fields["display_name"],
            authorPicture = fields["picture"],
            authorNip05 = fields["nip05"],
            reactionCount = reactionCount(statsId),
            replyCount = replyCount(statsId),
            repostCount = repostCount(statsId),
            zapCount = zap.count,
            relaysSeen = boundedSeenRelayHints(
                seenRelays = listOf(event.relayUrl) + event.relaysSeen,
            ),
        )

        feedRowCache[event.id] = CachedFeedRow(row, authorProfileTs, statsTs)
        feedRowAccessedAt[event.id] = System.nanoTime()
        // Gated: CHM .size per row conversion adds up during feed scans —
        // check every 64th put. Worst-case overshoot past FEED_ROW_CACHE_CAP
        // between checks is 64 entries.
        if (feedRowPutsSinceTrimCheck.incrementAndGet() >= 64) {
            feedRowPutsSinceTrimCheck.set(0)
            trimFeedRowCacheIfNeeded()
        }
        return row
    }

    private fun trimFeedRowCacheIfNeeded() {
        if (feedRowCache.size <= FEED_ROW_CACHE_CAP) return
        // Snapshot accessed-at values before sorting — feedRowAccessedAt is concurrently
        // mutated by the touch path. Without snapshot, TimSort throws IllegalArgumentException.
        val accessSnapshot = HashMap<String, Long>(feedRowAccessedAt.size)
        for ((k, v) in feedRowAccessedAt) accessSnapshot[k] = v
        val candidates = accessSnapshot.entries.sortedBy { it.value }
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
            eventBytes += estimateNostrEventRetainedBytes(event)
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
            replyIndexEntries = idsByReplyTarget.values.sumOf { it.size },
            repostCountEntries = repostCounts.size,
            reactionCountEntries = reactionsByTarget.size,
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
            relayIdentityEntries = relayIdentitiesByUrl.size,
            relaySetEntries = relaySetsByCoordinate.size,
            pendingRelayEntries = pendingRelays.size,
            profileAnchoredRefEntries = profileAnchoredIds.size,
        )
    }

    /** Snapshot + reset interval work; anchors come from the latest complete pass. */
    fun snapshotEvictionMetrics(): MesEvictionSnapshot {
        val anchors = lastEvictionAnchors
        val byKind = evictionByKind.entries
            .mapNotNull { (kind, count) ->
                count.getAndSet(0).takeIf { it > 0 }?.let { kind to it }
            }
            .toMap()
        val rejectedByKind = evictionAdmissionRejectedByKind.entries
            .mapNotNull { (kind, count) ->
                count.getAndSet(0).takeIf { it > 0 }?.let { kind to it }
            }
            .toMap()
        return MesEvictionSnapshot(
            passes = evictionPasses.getAndSet(0),
            evicted = evictionEvicted.getAndSet(0),
            tier1 = evictionTier1.getAndSet(0),
            tier2 = evictionTier2.getAndSet(0),
            tier3 = evictionTier3.getAndSet(0),
            admissionReplaced = evictionAdmissionReplaced.getAndSet(0),
            admissionRejected = evictionAdmissionRejected.getAndSet(0),
            evictedByKind = byKind,
            admissionRejectedByKind = rejectedByKind,
            anchoredOwn = anchors.own,
            anchoredMentioned = anchors.mentioned,
            anchoredViewed = anchors.viewed,
            anchoredProfileRefs = anchors.profileRefs,
            liveTimelineRefs = anchors.liveTimelineRefs,
        )
    }

    // ─── Snapshot persistence ───────────────────────────────────────────────

    private fun snapshotEventSelection(): SnapshotEventSelection {
        val own = ownPubkey
        val followed = own?.let { followsByPubkey[it] }.orEmpty()
        return selectSnapshotEventsForPersistence(
            events = eventsById.values.toList(),
            ownPubkey = own,
            followedPubkeys = followed,
            lastTouchedAt = lastTouchedAt,
        )
    }

    private fun snapshotFollowsSelection(): SnapshotFollowsSelection {
        trimFollowsAccessIndex(forceStalePrune = true)
        val own = ownPubkey
        val followed = own?.let { followsByPubkey[it] }.orEmpty()
        return selectSnapshotFollowsForPersistence(
            followsByPubkey = followsByPubkey.toMap(),
            followsCreatedAt = followsCreatedAt.toMap(),
            followsAccessedAt = followsAccessedAt.toMap(),
            ownPubkey = own,
            followedPubkeys = followed,
        )
    }

    private fun recordFollowsAccess(pubkey: String, accessedAt: Long) {
        followsAccessedAt[pubkey] = accessedAt
        if (followsAccessedAt.size > FOLLOWS_ACCESS_INDEX_CAP) {
            trimFollowsAccessIndex(forceStalePrune = false)
        }
    }

    private fun trimFollowsAccessIndex(forceStalePrune: Boolean) {
        if (!forceStalePrune && followsAccessedAt.size <= FOLLOWS_ACCESS_INDEX_CAP) return
        val accessSnapshot = followsAccessedAt.toMap()
        val toPrune = selectFollowsAccessKeysToPrune(
            accessTimes = accessSnapshot,
            livePubkeys = followsByPubkey.keys.toSet(),
            ownPubkey = ownPubkey,
        )
        for (pubkey in toPrune) {
            accessSnapshot[pubkey]?.let { captured ->
                followsAccessedAt.remove(pubkey, captured)
            }
        }
    }

    /** V2 TSV writer — TEST-ONLY. Production writes V3 binary exclusively
     *  (saveSnapshotBinary); this remains so the V2→V3 migration round-trip
     *  tests can exercise restoreSnapshotFrom. Delete together with the V2
     *  reader once migration support is dropped. */
    @androidx.annotation.VisibleForTesting
    suspend fun saveSnapshotTo(writer: BufferedWriter) {
        writer.write(SNAPSHOT_VERSION)
        writer.newLine()
        // Write follows FIRST — ~1KB, parsed in <30ms on restore.
        // FeedVM's 10s cold-start timeout needs follows before the 25s
        // event parse completes; placing follows first eliminates the race.
        writer.write("---FOLLOWS---")
        writer.newLine()
        val followsSelection = snapshotFollowsSelection()
        for (entry in followsSelection.entries) {
            writer.write(
                "follows|${entry.pubkey}|${entry.createdAt}|" +
                    entry.followedPubkeys.joinToString(","),
            )
            writer.newLine()
        }
        // Events section — explicit marker so reader can switch from follows.
        // Old snapshots (pre-marker) start events implicitly at section 0.
        writer.write("---EVENTS---")
        writer.newLine()
        val eventSelection = snapshotEventSelection()
        for (event in eventSelection.nonContentEvents) {
            writer.write(serializeEvent(event))
            writer.newLine()
        }
        for (event in eventSelection.contentEvents) {
            writer.write(serializeEvent(event))
            writer.newLine()
        }
        // Write aggregates section
        writer.write("---AGGREGATES---")
        writer.newLine()
        for ((id, count) in repostCounts) {
            writer.write("repost|$id|$count")
            writer.newLine()
        }
        // reactionCounts no longer written — reactionsByTarget is the source of truth (H10)
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
        var lineCount = 0

        reader.useLines { lines ->
            for (line in lines) {
                // Yield every 500 events so dispatcher can service other work.
                // Critical when restore runs in parallel with relay subscribe.
                if (++lineCount % 500 == 0) {
                    kotlinx.coroutines.yield()
                }
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

        // Rebuild reaction set from raw kind-7 events (same as binary path)
        reindexReactionsFromEvents()
        rebuildActionEventIndexesFromEvents()
        rebuildNotificationIndex()
        // V2 aggregates persist zap counts but no per-zap detail rows — rebuild
        // them from retained kind-9735 events so the drawer matches the summary.
        repairZapDetailsFromReceipts()

        // Bump all signals once (follows signal fires again — idempotent,
        // consumers use distinctUntilChanged or one-shot .first())
        val now = System.nanoTime()
        _feedSignal.value = now
        _profileSignal.value = now
        _statsSignal.value = now
        _followsSignal.value = now
        _trustScoreSignal.value = now
        _relayMonitorSignal.value = now
        // Own NIP-51 config (favorites kind-10012, relay sets kind-30002) is
        // materialized into favoritesByPubkey/relaySetsByCoordinate during the
        // parse loop via the snapshotDirtySink, but the sink is never flushed —
        // bump these two here so the slide-up's Eagerly flows re-read on restore.
        _relayConfigSignal.value = now
        _relaySetSignal.value = now
        _snapshotRestoredSignal.value = now
        _statsInvalidations.tryEmit(StatsInvalidation.Broadcast)

        // Evict old content events from snapshot (may contain stale data)
        evictOldContentEvents()
        rebuildContentAdmissionIndex()

        Log.d("MES", "Snapshot restore complete (EventModel parsing deferred to first read)")
    }

    // Sink for handler self-bumps during snapshot restore. The bumps are
    // discarded — restoreSnapshotFrom fires every signal once at end, so
    // per-event bumps inside the 21s parse loop only waste CPU and cause
    // mid-restore Compose recomposes.
    private val snapshotDirtySink = InsertDirty()

    // ─── Binary snapshot V3 ─────────────────────────────────────────────────
    //
    // Replaces the V2 TSV format whose 10-15s parse cost is dominated by
    // String.split + escape decoding. The binary format:
    //   - Length-prefixed UTF-8 strings (no escape decoding)
    //   - Fixed-width primitives (long, int, byte) via DataOutput*
    //   - Section-offset header for future lazy section loading
    //
    // Header layout (32 bytes, big-endian):
    //   [0..3]   magic "USNS"
    //   [4..7]   version (= 3)
    //   [8..11]  followsOffset
    //   [12..15] eventsOffset
    //   [16..19] aggregatesOffset
    //   [20..23] relayHealthOffset
    //   [24..27] eventsCount (sanity / progress)
    //   [28..31] reserved (= 0)
    //
    // V14: immediately after the 32-byte header, a length-prefixed owner pubkey
    // string (writeStr) precedes the FOLLOWS section. NOTE: the four section
    // offsets above are NOT adjusted for this owner prefix — they remain the
    // pre-V14 values and are purely informational (restore reads sequentially).
    // TODO: any future lazy-seek implementer must account for the owner prefix
    // length when seeking to followsOffset/eventsOffset/etc.
    //
    // V2 TSV files are still readable — SnapshotScheduler peeks the first
    // 4 bytes and dispatches: "USNS" → binary, anything else → V2 reader.

    internal suspend fun saveSnapshotBinary(
        out: DataOutputStream,
        snapshotVersion: Int = SNAPSHOT_BINARY_VERSION,
    ): SnapshotSectionSizes {
        require(snapshotVersion in 16..SNAPSHOT_BINARY_VERSION) {
            "Binary writer supports V16..V$SNAPSHOT_BINARY_VERSION, got V$snapshotVersion"
        }
        // Serialize each section once, compute offsets from the live buffer sizes,
        // then stream those buffers directly. This keeps peak memory near the
        // snapshot size instead of duplicating 15-20MB into contiguous arrays.
        val writeStart = out.size()

        // Capture pending intent BEFORE event selection. commitAcceptedMutePublish
        // stores its event before clearing the journal, so a concurrent writer sees
        // either the pending intent, the accepted event, or both — never neither.
        val pendingMuteSnapshot = synchronized(muteStateLock) {
            val owner = ownPubkey
            owner?.let(pendingMutePublishesByPubkey::get)?.let(::listOf).orEmpty()
        }

        val followsSelection = snapshotFollowsSelection()
        val followsBuf = ByteArrayOutputStream(8 * 1024)
        DataOutputStream(followsBuf).use { d ->
            d.writeInt(followsSelection.entries.size)
            for (entry in followsSelection.entries) {
                d.writeStr(entry.pubkey)
                d.writeLong(entry.createdAt)
                d.writeInt(entry.followedPubkeys.size)
                for (f in entry.followedPubkeys) d.writeStr(f)
            }

            // V5: Own-user engaged sets — written here (FOLLOWS section) so they
            // restore before the 17K-event parse, giving instant icon state.
            val ownPk = ownPubkey
            for (index in listOf(reactedTargetsByActor, repostedTargetsByActor, zappedTargetsByActor)) {
                val snapshot = if (ownPk != null) index[ownPk]?.toSet() ?: emptySet() else emptySet()
                val capped = if (snapshot.size <= PERSISTED_ENGAGED_CAP) snapshot.size else PERSISTED_ENGAGED_CAP
                d.writeInt(capped)
                var written = 0
                for (id in snapshot) { if (++written > PERSISTED_ENGAGED_CAP) break; d.writeStr(id) }
            }

            // V17: one raw kind-3 for the signed-in account. Other users remain
            // derived-only; legacy V16 snapshots end this section above.
            if (snapshotVersion >= 17) {
                val retained = getOwnContactListEvent()
                    ?.takeIf { it.kind == 3 && it.pubkey == ownPubkey }
                d.writeBoolean(retained != null)
                if (retained != null) d.writeEventBinary(retained)
            }
        }

        // Keep all own/followed control-plane rows plus the hottest remaining
        // rows, and a separate newest-content warm cache. Relay subscriptions
        // backfill everything beyond these persistence-only bounds.
        val eventSelection = snapshotEventSelection()
        val totalEvents =
            eventSelection.nonContentEvents.size + eventSelection.contentEvents.size
        val selectedContentEventIds = eventSelection.contentEvents
            .mapTo(HashSet(eventSelection.contentEvents.size)) { it.id }

        val eventsBuf = ByteArrayOutputStream(2 * 1024 * 1024)
        DataOutputStream(eventsBuf).use { d ->
            d.writeInt(totalEvents)
            for (event in eventSelection.nonContentEvents) d.writeEventBinary(event)
            for (event in eventSelection.contentEvents) d.writeEventBinary(event)
        }

        val aggregatesBuf = ByteArrayOutputStream(64 * 1024)
        DataOutputStream(aggregatesBuf).use { d ->
            // Snapshot each ConcurrentHashMap to an immutable copy BEFORE
            // writing the pre-count and iterating. CHM's iteration is
            // weakly-consistent with respect to .size: under concurrent
            // inserts (which happen continuously while events arrive from
            // relays during a save), the iterator can produce a different
            // number of entries than the size we just recorded. The reader
            // then reads garbage when it consumes the next field — observed
            // in production as 'Invalid string length: 1631139890'.
            // Legacy reply-count field: keep the zero marker for V3-V17 wire
            // compatibility. Counts are rebuilt from unique retained reply IDs.
            d.writeInt(0)
            val reposts = repostCounts.toMap()
            d.writeInt(reposts.size)
            for ((id, count) in reposts) { d.writeStr(id); d.writeInt(count) }
            // reactionCounts: write 0 entries (legacy field, reactionsByTarget is source of truth)
            d.writeInt(0)
            val zaps = zapStatsByEventId.toMap()
            d.writeInt(zaps.size)
            for ((id, zap) in zaps) {
                d.writeStr(id); d.writeInt(zap.count); d.writeLong(zap.totalSats)
            }

            // V6: Engagement contributor indexes
            val repostContribs = repostPubkeysByTarget.mapValues { it.value.toSet() }
            d.writeInt(repostContribs.size)
            for ((id, pks) in repostContribs) {
                d.writeStr(id); d.writeInt(pks.size)
                for (pk in pks) d.writeStr(pk)
            }
            val reactionContribs = reactionsByTarget.mapValues { it.value.toSet() }
            d.writeInt(reactionContribs.size)
            for ((id, infos) in reactionContribs) {
                d.writeStr(id); d.writeInt(infos.size)
                for (info in infos) {
                    d.writeStr(info.pubkey)
                    when (val c = info.content) {
                        is ReactionContent.Standard -> {
                            d.writeByte(0)
                            d.writeStr(c.emoji)
                        }
                        is ReactionContent.Custom -> {
                            d.writeByte(1)
                            d.writeStr(c.shortcode)
                            d.writeStr(c.url)
                        }
                    }
                }
            }
            val zapContribs = zapDetailsByTarget.mapValues { it.value.toList() }
            d.writeInt(zapContribs.size)
            for ((id, details) in zapContribs) {
                d.writeStr(id); d.writeInt(details.size)
                for (z in details) {
                    d.writeStrOrNull(z.senderPubkey); d.writeLong(z.sats)
                    d.writeStrOrNull(z.comment); d.writeStrOrNull(z.eventId)
                }
            }

            // V7: Blossom server lists (kind 10063)
            val blossomServers = blossomServersByPubkey.toMap()
            d.writeInt(blossomServers.size)
            for ((pubkey, servers) in blossomServers) {
                d.writeStr(pubkey); d.writeInt(servers.size)
                for (url in servers) d.writeStr(url)
            }

            // V8: NIP-30 emoji sets (kind 30030)
            val emojiSets = emojiSetsByCoordinate.toMap()
            d.writeInt(emojiSets.size)
            for ((_, set) in emojiSets) {
                d.writeStr(set.authorPubkey)
                d.writeStr(set.setName)
                d.writeStrOrNull(set.title)
                d.writeInt(set.emojis.size)
                for (e in set.emojis) {
                    d.writeStr(e.shortcode)
                    d.writeStr(e.url)
                }
                d.writeLong(set.updatedAt)
            }

            // V8: NIP-30 user emoji lists (kind 10030)
            val emojiLists = userEmojiListByPubkey.toMap()
            d.writeInt(emojiLists.size)
            for ((_, list) in emojiLists) {
                d.writeStr(list.pubkey)
                d.writeInt(list.setRefs.size)
                for (ref in list.setRefs) {
                    d.writeStr(ref.authorPubkey)
                    d.writeStr(ref.setName)
                    d.writeStrOrNull(ref.hintRelay)
                }
                d.writeInt(list.inlineEmojis.size)
                for (e in list.inlineEmojis) {
                    d.writeStr(e.shortcode)
                    d.writeStr(e.url)
                }
                d.writeLong(list.updatedAt)
            }
        }

        val relayHealthBuf = ByteArrayOutputStream(64 * 1024)
        DataOutputStream(relayHealthBuf).use { d ->
            // Same concurrent-modification hazard as aggregates above.
            // Snapshot before count + iterate.
            val trustScores = trustScoresByUrl.toMap()
            d.writeInt(trustScores.size)
            for ((url, ts) in trustScores) {
                d.writeStr(url)
                d.writeInt(ts.score); d.writeInt(ts.reliability)
                d.writeInt(ts.quality); d.writeInt(ts.accessibility)
                d.writeStr(ts.confidence); d.writeInt(ts.observations)
                d.writeStrOrNull(ts.policy)
                d.writeStrOrNull(ts.countryCode)
                d.writeStrOrNull(ts.operatorVerified)
                d.writeLong(ts.updatedAt)
            }
            val monitors = relayMonitorsByUrl.toMap()
            d.writeInt(monitors.size)
            for ((url, m) in monitors) {
                d.writeStr(url)
                d.writeIntOrNull(m.rttOpen)
                d.writeIntOrNull(m.rttRead)
                d.writeIntOrNull(m.rttWrite)
                d.writeStr(m.monitorPubkey)
                d.writeLong(m.createdAt)
                d.writeStrOrNull(m.network)
                d.writeStrOrNull(m.geohash)
                d.writeStrOrNull(m.iconUrl)
                d.writeInt(m.supportedNips.size)
                for (n in m.supportedNips) d.writeInt(n)
            }
        }

        // ── Timelines (V12+) ────────────────────────────────────────────
        val timelinesBuf = ByteArrayOutputStream(64 * 1024)
        DataOutputStream(timelinesBuf).use { d ->
            val timelineEntries = timelineServiceProvider.get().snapshotData(selectedContentEventIds)
            d.writeInt(timelineEntries.size)
            for ((key, timeline) in timelineEntries) {
                d.writeStr(key)
                d.writeInt(timeline.urls.size)
                for (url in timeline.urls) d.writeStr(url)
                d.writeFilter(timeline.filter)
                d.writeInt(timeline.refs.size)
                for (ref in timeline.refs) {
                    d.writeStr(ref.id)
                    d.writeLong(ref.createdAt)
                }
            }
        }

        val followsOffset = SNAPSHOT_HEADER_SIZE
        val eventsOffset = followsOffset + followsBuf.size()
        val aggregatesOffset = eventsOffset + eventsBuf.size()
        val relayHealthOffset = aggregatesOffset + aggregatesBuf.size()
        val timelinesOffset = relayHealthOffset + relayHealthBuf.size()

        // Header
        out.write(SNAPSHOT_BINARY_MAGIC)
        out.writeInt(snapshotVersion)
        out.writeInt(followsOffset)
        out.writeInt(eventsOffset)
        out.writeInt(aggregatesOffset)
        out.writeInt(relayHealthOffset)
        out.writeInt(totalEvents)
        out.writeInt(timelinesOffset) // was reserved; V12+ carries timelines offset

        // V14 owner stamp — length-prefixed pubkey directly after the header.
        // The section offsets above intentionally do NOT include this prefix
        // (informational only; see header-layout comment TODO).
        out.writeStr(ownPubkey ?: "")
        val headerBytes = out.size() - writeStart

        // Sections in offset order.
        followsBuf.writeTo(out)
        eventsBuf.writeTo(out)
        aggregatesBuf.writeTo(out)
        relayHealthBuf.writeTo(out)
        timelinesBuf.writeTo(out)

        // V15 (appended after timelines; not in the informational offset table) —
        // own outgoing private-zap anon pubkeys, for cross-session self-recognition.
        val anons = synchronized(ownAnonZapPubkeys) { ownAnonZapPubkeys.toList() }
        out.writeInt(anons.size)
        for (a in anons) out.writeStr(a)

        // V16: device-authoritative relay identity cache. Appended at the tail so every
        // V3/V5-V15 section remains byte-compatible and older snapshots still restore.
        val relayIdentities = relayIdentitiesByUrl.toMap()
        out.writeInt(relayIdentities.size)
        for ((url, identity) in relayIdentities) {
            out.writeStr(url)
            out.writeStrOrNull(identity.name)
            out.writeStrOrNull(identity.iconUrl)
            out.writeLong(identity.fetchedAt)
        }

        // V18: local mute intent not yet acknowledged by any relay. Values are
        // stored in the app-private, OS-encrypted snapshot so they survive a hard
        // kill without pretending an unacknowledged event is canonical.
        if (snapshotVersion >= 18) {
            out.writeInt(pendingMuteSnapshot.size)
            for (pending in pendingMuteSnapshot) out.writePendingMutePublish(pending)
        }

        // V19: long-lived NIP-05 verification cache. Each entry is framed so
        // one malformed record can be dropped without losing the full snapshot.
        if (snapshotVersion >= 19) {
            val nip05Entries = nip05VerificationSnapshot()
            out.writeInt(nip05Entries.size)
            for (entry in nip05Entries) {
                val record = encodeNip05VerificationCacheRecord(entry)
                out.writeInt(record.size)
                out.write(record)
            }
        }
        val totalBytes = out.size() - writeStart
        val knownSectionBytes = headerBytes +
            followsBuf.size() +
            eventsBuf.size() +
            aggregatesBuf.size() +
            relayHealthBuf.size() +
            timelinesBuf.size()
        return SnapshotSectionSizes(
            headerBytes = headerBytes,
            followsBytes = followsBuf.size(),
            eventsBytes = eventsBuf.size(),
            aggregatesBytes = aggregatesBuf.size(),
            relayHealthBytes = relayHealthBuf.size(),
            timelinesBytes = timelinesBuf.size(),
            tailBytes = totalBytes - knownSectionBytes,
            totalBytes = totalBytes,
            eventCount = totalEvents,
            nonContentEventCount = eventSelection.nonContentEvents.size,
            nonContentCandidateCount = eventSelection.nonContentCandidateCount,
            anchoredNonContentCount = eventSelection.anchoredNonContentCount,
            contentEventCount = eventSelection.contentEvents.size,
            contentCandidateCount = eventSelection.contentCandidateCount,
            anchoredOwnProfileContentCount = eventSelection.anchoredOwnProfileContentCount,
            ownProfileContentCandidateCount = eventSelection.ownProfileContentCandidateCount,
            followsEntryCount = followsSelection.entries.size,
            followsCandidateCount = followsSelection.candidateCount,
            anchoredFollowsCount = followsSelection.anchoredCount,
        )
    }

    suspend fun restoreSnapshotBinary(input: DataInputStream) {
        val magic = ByteArray(4)
        input.readFully(magic)
        if (!magic.contentEquals(SNAPSHOT_BINARY_MAGIC)) {
            throw IOException("Invalid snapshot magic: " +
                magic.joinToString("") { "%02x".format(it) })
        }
        val version = input.readInt()
        if (version != 3 && version !in 5..SNAPSHOT_BINARY_VERSION) {
            throw IOException("Unsupported snapshot version: $version (expected 3 or 5+)")
        }
        // Section offsets — currently informational; we read sections
        // sequentially below. Future lazy-load can seek to these offsets
        // to read FOLLOWS first and defer EVENTS.
        input.readInt() // followsOffset
        input.readInt() // eventsOffset
        input.readInt() // aggregatesOffset
        input.readInt() // relayHealthOffset
        val declaredEventsCount = input.readInt()
        input.readInt() // reserved

        // V14 owner stamp — reject a snapshot belonging to a different account
        // BEFORE any MES insertion (store stays empty on mismatch). ≤V13 files
        // have no owner field and are trusted as legacy (restamped on next save).
        if (version >= 14) {
            val owner = input.readStr()
            val current = ownPubkey
            if (owner.isNotEmpty() && current != null && owner != current) {
                throw SnapshotOwnerMismatchException(owner, current)
            }
        }

        // FOLLOWS section
        ownContactListEvent = null
        val followsCount = input.readInt()
        if (followsCount < 0 || followsCount > 100_000) {
            throw IOException("Invalid follows count: $followsCount")
        }
        for (i in 0 until followsCount) {
            val pubkey = input.readStr()
            val createdAt = input.readLong()
            val followCount = input.readInt()
            if (followCount < 0 || followCount > 1_000_000) {
                throw IOException("Invalid follow count: $followCount")
            }
            val pks = HashSet<String>(followCount)
            for (j in 0 until followCount) pks.add(input.readStr())
            followsByPubkey[pubkey] = pks
            followsCreatedAt[pubkey] = createdAt
            recordFollowsAccess(pubkey, createdAt * 1_000L)
        }

        // V5+: Own-user engaged sets — in FOLLOWS section for instant icon
        // state before the 17K-event parse. (V4 had them in AGGREGATES.)
        if (version >= 5) {
            val ownPk = ownPubkey
            for ((index, label) in listOf(
                reactedTargetsByActor to "reacted",
                repostedTargetsByActor to "reposted",
                zappedTargetsByActor to "zapped",
            )) {
                val n = input.readInt()
                if (n < 0 || n > PERSISTED_ENGAGED_CAP) throw IOException("Invalid $label count: $n")
                if (ownPk != null && n > 0) {
                    val set: MutableSet<String> = ConcurrentHashMap.newKeySet()
                    for (i in 0 until n) set.add(input.readStr())
                    index[ownPk] = set
                } else {
                    for (i in 0 until n) input.readStr()
                }
            }
        }

        // V17: retained raw owner kind-3. Its p-tags/content are metadata only;
        // the materialized follows set above remains authoritative.
        if (version >= 17 && input.readBoolean()) {
            val retained = input.readEventBinary(version)
            val owner = ownPubkey
            if (retained.kind != 3 || owner == null || retained.pubkey != owner) {
                throw IOException("Invalid retained owner contact list")
            }
            ownContactListEvent = retained.copyForContactListRetention()
        }

        // Fire follows + action signals early so FeedVM cold-start resolves
        // and engagement icons light up before the events parse completes.
        if (followsByPubkey.isNotEmpty()) {
            _followsSignal.value = System.nanoTime()
        }
        _actionSignal.value = System.nanoTime()

        // EVENTS section
        val eventsCount = input.readInt()
        if (eventsCount < 0 || eventsCount > 1_000_000) {
            throw IOException("Invalid events count: $eventsCount")
        }
        var lineCount = 0
        for (i in 0 until eventsCount) {
            if (++lineCount % 500 == 0) kotlinx.coroutines.yield()
            val event = input.readEventBinary(version)
            insertFromSnapshot(event)
        }
        rebuildActionEventIndexesFromEvents()
        rebuildNotificationIndex()

        // AGGREGATES section. V3-V17 scalar reply counts were inflatable when
        // a reply payload was evicted and fetched again. Consume them only to
        // advance the stream; the live index was rebuilt from retained events.
        val replyN = input.readInt()
        if (replyN < 0 || replyN > 5_000_000) throw IOException("Invalid reply count: $replyN")
        for (i in 0 until replyN) { input.readStr(); input.readInt() }
        val repostN = input.readInt()
        if (repostN < 0 || repostN > 5_000_000) throw IOException("Invalid repost count: $repostN")
        for (i in 0 until repostN) {
            val id = input.readStr(); val c = input.readInt()
            repostCounts[id] = c
        }
        // reactionCounts: skip legacy entries to advance stream (no longer stored)
        val reactionN = input.readInt()
        if (reactionN < 0 || reactionN > 5_000_000) throw IOException("Invalid reaction count: $reactionN")
        for (i in 0 until reactionN) { input.readStr(); input.readInt() }
        val zapN = input.readInt()
        if (zapN < 0 || zapN > 5_000_000) throw IOException("Invalid zap count: $zapN")
        for (i in 0 until zapN) {
            val id = input.readStr()
            val c = input.readInt()
            val sats = input.readLong()
            zapStatsByEventId[id] = ZapAggregate(c, sats)
        }

        // V6: Engagement contributor indexes (absent in V5 snapshots)
        if (version >= 6) {
            val repostContribN = input.readInt()
            if (repostContribN < 0 || repostContribN > 5_000_000) throw IOException("Invalid repost contrib count: $repostContribN")
            for (i in 0 until repostContribN) {
                val id = input.readStr(); val n = input.readInt()
                val set: MutableSet<String> = ConcurrentHashMap.newKeySet()
                for (j in 0 until n) set.add(input.readStr())
                repostPubkeysByTarget[id] = set
            }
            val reactionContribN = input.readInt()
            if (reactionContribN < 0 || reactionContribN > 5_000_000) throw IOException("Invalid reaction contrib count: $reactionContribN")
            for (i in 0 until reactionContribN) {
                val id = input.readStr(); val n = input.readInt()
                val set: MutableSet<ReactionInfo> = ConcurrentHashMap.newKeySet()
                if (version >= 9) {
                    for (j in 0 until n) {
                        val pk = input.readStr()
                        val disc = input.readByte().toInt()
                        val content = if (disc == 1) {
                            ReactionContent.Custom(input.readStr(), input.readStr())
                        } else {
                            ReactionContent.Standard(input.readStr())
                        }
                        set.add(ReactionInfo(pk, content))
                    }
                } else {
                    // V8 and earlier: (pubkey, emoji) pairs → Standard
                    for (j in 0 until n) {
                        set.add(ReactionInfo(input.readStr(), ReactionContent.Standard(input.readStr())))
                    }
                }
                reactionsByTarget[id] = set
            }
            val zapContribN = input.readInt()
            if (zapContribN < 0 || zapContribN > 5_000_000) throw IOException("Invalid zap contrib count: $zapContribN")
            for (i in 0 until zapContribN) {
                val id = input.readStr(); val n = input.readInt()
                val list = java.util.Collections.synchronizedList(mutableListOf<ZapDetail>())
                for (j in 0 until n) {
                    val sender = if (version >= 10) input.readStrOrNull() else input.readStr()
                    val sats = input.readLong()
                    val comment = input.readStrOrNull()
                    val eventId = if (version >= 11) input.readStrOrNull() else null
                    list.add(ZapDetail(sender, sats, comment, eventId))
                }
                zapDetailsByTarget[id] = list
            }
        }

        // V7: Blossom server lists (absent in V5-V6 snapshots)
        if (version >= 7) {
            val blossomN = input.readInt()
            if (blossomN < 0 || blossomN > 100_000) throw IOException("Invalid blossom server count: $blossomN")
            for (i in 0 until blossomN) {
                val pubkey = input.readStr(); val n = input.readInt()
                val servers = ArrayList<String>(n)
                for (j in 0 until n) servers.add(input.readStr())
                blossomServersByPubkey[pubkey] = servers
            }
        }

        // V8: NIP-30 emoji sets + user emoji lists (absent in V5-V7 snapshots)
        if (version >= 8) {
            val emojiSetN = input.readInt()
            if (emojiSetN < 0 || emojiSetN > 100_000) throw IOException("Invalid emoji set count: $emojiSetN")
            for (i in 0 until emojiSetN) {
                val authorPubkey = input.readStr()
                val setName = input.readStr()
                val title = input.readStrOrNull()
                val emojiN = input.readInt()
                if (emojiN < 0 || emojiN > 10_000) throw IOException("Invalid emoji count: $emojiN")
                val emojis = ArrayList<CustomEmoji>(emojiN)
                for (j in 0 until emojiN) emojis.add(CustomEmoji(input.readStr(), input.readStr()))
                val updatedAt = input.readLong()
                emojiSetsByCoordinate[authorPubkey to setName] = EmojiSetEntity(
                    authorPubkey = authorPubkey, setName = setName,
                    title = title, emojis = emojis, updatedAt = updatedAt,
                )
                emojiKindCreatedAt["$authorPubkey:30030:$setName"] = updatedAt
            }

            val emojiListN = input.readInt()
            if (emojiListN < 0 || emojiListN > 100_000) throw IOException("Invalid emoji list count: $emojiListN")
            for (i in 0 until emojiListN) {
                val pubkey = input.readStr()
                val refN = input.readInt()
                if (refN < 0 || refN > 10_000) throw IOException("Invalid emoji set ref count: $refN")
                val refs = ArrayList<EmojiSetRef>(refN)
                for (j in 0 until refN) {
                    refs.add(EmojiSetRef(input.readStr(), input.readStr(), input.readStrOrNull()))
                }
                val inlineN = input.readInt()
                if (inlineN < 0 || inlineN > 10_000) throw IOException("Invalid inline emoji count: $inlineN")
                val inline = ArrayList<CustomEmoji>(inlineN)
                for (j in 0 until inlineN) inline.add(CustomEmoji(input.readStr(), input.readStr()))
                val updatedAt = input.readLong()
                userEmojiListByPubkey[pubkey] = UserEmojiListEntity(
                    pubkey = pubkey, setRefs = refs,
                    inlineEmojis = inline, updatedAt = updatedAt,
                )
                emojiKindCreatedAt["$pubkey:10030"] = updatedAt
            }
        }

        // RELAY_HEALTH section
        val trustN = input.readInt()
        if (trustN < 0 || trustN > 100_000) throw IOException("Invalid trust count: $trustN")
        for (i in 0 until trustN) {
            val url = input.readStr()
            val score = input.readInt(); val reliability = input.readInt()
            val quality = input.readInt(); val accessibility = input.readInt()
            val confidence = input.readStr(); val observations = input.readInt()
            val policy = input.readStrOrNull()
            val countryCode = input.readStrOrNull()
            val operatorVerified = input.readStrOrNull()
            val updatedAt = input.readLong()
            trustScoresByUrl[url] = RelayTrustScoreEntity(
                relayUrl = url,
                score = score, reliability = reliability,
                quality = quality, accessibility = accessibility,
                confidence = confidence, observations = observations,
                policy = policy, countryCode = countryCode,
                operatorVerified = operatorVerified, updatedAt = updatedAt,
            )
        }
        val monitorN = input.readInt()
        if (monitorN < 0 || monitorN > 100_000) throw IOException("Invalid monitor count: $monitorN")
        for (i in 0 until monitorN) {
            val url = input.readStr()
            val rttOpen = input.readIntOrNull()
            val rttRead = input.readIntOrNull()
            val rttWrite = input.readIntOrNull()
            val monitorPubkey = input.readStr()
            val createdAt = input.readLong()
            val network = input.readStrOrNull()
            val geohash = input.readStrOrNull()
            val iconUrl = input.readStrOrNull()
            val nipsN = input.readInt()
            if (nipsN < 0 || nipsN > 1_000) throw IOException("Invalid NIPs count: $nipsN")
            val nips = ArrayList<Int>(nipsN)
            for (j in 0 until nipsN) nips.add(input.readInt())
            relayMonitorsByUrl[url] = RelayMonitorEntity(
                relayUrl = url,
                rttOpen = rttOpen, rttRead = rttRead, rttWrite = rttWrite,
                supportedNips = nips,
                network = network,
                requirements = emptyList(), // V2 didn't persist this either; populated live
                geohash = geohash,
                iconUrl = iconUrl,
                monitorPubkey = monitorPubkey,
                createdAt = createdAt,
            )
        }

        // ── Timelines (V12+) ────────────────────────────────────────────
        if (version >= 12) {
            val n = input.readInt()
            if (n < 0 || n > 10_000) throw IOException("Invalid timeline count: $n")
            val restored = HashMap<String, TimelineService.Timeline>(n)
            for (i in 0 until n) {
                val key = input.readStr()
                val urlCount = input.readInt()
                if (urlCount < 0 || urlCount > 1_000) throw IOException("Invalid timeline url count: $urlCount")
                val urls = List(urlCount) { input.readStr() }
                val filter = input.readFilter()
                val refCount = input.readInt()
                if (refCount < 0 || refCount > 100_000) throw IOException("Invalid timeline ref count: $refCount")
                val refs = List(refCount) {
                    TimelineRef(input.readStr(), input.readLong())
                }
                restored[key] = TimelineService.Timeline(refs = refs, filter = filter, urls = urls)
            }
            timelineServiceProvider.get().restoreFromSnapshot(restored)
        }

        // V15: own private-zap anon pubkeys (appended after timelines). Restore
        // BEFORE the repair pass so it can promote our own anon receipts → own.
        if (version >= 15) {
            val anonCount = input.readInt()
            if (anonCount < 0 || anonCount > 1_000_000) throw IOException("Invalid anon-zap count: $anonCount")
            for (i in 0 until anonCount) addOwnAnonZap(input.readStr())
        }

        // V16: persisted device NIP-11 relay identities (absent in older snapshots).
        if (version >= 16) {
            val identityCount = input.readInt()
            if (identityCount < 0 || identityCount > RELAY_IDENTITY_CAP) {
                throw IOException("Invalid relay identity count: $identityCount")
            }
            for (i in 0 until identityCount) {
                val url = normalizeRelayUrl(input.readStr())
                val name = input.readStrOrNull()
                val iconUrl = input.readStrOrNull()
                val fetchedAt = input.readLong()
                if (url != null) {
                    relayIdentitiesByUrl[url] = RelayIdentityEntity(
                        relayUrl = url,
                        name = name?.trim()?.takeIf(String::isNotBlank),
                        iconUrl = iconUrl?.trim()?.takeIf(String::isNotBlank),
                        fetchedAt = fetchedAt,
                    )
                }
            }
        }

        // V18: apply the durable journal after raw kind-10000 events have rebuilt
        // their authoritative state. In-process edits made during the background
        // restore are newer and win per target.
        if (version >= 18) {
            val pendingOwnerCount = input.readInt()
            if (pendingOwnerCount < 0 || pendingOwnerCount > PENDING_MUTE_OWNER_CAP) {
                throw IOException("Invalid pending mute owner count: $pendingOwnerCount")
            }
            repeat(pendingOwnerCount) {
                val restoredPending = input.readPendingMutePublish()
                val owner = ownPubkey
                if (owner != null && restoredPending.ownerPubkey == owner) {
                    synchronized(muteStateLock) {
                        val live = pendingMutePublishesByPubkey[owner]
                        val merged = if (live == null) restoredPending else {
                            mergePendingMutePublishes(restoredPending, live)
                        }
                        pendingMutePublishesByPubkey[owner] = merged
                        muteListsByPubkey[owner] =
                            merged.applyTo(muteListsByPubkey[owner] ?: emptyMuteList())
                    }
                }
            }
            _muteListSignal.value = System.nanoTime()
        }

        if (version >= 19) {
            val entryCount = input.readInt()
            if (entryCount < 0 || entryCount > NIP05_CACHE_CAP) {
                throw IOException("Invalid NIP-05 cache count: $entryCount")
            }
            val nowMs = System.currentTimeMillis()
            repeat(entryCount) {
                val recordLength = input.readInt()
                if (recordLength < 0 || recordLength > MAX_SNAPSHOT_STR_LEN) {
                    throw IOException("Invalid NIP-05 cache record length: $recordLength")
                }
                val record = ByteArray(recordLength)
                input.readFully(record)
                if (recordLength <= NIP05_CACHE_RECORD_MAX_BYTES) {
                    restoreNip05VerificationCacheRecord(record, nowMs)
                }
            }
        }

        // Reindex kind-7 reactions from raw events so the widened shortcode regex
        // reclassifies old Standard(":shortcode:") entries as Custom(shortcode, url).
        reindexReactionsFromEvents()
        // Rebuild zap detail rows from retained kind-9735 events so the drawer
        // matches the persisted summary (the detail section can drift from the
        // aggregate section across saves; insertFromSnapshot skips handleZapReceipt).
        repairZapDetailsFromReceipts()

        // Notification recipients were rebuilt after the event section so
        // e-tag-only poll responses resolve regardless of snapshot event order.

        // End-of-restore signal bumps (matches V2 reader)
        val now = System.nanoTime()
        _feedSignal.value = now
        _profileSignal.value = now
        _statsSignal.value = now
        _followsSignal.value = now
        _actionSignal.value = now
        _trustScoreSignal.value = now
        _relayMonitorSignal.value = now
        _relayIdentitySignal.value = now
        _emojiSetSignal.value = now
        // Own NIP-51 config (favorites kind-10012, relay sets kind-30002) is
        // materialized via the snapshotDirtySink during the parse loop but the
        // sink is never flushed — bump these so the slide-up re-reads on restore.
        _relayConfigSignal.value = now
        _relaySetSignal.value = now
        _snapshotRestoredSignal.value = now
        _statsInvalidations.tryEmit(StatsInvalidation.Broadcast)

        evictOldContentEvents()
        rebuildContentAdmissionIndex()

        Log.d("MES", "Snapshot restore complete (binary V$version, $declaredEventsCount events declared, EventModel parsing deferred)")
    }

    private fun DataOutputStream.writeEventBinary(e: NostrEvent) {
        writeLong(e.createdAt)
        writeInt(e.kind)
        writeStr(e.id)
        writeStr(e.pubkey)
        writeStr(e.sig)
        writeStr(e.content)
        writeStr(e.relayUrl)
        writeInt(e.tags.size)
        for (tag in e.tags) {
            writeInt(tag.size)
            for (item in tag) writeStr(item)
        }
        // V13+ wire slot retained for snapshot compatibility. The JSON is no
        // longer kept in memory; serialize only at this persistence seam.
        writeStr(tagsToJson(e.tags))
        var flags = 0
        if (e.replyToId != null) flags = flags or 0x01
        if (e.rootId != null) flags = flags or 0x02
        if (e.hasContentWarning) flags = flags or 0x04
        if (e.contentWarningReason != null) flags = flags or 0x08
        writeByte(flags)
        if (e.replyToId != null) writeStr(e.replyToId)
        if (e.rootId != null) writeStr(e.rootId)
        if (e.contentWarningReason != null) writeStr(e.contentWarningReason)
        writeLong(e.firstSeenAt)
        // relaysSeen is a Set<String> (ConcurrentHashMap-backed); snapshot
        // a list now to avoid surprises if it mutates during iteration.
        val seenSnapshot = e.relaysSeen.toList()
        writeInt(seenSnapshot.size)
        for (r in seenSnapshot) writeStr(r)
    }

    private fun DataInputStream.readEventBinary(version: Int): NostrEvent {
        val createdAt = readLong()
        val kind = readInt()
        val id = readStr()
        val pubkey = readStr()
        val sig = readStr()
        val content = readStr()
        val relayUrl = readStr()
        val tagsCount = readInt()
        if (tagsCount < 0 || tagsCount > 10_000) {
            throw IOException("Invalid tag count: $tagsCount")
        }
        val tags = ArrayList<List<String>>(tagsCount)
        for (i in 0 until tagsCount) {
            val itemCount = readInt()
            if (itemCount < 0 || itemCount > 10_000) {
                throw IOException("Invalid tag item count: $itemCount")
            }
            val tag = ArrayList<String>(itemCount)
            for (j in 0 until itemCount) tag.add(readStr())
            tags.add(tag)
        }
        // V13+ snapshots contain a redundant serialized copy. Consume it to
        // preserve the wire cursor, then discard it in favour of parsed tags.
        if (version >= 13) readStr()
        val flags = readByte().toInt() and 0xff
        val replyToId = if (flags and 0x01 != 0) readStr() else null
        val rootId = if (flags and 0x02 != 0) readStr() else null
        val hasCW = flags and 0x04 != 0
        val cwReason = if (flags and 0x08 != 0) readStr() else null
        val firstSeenAt = readLong()
        val seenCount = readInt()
        if (seenCount < 0 || seenCount > 10_000) {
            throw IOException("Invalid relaysSeen count: $seenCount")
        }
        val relaysSeen = ConcurrentHashMap.newKeySet<String>()
        for (i in 0 until seenCount) relaysSeen.add(readStr())

        return NostrEvent(
            id = id,
            pubkey = pubkey,
            kind = kind,
            content = content,
            createdAt = createdAt,
            tags = tags,
            sig = sig,
            relayUrl = relayUrl,
            replyToId = replyToId,
            rootId = rootId,
            hasContentWarning = hasCW,
            contentWarningReason = cwReason,
            firstSeenAt = firstSeenAt,
            relaysSeen = relaysSeen,
        ).withParsedRepostMetadata()
    }

    private fun DataOutputStream.writePendingMutePublish(pending: PendingMutePublish) {
        writeStr(pending.ownerPubkey)
        writeLong(pending.revision)
        writeMuteChanges(pending.userChanges)
        writeMuteChanges(pending.wordChanges)
        writeMuteChanges(pending.hashtagChanges)
    }

    internal fun encodeNip05VerificationCacheRecord(
        entry: Nip05VerificationCacheEntry,
    ): ByteArray {
        val bytes = ByteArrayOutputStream(256)
        DataOutputStream(bytes).use { record ->
            record.writeByte(1) // record format version
            record.writeStr(entry.key.pubkey)
            record.writeStr(entry.key.nip05)
            record.writeByte(
                when (entry.status) {
                    Nip05VerificationStatus.VERIFIED -> 1
                    Nip05VerificationStatus.UNVERIFIED -> 2
                    Nip05VerificationStatus.UNKNOWN -> 0
                },
            )
            record.writeLong(entry.checkedAtMs)
            record.writeStrOrNull(entry.resolvedPubkey)
        }
        return bytes.toByteArray()
    }

    internal fun restoreNip05VerificationCacheRecord(
        recordBytes: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val entry = runCatching {
            DataInputStream(ByteArrayInputStream(recordBytes)).use { record ->
                if (record.readUnsignedByte() != 1) return@use null
                val key = nip05VerificationCacheKey(record.readStr(), record.readStr())
                    ?: return@use null
                val status = when (record.readUnsignedByte()) {
                    1 -> Nip05VerificationStatus.VERIFIED
                    2 -> Nip05VerificationStatus.UNVERIFIED
                    else -> return@use null
                }
                val checkedAtMs = record.readLong()
                val resolvedPubkey = record.readStrOrNull()
                if (record.available() != 0) return@use null
                Nip05VerificationCacheEntry(
                    key = key,
                    status = status,
                    checkedAtMs = checkedAtMs,
                    resolvedPubkey = resolvedPubkey,
                )
            }
        }.getOrNull() ?: return false
        return storeNip05Verification(entry, nowMs)
    }

    private fun DataOutputStream.writeMuteChanges(changes: Map<String, Boolean>) {
        require(changes.size <= PENDING_MUTE_CHANGE_CAP)
        writeInt(changes.size)
        for ((value, muted) in changes) {
            writeStr(value)
            writeBoolean(muted)
        }
    }

    private fun DataInputStream.readPendingMutePublish(): PendingMutePublish {
        val owner = readStr()
        val revision = readLong()
        if (revision < 0L) throw IOException("Invalid pending mute revision: $revision")
        val users = readMuteChanges("user")
        val words = readMuteChanges("word")
        val hashtags = readMuteChanges("hashtag")
        val total = users.size + words.size + hashtags.size
        if (total > PENDING_MUTE_CHANGE_CAP) {
            throw IOException("Invalid pending mute change count: $total")
        }
        return PendingMutePublish(
            ownerPubkey = owner,
            revision = revision,
            userChanges = users,
            wordChanges = words,
            hashtagChanges = hashtags,
        )
    }

    private fun DataInputStream.readMuteChanges(label: String): Map<String, Boolean> {
        val count = readInt()
        if (count < 0 || count > PENDING_MUTE_CHANGE_CAP) {
            throw IOException("Invalid pending mute $label count: $count")
        }
        return LinkedHashMap<String, Boolean>(count).apply {
            repeat(count) { put(readStr(), readBoolean()) }
        }
    }

    private fun DataOutputStream.writeStr(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readStr(): String {
        val len = readInt()
        if (len < 0 || len > MAX_SNAPSHOT_STR_LEN) {
            throw IOException("Invalid string length: $len")
        }
        val bytes = ByteArray(len)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataOutputStream.writeStrOrNull(s: String?) {
        if (s == null) {
            writeBoolean(false)
        } else {
            writeBoolean(true)
            writeStr(s)
        }
    }

    private fun DataInputStream.readStrOrNull(): String? =
        if (readBoolean()) readStr() else null

    private fun DataOutputStream.writeIntOrNull(i: Int?) {
        if (i == null) {
            writeBoolean(false)
        } else {
            writeBoolean(true)
            writeInt(i)
        }
    }

    private fun DataInputStream.readIntOrNull(): Int? =
        if (readBoolean()) readInt() else null

    private fun DataOutputStream.writeFilter(filter: NostrFilter) {
        var flags = 0
        if (filter.kinds   != null) flags = flags or 0x01
        if (filter.authors != null) flags = flags or 0x02
        if (filter.ids     != null) flags = flags or 0x04
        if (filter.since   != null) flags = flags or 0x08
        if (filter.until   != null) flags = flags or 0x10
        if (filter.limit   != null) flags = flags or 0x20
        if (filter.search  != null) flags = flags or 0x40
        if (filter.tags    != null) flags = flags or 0x80
        writeByte(flags)
        if (filter.kinds != null) {
            writeInt(filter.kinds.size)
            for (k in filter.kinds) writeInt(k)
        }
        if (filter.authors != null) {
            writeInt(filter.authors.size)
            for (a in filter.authors) writeStr(a)
        }
        if (filter.ids != null) {
            writeInt(filter.ids.size)
            for (i in filter.ids) writeStr(i)
        }
        if (filter.since != null) writeLong(filter.since)
        if (filter.until != null) writeLong(filter.until)
        if (filter.limit != null) writeInt(filter.limit)
        if (filter.search != null) writeStr(filter.search)
        if (filter.tags != null) {
            writeInt(filter.tags.size)
            for ((tagName, values) in filter.tags) {
                writeStr(tagName)
                writeInt(values.size)
                for (v in values) writeStr(v)
            }
        }
    }

    private fun DataInputStream.readFilter(): NostrFilter {
        val flags = readByte().toInt() and 0xff
        val kinds = if (flags and 0x01 != 0) {
            val n = readInt(); List(n) { readInt() }
        } else null
        val authors = if (flags and 0x02 != 0) {
            val n = readInt(); List(n) { readStr() }
        } else null
        val ids = if (flags and 0x04 != 0) {
            val n = readInt(); List(n) { readStr() }
        } else null
        val since = if (flags and 0x08 != 0) readLong() else null
        val until = if (flags and 0x10 != 0) readLong() else null
        val limit = if (flags and 0x20 != 0) readInt() else null
        val search = if (flags and 0x40 != 0) readStr() else null
        val tags = if (flags and 0x80 != 0) {
            val n = readInt()
            val map = HashMap<String, List<String>>(n)
            for (i in 0 until n) {
                val tagName = readStr()
                val vCount = readInt()
                map[tagName] = List(vCount) { readStr() }
            }
            map
        } else null
        return NostrFilter(kinds, authors, ids, since, until, limit, search, tags)
    }

    internal fun insertFromSnapshot(event: NostrEvent) {
        if (event.kind == 10000) {
            synchronized(muteStateLock) { insertFromSnapshotUnlocked(event) }
        } else {
            insertFromSnapshotUnlocked(event)
        }
    }

    private fun insertFromSnapshotUnlocked(event: NostrEvent) {
        val nowSec = System.currentTimeMillis() / 1000L
        if (event.createdAt > nowSec + MAX_FUTURE_DRIFT_SECONDS) return
        if (event.kind in DERIVED_ONLY_KINDS) {
            insertDerivedOnly(event, snapshotDirtySink)
            return
        }
        if (event.kind != 5 && isDeletedByTombstone(event)) return

        eventsById[event.id] = event
        contentAdmissionIndex.track(
            eventId = event.id,
            kind = event.kind,
            tier = contentAdmissionTier(event),
        )
        idsByKind.getOrPut(event.kind) { ConcurrentHashMap.newKeySet() }.add(event.id)
        idsByPubkey.getOrPut(event.pubkey) { ConcurrentHashMap.newKeySet() }.add(event.id)
        recentByCreatedAt.add(EventEntry(event.id, event.createdAt))
        // Restored ≠ recently used. Use firstSeenAt (epoch millis, same unit as
        // lastTouchedAt) so eviction's LRU correctly targets cold restored data
        // rather than live relay events the user is currently viewing.
        lastTouchedAt[event.id] = event.firstSeenAt

        forEachReplyIndexTarget(event) { targetId ->
            idsByReplyTarget.getOrPut(targetId) { ConcurrentHashMap.newKeySet() }.add(event.id)
        }

        // Addressable index parity with live insert.
        if (event.kind == 30023 || event.kind == 34235 || event.kind == 34236) {
            val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
            registerArticleCoord(event.id, "${event.kind}:${event.pubkey}:$d")
        }
        indexArticleComment(event, null)

        indexRelayHints(event)
        indexNotificationRecipients(event, backfillPollResponses = false)

        // Pre-compute media metadata at snapshot-restore time (sidecar caches).
        // ContentParser.parse is LAZY — deferred to first getOrParseEventModel() read.
        if (event.kind in setOf(1, 6, 16, 20, 21, 22, 34235, 34236)) {
            val imetaMedia = com.unsilence.app.data.relay.ImetaParser.parseFromList(event.tags)
            val models = com.unsilence.app.data.model.buildVideoRenderModels(
                event.kind, event.content, event.tags, event.repostInfo,
            )
            if (models.isNotEmpty()) videoRenderModelsByEventId[event.id] = models
            val imageDims = imetaMedia
                .filter { it.mimeType?.startsWith("image/") == true && it.width != null && it.height != null && it.height != 0 }
                .associate { it.url to (it.width!!.toFloat() / it.height!!) }
            if (imageDims.isNotEmpty()) imetaImageDimsByEventId[event.id] = imageDims
        }

        // Restore kind-derived state (profiles, follows, relay lists)
        // Pass snapshotDirtySink so handlers don't bump signals per-event
        // during the 21s parse loop. End-of-restore bumps every signal once.
        val sink = snapshotDirtySink
        when (event.kind) {
            0 -> handleProfile(event)
            5 -> handleDeletion(event, sink)
            3 -> handleFollows(event, sink)
            10000 -> handleMuteList(event)
            10002 -> handleRelayList(event, sink)
            10006 -> handleBlocked(event, sink)
            10007 -> handleSearchRelays(event, sink)
            10012 -> handleFavorites(event, sink)
            10063 -> handleBlossomServers(event)
            30002 -> {
                handleParameterizedReplaceable(event)
                handleRelaySetMaterialized(event, sink)
            }
        }
    }

    private fun restoreAggregate(line: String) {
        val parts = line.split("|")
        if (parts.size < 3) return
        when (parts[0]) {
            "reply" -> { /* legacy scalar: consume and ignore */ }
            "repost" -> repostCounts[parts[1]] = parts[2].toIntOrNull() ?: return
            "reaction" -> { /* legacy: skip, reactionsByTarget is source of truth */ }
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
        followsByPubkey[pubkey] = pks
        followsCreatedAt[pubkey] = createdAt
        recordFollowsAccess(pubkey, createdAt * 1_000L)
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
        val evKind = parts[2].toIntOrNull() ?: return null
        val evContent = unescapeContent(parts[3])
        return NostrEvent(
            id = parts[0],
            pubkey = parts[1],
            kind = evKind,
            content = evContent,
            createdAt = parts[4].toLongOrNull() ?: return null,
            tags = tags,
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
        ).withParsedRepostMetadata()
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
        require(events >= 0)
        evictOldContentEvents(CONTENT_EVENT_KIND_CAPS + (1 to events))
    }

    /**
     * Clear user-specific state (follows, relay configs, actions) but preserve
     * the event/profile/stats cache. Used during logout so the next user
     * benefits from already-cached public Nostr data (events, profiles, stats).
     */
    fun clearUserState() {
        followsByPubkey.clear()
        followsCreatedAt.clear()
        ownContactListEvent = null
        followsAccessedAt.clear()
        followerCountCache.clear()
        clearNip05VerificationCache()
        relayListsByPubkey.clear()
        muteListsByPubkey.clear()
        latestMuteEventIdByPubkey.clear()
        pendingMutePublishesByPubkey.clear()
        blockedRelaysByPubkey.clear()
        searchRelaysByPubkey.clear()
        favoritesByPubkey.clear()
        readWriteRelayConfigsByPubkey.clear()
        relayKindCreatedAt.clear()
        relaySetsByCoordinate.clear()
        deletedRelaySetTombstones.clear()
        blossomServersByPubkey.clear()
        trustScoresByUrl.clear()
        synchronized(wotProviderLock) {
            clearWotAssertionsLocked()
            ownWotProviderRegistry = null
            ownWotProviderEncryptedContent = null
            ownWotProviderEncryptedUpdatedAt = 0L
        }
        relayMonitorsByUrl.clear()
        reactedTargetsByActor.clear()
        repostedTargetsByActor.clear()
        zappedTargetsByActor.clear()
        reactionEventIdsByActorTarget.clear()
        repostEventIdsByActorTarget.clear()
        deletedEventTombstones.clear()
        deletedAddressableTombstones.clear()
        actorAccessedAt.clear()
        _followsSignal.value++
        _actionSignal.value++
        _relayConfigSignal.value++
        _relaySetSignal.value++
        _trustScoreSignal.value++
        _wotSignal.value++
        _relayMonitorSignal.value++
        _statsInvalidations.tryEmit(StatsInvalidation.Broadcast)
    }

    fun clear() {
        // Metrics are account-scoped evidence. Carrying interval work or the
        // last pass's anchors across logout would corrupt the next session's
        // release probe even though the underlying store has been cleared.
        resetEvictionMetrics()
        insertsSinceLastEviction.set(0)
        eventsById.clear()
        pendingRelays.clear()
        idsByKind.clear()
        idsByPubkey.clear()
        idsByReplyTarget.clear()
        articleIdByCoord.clear()
        articleCoordById.clear()
        commentIdsByCoord.clear()
        recentByCreatedAt.clear()
        lastTouchedAt.clear()
        contentAdmissionIndex.clear()
        repostCounts.clear()
        zapStatsByEventId.clear()
        statsUpdatedAt.clear()
        repostPubkeysByTarget.clear()
        reactionsByTarget.clear()
        zapDetailsByTarget.clear()
        ownAnonZapPubkeys.clear()
        profilesByPubkey.clear()
        profileUpdatedAt.clear()
        profileFieldsCache.clear()
        profileAccessedAt.clear()
        clearNip05VerificationCache()
        profileDerivedLookupRelaysByPubkey.clear()
        feedRowCache.clear()
        feedRowAccessedAt.clear()
        followsByPubkey.clear()
        followsCreatedAt.clear()
        ownContactListEvent = null
        followsAccessedAt.clear()
        followerCountCache.clear()
        relayListsByPubkey.clear()
        muteListsByPubkey.clear()
        latestMuteEventIdByPubkey.clear()
        pendingMutePublishesByPubkey.clear()
        blockedRelaysByPubkey.clear()
        searchRelaysByPubkey.clear()
        favoritesByPubkey.clear()
        readWriteRelayConfigsByPubkey.clear()
        relayKindCreatedAt.clear()
        replaceableByCoordinate.clear()
        reactedTargetsByActor.clear()
        repostedTargetsByActor.clear()
        zappedTargetsByActor.clear()
        reactionEventIdsByActorTarget.clear()
        repostEventIdsByActorTarget.clear()
        deletedEventTombstones.clear()
        deletedAddressableTombstones.clear()
        actorAccessedAt.clear()
        engagementCapped.clear()
        profileAnchoredIds.clear()
        privateZapDecryptedById.clear()
        notifIdsByRecipient.clear()
        notificationSignalByRecipient.clear()
        _feedSignal.value = 0L
        _profileSignal.value = 0L
        _statsSignal.value = 0L
        _followsSignal.value = 0L
        _actionSignal.value = 0L
        _snapshotRestoredSignal.value = 0L
        _relayConfigSignal.value = 0L
        relaySetsByCoordinate.clear()
        deletedRelaySetTombstones.clear()
        videoRenderModelsByEventId.clear()
        imetaImageDimsByEventId.clear()
        eventModelsByEventId.clear()
        relayHintsForEvent.clear()
        blossomServersByPubkey.clear()
        emojiSetsByCoordinate.clear()
        userEmojiListByPubkey.clear()
        emojiKindCreatedAt.clear()
        trustScoresByUrl.clear()
        synchronized(wotProviderLock) {
            clearWotAssertionsLocked()
            ownWotProviderRegistry = null
            ownWotProviderEncryptedContent = null
            ownWotProviderEncryptedUpdatedAt = 0L
        }
        relayMonitorsByUrl.clear()
        relayIdentitiesByUrl.clear()
        _relaySetSignal.value = 0L
        _emojiSetSignal.value = 0L
        _trustScoreSignal.value = 0L
        _wotSignal.value = 0L
        _relayMonitorSignal.value = 0L
        _relayIdentitySignal.value = 0L
        _statsInvalidations.tryEmit(StatsInvalidation.Broadcast)
        timelineServiceProvider.get().clear()
    }
}

// ─── Utilities ──────────────────────────────────────────────────────────────

/** Serialize tags to JSON format matching snapshot storage: [["tag","val"],["tag","val"]] */
internal fun tagsToJson(tags: List<List<String>>): String =
    JsonArray(tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) }).toString()

internal fun NostrEvent.toEventEntity(): EventEntity = EventEntity(
    id = id,
    pubkey = pubkey,
    kind = kind,
    content = content,
    createdAt = createdAt,
    tags = tagsToJson(tags),
    sig = sig,
    relayUrl = relayUrl,
    replyToId = replyToId,
    rootId = rootId,
    hasContentWarning = hasContentWarning,
    contentWarningReason = contentWarningReason,
    firstSeenAt = firstSeenAt,
)

/** Parse profile JSON once and return all string fields. Handles escaped chars, nulls, etc. */
private fun parseProfileJson(content: String): Map<String, String?> {
    return try {
        val obj = NostrJson.parseToJsonElement(content).jsonObject
        buildMap {
            for (key in PROFILE_JSON_KEYS) {
                put(key, profileJsonString(obj, PROFILE_JSON_FIELD_ALIASES[key] ?: listOf(key)))
            }
        }
    } catch (_: Exception) { emptyMap() }
}

private fun profileJsonString(
    obj: kotlinx.serialization.json.JsonObject,
    keys: List<String>,
): String? {
    var blank: String? = null
    for (key in keys) {
        val element = obj[key]
        val value = if (element != null && element !is JsonNull && element is JsonPrimitive) {
            element.content
        } else {
            null
        } ?: continue
        if (value.isNotBlank()) return value
        if (blank == null) blank = value
    }
    return blank
}

private fun Map<String, String?>.withIdentityFallback(fallbackFields: Map<String, String?>?): Map<String, String?> {
    if (fallbackFields == null) return this
    var merged: MutableMap<String, String?>? = null
    for (key in PROFILE_IDENTITY_FALLBACK_KEYS) {
        if (nonBlankProfileField(key) != null) continue
        if (key == "display_name" && nonBlankProfileField("name") != null) continue
        val fallback = fallbackFields.nonBlankProfileField(key) ?: continue
        val target = merged ?: toMutableMap().also { merged = it }
        target[key] = fallback
    }
    return merged ?: this
}

private fun Map<String, String?>.nonBlankProfileField(key: String): String? =
    this[key]?.takeIf { it.isNotBlank() }

private val PROFILE_JSON_KEYS = listOf(
    "name",
    "display_name",
    "about",
    "picture",
    "nip05",
    "lud16",
    "banner",
    "website",
)
private val PROFILE_JSON_FIELD_ALIASES = mapOf(
    "display_name" to listOf("display_name", "displayName"),
    "picture" to listOf("picture", "avatar", "image"),
)
private val PROFILE_IDENTITY_FALLBACK_KEYS = listOf("name", "display_name", "picture", "nip05", "banner")
