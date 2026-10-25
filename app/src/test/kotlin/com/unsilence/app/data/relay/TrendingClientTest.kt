package com.unsilence.app.data.relay

import app.cash.turbine.test
import com.unsilence.app.data.memory.UserEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class TrendingClientTest {
    private val pubkeyA = "a".repeat(64)
    private val pubkeyB = "b".repeat(64)

    @Test
    fun `cold failure suppresses automatic callers for sixty seconds`() = runTest {
        val now = AtomicLong(1_000L)
        val transport = FakeTrendingTransport().apply { events = null }
        val client = TrendingClient(transport, { null }, backgroundScope, now::get)
        client.refresh()
        repeat(4) { client.refresh() }
        assertEquals(1, transport.fetchCalls.get())
        now.addAndGet(59_999)
        client.refresh()
        assertEquals(1, transport.fetchCalls.get())
        now.incrementAndGet()
        client.refresh()
        assertEquals(2, transport.fetchCalls.get())
    }

    @Test
    fun `forced concurrent callers share the same failed attempt`() = runTest {
        val transport = FakeTrendingTransport().apply {
            events = null
            eventsGate = CompletableDeferred()
        }
        val client = TrendingClient(transport, { null }, backgroundScope)
        val first = async { client.refresh(forceRefresh = true) }
        runCurrent()
        val second = async { client.refresh(forceRefresh = true) }
        runCurrent()
        transport.eventsGate?.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, transport.fetchCalls.get())
    }

    private class FakeTrendingTransport : TrendingTransport {
        val fetchCalls = AtomicInteger(0)
        val warmCalls = AtomicInteger(0)
        val countCalls = AtomicInteger(0)
        var failFetch = false
        var fetchCancelled = false
        var events: List<JsonObject>? = emptyList()
        var eventsGate: CompletableDeferred<Unit>? = null
        var warmGate: CompletableDeferred<Unit>? = null
        var counts: Map<String, Long> = emptyMap()
        var countDelayMs: Long = 0L

        override suspend fun fetchTrendingEvents(): List<JsonObject>? {
            fetchCalls.incrementAndGet()
            if (failFetch) error("deterministic transport failure")
            try {
                eventsGate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                fetchCancelled = true
                throw e
            }
            return events
        }

        override suspend fun warmCountRelay() {
            warmCalls.incrementAndGet()
            warmGate?.await()
        }

        override suspend fun fetchFollowerCount(pubkey: String): Long? {
            countCalls.incrementAndGet()
            if (countDelayMs > 0) delay(countDelayMs)
            return counts[pubkey]
        }
    }

    @Test
    fun `empty and unusable responses are terminal and cool down without count probes`() = runTest {
        for (events in listOf(emptyList(), listOf(buildJsonObject {}))) {
            val transport = FakeTrendingTransport().apply { this.events = events }
            val client = TrendingClient(transport, { null }, backgroundScope, { testScheduler.currentTime })
            client.refresh()
            val warmCalls = transport.warmCalls.get()
            assertEquals(TrendingOutcome.Empty, client.state.value.outcome)
            assertFalse(client.state.value.refreshing)
            repeat(4) { client.refresh() }
            assertEquals(1, transport.fetchCalls.get())
            assertEquals(warmCalls, transport.warmCalls.get())
            assertEquals(0, transport.countCalls.get())
        }
    }

    @Test
    fun `exception ends initial loading and permits recovery after cooldown`() = runTest {
        val transport = FakeTrendingTransport().apply { failFetch = true }
        val client = TrendingClient(transport, { null }, backgroundScope, { testScheduler.currentTime })
        client.refresh()
        assertEquals(TrendingOutcome.Unavailable, client.state.value.outcome)
        assertFalse(client.state.value.refreshing)
        transport.failFetch = false
        transport.events = listOf(trendingEvent(pubkeyA))
        client.refresh()
        assertEquals(1, transport.fetchCalls.get())
        advanceTimeBy(60_000)
        client.refresh()
        assertEquals(TrendingOutcome.Success, client.state.value.outcome)
        assertEquals(listOf(pubkeyA), client.state.value.data?.profiles?.map { it.pubkey })
    }

    @Test
    fun `five second timeout ends loading and cancels transport`() = runTest {
        val transport = FakeTrendingTransport().apply { eventsGate = CompletableDeferred() }
        val client = TrendingClient(transport, { null }, backgroundScope, { testScheduler.currentTime })
        val pending = async { client.refresh() }
        runCurrent()
        assertTrue(client.state.value.refreshing)
        advanceTimeBy(5_000)
        runCurrent()
        pending.await()
        assertTrue(transport.fetchCancelled)
        assertFalse(client.state.value.refreshing)
        assertEquals(TrendingOutcome.Unavailable, client.state.value.outcome)
        client.refresh()
        assertEquals(1, transport.fetchCalls.get())
    }

    @Test
    fun `retry bypasses failure cooldown but double taps and concurrent callers do not queue`() = runTest {
        val transport = FakeTrendingTransport().apply { events = null }
        val client = TrendingClient(transport, { null }, backgroundScope, { testScheduler.currentTime })
        client.refresh()
        client.retry()
        runCurrent()
        assertEquals(2, transport.fetchCalls.get())
        client.retry()
        advanceTimeBy(1_499)
        client.retry()
        runCurrent()
        assertEquals(2, transport.fetchCalls.get())
        advanceTimeBy(1)
        transport.eventsGate = CompletableDeferred()
        client.retry()
        runCurrent()
        repeat(5) { client.retry(); client.refreshIfStale() }
        runCurrent()
        assertEquals(3, transport.fetchCalls.get())
        transport.eventsGate?.complete(Unit)
        runCurrent()
        assertEquals(3, transport.fetchCalls.get())
    }

    @Test
    fun `failure preserves successful data and does not reset its ten minute freshness`() = runTest {
        val transport = FakeTrendingTransport().apply {
            events = listOf(trendingEvent(pubkeyA))
            counts = mapOf(pubkeyA to 10L)
        }
        val client = TrendingClient(transport, { null }, backgroundScope, { testScheduler.currentTime })
        client.refresh()
        val original = client.state.value.data
        advanceTimeBy(600_000)
        transport.events = null
        client.refresh()
        assertEquals(original, client.state.value.data)
        assertEquals(TrendingOutcome.Unavailable, client.state.value.outcome)
        advanceTimeBy(60_000)
        client.refresh()
        assertEquals(3, transport.fetchCalls.get())
    }

    @Test
    fun `cancelling a waiter does not cancel a shared refresh`() = runTest {
        val transport = FakeTrendingTransport().apply {
            eventsGate = CompletableDeferred()
            events = listOf(trendingEvent(pubkeyA))
        }
        val client = TrendingClient(transport, { null }, backgroundScope)
        val first = async { client.refresh() }
        val second = async { client.refresh() }
        runCurrent()
        first.cancel()
        transport.eventsGate?.complete(Unit)
        second.await()
        assertEquals(1, transport.fetchCalls.get())
        assertFalse(transport.fetchCancelled)
        assertFalse(client.state.value.refreshing)
    }

    @Test
    fun `cancelling shared scope cancels transport and clears loading`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        val transport = FakeTrendingTransport().apply { eventsGate = CompletableDeferred() }
        val client = TrendingClient(transport, { null }, scope)
        client.refreshIfStale()
        runCurrent()
        assertTrue(client.state.value.refreshing)
        scope.cancel()
        runCurrent()
        assertTrue(transport.fetchCancelled)
        assertFalse(client.state.value.refreshing)
        assertEquals(TrendingOutcome.Unavailable, client.state.value.outcome)
    }

    @Test
    fun `already cancelled scope cannot leave a lazy attempt loading`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        scope.cancel()
        val client = TrendingClient(FakeTrendingTransport(), { null }, scope)
        client.refreshIfStale()
        runCurrent()
        assertFalse(client.state.value.refreshing)
    }

    @Test
    fun `refresh emits phase one then enriched snapshot`() = runTest {
        val transport = FakeTrendingTransport().apply {
            events = listOf(
                trendingEvent(pubkeyA, "nostr", "kotlin"),
                trendingEvent(pubkeyA, "nostr"),
                trendingEvent(pubkeyB, "android"),
            )
            warmGate = CompletableDeferred()
            counts = mapOf(pubkeyA to 42L, pubkeyB to 7L)
        }
        val profiles = mutableMapOf(
            pubkeyA to UserEntity(pubkey = pubkeyA, name = "early"),
        )
        val client = TrendingClient(
            transport = transport,
            profileLookup = { profiles[it] },
            scope = backgroundScope,
        )

        client.state.test {
            assertEquals(TrendingState(), awaitItem())
            val refresh = async { client.refresh(forceRefresh = true) }

            assertEquals(true, awaitItem().refreshing)
            val phaseOne = awaitItem().data
            assertEquals(listOf("nostr", "kotlin", "android"), phaseOne?.hashtags?.map { it.tag })
            assertEquals("early", phaseOne?.profiles?.first { it.pubkey == pubkeyA }?.name)
            assertNull(phaseOne?.profiles?.first { it.pubkey == pubkeyA }?.followerCount)

            profiles[pubkeyA] = UserEntity(pubkey = pubkeyA, displayName = "Hydrated", picture = "https://example.com/a.jpg")
            transport.warmGate?.complete(Unit)

            val enriched = awaitItem().data
            assertEquals("Hydrated", enriched?.profiles?.first { it.pubkey == pubkeyA }?.displayName)
            assertEquals(42L, enriched?.profiles?.first { it.pubkey == pubkeyA }?.followerCount)
            refresh.await()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `concurrent stale callers share one fetch`() = runTest {
        val transport = FakeTrendingTransport().apply {
            events = listOf(trendingEvent(pubkeyA, "nostr"))
            eventsGate = CompletableDeferred()
        }
        val client = TrendingClient(transport, { null }, backgroundScope)

        val first = async { client.refresh(forceRefresh = true) }
        runCurrent()
        val second = async { client.refresh() }

        assertEquals(1, transport.fetchCalls.get())
        transport.eventsGate?.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, transport.fetchCalls.get())
    }

    @Test
    fun `failed refresh keeps previous snapshot`() = runTest {
        val transport = FakeTrendingTransport().apply {
            events = listOf(trendingEvent(pubkeyA, "nostr"))
        }
        val client = TrendingClient(transport, { null }, backgroundScope)

        client.refresh(forceRefresh = true)
        val cached = client.state.value.data

        transport.events = null
        client.refresh(forceRefresh = true)

        assertEquals(cached, client.state.value.data)
    }

    @Test
    fun `count timeout falls back to previous count`() = runTest {
        val transport = FakeTrendingTransport().apply {
            events = listOf(trendingEvent(pubkeyA, "nostr"))
            counts = mapOf(pubkeyA to 99L)
        }
        val client = TrendingClient(transport, { null }, backgroundScope)
        client.refresh(forceRefresh = true)
        assertEquals(99L, client.state.value.data?.profiles?.first()?.followerCount)

        transport.countDelayMs = 3_000L
        transport.counts = mapOf(pubkeyA to 1L)
        client.refresh(forceRefresh = true)

        assertEquals(99L, client.state.value.data?.profiles?.first()?.followerCount)
    }

    @Test
    fun `failed count remains unresolved instead of becoming zero`() = runTest {
        val transport = FakeTrendingTransport().apply {
            events = listOf(trendingEvent(pubkeyA, "nostr"))
        }
        val client = TrendingClient(transport, { null }, backgroundScope)

        client.refresh(forceRefresh = true)

        assertNull(client.state.value.data?.profiles?.first()?.followerCount)
    }

    @Test
    fun `current count miss uses retry freshness even when an older count survives`() = runTest {
        val nowMs = AtomicLong(1_000L)
        val transport = FakeTrendingTransport().apply {
            events = listOf(trendingEvent(pubkeyA, "nostr"))
            counts = mapOf(pubkeyA to 99L)
        }
        val client = TrendingClient(
            transport = transport,
            profileLookup = { null },
            scope = backgroundScope,
            nowMs = nowMs::get,
        )
        client.refresh(forceRefresh = true)

        transport.counts = emptyMap()
        client.refresh(forceRefresh = true)
        assertEquals(99L, client.state.value.data?.profiles?.first()?.followerCount)
        assertEquals(2, transport.fetchCalls.get())

        nowMs.addAndGet(59_999L)
        client.refresh()
        assertEquals(2, transport.fetchCalls.get())

        nowMs.incrementAndGet()
        client.refresh()
        assertEquals(3, transport.fetchCalls.get())
    }

    @Test
    fun `current count success keeps the full freshness window`() = runTest {
        val nowMs = AtomicLong(1_000L)
        val transport = FakeTrendingTransport().apply {
            events = listOf(trendingEvent(pubkeyA, "nostr"))
            counts = mapOf(pubkeyA to 0L)
        }
        val client = TrendingClient(
            transport = transport,
            profileLookup = { null },
            scope = backgroundScope,
            nowMs = nowMs::get,
        )
        client.refresh(forceRefresh = true)

        nowMs.addAndGet(599_999L)
        client.refresh()
        assertEquals(1, transport.fetchCalls.get())

        nowMs.incrementAndGet()
        client.refresh()
        assertEquals(2, transport.fetchCalls.get())
        assertEquals(0L, client.state.value.data?.profiles?.first()?.followerCount)
    }

    private fun trendingEvent(pubkey: String, vararg hashtags: String): JsonObject =
        buildJsonObject {
            put("pubkey", JsonPrimitive(pubkey))
            put("tags", buildJsonArray {
                for (tag in hashtags) {
                    add(buildJsonArray {
                        add(JsonPrimitive("t"))
                        add(JsonPrimitive(tag))
                    })
                }
            })
        }
}
