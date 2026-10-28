package com.unsilence.app.data.relay

import com.unsilence.app.data.model.ContentParser
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production warm-up selection/dispatch stage with no network. */
class OgWarmPolicyTest {
    @Test fun rejectedFirstLinkWarmsTheLaterValidPreviewOnly() {
        val calls = mutableListOf<String>()
        val good = "https://example.com/guide?q=1#part"
        val counted = warmOgCandidate(model("https://0x7f.1/path then $good")) { calls += it; true }
        assertEquals(listOf(good), calls)
        assertTrue(counted)
    }

    @Test fun rejectedOnlyLinksNeverReachFetchAndNeverSpendBudget() {
        for (url in listOf("https://0x7f.1/path", "https://127.1/path", "https:///empty",
            "https://example.com:99999/path", "https://[invalid]/")) {
            val calls = mutableListOf<String>()
            val counted = warmOgCandidate(model(url)) { calls += it; true }
            assertEquals(url, emptyList<String>(), calls)
            assertFalse(url, counted)
        }
    }

    @Test fun noLinksDoNotFetchOrSpendBudget() {
        assertFalse(warmOgCandidate(model("Plain text only")) { error("Unexpected fetch: $it") })
    }

    @Test fun cachedOrAlreadyWarmedCandidateStillDoesNotSpendBudget() {
        val url = "https://example.com/guide"
        val calls = mutableListOf<String>()
        assertFalse(warmOgCandidate(model(url)) { calls += it; false })
        assertEquals(listOf(url), calls)
    }

    @Test fun acceptedFirstLinkKeepsExactUrlAndSinglePreviewCap() {
        val url = "https://аpple.com:8443/path?q=1#part"
        val calls = mutableListOf<String>()
        assertTrue(warmOgCandidate(model("$url then https://example.com/second")) { calls += it; true })
        assertEquals(listOf(url), calls)
    }

    private fun model(content: String) = ContentParser.parse(
        id = "a".repeat(64), pubkey = "b".repeat(64), kind = 1,
        content = content, tagsJson = "[]", createdAt = 1L, relayUrl = "",
        replyToId = null, rootId = null, hasContentWarning = false, contentWarningReason = null,
    )
}
