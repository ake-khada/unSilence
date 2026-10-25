package com.unsilence.app.ui.search

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun discoveryStore(): MemoryEventStore =
    MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider()).apply {
        ownPubkey = "owner"
    }

internal fun discoveryEvent(
    id: String,
    author: String = id,
    time: Long = 1,
    kind: Int = 1,
    content: String = "note",
    tags: List<List<String>> = emptyList(),
): NostrEvent = NostrEvent(
    id = id, pubkey = author, kind = kind, content = content, createdAt = time,
    tags = tags, sig = "test", relayUrl = "wss://relay.example",
    replyToId = null, rootId = null, hasContentWarning = false, contentWarningReason = null,
    firstSeenAt = time * 1_000,
    relaysSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>(),
)

class DiscoveryFallbackTest {
    @Test
    fun `self follows and mutes cannot consume the candidate budget`() {
        val store = discoveryStore()
        val follows = (0..80).mapTo(linkedSetOf()) { "followed-$it" }
        store.updateFollows("owner", follows, 1)
        repeat(40) { store.insert(discoveryEvent("new-$it", time = 10L + it)) }
        repeat(81) { store.insert(discoveryEvent("follow-note-$it", "followed-$it", 100L + it)) }
        store.insert(discoveryEvent("self", "owner", 200))
        store.insert(discoveryEvent("muted-note", "muted", 201))
        store.insert(discoveryEvent("mute", "owner", 202, 10000, "", listOf(listOf("p", "muted"))))
        val result = store.discoverySnapshot("owner")
        assertEquals(32, result.users.size)
        assertTrue(result.users.all { it.pubkey.startsWith("new-") })
        assertTrue(result.users.all { it.followerCount == null })
    }

    @Test
    fun `zero count and missing profile stay eligible`() {
        val store = discoveryStore()
        store.insert(discoveryEvent("note", "zero"))
        store.insert(discoveryEvent("profile", "zero", kind = 0, content = """{"name":"Zero"}"""))
        store.cacheFollowerCount("zero", 0)
        store.insert(discoveryEvent("unknown-note", "unknown", 2))
        val result = store.discoverySnapshot("owner")
        assertEquals(listOf("unknown", "zero"), result.users.map { it.pubkey })
        assertEquals(0L, result.users.last().followerCount)
    }

    @Test
    fun `non-note entries count toward the hard scan ceiling`() {
        val store = discoveryStore()
        store.insert(discoveryEvent("old-note", time = 1))
        repeat(1_010) { store.insert(discoveryEvent("reaction-$it", time = 10L + it, kind = 7)) }
        val result = store.discoverySnapshot("owner")
        assertEquals(1_000, result.indexEntriesExamined)
        assertEquals(0, result.notesExamined)
        assertTrue(result.users.isEmpty())
    }

    @Test
    fun `excluded notes count toward the note ceiling without widening for people`() {
        val store = discoveryStore()
        store.updateFollows("owner", setOf("followed"), 1)
        store.insert(discoveryEvent("unfollowed", time = 1))
        repeat(600) { store.insert(discoveryEvent("note-$it", "followed", 10L + it)) }
        val result = store.discoverySnapshot("owner")
        assertEquals(500, result.indexEntriesExamined)
        assertEquals(500, result.notesExamined)
        assertTrue(result.users.isEmpty())
        assertTrue(store.discoverySnapshot("another-owner").users.isEmpty())
    }

    @Test
    fun `bounded fixture timing`() {
        val store = discoveryStore()
        repeat(60) { i ->
            store.insert(discoveryEvent("profile-$i", "author-$i", 1, 0, """{"name":"Person $i"}"""))
            store.cacheFollowerCount("author-$i", i.toLong())
        }
        repeat(2_000) { i ->
            store.insert(discoveryEvent("note-$i", "author-${i % 60}", 10L + i, tags = listOf(listOf("t", "topic${i % 12}"))))
        }
        repeat(20) { store.discoverySnapshot("owner") }
        val samples = LongArray(100) {
            val start = System.nanoTime()
            val snapshot = store.discoverySnapshot("owner")
            assertEquals(12, snapshot.hashtags.size)
            assertEquals(32, snapshot.users.size)
            assertEquals(500, snapshot.indexEntriesExamined)
            assertEquals(500, snapshot.notesExamined)
            System.nanoTime() - start
        }.sorted()
        println("DISCOVERY_BOUNDED fixture=2060 notes=2000 peopleCandidates=32 measuredPasses=100 medianUs=${samples[50] / 1000} p95Us=${samples[95] / 1000} maxUs=${samples.last() / 1000} indexEntries=500 notesExamined=500")
    }
}
