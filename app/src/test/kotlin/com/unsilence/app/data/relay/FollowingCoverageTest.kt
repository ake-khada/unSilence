package com.unsilence.app.data.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FollowingCoverageTest {
    private val relay = "wss://write.example"
    private val other = "wss://unrelated.example"

    @Test fun `loss restores fallback once through a background metadata signal`() = runTest {
        val author = "a".repeat(64)
        val metadata = object : RelayMetadataSource {
            override fun writeRelaysFor(pubkey: String) = listOf(relay)
        }
        val resolver = OutboxRelayResolver(metadata)
        val healthy = MutableStateFlow(setOf(relay, other))
        val dependencies = MutableStateFlow(emptySet<String>())
        val config = OutboxRelayResolver.Config(listOf(1), 300)
        fun resolve() = resolver.resolveFollowing(setOf(author), listOf("wss://fallback.example"), emptySet(), config,
            healthy.value, onFallbackDependencies = { dependencies.value = it })
        var requests = resolve()
        assertEquals(listOf(relay), requests.flatMap { it.urls })
        var rebuilds = 0
        val observer = launch {
            followingCoverageLosses(dependencies, healthy).collect { rebuilds++; requests = resolve() }
        }
        runCurrent()
        healthy.value = setOf(other)
        runCurrent()
        advanceTimeBy(RELAY_METADATA_DEBOUNCE_MS)
        runCurrent()
        assertEquals(1, rebuilds)
        assertEquals(listOf(author), requests.single { it.urls == listOf("wss://fallback.example") }.filter.authors)
        assertTrue(dependencies.value.isEmpty())
        advanceTimeBy(RELAY_METADATA_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(1, rebuilds)
        observer.cancel()
    }

    @Test fun `flaps coalesce and unrelated connections or recovery cannot trigger narrowing`() = runTest {
        val dependencies = MutableStateFlow(setOf(relay))
        val healthy = MutableStateFlow(setOf(relay, other))
        var rebuilds = 0
        val observer = launch { followingCoverageLosses(dependencies, healthy).collect { rebuilds++ } }
        runCurrent()
        healthy.value = setOf(relay)
        advanceTimeBy(RELAY_METADATA_DEBOUNCE_MS)
        runCurrent()
        assertEquals(0, rebuilds)
        repeat(3) {
            healthy.value = emptySet()
            runCurrent()
            advanceTimeBy(200)
            healthy.value = setOf(relay)
            runCurrent()
            advanceTimeBy(200)
        }
        healthy.value = emptySet()
        runCurrent()
        advanceTimeBy(RELAY_METADATA_DEBOUNCE_MS)
        runCurrent()
        assertEquals(1, rebuilds)
        healthy.value = setOf(relay)
        advanceTimeBy(RELAY_METADATA_DEBOUNCE_MS)
        runCurrent()
        assertEquals(1, rebuilds)
        observer.cancel()
    }

    @Test fun `leaving Following cancels pending recovery`() = runTest {
        val dependencies = MutableStateFlow(setOf(relay))
        val healthy = MutableStateFlow(emptySet<String>())
        var rebuilds = 0
        val observer = launch { followingCoverageLosses(dependencies, healthy).collect { rebuilds++ } }
        runCurrent()
        advanceTimeBy(500)
        dependencies.value = emptySet()
        advanceTimeBy(RELAY_METADATA_DEBOUNCE_MS)
        runCurrent()
        assertEquals(0, rebuilds)
        observer.cancel()
    }
}
