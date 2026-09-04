package com.unsilence.app.data

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MuteMutation
import com.unsilence.app.data.memory.MuteMutationKind
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.relay.MuteListFetchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppBootstrapperMuteSettlementTest {

    @Test
    fun `timeout with no raw kind-10000 returns no event`() = runTest {
        val updates = MutableStateFlow<NostrEvent?>(null)

        val result = awaitMuteListEvent(
            current = { null },
            updates = updates,
            timeoutMs = 1_000L,
        )

        assertNull(result)
    }

    @Test
    fun `local pending overlay cannot satisfy the raw event settlement gate`() = runTest {
        val store = MemoryEventStore(
            object : MuteKeyProvider {},
            stubTimelineServiceProvider(),
        ).apply { ownPubkey = OWN }
        store.recordPendingMuteMutation(MuteMutation(MuteMutationKind.User, "alice", true))
        val waiting = async {
            awaitMuteListEvent(
                current = { store.getLatestMuteListEvent(OWN) },
                updates = store.ownMuteListEventFlow(),
                timeoutMs = 1_000L,
            )
        }

        advanceUntilIdle()

        assertNull(waiting.await())
        assertEquals(setOf("alice"), store.getMuteList(OWN)?.privatePubkeys)
    }

    @Test
    fun `real raw kind-10000 completes settlement wait`() = runTest {
        val updates = MutableStateFlow<NostrEvent?>(null)
        val event = event("mute-event")
        val waiting = async {
            awaitMuteListEvent(
                current = { null },
                updates = updates,
                timeoutMs = 5_000L,
            )
        }

        updates.value = event
        advanceUntilIdle()

        assertEquals("mute-event", waiting.await()?.id)
    }

    @Test
    fun `cached event without relay evidence cannot authorize settlement`() {
        assertEquals(false, MuteListFetchResult().hasFreshnessEvidence)
    }

    @Test
    fun `partial EOSE coverage cannot authorize a cached base`() {
        val result = MuteListFetchResult(
            eoseRelays = setOf("wss://one.example"),
            expectedRelays = setOf("wss://one.example", "wss://two.example"),
        )

        assertEquals(false, result.confirmedEmptyCoverage)
        assertEquals(false, result.hasFreshnessEvidence)
    }

    @Test
    fun `full real EOSE coverage authorizes a cached or empty base`() {
        val relays = setOf("wss://one.example", "wss://two.example")
        val result = MuteListFetchResult(
            eoseRelays = relays,
            expectedRelays = relays,
        )

        assertEquals(true, result.confirmedEmptyCoverage)
        assertEquals(true, result.hasFreshnessEvidence)
    }

    @Test
    fun `verified fetch event is freshness evidence without full EOSE coverage`() {
        val result = MuteListFetchResult(receivedEvent = event("network-event"))

        assertEquals(true, result.hasFreshnessEvidence)
    }

    @Test
    fun `declared write relays all require real EOSE before empty is confirmed`() {
        val writes = setOf("wss://one.example", "wss://two.example")
        val partial = MuteListFetchResult(
            eoseRelays = setOf("wss://one.example"),
            expectedRelays = writes,
            writeRelays = writes,
        )
        val complete = partial.copy(eoseRelays = writes)

        assertEquals(false, partial.confirmedEmptyCoverage)
        assertEquals(true, complete.confirmedEmptyCoverage)
    }

    @Test
    fun `missing relay list uses corroborated fallback and indexer quorum`() {
        val fallbacks = setOf("wss://global-one.example", "wss://global-two.example")
        val indexers = setOf("wss://index-one.example", "wss://index-two.example")
        val covered = MuteListFetchResult(
            eoseRelays = fallbacks + indexers,
            expectedRelays = fallbacks + indexers,
            fallbackRelays = fallbacks,
            indexerRelays = indexers,
        )

        assertEquals(true, covered.confirmedEmptyCoverage)
        assertEquals(
            false,
            covered.copy(eoseRelays = fallbacks + "wss://index-one.example")
                .confirmedEmptyCoverage,
        )
        assertEquals(
            false,
            covered.copy(
                fallbackRelays = setOf("wss://global-one.example"),
                eoseRelays = setOf("wss://global-one.example") + indexers,
            ).confirmedEmptyCoverage,
        )
    }

    @Test
    fun `settlement ignores stale current event until observed relay revision lands`() = runTest {
        val stale = event("stale").copy(createdAt = 99L)
        val fresh = event("fresh").copy(createdAt = 101L)
        val updates = MutableStateFlow<NostrEvent?>(stale)
        val waiting = async {
            awaitMuteListEvent(
                current = { stale },
                updates = updates,
                timeoutMs = 5_000L,
                accept = { it.createdAt >= fresh.createdAt },
            )
        }

        updates.value = fresh
        advanceUntilIdle()

        assertEquals("fresh", waiting.await()?.id)
    }

    @Test
    fun `mute verification coalesces concurrent relay copies`() {
        val gate = MuteEventVerificationGate()

        assertEquals(true, gate.tryBegin("same-event"))
        assertEquals(false, gate.tryBegin("same-event"))
        gate.finish("same-event", verified = true)
        assertEquals(false, gate.tryBegin("same-event"))
    }

    @Test
    fun `failed mute verification may retry and reset clears verified revision`() {
        val gate = MuteEventVerificationGate()

        assertEquals(true, gate.tryBegin("event"))
        gate.finish("event", verified = false)
        assertEquals(true, gate.tryBegin("event"))
        gate.finish("event", verified = true)
        gate.reset()
        assertEquals(true, gate.tryBegin("event"))
    }

    @Test
    fun `settled mute revision suppresses later relay copies`() {
        val gate = MuteEventVerificationGate()

        gate.markVerified("settled-event")

        assertEquals(false, gate.tryBegin("settled-event"))
        assertEquals(true, gate.tryBegin("new-event"))
    }

    private fun event(id: String) = NostrEvent(
        id = id,
        pubkey = OWN,
        kind = 10000,
        content = "",
        createdAt = 100L,
        tags = emptyList(),

        sig = "sig",
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = 0L,
        relaysSeen = mutableSetOf(),
    )

    private companion object {
        const val OWN = "owner"
    }
}
