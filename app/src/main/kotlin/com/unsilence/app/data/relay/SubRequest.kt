package com.unsilence.app.data.relay

/**
 * A single relay-group subscription request — typically one SubRequest per
 * write-relay group from outbox routing. Mirrors Jumble's TFeedSubRequest.
 *
 * Multiple SubRequests compose a "timeline" — TimelineService runs each
 * concurrently, merges results via mergeTimelines, exposes a unified
 * sorted feed via callbacks.
 */
data class SubRequest(
    val urls: List<String>,
    val filter: NostrFilter,
)
