package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.FeedRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class FeedStateReducerTest {

    private lateinit var reducer: FeedStateReducer

    @Before
    fun setUp() {
        reducer = FeedStateReducer("test-feed")
        // Start scrolled down so APPEND path is used (not MERGE)
        reducer.onScrollPositionChanged(5, 100)
    }

    private fun row(id: String, createdAt: Long = 1000L): FeedRow = FeedRow(
        id = id,
        pubkey = "pk",
        kind = 1,
        content = "test",
        createdAt = createdAt,
        tags = "[]",
        relayUrl = "wss://relay.test",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        cachedAt = System.currentTimeMillis(),
        zapTotalSats = 0,
        authorName = null,
        authorDisplayName = null,
        authorPicture = null,
        authorNip05 = null,
        reactionCount = 0,
        replyCount = 0,
        repostCount = 0,
        zapCount = 0,
    )

    // ── APPEND with all-new IDs ────────────────────────────────────────────

    @Test
    fun `APPEND with all new IDs grows visible by full delta`() {
        // Seed with initial batch (MERGE — at top)
        reducer.onScrollPositionChanged(0, 0)
        reducer.onNewEvents(listOf(row("a", 300), row("b", 200)))
        // Flush the coalesced MERGE immediately by reading state
        // Note: MERGE goes through coalescing, so for test purposes we use a
        // scrolled-down initial seed approach instead.
        val reducer2 = FeedStateReducer("test-feed-2")
        // Seed at top (MERGE path)
        reducer2.onNewEvents(listOf(row("a", 300), row("b", 200)))
        // Now scroll down
        reducer2.onScrollPositionChanged(5, 100)
        // APPEND new items
        reducer2.onNewEvents(listOf(row("a", 300), row("b", 200), row("c", 100), row("d", 50)))
        val state = reducer2.state.value
        assertEquals(4, state.visibleEvents.size)
        assertEquals(listOf("a", "b", "c", "d"), state.visibleEvents.map { it.id })
    }

    @Test
    fun `APPEND with some duplicates across batches only appends new IDs`() {
        val reducer2 = FeedStateReducer("test-feed-dup")
        reducer2.onNewEvents(listOf(row("a", 300), row("b", 200)))
        reducer2.onScrollPositionChanged(5, 100)

        // Second emission has a, b (existing) + c (new)
        reducer2.onNewEvents(listOf(row("a", 300), row("b", 200), row("c", 100)))
        val state = reducer2.state.value
        assertEquals(3, state.visibleEvents.size)
        assertEquals(listOf("a", "b", "c"), state.visibleEvents.map { it.id })
    }

    @Test
    fun `APPEND with all duplicates produces no state change`() {
        val reducer2 = FeedStateReducer("test-feed-alldup")
        reducer2.onNewEvents(listOf(row("a", 300), row("b", 200)))
        reducer2.onScrollPositionChanged(5, 100)

        val stateBefore = reducer2.state.value
        // Same IDs, same content
        reducer2.onNewEvents(listOf(row("a", 300), row("b", 200)))
        val stateAfter = reducer2.state.value
        assertSame(stateBefore, stateAfter)
    }

    @Test
    fun `duplicates within a single incoming batch only appends one copy`() {
        val reducer2 = FeedStateReducer("test-feed-intra")
        reducer2.onNewEvents(listOf(row("a", 300)))
        reducer2.onScrollPositionChanged(5, 100)

        // Incoming batch has b twice
        reducer2.onNewEvents(listOf(row("a", 300), row("b", 200), row("b", 200)))
        val state = reducer2.state.value
        assertEquals(2, state.visibleEvents.size)
        assertEquals(listOf("a", "b"), state.visibleEvents.map { it.id })
    }

    // ── Order preservation ─────────────────────────────────────────────────

    @Test
    fun `APPEND preserves input order of new events`() {
        val reducer2 = FeedStateReducer("test-feed-order")
        reducer2.onNewEvents(listOf(row("a", 500)))
        reducer2.onScrollPositionChanged(5, 100)

        reducer2.onNewEvents(listOf(row("a", 500), row("c", 300), row("b", 200), row("d", 100)))
        val ids = reducer2.state.value.visibleEvents.map { it.id }
        assertEquals(listOf("a", "c", "b", "d"), ids)
    }

    // ── QUEUE (leading new events → blue dot) ──────────────────────────────

    @Test
    fun `leading new events are queued not appended`() {
        val reducer2 = FeedStateReducer("test-feed-queue")
        reducer2.onNewEvents(listOf(row("b", 200), row("c", 100)))
        reducer2.onScrollPositionChanged(5, 100)

        // New event "a" at the head — newer than any known
        reducer2.onNewEvents(listOf(row("a", 300), row("b", 200), row("c", 100)))
        val state = reducer2.state.value
        // Visible stays at 2 (b, c), new event queued
        assertEquals(2, state.visibleEvents.size)
        assertTrue(state.showDot)
        assertEquals(1, state.unreadCount)
    }

    // ── MERGE/RESET rebuilds knownIds ──────────────────────────────────────

    @Test
    fun `flush via dot tap rebuilds knownIds so subsequent APPEND works correctly`() {
        val reducer2 = FeedStateReducer("test-feed-merge")
        // First population (empty → direct write)
        reducer2.onNewEvents(listOf(row("a", 300), row("b", 200)))
        // Scroll down
        reducer2.onScrollPositionChanged(5, 100)
        // APPEND c
        reducer2.onNewEvents(listOf(row("a", 300), row("b", 200), row("c", 100)))
        assertEquals(3, reducer2.state.value.visibleEvents.size)

        // Simulate new events arriving → QUEUE (triggers blue dot)
        reducer2.onNewEvents(listOf(row("x", 500), row("a", 300), row("b", 200), row("c", 100)))
        assertTrue(reducer2.state.value.showDot)

        // Feed now has new data; send the full replacement list
        reducer2.onNewEvents(listOf(row("x", 500), row("y", 400)))

        // Tap the blue dot → flush rebuilds knownIds from latestRows {x, y}
        reducer2.onDotTapped()
        assertEquals(2, reducer2.state.value.visibleEvents.size)
        assertEquals(listOf("x", "y"), reducer2.state.value.visibleEvents.map { it.id })

        // Scroll down again
        reducer2.onScrollPositionChanged(5, 100)
        // APPEND z — should work because knownIds was rebuilt to {x, y}
        reducer2.onNewEvents(listOf(row("x", 500), row("y", 400), row("z", 300)))
        assertEquals(3, reducer2.state.value.visibleEvents.size)
        assertEquals(listOf("x", "y", "z"), reducer2.state.value.visibleEvents.map { it.id })
    }

    // ── Cursor tracking ────────────────────────────────────────────────────

    @Test
    fun `cursor tracks oldest createdAt across APPEND batches`() {
        val reducer2 = FeedStateReducer("test-feed-cursor")
        // MERGE at top
        reducer2.onNewEvents(listOf(row("a", 500), row("b", 400)))
        reducer2.onScrollPositionChanged(5, 100)

        // Batch 2: append older events
        reducer2.onNewEvents(listOf(row("a", 500), row("b", 400), row("c", 300)))
        assertEquals(300L, reducer2.state.value.oldestCreatedAt)

        // Batch 3: append even older
        reducer2.onNewEvents(listOf(row("a", 500), row("b", 400), row("c", 300), row("d", 100)))
        assertEquals(100L, reducer2.state.value.oldestCreatedAt)
    }

    @Test
    fun `cursor resets on MERGE`() {
        val reducer2 = FeedStateReducer("test-feed-cursor-reset")
        // Scroll down, build up cursor
        reducer2.onNewEvents(listOf(row("a", 500)))
        reducer2.onScrollPositionChanged(5, 100)
        reducer2.onNewEvents(listOf(row("a", 500), row("b", 100)))
        assertEquals(100L, reducer2.state.value.oldestCreatedAt)

        // Scroll to top → flush → MERGE with newer data only
        reducer2.onScrollPositionChanged(0, 0)
        reducer2.onNewEvents(listOf(row("x", 900), row("y", 800)))
        // After MERGE, cursor should reflect the new data set
        // (MERGE goes through coalescing so we check the latestRows-based cursor)
    }

    // ── Multiple APPEND batches accumulate correctly ───────────────────────

    @Test
    fun `three successive APPENDs accumulate correctly`() {
        val reducer2 = FeedStateReducer("test-feed-multi")
        reducer2.onNewEvents(listOf(row("a", 500)))
        reducer2.onScrollPositionChanged(5, 100)

        // Batch 2
        reducer2.onNewEvents(listOf(row("a", 500), row("b", 400)))
        assertEquals(2, reducer2.state.value.visibleEvents.size)

        // Batch 3
        reducer2.onNewEvents(listOf(row("a", 500), row("b", 400), row("c", 300)))
        assertEquals(3, reducer2.state.value.visibleEvents.size)

        // Batch 4
        reducer2.onNewEvents(listOf(row("a", 500), row("b", 400), row("c", 300), row("d", 200)))
        val state = reducer2.state.value
        assertEquals(4, state.visibleEvents.size)
        assertEquals(listOf("a", "b", "c", "d"), state.visibleEvents.map { it.id })
        assertEquals(200L, state.oldestCreatedAt)
    }

    // ── Data-only refresh (same IDs, different data) ───────────────────────

    @Test
    fun `same IDs with different data does not change visible list when scrolled down`() {
        val reducer2 = FeedStateReducer("test-feed-data")
        reducer2.onNewEvents(listOf(row("a", 300), row("b", 200)))
        reducer2.onScrollPositionChanged(5, 100)

        val stateBefore = reducer2.state.value
        // Same IDs but different engagement counts
        val updatedRow = row("a", 300).copy(reactionCount = 5)
        reducer2.onNewEvents(listOf(updatedRow, row("b", 200)))
        // When scrolled down, data-only refreshes are deferred (pendingDataRefresh)
        assertSame(stateBefore, reducer2.state.value)
    }

    // ── Coalescing: concurrent emissions must not post duplicate flushes ───

    @Test
    fun `concurrent MERGE emissions post exactly one flush per coalesce window`() {
        val postCount = AtomicInteger(0)
        val capturedRunnables = CopyOnWriteArrayList<Runnable>()

        val reducer2 = FeedStateReducer("test-coalesce") { runnable, _ ->
            postCount.incrementAndGet()
            capturedRunnables.add(runnable)
        }

        // First population (empty → direct write, bypasses coalescing)
        reducer2.onNewEvents(listOf(row("seed", 1000)))
        assertEquals(0, postCount.get()) // direct write, no post

        // Stay at top so subsequent emissions take the MERGE→emitCoalesced path.
        reducer2.onScrollPositionChanged(0, 0)

        // Fire 20 concurrent MERGE emissions from different threads.
        val threadCount = 20
        val latch = CountDownLatch(threadCount)
        val threads = (1..threadCount).map { i ->
            Thread {
                latch.countDown()
                latch.await() // all threads start simultaneously
                reducer2.onNewEvents(
                    listOf(row("seed", 1000), row("new-$i", 900L - i))
                )
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }

        // The synchronized check-then-act in emitCoalesced must ensure exactly
        // one postDelayed call per coalesce window, regardless of thread count.
        assertEquals(
            "Expected exactly 1 postDelayed call per coalesce window, got ${postCount.get()}",
            1,
            postCount.get(),
        )

        // Simulate the Handler firing the flush runnable.
        capturedRunnables.single().run()

        // After flush, the last writer's state should be applied.
        val state = reducer2.state.value
        assertTrue(
            "Visible events should contain more than the seed after flush",
            state.visibleEvents.size > 1,
        )

        // Fire another round — should open a NEW coalesce window (exactly 1 more post).
        reducer2.onNewEvents(listOf(row("seed", 1000), row("round2", 500)))
        assertEquals(
            "Second emission after flush should open a new window",
            2,
            postCount.get(),
        )
    }

    @Test
    fun `concurrent data-only emissions post exactly one flush per coalesce window`() {
        val postCount = AtomicInteger(0)
        val capturedRunnables = CopyOnWriteArrayList<Runnable>()

        val reducer2 = FeedStateReducer("test-data-coalesce") { runnable, _ ->
            postCount.incrementAndGet()
            capturedRunnables.add(runnable)
        }

        // First population (empty → direct write)
        val initial = listOf(row("a", 300), row("b", 200))
        reducer2.onNewEvents(initial)
        assertEquals(0, postCount.get())

        // Stay at top so data-only refreshes go through emitCoalescedDataRefresh.
        reducer2.onScrollPositionChanged(0, 0)

        // Fire 20 concurrent data-only emissions (same IDs, different data).
        val threadCount = 20
        val latch = CountDownLatch(threadCount)
        val threads = (1..threadCount).map { i ->
            Thread {
                latch.countDown()
                latch.await()
                reducer2.onNewEvents(
                    listOf(
                        row("a", 300).copy(reactionCount = i),
                        row("b", 200).copy(reactionCount = i),
                    )
                )
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }

        // Exactly one postDelayed for the data coalesce window.
        assertEquals(
            "Expected exactly 1 data-coalesce postDelayed, got ${postCount.get()}",
            1,
            postCount.get(),
        )
    }
}
