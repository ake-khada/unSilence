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
    val replyCountEntries: Int,
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
            // Engagement: ~40 bytes per entry (64-char key + int/long value)
            (replyCountEntries + repostCountEntries + reactionCountEntries + zapStatsEntries + statsUpdatedAtEntries) * 40L +
            // Sidecars: ~200 bytes avg per entry (EventModel ~600 bytes avg)
            (videoRenderModelEntries + imetaImageDimEntries) * 200L +
            eventModelEntries * 600L +
            feedRowCacheEntries * 400L +
            // Follows: ~128 bytes per entry (key + Set ref)
            followsEntries * 128L +
            followerCountEntries * 48L +
            // Relay config/health: ~300 bytes per entry
            (relayListEntries + trustScoreEntries + relayMonitorEntries + relaySetEntries) * 300L +
            pendingRelayEntries * 128L
}
