package com.unsilence.app.data.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleCardSchedulingTest {
    @Test fun `engagement is accumulated while the media probe is still suspended`() = runTest {
        val media = CompletableDeferred<Unit>()
        val stages = mutableListOf<String>()
        val job = launch {
            hydrateVisibleCardWork(
                events = emptyList(),
                hydrateMedia = { stages += "probe"; media.await(); stages += "media-done" },
                accumulateOwn = { stages += "own" },
                accumulatePublic = { stages += "public" },
            )
        }
        runCurrent()
        try {
            assertEquals(listOf("own", "public", "probe"), stages)
        } finally {
            media.complete(Unit)
            job.join()
        }
        assertEquals(listOf("own", "public", "probe", "media-done"), stages)
    }

    @Test fun `narrow engagement keeps media warm-up and explicit empty selection skips both engagement paths`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val rows = (0..13).map { id -> store.synthesizeFeedRow(NostrEvent(
            "row-$id", "author", 1, "body", 1L, emptyList(), "", "wss://fixture.example",
            null, null, false, null, 1L, mutableSetOf(),
        )) }
        for (selection in listOf(rows.take(9), emptyList())) {
            var media = emptyList<String>()
            var own = emptyList<String>()
            var public = emptyList<String>()
            hydrateVisibleCardWork(
                events = rows, viewportIds = rows.map { it.id }.toSet(), engagementRows = selection,
                hydrateMedia = { media = it.map { row -> row.id } },
                accumulateOwn = { own = it.map { row -> row.id } },
                accumulatePublic = { public = it.map { row -> row.id } },
            )
            assertEquals(rows.map { it.id }, media)
            assertEquals(selection.map { it.id }, own)
            assertEquals(selection.map { it.id }, public)
        }
        var selected = emptyList<String>()
        hydrateVisibleCardWork(rows, setOf(rows.last().id),
            hydrateMedia = { assertEquals(listOf(rows.last()), it) },
            accumulateOwn = { assertEquals(listOf(rows.last()), it) },
            accumulatePublic = { selected = it.map { row -> row.id } })
        assertEquals(listOf(rows.last().id), selected)
    }
}
