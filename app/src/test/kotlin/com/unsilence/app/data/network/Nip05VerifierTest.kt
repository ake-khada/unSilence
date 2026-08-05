package com.unsilence.app.data.network

import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Nip05VerifierTest {
    private val owner = "11".repeat(32)
    private val target = "22".repeat(32)
    private val address = "alice@example.com"

    @Test
    fun `unfollowed unopened profile triggers zero network calls`() = runTest {
        val store = storeWithFollows(emptySet())
        var calls = 0
        val coordinator = coordinator(store) {
            calls++
            Nip05VerificationStatus.VERIFIED
        }

        assertEquals(
            Nip05VerificationStatus.UNKNOWN,
            coordinator.resolveNowForTest(target, address),
        )
        assertEquals(0, calls)
    }

    @Test
    fun `followed profile resolves once and reuses the cache`() = runTest {
        val store = storeWithFollows(setOf(target))
        var calls = 0
        val coordinator = coordinator(store) {
            calls++
            Nip05VerificationStatus.VERIFIED
        }

        assertEquals(Nip05VerificationStatus.VERIFIED, coordinator.resolveNowForTest(target, address))
        assertEquals(Nip05VerificationStatus.VERIFIED, coordinator.resolveNowForTest(target, address))
        assertEquals(1, calls)
    }

    @Test
    fun `explicit profile open allows an unfollowed profile`() = runTest {
        val store = storeWithFollows(emptySet())
        var calls = 0
        val coordinator = coordinator(store) {
            calls++
            Nip05VerificationStatus.VERIFIED
        }

        coordinator.markProfileOpened(target)
        assertEquals(Nip05VerificationStatus.VERIFIED, coordinator.resolveNowForTest(target, address))
        assertEquals(1, calls)
    }

    @Test
    fun `visible claim resolves when its profile becomes explicitly opened`() = runTest {
        val store = storeWithFollows(emptySet())
        var calls = 0
        val coordinator = coordinator(store) {
            calls++
            Nip05VerificationStatus.VERIFIED
        }

        coordinator.requestIfEligible(target, address)
        runCurrent()
        assertEquals(0, calls)

        coordinator.markProfileOpened(target)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `visible claim resolves when the own follow list arrives later`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider()).apply {
            ownPubkey = owner
        }
        var calls = 0
        val coordinator = coordinator(store) {
            calls++
            Nip05VerificationStatus.VERIFIED
        }
        runCurrent()

        coordinator.requestIfEligible(target, address)
        runCurrent()
        assertEquals(0, calls)

        store.updateFollows(owner, setOf(target), createdAt = 1L)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `changing nip05 on the same pubkey never inherits the former badge`() = runTest {
        val store = storeWithFollows(setOf(target))
        val coordinator = coordinator(store) { Nip05VerificationStatus.VERIFIED }
        val oldKey = requireNotNull(nip05VerificationCacheKey(target, address))
        val newAddress = "alice@new.example"
        val newKey = requireNotNull(nip05VerificationCacheKey(target, newAddress))

        assertEquals(Nip05VerificationStatus.VERIFIED, coordinator.resolveNowForTest(target, address))
        assertEquals(Nip05VerificationStatus.VERIFIED, store.currentNip05Verification(oldKey))

        coordinator.verificationFlow(target, newAddress)
        assertEquals(Nip05VerificationStatus.UNKNOWN, store.currentNip05Verification(newKey))
    }

    @Test
    fun `stale rendered claim does not trigger a network request`() = runTest {
        val store = storeWithFollows(setOf(target))
        store.insert(profileEvent("alice@new.example"))
        var calls = 0
        val coordinator = coordinator(store) {
            calls++
            Nip05VerificationStatus.VERIFIED
        }

        assertEquals(
            Nip05VerificationStatus.UNKNOWN,
            coordinator.resolveNowForTest(target, address),
        )
        assertEquals(0, calls)
    }

    @Test
    fun `expired positive cache is resolved again`() = runTest {
        val store = storeWithFollows(setOf(target))
        var now = 1_800_000_000_000L
        var calls = 0
        val coordinator = Nip05ResolutionCoordinator(
            memoryEventStore = store,
            fetch = {
                calls++
                Nip05VerificationStatus.VERIFIED
            },
            onStored = {},
            scope = backgroundScope,
            nowMs = { now },
            staggerMs = { 0L },
        )

        assertEquals(Nip05VerificationStatus.VERIFIED, coordinator.resolveNowForTest(target, address))
        now += NIP05_POSITIVE_TTL_MS
        assertEquals(Nip05VerificationStatus.VERIFIED, coordinator.resolveNowForTest(target, address))
        assertEquals(2, calls)
    }

    private fun storeWithFollows(follows: Set<String>): MemoryEventStore =
        MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider()).apply {
            ownPubkey = owner
            updateFollows(owner, follows, createdAt = 1L)
        }

    private fun profileEvent(nip05: String) = NostrEvent(
        id = "profile-event",
        pubkey = target,
        kind = 0,
        content = """{"nip05":"$nip05"}""",
        createdAt = 1L,
        tags = emptyList(),

        sig = "sig",
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = 1_000L,
        relaysSeen = mutableSetOf("wss://relay.example"),
    )

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        store: MemoryEventStore,
        fetch: suspend (Nip05VerificationCacheKey) -> Nip05VerificationStatus?,
    ) = Nip05ResolutionCoordinator(
        memoryEventStore = store,
        fetch = fetch,
        onStored = {},
        scope = backgroundScope,
        staggerMs = { 0L },
    )
}
