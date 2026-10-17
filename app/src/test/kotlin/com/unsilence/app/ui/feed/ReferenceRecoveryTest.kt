package com.unsilence.app.ui.feed

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReferenceRecoveryTest {
    private class Fixture(val scope: TestScope, maxActive: Int = 32, capacity: Int = 128) {
        val values = mutableMapOf<String, MutableStateFlow<String?>>()
        val calls = mutableListOf<Pair<String, Boolean>>()
        var leases = 0
        var fetch: suspend (String, Boolean) -> String? = { _, _ -> null }
        fun state(id: String) = values.getOrPut(id) { MutableStateFlow(null) }
        val recovery = ReferenceRecovery(
            cached = { id: String -> state(id).value },
            watch = ::state,
            fetch = { id, retry -> calls += id to retry; fetch(id, retry) },
            retain = { _: String -> leases++; AutoCloseable { leases-- } },
            nowMs = { scope.testScheduler.currentTime },
            attemptMs = 100,
            retryDelayMs = 20,
            cooldownMs = 1_000,
            maxActive = maxActive,
            capacity = capacity,
        )
    }

    @Test fun `unavailable card recovers on a late arrival without another fetch`() = runTest {
        val f = Fixture(this)
        val states = mutableListOf<ReferenceState<String>>()
        val job = backgroundScope.launch { f.recovery.observe("a").collect { states += it } }
        runCurrent()
        advanceTimeBy(21); runCurrent()
        assertEquals(ReferenceState<String>(loading = false), states.last())
        assertEquals(2, f.calls.size)
        assertEquals(0, f.leases)
        f.state("a").value = "late"
        runCurrent()
        assertEquals(ReferenceState("late", false), states.last())
        assertEquals(2, f.calls.size)
        // Deletion/eviction is observed too; an old payload must not stick forever.
        f.state("a").value = null
        runCurrent()
        assertNull(states.last().event)
        assertEquals(2, f.calls.size)
        job.cancelAndJoin()
    }

    @Test fun `retryable miss backs off once then succeeds`() = runTest {
        val f = Fixture(this)
        f.fetch = { _, retry -> if (retry) "resolved" else null }
        val result = async { f.recovery.lookup("a") }
        runCurrent()
        assertEquals(listOf("a" to false), f.calls)
        advanceTimeBy(19); runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals("resolved", result.await())
        assertEquals(listOf("a" to false, "a" to true), f.calls)
        assertEquals(0, f.leases)
    }

    @Test fun `fetch exception becomes a miss with only one retry and late recovery`() = runTest {
        val f = Fixture(this)
        f.fetch = { _, _ -> throw java.io.IOException("offline") }
        val states = mutableListOf<ReferenceState<String>>()
        val job = backgroundScope.launch { f.recovery.observe("a").collect { states += it } }
        runCurrent()
        assertFalse(states.last().loading)
        advanceTimeBy(21); runCurrent()
        assertTrue(job.isActive)
        assertEquals(listOf("a" to false, "a" to true), f.calls)
        assertEquals(0, f.leases)
        f.state("a").value = "late"
        runCurrent()
        assertEquals("late", states.last().event)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, f.calls.size)
        job.cancelAndJoin()
    }

    @Test fun `timeout has one retry and the first miss ends loading`() = runTest {
        val f = Fixture(this)
        f.fetch = { _, _ -> awaitCancellation() }
        val states = mutableListOf<ReferenceState<String>>()
        val job = backgroundScope.launch { f.recovery.observe("a").collect { states += it } }
        runCurrent()
        advanceTimeBy(100); runCurrent()
        assertFalse(states.last().loading)
        assertEquals(1, f.calls.size)
        advanceTimeBy(120); runCurrent()
        assertEquals(2, f.calls.size)
        assertEquals(0, f.leases)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, f.calls.size)
        job.cancelAndJoin()
    }

    @Test fun `store arrival cancels slow fetch and releases lease while observation lives`() = runTest {
        val f = Fixture(this)
        var cancelled = false
        f.fetch = { _, _ -> try { awaitCancellation() } finally { cancelled = true } }
        val states = mutableListOf<ReferenceState<String>>()
        val job = backgroundScope.launch { f.recovery.observe("a").collect { states += it } }
        runCurrent()
        assertEquals(1, f.leases)
        f.state("a").value = "arrived elsewhere"
        runCurrent()
        assertTrue(cancelled)
        assertTrue(job.isActive)
        assertEquals(0, f.leases)
        assertEquals("arrived elsewhere", states.last().event)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, f.calls.size)
        job.cancelAndJoin()
    }

    @Test fun `at capacity waits without declaring a miss or acquiring a lease`() = runTest {
        val f = Fixture(this, maxActive = 1)
        val release = CompletableDeferred<Unit>()
        f.fetch = { id, _ -> if (id == "a") release.await(); id }
        val first = async { f.recovery.lookup("a") }
        runCurrent()
        val second = async { f.recovery.lookup("b") }
        runCurrent()
        assertFalse(second.isCompleted)
        assertEquals(1, f.leases)
        assertEquals(listOf("a" to false), f.calls)
        release.complete(Unit)
        runCurrent()
        assertEquals("a", first.await())
        assertEquals("b", second.await())
        assertEquals(listOf("a" to false, "b" to false), f.calls)
        assertEquals(0, f.leases)
    }

    @Test fun `cancelled owner lets a visible follower take over`() = runTest {
        val f = Fixture(this)
        f.fetch = { _, _ -> if (f.calls.size == 1) awaitCancellation() else "recovered" }
        val owner = async { f.recovery.lookup("a") }
        runCurrent()
        val follower = async { f.recovery.lookup("a") }
        runCurrent()
        owner.cancelAndJoin()
        runCurrent()
        assertEquals("recovered", follower.await())
        assertEquals(2, f.calls.size)
        assertEquals(0, f.leases)
    }

    @Test fun `cancelling follower cannot clear owner or start a duplicate`() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        f.fetch = { _, _ -> release.await(); "resolved" }
        val owner = async { f.recovery.lookup("a") }
        runCurrent()
        val follower = async { f.recovery.lookup("a") }
        runCurrent()
        follower.cancelAndJoin()
        val later = async { f.recovery.lookup("a") }
        runCurrent()
        assertEquals(1, f.calls.size)
        assertEquals(1, f.leases)
        release.complete(Unit)
        assertEquals("resolved", owner.await())
        assertEquals("resolved", later.await())
    }

    @Test fun `many concurrent callers share both attempts and their backoff`() = runTest {
        val f = Fixture(this)
        f.fetch = { _, _ -> delay(10); null }
        val callers = List(30) { async { f.recovery.lookup("a") } }
        advanceUntilIdle()
        callers.forEach { assertNull(it.await()) }
        assertEquals(listOf("a" to false, "a" to true), f.calls)
        assertEquals(0, f.leases)
    }

    @Test fun `remount within cooldown does not retry but later arrival still renders`() = runTest {
        val f = Fixture(this)
        assertNull(f.recovery.lookup("a"))
        repeat(20) { assertNull(f.recovery.lookup("a")) }
        assertEquals(2, f.calls.size)
        f.state("a").value = "late"
        assertEquals("late", f.recovery.lookup("a"))
        assertEquals(2, f.calls.size)
    }

    @Test fun `cooldown expires and miss registry stays bounded`() = runTest {
        val f = Fixture(this, capacity = 2)
        f.recovery.lookup("a"); f.recovery.lookup("b"); f.recovery.lookup("c")
        assertEquals(6, f.calls.size)
        f.recovery.lookup("b")
        assertEquals(6, f.calls.size)
        f.recovery.lookup("a") // oldest entry was removed
        assertEquals(8, f.calls.size)
        advanceTimeBy(1_000)
        f.recovery.lookup("a")
        assertEquals(10, f.calls.size)
    }

    @Test fun `cancelling a queued lookup does not consume capacity or protection`() = runTest {
        val f = Fixture(this, maxActive = 1)
        f.fetch = { _, _ -> awaitCancellation() }
        val owner = async { f.recovery.lookup("a") }
        runCurrent()
        val queued = async { f.recovery.lookup("b") }
        runCurrent()
        queued.cancelAndJoin()
        owner.cancelAndJoin()
        assertEquals(0, f.leases)
        f.fetch = { _, _ -> "c" }
        assertEquals("c", f.recovery.lookup("c"))
        assertEquals(listOf("a" to false, "c" to false), f.calls)
    }

    @Test fun `slow hint step cannot starve fallback within the attempt budget`() = runTest {
        val budget = ReferencePhaseBudget(3, timeoutMs = 90, nowMs = { testScheduler.currentTime })
        var hintCancelled = false
        budget.run { try { awaitCancellation() } finally { hintCancelled = true } }
        assertTrue(hintCancelled)
        assertEquals(30L, testScheduler.currentTime)
        var fallbackRan = false
        budget.run { delay(10); fallbackRan = true }
        assertTrue(fallbackRan)
        budget.run { awaitCancellation() }
        assertEquals(90L, testScheduler.currentTime)
    }

    @Test fun `all six slow ladder steps get time without exceeding the attempt`() = runTest {
        val budget = ReferencePhaseBudget(6, nowMs = { testScheduler.currentTime })
        var attempted = 0
        var cancelled = 0
        repeat(6) {
            budget.run {
                attempted++
                try { awaitCancellation() } finally { cancelled++ }
            }
        }
        assertEquals(6, attempted)
        assertEquals(6, cancelled)
        assertEquals(REFERENCE_ATTEMPT_MS - 1_000, testScheduler.currentTime)
    }
}
