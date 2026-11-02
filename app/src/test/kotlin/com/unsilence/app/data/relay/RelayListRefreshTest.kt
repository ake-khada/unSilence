package com.unsilence.app.data.relay

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayListRefreshTest {
    @Test fun `nonempty restored lists are unchecked and age by lookup not signed time`() = runTest {
        var now = 0L
        val refresh = RelayListRefresh({ now })
        var calls = 0
        suspend fun attempt() = refresh.refresh(listOf("alice"), { true }) { calls++; true }
        attempt(); assertEquals(1, calls)
        now = RELAY_LIST_REFRESH_MS - 1
        attempt(); assertEquals(1, calls)
        now++
        attempt(); assertEquals(2, calls)
        // A new process has no success evidence even when a snapshot contains a list.
        RelayListRefresh({ now }).refresh(listOf("alice"), { true }) { calls++; true }
        assertEquals(3, calls)
    }

    @Test fun `failed lookup and empty lists stay retryable with bounded spacing`() = runTest {
        var now = 0L
        val refresh = RelayListRefresh({ now })
        var calls = 0
        refresh.refresh(listOf("alice"), { true }) { calls++; false }
        refresh.refresh(listOf("alice"), { true }) { calls++; true }
        assertEquals(1, calls)
        now += RELAY_LIST_RETRY_MS
        refresh.refresh(listOf("alice"), { true }) { calls++; true }
        assertEquals(2, calls)
        now += RELAY_LIST_RETRY_MS
        refresh.refresh(listOf("alice"), { false }) { calls++; true }
        assertEquals(3, calls)
    }

    @Test fun `only successful chunk becomes fresh and each request has at most 500 authors`() = runTest {
        var now = 0L
        val refresh = RelayListRefresh({ now })
        val authors = (0..500).map { "author-$it" }
        val first = mutableListOf<List<String>>()
        refresh.refresh(authors, { true }) { first += it; it.size == 500 }
        assertEquals(listOf(500, 1), first.map { it.size })
        now += RELAY_LIST_RETRY_MS
        val retry = mutableListOf<List<String>>()
        refresh.refresh(authors, { true }) { retry += it; true }
        assertEquals(listOf(listOf("author-500")), retry)
    }

    @Test fun `clear fences late completion and a new account cannot inherit freshness`() = runTest {
        val refresh = RelayListRefresh({ testScheduler.currentTime })
        val arrived = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val old = launch { refresh.refresh(listOf("alice"), { true }) { arrived.complete(Unit); finish.await(); true } }
        arrived.await()
        refresh.clear()
        finish.complete(Unit); old.join()
        var calls = 0
        refresh.refresh(listOf("alice"), { true }) { calls++; true }
        assertEquals(1, calls)
    }

    @Test fun `cancellation does not certify lookup and concurrent demand is coalesced`() = runTest {
        val refresh = RelayListRefresh({ testScheduler.currentTime })
        var calls = 0
        val job = launch { refresh.refresh(listOf("alice"), { true }) { calls++; awaitCancellation() } }
        runCurrent()
        refresh.refresh(listOf("alice"), { true }) { calls++; true }
        assertEquals(1, calls)
        job.cancelAndJoin()
        advanceTimeBy(RELAY_LIST_RETRY_MS)
        refresh.refresh(listOf("alice"), { true }) { calls++; true }
        assertEquals(2, calls)
    }

    @Test fun `bookkeeping evicts old evidence within its bound without inventing success`() = runTest {
        val refresh = RelayListRefresh({ testScheduler.currentTime }, capacity = 2)
        refresh.refresh(listOf("alice", "bob", "carol"), { true }) { true }
        val fetched = mutableListOf<String>()
        refresh.refresh(listOf("alice"), { true }) { fetched += it; true }
        assertEquals(listOf("alice"), fetched)
    }
    @Test fun `owned lookup only certifies real complete EOSE and releases on every outcome`() = runTest {
        for (outcome in OneShotOutcome.entries.filter { it != OneShotOutcome.CANCELLED }) {
            val refresh = RelayListRefresh({ testScheduler.currentTime })
            val outcomes = OneShotOutcomes()
            var cleaned = 0
            val job = launch {
                refresh.refresh(listOf("alice"), { true }) {
                    awaitThreadOneShot(outcomes, "kind10002-fixture", listOf("wss://a.test", "wss://b.test"),
                        { setOf("wss://a.test", "wss://b.test") }, { cleaned++ })
                }
            }
            runCurrent()
            outcomes.record("kind10002-fixture", "wss://a.test", OneShotOutcome.EOSE)
            outcomes.record("kind10002-fixture", "wss://b.test", outcome)
            job.join()
            assertEquals(1, cleaned)
            assertFalse(outcomes.contains("kind10002-fixture"))
            advanceTimeBy(RELAY_LIST_RETRY_MS)
            var fetched = false
            refresh.refresh(listOf("alice"), { true }) { fetched = true; true }
            assertEquals(outcome != OneShotOutcome.EOSE, fetched)
        }
    }

    @Test fun `silent skipped and cancelled owned lookups never certify freshness`() = runTest {
        for (mode in listOf("silent", "skipped", "cancelled", "failure")) {
            val refresh = RelayListRefresh({ testScheduler.currentTime })
            val outcomes = OneShotOutcomes()
            var cleaned = 0
            val job = launch {
                refresh.refresh(listOf("alice"), { true }) {
                    awaitThreadOneShot(outcomes, "kind10002-fixture", listOf("wss://a.test"),
                        {
                            if (mode == "failure") error("dispatch failed")
                            if (mode == "skipped") emptySet() else setOf("wss://a.test")
                        }, { cleaned++ })
                }
            }
            runCurrent()
            if (mode == "cancelled") job.cancelAndJoin() else job.join()
            assertEquals(1, cleaned)
            advanceTimeBy(RELAY_LIST_RETRY_MS)
            var fetched = false
            refresh.refresh(listOf("alice"), { true }) { fetched = true; true }
            assertTrue(mode, fetched)
        }
    }

}
