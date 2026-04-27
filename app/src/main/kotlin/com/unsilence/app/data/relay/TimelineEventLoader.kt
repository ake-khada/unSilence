package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent

/**
 * Batched event-by-id lookup. P2 ships a thin MES-backed implementation;
 * P3 will replace it with EventCache (DataLoader-style batching).
 *
 * MUST return events sorted desc by createdAt — TimelineService relies on
 * cachedEvents[0] being the head for since-injection.
 */
interface TimelineEventLoader {
    suspend fun getEvents(ids: List<String>): List<NostrEvent>
}
