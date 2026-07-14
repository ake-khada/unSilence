package com.unsilence.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkRouterTest {
    private val pubkey = "11".repeat(32)
    private val eventId = "22".repeat(32)
    private val relayHints = listOf("wss://relay.example")

    private val decoded = mapOf(
        "npub-payload" to DeepLinkTarget.Profile(pubkey),
        "nprofile-payload" to DeepLinkTarget.Profile(pubkey, relayHints),
        "note-payload" to DeepLinkTarget.Note(eventId),
        "nevent-payload" to DeepLinkTarget.Note(eventId, relayHints),
        "naddr-payload" to DeepLinkTarget.Address(34236, pubkey, "clip", relayHints),
    )

    private fun resolve(uri: String): DeepLinkTarget? =
        resolveDeepLink(uri) { payload -> decoded[payload] }

    @Test
    fun `resolver maps every navigable nip19 entity`() {
        assertEquals(DeepLinkTarget.Profile(pubkey), resolve("nostr:npub-payload"))
        assertEquals(
            DeepLinkTarget.Profile(pubkey, listOf("wss://relay.example")),
            resolve("nostr:nprofile-payload"),
        )
        assertEquals(DeepLinkTarget.Note(eventId), resolve("nostr:note-payload"))
        assertEquals(
            DeepLinkTarget.Note(eventId, listOf("wss://relay.example")),
            resolve("nostr:nevent-payload"),
        )
        assertEquals(
            DeepLinkTarget.Address(34236, pubkey, "clip", listOf("wss://relay.example")),
            resolve("nostr:naddr-payload"),
        )
    }

    @Test
    fun `resolver accepts uppercase scheme and rejects secrets and malformed input`() {
        assertEquals(DeepLinkTarget.Profile(pubkey), resolve("NOSTR:npub-payload"))
        assertNull(resolve("nostr:nsec-payload"))
        assertNull(resolve("nostr:not-bech32"))
        assertNull(resolve("nostr:"))
        assertNull(resolve(""))
        assertNull(resolve("https://example.com"))
    }

    @Test
    fun `pending target is a replaceable slot consumed exactly once`() {
        val router = DeepLinkRouter()
        val first = DeepLinkTarget.Note("first")
        val second = DeepLinkTarget.Note("second")

        router.submit(DeepLinkTarget.Note(eventId))
        val parsed = router.pendingTarget.value
        assertEquals(DeepLinkTarget.Note(eventId), parsed)
        assertTrue(router.consume(parsed!!))
        assertFalse(router.consume(parsed))
        assertNull(router.pendingTarget.value)

        // Directly exercise replacement with two valid external intents.
        router.submit(DeepLinkTarget.Note("33".repeat(32)))
        router.submit(DeepLinkTarget.Note("44".repeat(32)))
        assertEquals(DeepLinkTarget.Note("44".repeat(32)), router.pendingTarget.value)

        // Keep local values referenced so the intended slot model stays explicit.
        assertFalse(router.consume(first))
        assertFalse(router.consume(second))
    }

    @Test
    fun `malformed notification is also consumed once`() {
        val router = DeepLinkRouter()
        router.submit("nostr:garbage")

        assertTrue(router.pendingFailure.value)
        assertTrue(router.consumeFailure())
        assertFalse(router.consumeFailure())
    }
}
