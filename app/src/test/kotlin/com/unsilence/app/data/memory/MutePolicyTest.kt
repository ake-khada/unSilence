package com.unsilence.app.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MutePolicyTest {
    @Test
    fun `word mute matches normal note content`() {
        val muteList = muteList(words = setOf("spam"))

        assertTrue(isMuted(event(content = "Fresh spam here"), muteList))
    }

    @Test
    fun `hashtag mute matches t tags for events and rows`() {
        val muteList = muteList(hashtags = setOf("nostr"))

        assertTrue(isMuted(event(tags = listOf(listOf("t", "Nostr"))), muteList))
        assertTrue(isMuted(row(tags = """[["t","Nostr"]]"""), muteList))
    }

    @Test
    fun `hashtag mute preserves old raw lowercase matching for numeric tags`() {
        val muteList = muteList(hashtags = setOf("1"))

        assertTrue(isMuted(event(tags = listOf(listOf("t", "1"))), muteList))
    }

    @Test
    fun `kind 6 skips word mute because content is an envelope`() {
        val muteList = muteList(words = setOf("spam"))

        assertFalse(isMuted(event(kind = 6, content = """{"content":"spam"}"""), muteList))
    }

    @Test
    fun `hashtag count uses distinct t tags`() {
        val tags = listOf(
            listOf("t", "a"),
            listOf("t", "a"),
            listOf("t", "b"),
            listOf("t", "c"),
            listOf("t", "d"),
            listOf("t", "e"),
            listOf("t", "f"),
        )

        assertEquals(6, hashtagCount(tags, content = "plain"))
        assertTrue(exceedsHashtagCap(event(tags = tags), cap = 5))
    }

    @Test
    fun `hashtag count uses content hashtags and ignores numeric fragments`() {
        val content = "#a #b #c #d #e #f #1 https://example.com/path#fragment"

        assertEquals(6, hashtagCount(emptyList(), content))
        assertTrue(exceedsHashtagCap(event(content = content), cap = 5))
    }

    @Test
    fun `hashtag count takes the max of tag and content counts`() {
        val tags = listOf(listOf("t", "a"), listOf("t", "b"), listOf("t", "c"))
        val content = "#a #b #c #d #e #f"

        assertEquals(6, hashtagCount(tags, content))
    }

    @Test
    fun `hashtag cap boundary is strictly greater than threshold`() {
        val content = "#a #b #c #d #e"

        assertEquals(DEFAULT_HASHTAG_CAP, hashtagCount(emptyList(), content))
        assertFalse(exceedsHashtagCap(event(content = content), cap = DEFAULT_HASHTAG_CAP))
    }

    @Test
    fun `repost wrappers skip content hashtags but still check t tags`() {
        val stuffedContent = "#a #b #c #d #e #f"
        val stuffedTags = listOf(
            listOf("t", "a"),
            listOf("t", "b"),
            listOf("t", "c"),
            listOf("t", "d"),
            listOf("t", "e"),
            listOf("t", "f"),
        )

        assertFalse(exceedsHashtagCap(event(kind = 16, content = stuffedContent), cap = 5))
        assertTrue(exceedsHashtagCap(event(kind = 16, content = stuffedContent, tags = stuffedTags), cap = 5))
    }

    private fun muteList(
        pubkeys: Set<String> = emptySet(),
        hashtags: Set<String> = emptySet(),
        words: Set<String> = emptySet(),
        eventIds: Set<String> = emptySet(),
    ): MuteList = MuteList(
        pubkeys = pubkeys,
        hashtags = hashtags,
        words = words,
        eventIds = eventIds,
    )

    private fun event(
        id: String = "id",
        pubkey: String = "pubkey",
        kind: Int = 1,
        content: String = "",
        tags: List<List<String>> = emptyList(),
    ): NostrEvent = NostrEvent(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        createdAt = 1L,
        tags = tags,

        sig = "sig",
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = 1L,
        relaysSeen = mutableSetOf(),
    )

    private fun row(
        id: String = "id",
        pubkey: String = "pubkey",
        kind: Int = 1,
        content: String = "",
        tags: String = "[]",
    ): FeedRow = FeedRow(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        createdAt = 1L,
        tags = tags,
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        zapTotalSats = 0L,
        authorName = null,
        authorDisplayName = null,
        authorPicture = null,
        authorNip05 = null,
        reactionCount = 0,
        replyCount = 0,
        repostCount = 0,
        zapCount = 0,
    )
}
