package com.unsilence.app.data.relay

import org.junit.Assert.*
import org.junit.Test

class FollowingHistoryTest {
    @Test fun `eleven isolated authors have an actual declared route without expanding ten live slots`() {
        val writes = (1..11).associate { "%064x".format(it) to listOf("wss://r$it.test") }
        val resolver = OutboxRelayResolver(object : RelayMetadataSource {
            override fun writeRelaysFor(pubkey: String) = writes[pubkey].orEmpty()
        })
        val live = resolver.resolveFollowing(writes.keys, listOf("wss://fallback.test"), emptySet(),
            OutboxRelayResolver.Config(listOf(1), 300))
        val history = resolver.resolveFollowingHistory(writes.keys, live, emptySet())
        val liveWrites = live.flatMap { it.urls }.toSet() - "wss://fallback.test"
        assertEquals(10, liveWrites.size)
        val reached = writes.filter { (author, urls) ->
            urls.any { it in liveWrites } || history.candidates[author].orEmpty().any { it in urls }
        }.keys
        assertEquals("Unrelated fallback assignment is not an author's outbox turn", writes.keys, reached)
        assertEquals(1, history.candidates.size)
    }
}
