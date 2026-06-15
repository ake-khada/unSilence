package com.unsilence.app.ui.feed

import com.unsilence.app.data.relay.normalizeRelayUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class EngagementPublishRelaysTest {

    private fun n(u: String) = normalizeRelayUrl(u)!!

    @Test
    fun `combines all sources and dedups`() {
        val r = engagementPublishRelays(
            ownWrite         = listOf("wss://own.com"),
            targetAuthorRead = listOf("wss://author.com"),
            eventSeen        = listOf("wss://seen.com", "wss://own.com"), // own repeated
            relayHints       = listOf("wss://hint.com"),
            fallbackHint     = "wss://fallback.com",
            blocked          = emptySet(),
        )
        assertEquals(
            setOf(n("wss://own.com"), n("wss://author.com"), n("wss://seen.com"), n("wss://hint.com"), n("wss://fallback.com")),
            r.toSet(),
        )
        assertEquals(5, r.size) // own.com deduped to one
    }

    @Test
    fun `blocked relays are excluded`() {
        val r = engagementPublishRelays(
            ownWrite = listOf("wss://own.com", "wss://bad.com"),
            targetAuthorRead = emptyList(),
            eventSeen = emptyList(),
            relayHints = emptyList(),
            fallbackHint = null,
            blocked = setOf("wss://bad.com"),
        )
        assertEquals(listOf(n("wss://own.com")), r)
    }

    @Test
    fun `blank fallback is ignored`() {
        val r = engagementPublishRelays(
            ownWrite = listOf("wss://own.com"),
            targetAuthorRead = emptyList(),
            eventSeen = emptyList(),
            relayHints = emptyList(),
            fallbackHint = "",
            blocked = emptySet(),
        )
        assertEquals(listOf(n("wss://own.com")), r)
    }
}
