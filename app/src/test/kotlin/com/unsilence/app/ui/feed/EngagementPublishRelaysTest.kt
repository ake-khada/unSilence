package com.unsilence.app.ui.feed

import com.unsilence.app.data.relay.normalizeRelayUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngagementPublishRelaysTest {

    private fun n(u: String) = normalizeRelayUrl(u)!!

    @Test
    fun `keeps both intentional buckets without speculative tail`() {
        val r = engagementPublishRelays(
            ownWrite         = listOf("wss://own.com"),
            targetAuthorRead = listOf("wss://author.com"),
            blocked          = emptySet(),
        )

        assertEquals(listOf(n("wss://own.com"), n("wss://author.com")), r)
        val speculativeTail = setOf(n("wss://seen.com"), n("wss://hint.com"))
        assertTrue(r.none { it in speculativeTail })
    }

    @Test
    fun `own write relays are emitted before target author read relays`() {
        val r = engagementPublishRelays(
            ownWrite = listOf("wss://own1.com", "wss://own2.com"),
            targetAuthorRead = listOf("wss://author1.com", "wss://author2.com"),
            blocked = emptySet(),
        )

        assertEquals(
            listOf(
                n("wss://own1.com"),
                n("wss://own2.com"),
                n("wss://author1.com"),
                n("wss://author2.com"),
            ),
            r,
        )
    }

    @Test
    fun `max own write is enforced per bucket`() {
        val r = engagementPublishRelays(
            ownWrite = (1..6).map { "wss://own$it.com" },
            targetAuthorRead = listOf("wss://author.com"),
            blocked = emptySet(),
            maxOwnWrite = 4,
            maxAuthorRead = 4,
            maxRelays = 8,
        )

        assertEquals(
            (1..4).map { n("wss://own$it.com") } +
                n("wss://author.com"),
            r,
        )
    }

    @Test
    fun `max author read is enforced per bucket`() {
        val r = engagementPublishRelays(
            ownWrite = listOf("wss://own.com"),
            targetAuthorRead = (1..6).map { "wss://author$it.com" },
            blocked = emptySet(),
            maxOwnWrite = 4,
            maxAuthorRead = 4,
            maxRelays = 8,
        )

        assertEquals(
            listOf(n("wss://own.com")) +
                (1..4).map { n("wss://author$it.com") },
            r,
        )
    }

    @Test
    fun `max relays caps total after own write bucket first`() {
        val r = engagementPublishRelays(
            ownWrite = (1..4).map { "wss://own$it.com" },
            targetAuthorRead = (1..4).map { "wss://author$it.com" },
            blocked = emptySet(),
            maxOwnWrite = 4,
            maxAuthorRead = 4,
            maxRelays = 6,
        )

        assertEquals(
            (1..4).map { n("wss://own$it.com") } +
                (1..2).map { n("wss://author$it.com") },
            r,
        )
    }

    @Test
    fun `blocked relays are excluded and cross bucket duplicates are deduped`() {
        val r = engagementPublishRelays(
            ownWrite = listOf("wss://shared.com", "wss://bad.com", "wss://own.com"),
            targetAuthorRead = listOf("shared.com", "wss://bad.com", "wss://author.com"),
            blocked = setOf("wss://bad.com"),
        )

        assertEquals(
            listOf(n("wss://shared.com"), n("wss://own.com"), n("wss://author.com")),
            r,
        )
    }
}
