package com.unsilence.app.ui.feed

import androidx.collection.LruCache
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.TimelineMerge
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import org.junit.Assert.*
import org.junit.Test

internal fun pagingEvents(first: Int, count: Int) = (first until first + count).map { n ->
    EventDto(id = (n + 100_000).toString(16).padStart(64, '0'), pubkey = "a".repeat(64),
        createdAt = 100_000L - n, kind = 1, tags = emptyList(), content = "note $n",
        sig = "b".repeat(128)).toNostrEvent("wss://relay.example")
}

class FeedPagingWindowTest {
    @Test fun `normal window remains 500 displayed and 1000 raw`() {
        val timeline = FeedTimeline().merge(pagingEvents(0, 2000))
        assertEquals(1000, timeline.events.size)
        assertEquals(500, timeline.paging.project(timeline.events).events.size)
    }

    @Test fun `buffered rows after 500 are exposed a page at a time`() {
        val events = pagingEvents(0, 1000)
        val base = FeedPagingWindow()
        val next = base.project(events).nextBufferedEnd!!
        val expanded = base.advance(next).project(events)
        assertEquals(events.take(600), expanded.events)
        assertEquals(events[699].pageCursor(), expanded.nextBufferedEnd)
    }

    @Test fun `a network page remains in raw and displayed windows after replay and history merges`() {
        val original = pagingEvents(0, 1000)
        // Only half the raw rows pass policy, as with conversations/mutes.
        val older = pagingEvents(1000, 100)
        var timeline = FeedTimeline(events = original,
            paging = FeedPagingWindow().advance(older.last().pageCursor())).merge(older)
        val before = timeline.events.filterIndexed { index, _ -> index % 2 == 0 }
        assertEquals(550, timeline.paging.project(before).events.size)
        timeline = timeline.merge(pagingEvents(-100, 100))
        timeline = timeline.merge(pagingEvents(1200, 300))
        assertEquals(1100, timeline.events.size)
        assertTrue(timeline.events.containsAll(older))
        assertEquals(before, timeline.paging.project(timeline.events.filterIndexed { index, _ -> index % 2 == 0 }).events)
    }

    @Test fun `new accepted rows above paged cursor cannot hide its bottom`() {
        val initial = pagingEvents(0, 1000)
        val window = FeedPagingWindow().advance(initial[599].pageCursor())
        val recovered = pagingEvents(-100, 100)
        val merged = window.merge(initial, recovered)
        val projected = window.project(merged).events
        assertEquals(600, projected.size)
        assertEquals(initial[599], projected.last())
        assertTrue(projected.containsAll(initial.subList(495, 600)))
        assertEquals(initial[699].pageCursor(), window.project(merged).nextBufferedEnd)
    }

    @Test fun `cursor ordering includes timestamp ties and survives deleting cursor event`() {
        val events = pagingEvents(0, 800).map { it.copy(createdAt = 100) }
        val window = FeedPagingWindow().advance(events[599].pageCursor())
        assertEquals(events.take(600), window.project(events).events)
        val withoutCursor = events.filterNot { it.id == events[599].id }
        assertEquals(events.take(599), window.project(withoutCursor).events)
        assertEquals(events[699].pageCursor(), window.project(withoutCursor).nextBufferedEnd)
    }

    @Test fun `paging has a hard bound and resets restore default limits with a new generation`() {
        var timeline = FeedTimeline(events = pagingEvents(0, 1000))
        repeat(FeedPagingWindow.MAX_PAGES) { n ->
            val older = pagingEvents(1000 + n * 100, 100)
            timeline = timeline.copy(paging = timeline.paging.advance(older.last().pageCursor())).merge(older)
        }
        assertEquals(2500, timeline.events.size)
        assertEquals(2000, timeline.paging.project(timeline.events).events.size)
        assertFalse(timeline.paging.canLoadMore)
        assertNull(timeline.paging.project(timeline.events).nextBufferedEnd)
        val reset = timeline.resetPaging()
        assertEquals(1000, reset.events.size)
        assertEquals(500, reset.paging.project(reset.events).events.size)
        assertTrue(reset.paging.canLoadMore)
        assertNotEquals(timeline.paging.generation, reset.paging.generation)
    }

    @Test fun `paged merge retains existing rows but fills spare capacity without duplicates`() {
        val existing = pagingEvents(0, 300)
        val window = FeedPagingWindow().advance(existing.last().pageCursor())
        val merged = window.merge(existing, pagingEvents(-500, 2000))
        assertEquals(window.rawLimit, merged.size)
        assertTrue(merged.containsAll(existing))
        assertEquals(merged.size, merged.map { it.id }.toSet().size)
        assertEquals(TimelineMerge.sort(merged), merged)
    }

    @Test fun `largest projection resolves all rows even with a smaller LRU and missing MES payloads`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val events = pagingEvents(0, 2000)
        val cache = LruCache<String, FeedRow>(1000)
        val rows = events.map(store::synthesizeFeedRow)
        rows.take(1000).forEach { cache.put(it.id, it) }
        var fetched = emptySet<String>()
        var synthesized = 0
        val projected = resolveFeedRows(events, cache, load = { ids ->
            fetched = ids
            rows.subList(1000, 1500)
        }, synthesize = { synthesized++; store.synthesizeFeedRow(it) })
        assertEquals(events.map { it.id }, projected.map { it.id })
        assertEquals(1000, fetched.size)
        assertEquals(500, synthesized)
        assertEquals(1000, cache.size())
        val repeated = resolveFeedRows(events, cache,
            load = { error("Unchanged displayed rows must not reload after LRU eviction") },
            synthesize = { error("Unchanged rows must not be rebuilt") }, previousRows = projected)
        assertEquals(projected, repeated)
        assertTrue(projected.indices.all { projected[it] === repeated[it] })
    }

    @Test fun `reading retention never bypasses a new mute or deletion exclusion`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val events = pagingEvents(0, 1000)
        val window = FeedPagingWindow().advance(events[599].pageCursor())
        val previous = events.take(600).map(store::synthesizeFeedRow)
        val hidden = setOf(events[20].id, events[599].id)
        val incoming = window.merge(events, pagingEvents(-100, 100))
        val filtered = incoming.filterNot { it.id in hidden }
        val projected = window.project(filtered, previous)
        assertTrue(projected.events.none { it.id in hidden })
        assertTrue(projected.events.map { it.id }.containsAll(previous.map { it.id }.filterNot { it in hidden }))
        assertTrue(projected.events.size <= window.displayLimit)
    }
}
