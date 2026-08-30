package com.unsilence.app.ui.profile

import com.unsilence.app.data.memory.MuteList
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

    @Test
    fun `profile timeline hides events covered by the existing mute policy`() {
        val mutedAuthor = event(kind = 1, pubkey = "muted-author")
        val mutedWord = event(kind = 1, content = "A blocked phrase appears here")
        val visible = event(kind = 1, id = "visible", content = "ordinary note")
        val muteList = MuteList(
            pubkeys = linkedSetOf("muted-author"),
            hashtags = linkedSetOf(),
            words = linkedSetOf("blocked phrase"),
            eventIds = linkedSetOf(),
        )

        assertFalse(
            isVisibleProfileTimelineEvent(
                mutedAuthor,
                FeedContentFilter.NOTES_ONLY,
                muteList,
                eventProvider = { null },
            ),
        )
        assertFalse(
            isVisibleProfileTimelineEvent(
                mutedWord,
                FeedContentFilter.NOTES_ONLY,
                muteList,
                eventProvider = { null },
            ),
        )
        assertTrue(
            isVisibleProfileTimelineEvent(
                visible,
                FeedContentFilter.NOTES_ONLY,
                muteList,
                eventProvider = { null },
            ),
        )
    }

    private fun event(
        kind: Int,
        id: String = "event-$kind",
        pubkey: String = "author",
        content: String = "content",
        tags: List<List<String>> = emptyList(),
        replyToId: String? = null,
        rootId: String? = null,
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        createdAt = 1L,
        tags = tags,

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
