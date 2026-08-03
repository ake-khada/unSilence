package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P2 stub implementation of TimelineEventLoader. Delegates to MES.
 * P3 will replace this with EventCache (DataLoader-style batching).
 */
@Singleton
class MesEventLoader @Inject constructor(
    private val mes: MemoryEventStore,
    private val relayPool: dagger.Lazy<RelayPool>,
) : TimelineEventLoader {
    override suspend fun getEvents(ids: List<String>): TimelineEventResolution {
        if (ids.isEmpty()) return TimelineEventResolution(emptyList(), emptyList())
        return timelineEventResolution(ids, mes.eventsByIds(ids.toSet()))
    }

    override suspend fun repairEvents(
        ids: List<String>,
        relayHints: List<String>,
    ): TimelineEventResolution {
        val local = getEvents(ids)
        if (local.missingIds.isEmpty()) return local
        relayPool.get().fetchTimelineEventsByIds(local.missingIds, relayHints)
        return getEvents(ids)
    }
}
