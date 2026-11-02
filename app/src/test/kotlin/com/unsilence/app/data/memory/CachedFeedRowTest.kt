package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import org.junit.Assert.*
import org.junit.Test
import com.unsilence.app.data.relay.stubTimelineServiceProvider

class CachedFeedRowTest {
    private val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
    private val note = NostrEvent(
        "note", "author", 1, "Already displayed", 100L, emptyList(), "sig", "",
        null, null, false, null, 100_000L, java.util.concurrent.ConcurrentHashMap.newKeySet(),
    )

    @Test fun `peek only returns an existing projection and never synthesizes one`() {
        assertNull(store.cachedFeedRow(note.id))
        store.insert(note)
        assertNull(store.cachedFeedRow(note.id))
        val rendered = store.feedRowsByIds(setOf(note.id)).single()
        assertSame(rendered, store.cachedFeedRow(note.id))
    }

    @Test fun `valid deletion revokes cached presentation`() {
        store.insert(note)
        store.feedRowsByIds(setOf(note.id))
        store.insert(note.copy(id = "delete", kind = 5, createdAt = 101L,
            tags = listOf(listOf("e", note.id))))
        assertNull(store.cachedFeedRow(note.id))
    }

    @Test fun `account teardown cannot expose the previous cached row`() {
        store.insert(note)
        store.feedRowsByIds(setOf(note.id))
        store.clear()
        assertNull(store.cachedFeedRow(note.id))
    }

    @Test fun `cached but evicted row is not an initial presentation source`() {
        store.insert(note)
        store.feedRowsByIds(setOf(note.id))
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertNull(store.getNostrEvent(note.id))
        assertNull(store.cachedFeedRow(note.id))
    }
}
