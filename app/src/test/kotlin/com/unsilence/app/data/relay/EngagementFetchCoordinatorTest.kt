package com.unsilence.app.data.relay

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.auth.SignatureVerifier
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.CardHydrator.EngagementTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Same production coordinator, builders and outcome runner for public AND own backfill. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class EngagementFetchCoordinatorTest(mode: String) {
    private val ownPubkey = if (mode == "own") "b".repeat(64) else null
    private val first = "wss://first.test"
    private val second = "wss://second.test"
    private val epochMs = 2_000_000_000_000L
    private val old = EngagementTarget("a".repeat(64), null, "c".repeat(64), epochMs / 1_000 - 8 * 86_400)

    private data class Request(val subId: String, val relays: List<String>, val json: String)

    /** Only network I/O is fake. Completion, timeout, cleanup and retry policy are production. */
    private inner class Harness(scope: TestScope, maxEntries: Int = 500) {
        val outcomes = OneShotOutcomes()
        val requests = mutableListOf<Request>()
        val cleaned = mutableListOf<String>()
        var dispatch: suspend (Request) -> Set<String> = { it.relays.toSet() }
        val coordinator = EngagementFetchCoordinator(
            fetchRequest = { subId, relays, json, timeout ->
                outcomes.fetch(subId, relays, timeout, {
                    val request = Request(subId, relays, json)
                    requests += request
                    dispatch(request)
                }, { cleaned += subId })
            },
            nowMs = { epochMs + scope.testScheduler.currentTime },
            maxEntries = maxEntries,
        )

        suspend fun fetch(targets: List<EngagementTarget> = listOf(old), relays: List<String> = listOf(first, second)) {
            coordinator.fetch(targets, relays.map { it to targets.map(EngagementTarget::id) }, ownPubkey)
        }

        fun reply(request: Request, outcome: OneShotOutcome) {
            request.relays.forEach { outcomes.record(request.subId, it, outcome) }
        }

        fun due(target: EngagementTarget = old) = coordinator.needsFetch(target, ownPubkey)
    }

    @Test
    fun `mixed outcomes keep attempt active until both relays settle then retry only failure`() = runTest {
        for (successFirst in listOf(true, false)) {
            val h = Harness(this)
            val fetch = launch { h.fetch() }
            runCurrent()
            val success = h.requests.single { first in it.relays }
            val failure = h.requests.single { second in it.relays }
            val responses = listOf(success to OneShotOutcome.EOSE, failure to OneShotOutcome.CLOSED)
                .let { if (successFirst) it else it.reversed() }
            h.reply(responses[0].first, responses[0].second)
            runCurrent()
            // More viewport work cannot race the outstanding relay.
            h.fetch()
            assertEquals(2, h.requests.size)
            assertFalse(fetch.isCompleted)
            h.reply(responses[1].first, responses[1].second)
            fetch.join()
            assertFalse("Failure backoff prevents a hot retry loop", h.due())
            advanceTimeBy(30_000)
            assertTrue("Old posts must not become permanently fresh after a failed attempt", h.due())
            val retry = launch { h.fetch() }
            runCurrent()
            assertEquals(3, h.requests.size)
            assertEquals(listOf(second), h.requests.last().relays)
            h.reply(h.requests.last(), OneShotOutcome.EOSE)
            retry.join()
            advanceTimeBy(600_000)
            assertFalse(h.due())
            assertEquals(3, h.cleaned.size)
        }
    }

    @Test
    fun `all skipped dispatch leaves old post retryable`() = runTest {
        val h = Harness(this)
        h.dispatch = { emptySet() }
        h.fetch()
        assertEquals(2, h.requests.size)
        assertEquals(2, h.cleaned.size)
        assertFalse(h.due())
        advanceTimeBy(30_000)
        assertTrue(h.due())
        h.fetch()
        assertEquals(4, h.requests.size)
    }

    @Test
    fun `no selected relays gets backoff not freshness`() = runTest {
        val h = Harness(this)
        h.fetch(relays = emptyList())
        assertTrue(h.requests.isEmpty())
        assertFalse(h.due())
        advanceTimeBy(30_000)
        assertTrue(h.due())
    }

    @Test
    fun `CLOSED and transport failure are not successful freshness`() = runTest {
        for (failure in listOf(OneShotOutcome.CLOSED, OneShotOutcome.FAILED)) {
            val h = Harness(this)
            h.dispatch = { h.reply(it, failure); it.relays.toSet() }
            h.fetch()
            assertFalse(h.due())
            advanceTimeBy(30_000)
            assertTrue(h.due())
        }
    }

    @Test
    fun `timeout retains successful relay and leaves silent relay retryable`() = runTest {
        val h = Harness(this)
        val fetch = launch { h.fetch() }
        runCurrent()
        h.reply(h.requests.first(), OneShotOutcome.EOSE)
        advanceTimeBy(ENGAGEMENT_FETCH_TIMEOUT_MS)
        fetch.join()
        assertEquals(2, h.cleaned.size)
        assertFalse(h.due())
        advanceTimeBy(30_000)
        assertTrue(h.due())
        h.dispatch = { h.reply(it, OneShotOutcome.EOSE); it.relays.toSet() }
        h.fetch()
        assertEquals(listOf(second), h.requests.last().relays)
        assertEquals(3, h.requests.size)
    }

    @Test
    fun `cancellation releases attempt without checking old posts`() = runTest {
        val h = Harness(this)
        val fetch = launch { h.fetch() }
        runCurrent()
        fetch.cancel()
        fetch.join()
        assertTrue(fetch.isCancelled)
        assertEquals(2, h.cleaned.size)
        advanceTimeBy(30_000)
        assertTrue(h.due())
    }

    @Test
    fun `late completion after timeout cannot complete or clean up retry`() = runTest {
        val h = Harness(this)
        val fetch = launch { h.fetch(relays = listOf(first)) }
        runCurrent()
        val oldRequest = h.requests.single()
        advanceTimeBy(ENGAGEMENT_FETCH_TIMEOUT_MS)
        fetch.join()
        advanceTimeBy(30_000)
        val retry = launch { h.fetch(relays = listOf(first)) }
        runCurrent()
        val newRequest = h.requests.last()
        assertNotEquals(oldRequest.subId, newRequest.subId)
        h.reply(oldRequest, OneShotOutcome.EOSE)
        runCurrent()
        assertFalse(retry.isCompleted)
        assertEquals(listOf(oldRequest.subId), h.cleaned)
        assertTrue(h.outcomes.contains(newRequest.subId))
        h.reply(newRequest, OneShotOutcome.CLOSED)
        retry.join()
        advanceTimeBy(30_000)
        assertFalse("A second failure doubles the retry backoff", h.due())
        advanceTimeBy(30_000)
        assertTrue("Late success must not refresh a failed retry", h.due())
    }

    @Test
    fun `failure backoff increases but stays bounded`() = runTest {
        val h = Harness(this)
        h.dispatch = { h.reply(it, OneShotOutcome.FAILED); it.relays.toSet() }
        for (backoff in listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)) {
            h.fetch(relays = listOf(first))
            advanceTimeBy(backoff - 1)
            assertFalse(h.due())
            advanceTimeBy(1)
            assertTrue(h.due())
        }
    }

    @Test
    fun `backstop bounds an unresponsive fetch adapter without marking success`() = runTest {
        var cancelled = false
        val coordinator = EngagementFetchCoordinator(
            fetchRequest = { _, _, _, _ ->
                try {
                    delay(60_000) // Ignores the passed request deadline; coordinator must own its backstop.
                    mapOf(first to OneShotOutcome.EOSE)
                } finally { cancelled = true }
            },
            nowMs = { epochMs + testScheduler.currentTime },
        )
        val fetch = launch { coordinator.fetch(listOf(old), listOf(first to listOf(old.id)), ownPubkey) }
        advanceTimeBy(ENGAGEMENT_ATTEMPT_TIMEOUT_MS)
        fetch.join()
        assertTrue(cancelled)
        assertFalse(coordinator.needsFetch(old, ownPubkey))
        advanceTimeBy(30_000)
        assertTrue(coordinator.needsFetch(old, ownPubkey))
    }

    @Test
    fun `old cleanup after reset cannot clear or refresh newer attempt`() = runTest {
        val oldMayFinish = CompletableDeferred<Unit>()
        val newResponse = CompletableDeferred<Map<String, OneShotOutcome>>()
        var calls = 0
        val coordinator = EngagementFetchCoordinator(
            fetchRequest = { _, _, _, _ ->
                if (++calls == 1) withContext(NonCancellable) {
                    oldMayFinish.await()
                    mapOf(first to OneShotOutcome.EOSE)
                } else newResponse.await()
            },
            nowMs = { epochMs + testScheduler.currentTime },
        )
        val batches = listOf(first to listOf(old.id))
        val previous = launch { coordinator.fetch(listOf(old), batches, ownPubkey) }
        runCurrent()
        previous.cancel()
        coordinator.clear()
        val retry = launch { coordinator.fetch(listOf(old), batches, ownPubkey) }
        runCurrent()
        oldMayFinish.complete(Unit)
        previous.join()
        assertFalse(retry.isCompleted)
        coordinator.fetch(listOf(old), batches, ownPubkey)
        assertEquals("Old finally must not clear the retry's in-flight reservation", 2, calls)
        newResponse.complete(mapOf(first to OneShotOutcome.CLOSED))
        retry.join()
        advanceTimeBy(30_000)
        assertTrue("Old success must not overwrite the retry failure", coordinator.needsFetch(old, ownPubkey))
    }

    @Test
    fun `ID coverage cannot satisfy coordinate scope and changed coordinates need coverage`() = runTest {
        val h = Harness(this)
        h.dispatch = { h.reply(it, OneShotOutcome.EOSE); it.relays.toSet() }
        h.fetch(relays = listOf(first))
        assertFalse(h.due())
        val article = old.copy(coord = "30023:${old.authorPubkey}:article")
        assertTrue(h.due(article))
        h.fetch(listOf(article), listOf(first))
        val filters = Json.parseToJsonElement(h.requests.last().json).jsonArray.drop(2).map { it.jsonObject }
        assertEquals(listOf(article.coord), filters[1]["#a"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(article.coord), filters[2]["#A"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(ownPubkey, filters[0]["authors"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertFalse(h.due(article))
        val another = article.copy(coord = "30023:${old.authorPubkey}:different")
        assertTrue(h.due(another))
        h.fetch(listOf(another), listOf(first))
        assertEquals(3, h.requests.size)
    }

    @Test
    fun `coordinate failure does not inherit previous ID success`() = runTest {
        val h = Harness(this)
        h.dispatch = { h.reply(it, OneShotOutcome.EOSE); it.relays.toSet() }
        h.fetch(relays = listOf(first))
        val article = old.copy(coord = "30023:${old.authorPubkey}:article")
        h.dispatch = { h.reply(it, OneShotOutcome.CLOSED); it.relays.toSet() }
        h.fetch(listOf(article), listOf(first))
        advanceTimeBy(30_000)
        assertTrue(h.due(article))
    }

    @Test
    fun `coverage cannot transfer to another relay target or signed-in owner`() = runTest {
        val h = Harness(this)
        h.dispatch = { h.reply(it, OneShotOutcome.EOSE); it.relays.toSet() }
        h.fetch(relays = listOf(first))
        h.fetch(relays = listOf(first, second))
        assertEquals(listOf(listOf(first), listOf(second)), h.requests.map { it.relays })
        val other = old.copy(id = "d".repeat(64))
        assertTrue(h.due(other))
        h.fetch(listOf(other), listOf(first))
        assertEquals(3, h.requests.size)
        h.coordinator.fetch(listOf(old), listOf(first to listOf(old.id)), "another-owner")
        assertEquals(4, h.requests.size)
    }

    @Test
    fun `successful bounded fetch observes the existing public and own freshness intervals`() = runTest {
        val h = Harness(this)
        val recent = old.copy(createdAt = epochMs / 1_000 - 60)
        h.dispatch = { h.reply(it, OneShotOutcome.EOSE); it.relays.toSet() }
        h.fetch(listOf(recent))
        advanceTimeBy(119_999)
        assertFalse(h.due(recent))
        advanceTimeBy(1)
        assertEquals(ownPubkey == null, h.due(recent))
    }

    @Test
    fun `bounded tracker evicts idle entries but never an active attempt`() = runTest {
        val h = Harness(this, maxEntries = 1)
        val running = launch { h.fetch(relays = listOf(first)) }
        runCurrent()
        val other = old.copy(id = "d".repeat(64))
        h.fetch(listOf(other), listOf(first))
        assertEquals(1, h.requests.size)
        h.reply(h.requests.single(), OneShotOutcome.EOSE)
        running.join()
        h.dispatch = { h.reply(it, OneShotOutcome.EOSE); it.relays.toSet() }
        h.fetch(listOf(other), listOf(first))
        assertTrue(h.due(old))
        assertFalse(h.due(other))
    }

    @Test
    fun `EOSE can precede MES drain without consuming events or inferring a count cap`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, object : SignatureVerifier() {
            override fun verify(event: NostrEvent) = true
        })
        val drainScheduler = TestCoroutineScheduler()
        val drainScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(drainScheduler))
        processor.setTestScope(drainScope)
        processor.start()
        try {
            val h = Harness(this)
            h.dispatch = { request ->
                processor.process(
                    """["EVENT","${request.subId}",{"id":"${"d".repeat(64)}","pubkey":"${"b".repeat(64)}","kind":7,"content":"+","created_at":1700000000,"tags":[["e","${old.id}"]],"sig":"${"e".repeat(128)}"}]""",
                    first,
                )
                h.reply(request, OneShotOutcome.EOSE)
                request.relays.toSet()
            }
            h.fetch(relays = listOf(first))
            assertEquals("EOSE is a transport boundary, not a MES barrier", 0, store.currentStatsSnapshot(old.id).reactionCount)
            assertFalse(h.due())
            drainScheduler.runCurrent() // Actual cold drainer, not drainForTest().
            assertEquals(1, store.currentStatsSnapshot(old.id).reactionCount)
            assertFalse(h.due())
        } finally {
            processor.stop()
            drainScope.cancel()
        }
    }

    @Test
    fun `twelve requests behind four transport slots retain response time in every wave`() = runTest {
        val outcomes = OneShotOutcomes()
        val slots = Semaphore(MAX_EPHEMERAL_CONNECTIONS)
        val relays = (1..12).map { "wss://relay-$it.test" }
        val results = mutableListOf<OneShotOutcome>()
        var requested = 0
        var started = 0
        var cleaned = 0
        val coordinator = EngagementFetchCoordinator(
            fetchRequest = { subId, urls, _, timeout ->
                requested++
                outcomes.fetch(subId, urls, timeout, { admitted ->
                    slots.withPermit {
                        started++
                        admitted(urls.single())
                        delay(7_000) // Same workload that previously lost 8 of 12 requests.
                        outcomes.record(subId, urls.single(), OneShotOutcome.EOSE)
                    }
                    urls.toSet()
                }, { cleaned++ }, admissionTimeoutMs = ENGAGEMENT_ADMISSION_TIMEOUT_MS)
                    .also { results += it.values }
            },
            nowMs = { epochMs + testScheduler.currentTime },
        )
        coordinator.fetch(listOf(old), relays.map { it to listOf(old.id) }, ownPubkey)
        assertEquals(12, requested)
        assertEquals(12, started)
        assertEquals(12, results.count { it == OneShotOutcome.EOSE })
        assertEquals(0, results.count { it == OneShotOutcome.TIMEOUT })
        assertEquals(12, cleaned)
        assertEquals(21_000L, testScheduler.currentTime)
        assertEquals(MAX_EPHEMERAL_CONNECTIONS, slots.availablePermits)
        assertFalse(coordinator.needsFetch(old, ownPubkey))
    }

    @Test
    fun `deadline signal retries only failed coverage without another viewport change`() = runTest {
        val h = Harness(this)
        h.dispatch = { request ->
            h.reply(request, if (second in request.relays) OneShotOutcome.CLOSED else OneShotOutcome.EOSE)
            request.relays.toSet()
        }
        val observer = backgroundScope.launch {
            h.coordinator.retrySignals().collect { h.fetch() }
        }
        h.fetch()
        assertEquals(2, h.requests.size)
        h.dispatch = { request -> h.reply(request, OneShotOutcome.EOSE); request.relays.toSet() }
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, h.requests.size)
        assertEquals(listOf(second), h.requests.last().relays)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals("Successful coverage must not become a polling loop", 3, h.requests.size)
        observer.cancel()
    }

    @Test
    fun `withdrawn viewport demand is not retained or retried`() = runTest {
        val h = Harness(this)
        var visibleTargets = listOf(old)
        h.dispatch = { request -> h.reply(request, OneShotOutcome.CLOSED); request.relays.toSet() }
        val observer = backgroundScope.launch {
            h.coordinator.retrySignals().collect { h.fetch(visibleTargets) }
        }
        h.fetch()
        visibleTargets = emptyList()
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(2, h.requests.size)
        assertTrue(h.due())
        observer.cancel()
    }

    @Test
    fun `unobserved overdue failure wakes a returning screen only once`() = runTest {
        val h = Harness(this)
        h.dispatch = { request -> h.reply(request, OneShotOutcome.CLOSED); request.relays.toSet() }
        h.fetch()
        advanceTimeBy(60_000)
        assertEquals(2, h.requests.size)
        var signals = 0
        val observer = backgroundScope.launch {
            h.coordinator.retrySignals().collect { signals++ }
        }
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals("A matured deadline is a wake-up, not a permanent ticking timer", 1, signals)
        observer.cancel()
    }

    @Test
    fun `reset cancels the old retry deadline without losing the new session deadline`() = runTest {
        val h = Harness(this)
        h.dispatch = { request -> h.reply(request, OneShotOutcome.CLOSED); request.relays.toSet() }
        var signals = 0
        val observer = backgroundScope.launch {
            h.coordinator.retrySignals().collect { signals++ }
        }
        h.fetch()
        advanceTimeBy(10_000)
        h.coordinator.clear()
        h.fetch()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(0, signals)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, signals)
        observer.cancel()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes() = listOf(arrayOf("public"), arrayOf("own"))
    }
}
