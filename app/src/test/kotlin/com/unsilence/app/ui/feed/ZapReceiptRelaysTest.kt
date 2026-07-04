package com.unsilence.app.ui.feed

import com.unsilence.app.data.relay.normalizeRelayUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zap-receipt relay set (NIP-57 ["relays", …] tag) must mirror the
 * engagement READ path — target author WRITE + own READ + event seen/hints —
 * so the wallet publishes the kind-9735 where this app actually fetches it.
 * This is the deliberate inverse of [engagementPublishRelays] (own write +
 * author read), which is correct for reactions/reposts but wrong for zaps.
 */
class ZapReceiptRelaysTest {

    private fun n(u: String) = normalizeRelayUrl(u)!!

    @Test
    fun `disjoint read-path sets are unioned — author write plus own read present`() {
        // engagement-publish set (own write + author read) is the OLD, wrong set;
        // the receipt-read path is author write + own read. They are disjoint here.
        val r = zapReceiptRelays(
            targetAuthorWrite = listOf("wss://author-write.com"),
            ownRead           = listOf("wss://own-read.com"),
            eventSeen         = emptyList(),
            relayHints        = emptyList(),
            fallbackHint      = null,
            blocked           = emptySet(),
        )
        assertTrue("author write must be present", r.contains(n("wss://author-write.com")))
        assertTrue("own read must be present", r.contains(n("wss://own-read.com")))
    }

    @Test
    fun `combines all sources and dedups`() {
        val r = zapReceiptRelays(
            targetAuthorWrite = listOf("wss://author-write.com"),
            ownRead           = listOf("wss://own-read.com"),
            eventSeen         = listOf("wss://seen.com", "wss://own-read.com"), // own-read repeated
            relayHints        = listOf("wss://hint.com"),
            fallbackHint      = "wss://fallback.com",
            blocked           = emptySet(),
        )
        assertEquals(
            setOf(
                n("wss://author-write.com"), n("wss://own-read.com"),
                n("wss://seen.com"), n("wss://hint.com"), n("wss://fallback.com"),
            ),
            r.toSet(),
        )
        assertEquals(5, r.size) // own-read.com deduped to one
    }

    @Test
    fun `blocked relays are excluded`() {
        val r = zapReceiptRelays(
            targetAuthorWrite = listOf("wss://author-write.com", "wss://bad.com"),
            ownRead           = emptyList(),
            eventSeen         = emptyList(),
            relayHints        = emptyList(),
            fallbackHint      = null,
            blocked           = setOf("wss://bad.com"),
        )
        assertEquals(listOf(n("wss://author-write.com")), r)
    }

    @Test
    fun `blank fallback is ignored`() {
        val r = zapReceiptRelays(
            targetAuthorWrite = listOf("wss://author-write.com"),
            ownRead           = emptyList(),
            eventSeen         = emptyList(),
            relayHints        = emptyList(),
            fallbackHint      = "",
            blocked           = emptySet(),
        )
        assertEquals(listOf(n("wss://author-write.com")), r)
    }

    @Test
    fun `nonblank fallback is included`() {
        val r = zapReceiptRelays(
            targetAuthorWrite = emptyList(),
            ownRead           = emptyList(),
            eventSeen         = emptyList(),
            relayHints        = emptyList(),
            fallbackHint      = "wss://fallback.com",
            blocked           = emptySet(),
        )
        assertEquals(listOf(n("wss://fallback.com")), r)
    }

    @Test
    fun `cap preserves author write and own read before popular event tail`() {
        val r = zapReceiptRelays(
            targetAuthorWrite = (1..6).map { "wss://author-write$it.com" },
            ownRead           = (1..4).map { "wss://own-read$it.com" },
            eventSeen         = (1..20).map { "wss://seen$it.com" },
            relayHints        = listOf("wss://hint.com"),
            fallbackHint      = "wss://fallback.com",
            blocked           = emptySet(),
            maxRelays         = 6,
            maxAuthorWrite    = 4,
            maxOwnRead        = 2,
            maxSourceRelays   = 2,
            maxRelayHints     = 1,
        )

        assertEquals(
            listOf(
                n("wss://author-write1.com"),
                n("wss://author-write2.com"),
                n("wss://author-write3.com"),
                n("wss://author-write4.com"),
                n("wss://own-read1.com"),
                n("wss://own-read2.com"),
            ),
            r,
        )
    }

    @Test
    fun `cap fills from source and hints when core relay buckets are small`() {
        val r = zapReceiptRelays(
            targetAuthorWrite = listOf("wss://author-write.com"),
            ownRead           = listOf("wss://own-read.com"),
            eventSeen         = listOf("wss://seen1.com", "wss://seen2.com", "wss://seen3.com"),
            relayHints        = listOf("wss://hint.com"),
            fallbackHint      = "wss://fallback.com",
            blocked           = emptySet(),
            maxRelays         = 6,
            maxAuthorWrite    = 4,
            maxOwnRead        = 2,
            maxSourceRelays   = 2,
            maxRelayHints     = 1,
        )

        assertEquals(
            listOf(
                n("wss://author-write.com"),
                n("wss://own-read.com"),
                n("wss://seen1.com"),
                n("wss://seen2.com"),
                n("wss://hint.com"),
                n("wss://fallback.com"),
            ),
            r,
        )
    }

    @Test
    fun `default relay selection is uncapped`() {
        val r = zapReceiptRelays(
            targetAuthorWrite = (1..30).map { "wss://author-write$it.com" },
            ownRead = emptyList(),
            eventSeen = emptyList(),
            relayHints = emptyList(),
            fallbackHint = null,
            blocked = emptySet(),
        )

        assertEquals(30, r.size)
    }
}
