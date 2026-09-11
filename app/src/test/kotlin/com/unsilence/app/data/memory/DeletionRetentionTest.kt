package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeletionRetentionTest {
    private val store = newStore()

    @Test
    fun `remote deletion keeps only bounded tombstones even for followed or mentioning authors`() = runTest {
        store.updateFollows(OWNER, setOf(REMOTE), createdAt = 1L)
        store.viewedPubkey = REMOTE
        val targets = (1..MAX_DELETION_TARGETS * 10).map(::target)
        val request = deletion(10_001, targets).let {
            it.copy(content = "x".repeat(100_000), tags = it.tags + listOf(listOf("p", OWNER)))
        }
        store.addRelaySeen(request.id, RELAY)

        store.insert(request)

        assertEquals(MAX_DELETION_TARGETS, targets.count(store::isDeleted))
        assertNull(store.getNostrEvent(request.id))
        assertTrue(store.eventsByAuthors(setOf(REMOTE), setOf(5)).isEmpty())
        assertEquals(0L, store.getMaxEventCreatedAt())
        assertEquals(0, store.pendingRelayCount)
        assertTrue(targets.all { store.relayHintsForEvent(it.id).isEmpty() })
        assertTrue(store.ownDeletionHistorySnapshot(OWNER).isEmpty())

        val restored = roundTrip(store)
        assertFalse("Remote tombstones are session-local", restored.isDeleted(targets.first()))
        assertNull(restored.getNostrEvent(request.id))
    }

    @Test
    fun `old snapshot deletion payloads use the same derived only path`() = runTest {
        val targets = (1..MAX_DELETION_TARGETS * 10).map(::target)
        val request = deletion(10_001, targets)

        store.insertFromSnapshot(request)

        assertEquals(MAX_DELETION_TARGETS, targets.count(store::isDeleted))
        assertNull(store.getNostrEvent(request.id))
        assertTrue(targets.all { store.relayHintsForEvent(it.id).isEmpty() })
        assertFalse(roundTrip(store).isDeleted(targets.first()))
    }

    @Test
    fun `own signed deletion history survives binary snapshots unchanged`() = runTest {
        val target = target(1).copy(pubkey = OWNER)
        val request = deletion(10_001, listOf(target), pubkey = OWNER).copy(
            content = "reason with unicode: café",
            tags = listOf(listOf("e", target.id, RELAY), listOf("k", "1"), listOf("custom", "keep this")),
        )
        store.insert(request)
        assertSignedPayloadEquals(request, store.ownDeletionHistorySnapshot(OWNER).single())
        assertNull(store.getNostrEvent(request.id))
        assertTrue(store.relayHintsForEvent(target.id).isEmpty())

        val restored = roundTrip(store)

        assertSignedPayloadEquals(request, restored.ownDeletionHistorySnapshot(OWNER).single())
        assertTrue(restored.isDeleted(target))
        assertFalse(restored.insert(target))
        assertTrue(restored.relayHintsForEvent(target.id).isEmpty())
    }

    @Test
    fun `owner resolved after a remote arrival can retain the repeated deletion`() {
        val target = target(1)
        val request = deletion(10_001, listOf(target))
        store.ownPubkey = null
        store.insert(request)
        assertTrue(store.ownDeletionHistorySnapshot(REMOTE).isEmpty())

        store.ownPubkey = REMOTE
        store.insert(request)

        assertSignedPayloadEquals(request, store.ownDeletionHistorySnapshot(REMOTE).single())
    }

    @Test
    fun `own history trims oldest timestamps without removing active tombstones`() = runTest {
        val targets = (1..OWN_DELETION_HISTORY_CAP + 1).map { target(it).copy(pubkey = OWNER) }
        val requests = targets.mapIndexed { index, target ->
            deletion(10_001 + index, listOf(target), pubkey = OWNER, createdAt = target.createdAt)
        }
        requests.asReversed().forEach(store::insert)

        val retained = store.ownDeletionHistorySnapshot(OWNER)
        assertEquals(OWN_DELETION_HISTORY_CAP + 1 - OWN_DELETION_HISTORY_TRIM, retained.size)
        assertFalse(retained.any { it.id == requests.first().id })
        assertTrue(retained.any { it.id == requests.last().id })
        assertTrue(targets.all(store::isDeleted))

        val restored = roundTrip(store)
        assertEquals(retained.map { it.id }.toSet(), restored.ownDeletionHistorySnapshot(OWNER).map { it.id }.toSet())
        assertFalse(restored.isDeleted(targets.first()))
        assertTrue(restored.isDeleted(targets.last()))
    }

    @Test
    fun `concurrent owner history writes cannot bypass its cap`() {
        val requests = (1..OWN_DELETION_HISTORY_CAP * 2).map { seed ->
            deletion(10_000 + seed, listOf(target(seed).copy(pubkey = OWNER)), pubkey = OWNER, createdAt = seed.toLong())
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = requests.map { request ->
                executor.submit {
                    start.await()
                    store.insert(request)
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }

            val retained = store.ownDeletionHistorySnapshot(OWNER)
            assertTrue(retained.size <= OWN_DELETION_HISTORY_CAP)
            assertTrue(retained.size >= OWN_DELETION_HISTORY_CAP - OWN_DELETION_HISTORY_TRIM)
            assertFalse(retained.any { it.id == requests.first().id })
            assertTrue(retained.any { it.id == requests.last().id })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `oversized owner payload applies its targets without raw retention`() = runTest {
        val target = target(1).copy(pubkey = OWNER)
        val request = deletion(10_001, listOf(target), pubkey = OWNER).copy(content = "x".repeat(OWN_DELETION_MAX_CHARS + 1))
        store.insert(request)

        assertTrue(store.isDeleted(target))
        assertTrue(store.ownDeletionHistorySnapshot(OWNER).isEmpty())
        assertNull(store.getNostrEvent(request.id))
        assertFalse(roundTrip(store).isDeleted(target))
    }

    @Test
    fun `empty tag fields cannot bypass owner payload bounds`() {
        val target = target(1).copy(pubkey = OWNER)
        val request = deletion(10_001, listOf(target), pubkey = OWNER).let {
            it.copy(tags = it.tags + List(OWN_DELETION_MAX_TAG_FIELDS) { listOf("") })
        }
        store.insert(request)

        assertTrue(store.isDeleted(target))
        assertTrue(store.ownDeletionHistorySnapshot(OWNER).isEmpty())
    }

    @Test
    fun `oversized event and address references are not retained as tombstone keys`() {
        val oversizedId = target(1).copy(id = "a".repeat(65))
        val oversizedAddress = target(2).copy(kind = 30023, tags = listOf(listOf("d", "x".repeat(MAX_DELETION_COORDINATE_CHARS))))
        val valid = target(3)
        store.insert(deletion(10_001, listOf(oversizedId, oversizedAddress, valid)))

        assertFalse(store.isDeleted(oversizedId))
        assertFalse(store.isDeleted(oversizedAddress))
        assertTrue(store.isDeleted(valid))
        assertTrue(store.relayHintsForEvent(oversizedId.id).isEmpty())
    }

    @Test
    fun `history is cleared by either teardown and cannot leak across owner changes`() {
        val request = deletion(10_001, listOf(target(1).copy(pubkey = OWNER)), pubkey = OWNER)
        store.insert(request)
        store.clearUserState()
        assertTrue(store.ownDeletionHistorySnapshot(OWNER).isEmpty())

        store.insert(request)
        store.clear()
        assertTrue(store.ownDeletionHistorySnapshot(OWNER).isEmpty())

        store.insert(request)
        store.ownPubkey = REMOTE
        assertTrue(store.ownDeletionHistorySnapshot(OWNER).isEmpty())
        store.insert(request)
        assertTrue(store.ownDeletionHistorySnapshot(REMOTE).isEmpty())
        assertTrue(store.ownDeletionHistorySnapshot(OWNER).isEmpty())
    }

    private suspend fun roundTrip(source: MemoryEventStore): MemoryEventStore {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { source.saveSnapshotBinary(it) }
        return newStore().also { restored ->
            DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { restored.restoreSnapshotBinary(it) }
        }
    }

    private fun assertSignedPayloadEquals(expected: NostrEvent, actual: NostrEvent) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.pubkey, actual.pubkey)
        assertEquals(expected.kind, actual.kind)
        assertEquals(expected.createdAt, actual.createdAt)
        assertEquals(expected.content, actual.content)
        assertEquals(expected.tags, actual.tags)
        assertEquals(expected.sig, actual.sig)
    }

    private fun newStore() = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider()).apply { ownPubkey = OWNER }

    private fun target(seed: Int) = event(seed, kind = 1, pubkey = REMOTE, createdAt = seed.toLong())

    private fun deletion(seed: Int, targets: List<NostrEvent>, pubkey: String = REMOTE, createdAt: Long = 20_000L) = event(
        seed, kind = 5, pubkey, createdAt,
        tags = targets.map { target ->
            if (target.kind == 30023) listOf("a", "30023:${target.pubkey}:${target.tags.single()[1]}")
            else listOf("e", target.id, RELAY)
        },
    )

    private fun event(seed: Int, kind: Int, pubkey: String, createdAt: Long, tags: List<List<String>> = emptyList()) = NostrEvent(
        id = seed.toString(16).padStart(64, '0'),
        pubkey = pubkey,
        kind = kind,
        content = "",
        createdAt = createdAt,
        tags = tags,
        sig = "c".repeat(128),
        relayUrl = RELAY,
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = 0L,
        relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add(RELAY) },
    )

    private companion object {
        val OWNER = "a".repeat(64)
        val REMOTE = "b".repeat(64)
        const val RELAY = "wss://relay.example.com"
    }
}
