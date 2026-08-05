package com.unsilence.app.data.network

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip05ResolutionPolicyTest {
    private val pubkey = "ab".repeat(32)

    @Test
    fun `exact and case-mismatched hex mappings verify`() {
        val target = requireNotNull(nip05LookupTarget("alice@example.com"))
        assertEquals(
            Nip05VerificationStatus.VERIFIED,
            nip05StatusFromHttpResponse(200, namesJson("alice", pubkey), target, pubkey),
        )
        assertEquals(
            Nip05VerificationStatus.VERIFIED,
            nip05StatusFromHttpResponse(200, namesJson("alice", pubkey.uppercase()), target, pubkey),
        )
    }

    @Test
    fun `wrong absent malformed and non-200 responses do not verify`() {
        val target = requireNotNull(nip05LookupTarget("alice@example.com"))
        val cases = listOf(
            nip05StatusFromHttpResponse(200, namesJson("alice", "cd".repeat(32)), target, pubkey),
            nip05StatusFromHttpResponse(200, namesJson("bob", pubkey), target, pubkey),
            nip05StatusFromHttpResponse(200, "not json", target, pubkey),
            nip05StatusFromHttpResponse(404, namesJson("alice", pubkey), target, pubkey),
        )
        assertTrue(cases.all { it == Nip05VerificationStatus.UNVERIFIED })
    }

    @Test
    fun `root identifier requests underscore and keeps the bare domain`() {
        val target = requireNotNull(nip05LookupTarget("example.com"))
        assertEquals("_", target.local)
        assertEquals("example.com", target.domain)
        assertEquals("_", target.url.queryParameter("name"))
        assertEquals("https://example.com/.well-known/nostr.json?name=_", target.url.toString())
        assertEquals(
            Nip05VerificationStatus.VERIFIED,
            nip05StatusFromHttpResponse(200, namesJson("_", pubkey), target, pubkey),
        )
    }

    @Test
    fun `cache validation rejects changed mapping expiry and malformed entries`() {
        val now = 1_800_000_000_000L
        val key = requireNotNull(nip05VerificationCacheKey(pubkey, "alice@example.com"))
        val verified = Nip05VerificationCacheEntry(
            key = key,
            status = Nip05VerificationStatus.VERIFIED,
            checkedAtMs = now,
            resolvedPubkey = pubkey.uppercase(),
        )
        assertTrue(verified.isValidAt(now))
        assertFalse(verified.copy(resolvedPubkey = "cd".repeat(32)).isValidAt(now))
        assertFalse(verified.copy(checkedAtMs = now - NIP05_POSITIVE_TTL_MS).isValidAt(now))
        assertFalse(verified.copy(status = Nip05VerificationStatus.UNKNOWN).isValidAt(now))
    }

    @Test
    fun `bounded reader rejects an oversized response`() {
        assertEquals("1234", readBoundedUtf8(Buffer().writeUtf8("1234"), 4))
        assertNull(readBoundedUtf8(Buffer().writeUtf8("12345"), 4))
    }

    private fun namesJson(name: String, mappedPubkey: String): String =
        """{"names":{"$name":"$mappedPubkey"}}"""
}
