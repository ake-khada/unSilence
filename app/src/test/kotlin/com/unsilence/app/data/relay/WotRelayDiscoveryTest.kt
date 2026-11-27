package com.unsilence.app.data.relay

import com.unsilence.app.data.DEFAULT_WOT_RELAY
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.WotLookup
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WotRelayDiscoveryTest {
    private val signer = "a".repeat(64)
    private val target = "b".repeat(64)

    @Test fun sameSignerOnNewRelayRetriesAnOldMiss() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        store.setActiveWotProvider(signer, "wss://old.example")
        store.insertWotAssertionChunk(signer, emptyList(), listOf(target))
        assertEquals(WotLookup.Absent, store.wotFor(target))
        store.setActiveWotProvider(signer, "wss://new.example")
        assertEquals(WotLookup.Pending, store.wotFor(target))
    }

    @Test fun installedDefaultCanReachTheMovedService() {
        assertEquals("wss://scores.brainstorm.world", DEFAULT_WOT_RELAY)
    }

    @Test fun completedFetchIsScopedToItsRelay() {
        assertNotEquals(wotTargetsHash(signer, listOf(target), "wss://old.example"),
            wotTargetsHash(signer, listOf(target), "wss://new.example"))
    }

    @Test fun simultaneousRequestsShareDiscoveryAndARejectedRelayIsRediscovered() = runTest {
        val first = "wss://one.example"
        val second = "wss://two.example"
        var available = first
        var discoveries = 0
        val probes = mutableListOf<String>()
        val resolver = WotRelayDiscovery({
            discoveries++
            delay(10)
            listOf(first, second)
        }, { key, url ->
            assertEquals(signer, key)
            probes.add(url)
            url == available
        })
        suspend fun resolve(rejected: String? = null) =
            resolver.resolve(target, signer, DEFAULT_WOT_RELAY, true, rejected) { true }
        val concurrent = async { resolve() }
        assertEquals(first, resolve())
        assertEquals(first, concurrent.await())
        assertEquals(1, discoveries)
        available = second
        assertEquals(second, resolve(first))
        assertEquals(2, discoveries)
        assertEquals(1, probes.count { it == first })
    }

    @Test fun laterWriteRelaysAreTriedAcrossBoundedRetries() = runTest {
        var clock = 0L
        val candidates = (1..6).map { "wss://relay$it.test" }
        val probes = mutableListOf<String>()
        val resolver = WotRelayDiscovery({ candidates }, { _, url ->
            probes += url
            url == candidates.last()
        }, { clock })
        suspend fun resolve() = resolver.resolve(target, signer, DEFAULT_WOT_RELAY, true) { true }
        assertNull(resolve())
        assertEquals(candidates.take(4), probes.filter { it in candidates })
        clock += 60_000
        assertEquals(candidates.last(), resolve())
        assertEquals(candidates, probes.filter { it in candidates })
    }

    @Test fun failedDiscoveryHasABoundedCooldownAndCustomRelaysStayPinned() = runTest {
        var clock = 0L
        var attempts = 0
        val resolver = WotRelayDiscovery({ emptyList() }, { _, _ -> attempts++; false }, { clock })
        suspend fun resolve(discover: Boolean) =
            resolver.resolve(target, signer, DEFAULT_WOT_RELAY, discover) { true }
        assertNull(resolve(true))
        assertNull(resolve(true))
        assertEquals(1, attempts)
        clock += 60_000
        assertNull(resolve(true))
        assertEquals(DEFAULT_WOT_RELAY, resolve(false))
        assertEquals(2, attempts)
    }

    @Test fun lateEmptyFromOldRelayCannotReinstateAMiss() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        store.setActiveWotProvider(signer, "wss://old.example")
        store.setActiveWotProvider(signer, "wss://new.example")
        store.insertWotAssertionChunk(signer, emptyList(), listOf(target), queryRelay = "wss://old.example")
        assertEquals(WotLookup.Pending, store.wotFor(target))
    }
}
