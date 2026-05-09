package com.unsilence.app.data.relay

/**
 * Connection-priority tier for a [SubRequest].
 *
 * FAST — user's read relays and the top write relays for the follow set;
 *        connected and REQ-sent immediately on subscribe.
 * SLOW — obscure outbox write relays that round out long-tail coverage;
 *        connection is deferred until the FAST tier EOSEs or a short
 *        watchdog elapses, so a quick feed swap or close avoids paying
 *        for low-value WebSocket handshakes the user may never read from.
 */
enum class SubTier { FAST, SLOW }

/**
 * A single relay-group subscription request — typically one SubRequest per
 * write-relay group from outbox routing. Mirrors Jumble's TFeedSubRequest.
 *
 * Multiple SubRequests compose a "timeline" — TimelineService runs each
 * concurrently, merges results via mergeTimelines, exposes a unified
 * sorted feed via callbacks.
 *
 * [tier] lets the resolver mark obscure relays for deferred connect; see
 * [SubTier] and TimelineService.subscribeTimeline.
 */
data class SubRequest(
    val urls: List<String>,
    val filter: NostrFilter,
    val tier: SubTier = SubTier.FAST,
    val onlyReplies: Boolean = false,
)
