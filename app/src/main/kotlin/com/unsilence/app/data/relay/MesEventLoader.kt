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
) : TimelineEventLoader {
    override suspend fun getEvents(ids: List<String>): List<NostrEvent> {
        if (ids.isEmpty()) return emptyList()
        // mes.eventsByIds returns an unordered set — sort desc by createdAt
        // to match the contract.
        return mes.eventsByIds(ids.toSet())
            .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
    }
}
