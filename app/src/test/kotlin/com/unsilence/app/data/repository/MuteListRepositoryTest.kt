package com.unsilence.app.data.repository

import com.unsilence.app.data.memory.MuteMutation
import com.unsilence.app.data.memory.MuteMutationKind
import com.unsilence.app.data.memory.MutePublishSnapshot
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.PendingMutePublish
import com.unsilence.app.data.memory.emptyMuteList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteListRepositoryTest {

    @Test
    fun `all six unsafe entry points persist and remain pending without publishing`() {
        val recorded = mutableListOf<MuteMutation>()
        var revision = 0L
        var persists = 0
        var publishes = 0
        val coordinator = MuteMutationCoordinator(
            record = { mutation ->
                recorded += mutation
                PendingMutePublish(OWN, ++revision)
            },
            persist = { persists++; true },
            isPublishSafe = { false },
            requestPublish = { publishes++ },
        )

        val results = listOf(
            coordinator.muteUser("alice"),
            coordinator.unmuteUser("bob"),
            coordinator.muteWord("LOUD"),
            coordinator.unmuteWord("quiet"),
            coordinator.muteHashtag("News"),
            coordinator.unmuteHashtag("spam"),
        )

        assertEquals(List(6) { MuteResult.PendingSync }, results)
        assertEquals(6, persists)
        assertEquals(0, publishes)
        assertEquals(
            listOf(
                MuteMutation(MuteMutationKind.User, "alice", true),
                MuteMutation(MuteMutationKind.User, "bob", false),
                MuteMutation(MuteMutationKind.Word, "LOUD", true),
                MuteMutation(MuteMutationKind.Word, "quiet", false),
                MuteMutation(MuteMutationKind.Hashtag, "News", true),
                MuteMutation(MuteMutationKind.Hashtag, "spam", false),
            ),
            recorded,
        )
    }

    @Test
    fun `all six safe entry points persist and request publishing`() {
        var revision = 0L
        var persists = 0
        var publishes = 0
        val coordinator = MuteMutationCoordinator(
            record = { PendingMutePublish(OWN, ++revision) },
            persist = { persists++; true },
            isPublishSafe = { true },
            requestPublish = { publishes++ },
        )

        val results = listOf(
            coordinator.muteUser("alice"),
            coordinator.unmuteUser("bob"),
            coordinator.muteWord("word"),
            coordinator.unmuteWord("word"),
            coordinator.muteHashtag("tag"),
            coordinator.unmuteHashtag("tag"),
        )

        assertEquals(List(6) { MuteResult.Queued }, results)
        assertEquals(6, persists)
        assertEquals(6, publishes)
    }

    @Test
    fun `unavailable mutation neither persists nor publishes`() {
        var persists = 0
        var publishes = 0
        val coordinator = MuteMutationCoordinator(
            record = { null },
            persist = { persists++; true },
            isPublishSafe = { true },
            requestPublish = { publishes++ },
        )

        assertEquals(MuteResult.Unavailable, coordinator.muteUser("alice"))
        assertEquals(0, persists)
        assertEquals(0, publishes)
    }

    @Test
    fun `journal failure is surfaced and cannot start publishing`() {
        var publishes = 0
        val coordinator = MuteMutationCoordinator(
            record = { PendingMutePublish(OWN, 1L) },
            persist = { false },
            isPublishSafe = { true },
            requestPublish = { publishes++ },
        )

        assertEquals(MuteResult.Unavailable, coordinator.muteUser("alice"))
        assertEquals(0, publishes)
    }

    @Test
    fun `zero relay OK keeps pending state and requests retry`() = runTest {
        val harness = PublishHarness(baseCreatedAt = 100L).apply { relayAccepted = false }

        val result = harness.coordinator(nowSeconds = 100L).publishPending(OWN)

        assertEquals(MutePublishResult.NoRelayAccepted, result)
        assertEquals(0, harness.commitCalls)
        assertEquals(1, harness.retryCalls)
        assertTrue(harness.snapshot != null)
    }

    @Test
    fun `one relay OK commits and clears pending`() = runTest {
        val harness = PublishHarness(baseCreatedAt = 100L)

        val result = harness.coordinator(nowSeconds = 100L).publishPending(OWN)

        assertEquals(MutePublishResult.Success(revision = 1L, createdAt = 101L), result)
        assertEquals(1, harness.commitCalls)
        assertTrue(harness.snapshot == null)
        assertEquals(listOf("signed-101"), harness.rememberedIds)
    }

    @Test
    fun `createdAt is strictly monotonic across two publishes in one epoch second`() = runTest {
        val harness = PublishHarness(baseCreatedAt = 100L)
        val coordinator = harness.coordinator(nowSeconds = 100L)

        coordinator.publishPending(OWN)
        harness.enqueueNextMutation(revision = 2L)
        coordinator.publishPending(OWN)

        assertEquals(listOf(101L, 102L), harness.signedCreatedAts)
    }

    @Test
    fun `state change during signing cannot publish stale mute list`() = runTest {
        val harness = PublishHarness(baseCreatedAt = 100L)
        harness.afterSign = { harness.enqueueNextMutation(revision = 2L) }

        val result = harness.coordinator().publishPending(OWN)

        assertEquals(MutePublishResult.ChangedWhileSigning, result)
        assertEquals(0, harness.publishCalls)
        assertEquals(1, harness.retryCalls)
        assertTrue(harness.snapshot?.pending?.revision == 2L)
    }

    @Test
    fun `self echo tracker retains multiple ids and is bounded and expiring`() {
        var now = 1_000L
        val tracker = SelfPublishedEventTracker(maxSize = 2, ttlMs = 100L) { now }
        tracker.add("first")
        tracker.add("second")
        assertTrue(tracker.contains("first"))
        assertTrue(tracker.contains("second"))

        tracker.add("third")
        assertFalse(tracker.contains("first"))
        assertTrue(tracker.contains("second"))
        assertTrue(tracker.contains("third"))
        assertEquals(2, tracker.size())

        now = 1_101L
        assertFalse(tracker.contains("second"))
        assertFalse(tracker.contains("third"))
    }

    @Test
    fun `confirmed empty base rejects a concurrently arriving relay event`() {
        assertTrue(
            muteBaseMatchesExpectation(
                currentEventId = null,
                expectedEventId = null,
                expectNoCurrentEvent = true,
            ),
        )
        assertFalse(
            muteBaseMatchesExpectation(
                currentEventId = "arrived-after-eose",
                expectedEventId = null,
                expectNoCurrentEvent = true,
            ),
        )
    }

    private class PublishHarness(baseCreatedAt: Long) {
        var snapshot: MutePublishSnapshot? = snapshot(revision = 1L, baseCreatedAt)
        var relayAccepted = true
        var publishCalls = 0
        var commitCalls = 0
        var retryCalls = 0
        var afterSign: () -> Unit = {}
        val signedCreatedAts = mutableListOf<Long>()
        val rememberedIds = mutableListOf<String>()

        fun coordinator(nowSeconds: Long = 1_000L) = MutePublishCoordinator(
            loadSnapshot = { snapshot },
            sign = { _, createdAt ->
                signedCreatedAts += createdAt
                val signed = signed(createdAt)
                afterSign()
                signed
            },
            beginPublish = { captured -> captured == snapshot },
            rememberSelfPublished = rememberedIds::add,
            publishAndAwait = {
                publishCalls++
                relayAccepted
            },
            commitAccepted = { captured, event ->
                commitCalls++
                if (snapshot != captured) {
                    false
                } else {
                    snapshot = null
                    lastAcceptedCreatedAt = event.createdAt
                    true
                }
            },
            requestRetry = { retryCalls++ },
            nowSeconds = { nowSeconds },
        )

        private var lastAcceptedCreatedAt = baseCreatedAt

        fun enqueueNextMutation(revision: Long) {
            snapshot = snapshot(revision, lastAcceptedCreatedAt)
        }

        private fun snapshot(revision: Long, baseCreatedAt: Long): MutePublishSnapshot {
            val pending = PendingMutePublish(
                ownerPubkey = OWN,
                revision = revision,
                userChanges = mapOf("alice-$revision" to true),
            )
            return MutePublishSnapshot(
                pending = pending,
                muteList = pending.applyTo(emptyMuteList()),
                baseEventId = "base-$baseCreatedAt",
                baseCreatedAt = baseCreatedAt,
            )
        }

        private fun signed(createdAt: Long): SignedMuteList {
            val event = NostrEvent(
                id = "signed-$createdAt",
                pubkey = OWN,
                kind = 10000,
                content = "encrypted",
                createdAt = createdAt,
                tags = emptyList(),
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
            return SignedMuteList(event.id, "{}", event)
        }
    }

    private companion object {
        const val OWN = "owner"
    }
}
