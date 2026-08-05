package com.unsilence.app.data.memory

private const val ART_OBJECT_ALIGNMENT = 8L
private const val ART_STRING_SHALLOW_BYTES = 24L
private const val ART_ARRAY_HEADER_BYTES = 16L
private const val ART_ARRAY_LIST_SHALLOW_BYTES = 24L
private const val ART_REFERENCE_BYTES = 4L
private const val NOSTR_EVENT_SHALLOW_BYTES = 72L
private const val CHM_SHALLOW_BYTES = 64L
private const val CHM_KEY_SET_VIEW_BYTES = 16L
private const val CHM_NODE_BYTES = 24L

/**
 * Amortized ownership outside the event object itself: eventsById node/table,
 * kind/author memberships, recent-order entry, touch map, and admission index.
 * Calibrated from the 2026-08-05 ART/MAT capture; kept explicit so future heap
 * captures can adjust the model rather than hiding a multiplier in telemetry.
 */
private const val PRIMARY_EVENT_INDEX_BYTES = 640L

private fun alignArt(bytes: Long): Long =
    (bytes + ART_OBJECT_ALIGNMENT - 1L) and -ART_OBJECT_ALIGNMENT

private fun artStringPayloadBytes(value: String): Long {
    // ART compact strings use one byte for Latin-1 and two for other UTF-16
    // code units. Scan without allocating an encoded byte array in the probe.
    val bytesPerChar = if (value.all { it.code <= 0xff }) 1L else 2L
    return value.length * bytesPerChar
}

internal fun estimateArtStringBytes(value: String): Long =
    ART_STRING_SHALLOW_BYTES +
        alignArt(ART_ARRAY_HEADER_BYTES + artStringPayloadBytes(value))

private fun estimateArtListBytes(size: Int): Long =
    ART_ARRAY_LIST_SHALLOW_BYTES +
        alignArt(ART_ARRAY_HEADER_BYTES + size * ART_REFERENCE_BYTES)

/** Estimated retained ART heap attributable to one indexed [NostrEvent]. */
internal fun estimateNostrEventRetainedBytes(event: NostrEvent): Long {
    var bytes = NOSTR_EVENT_SHALLOW_BYTES + PRIMARY_EVENT_INDEX_BYTES
    bytes += estimateArtStringBytes(event.id)
    bytes += estimateArtStringBytes(event.pubkey)
    bytes += estimateArtStringBytes(event.content)
    bytes += estimateArtStringBytes(event.sig)
    bytes += estimateArtStringBytes(event.relayUrl)
    event.replyToId?.let { bytes += estimateArtStringBytes(it) }
    event.rootId?.let { bytes += estimateArtStringBytes(it) }
    event.contentWarningReason?.let { bytes += estimateArtStringBytes(it) }

    bytes += estimateArtListBytes(event.tags.size)
    for (tag in event.tags) {
        bytes += estimateArtListBytes(tag.size)
        for (cell in tag) bytes += estimateArtStringBytes(cell)
    }

    // ConcurrentHashMap.newKeySet(): view + map + lazily allocated table and
    // one node per relay. The primary relay string is already counted above.
    val relayCount = event.relaysSeen.size
    bytes += CHM_KEY_SET_VIEW_BYTES + CHM_SHALLOW_BYTES
    if (relayCount > 0) {
        val tableSlots = 16 // CHM's first allocation for the tiny provenance set
        bytes += alignArt(ART_ARRAY_HEADER_BYTES + tableSlots * ART_REFERENCE_BYTES)
        bytes += relayCount * CHM_NODE_BYTES
        for (relay in event.relaysSeen) {
            if (relay != event.relayUrl) bytes += estimateArtStringBytes(relay)
        }
    }
    return bytes
}

/**
 * Point-in-time snapshot of MemoryEventStore collection sizes.
 * Produced by [MemoryEventStore.snapshotSize] — no locking, relies on
 * ConcurrentHashMap's weakly-consistent iteration semantics.
 */
data class MesSizeSnapshot(
    // ── Primary store ───────────────────────────────────────────────────
    val eventCount: Int,
    val eventBytes: Long,
    /** kind → count for content kinds, including regular/addressable NIP-71 video. */
    val eventsByKind: Map<Int, Int>,

    // ── Profiles & follows ──────────────────────────────────────────────
    val profileCount: Int,
    val profileBytes: Long,
    val followsEntries: Int,
    val followerCountEntries: Int,

    // ── Engagement aggregates ───────────────────────────────────────────
    /** Unique target memberships in the live reply-id index. */
    val replyIndexEntries: Int,
    val repostCountEntries: Int,
    val reactionCountEntries: Int,
    val zapStatsEntries: Int,
    val statsUpdatedAtEntries: Int,

    // ── Actor-side indexes ──────────────────────────────────────────────
    val reactedActors: Int,
    val reactedTargetsTotal: Int,
    val repostedActors: Int,
    val repostedTargetsTotal: Int,
    val zappedActors: Int,
    val zappedTargetsTotal: Int,

    // ── Media sidecars ──────────────────────────────────────────────────
    val videoRenderModelEntries: Int,
    val imetaImageDimEntries: Int,
    val eventModelEntries: Int,
    val feedRowCacheEntries: Int,

    // ── Relay config & health ───────────────────────────────────────────
    val relayListEntries: Int,
    val trustScoreEntries: Int,
    val relayMonitorEntries: Int,
    val relayIdentityEntries: Int,
    val relaySetEntries: Int,

    // ── Dedup & provenance ──────────────────────────────────────────────
    val pendingRelayEntries: Int,

    // ── Profile pipeline anchoring ─────────────────────────────────────
    val profileAnchoredRefEntries: Int = 0,
) {
    /** Rough total estimated bytes across all measured collections. */
    val totalEstimatedBytes: Long get() =
        eventBytes + profileBytes +
            // Actor indexes: 64-char hex strings → ~128 bytes per entry (key + value ref)
            (reactedTargetsTotal + repostedTargetsTotal + zappedTargetsTotal) * 128L +
            // Reply set memberships carry an event-id key/reference; scalar
            // engagement maps carry a key + int/long value.
            replyIndexEntries * 128L +
            (repostCountEntries + reactionCountEntries + zapStatsEntries + statsUpdatedAtEntries) * 40L +
            // Sidecars: ~200 bytes avg per entry (EventModel ~600 bytes avg)
            (videoRenderModelEntries + imetaImageDimEntries) * 200L +
            eventModelEntries * 600L +
            feedRowCacheEntries * 400L +
            // Follows: ~128 bytes per entry (key + Set ref)
            followsEntries * 128L +
            followerCountEntries * 48L +
            // Relay config/health: ~300 bytes per entry
            (relayListEntries + trustScoreEntries + relayMonitorEntries + relayIdentityEntries + relaySetEntries) * 300L +
            pendingRelayEntries * 128L
}

/** Eviction work accumulated since the previous metrics sample. */
data class MesEvictionSnapshot(
    val passes: Long,
    val evicted: Long,
    /** Followed-author events; strongest bounded protection. */
    val tier1: Long,
    /** Events referenced by a cached timeline. */
    val tier2: Long,
    /** Ordinary events; evicted before tiers 2 and 1. */
    val tier3: Long,
    /** Existing indexed events replaced at the admission boundary. */
    val admissionReplaced: Long,
    /** Novel events rejected before secondary indexing because they were the victim. */
    val admissionRejected: Long,
    /** Interval removals grouped by event kind. */
    val evictedByKind: Map<Int, Long>,
    /** Interval admission rejections grouped by incoming event kind. */
    val admissionRejectedByKind: Map<Int, Long>,
    /** Unique anchor counts observed by the latest completed eviction pass. */
    val anchoredOwn: Long,
    val anchoredMentioned: Long,
    val anchoredViewed: Long,
    val anchoredProfileRefs: Long,
    /** Size of the most recent per-pass timeline-ref union. */
    val liveTimelineRefs: Long,
)
