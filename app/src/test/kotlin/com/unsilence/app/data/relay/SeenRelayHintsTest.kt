package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenRelayHintsTest {

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
}
