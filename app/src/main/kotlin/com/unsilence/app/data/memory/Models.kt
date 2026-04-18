package com.unsilence.app.data.memory

/**
 * In-memory Nostr event representation. Pre-parsed NIP-10 threading
 * and NIP-36 content warnings. Tags stored as parsed lists, not JSON.
 *
 * NOT a UI-facing type — never passed to @Composable functions.
 * The MutableSet<String> on relaysSeen is intentional: relay provenance
 * accumulates across duplicate arrivals via insert()'s dedup path.
 * Must be a ConcurrentHashMap.newKeySet() — iterated on Default dispatcher
 * (feedFlow scans) while mutated on IO (addRelaySeen, insert dedup).
 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val content: String,
    val createdAt: Long,
    val tags: List<List<String>>,
    val sig: String,
    val relayUrl: String,
    val replyToId: String?,
    val rootId: String?,
    val hasContentWarning: Boolean,
    val contentWarningReason: String?,
    val firstSeenAt: Long,
    val relaysSeen: MutableSet<String>,
)

data class ZapAggregate(val count: Int, val totalSats: Long) {
    companion object {
        val EMPTY = ZapAggregate(0, 0L)
    }
}

data class RelayList(val read: List<String>, val write: List<String>)

data class MuteList(val pubkeys: Set<String>, val words: Set<String>)

/** Kind-10002 relay config with marker info (read/write/both). */
data class RelayConfig(val url: String, val marker: String?)

/** Kind-10012 favorite entry — either a relay URL or a set reference (["a", "30002:pubkey:dtag"]). */
data class FavoriteEntry(val url: String?, val setRef: String?)

/** Kind-30002 relay set metadata. One per (ownerPubkey, dTag). */
data class RelaySet(
    val dTag: String,
    val ownerPubkey: String,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val members: List<String> = emptyList(),
)

/**
 * A single notification item — MES equivalent of Room's NotificationRow.
 *
 * Built at scan time from eventsById + profile lookups.
 * Carries enough resolved data for the UI to render without additional lookups.
 */
data class NotificationItem(
    val id: String,
    val notifType: String,   // "reaction" | "reply" | "repost" | "zap" | "mention"
    val actorPubkey: String,
    val actorName: String?,
    val actorDisplayName: String?,
    val actorPicture: String?,
    val targetNoteId: String?,
    val targetNoteContent: String,
    val parentNoteContent: String,
    val createdAt: Long,
)

/** Pinned relay in the feed picker. Pure local config, not a Nostr event. */
data class PinnedRelay(
    val pubkey: String,
    val url: String,
    val displayLabel: String?,
    val addedAt: Long = System.currentTimeMillis() / 1000,
)

/**
 * Entry in the createdAt-sorted index. Implements comparison for
 * ConcurrentSkipListSet ordering: descending by createdAt, ascending by id
 * for deterministic tie-breaking.
 */
data class EventEntry(val id: String, val createdAt: Long)

/**
 * Feed query filter. Determines which events appear in a feed flow.
 *
 * Structural filters only — these are applied inside the walk so that
 * [limit] counts accepted rows, not scanned rows. Presentation filters
 * (media type, sinceHours, engagement minimums) stay in FeedViewModel.
 *
 * @param contentFilter 0 = all, 1 = notes only (no replies), 2 = replies only
 * @param relayUrls When non-null, only events seen on at least one of these relays pass.
 */
data class FeedFilter(
    val kinds: Set<Int> = setOf(1, 6, 30023),
    val followedPubkeys: Set<String>? = null,
    val contentFilter: Int = 0,
    val relayUrls: Set<String>? = null,
)

// ── Types moved from Room entity/dao layer (A.8) ────────────────────────────

/**
 * Event data class. Formerly a Room @Entity; now a plain data class
 * used throughout the app as the canonical event representation.
 */
data class EventEntity(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val content: String,
    val createdAt: Long,
    /** JSON-serialized List<List<String>> */
    val tags: String,
    val sig: String = "",
    val relayUrl: String = "",
    val replyToId: String? = null,
    val rootId: String? = null,
    val hasContentWarning: Boolean = false,
    val contentWarningReason: String? = null,
    val cachedAt: Long = 0,
    val zapTotalSats: Long = 0,
    val firstSeenAt: Long = 0,
)

/**
 * User profile data class. Formerly a Room @Entity; now a plain data class.
 */
data class UserEntity(
    val pubkey: String,
    val name: String? = null,
    val displayName: String? = null,
    val about: String? = null,
    val picture: String? = null,
    val nip05: String? = null,
    val lud16: String? = null,
    val banner: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val followerCount: Long? = null,
    val followerCountUpdatedAt: Long? = null,
)

/**
 * Flattened feed row — event + author + engagement counts.
 * Formerly defined in EventDao with @ColumnInfo; now a plain data class.
 */
@androidx.compose.runtime.Immutable
data class FeedRow(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val content: String,
    val createdAt: Long,
    val tags: String,
    val relayUrl: String,
    val replyToId: String?,
    val rootId: String?,
    val hasContentWarning: Boolean,
    val contentWarningReason: String?,
    val cachedAt: Long,
    val zapTotalSats: Long,
    val authorName: String?,
    val authorDisplayName: String?,
    val authorPicture: String?,
    val authorNip05: String?,
    val reactionCount: Int,
    val replyCount: Int,
    val repostCount: Int,
    val zapCount: Int,
)

/**
 * Session-scoped relay sync state. Formerly a Room @Entity; now a plain data class
 * used by SyncTracker (in-memory ConcurrentHashMap).
 */
data class SyncStateEntity(
    val subscriptionKey: String,
    val lastSyncAt: Long,
    val lastEventCount: Int,
    val source: String,
)

/**
 * Relay trust score (kind 30385). Formerly a Room @Entity; now a plain data class.
 * Populated by MES handleTrustScore() from kind-30385 events fetched via
 * RelayPool.fetchTrustScores(). UI renders colored dots in Relay Settings.
 */
data class RelayTrustScoreEntity(
    val relayUrl: String,
    val score: Int,
    val reliability: Int,
    val quality: Int,
    val accessibility: Int,
    val confidence: String,
    val observations: Int,
    val policy: String? = null,
    val countryCode: String? = null,
    val operatorVerified: String? = null,
    val updatedAt: Long = 0L,
)

/**
 * Relay liveness monitor (kind 30166 / NIP-66). Populated by MES
 * handleRelayMonitor() from events fetched via RelayPool.fetchRelayMonitors().
 * Source: wss://relay.nostr.watch — operational health, RTT, NIP support.
 */
data class RelayMonitorEntity(
    val relayUrl: String,
    val rttOpen: Int? = null,
    val rttRead: Int? = null,
    val rttWrite: Int? = null,
    val supportedNips: List<Int> = emptyList(),
    val network: String? = null,
    val requirements: List<String> = emptyList(),
    val geohash: String? = null,
    val iconUrl: String? = null,
    val monitorPubkey: String,
    val createdAt: Long,
)

/**
 * Combined relay health: trust quality (kind 30385) + operational liveness (kind 30166).
 * Keyed by normalized relay URL. Either or both sources may be present.
 */
data class RelayHealthInfo(
    val relayUrl: String,
    val trustScore: RelayTrustScoreEntity? = null,
    val monitor: RelayMonitorEntity? = null,
) {
    val score: Int? get() = trustScore?.score
    val ping: Int? get() = monitor?.rttOpen ?: monitor?.rttRead
    val iconUrl: String? get() = monitor?.iconUrl
}

/**
 * Instrumentation result from a paginated fetch session.
 */
data class PaginatedFetchResult(
    val totalEvents: Int,
    val totalPages: Int,
    val oldestCreatedAt: Long,
    val relay: String,
)
