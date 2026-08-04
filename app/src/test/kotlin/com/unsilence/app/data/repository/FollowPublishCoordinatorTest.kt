package com.unsilence.app.data.repository

import com.unsilence.app.data.memory.FollowsSnapshot
import com.unsilence.app.data.memory.NostrEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowPublishCoordinatorTest {
    @Test
    fun `unresolved follows do not sign publish or mutate`() = runTest {
        val harness = Harness(initial = null)

        val result = harness.coordinator().publishMutation(OWN) { it + TARGET }

        assertEquals(FollowPublishResult.FollowsUnavailable, result)
        assertEquals(0, harness.signCalls)
        assertEquals(0, harness.applyCalls)
        assertEquals(0, harness.publishCalls)
        assertEquals(0, harness.revertCalls)
        assertEquals(0, harness.persistCalls)
        assertEquals(null, harness.snapshot)
    }

    @Test
    fun `known empty follows publish as a legitimate new account state`() = runTest {
        val harness = Harness(FollowsSnapshot(emptySet(), createdAt = 10L))

        val result = harness.coordinator(nowSeconds = 10L)
            .publishMutation(OWN) { it + TARGET }

        assertEquals(FollowPublishResult.Success(setOf(TARGET)), result)
        assertEquals(FollowsSnapshot(setOf(TARGET), createdAt = 11L), harness.snapshot)
        assertEquals(listOf(11L), harness.signedCreatedAts)
        assertEquals(1, harness.publishCalls)
        assertEquals(1, harness.persistCalls)
    }

    @Test
    fun `unchanged mutation does not sign publish or schedule persistence`() = runTest {
        val original = FollowsSnapshot(setOf("alice"), createdAt = 10L)
        val harness = Harness(original)

        val result = harness.coordinator().publishMutation(OWN) { it }

        assertEquals(FollowPublishResult.Success(original.follows), result)
        assertEquals(0, harness.signCalls)
        assertEquals(0, harness.applyCalls)
        assertEquals(0, harness.publishCalls)
        assertEquals(0, harness.persistCalls)
        assertEquals(original, harness.snapshot)
    }

    @Test
    fun `contact list arriving during signing aborts the stale publish`() = runTest {
        val original = FollowsSnapshot(setOf("alice"), createdAt = 10L)
        val relayState = FollowsSnapshot(setOf("alice", "relay-new"), createdAt = 20L)
        val harness = Harness(original).apply {
            afterSign = { snapshot = relayState }
        }

        val result = harness.coordinator().publishMutation(OWN) { it + TARGET }

        assertEquals(FollowPublishResult.ChangedWhileSigning, result)
        assertEquals(relayState, harness.snapshot)
        assertEquals(1, harness.signCalls)
        assertEquals(1, harness.applyCalls)
        assertEquals(0, harness.publishCalls)
        assertEquals(0, harness.revertCalls)
        assertEquals(0, harness.persistCalls)
    }

    @Test
    fun `signer exception surfaces failure without mutating follows`() = runTest {
        val original = FollowsSnapshot(setOf("alice"), createdAt = 10L)
        val harness = Harness(original).apply { signException = IllegalStateException("sign failed") }

        val result = harness.coordinator().publishMutation(OWN) { it + TARGET }

        assertEquals(FollowPublishResult.SigningFailed, result)
        assertEquals(original, harness.snapshot)
        assertEquals(0, harness.applyCalls)
        assertEquals(0, harness.publishCalls)
        assertEquals(0, harness.persistCalls)
    }

    @Test
    fun `zero relay acknowledgements restore both follows and createdAt`() = runTest {
        val original = FollowsSnapshot(setOf("alice"), createdAt = 30L)
        val harness = Harness(original).apply { relayAccepted = false }

        val result = harness.coordinator(nowSeconds = 30L)
            .publishMutation(OWN) { it + TARGET }

        assertEquals(FollowPublishResult.NoRelayAccepted(rollbackRestored = true), result)
        assertEquals(original, harness.snapshot)
        assertEquals(1, harness.applyCalls)
        assertEquals(1, harness.publishCalls)
        assertEquals(1, harness.revertCalls)
        assertEquals(0, harness.persistCalls)
    }

    @Test
    fun `one relay acknowledgement retains optimistic follows`() = runTest {
        val original = FollowsSnapshot(setOf("alice"), createdAt = 40L)
        val harness = Harness(original).apply { relayAccepted = true }

        val result = harness.coordinator(nowSeconds = 40L)
            .publishMutation(OWN) { it + TARGET }

        assertEquals(FollowPublishResult.Success(setOf("alice", TARGET)), result)
        assertEquals(FollowsSnapshot(setOf("alice", TARGET), 41L), harness.snapshot)
        assertEquals(0, harness.revertCalls)
        assertEquals(1, harness.persistCalls)
        assertEquals(listOf("event-1"), harness.persistedEventIds)
    }

    @Test
    fun `accepted persistence hook runs strictly after relay acknowledgement`() = runTest {
        val harness = Harness(FollowsSnapshot(setOf("alice"), createdAt = 40L))

        val result = harness.coordinator(nowSeconds = 40L)
            .publishMutation(OWN) { it + TARGET }

        assertEquals(FollowPublishResult.Success(setOf("alice", TARGET)), result)
        assertEquals(listOf("sign", "apply", "publish", "persist"), harness.callOrder)
    }

    @Test
    fun `createdAt advances across two toggles in the same epoch second`() = runTest {
        val harness = Harness(FollowsSnapshot(emptySet(), createdAt = 100L))
        val coordinator = harness.coordinator(nowSeconds = 100L)

        coordinator.publishMutation(OWN) { it + "alice" }
        coordinator.publishMutation(OWN) { it + "bob" }

        assertEquals(listOf(101L, 102L), harness.signedCreatedAts)
        assertEquals(FollowsSnapshot(setOf("alice", "bob"), 102L), harness.snapshot)
        assertEquals(2, harness.persistCalls)
    }

    @Test
    fun `relay acceptance tracker registers before dispatch and accepts one target OK`() = runTest {
        var callback: ((String, Boolean, String) -> Unit)? = null
        var unregistered = false

        val accepted = awaitRelayAcceptance(
            targetRelays = setOf("wss://one.example", "wss://two.example"),
            timeoutMs = 1_000L,
            register = { callback = it },
            unregister = { unregistered = true },
            dispatch = {
                assertTrue(callback != null)
                callback?.invoke("wss://one.example", true, "saved")
            },
        )

        assertTrue(accepted)
        assertTrue(unregistered)
    }

    @Test
    fun `relay acceptance tracker fails after every target rejects`() = runTest {
        var callback: ((String, Boolean, String) -> Unit)? = null
        var unregistered = false

        val accepted = awaitRelayAcceptance(
            targetRelays = setOf("wss://one.example", "wss://two.example"),
            timeoutMs = 1_000L,
            register = { callback = it },
            unregister = { unregistered = true },
            dispatch = {
                callback?.invoke("wss://one.example", false, "blocked")
                callback?.invoke("wss://two.example", false, "blocked")
            },
        )

        assertFalse(accepted)
        assertTrue(unregistered)
    }

    @Test
    fun `relay acceptance tracker times out and unregisters when no relay replies`() = runTest {
        var unregistered = false

        val accepted = awaitRelayAcceptance(
            targetRelays = setOf("wss://one.example"),
            timeoutMs = 1L,
            register = {},
            unregister = { unregistered = true },
            dispatch = {},
        )

        assertFalse(accepted)
        assertTrue(unregistered)
    }

    private class Harness(initial: FollowsSnapshot?) {
        var snapshot: FollowsSnapshot? = initial
        var signCalls = 0
        var applyCalls = 0
        var publishCalls = 0
        var revertCalls = 0
        var persistCalls = 0
        var relayAccepted = true
        var signException: Exception? = null
        var afterSign: () -> Unit = {}
        val signedCreatedAts = mutableListOf<Long>()
        val persistedEventIds = mutableListOf<String>()
        val callOrder = mutableListOf<String>()

        fun coordinator(nowSeconds: Long = 1_000L): FollowPublishCoordinator =
            FollowPublishCoordinator(
                loadSnapshot = { snapshot },
                sign = { createdAt, follows, _ ->
                    callOrder += "sign"
                    signCalls++
                    signedCreatedAts += createdAt
                    signException?.let { throw it }
                    afterSign()
                    val eventId = "event-$signCalls"
                    SignedFollowList(
                        eventId = eventId,
                        eventJson = "{}",
                        event = contactListEvent(eventId, createdAt, follows),
                    )
                },
                applyOptimistic = { _, previous, updatedFollows, updatedCreatedAt ->
                    callOrder += "apply"
                    applyCalls++
                    if (snapshot != previous) {
                        false
                    } else {
                        snapshot = FollowsSnapshot(updatedFollows, updatedCreatedAt)
                        true
                    }
                },
                revertOptimistic = { _, optimisticFollows, optimisticCreatedAt, previous ->
                    callOrder += "revert"
                    revertCalls++
                    if (snapshot != FollowsSnapshot(optimisticFollows, optimisticCreatedAt)) {
                        false
                    } else {
                        snapshot = previous
                        true
                    }
                },
                publishAndAwait = { _, _ ->
                    callOrder += "publish"
                    publishCalls++
                    relayAccepted
                },
                persistAccepted = { signed ->
                    callOrder += "persist"
                    persistCalls++
                    persistedEventIds += signed.event.id
                },
                nowSeconds = { nowSeconds },
            )

        private fun contactListEvent(
            eventId: String,
            createdAt: Long,
            follows: Set<String>,
        ): NostrEvent = NostrEvent(
            id = eventId,
            pubkey = OWN,
            kind = 3,
            content = "",
            createdAt = createdAt,
            tags = follows.sorted().map { listOf("p", it) },
            tagsJson = "[]",
            sig = "sig",
            relayUrl = "",
            replyToId = null,
            rootId = null,
            hasContentWarning = false,
            contentWarningReason = null,
            firstSeenAt = 0L,
            relaysSeen = mutableSetOf(),
        )
    }

    private companion object {
        const val OWN = "own-pubkey"
        const val TARGET = "target-pubkey"
    }
}
