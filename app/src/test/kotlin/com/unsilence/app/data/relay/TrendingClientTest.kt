package com.unsilence.app.data.relay

import app.cash.turbine.test
import com.unsilence.app.data.memory.UserEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TrendingClientTest {
    private val pubkeyA = "a".repeat(64)
    private val pubkeyB = "b".repeat(64)

    private class FakeTrendingTransport : TrendingTransport {
        val fetchCalls = AtomicInteger(0)
        var events: List<JsonObject>? = emptyList()
        var eventsGate: CompletableDeferred<Unit>? = null
        var warmGate: CompletableDeferred<Unit>? = null
        var counts: Map<String, Long> = emptyMap()
        var countDelayMs: Long = 0L

        override suspend fun fetchTrendingEvents(): List<JsonObject>? {
            fetchCalls.incrementAndGet()
            eventsGate?.await()
            return events
        }

        override suspend fun warmCountRelay() {
            warmGate?.await()
        }

        override suspend fun fetchFollowerCount(pubkey: String): Long? {
            if (countDelayMs > 0) delay(countDelayMs)
            return counts[pubkey]
        }
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

        client.data.test {
            assertNull(awaitItem())
            val refresh = async { client.refresh(forceRefresh = true) }

            val phaseOne = awaitItem()
            assertEquals(listOf("nostr", "kotlin", "android"), phaseOne?.hashtags?.map { it.tag })
            assertEquals("early", phaseOne?.profiles?.first { it.pubkey == pubkeyA }?.name)
            assertEquals(0L, phaseOne?.profiles?.first { it.pubkey == pubkeyA }?.followerCount)

            profiles[pubkeyA] = UserEntity(pubkey = pubkeyA, displayName = "Hydrated", picture = "https://example.com/a.jpg")
            transport.warmGate?.complete(Unit)

            val enriched = awaitItem()
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
        val cached = client.data.value

        transport.events = null
        client.refresh(forceRefresh = true)

        assertEquals(cached, client.data.value)
    }

    @Test
    fun `count timeout falls back to previous count`() = runTest {
        val transport = FakeTrendingTransport().apply {
            events = listOf(trendingEvent(pubkeyA, "nostr"))
            counts = mapOf(pubkeyA to 99L)
        }
        val client = TrendingClient(transport, { null }, backgroundScope)
        client.refresh(forceRefresh = true)
        assertEquals(99L, client.data.value?.profiles?.first()?.followerCount)

        transport.countDelayMs = 3_000L
        transport.counts = mapOf(pubkeyA to 1L)
        client.refresh(forceRefresh = true)

        assertEquals(99L, client.data.value?.profiles?.first()?.followerCount)
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
