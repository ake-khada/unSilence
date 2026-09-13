package com.unsilence.app.data.relay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production request lifetime; only socket dispatch is substituted. */
@OptIn(ExperimentalCoroutinesApi::class)
class OneShotOutcomesTest {
    private val first = "wss://first.test"
    private val second = "wss://second.test"

    @Test
    fun `mixed relay outcomes are retained in either completion order`() = runTest {
        for (successFirst in listOf(true, false)) {
            val outcomes = OneShotOutcomes()
            var cleaned = 0
            val result = async {
                outcomes.fetch("mixed", listOf(first, second), 1_000, { setOf(first, second) }, { cleaned++ })
            }
            runCurrent()
            val responses = listOf(first to OneShotOutcome.EOSE, second to OneShotOutcome.CLOSED)
                .let { if (successFirst) it else it.reversed() }
            outcomes.record("mixed", responses[0].first, responses[0].second)
            runCurrent()
            assertFalse(result.isCompleted)
            outcomes.record("mixed", responses[1].first, responses[1].second)
            assertEquals(mapOf(first to OneShotOutcome.EOSE, second to OneShotOutcome.CLOSED), result.await())
            assertEquals(1, cleaned)
            assertFalse(outcomes.contains("mixed"))
        }
    }

    @Test
    fun `all skipped dispatch is not EOSE`() = runTest {
        val outcomes = OneShotOutcomes()
        var cleaned = false
        val result = outcomes.fetch("skipped", listOf(first, second), 1_000, { emptySet() }, { cleaned = true })
        assertEquals(mapOf(first to OneShotOutcome.SKIPPED, second to OneShotOutcome.SKIPPED), result)
        assertTrue(cleaned)
    }

    @Test
    fun `partial EOSE survives timeout without promoting the silent relay`() = runTest {
        val outcomes = OneShotOutcomes()
        val result = async {
            outcomes.fetch("partial", listOf(first, second), 1_000, { setOf(first, second) }, {})
        }
        runCurrent()
        outcomes.record("partial", first, OneShotOutcome.EOSE)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(mapOf(first to OneShotOutcome.EOSE, second to OneShotOutcome.TIMEOUT), result.await())
    }

    @Test
    fun `dispatch failure stays failed and cleans up`() = runTest {
        val outcomes = OneShotOutcomes()
        var cleaned = 0
        val result = outcomes.fetch("failed", listOf(first), 1_000, { error("socket failed") }, { cleaned++ })
        assertEquals(mapOf(first to OneShotOutcome.FAILED), result)
        assertEquals(1, cleaned)
    }

    @Test
    fun `admission deadline includes stalled dispatch not just the EOSE wait`() = runTest {
        val outcomes = OneShotOutcomes()
        val result = async {
            outcomes.fetch("connect", listOf(first), 1_000, {
                delay(2_000)
                setOf(first)
            }, {})
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(mapOf(first to OneShotOutcome.TIMEOUT), result.await())
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun `queued relays each retain their full network budget`() = runTest {
        val outcomes = OneShotOutcomes()
        var cleaned = 0
        val result = outcomes.fetch("queued", listOf(first, second), 1_000, { admitted ->
            coroutineScope {
                launch {
                    admitted(first)
                    delay(700)
                    outcomes.record("queued", first, OneShotOutcome.EOSE)
                }
                launch {
                    delay(900) // Nearly all of this relay's admission budget.
                    admitted(second)
                    delay(700) // Still gets its own network window, not the first relay's remainder.
                    outcomes.record("queued", second, OneShotOutcome.EOSE)
                }
            }
            setOf(first, second)
        }, { cleaned++ })
        assertEquals(mapOf(first to OneShotOutcome.EOSE, second to OneShotOutcome.EOSE), result)
        assertEquals(1_600L, testScheduler.currentTime)
        assertEquals(1, cleaned)
    }

    @Test
    fun `admitted but silent relay times out after its network budget`() = runTest {
        val outcomes = OneShotOutcomes()
        var dispatchCancelled = false
        val result = outcomes.fetch("silent", listOf(first), 1_000, { admitted ->
            try {
                delay(900)
                admitted(first)
                awaitCancellation()
            } finally { dispatchCancelled = true }
        }, {})
        assertEquals(OneShotOutcome.TIMEOUT, result[first])
        assertEquals(1_900L, testScheduler.currentTime)
        assertTrue(dispatchCancelled)
        assertFalse(outcomes.contains("silent"))
    }

    @Test
    fun `cancellation propagates and releases its ticket once`() = runTest {
        val outcomes = OneShotOutcomes()
        var cleaned = 0
        var returned = false
        val fetch = launch {
            outcomes.fetch("cancelled", listOf(first), 1_000, { awaitCancellation() }, { cleaned++ })
            returned = true
        }
        runCurrent()
        fetch.cancel()
        fetch.join()
        assertTrue(fetch.isCancelled)
        assertFalse(returned)
        assertFalse(outcomes.contains("cancelled"))
        assertEquals(1, cleaned)
    }

    @Test
    fun `dispatch cancellation propagates without waiting for either deadline`() = runTest {
        val outcomes = OneShotOutcomes()
        var cleaned = 0
        var returned = false
        val fetch = launch {
            outcomes.fetch("dispatch-cancel", listOf(first), 1_000, {
                throw CancellationException("transport cancelled")
            }, { cleaned++ })
            returned = true
        }
        runCurrent()
        assertTrue(fetch.isCancelled)
        assertFalse(returned)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, cleaned)
        assertFalse(outcomes.contains("dispatch-cancel"))
    }

    @Test
    fun `late response for timed out request cannot finish retry`() = runTest {
        val outcomes = OneShotOutcomes()
        val cleaned = mutableListOf<String>()
        val old = async { outcomes.fetch("old", listOf(first), 1_000, { setOf(first) }, { cleaned += "old" }) }
        advanceTimeBy(1_000)
        assertEquals(OneShotOutcome.TIMEOUT, old.await()[first])
        val retry = async { outcomes.fetch("retry", listOf(first), 1_000, { setOf(first) }, { cleaned += "retry" }) }
        runCurrent()
        outcomes.record("old", first, OneShotOutcome.EOSE)
        runCurrent()
        assertFalse(retry.isCompleted)
        assertTrue(outcomes.contains("retry"))
        assertEquals(listOf("old"), cleaned)
        outcomes.record("retry", first, OneShotOutcome.EOSE)
        assertEquals(OneShotOutcome.EOSE, retry.await()[first])
        assertEquals(listOf("old", "retry"), cleaned)
    }

    @Test
    fun `unrequested and duplicate responses cannot fabricate coverage`() = runTest {
        val outcomes = OneShotOutcomes()
        val result = async {
            outcomes.fetch("scoped", listOf(first, second), 1_000, { setOf(first, second) }, {})
        }
        runCurrent()
        outcomes.record("scoped", "wss://unrequested.test", OneShotOutcome.EOSE)
        outcomes.record("scoped", first, OneShotOutcome.CLOSED)
        outcomes.record("scoped", first, OneShotOutcome.EOSE)
        runCurrent()
        assertFalse(result.isCompleted)
        outcomes.record("scoped", second, OneShotOutcome.EOSE)
        assertEquals(mapOf(first to OneShotOutcome.CLOSED, second to OneShotOutcome.EOSE), result.await())
    }
}
