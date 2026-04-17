package com.unsilence.app.data.memory

/**
 * In-memory Nostr event representation. Pre-parsed NIP-10 threading
 * and NIP-36 content warnings. Tags stored as parsed lists, not JSON.
 *
 * NOT a UI-facing type — never passed to @Composable functions.
 * The MutableSet<String> on relaysSeen is intentional: relay provenance
 * accumulates across duplicate arrivals via insert()'s dedup path.
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
