package com.unsilence.app.ui.feed

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReferenceResolutionTest {
    private fun store() = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
    private fun target(id: String) = EventReferenceTarget(id, null, null, emptyList())
    private fun event(id: String, kind: Int = 1, tags: List<List<String>> = emptyList()) = NostrEvent(
        id = id, pubkey = "author", kind = kind, content = "content", tags = tags,
        createdAt = 100, sig = "sig", relayUrl = "wss://relay.example", firstSeenAt = 100_000,
        replyToId = null, rootId = null, hasContentWarning = false, contentWarningReason = null,
        relaysSeen = java.util.concurrent.ConcurrentHashMap.newKeySet(),
    )

    @Test fun `account switch fences old result without blocking new account lookup`() = runTest {
        val store = store()
        var owner = "old"
        val oldRelease = CompletableDeferred<Unit>()
        val requests = mutableListOf<String?>()
        val resolver = ReferenceResolution(store, { owner }, { request, _ ->
            requests += request.owner
            if (request.owner == "old") oldRelease.await()
            else store.insert(event("target"))
            store.getEventEntity("target")
        }, StandardTestDispatcher(testScheduler))
        val old = async { resolver.lookup(target("target")) }
        runCurrent()
        owner = "new"
        val fresh = async { resolver.lookup(target("target")) }
        runCurrent()
        assertEquals("target", fresh.await()?.id)
        oldRelease.complete(Unit)
        runCurrent()
        assertTrue("Old account must not receive the result", old.isCancelled)
        assertEquals(listOf("old", "new"), requests)
    }

    @Test fun `old account miss cooldown cannot suppress new account lookup`() = runTest {
        val store = store()
        var owner = "old"
        val requests = mutableListOf<String?>()
        val resolver = ReferenceResolution(store, { owner }, { request, _ ->
            requests += request.owner
            if (request.owner == "new") store.insert(event("target"))
            store.getEventEntity("target")
        }, StandardTestDispatcher(testScheduler))
        assertNull(resolver.lookup(target("target")))
        owner = "new"
        assertEquals("target", resolver.lookup(target("target"))?.id)
        assertEquals(listOf("old", "old", "new"), requests)
    }

    @Test fun `old observer cannot deliver a store arrival after account switch`() = runTest {
        val store = store()
        var owner = "old"
        val resolver = ReferenceResolution(store, { owner }, { _, _ -> awaitCancellation() },
            StandardTestDispatcher(testScheduler))
        val states = mutableListOf<ReferenceState<com.unsilence.app.data.memory.EventEntity>>()
        val job = backgroundScope.launch { resolver.observe(target("target")).collect { states += it } }
        runCurrent()
        owner = "new"
        store.insert(event("target"))
        runCurrent()
        assertTrue(states.all { it.event == null })
        assertTrue(job.isCancelled)
        assertEquals("target", resolver.lookup(target("target"))?.id)
    }

    @Test fun `real store late insert and deletion update observer without new requests`() = runTest {
        val store = store()
        var requests = 0
        val resolver = ReferenceResolution(store, { "owner" }, { _, _ -> requests++; null },
            StandardTestDispatcher(testScheduler))
        val states = mutableListOf<ReferenceState<com.unsilence.app.data.memory.EventEntity>>()
        val job = backgroundScope.launch { resolver.observe(target("target")).collect { states += it } }
        runCurrent()
        advanceTimeBy(2_001); runCurrent()
        assertFalse(states.last().loading)
        assertEquals(2, requests)
        store.insert(event("target"))
        runCurrent()
        assertEquals("target", states.last().event?.id)
        val before = states.size
        store.insert(event("unrelated"))
        runCurrent()
        assertEquals("Unrelated feed signals must not re-render the target", before, states.size)
        store.insert(event("deletion", 5, listOf(listOf("e", "target"))))
        runCurrent()
        assertNull(states.last().event)
        assertEquals(2, requests)
        job.cancelAndJoin()
    }

    @Test fun `slow optional discovery cannot delay a successful lookup`() = runTest {
        var cancelled = false
        val result = withReferenceDiscovery(
            discover = { try { awaitCancellation() } finally { cancelled = true } },
            resolve = { delay(1); "found" },
        )
        assertEquals("found", result)
        assertEquals(1L, testScheduler.currentTime)
        assertTrue(cancelled)
    }

    @Test fun `failed optional discovery cannot fail a successful lookup`() = runTest {
        val result = withReferenceDiscovery(
            discover = { throw java.io.IOException("offline") },
            resolve = { delay(1); "found" },
        )
        assertEquals("found", result)
        assertEquals(1L, testScheduler.currentTime)
    }
}
