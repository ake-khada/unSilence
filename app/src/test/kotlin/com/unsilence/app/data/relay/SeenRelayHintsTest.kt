package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.*

class SeenRelayHintsTest {

    @Test
    fun `profile request chunks preserve kinds unique ids and every author`() {
        val authors = (1..201).map { it.toString(16).padStart(64, '0') }
        for (kinds in listOf(listOf(0), listOf(0, 10002))) {
            val requests = profileRequestPayloads(authors + authors.first(), "hint-profiles-fixture", kinds)
            val frames = requests.values.map { NostrJson.parseToJsonElement(it).jsonArray }
            assertEquals(listOf(100, 100, 1), frames.map { it[2].jsonObject.getValue("authors").jsonArray.size })
            assertEquals(requests.keys.toList(), frames.map { it[1].jsonPrimitive.content })
            assertEquals(authors, frames.flatMap { it[2].jsonObject.getValue("authors").jsonArray.map { author -> author.jsonPrimitive.content } })
            frames.forEach { frame ->
                assertEquals("REQ", frame[0].jsonPrimitive.content)
                assertEquals(setOf("kinds", "authors"), frame[2].jsonObject.keys)
                assertEquals(kinds, frame[2].jsonObject.getValue("kinds").jsonArray.map { it.jsonPrimitive.int })
            }
        }
        assertTrue(profileRequestPayloads(emptyList(), "empty", listOf(0)).isEmpty())
    }

    @Test
    fun `per relay profile grouping retains normalized three hint bound`() {
        val hints = listOf("wss://one.example/", "one.example", "wss://two.example", "wss://three.example", "wss://four.example")
        val groups = groupProfileHintFetches(listOf("a", "b"), mapOf("a" to hints, "b" to listOf("wss://two.example/")))
        assertEquals(setOf("wss://one.example", "wss://two.example", "wss://three.example"), groups.keys.flatten().toSet())
        assertEquals(listOf("a", "b"), groups[listOf("wss://two.example")])
        assertEquals(4, groups.values.sumOf { it.size })
    }

    @Test
    fun `overlapping profile hints make one group per relay with exact author coverage`() {
        val hints = mapOf(
            "a" to listOf("wss://shared.example/", "wss://a.example"),
            "b" to listOf("shared.example", "wss://b.example"),
            "c" to listOf("wss://shared.example"),
        )
        val groups = groupProfileHintFetches(listOf("a", "b", "c", "a", "hintless"), hints)
        val pairs = groups.flatMap { (urls, authors) -> urls.flatMap { url -> authors.map { it to url } } }
        val expected = hints.flatMap { (author, urls) -> canonicalRelayHints(urls).map { author to it } }.toSet()
        assertEquals(expected, pairs.toSet())
        assertEquals("each author-relay pair appears exactly once", expected.size, pairs.size)
        assertEquals("shared relay gets one combined request", 1, groups.keys.count { "wss://shared.example" in it })
        assertEquals(listOf("a", "b", "c"), groups[listOf("wss://shared.example")])
        assertEquals(3, groups.keys.sumOf { it.size })
    }

    @Test
    fun `seen hints are normalized deduped and capped while reserving browse locality`() {
        val targets = relayResolutionTargets(
            seenRelays = listOf(
                "wss://first.example/",
                "first.example",
                "wss://second.example",
                "wss://third.example",
                "wss://fourth.example",
            ),
            browseRelays = listOf("wss://relay.divine.video/"),
            additionalRelays = listOf("wss://tag.example"),
            fallbackRelays = listOf(
                "wss://first.example",
                "relay.divine.video",
                "wss://global.example/",
            ),
        )

        assertEquals(
            listOf(
                "wss://first.example",
                "wss://relay.divine.video",
                "wss://second.example",
            ),
            targets.hints,
        )
        assertEquals(listOf("wss://global.example"), targets.fallback)
        assertEquals(3, targets.hints.size)
    }

    @Test
    fun `empty hints leave fallback behavior unchanged`() {
        val targets = relayResolutionTargets(
            seenRelays = emptyList(),
            fallbackRelays = listOf("wss://one.example/", "one.example", "wss://two.example"),
        )

        assertTrue(targets.hints.isEmpty())
        assertEquals(listOf("wss://one.example", "wss://two.example"), targets.fallback)
    }

    @Test
    fun `locality one shots retain the active feed relay while ordinary fanout excludes it`() {
        val candidates = listOf(
            "relay.divine.video",
            "wss://relay.divine.video/",
            "wss://global.example/",
        )

        assertEquals(
            listOf("wss://global.example"),
            oneShotRelayTargets(
                relayUrls = candidates,
                activeSingleRelayFeedUrl = "wss://relay.divine.video",
                includeActiveFeedRelay = false,
            ),
        )
        assertEquals(
            listOf("wss://relay.divine.video", "wss://global.example"),
            oneShotRelayTargets(
                relayUrls = candidates,
                activeSingleRelayFeedUrl = "wss://relay.divine.video/",
                includeActiveFeedRelay = true,
            ),
        )
    }

    @Test
    fun `profile fetch groups retain each authors row hints`() {
        val groups = groupProfileHintFetches(
            pubkeys = listOf("divine-bot", "ordinary-author", "hintless"),
            relayHintsByPubkey = mapOf(
                "divine-bot" to listOf("wss://relay.divine.video/"),
                "ordinary-author" to listOf("wss://relay.example", "relay.example"),
            ),
        )

        assertEquals(
            listOf("divine-bot"),
            groups[listOf("wss://relay.divine.video")],
        )
        assertEquals(
            listOf("ordinary-author"),
            groups[listOf("wss://relay.example")],
        )
        assertTrue(groups.values.flatten().none { it == "hintless" })
    }

    @Test
    fun `bridge fallback is one normalized relay deduped against prior fanout`() {
        assertEquals(
            listOf("wss://relay.mostr.pub"),
            bridgeFallbackRelayTargets(
                listOf("wss://one.example/", "one.example"),
            ),
        )
        assertTrue(
            bridgeFallbackRelayTargets(
                listOf("relay.mostr.pub", "wss://relay.mostr.pub/"),
            ).isEmpty(),
        )
        assertTrue(BRIDGE_FALLBACK_RELAY_URLS.none { it in GLOBAL_RELAY_URLS })
    }

    @Test
    fun `bridge fallback identity is normalization safe`() {
        assertTrue(isBridgeFallbackRelay("relay.mostr.pub/"))
        assertFalse(isBridgeFallbackRelay("wss://relay.example"))
        assertFalse(isBridgeFallbackRelay("not a relay"))
    }

    @Test
    fun `mostr nip05 derives lookup outbox while ordinary nip05 does not`() {
        assertEquals(
            listOf("wss://relay.mostr.pub"),
            profileDerivedBridgeOutbox("  Alice@MOSTR.PUB  "),
        )
        assertTrue(profileDerivedBridgeOutbox("alice@example.com").isEmpty())
        assertTrue(profileDerivedBridgeOutbox("alice@sub.mostr.pub").isEmpty())
        assertTrue(profileDerivedBridgeOutbox(null).isEmpty())
    }
}
