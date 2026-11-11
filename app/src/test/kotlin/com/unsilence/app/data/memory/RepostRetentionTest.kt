package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RepostRetentionTest(
    private val repostKind: Int,
    private val targetKind: Int,
    private val byCoordinate: Boolean,
) {
    private val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
    private val target = event(1, targetKind).copy(tags = if (targetKind >= 30000) listOf(listOf("d", "fixture")) else emptyList())
    private val coordinate = "$targetKind:${target.pubkey}:fixture"
    private val targetKey = if (byCoordinate) coordinate else target.id
    private val repost = repost(2)

    @Test
    fun `target eviction preserves exact retained contributors and duplicate provenance`() {
        insertPair()
        evictTarget()
        assertNull(store.getNostrEvent(target.id))
        assertNotNull(store.getNostrEvent(repost.id))
        assertEquals(1, store.repostCount(targetKey))
        assertFalse(store.insert(repost.copy(relayUrl = OTHER_RELAY, relaysSeen = mutableSetOf(OTHER_RELAY))))
        assertTrue(OTHER_RELAY in store.getNostrEvent(repost.id)!!.relaysSeen)
        assertTrue(store.insert(target))
        assertEquals(1, store.repostCount(target.id))
    }

    @Test
    fun `target then contributor eviction removes the last backing count`() {
        insertPair()
        evictTarget()
        assertEquals(1, store.repostCount(targetKey))
        evictReposts()
        assertEquals(0, store.repostCount(targetKey))
        store.insert(target)
        assertEquals(0, store.repostCount(target.id))
    }

    @Test
    fun `contributor then target eviction drops sticky summary without retained payload`() {
        insertPair()
        evictReposts()
        assertEquals("Summary stays while target remains", 1, store.repostCount(target.id))
        assertEquals("Lookup index alone is not retention proof", setOf(repost.id), store.repostEventIdsForTarget(repost.pubkey, targetKey))
        evictTarget()
        assertEquals(0, store.repostCount(targetKey))
        store.insert(target)
        assertEquals(0, store.repostCount(target.id))
    }

    @Test
    fun `refetch after both eviction orders counts once`() {
        for (targetFirst in listOf(true, false)) {
            store.clear()
            insertPair()
            if (targetFirst) { evictTarget(); evictReposts() } else { evictReposts(); evictTarget() }
            store.insert(target)
            assertEquals(0, store.repostCount(target.id))
            assertTrue(store.insert(repost))
            repeat(3) { assertFalse(store.insert(repost)) }
            assertEquals(1, store.repostCount(target.id))
        }
    }

    @Test
    fun `repeated contributor recovery then deletion leaves no scalar ghost`() {
        insertPair()
        repeat(3) {
            evictReposts()
            assertEquals(1, store.repostCount(target.id))
            assertTrue(store.insert(repost))
        }
        delete(repost)
        assertEquals(0, store.repostCount(target.id))
        assertFalse(store.insert(repost))
    }

    @Test
    fun `multiple reposts by one actor count once and exact deletions revoke only that actor`() {
        val sameActor = repost(3, repost.pubkey)
        val otherActor = repost(4)
        insertPair()
        store.insert(sameActor)
        store.insert(otherActor)
        assertEquals(2, store.repostCount(target.id))
        delete(repost)
        assertEquals(2, store.repostCount(target.id))
        delete(sameActor)
        assertEquals(1, store.repostCount(target.id))
        delete(otherActor)
        assertEquals(0, store.repostCount(target.id))
    }

    @Test
    fun `deletion after raw eviction removes sticky actor and prevents tombstone replay`() {
        insertPair()
        evictReposts()
        assertEquals(1, store.repostCount(target.id))
        delete(repost)
        assertEquals(0, store.repostCount(target.id))
        assertTrue(store.repostEventIdsForTarget(repost.pubkey, targetKey).isEmpty())
        assertFalse(store.insert(repost))
        assertEquals(0, store.repostCount(target.id))
    }

    @Test
    fun `deleting evicted siblings preserves remaining sticky evidence until each is deleted`() {
        val sibling = repost(3, repost.pubkey)
        insertPair()
        store.insert(sibling)
        evictReposts()
        delete(repost)
        assertEquals(1, store.repostCount(target.id))
        delete(sibling)
        assertEquals(0, store.repostCount(target.id))
    }

    @Test
    fun `deletion finds exact ownership even beyond the actor target summary bound`() {
        store.insert(target)
        repeat(500) { index ->
            store.insert(repost(10_000 + index, repost.pubkey).copy(tags = listOf(listOf("e", id(20_000 + index)))))
        }
        store.insert(repost)
        evictReposts()
        assertEquals(1, store.repostCount(target.id))
        delete(repost)
        assertEquals(0, store.repostCount(target.id))
        assertFalse(store.insert(repost))
    }

    @Test
    fun `wrong-author deletion after eviction cannot erase or tombstone repost`() {
        insertPair()
        evictReposts()
        delete(repost, actor = event(90).pubkey)
        assertEquals(1, store.repostCount(target.id))
        assertTrue(store.insert(repost))
        assertEquals(1, store.repostCount(target.id))
    }

    @Test
    fun `deletion of retained contributor after target eviction revokes backed count`() {
        insertPair()
        evictTarget()
        delete(repost)
        assertEquals(0, store.repostCount(targetKey))
        store.insert(target)
        assertEquals(0, store.repostCount(target.id))
        assertFalse(store.insert(repost))
    }

    @Test
    fun `account store teardown clears raw payloads indexes and summaries`() {
        insertPair()
        evictReposts()
        store.clear()
        assertNull(store.getNostrEvent(target.id))
        assertEquals(0, store.repostCount(targetKey))
        assertTrue(store.repostEventIdsForTarget(repost.pubkey, targetKey).isEmpty())
        store.insert(target)
        assertEquals(0, store.repostCount(target.id))
    }

    @Test
    fun `direct and coordinate contributions from one actor display once`() {
        assumeTrue(byCoordinate)
        insertPair()
        store.insert(repost(3, repost.pubkey).copy(tags = listOf(listOf("e", target.id))))
        assertEquals(1, store.repostCount(target.id))
        evictTarget()
        store.insert(target)
        assertEquals(1, store.repostCount(target.id))
    }

    @Test
    fun `evicting older revision preserves coordinate summary owned by retained revision`() {
        assumeTrue(byCoordinate)
        val newer = target.copy(id = id(50), createdAt = 50)
        insertPair()
        store.insert(newer)
        evictReposts()
        store.openDisplayWindow().use { window ->
            window.update(listOf(newer.id to newer.id), setOf(newer.id))
            evictTarget()
            assertNull(store.getNostrEvent(target.id))
            assertNotNull(store.getNostrEvent(newer.id))
            assertEquals(1, store.repostCount(newer.id))
        }
        evictTarget()
        assertEquals(0, store.repostCount(coordinate))
    }

    private fun insertPair() { assertTrue(store.insert(target)); assertTrue(store.insert(repost)) }
    private fun evictTarget() = store.evictOldContentEventsForTest(mapOf(targetKind to 0))
    private fun evictReposts() = store.evictOldContentEventsForTest(mapOf(repostKind to 0))
    private fun delete(event: NostrEvent, actor: String = event.pubkey) {
        store.insert(event(100 + event.id.takeLast(4).toInt(16), 5).copy(
            pubkey = actor, tags = listOf(listOf("e", event.id)), createdAt = 100,
        ))
    }
    private fun repost(seed: Int, actor: String = event(seed).pubkey) = event(seed, repostKind).copy(
        pubkey = actor, content = "", tags = listOf(listOf(if (byCoordinate) "a" else "e", targetKey)),
    )
    private fun event(seed: Int, kind: Int = 1) = NostrEvent(
        id = id(seed), pubkey = id(1000 + seed), kind = kind, content = "fixture", createdAt = seed.toLong(),
        tags = emptyList(), sig = "", relayUrl = RELAY, replyToId = null, rootId = null,
        hasContentWarning = false, contentWarningReason = null, firstSeenAt = 1L,
        relaysSeen = mutableSetOf(RELAY),
    )
    private fun id(seed: Int) = seed.toString(16).padStart(64, '0')

    companion object {
        private const val RELAY = "wss://relay.example"
        private const val OTHER_RELAY = "wss://other.example"
        @JvmStatic @Parameterized.Parameters(name = "kind={0}, target={1}, coordinate={2}")
        fun cases() = listOf(
            arrayOf<Any>(6, 1, false), arrayOf<Any>(16, 1, false), arrayOf<Any>(16, 30023, false),
            arrayOf<Any>(16, 30023, true), arrayOf<Any>(16, 34235, true), arrayOf<Any>(16, 34236, true),
        )
    }
}
