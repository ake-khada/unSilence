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
import org.junit.Test

class TimelineCardDataTest {
    @Test
    fun `profileFlow seeds from a cached profile immediately`() = runTest {
        val store = memoryEventStore()
        store.insert(profileEvent(pubkey = "alice", picture = "https://example.com/alice.jpg"))
        val data = TimelineCardData(userRepository(store), store)

        val flow = data.profileFlow("alice", backgroundScope)

        assertEquals("https://example.com/alice.jpg", flow.value?.picture)
    }

    @Test
    fun `profileFlow replaces stale null cache when profile arrives while inactive`() = runTest {
        val store = memoryEventStore()
        val data = TimelineCardData(userRepository(store), store)
        val missing = data.profileFlow("alice", backgroundScope)
        assertNull(missing.value)

        store.insert(profileEvent(pubkey = "alice", picture = "https://example.com/alice.jpg"))
        val refreshed = data.profileFlow("alice", backgroundScope)

        assertEquals("https://example.com/alice.jpg", refreshed.value?.picture)
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
