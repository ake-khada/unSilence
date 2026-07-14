package com.unsilence.app.ui.profile

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.ui.feed.FeedContentFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileContentPolicyTest {

    @Test
    fun `note and reply tabs subscribe to NIP-22 comments`() {
        assertTrue(1111 in profileKindsForTab(ProfileTab.NOTES))
        assertTrue(1111 in profileKindsForTab(ProfileTab.REPLIES))
        assertFalse(1111 in profileKindsForTab(ProfileTab.LONGFORM))
    }

    @Test
    fun `coordinate-only NIP-22 comment is a reply and never a note`() {
        val comment = event(kind = 1111, tags = listOf(listOf("A", "34236:author:clip")))

        assertTrue(matchesProfileContentFilter(comment, FeedContentFilter.REPLIES_ONLY))
        assertFalse(matchesProfileContentFilter(comment, FeedContentFilter.NOTES_ONLY))
    }

    @Test
    fun `ordinary roots replies and reposts retain their tab classification`() {
        val root = event(kind = 1)
        val reply = event(kind = 1, replyToId = "root", rootId = "root")
        val repost = event(kind = 16, replyToId = "root", rootId = "root")

        assertTrue(matchesProfileContentFilter(root, FeedContentFilter.NOTES_ONLY))
        assertTrue(matchesProfileContentFilter(reply, FeedContentFilter.REPLIES_ONLY))
        assertFalse(matchesProfileContentFilter(reply, FeedContentFilter.NOTES_ONLY))
        assertTrue(matchesProfileContentFilter(repost, FeedContentFilter.NOTES_ONLY))
        assertFalse(matchesProfileContentFilter(repost, FeedContentFilter.REPLIES_ONLY))
    }

    private fun event(
        kind: Int,
        tags: List<List<String>> = emptyList(),
        replyToId: String? = null,
        rootId: String? = null,
    ) = NostrEvent(
        id = "event-$kind",
        pubkey = "author",
        kind = kind,
        content = "content",
        createdAt = 1L,
        tags = tags,
        tagsJson = "[]",
        sig = "sig",
        relayUrl = "wss://relay.example",
        replyToId = replyToId,
        rootId = rootId,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = 1L,
        relaysSeen = mutableSetOf("wss://relay.example"),
    )
}
