package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(Parameterized::class)
class DeletionTombstoneTest(private val targetKind: Int) {
    private val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
    private var deletionSequence = 1_000_000

    @Test
    fun `overflow trims oldest timestamps rather than oldest arrivals`() {
        val targets = (1..DELETION_TOMBSTONE_CAP + 1).map(::target)
        // Newest arrives first; the oldest tombstone itself triggers the trim.
        for (target in targets.asReversed()) {
            store.insert(deletion(listOf(target), createdAt = target.createdAt))
        }

        assertFalse(store.isDeleted(targets.first()))
        assertTrue(store.isDeleted(targets.last()))
        val retained = targets.count(store::isDeleted)
        assertEquals(DELETION_TOMBSTONE_CAP + 1 - DELETION_TOMBSTONE_TRIM, retained)
        assertTrue(retained <= DELETION_TOMBSTONE_CAP)
    }

    @Test
    fun `real deletion continues suppressing its target within the cap`() {
        val target = target(1)
        assertTrue(store.insert(target))
        store.insert(deletion(listOf(target)))
        assertNull(store.getNostrEvent(target.id))

        store.insert(deletion((2..MAX_DELETION_TARGETS + 1).map(::target)))

        assertTrue(store.isDeleted(target))
        assertFalse(store.insert(target))
        assertNull(store.getNostrEvent(target.id))
    }

    @Test
    fun `concurrent insertions keep tombstones within the cap`() {
        val targets = (1..DELETION_TOMBSTONE_CAP * 2).map(::target)
        val requests = targets.chunked(MAX_DELETION_TARGETS).map { chunk ->
            deletion(chunk, createdAt = chunk.last().createdAt)
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

            val retained = targets.count(store::isDeleted)
            assertTrue(retained <= DELETION_TOMBSTONE_CAP)
            assertTrue(retained >= DELETION_TOMBSTONE_CAP - DELETION_TOMBSTONE_TRIM)
            assertFalse(store.isDeleted(targets.first()))
            assertTrue(store.isDeleted(targets.last()))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `one deletion cannot create tombstones past the target limit`() {
        val targets = (1..MAX_DELETION_TARGETS * 10).map(::target)
        store.insert(deletion(targets))

        assertEquals(MAX_DELETION_TARGETS, targets.count(store::isDeleted))
        assertTrue(targets.take(MAX_DELETION_TARGETS).all(store::isDeleted))
        assertTrue(targets.drop(MAX_DELETION_TARGETS).none(store::isDeleted))
    }

    @Test
    fun `non target tags do not consume the deletion target budget`() {
        val targets = (1..MAX_DELETION_TARGETS + 1).map(::target)
        val deletion = deletion(targets)
        val decoratedTags = deletion.tags.flatMap { tag ->
            listOf(emptyList(), listOf("e"), listOf("k", "$targetKind"), tag)
        }
        store.insert(deletion.copy(tags = decoratedTags))

        assertEquals(MAX_DELETION_TARGETS, targets.count(store::isDeleted))
        assertTrue(store.isDeleted(targets[MAX_DELETION_TARGETS - 1]))
        assertFalse(store.isDeleted(targets.last()))
    }

    @Test
    fun `event and address references share one target budget`() {
        val targets = (1..MAX_DELETION_TARGETS * 2).map { seed ->
            target(seed).copy(
                kind = if (seed % 2 == 0) 30023 else 1,
                tags = listOf(listOf("d", "target-$seed")),
            )
        }
        store.insert(deletion(targets))

        assertEquals(MAX_DELETION_TARGETS, targets.count(store::isDeleted))
        assertTrue(targets.take(MAX_DELETION_TARGETS).all(store::isDeleted))
        assertTrue(targets.drop(MAX_DELETION_TARGETS).none(store::isDeleted))
    }

    @Test
    fun `same deletion restores suppression after its tombstone is evicted`() {
        val target = target(1)
        val deletion = deletion(listOf(target), createdAt = 1L)
        store.insert(deletion)
        for (seed in 2..DELETION_TOMBSTONE_CAP + 1) {
            store.insert(deletion(listOf(target(seed)), createdAt = seed.toLong()))
        }
        assertFalse(store.isDeleted(target))
        assertTrue(store.insert(target))

        store.insert(deletion)

        assertNull(store.getNostrEvent(target.id))
        assertTrue(store.isDeleted(target))
        assertFalse(store.insert(target))
    }

    @Test
    fun `older deletion replay cannot move a retained tombstone to the eviction frontier`() {
        val target = target(1)
        val older = deletion(listOf(target), createdAt = 1L)
        store.insert(older)
        store.insert(deletion(listOf(target), createdAt = DELETION_TOMBSTONE_CAP + 2L))
        store.insert(older)
        for (seed in 2..DELETION_TOMBSTONE_CAP + 1) {
            store.insert(deletion(listOf(target(seed)), createdAt = seed.toLong()))
        }

        assertTrue(store.isDeleted(target))
    }

    @Test
    fun `replayed deletion applies its address cutoff but event references remain exact`() {
        val target = target(1)
        val deletion = deletion(listOf(target), createdAt = 1L)
        store.insert(deletion)

        // A new version has another id but the same address. Only an address
        // reference can match it, and that reference must honor its timestamp.
        val newer = target.copy(id = target(2).id, createdAt = 2L)
        assertTrue(store.insert(newer))
        store.insert(deletion)
        assertNotNull(store.getNostrEvent(newer.id))
        assertFalse(store.isDeleted(newer))

        // A newer address deletion or an exact event-id reference can remove it.
        store.insert(deletion(listOf(newer), createdAt = 2L))
        assertNull(store.getNostrEvent(newer.id))
        assertTrue(store.isDeleted(newer))
    }

    @Test
    fun `evicted deletion from a different author cannot delete the real target`() {
        val unknownTarget = target(1)
        val deletion = deletion(listOf(unknownTarget), createdAt = 1L)
        store.insert(deletion)
        assertTrue(store.isDeleted(unknownTarget))
        for (seed in 2..DELETION_TOMBSTONE_CAP + 1) {
            store.insert(deletion(listOf(target(seed)), createdAt = seed.toLong()))
        }
        assertFalse("The old tombstone must actually have been evicted", store.isDeleted(unknownTarget))

        // The event id was unknown when the request arrived. Its actual author
        // is different from the deletion signer and must still be protected.
        val realTarget = unknownTarget.copy(pubkey = OTHER_AUTHOR)
        assertTrue(store.insert(realTarget))
        store.insert(deletion)

        assertNotNull(store.getNostrEvent(realTarget.id))
        assertFalse(store.isDeleted(realTarget))

        // A new request id also forces the known-target authorship branch,
        // independently of any duplicate-id shortcut in the store.
        store.insert(deletion(listOf(unknownTarget)))
        assertNotNull(store.getNostrEvent(realTarget.id))
        assertFalse(store.isDeleted(realTarget))
    }

    @Test
    fun `non author deletion cannot displace an earlier legitimate tombstone`() {
        val target = target(1)
        assertTrue(store.insert(target))
        store.insert(deletion(listOf(target)))
        assertNull(store.getNostrEvent(target.id))

        store.insert(deletion(listOf(target)).copy(pubkey = OTHER_AUTHOR))

        assertTrue(store.isDeleted(target))
        assertFalse(store.insert(target))
        assertNull(store.getNostrEvent(target.id))
    }

    @Test
    fun `legitimate deletion suppresses a target after a non author deletion arrives first`() {
        val target = target(1)
        store.insert(deletion(listOf(target)).copy(pubkey = OTHER_AUTHOR))
        assertFalse(store.isDeleted(target))

        store.insert(deletion(listOf(target)))

        assertTrue(store.isDeleted(target))
        assertFalse(store.insert(target))
        assertNull(store.getNostrEvent(target.id))
    }

    private fun target(seed: Int): NostrEvent = event(
        seed = seed,
        kind = targetKind,
        createdAt = seed.toLong(),
        tags = if (targetKind == 30023) listOf(listOf("d", "target-$seed")) else emptyList(),
    )

    private fun deletion(targets: List<NostrEvent>, createdAt: Long = 20_000L): NostrEvent = event(
        seed = deletionSequence++,
        kind = 5,
        createdAt = createdAt,
        tags = targets.map { target ->
            if (target.kind == 30023) {
                listOf("a", "30023:${target.pubkey}:${target.tags.single()[1]}")
            } else {
                listOf("e", target.id)
            }
        },
    )

    private fun event(
        seed: Int,
        kind: Int,
        createdAt: Long,
        tags: List<List<String>>,
    ) = NostrEvent(
        id = seed.toString(16).padStart(64, '0'),
        pubkey = AUTHOR,
        kind = kind,
        content = "",
        createdAt = createdAt,
        tags = tags,
        sig = "sig",
        relayUrl = RELAY,
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = 0L,
        relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add(RELAY) },
    )

    companion object {
        private val AUTHOR = "a".repeat(64)
        private val OTHER_AUTHOR = "b".repeat(64)
        private const val RELAY = "wss://relay.example.com"

        @JvmStatic
        @Parameterized.Parameters(name = "targetKind={0}")
        fun targetKinds(): List<Array<Int>> = listOf(arrayOf(1), arrayOf(30023))
    }
}
