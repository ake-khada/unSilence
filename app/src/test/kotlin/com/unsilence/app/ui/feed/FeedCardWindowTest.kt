package com.unsilence.app.ui.feed

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.ui.shared.DisplayWindowReporter
import com.unsilence.app.ui.shared.protectedDisplayRows
import com.unsilence.app.ui.shared.collectLatestWhileActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedCardWindowTest {
    private val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
    private fun rows(count: Int) = (0 until count).map { store.synthesizeFeedRow(note("row-$it")) }

    @Test fun `one to eight visible rows fetch only the protected forward range`() {
        val rows = rows(100)
        for (first in listOf(0, 40)) for (count in 1..8) {
            val last = first + count - 1
            val visible = rows.subList(first, last + 1).map { it.id }.toSet()
            val selected = feedEngagementRows(rows, FeedCardViewport(first, last, 360, visible))
            assertEquals("first=$first visible=$count", (first..last + 6).map { "row-$it" }.toSet(), selected.map { it.id }.toSet())
            assertTrue(protectedDisplayRows(rows, first, last, visible).containsAll(selected))
        }
    }

    @Test fun `visible identities survive a prepend with stale viewport indices`() {
        val rows = rows(100)
        val selected = feedEngagementRows(rows, FeedCardViewport(0, 2, 360, setOf("row-40", "row-41", "row-42")))
        assertEquals((40..48).map { "row-$it" }.toSet(), selected.map { it.id }.toSet())
    }

    @Test fun `empty short and final windows never add backward rows`() {
        assertTrue(feedEngagementRows(emptyList(), FeedCardViewport(0, 0, 0)).isEmpty())
        val rows = rows(4)
        assertEquals(rows, feedEngagementRows(rows, FeedCardViewport(0, 0, 0)))
        assertEquals(listOf(rows.last()), feedEngagementRows(rows, FeedCardViewport(3, 3, 360)))
    }

    @Test fun `large viewports retain the existing display bound`() {
        val rows = rows(100)
        val visible = rows.take(40).map { it.id }.toSet()
        val selected = feedEngagementRows(rows, FeedCardViewport(0, 39, 360, visible))
        assertEquals(rows.take(32), selected)
    }

    @Test fun `selected history survives pressure in the published reading window`() {
        store.ownPubkey = "owner"
        store.updateFollows(note("follows").copy(pubkey = "owner", kind = 3, tags = listOf(listOf("p", "followed"))))
        val roots = (0 until 20).map { note("root-$it").copy(pubkey = "owner") }
        store.insertBatch(roots)
        store.insertBatch((0 until 5_000).map { note("history-$it").copy(pubkey = "followed") })
        val rows = roots.map(store::synthesizeFeedRow)
        val visible = rows.take(3).map { it.id }.toSet()
        DisplayWindowReporter(store).use { reporter ->
            reporter.setActive(true)
            reporter.prepareWarmWindow(rows, 0, 2, visible, 360)
            val selected = feedEngagementRows(rows, FeedCardViewport(0, 2, 360, visible))
            for (row in selected) {
                assertTrue("Fetched history for ${row.id} must have reading protection", store.insert(note("reply-${row.id}", row.id)))
                assertEquals(1, store.replyCount(row.id))
            }
            assertFalse(roots[14].id in store.displayPriorities())
            assertTrue("Outside history can replace older unprotected content", store.insert(note("outside", roots[14].id)))
            assertEquals(5_020, store.snapshotSize().eventsByKind[1])
            // Make outside oldest among unprotected content; selected replies are older still.
            store.markTouched(store.recentEvents(setOf(1), limit = 5_020)
                .filter { it.pubkey == "followed" }.map { it.id })
            assertTrue(store.insert(note("pressure")))
            assertNull("Outside history has no reading protection", store.getNostrEvent("outside"))
            assertEquals(0, store.replyCount(roots[14].id))
            for (row in selected) {
                assertNotNull(store.getNostrEvent("reply-${row.id}"))
                assertEquals(1, store.replyCount(row.id))
            }
            assertEquals(5_020, store.snapshotSize().eventsByKind[1])
        }
    }

    @Test fun `protection precedes hydration and retries do not restart asset work`() = runTest {
        val rows = rows(100)
        val active = MutableStateFlow(true)
        val viewports = MutableStateFlow(FeedCardViewport(0, 2, 0, rows.take(3).map { it.id }.toSet()))
        val retries = MutableStateFlow(0L)
        val fetched = mutableListOf<Set<String>>()
        var assetStarts = 0
        var assetStops = 0
        val reporter = DisplayWindowReporter(store).apply { setActive(true) }
        val job = launch {
            viewports.collectLatestWhileActive(active) { viewport ->
                observeFeedCardWindow(rows, viewport, 50, retries, reporter::report,
                    warmAssets = {
                        assetStarts++
                        try { awaitCancellation() } finally { assetStops++ }
                    },
                    hydrate = { selected ->
                        val ids = selected.map { it.id }.toSet()
                        assertTrue("Protection must precede accumulation", store.displayPriorities().keys.containsAll(ids))
                        fetched += ids
                    },
                )
            }
        }
        try {
            runCurrent()
            assertEquals((0..8).map { "row-$it" }.toSet(), store.displayPriorities().keys)
            assertTrue("Zero width does not postpone protection", fetched.isEmpty())
            advanceTimeBy(300); runCurrent()
            assertEquals(listOf((0..8).map { "row-$it" }.toSet()), fetched)
            retries.value++
            runCurrent(); advanceTimeBy(300); runCurrent()
            assertEquals(2, fetched.size)
            assertEquals("Retries do not restart assets", 1, assetStarts)
            viewports.value = FeedCardViewport(20, 22, 360, setOf("row-20", "row-21", "row-22"))
            runCurrent()
            assertEquals(1, assetStops)
            assertEquals((18..28).map { "row-$it" }.toSet(), store.displayPriorities().keys)
            advanceTimeBy(300); runCurrent()
            assertEquals((20..28).map { "row-$it" }.toSet(), fetched.last())
            reporter.setActive(false)
            active.value = false
            runCurrent()
            retries.value++
            runCurrent(); advanceTimeBy(300); runCurrent()
            assertEquals(3, fetched.size)
            assertEquals(2, assetStops)
        } finally { job.cancelAndJoin(); reporter.close() }
    }

    @Test fun `empty projection releases protection without broad hydration fallback`() = runTest {
        val projections = MutableStateFlow(rows(20))
        val retries = MutableStateFlow(0L)
        val reporter = DisplayWindowReporter(store).apply { setActive(true) }
        var hydrated = 0
        val job = launch {
            projections.collectLatestWhileActive(MutableStateFlow(true)) { rows ->
                observeFeedCardWindow(rows, FeedCardViewport(0, 2, 360), 50, retries,
                    reporter::report, warmAssets = {}, hydrate = { hydrated++ })
            }
        }
        try {
            runCurrent(); advanceTimeBy(300); runCurrent()
            assertEquals(1, hydrated)
            projections.value = emptyList()
            runCurrent()
            assertTrue(store.displayPriorities().isEmpty())
            retries.value++
            runCurrent(); advanceTimeBy(300); runCurrent()
            assertEquals(1, hydrated)
        } finally { job.cancelAndJoin(); reporter.close() }
    }

    @Test fun `forward repost rows protect their actual engagement targets`() = runTest {
        for (kind in listOf(6, 16)) {
            val target = note("target-$kind")
            val wrapper = note("wrapper-$kind").copy(kind = kind, rootId = target.id,
                tags = listOf(listOf("e", target.id)))
            store.insertBatch(listOf(target, wrapper))
            val rows = listOf(store.synthesizeFeedRow(wrapper))
            val reporter = DisplayWindowReporter(store).apply { setActive(true) }
            var fetched = false
            val job = launch {
                observeFeedCardWindow(rows, FeedCardViewport(0, 0, 360, setOf(wrapper.id)), 50,
                    MutableStateFlow(0L), reporter::report, warmAssets = {},
                    hydrate = { selected ->
                        assertEquals(listOf(wrapper.id), selected.map { it.id })
                        assertTrue(store.displayPriorities().keys.containsAll(listOf(wrapper.id, target.id)))
                        fetched = true
                    })
            }
            try {
                runCurrent(); advanceTimeBy(300); runCurrent()
                assertTrue(fetched)
            } finally { job.cancelAndJoin(); reporter.close() }
        }
    }

    private fun note(id: String, parent: String? = null) = NostrEvent(
        id, "ordinary", 1, "body", 1L,
        parent?.let { listOf(listOf("e", it, "", "reply")) }.orEmpty(), "", "wss://fixture.example",
        parent, null, false, null, 1L, mutableSetOf(),
    )
}
