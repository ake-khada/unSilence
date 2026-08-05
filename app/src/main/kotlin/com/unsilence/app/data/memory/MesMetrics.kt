package com.unsilence.app.data.memory

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
