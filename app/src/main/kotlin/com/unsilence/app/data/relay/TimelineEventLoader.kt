package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent

/** Result of resolving a requested ID page from the event store. */
data class TimelineEventResolution(
    val events: List<NostrEvent>,
    val missingIds: List<String>,
)

/**
 * Batched event-by-id lookup. P2 ships a thin MES-backed implementation;
 * P3 will replace it with EventCache (DataLoader-style batching).
 *
 * [getEvents] and [repairEvents] MUST return events sorted desc by createdAt.
 * Missing IDs are explicit: silently collapsing a sparse persisted timeline
 * into a shorter list lets pagination advance across holes.
 */
interface TimelineEventLoader {
    suspend fun getEvents(ids: List<String>): TimelineEventResolution

    /** Resolve missing persisted refs from the relays that backed their timeline. */
    suspend fun repairEvents(
        ids: List<String>,
        relayHints: List<String>,
    ): TimelineEventResolution = getEvents(ids)
}

internal fun timelineEventResolution(
    requestedIds: List<String>,
    events: Collection<NostrEvent>,
): TimelineEventResolution {
    val requested = requestedIds.distinct()
    val byId = events.associateBy { it.id }
    return TimelineEventResolution(
        events = byId.values.sortedWith(TimelineService.compareEventsDesc),
        missingIds = requested.filterNot(byId::containsKey),
    )
}
