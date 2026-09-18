package com.unsilence.app.ui.shared

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.ProfileResolver
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.repository.UserRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import kotlinx.coroutines.flow.first
import org.junit.Test

class TimelineCardDataTest {
    @Test
    fun `profileFlow seeds from a cached profile immediately`() = runTest {
        val store = memoryEventStore()
        store.insert(profileEvent(pubkey = "alice", picture = "https://example.com/alice.jpg"))
        val data = TimelineCardData(userRepository(store), store)

        val flow = data.profileFlow("alice")

        assertEquals("https://example.com/alice.jpg", flow.value?.picture)
    }

    @Test
    fun `profileFlow reads fresh state without replacing a handle or retaining a sharing job`() = runTest {
        val store = memoryEventStore()
        val data = TimelineCardData(userRepository(store), store)
        val missing = data.profileFlow("alice")
        assertNull(missing.value)

        store.insert(profileEvent(pubkey = "alice", picture = "https://example.com/alice.jpg"))
        val refreshed = data.profileFlow("alice")

        assertSame(missing, refreshed)
        assertEquals("https://example.com/alice.jpg", refreshed.value?.picture)
    }

    @Test
    fun `LRU eviction never invalidates a handle still held by a card`() = runTest {
        val store = memoryEventStore()
        val data = TimelineCardData(userRepository(store), store)
        val heldByCard = data.profileFlow("alice")
        repeat(501) { data.profileFlow("author-$it") }
        val freshHandle = data.profileFlow("alice")
        assertNotSame(heldByCard, freshHandle)
        store.insert(profileEvent(pubkey = "alice", picture = "https://example.com/new.jpg"))
        assertEquals(freshHandle.value, heldByCard.value)
        assertEquals("https://example.com/new.jpg", heldByCard.first()?.picture)
    }

    @Test
    fun `stats handles stay valid after cache eviction`() = runTest {
        val store = memoryEventStore()
        val data = TimelineCardData(userRepository(store), store)
        val heldByCard = data.statsFlow("note")
        repeat(501) { data.statsFlow("note-$it") }
        assertNotSame(heldByCard, data.statsFlow("note"))
        assertEquals(store.currentStatsSnapshot("note"), heldByCard.first())
    }

    private fun userRepository(store: MemoryEventStore): UserRepository {
        val resolver = ProfileResolver(
            memoryEventStore = store,
            relayPool = dagger.Lazy<RelayPool> { error("RelayPool should not be used by this test") },
        )
        return UserRepository(store, resolver)
    }

    private fun memoryEventStore(): MemoryEventStore =
        MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())

    private fun profileEvent(pubkey: String, picture: String): NostrEvent =
        NostrEvent(
            id = "profile-$pubkey-${System.nanoTime()}",
            pubkey = pubkey,
            kind = 0,
            content = """{"name":"$pubkey","picture":"$picture"}""",
            createdAt = System.currentTimeMillis() / 1000,
            tags = emptyList(),

            sig = "sig",
            relayUrl = "wss://relay.example.com",
            replyToId = null,
            rootId = null,
            hasContentWarning = false,
            contentWarningReason = null,
            firstSeenAt = System.currentTimeMillis(),
            relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add("wss://relay.example.com") },
        )
}
