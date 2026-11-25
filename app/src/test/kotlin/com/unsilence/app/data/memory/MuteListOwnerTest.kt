package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MuteListOwnerTest {
    private val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())

    @Test fun signedOutCollectorHasNoMuteList() = runBlocking {
        assertNull(store.ownMuteListFlow().first())
    }

    @Test fun subscribedCollectorDropsClearedListsAndSelectsOnlyCurrentOwner() = runBlocking {
        val changes = Channel<MuteList?>(Channel.UNLIMITED)
        fun seed(owner: String, muted: String) {
            store.updateMuteListPrivateTags(owner, setOf(muted), emptySet(), emptySet(), emptySet())
        }
        suspend fun next() = withTimeout(3_000) { changes.receive() }
        store.ownPubkey = "alice"
        seed("alice", "alpha")
        seed("bob", "bravo")
        val collector = launch { store.ownMuteListFlow().collect(changes::send) }
        try {
            assertEquals(setOf("alpha"), next()?.privatePubkeys)
            store.ownPubkey = "bob"
            assertEquals(setOf("bravo"), next()?.privatePubkeys)
            store.clear()
            assertNull(next())
            seed("bob", "charlie")
            assertEquals(setOf("charlie"), next()?.privatePubkeys)
            store.ownPubkey = null
            assertNull(next())
            store.ownPubkey = "bob"
            assertEquals(setOf("charlie"), next()?.privatePubkeys)
            store.clearUserState()
            assertNull(next())
        } finally {
            collector.cancel()
        }
    }
}
