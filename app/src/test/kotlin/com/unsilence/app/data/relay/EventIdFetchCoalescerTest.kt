package com.unsilence.app.data.relay

import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventIdFetchCoalescerTest {

    @Test
    fun `quote-heavy screenful collapses thirty ids into one request`() = runTest {
        val batches = mutableListOf<EventIdFetchBatch>()
        val coalescer = EventIdFetchCoalescer(this) { batches += it }
        val completions = (1..30).map { coalescer.enqueue("event-$it", emptyList()) }

        advanceTimeBy(EVENT_ID_FETCH_WINDOW_MS - 1)
        runCurrent()
        assertTrue(batches.isEmpty())
        assertFalse(completions.first().isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, batches.size)
        assertEquals((1..30).map { "event-$it" }, batches.single().eventIds)
        completions.awaitAll()
    }

    @Test
    fun `cap overflow rolls into the next window`() = runTest {
        val batches = mutableListOf<EventIdFetchBatch>()
        val coalescer = EventIdFetchCoalescer(this) { batches += it }
        val completions = (1..51).map { coalescer.enqueue("event-$it", emptyList()) }

        advanceTimeBy(EVENT_ID_FETCH_WINDOW_MS)
        runCurrent()
        assertEquals(listOf(50), batches.map { it.eventIds.size })

        advanceTimeBy(EVENT_ID_FETCH_WINDOW_MS)
        runCurrent()
        assertEquals(listOf(50, 1), batches.map { it.eventIds.size })
        completions.awaitAll()
    }

    @Test
    fun `ids sharing canonical hints group while hintless ids use the default batch`() = runTest {
        val batches = mutableListOf<EventIdFetchBatch>()
        val coalescer = EventIdFetchCoalescer(this) { batches += it }

        coalescer.enqueue("a", listOf("wss://two.example/", "wss://one.example"))
        coalescer.enqueue("b", listOf("one.example", "wss://two.example"))
        coalescer.enqueue("c", listOf("wss://other.example"))
        coalescer.enqueue("d", emptyList())
        advanceTimeBy(EVENT_ID_FETCH_WINDOW_MS)
        runCurrent()

        assertEquals(3, batches.size)
        assertEquals(listOf("a", "b"), batches[0].eventIds)
        assertEquals(
            listOf("wss://one.example", "wss://two.example"),
            batches[0].relayHints,
        )
        assertEquals(listOf("c"), batches[1].eventIds)
        assertEquals(listOf("d"), batches[2].eventIds)
        assertTrue(batches[2].relayHints.isEmpty())
    }

    @Test
    fun `duplicate id shares one dispatch and both callers complete`() = runTest {
        val batches = mutableListOf<EventIdFetchBatch>()
        val coalescer = EventIdFetchCoalescer(this) { batches += it }

        val first = coalescer.enqueue("same-id", listOf("wss://first.example"))
        val duplicate = coalescer.enqueue("same-id", listOf("wss://second.example"))
        advanceTimeBy(EVENT_ID_FETCH_WINDOW_MS)
        runCurrent()

        assertEquals(1, batches.size)
        assertEquals(listOf("same-id"), batches.single().eventIds)
        assertEquals(listOf("wss://first.example"), batches.single().relayHints)
        assertTrue(first.isCompleted)
        assertTrue(duplicate.isCompleted)
    }

    @Test
    fun `straggler after a closed window starts a new window`() = runTest {
        val batches = mutableListOf<EventIdFetchBatch>()
        val coalescer = EventIdFetchCoalescer(this) { batches += it }

        coalescer.enqueue("first", emptyList())
        advanceTimeBy(EVENT_ID_FETCH_WINDOW_MS)
        runCurrent()
        assertEquals(1, batches.size)

        val second = coalescer.enqueue("second", emptyList())
        advanceTimeBy(EVENT_ID_FETCH_WINDOW_MS - 1)
        runCurrent()
        assertEquals(1, batches.size)
        assertFalse(second.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, batches.size)
        assertEquals(listOf("second"), batches.last().eventIds)
    }
}
