package com.unsilence.app.data.relay

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guard tests for [normalizeRelayUrl]. The tab-crash input is the exact
 * regression lock for the FATAL EXCEPTION on profile open (malformed
 * relay URL "nostr.wine\twss" reached okhttp's URL builder).
 */
class NormalizeRelayUrlTest {

    // ── Crash regression: embedded tab ────────────────────────────────

    @Test
    fun `rejects url with embedded tab - the crash input`() {
        assertNull(normalizeRelayUrl("wss://nostr.wine\twss"))
    }

    @Test
    fun `rejects bare host with embedded tab`() {
        assertNull(normalizeRelayUrl("nostr.wine\twss"))
    }

    // ── Internal whitespace and control chars ─────────────────────────

    @Test
    fun `rejects url with internal space`() {
        assertNull(normalizeRelayUrl("wss://nostr .wine"))
    }

    @Test
    fun `rejects url with internal newline`() {
        assertNull(normalizeRelayUrl("wss://nostr\nwine"))
    }

    @Test
    fun `rejects url with internal carriage return`() {
        assertNull(normalizeRelayUrl("wss://relay\r.damus.io"))
    }

    @Test
    fun `rejects url with null byte`() {
        assertNull(normalizeRelayUrl("wss://relay.damus.io\u0000"))
    }

    @Test
    fun `rejects percent encoded space joining multiple relay urls`() {
        assertNull(normalizeRelayUrl("wss://nos.lol/%20wss://relay.damus.io/%20wss://nostr.wine"))
    }

    @Test
    fun `rejects percent encoded control character`() {
        assertNull(normalizeRelayUrl("wss://relay.damus.io/%0a"))
    }

    @Test
    fun `rejects empty user info before host`() {
        assertNull(normalizeRelayUrl("wss://@nos.lol"))
    }

    // ── Valid URLs still pass ─────────────────────────────────────────

    @Test
    fun `accepts valid wss relay`() {
        assertNotNull(normalizeRelayUrl("wss://nostr.wine"))
    }

    @Test
    fun `accepts valid relay with trailing slash`() {
        assertNotNull(normalizeRelayUrl("wss://relay.damus.io/"))
    }

    @Test
    fun `accepts valid relay subpath`() {
        assertNotNull(normalizeRelayUrl("wss://relay.example.com/tenant"))
    }

    @Test
    fun `accepts bare host and prepends wss`() {
        val result = normalizeRelayUrl("relay.damus.io")
        assertNotNull(result)
        assert(result!!.startsWith("wss://"))
    }

    @Test
    fun `trims surrounding whitespace before validation`() {
        // Leading/trailing whitespace is trimmed — not internal, so it passes
        assertNotNull(normalizeRelayUrl("  wss://nostr.wine  "))
    }

    // ── Existing rejections still work ────────────────────────────────

    @Test
    fun `rejects blank input`() {
        assertNull(normalizeRelayUrl(""))
        assertNull(normalizeRelayUrl("   "))
    }

    @Test
    fun `rejects cleartext ws`() {
        assertNull(normalizeRelayUrl("ws://nostr.wine"))
    }

    @Test
    fun `rejects host without dot`() {
        assertNull(normalizeRelayUrl("wss://localhost"))
    }
}
