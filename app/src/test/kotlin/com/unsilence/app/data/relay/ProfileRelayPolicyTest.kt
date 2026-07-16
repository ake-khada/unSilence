package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.RelayConfig
import com.unsilence.app.data.memory.ProfileRelayFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRelayPolicyTest {

    @Test
    fun `nip65 markers normalize and duplicate permissions merge`() {
        val parsed = parseNip65RelayTags(
            listOf(
                listOf("r", "relay.example"),
                listOf("r", "wss://read.example/", "read"),
                listOf("r", "wss://write.example", "write"),
                listOf("r", "wss://split.example", "read"),
                listOf("r", "wss://split.example/", "write"),
                listOf("r", "wss://unknown.example", "future-marker"),
                listOf("p", "ignored"),
            ),
        )

        assertEquals(
            listOf(
                RelayConfig("wss://relay.example", null),
                RelayConfig("wss://read.example", "read"),
                RelayConfig("wss://write.example", "write"),
                RelayConfig("wss://split.example", null),
                RelayConfig("wss://unknown.example", null),
            ),
            parsed,
        )
    }

    @Test
    fun `relay parsers drop malformed URLs and handle empty lists`() {
        val tags = listOf(
            listOf("relay", "wss://good.example/"),
            listOf("relay", "wss://good.example"),
            listOf("relay", "ws://cleartext.example"),
            listOf("relay", "not-a-host"),
            listOf("r", "wss://wrong-tag.example"),
        )

        assertEquals(listOf("wss://good.example"), parseNip51RelayTags(tags))
        assertEquals(emptyList<String>(), parseNip51RelayTags(emptyList()))
        assertEquals(emptyList<RelayConfig>(), parseNip65RelayTags(emptyList()))
    }

    @Test
    fun `nip51 plain relay lists deduplicate normalized URLs`() {
        assertEquals(
            listOf("wss://pyramid.fiatjaf.com"),
            parseNip51RelayTags(
                listOf(
                    listOf("relay", "wss://pyramid.fiatjaf.com"),
                    listOf("relay", "wss://pyramid.fiatjaf.com/"),
                    listOf("relay", "pyramid.fiatjaf.com"),
                ),
            ),
        )
    }

    @Test
    fun `replaceable relay policy accepts only a newer event`() {
        assertTrue(shouldAcceptProfileRelayEvent(null, 100L))
        assertTrue(shouldAcceptProfileRelayEvent(100L, 101L))
        assertFalse(shouldAcceptProfileRelayEvent(100L, 100L))
        assertFalse(shouldAcceptProfileRelayEvent(100L, 99L))
    }

    @Test
    fun `relay count distinguishes unknown from published empty and deduplicates`() {
        val relays = listOf(
            RelayConfig("wss://one.example", "read"),
            RelayConfig("wss://one.example/", "write"),
            RelayConfig("wss://two.example", null),
            RelayConfig("invalid", null),
        )

        assertNull(deriveProfileRelayCount(relayListPublished = false, relays = relays))
        assertEquals(0, deriveProfileRelayCount(relayListPublished = true, relays = emptyList()))
        assertEquals(2, deriveProfileRelayCount(relayListPublished = true, relays = relays))
    }

    @Test
    fun `relay identity refresh is bounded and clock rollback stays fresh`() {
        val now = 10L * RELAY_IDENTITY_REFRESH_MS

        assertTrue(shouldRefreshRelayIdentity(null, now))
        assertFalse(shouldRefreshRelayIdentity(now - RELAY_IDENTITY_REFRESH_MS + 1, now))
        assertTrue(shouldRefreshRelayIdentity(now - RELAY_IDENTITY_REFRESH_MS, now))
        assertFalse(shouldRefreshRelayIdentity(now + 1, now))
    }

    @Test
    fun `relay identity prefetch normalizes deduplicates and caps untrusted lists`() {
        val facts = ProfileRelayFacts(
            relays = (0..RELAY_IDENTITY_PREFETCH_CAP).map {
                RelayConfig("wss://relay-$it.example/", null)
            },
            searchRelays = listOf("wss://relay-0.example", "invalid"),
            blockedRelays = listOf("wss://blocked.example"),
        )

        val urls = relayIdentityPrefetchUrls(facts)

        assertEquals(RELAY_IDENTITY_PREFETCH_CAP, urls.size)
        assertEquals("wss://relay-0.example", urls.first())
        assertEquals("wss://relay-99.example", urls.last())
    }

    @Test
    fun `same relay in three sections has three globally distinct row keys`() {
        val url = "wss://pyramid.fiatjaf.com"
        val entries = profileRelaySectionEntries(
            ProfileRelayFacts(
                relays = listOf(RelayConfig(url, "write"), RelayConfig("$url/", "write")),
                searchRelays = listOf(url, "$url/"),
                blockedRelays = listOf(url, "$url/"),
            ),
        )

        assertEquals(
            listOf("relays:$url", "search:$url", "blocked:$url"),
            entries.map(ProfileRelaySectionEntry::key),
        )
        assertEquals(3, entries.map(ProfileRelaySectionEntry::key).distinct().size)
    }
}
