package com.unsilence.app.data.relay

import com.unsilence.app.data.model.RepostInfo
import com.unsilence.app.data.model.RepostPayload
import com.unsilence.app.data.model.VerifiedRepostEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [effectiveContentWarning] — NIP-36 repost-aware warning.
 * A kind-6/16 wrapper carries no content-warning tag of its own (it lives on the
 * embedded inner event), so the effective warning must OR the inner event in,
 * else a repost of sensitive content renders unflagged.
 */
class ContentWarningTest {

    private val cwTags = listOf(listOf("content-warning", "nsfw"))
    private val plainTags = listOf(listOf("t", "nostr"))

    private fun verifiedInner(tags: List<List<String>>): RepostInfo = RepostInfo(
        targetId = "e".repeat(64),
        relayHint = null,
        addressCoordinate = null,
        addressRelayHint = null,
        targetAuthorHint = "b".repeat(64),
        proxyUrl = null,
        payload = RepostPayload.VerifiedEmbedded(
            VerifiedRepostEvent("e".repeat(64), "b".repeat(64), 1, "x", 1L, tags),
        ),
    )

    @Test
    fun `non-repost passes through wrapper warning`() {
        val (has, reason) = effectiveContentWarning(cwTags, null)
        assertTrue(has); assertEquals("nsfw", reason)
    }

    @Test
    fun `non-repost without warning is not sensitive`() {
        val (has, reason) = effectiveContentWarning(plainTags, null)
        assertFalse(has); assertNull(reason)
    }

    @Test
    fun `kind-6 repost inherits inner content-warning`() {
        val (has, reason) = effectiveContentWarning(
            plainTags,
            verifiedInner(listOf(listOf("content-warning", "gore"))),
        )
        assertTrue(has); assertEquals("gore", reason)
    }

    @Test
    fun `kind-16 repost inherits inner content-warning`() {
        val (has, _) = effectiveContentWarning(
            plainTags,
            verifiedInner(listOf(listOf("content-warning", "nsfw"))),
        )
        assertTrue(has)
    }

    @Test
    fun `kind-6 repost of non-sensitive target stays not sensitive`() {
        val (has, reason) = effectiveContentWarning(
            plainTags,
            verifiedInner(listOf(listOf("t", "grownostr"))),
        )
        assertFalse(has); assertNull(reason)
    }

    @Test
    fun `wrapper warning wins on reason but inner still ORs`() {
        val (has, reason) = effectiveContentWarning(
            cwTags,
            verifiedInner(listOf(listOf("content-warning", "inner-reason"))),
        )
        assertTrue(has); assertEquals("nsfw", reason) // wrapper reason priority
    }

    @Test
    fun `reference-only repost cannot inject an inner warning`() {
        val reference = verifiedInner(listOf(listOf("content-warning", "forged"))).copy(
            payload = RepostPayload.ReferenceOnly,
        )
        val (has, reason) = effectiveContentWarning(plainTags, reference)
        assertFalse(has); assertNull(reason)
    }
}
