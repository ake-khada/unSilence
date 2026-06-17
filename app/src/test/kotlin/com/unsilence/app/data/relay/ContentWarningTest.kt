package com.unsilence.app.data.relay

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

    @Test
    fun `non-repost passes through wrapper warning`() {
        val (has, reason) = effectiveContentWarning(1, "hi", cwTags)
        assertTrue(has); assertEquals("nsfw", reason)
    }

    @Test
    fun `non-repost without warning is not sensitive`() {
        val (has, reason) = effectiveContentWarning(1, "hi", plainTags)
        assertFalse(has); assertNull(reason)
    }

    @Test
    fun `kind-6 repost inherits inner content-warning`() {
        val inner = """{"kind":1,"content":"x","tags":[["content-warning","gore"]]}"""
        val (has, reason) = effectiveContentWarning(6, inner, plainTags)
        assertTrue(has); assertEquals("gore", reason)
    }

    @Test
    fun `kind-16 repost inherits inner content-warning`() {
        val inner = """{"kind":21,"content":"","tags":[["content-warning","nsfw"]]}"""
        val (has, _) = effectiveContentWarning(16, inner, plainTags)
        assertTrue(has)
    }

    @Test
    fun `kind-6 repost of non-sensitive target stays not sensitive`() {
        val inner = """{"kind":1,"content":"hello","tags":[["t","grownostr"]]}"""
        val (has, reason) = effectiveContentWarning(6, inner, plainTags)
        assertFalse(has); assertNull(reason)
    }

    @Test
    fun `wrapper warning wins on reason but inner still ORs`() {
        val inner = """{"kind":1,"content":"x","tags":[["content-warning","inner-reason"]]}"""
        val (has, reason) = effectiveContentWarning(6, inner, cwTags)
        assertTrue(has); assertEquals("nsfw", reason) // wrapper reason priority
    }

    @Test
    fun `malformed embedded json falls back to wrapper`() {
        val (has, reason) = effectiveContentWarning(6, "not json", plainTags)
        assertFalse(has); assertNull(reason)
    }
}
