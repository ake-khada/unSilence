package com.unsilence.app.data.memory

import androidx.compose.runtime.Immutable

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

/**
 * Entry in the createdAt-sorted index. Implements comparison for
 * ConcurrentSkipListSet ordering: descending by createdAt, ascending by id
 * for deterministic tie-breaking.
 */
data class EventEntry(val id: String, val createdAt: Long)

/**
 * Feed query filter. Determines which events appear in a feed flow.
 */
data class FeedFilter(
    val kinds: Set<Int> = setOf(1, 6, 30023),
    val followedPubkeys: Set<String>? = null,
)

/**
 * UI row contract. Replaces the Room-based FeedRow from EventDao.
 * Contains event data + denormalized author info + engagement counts.
 *
 * @Immutable: all fields are val, all collection types are immutable
 * interfaces (List, not MutableList). Compose compiler treats stdlib
 * List/Set as potentially unstable without this annotation.
 */
@Immutable
data class FeedRow(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val content: String,
    val createdAt: Long,
    val tags: List<List<String>>,
    val relayUrl: String,
    val replyToId: String?,
    val rootId: String?,
    val hasContentWarning: Boolean,
    val contentWarningReason: String?,
    val firstSeenAt: Long,
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
