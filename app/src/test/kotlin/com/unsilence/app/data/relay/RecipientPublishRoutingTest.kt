package com.unsilence.app.data.relay

import com.unsilence.app.ui.compose.resolveComposePublishRelays
import org.junit.Assert.*
import org.junit.Test

class RecipientPublishRoutingTest {
    private val own = "aa".repeat(32)
    private val a = "bb".repeat(32)
    private val b = "cc".repeat(32)
    private val c = "dd".repeat(32)
    private fun tags(vararg keys: String) = keys.map { listOf("p", it) }
    private fun relays(name: String, count: Int) = (1..count).map { "wss://$name$it.example" }

    @Test fun `uppercase-only recipient p tag is rejected without a metadata lookup`() {
        var lookups = 0
        val result = recipientPublishRelays(listOf("wss://own.example"), own, tags(a.uppercase()),
            { lookups++; listOf("wss://inbox.example") }, emptySet())
        assertEquals("invalid wire pubkey must not be normalized into a recipient", 0, lookups)
        assertEquals(listOf("wss://own.example"), result)
    }

    @Test fun `multiple recipient inboxes are allocated round robin without capping own writes`() {
        val ownWrites = relays("own", 12)
        val lookup = mapOf(a to relays("a", 9), b to relays("b", 9), c to relays("c", 9))
        assertEquals(ownWrites + listOf("a1", "b1", "c1", "a2", "b2", "c2", "a3", "b3").map { "wss://$it.example" },
            recipientPublishRelays(ownWrites, own, tags(a, b, c), { lookup[it].orEmpty() }, emptySet()))
    }

    @Test fun `single recipient adds at most four distinct relays`() {
        assertEquals(listOf("wss://own.example") + relays("a", 4),
            recipientPublishRelays(listOf("wss://own.example"), own, tags(a), { relays("a", 10) }, emptySet()))
    }

    @Test fun `self duplicates and invalid p tags never trigger extra metadata lookups`() {
        val lookedUp = mutableListOf<String>()
        val signedTags = tags(own, a, a.uppercase(), "g".repeat(64), "too-short") +
            listOf(listOf("p"), listOf("P", b), listOf("e", c), listOf("p", " $b"))
        val result = recipientPublishRelays(listOf("own.example"), own, signedTags,
            { lookedUp += it; listOf("inbox.example/") }, emptySet())
        assertEquals(listOf(a), lookedUp)
        assertEquals(listOf("wss://own.example", "wss://inbox.example"), result)
    }

    @Test fun `normalization blocked relays and duplicates apply to every target`() {
        val result = recipientPublishRelays(listOf("own.example/", "wss://own.example", "blocked.example"), own,
            tags(a, b), { listOf("wss://blocked.example/", "ws://clear.example", "not a relay", "own.example", "shared.example/", "wss://shared.example", "unique.example") },
            setOf("wss://blocked.example/"))
        assertEquals(listOf("wss://own.example", "wss://shared.example", "wss://unique.example"), result)
    }

    @Test fun `shared first choice does not starve a later recipient unique inbox`() {
        val lookup = mapOf(a to listOf("shared.example", "a.example"), b to listOf("shared.example", "b.example"))
        assertEquals(listOf("wss://own.example", "wss://shared.example", "wss://b.example", "wss://a.example"),
            recipientPublishRelays(listOf("own.example"), own, tags(a,b), { lookup[it].orEmpty() }, emptySet()))
    }

    @Test fun `missing cached metadata does not use p tag hints or recipient defaults`() {
        val result = recipientPublishRelays(listOf("own.example"), own,
            listOf(listOf("p", a, "wss://hint.example")), { emptyList() }, emptySet())
        assertEquals(listOf("wss://own.example"), result)
    }

    @Test fun `unresolved compose readiness cannot be bypassed by recipient cache`() {
        val result = resolveComposePublishRelays(emptyList(), false, listOf("fallback.example"), own,
            tags(a), { error("must not consult recipients before own readiness") })
        assertTrue(result.isEmpty())
    }

    @Test fun `settled empty compose keeps own fallback and then adds cached recipient inbox`() {
        assertEquals(listOf("wss://fallback.example", "wss://inbox.example"),
            resolveComposePublishRelays(emptyList(), true, listOf("fallback.example"), own,
                tags(a), { listOf("inbox.example") }))
    }

    @Test fun `known own writes remain usable during bounded readiness timeout`() {
        assertEquals(listOf("wss://own.example", "wss://inbox.example"),
            resolveComposePublishRelays(listOf("own.example"), false, listOf("fallback.example"), own,
                tags(a), { listOf("inbox.example") }))
    }
}
