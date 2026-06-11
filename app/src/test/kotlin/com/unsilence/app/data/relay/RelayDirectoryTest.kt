package com.unsilence.app.data.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 directory tests. Parser tests run against REAL captured kind-30166 fixtures
 * (relaymon_fixtures.jsonl: rich nostr.watch schema + light/bot schema). Pure functions
 * tested directly.
 */
class RelayDirectoryTest {

    // ── Fixture loading ───────────────────────────────────────────────────────

    private fun fixtures(): List<JsonObject> =
        javaClass.getResourceAsStream("/relaymon_fixtures.jsonl")!!
            .bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }   // skip provenance header
            .map { NostrJson.parseToJsonElement(it).jsonObject }

    private fun JsonObject.tagsList(): List<List<String>> =
        (this["tags"] as JsonArray).map { t -> (t as JsonArray).map { (it as JsonPrimitive).content } }

    private fun parse(ev: JsonObject): RelayDirectoryEntry? = RelayDirectory.parseMonitorEvent(
        tags = ev.tagsList(),
        content = (ev["content"] as JsonPrimitive).content,
        monitorPubkey = (ev["pubkey"] as JsonPrimitive).content,
        createdAt = (ev["created_at"] as JsonPrimitive).long,
    )

    private fun byUrl(host: String): RelayDirectoryEntry =
        fixtures().mapNotNull { parse(it) }.first { it.url.contains(host) }

    // ── Parser: rich nostr.watch schema ───────────────────────────────────────

    @Test
    fun `rich monitor event extracts url, nip11, rtt, nips, limitations`() {
        val e = byUrl("relay.libernet.app")
        assertEquals("wss://relay.libernet.app", e.url)          // trailing slash normalized away
        assertEquals("LiberNet Relay", e.name)
        assertTrue(e.description!!.contains("brasileiro"))
        assertEquals("https://media.libernet.app/static/img/logo-relay.jpg", e.icon)
        assertTrue(e.software!!.contains("strfry"))
        assertEquals("1.0.4", e.version)
        assertEquals(153, e.monitorRttMs)                        // rtt-open
        assertEquals(RelayNetwork.CLEARNET, e.network)           // `n` tag, not "network"
        assertTrue(e.supportedNips.containsAll(setOf(1, 2, 13, 77)))
        assertFalse("R says !auth", e.auth)
        assertFalse("R says !payment", e.payment)
        assertEquals(Nip11Source.MONITOR, e.nip11Source)
        assertEquals(setOf("9bbbb845e5b6c831c29789900769843ab43bb5047abe697870cb50b6fc9bf923"), e.monitorPubkeys)
    }

    @Test
    fun `sparse content with nulls parses without throw`() {
        val e = byUrl("search.nos.today")
        assertEquals(758, e.monitorRttMs)
        assertEquals("searchnos", e.name)
        // content has icon:null, pubkey:null, limitation:null — must not throw, fields null/false
        assertNull(e.icon)
        assertFalse(e.restrictedWrites)
        assertTrue(e.supportedNips.contains(50))
    }

    // ── Parser: light/bot schema (empty content, no rtt, no network) ───────────

    @Test
    fun `light monitor event - empty content, no rtt, no network - parses with nulls`() {
        val e = byUrl("wot.utxo.one")
        assertEquals("wss://wot.utxo.one", e.url)
        assertNull("no rtt-* tags", e.monitorRttMs)
        assertEquals("no n tag", RelayNetwork.UNKNOWN, e.network)
        assertNull("empty content → no NIP-11 name", e.name)
        assertNull(e.description)
        assertTrue(e.supportedNips.containsAll(setOf(9, 40, 45)))
        assertFalse(e.auth)
    }

    @Test
    fun `malformed content yields entry with null nip11, never throws`() {
        val entry = RelayDirectory.parseMonitorEvent(
            tags = listOf(listOf("d", "wss://broken.example/"), listOf("N", "1")),
            content = "{ this is not valid json ::",
            monitorPubkey = "deadbeef",
            createdAt = 123L,
        )
        assertEquals("wss://broken.example", entry!!.url)
        assertNull(entry.name)
        assertTrue(entry.supportedNips.contains(1))
    }

    @Test
    fun `R writes tag sets restrictedWrites, NIP-11 limitation wins when present (A2)`() {
        // R "writes" → restricted; "!writes" → not.
        val restricted = RelayDirectory.parseMonitorEvent(
            tags = listOf(listOf("d", "wss://wl.example/"), listOf("R", "writes")),
            content = "", monitorPubkey = "m", createdAt = 1L,
        )!!
        assertTrue(restricted.restrictedWrites)
        val open = RelayDirectory.parseMonitorEvent(
            tags = listOf(listOf("d", "wss://open.example/"), listOf("R", "!writes")),
            content = "", monitorPubkey = "m", createdAt = 1L,
        )!!
        assertFalse(open.restrictedWrites)
        // limitation.restricted_writes=false overrides an R "writes" tag.
        val limitationWins = RelayDirectory.parseMonitorEvent(
            tags = listOf(listOf("d", "wss://lim.example/"), listOf("R", "writes")),
            content = """{"limitation":{"restricted_writes":false}}""",
            monitorPubkey = "m", createdAt = 1L,
        )!!
        assertFalse("NIP-11 limitation wins over R tag", limitationWins.restrictedWrites)
    }

    @Test
    fun `missing d-tag yields null`() {
        val entry = RelayDirectory.parseMonitorEvent(
            tags = listOf(listOf("N", "1")), content = "", monitorPubkey = "x", createdAt = 1L,
        )
        assertNull(entry)
    }

    // ── computeReachability (device-empirical trump card) ──────────────────────

    @Test
    fun `DNS-dead store state yields DNS_BLOCKED`() {
        val r = RelayDirectory.computeReachability(
            hasCapsEntry = true, deadFailCount = DEAD_RELAY_THRESHOLD, authRequired = false,
            restricted = false, strikes = 0, consecutiveFailures = 5,
            lastReason = SkipReason.DNS_RESOLUTION.name, monitorRttMs = 100,
        )
        assertEquals(Reachability.DNS_BLOCKED, r)
    }

    @Test
    fun `auth-learned yields AUTH_WALL`() {
        val r = RelayDirectory.computeReachability(
            hasCapsEntry = true, deadFailCount = 0, authRequired = true, restricted = false,
            strikes = 0, consecutiveFailures = 0, lastReason = null, monitorRttMs = null,
        )
        assertEquals(Reachability.AUTH_WALL, r)
    }

    @Test
    fun `clean with monitor RTT yields REACHABLE`() {
        val r = RelayDirectory.computeReachability(
            hasCapsEntry = false, deadFailCount = 0, authRequired = false, restricted = false,
            strikes = 0, consecutiveFailures = 0, lastReason = null, monitorRttMs = 153,
        )
        assertEquals(Reachability.REACHABLE, r)
    }

    @Test
    fun `unknown relay yields UNKNOWN`() {
        val r = RelayDirectory.computeReachability(
            hasCapsEntry = false, deadFailCount = 0, authRequired = false, restricted = false,
            strikes = 0, consecutiveFailures = 0, lastReason = null, monitorRttMs = null,
        )
        assertEquals(Reachability.UNKNOWN, r)
    }

    // ── computeDirectoryScore (reachable-for-you beats globally-famous) ─────────

    @Test
    fun `reachable unknown relay outranks DNS-blocked famous relay`() {
        val reachable = RelayDirectory.computeDirectoryScore(
            Reachability.REACHABLE, trustScore = null, monitorRttMs = null, popularity = 0,
        )
        val blockedFamous = RelayDirectory.computeDirectoryScore(
            Reachability.DNS_BLOCKED, trustScore = 100, monitorRttMs = 50, popularity = 500,
        )
        assertTrue("reachable ($reachable) must beat blocked-famous ($blockedFamous)", reachable > blockedFamous)
    }

    // ── mergeMonitorEntries (median RTT, union NIPs/pubkeys) ───────────────────

    // ── Bound: cap 3000, evict oldest, protect own-list relays ─────────────────

    @Test
    fun `enforceBound caps at 3000, evicts oldest monitorLastSeenAt, protects own-list relays`() {
        val entries = (1..3_500).associate { i ->
            "wss://relay$i.example" to RelayDirectoryEntry(url = "wss://relay$i.example", monitorLastSeenAt = i.toLong())
        }
        // relay1/relay2 are the OLDEST (lastSeen 1,2) — would normally evict first — but protected.
        val protectedUrls = setOf("wss://relay1.example", "wss://relay2.example")
        val bounded = RelayDirectory.enforceBound(entries, protectedUrls)
        assertTrue("size capped (was ${bounded.size})", bounded.size <= 3_000)
        assertTrue("own relay1 survives despite being oldest", bounded.containsKey("wss://relay1.example"))
        assertTrue("own relay2 survives despite being oldest", bounded.containsKey("wss://relay2.example"))
        assertTrue("newest kept", bounded.containsKey("wss://relay3500.example"))
        assertFalse("oldest non-protected evicted", bounded.containsKey("wss://relay3.example"))
    }

    @Test
    fun `merge takes median RTT and unions nips and monitors`() {
        val a = RelayDirectoryEntry(url = "wss://r.example", monitorRttMs = 100, supportedNips = setOf(1, 2), monitorPubkeys = setOf("m1"), monitorLastSeenAt = 10)
        val b = RelayDirectoryEntry(url = "wss://r.example", monitorRttMs = 200, supportedNips = setOf(2, 9), monitorPubkeys = setOf("m2"), monitorLastSeenAt = 30, name = "R")
        val c = RelayDirectoryEntry(url = "wss://r.example", monitorRttMs = 300, supportedNips = setOf(40), monitorPubkeys = setOf("m3"), monitorLastSeenAt = 20)
        val merged = RelayDirectory.mergeMonitorEntries(listOf(a, b, c))
        assertEquals(200, merged.monitorRttMs)                   // median of [100,200,300]
        assertEquals(setOf(1, 2, 9, 40), merged.supportedNips)
        assertEquals(setOf("m1", "m2", "m3"), merged.monitorPubkeys)
        assertEquals(30L, merged.monitorLastSeenAt)              // newest
        assertEquals("R", merged.name)                           // richest (has name)
    }
}
