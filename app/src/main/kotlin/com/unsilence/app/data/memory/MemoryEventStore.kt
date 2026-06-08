package com.unsilence.app.data.memory

import android.os.Trace
import android.util.Log
// FeedRow, EventEntity, UserEntity are in the same package (data.memory.Models)
import com.unsilence.app.data.relay.NostrJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.NostrFilter
import com.unsilence.app.data.relay.TimelineRef
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.relay.normalizeRelayUrl
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
private const val SNAPSHOT_BINARY_VERSION = 12
/** Max engaged event IDs persisted per action type (react/repost/zap). */
private const val PERSISTED_ENGAGED_CAP = 10_000
private const val SNAPSHOT_HEADER_SIZE = 32 // bytes
/** Defensive cap on per-string length read from snapshot (1 MB). */
private const val MAX_SNAPSHOT_STR_LEN = 1024 * 1024

private const val PENDING_RELAYS_CAP = 1_000
private const val PENDING_RELAYS_TRIM = 200
private const val MAX_CONTENT_EVENTS = 10_000
private const val FEED_ROW_CACHE_CAP = 2000
private const val ACTOR_INDEX_CAP = 1_000
private const val ACTOR_TARGETS_CAP = 500
private const val PROFILE_CAP = 2_000
private const val PROFILE_ANCHOR_RECENT_EVENTS = 500
private const val MAX_FUTURE_DRIFT_SECONDS = 60L
private val CONTENT_KINDS = setOf(1, 6, 7, 9734, 9735, 20, 21, 30023)
private val NOTIFICATION_KINDS = setOf(1, 6, 7, 9735)
private val DERIVED_ONLY_KINDS = setOf(30166)

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

    // ─── Derived aggregates (incrementally maintained) ──────────────────────
    private val replyCounts = ConcurrentHashMap<String, Int>()
    private val repostCounts = ConcurrentHashMap<String, Int>()
    private val reactionCounts = ConcurrentHashMap<String, Int>()
    private val zapStatsByEventId = ConcurrentHashMap<String, ZapAggregate>()
    private val statsUpdatedAt = ConcurrentHashMap<String, Long>()

    // ─── Engagement contributor indexes (per-target breakdowns for drawer) ──
    private val repostPubkeysByTarget = ConcurrentHashMap<String, MutableSet<String>>()
    private val reactionsByTarget = ConcurrentHashMap<String, MutableSet<ReactionInfo>>()
    private val zapDetailsByTarget = ConcurrentHashMap<String, MutableList<ZapDetail>>()

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
     * (insertCore + insertFromSnapshot + rebuildNotificationIndex on restore)
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

    /**
     * Index every unique p-tag recipient of [event] and bump their
     * notification signals. No-op for non-notification kinds.
     */
    private fun indexNotificationRecipients(event: NostrEvent) {
        if (event.kind !in NOTIFICATION_KINDS) return
        val seen = HashSet<String>(4)
        for (tag in event.tags) {
            if (tag.size < 2 || tag[0] != "p") continue
            val recipient = tag[1]
            if (recipient.length != 64) continue
            if (!seen.add(recipient)) continue
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
        val seen = HashSet<String>(4)
        for (tag in event.tags) {
            if (tag.size < 2 || tag[0] != "p") continue
            val recipient = tag[1]
            if (!seen.add(recipient)) continue
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
            val seen = HashSet<String>(4)
            for (tag in event.tags) {
                if (tag.size < 2 || tag[0] != "p") continue
                val recipient = tag[1]
                if (recipient.length != 64) continue
                if (!seen.add(recipient)) continue
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
    private val actorAccessedAt = ConcurrentHashMap<String, Long>()

    /** Posts where engagement download hit the limit — cards show "N+" for these. */
    private val engagementCapped: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Set by AppBootstrapper after login — used as anchor for LRU eviction. */
    @Volatile var ownPubkey: String? = null

    /** Currently viewed profile — single-slot anchor for content eviction.
     *  Set by UserProfileViewModel on loadProfile(), cleared on onCleared(). */
    @Volatile var viewedPubkey: String? = null

    /** Ref IDs anchored by the OWN profile pipeline — quoted notes, repost targets,
     *  thread parents of own-authored events. OWN-scope only (populated at cold-start,
     *  rebuilt from MES own-notes on every app startup). Flat set, no LRU, no per-profile
     *  partitioning. Cleared on logout via [clear]. */
    val profileAnchoredIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
    private val relayListsByPubkey = ConcurrentHashMap<String, RelayList>()
    private val muteListsByPubkey = ConcurrentHashMap<String, MuteList>()
    /** Epoch-seconds floor: reject own kind-10000 relay events older than this.
     *  Set by addPrivateMute/removePrivateMute; cleared when a relay event
     *  with createdAt >= floor is accepted (our publish echo arrived). */
    @Volatile private var muteListOptimisticFloor: Long = 0L

    /** Callback fired when a kind-10000 with encrypted content arrives in Amber mode.
     *  Set by AppBootstrapper to trigger async decrypt via SigningManager. */
    @Volatile internal var muteListDecryptCallback: ((NostrEvent) -> Unit)? = null

    /** Checks if an event was self-published by MuteListRepository.
     *  Wired by AppBootstrapper to avoid re-processing our own echoes. */
    @Volatile internal var isSelfPublishedCheck: ((String) -> Boolean) = { false }

    // ─── Blossom servers (kind 10063 / NIP-B7) ─────────────────────────────────
    // Key: pubkey → ordered list of server URLs (replaceable event, last-write-wins)
    private val blossomServersByPubkey = ConcurrentHashMap<String, List<String>>()

    // ─── Trust scores (kind 30385) ────────────────────────────────────────────
    private val trustScoresByUrl = ConcurrentHashMap<String, RelayTrustScoreEntity>()

    // ─── Relay monitors (kind 30166 / NIP-66) ─────────────────────────────────
    private val relayMonitorsByUrl = ConcurrentHashMap<String, RelayMonitorEntity>()

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
    private val _relayMonitorSignal = MutableStateFlow(0L)
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
        var relayMonitor = false
        var relaySet = false
        var emojiSet = false
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
        if (d.relayMonitor) _relayMonitorSignal.value = now
        if (d.relaySet) _relaySetSignal.value = now
        if (d.emojiSet) _emojiSetSignal.value = now
        if (d.invalidatedStatsIds.isNotEmpty()) {
            _statsInvalidations.tryEmit(
                StatsInvalidation.Targeted(d.invalidatedStatsIds.toSet())
            )
        }
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
            flushDirty(dirty)
            evictionTickAfterInsert()
        }
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
        if (inserted > 0) evictionTickAfterInsert(inserted)
        return inserted
    }

    /** Map a kind to its corresponding feed/profile/stats/follows/action dirty flags. */
    private fun markKindDirty(kind: Int, d: InsertDirty) {
        when (kind) {
            0 -> d.profile = true
            3 -> d.follows = true
            1, 6, 20, 21, 30023 -> d.feed = true
            7, 9734, 9735 -> d.stats = true
        }
        if (kind == 7 || kind == 6 || kind == 9734) d.action = true
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
        if (event.kind in DERIVED_ONLY_KINDS) {
            return insertDerivedOnly(event, dirty)
        }

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

        // 2b. Index relay hints from e-tags (provenance + explicit NIP-10/18 hints)
        indexRelayHints(event)

        // 2c. Notification recipient index (M4)
        indexNotificationRecipients(event)

        // 3. Update derived aggregates based on kind
        when (event.kind) {
            0 -> handleProfile(event)
            1 -> handleNote(event, dirty)
            3 -> handleFollows(event, dirty)
            6 -> handleRepost(event, dirty)
            7 -> handleReaction(event, dirty)
            9734 -> handleZapRequest(event)
            9735 -> handleZapReceipt(event, dirty)
            10000 -> handleMuteList(event)
            10002 -> handleRelayList(event, dirty)
            10006 -> handleBlocked(event, dirty)
            10007 -> handleSearchRelays(event, dirty)
            10012 -> handleFavorites(event, dirty)
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
        when (event.kind) {
            30166 -> handleRelayMonitor(event, dirty)
            else -> return false
        }
        return true
    }

    private fun evictionTickAfterInsert(count: Int = 1) {
        if (insertsSinceLastEviction.addAndGet(count) >= 500) {
            insertsSinceLastEviction.set(0)
            evictOldContentEvents()
        }
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

        // Snapshot profileAccessedAt values before sorting — same TimSort
        // contract bug as evictOldContentEvents. profileAccessedAt is mutated
        // concurrently by handleProfile and cachedProfileFields. See CLAUDE.md
        // rule #24.
        val candidateKeys = profileAccessedAt.keys.filter { it !in anchors }
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
            removed++
        }
        if (removed > 0) {
            Log.d("MES", "Profiles trimmed $removed entries, remaining=${profilesByPubkey.size}")
        }
    }

    private fun handleNote(event: NostrEvent, dirty: InsertDirty) {
        // Increment reply counts for targets
        event.replyToId?.let { targetId ->
            replyCounts.compute(targetId) { _, v -> (v ?: 0) + 1 }
            statsUpdatedAt[targetId] = System.currentTimeMillis()
            dirty.invalidatedStatsIds.add(targetId)
        }
        // If rootId differs from replyToId, root also gets a reply count
        if (event.rootId != null && event.rootId != event.replyToId) {
            replyCounts.compute(event.rootId) { _, v -> (v ?: 0) + 1 }
            statsUpdatedAt[event.rootId] = System.currentTimeMillis()
            dirty.invalidatedStatsIds.add(event.rootId)
        }
    }

    private fun handleFollows(event: NostrEvent, dirty: InsertDirty? = null) {
        val pubkeys = event.tags
            .filter { it.size >= 2 && it[0] == "p" }
            .map { it[1] }
            .toSet()
        updateFollowsInternal(event.pubkey, pubkeys, event.createdAt, dirty)
    }

    private fun handleRepost(event: NostrEvent, dirty: InsertDirty) {
        val targetId = event.rootId ?: return
        repostCounts.compute(targetId) { _, v -> (v ?: 0) + 1 }
        repostPubkeysByTarget
            .computeIfAbsent(targetId) { ConcurrentHashMap.newKeySet() }
            .add(event.pubkey)
        statsUpdatedAt[targetId] = System.currentTimeMillis()
        dirty.invalidatedStatsIds.add(targetId)
        // Actor-side index: track what this pubkey has reposted
        addToActorIndex(repostedTargetsByActor, event.pubkey, targetId)
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
        // Last e-tag is the target
        val targetId = event.tags
            .lastOrNull { it.size >= 2 && it[0] == "e" }
            ?.get(1) ?: return
        reactionCounts.compute(targetId) { _, v -> (v ?: 0) + 1 }
        val contentStr = event.content.ifBlank { "+" }
        val reactionContent = parseReactionContent(contentStr, event.tags)
        reactionsByTarget
            .computeIfAbsent(targetId) { ConcurrentHashMap.newKeySet() }
            .add(ReactionInfo(event.pubkey, reactionContent))
        statsUpdatedAt[targetId] = System.currentTimeMillis()
        dirty.invalidatedStatsIds.add(targetId)
        // Actor-side index: track what this pubkey has reacted to
        addToActorIndex(reactedTargetsByActor, event.pubkey, targetId)
    }

    /**
     * Rebuilds [reactionsByTarget] from raw kind-7 events in [eventsById].
     * Called after snapshot restore so [parseReactionContent] reclassifies reactions
     * that were persisted with the old narrower regex.
     */
    private fun reindexReactionsFromEvents() {
        val kind7Ids = idsByKind[7] ?: return
        if (kind7Ids.isEmpty()) return
        reactionsByTarget.clear()
        var customCount = 0
        var standardCount = 0
        var noETagCount = 0
        for (id in kind7Ids) {
            val event = eventsById[id] ?: continue
            val targetId = event.tags
                .lastOrNull { it.size >= 2 && it[0] == "e" }
                ?.get(1) ?: run { noETagCount++; continue }
            val contentStr = event.content.ifBlank { "+" }
            val reactionContent = parseReactionContent(contentStr, event.tags)
            if (reactionContent is ReactionContent.Custom) customCount++ else standardCount++
            reactionsByTarget
                .computeIfAbsent(targetId) { ConcurrentHashMap.newKeySet() }
                .add(ReactionInfo(event.pubkey, reactionContent))
        }
        Log.d("MES", "Reindexed ${kind7Ids.size} kind-7 reactions (custom=$customCount, standard=$standardCount, noETag=$noETagCount)")
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

    private fun handleZapReceipt(event: NostrEvent, dirty: InsertDirty) {
        val targetId = event.tags
            .firstOrNull { it.size >= 2 && it[0] == "e" }
            ?.get(1) ?: return

        val sats = extractSatsFromZap(event)
        zapStatsByEventId.compute(targetId) { _, existing ->
            val current = existing ?: ZapAggregate.EMPTY
            ZapAggregate(current.count + 1, current.totalSats + sats)
        }
        statsUpdatedAt[targetId] = System.currentTimeMillis()
        dirty.invalidatedStatsIds.add(targetId)

        // Parse embedded kind-9734 zap request for sender pubkey + comment.
        val desc = parseZapDescription(event)
        zapDetailsByTarget
            .computeIfAbsent(targetId) { java.util.Collections.synchronizedList(mutableListOf()) }
            .add(ZapDetail(desc?.senderPubkey, sats, desc?.comment, eventId = event.id))

        // Own-zap detection: signal VM to clear optimistic sats overlay.
        // desc == null → anonymous, can never be our own.
        val own = ownPubkey
        if (own != null && desc != null && desc.senderPubkey == own) {
            _ownZapReceived.tryEmit(targetId)
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
        val isOwn = event.pubkey == ownPubkey

        // Skip our own published events — we already have canonical local state.
        // The echo would clobber private fields (Amber can't decrypt inline).
        if (isOwn && isSelfPublishedCheck(event.id)) {
            // Still update floor timestamp so subsequent relay events are accepted
            val floor = muteListOptimisticFloor
            if (floor > 0L && event.createdAt >= floor) muteListOptimisticFloor = 0L
            return
        }

        // Guard: reject relay events older than an in-flight optimistic update.
        // addPrivateMute/removePrivateMute set the floor; it clears when a relay
        // event with createdAt >= floor is accepted (our publish echo arrived).
        if (isOwn) {
            val floor = muteListOptimisticFloor
            if (floor > 0L && event.createdAt < floor) {
                return
            }
            if (floor > 0L) {
                // Relay event caught up — clear the floor
                muteListOptimisticFloor = 0L
            }
        }

        // Replaceable event guard: skip if a newer kind-10000 for this pubkey
        // already exists in MES. Prevents older relay echoes from clobbering
        // the newest mute list (especially the async Amber decrypt callback).
        val newerExists = eventsById.values.any {
            it.pubkey == event.pubkey && it.kind == 10000 && it.id != event.id &&
                it.createdAt > event.createdAt
        }
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
        if (isOwn && event.content.isNotEmpty() && !keyProvider.isAmberMode) {
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

        muteListsByPubkey[event.pubkey] = MuteList(
            pubkeys = pubkeys,
            hashtags = hashtags,
            words = words,
            eventIds = eventIds,
            privatePubkeys = inlinePrivPubkeys ?: muteListsByPubkey[event.pubkey]?.privatePubkeys ?: emptySet(),
            privateHashtags = inlinePrivHashtags ?: muteListsByPubkey[event.pubkey]?.privateHashtags ?: emptySet(),
            privateWords = inlinePrivWords ?: muteListsByPubkey[event.pubkey]?.privateWords ?: emptySet(),
            privateEventIds = inlinePrivEventIds ?: muteListsByPubkey[event.pubkey]?.privateEventIds ?: emptySet(),
        )
        if (isOwn) _muteListSignal.value = System.nanoTime()

        // For Amber mode + own pubkey + non-empty content: fire async decrypt callback
        if (isOwn && event.content.isNotEmpty() && keyProvider.isAmberMode) {
            muteListDecryptCallback?.invoke(event)
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
            if (dirty != null) dirty.relayConfig = true
            else _relayConfigSignal.value = System.nanoTime()
        }
    }

    private fun handleBlocked(event: NostrEvent, dirty: InsertDirty? = null) {
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
            if (dirty != null) dirty.relayConfig = true
            else _relayConfigSignal.value = System.nanoTime()
        }
    }

    private fun handleSearchRelays(event: NostrEvent, dirty: InsertDirty? = null) {
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

    // ─── Kind 30385: Trusted Relay Assertions ─────────────────────────────

    private fun handleTrustScore(event: NostrEvent, dirty: InsertDirty? = null) {
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

    private fun handleRelayMonitor(event: NostrEvent, dirty: InsertDirty? = null) {
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
        if (dirty != null) dirty.relayMonitor = true
        else _relayMonitorSignal.value = System.nanoTime()
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
            // Band A: ref events anchored by own-profile pipeline (quoted notes,
            // repost targets, thread parents of own-authored events)
            if (entry.id in profileAnchoredIds) {
                anchoredOwn++ // counted under "own" since they protect own-profile refs
                evictionAnchoredOwn.incrementAndGet()
                continue
            }

            candidatesByKind.getOrPut(kind) { mutableListOf() }.add(entry)
        }

        // Pass 2: for each kind over its cap, sort candidates by lastTouchedAt
        // ascending and evict the least-recently-touched excess.
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
            val excess = candidates.size - cap
            val touchSnapshot = HashMap<String, Long>(candidates.size)
            for (c in candidates) touchSnapshot[c.id] = lastTouchedAt[c.id] ?: 0L
            candidates.sortBy { touchSnapshot[it.id] ?: 0L }
            for (i in 0 until excess) {
                toEvict.add(candidates[i])
            }
        }

        if (toEvict.isEmpty()) {
            if (anchoredOwn + anchoredMentioned + anchoredViewed > 0) {
                Log.d("MES", "Eviction: 0 removed, anchored own=$anchoredOwn mentioned=$anchoredMentioned viewed=$anchoredViewed")
                // Diagnostic: log when own-anchored count is unexpectedly high.
                // Field captures show own=1758/4242 events for one user — investigating
                // whether the test account really posts that much, ownPubkey is
                // matching too aggressively, or some import path is loading other
                // users' events under the own pubkey.
                if (anchoredOwn > 500 && ownPubkeyAnchor != null) {
                    val sampleAuthors = eventsById.values
                        .asSequence()
                        .filter { it.pubkey == ownPubkeyAnchor }
                        .take(3)
                        .map { "${it.kind}:${it.id.take(8)}" }
                        .toList()
                    Log.d("MES", "Eviction diag: ownPubkey=${ownPubkeyAnchor.take(8)}… (full=${ownPubkeyAnchor.length}ch) anchoredOwn=$anchoredOwn samples=$sampleAuthors")
                }
            }
            return
        }

        for (entry in toEvict) {
            val event = eventsById.remove(entry.id) ?: continue
            deindexNotificationRecipients(event)
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

        // Clean up aggregates + contributor indexes that reference removed events
        val removeIds = toEvict.map { it.id }.toSet()
        replyCounts.keys.removeAll(removeIds)
        repostCounts.keys.removeAll(removeIds)
        reactionCounts.keys.removeAll(removeIds)
        zapStatsByEventId.keys.removeAll(removeIds)
        statsUpdatedAt.keys.removeAll(removeIds)
        repostPubkeysByTarget.keys.removeAll(removeIds)
        reactionsByTarget.keys.removeAll(removeIds)
        zapDetailsByTarget.keys.removeAll(removeIds)

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
            val isRoot = event.kind == 6 || (event.replyToId == null && event.rootId == null)
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
        updateFollowsInternal(pubkey, followedPubkeys, createdAt, dirty = null)
    }

    private fun updateFollowsInternal(
        pubkey: String,
        followedPubkeys: Set<String>,
        createdAt: Long,
        dirty: InsertDirty?,
    ) {
        followsCreatedAt.compute(pubkey) { _, existingTs ->
            if (existingTs != null && existingTs > createdAt) {
                Log.d("MES", "updateFollows: stale for ${pubkey.take(8)}… (existing=$existingTs > new=$createdAt)")
                return@compute existingTs // stale — ignore
            }
            val existing = followsByPubkey[pubkey]
            val changed = existing == null || existing != followedPubkeys
            followsByPubkey[pubkey] = followedPubkeys
            if (changed) {
                if (dirty != null) dirty.follows = true
                else _followsSignal.value = System.nanoTime()
            }
            Log.d("MES", "updateFollows: ${pubkey.take(8)}… → ${followedPubkeys.size} follows (createdAt=$createdAt, changed=$changed)")
            createdAt
        }
    }
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
        muteListsByPubkey.compute(pubkey) { _, existing ->
            if (existing == null) {
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
        }
        Log.i("MES", "MuteList private update: ${privatePubkeys.size}p ${privateHashtags.size}t ${privateWords.size}word ${privateEventIds.size}e | owner=${pubkey.take(8)}…")
        if (pubkey == ownPubkey) _muteListSignal.value = System.nanoTime()
    }

    /**
     * Find the kind-10000 event content for a pubkey (for external decrypt).
     * Returns content from the NEWEST kind-10000 event for that pubkey.
     */
    fun getMuteListContent(pubkey: String): String? {
        var newest: NostrEvent? = null
        for (event in eventsById.values) {
            if (event.pubkey == pubkey && event.kind == 10000 && event.content.isNotEmpty()) {
                if (newest == null || event.createdAt > newest.createdAt) newest = event
            }
        }
        return newest?.content
    }

    /** True if [eventId] is the newest kind-10000 for [pubkey] in eventsById. */
    fun isNewestMuteEvent(eventId: String, pubkey: String): Boolean {
        val target = eventsById[eventId] ?: return false
        return eventsById.values.none {
            it.pubkey == pubkey && it.kind == 10000 && it.id != eventId &&
                it.createdAt > target.createdAt
        }
    }

    /** Optimistic local mute — feed refilters via _muteListSignal.
     *  Sets muteListOptimisticFloor so relay events don't overwrite. */
    fun addPrivateMute(targetPubkey: String) {
        val ownPk = ownPubkey ?: return
        muteListOptimisticFloor = System.currentTimeMillis() / 1000L
        muteListsByPubkey.compute(ownPk) { _, existing ->
            if (existing == null) MuteList(
                pubkeys = emptySet(), hashtags = emptySet(),
                words = emptySet(), eventIds = emptySet(),
                privatePubkeys = setOf(targetPubkey),
            ) else existing.copy(privatePubkeys = existing.privatePubkeys + targetPubkey)
        }
        _muteListSignal.value = System.nanoTime()
    }

    fun addPrivateWord(word: String) {
        val ownPk = ownPubkey ?: return
        muteListOptimisticFloor = System.currentTimeMillis() / 1000L
        muteListsByPubkey.compute(ownPk) { _, existing ->
            if (existing == null) MuteList(
                pubkeys = emptySet(), hashtags = emptySet(),
                words = emptySet(), eventIds = emptySet(),
                privateWords = setOf(word),
            ) else existing.copy(privateWords = existing.privateWords + word)
        }
        _muteListSignal.value = System.nanoTime()
    }

    fun removePrivateWord(word: String) {
        val ownPk = ownPubkey ?: return
        muteListOptimisticFloor = System.currentTimeMillis() / 1000L
        muteListsByPubkey.computeIfPresent(ownPk) { _, existing ->
            existing.copy(
                words = existing.words - word,
                privateWords = existing.privateWords - word,
            )
        }
        _muteListSignal.value = System.nanoTime()
    }

    fun addPrivateHashtag(tag: String) {
        val ownPk = ownPubkey ?: return
        muteListOptimisticFloor = System.currentTimeMillis() / 1000L
        muteListsByPubkey.compute(ownPk) { _, existing ->
            if (existing == null) MuteList(
                pubkeys = emptySet(), hashtags = emptySet(),
                words = emptySet(), eventIds = emptySet(),
                privateHashtags = setOf(tag),
            ) else existing.copy(privateHashtags = existing.privateHashtags + tag)
        }
        _muteListSignal.value = System.nanoTime()
    }

    fun removePrivateHashtag(tag: String) {
        val ownPk = ownPubkey ?: return
        muteListOptimisticFloor = System.currentTimeMillis() / 1000L
        muteListsByPubkey.computeIfPresent(ownPk) { _, existing ->
            existing.copy(
                hashtags = existing.hashtags - tag,
                privateHashtags = existing.privateHashtags - tag,
            )
        }
        _muteListSignal.value = System.nanoTime()
    }

    /** Optimistic local unmute — removes from both public and private sets.
     *  Sets muteListOptimisticFloor so relay events don't overwrite. */
    fun removePrivateMute(targetPubkey: String) {
        val ownPk = ownPubkey ?: return
        muteListOptimisticFloor = System.currentTimeMillis() / 1000L
        muteListsByPubkey.computeIfPresent(ownPk) { _, existing ->
            existing.copy(
                pubkeys = existing.pubkeys - targetPubkey,
                privatePubkeys = existing.privatePubkeys - targetPubkey,
            )
        }
        _muteListSignal.value = System.nanoTime()
    }

    /** Clear the optimistic floor — called when publish fails to avoid
     *  permanently blocking relay mute list updates. */
    fun clearMuteListOptimisticFloor() {
        muteListOptimisticFloor = 0L
    }

    /**
     * Store a locally-signed event in eventsById without triggering kind handlers.
     * Used by MuteListRepository to ensure the latest kind-10000 event reaches the
     * snapshot before the relay echo arrives — prevents data loss if the user
     * backgrounds between local mute and echo.
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

    fun replyCount(eventId: String): Int = replyCounts[eventId] ?: 0
    fun repostCount(eventId: String): Int = repostCounts[eventId] ?: 0
    fun reactionCount(eventId: String): Int = reactionCounts[eventId] ?: 0
    fun zapStats(eventId: String): ZapAggregate = zapStatsByEventId[eventId] ?: ZapAggregate.EMPTY
    fun statsLastUpdated(eventId: String): Long = statsUpdatedAt[eventId] ?: 0L

    // ─── Engagement contributor queries (drawer) ────────────────────────────

    /** Deduplicated pubkeys of users who replied to [eventId]. */
    fun replyPubkeysForEvent(eventId: String): List<String> {
        val replyIds = idsByReplyTarget[eventId] ?: return emptyList()
        val seen = HashSet<String>()
        for (id in replyIds) {
            val pk = eventsById[id]?.pubkey ?: continue
            seen.add(pk)
        }
        return seen.toList()
    }

    /** Deduplicated pubkeys of users who reposted [eventId]. */
    fun repostPubkeysForEvent(eventId: String): List<String> =
        repostPubkeysByTarget[eventId]?.toList() ?: emptyList()

    /** Reaction info for all reactions to [eventId]. */
    fun reactionsForEvent(eventId: String): List<ReactionInfo> =
        reactionsByTarget[eventId]?.toList() ?: emptyList()

    /** Per-zap breakdown for [eventId]: sender, sats, optional comment. */
    fun zapDetailsForEvent(eventId: String): List<ZapDetail> =
        zapDetailsByTarget[eventId]?.toList() ?: emptyList()

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

    /**
     * Trending hashtags: frequency scan of `t` tags across recent kind-1 events.
     * Returns up to [limit] (tag, count) pairs sorted by count DESC.
     * Scans the most recent 500 kind-1 events by createdAt.
     */
    fun trendingHashtags(limit: Int = 8): List<Pair<String, Int>> {
        val freq = HashMap<String, Int>()
        eventsById.values.asSequence()
            .filter { it.kind == 1 }
            .sortedByDescending { it.createdAt }
            .take(500)
            .forEach { event ->
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

    /** Synchronous check: has the current user reacted to or reposted [eventId]?
     *  Used by CardHydrator to skip backfill for already-lit posts. */
    fun isOwnEngaged(eventId: String): Boolean {
        val pk = ownPubkey ?: return false
        return reactedTargetsByActor[pk]?.contains(eventId) == true ||
            repostedTargetsByActor[pk]?.contains(eventId) == true
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

    /**
     * Observe per-event engagement counts. Used by EventActionBar so individual
     * cards update their counts without going through a list-wide signal trigger.
     *
     * Trigger: _statsSignal (kind 7/9735) plus _actionSignal (own kind 6/7/9734
     * inserts) plus _feedSignal (kind 1 replies bump replyCounts). distinctUntilChanged
     * via [EventStats] equality suppresses emission when THIS event's counts
     * didn't change — so a kind-7 reaction on an unrelated event doesn't
     * recompose 100 visible cards, only the affected card.
     */
    fun statsFlow(eventId: String): Flow<EventStats> =
        _statsInvalidations
            .filter { inv ->
                when (inv) {
                    is StatsInvalidation.Targeted -> eventId in inv.ids
                    StatsInvalidation.Broadcast -> true
                }
            }
            .map { currentStats(eventId) }
            .onStart { emit(currentStats(eventId)) }
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

    override fun getRelayMonitors(): Map<String, RelayMonitorEntity> =
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
     * Notification query backed by the recipient-pubkey reverse index.
     * Iterates the per-recipient sorted set in createdAt-DESC order until
     * [limit] items are collected, applying self-exclusion (parses
     * kind-9735 description for the real sender) and an optional
     * followed-only filter at read time.
     *
     * @param limit Maximum rows to return. null = unlimited.
     */
    fun getNotifications(
        recipientPubkey: String,
        followedOnly: Boolean = false,
        limit: Int? = null,
    ): List<NotificationItem> {
        val entries = notifIdsByRecipient[recipientPubkey] ?: return emptyList()
        val follows = if (followedOnly) followsByPubkey[recipientPubkey] else null
        val items = ArrayList<NotificationItem>(limit ?: 200)
        val cap = limit ?: Int.MAX_VALUE

        for (entry in entries) {
            if (items.size >= cap) break
            val event = eventsById[entry.eventId] ?: continue

            // Self-exclusion: kind-9735 must use parsed sender, not LNURL service.
            val effectivePubkey = if (event.kind == 9735) {
                val decrypted = privateZapDecryptedById[event.id]
                decrypted?.senderPubkey
                    ?: parseZapDescription(event)?.senderPubkey
                    ?: event.pubkey
            } else {
                event.pubkey
            }
            if (effectivePubkey == recipientPubkey) continue

            if (follows != null && effectivePubkey !in follows) continue

            val item = buildNotificationItem(event, recipientPubkey) ?: continue
            items.add(item)
        }
        return items
    }

    /**
     * Count notifications for [recipientPubkey] with createdAt > [since].
     * Walks the sorted set and breaks early when entries drop below [since].
     */
    fun notificationCountSince(recipientPubkey: String, since: Long): Int {
        val entries = notifIdsByRecipient[recipientPubkey] ?: return 0
        var count = 0
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
            count++
        }
        return count
    }

    /**
     * Reactive notification flow driven by per-recipient signal.
     * Only re-emits when something affecting THIS recipient changes —
     * not on every kind-1 insert globally.
     */
    fun notificationsFlow(
        recipientPubkey: String,
        followedOnly: Boolean = false,
        limit: Int? = null,
    ): Flow<List<NotificationItem>> =
        notificationSignalFor(recipientPubkey)
            .map { getNotifications(recipientPubkey, followedOnly, limit) }
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

        // For kind-9735 zap receipts, event.pubkey is the LNURL service signer —
        // resolve the real sender: decrypted private zap > description tag > anonymous.
        val actorPubkey: String
        val actorFields: Map<String, String?>
        if (event.kind == 9735) {
            val decryptedPrivate = privateZapDecryptedById[event.id]
            val desc = parseZapDescription(event)
            when {
                decryptedPrivate != null -> {
                    // NIP-57 private zap successfully decrypted.
                    actorPubkey = decryptedPrivate.senderPubkey
                    actorFields = cachedProfileFields(decryptedPrivate.senderPubkey)
                }
                desc != null -> {
                    // Standard public zap — kind-9734 signed by real sender.
                    actorPubkey = desc.senderPubkey
                    actorFields = cachedProfileFields(desc.senderPubkey)
                }
                else -> {
                    // Truly anonymous OR private-but-undecrypted. Show as "Anonymous"
                    // — never the LNURL service profile. IdentIcon falls back from
                    // event.pubkey so each LNURL still gets a distinct generic icon.
                    actorPubkey = event.pubkey
                    actorFields = mapOf(
                        "name" to "Anonymous",
                        "display_name" to null,
                        "picture" to null,
                    )
                }
            }
        } else {
            actorPubkey = event.pubkey
            actorFields = fields
        }

        return NotificationItem(
            id = event.id,
            notifType = notifType,
            actorPubkey = actorPubkey,
            actorName = actorFields["name"],
            actorDisplayName = actorFields["display_name"],
            actorPicture = actorFields["picture"],
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
        // Per-author + per-event cache keys. A profile update for author X
        // bumps profileUpdatedAt[X] but leaves other authors' timestamps
        // unchanged — rows for those authors stay cached. A stat update for
        // event Y bumps statsUpdatedAt[Y] but leaves other rows alone.
        val statsId = if (event.kind == 6) event.rootId ?: event.id else event.id
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

        feedRowCache[event.id] = CachedFeedRow(row, authorProfileTs, statsTs)
        feedRowAccessedAt[event.id] = System.nanoTime()
        trimFeedRowCacheIfNeeded()
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
            profileAnchoredRefEntries = profileAnchoredIds.size,
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

        // Bump all signals once (follows signal fires again — idempotent,
        // consumers use distinctUntilChanged or one-shot .first())
        val now = System.nanoTime()
        _feedSignal.value = now
        _profileSignal.value = now
        _statsSignal.value = now
        _followsSignal.value = now
        _trustScoreSignal.value = now
        _relayMonitorSignal.value = now
        _snapshotRestoredSignal.value = now
        _statsInvalidations.tryEmit(StatsInvalidation.Broadcast)

        // Evict old content events from snapshot (may contain stale data)
        evictOldContentEvents()

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
    // V2 TSV files are still readable — SnapshotScheduler peeks the first
    // 4 bytes and dispatches: "USNS" → binary, anything else → V2 reader.

    suspend fun saveSnapshotBinary(out: DataOutputStream) {
        // Two-pass: serialize each section to a ByteArrayOutputStream, then
        // write the header (with computed offsets) + concatenated sections.
        // Memory cost: ~2× snapshot size peak. For typical 5MB snapshots
        // that's 10MB peak — acceptable for the save path.

        val followsBuf = ByteArrayOutputStream(8 * 1024)
        DataOutputStream(followsBuf).use { d ->
            val pairs = followsByPubkey.toList()
            d.writeInt(pairs.size)
            for ((pubkey, follows) in pairs) {
                val createdAt = followsCreatedAt[pubkey] ?: 0L
                d.writeStr(pubkey)
                d.writeLong(createdAt)
                d.writeInt(follows.size)
                for (f in follows) d.writeStr(f)
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
        }

        // Build the events list: nonContent (profiles, relay configs, stats
        // events) first, then content sorted DESC by createdAt and capped.
        // kind-3 is persisted in the FOLLOWS section, not as raw events.
        val contentEvents = mutableListOf<NostrEvent>()
        val nonContentEvents = mutableListOf<NostrEvent>()
        for (event in eventsById.values) {
            if (event.kind in CONTENT_KINDS) contentEvents.add(event)
            else if (event.kind != 3) nonContentEvents.add(event)
        }
        contentEvents.sortByDescending { it.createdAt }
        val cappedContent = contentEvents.take(MAX_CONTENT_EVENTS)
        val totalEvents = nonContentEvents.size + cappedContent.size

        val eventsBuf = ByteArrayOutputStream(2 * 1024 * 1024)
        DataOutputStream(eventsBuf).use { d ->
            d.writeInt(totalEvents)
            for (event in nonContentEvents) d.writeEventBinary(event)
            for (event in cappedContent) d.writeEventBinary(event)
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
            val replies = replyCounts.toMap()
            d.writeInt(replies.size)
            for ((id, count) in replies) { d.writeStr(id); d.writeInt(count) }
            val reposts = repostCounts.toMap()
            d.writeInt(reposts.size)
            for ((id, count) in reposts) { d.writeStr(id); d.writeInt(count) }
            val reactions = reactionCounts.toMap()
            d.writeInt(reactions.size)
            for ((id, count) in reactions) { d.writeStr(id); d.writeInt(count) }
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
            val timelineEntries = timelineServiceProvider.get().snapshotData()
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

        val followsBytes = followsBuf.toByteArray()
        val eventsBytes = eventsBuf.toByteArray()
        val aggregatesBytes = aggregatesBuf.toByteArray()
        val relayHealthBytes = relayHealthBuf.toByteArray()
        val timelinesBytes = timelinesBuf.toByteArray()

        val followsOffset = SNAPSHOT_HEADER_SIZE
        val eventsOffset = followsOffset + followsBytes.size
        val aggregatesOffset = eventsOffset + eventsBytes.size
        val relayHealthOffset = aggregatesOffset + aggregatesBytes.size
        val timelinesOffset = relayHealthOffset + relayHealthBytes.size

        // Header
        out.write(SNAPSHOT_BINARY_MAGIC)
        out.writeInt(SNAPSHOT_BINARY_VERSION)
        out.writeInt(followsOffset)
        out.writeInt(eventsOffset)
        out.writeInt(aggregatesOffset)
        out.writeInt(relayHealthOffset)
        out.writeInt(totalEvents)
        out.writeInt(timelinesOffset) // was reserved; V12+ carries timelines offset

        // Sections in offset order.
        out.write(followsBytes)
        out.write(eventsBytes)
        out.write(aggregatesBytes)
        out.write(relayHealthBytes)
        out.write(timelinesBytes)
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

        // FOLLOWS section
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
            if (pks.isNotEmpty()) {
                followsByPubkey[pubkey] = pks
                followsCreatedAt[pubkey] = createdAt
            }
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
            val event = input.readEventBinary()
            insertFromSnapshot(event)
        }

        // AGGREGATES section
        val replyN = input.readInt()
        if (replyN < 0 || replyN > 5_000_000) throw IOException("Invalid reply count: $replyN")
        for (i in 0 until replyN) {
            val id = input.readStr(); val c = input.readInt()
            replyCounts[id] = c
        }
        val repostN = input.readInt()
        if (repostN < 0 || repostN > 5_000_000) throw IOException("Invalid repost count: $repostN")
        for (i in 0 until repostN) {
            val id = input.readStr(); val c = input.readInt()
            repostCounts[id] = c
        }
        val reactionN = input.readInt()
        if (reactionN < 0 || reactionN > 5_000_000) throw IOException("Invalid reaction count: $reactionN")
        for (i in 0 until reactionN) {
            val id = input.readStr(); val c = input.readInt()
            reactionCounts[id] = c
        }
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

        // Reindex kind-7 reactions from raw events so the widened shortcode regex
        // reclassifies old Standard(":shortcode:") entries as Custom(shortcode, url).
        reindexReactionsFromEvents()

        // Rebuild notification recipient index from restored events.
        // insertFromSnapshot already indexed each event, but rebuildNotificationIndex
        // is idempotent and ensures consistency after any future restore-path changes.
        rebuildNotificationIndex()

        // End-of-restore signal bumps (matches V2 reader)
        val now = System.nanoTime()
        _feedSignal.value = now
        _profileSignal.value = now
        _statsSignal.value = now
        _followsSignal.value = now
        _actionSignal.value = now
        _trustScoreSignal.value = now
        _relayMonitorSignal.value = now
        _emojiSetSignal.value = now
        _snapshotRestoredSignal.value = now
        _statsInvalidations.tryEmit(StatsInvalidation.Broadcast)

        evictOldContentEvents()

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

    private fun DataInputStream.readEventBinary(): NostrEvent {
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
            tagsJson = tagsToJson(tags),
            sig = sig,
            relayUrl = relayUrl,
            replyToId = replyToId,
            rootId = rootId,
            hasContentWarning = hasCW,
            contentWarningReason = cwReason,
            firstSeenAt = firstSeenAt,
            relaysSeen = relaysSeen,
        )
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

    private fun insertFromSnapshot(event: NostrEvent) {
        val nowSec = System.currentTimeMillis() / 1000L
        if (event.createdAt > nowSec + MAX_FUTURE_DRIFT_SECONDS) return
        if (event.kind in DERIVED_ONLY_KINDS) {
            insertDerivedOnly(event, snapshotDirtySink)
            return
        }

        eventsById[event.id] = event
        idsByKind.getOrPut(event.kind) { ConcurrentHashMap.newKeySet() }.add(event.id)
        idsByPubkey.getOrPut(event.pubkey) { ConcurrentHashMap.newKeySet() }.add(event.id)
        recentByCreatedAt.add(EventEntry(event.id, event.createdAt))
        // Restored ≠ recently used. Use firstSeenAt (epoch millis, same unit as
        // lastTouchedAt) so eviction's LRU correctly targets cold restored data
        // rather than live relay events the user is currently viewing.
        lastTouchedAt[event.id] = event.firstSeenAt

        if (event.replyToId != null) {
            idsByReplyTarget.getOrPut(event.replyToId) { ConcurrentHashMap.newKeySet() }.add(event.id)
        }
        if (event.rootId != null && event.rootId != event.replyToId) {
            idsByReplyTarget.getOrPut(event.rootId) { ConcurrentHashMap.newKeySet() }.add(event.id)
        }

        indexRelayHints(event)
        indexNotificationRecipients(event)

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
        // Pass snapshotDirtySink so handlers don't bump signals per-event
        // during the 21s parse loop. End-of-restore bumps every signal once.
        val sink = snapshotDirtySink
        when (event.kind) {
            0 -> handleProfile(event)
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
        blossomServersByPubkey.clear()
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
        _statsInvalidations.tryEmit(StatsInvalidation.Broadcast)
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
        repostPubkeysByTarget.clear()
        reactionsByTarget.clear()
        zapDetailsByTarget.clear()
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
        relayMonitorsByUrl.clear()
        _relaySetSignal.value = 0L
        _emojiSetSignal.value = 0L
        _trustScoreSignal.value = 0L
        _relayMonitorSignal.value = 0L
        _statsInvalidations.tryEmit(StatsInvalidation.Broadcast)
        timelineServiceProvider.get().clear()
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
