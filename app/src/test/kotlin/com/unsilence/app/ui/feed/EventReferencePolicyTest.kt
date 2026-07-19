package com.unsilence.app.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventReferencePolicyTest {

    @Test
    fun `reply wire tags retain direct id address author and relay hints`() {
        val reference = buildReplyParentReference(
            eventId = "video-revision-id",
            tagsJson = """[
                ["A","34236:video-author:clip","wss://relay.divine.video/"],
                ["P","video-author","wss://relay.divine.video"],
                ["a","34236:video-author:clip","wss://relay.divine.video"],
                ["e","video-revision-id","wss://comments.example/"],
                ["k","34236"],
                ["p","video-author","wss://relay.divine.video/"]
            ]""".trimIndent(),
            sourceRelay = "wss://nos.lol/",
        )

        assertEquals("video-revision-id", reference?.eventId)
        assertEquals("34236:video-author:clip", reference?.address?.coordinate)
        assertEquals("video-author", reference?.authorPubkey)
        assertEquals(
            listOf(
                "wss://nos.lol",
                "wss://relay.divine.video",
                "wss://comments.example",
            ),
            reference?.relayHints,
        )
    }

    @Test
    fun `id miss with coordinate advances to address lookup`() {
        val target = EventReferenceTarget(
            eventId = "stale-revision",
            address = EventAddressReference(34236, "author", "clip"),
            authorPubkey = "author",
            relayHints = emptyList(),
        )

        assertEquals(
            listOf(EventReferenceLookupStep.EVENT_ID, EventReferenceLookupStep.ADDRESS),
            eventReferenceLookupSteps(target),
        )
    }

    @Test
    fun `id-only reference preserves current behavior without address fetch`() {
        val target = EventReferenceTarget(
            eventId = "ordinary-note",
            address = null,
            authorPubkey = null,
            relayHints = emptyList(),
        )

        assertEquals(
            listOf(EventReferenceLookupStep.EVENT_ID),
            eventReferenceLookupSteps(target),
        )
    }

    @Test
    fun `coordinate-only comment remains resolvable`() {
        val reference = buildReplyParentReference(
            eventId = null,
            tagsJson = """[["A","34236:author:clip"],["P","author"]]""",
            sourceRelay = "wss://nos.lol",
        )

        assertEquals("34236:author:clip", reference?.address?.coordinate)
        assertEquals(
            listOf(EventReferenceLookupStep.ADDRESS),
            reference?.let(::eventReferenceLookupSteps),
        )
    }

    @Test
    fun `empty repost reuses id then address fallback contract`() {
        val reference = buildRepostTargetReference(
            eventId = "stale-revision",
            addressCoordinate = "34236:author:clip",
            authorPubkey = null,
            relayHints = listOf("wss://relay.divine.video/"),
        )

        assertEquals("author", reference?.authorPubkey)
        assertEquals(listOf("wss://relay.divine.video"), reference?.relayHints)
        assertEquals(
            listOf(EventReferenceLookupStep.EVENT_ID, EventReferenceLookupStep.ADDRESS),
            reference?.let(::eventReferenceLookupSteps),
        )

        val ladder = eventReferenceRelayLadder(
            target = requireNotNull(reference),
            browseRelayHints = listOf("wss://browse.example/"),
            idFallbackRelays = listOf(
                "wss://relay.divine.video",
                "wss://id-fallback.example",
            ),
            addressFallbackRelays = listOf(
                "wss://browse.example",
                "wss://address-fallback.example",
            ),
        )
        assertEquals(
            listOf(
                "wss://relay.divine.video",
                "wss://browse.example",
                "wss://id-fallback.example",
            ),
            ladder.eventId?.all,
        )
        assertEquals(
            listOf(
                "wss://relay.divine.video",
                "wss://browse.example",
                "wss://address-fallback.example",
            ),
            ladder.address?.all,
        )
        assertEquals(listOf("wss://relay.mostr.pub"), ladder.eventIdBridgeFallback)
        assertEquals(listOf("wss://relay.mostr.pub"), ladder.addressBridgeFallback)
        assertEquals(
            listOf(
                EventReferenceRelayPhase.EVENT_ID_STANDARD,
                EventReferenceRelayPhase.ADDRESS_STANDARD,
                EventReferenceRelayPhase.EVENT_ID_BRIDGE,
                EventReferenceRelayPhase.ADDRESS_BRIDGE,
            ),
            ladder.orderedPhases(),
        )
    }

    @Test
    fun `bridge fetched row retains mostr locality for parent id and coordinate phases`() {
        val reference = buildReplyParentReference(
            eventId = "bridged-parent-id",
            tagsJson = """[["A","34236:bridged-author:post"],["P","bridged-author"]]""",
            sourceRelay = "wss://relay.mostr.pub/",
            sourceRelayHints = listOf("relay.mostr.pub"),
        )
        val ladder = eventReferenceRelayLadder(
            target = requireNotNull(reference),
            browseRelayHints = listOf("wss://browse.example"),
            idFallbackRelays = listOf("wss://global.example"),
            addressFallbackRelays = listOf("wss://indexer.example"),
        )

        assertEquals(
            listOf("wss://relay.mostr.pub", "wss://browse.example"),
            ladder.eventId?.hints,
        )
        assertEquals(ladder.eventId?.hints, ladder.address?.hints)
        // The bridge was already queried first as row locality, so the miss tier
        // must not send a duplicate request to the same relay.
        assertEquals(emptyList<String>(), ladder.eventIdBridgeFallback)
        assertEquals(emptyList<String>(), ladder.addressBridgeFallback)
        assertEquals(
            listOf(
                EventReferenceRelayPhase.EVENT_ID_STANDARD,
                EventReferenceRelayPhase.ADDRESS_STANDARD,
            ),
            ladder.orderedPhases(),
        )
    }

    @Test
    fun `malformed reference has no fallback target`() {
        assertNull(
            buildRepostTargetReference(
                eventId = null,
                addressCoordinate = "34236:no-separator",
                authorPubkey = null,
                relayHints = emptyList(),
            )
        )
    }
}
