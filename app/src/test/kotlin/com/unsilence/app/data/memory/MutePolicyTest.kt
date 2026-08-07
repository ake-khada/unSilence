package com.unsilence.app.data.memory

import com.unsilence.app.data.model.RepostInfo
import com.unsilence.app.data.model.RepostPayload
import com.unsilence.app.data.model.VerifiedRepostEvent
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
    fun `reference repost never treats protocol json as user visible text`() {
        val muteList = muteList(words = setOf("spam"))
        val wrapper = event(
            kind = 6,
            content = """{"id":"${"a".repeat(64)}","content":"spam"}""",
            repostInfo = referenceRepost("a".repeat(64)),
        )

        assertFalse(isMuted(wrapper, muteList))
    }

    @Test
    fun `verified embedded repost applies word hashtag author and event mutes`() {
        val targetId = "a".repeat(64)
        val targetPubkey = "b".repeat(64)
        val wrapper = event(
            kind = 6,
            repostInfo = verifiedRepost(
                id = targetId,
                pubkey = targetPubkey,
                content = "Fresh spam here",
                tags = listOf(listOf("t", "Nostr")),
            ),
        )

        assertTrue(isMuted(wrapper, muteList(words = setOf("spam"))))
        assertTrue(isMuted(wrapper, muteList(hashtags = setOf("nostr"))))
        assertTrue(isMuted(wrapper, muteList(pubkeys = setOf(targetPubkey))))
        assertTrue(isMuted(wrapper, muteList(eventIds = setOf(targetId))))
    }

    @Test
    fun `reference repost applies moderation to independently resolved target`() {
        val targetId = "c".repeat(64)
        val target = event(
            id = targetId,
            pubkey = "d".repeat(64),
            content = "resolved spam",
        )
        val wrapper = event(
            kind = 16,
            content = """{"content":"not trusted"}""",
            repostInfo = referenceRepost(targetId),
        )

        assertTrue(isMuted(wrapper, muteList(words = setOf("spam"))) { id ->
            target.takeIf { id == targetId }
        })
    }

    @Test
    fun `unresolved reference can be muted by target id without inspecting envelope`() {
        val targetId = "e".repeat(64)
        val wrapper = event(
            kind = 16,
            content = """{"content":"spam"}""",
            repostInfo = referenceRepost(targetId),
        )

        assertTrue(isMuted(wrapper, muteList(eventIds = setOf(targetId))))
        assertFalse(isMuted(wrapper, muteList(words = setOf("spam"))))
    }

    @Test
    fun `flattened reference row honors target id mute but ignores envelope fields`() {
        val targetId = "9".repeat(64)
        val wrapper = row(
            kind = 6,
            content = """{"content":"spam"}""",
            tags = """[["t","nostr"]]""",
            rootId = targetId,
        )

        assertTrue(isMuted(wrapper, muteList(eventIds = setOf(targetId))))
        assertFalse(isMuted(wrapper, muteList(words = setOf("spam"))))
        assertFalse(isMuted(wrapper, muteList(hashtags = setOf("nostr"))))
        assertFalse(exceedsHashtagCap(wrapper, cap = 0))
    }

    @Test
    fun `muting the reposter still hides a reference repost`() {
        val wrapper = event(
            pubkey = "f".repeat(64),
            kind = 16,
            repostInfo = referenceRepost("a".repeat(64)),
        )

        assertTrue(isMuted(wrapper, muteList(pubkeys = setOf(wrapper.pubkey))))
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
    fun `repost wrappers ignore envelope hashtags and cap verified target hashtags`() {
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
        assertFalse(exceedsHashtagCap(event(kind = 16, content = stuffedContent, tags = stuffedTags), cap = 5))
        assertTrue(
            exceedsHashtagCap(
                event(
                    kind = 16,
                    content = "wrapper",
                    tags = stuffedTags,
                    repostInfo = verifiedRepost(content = stuffedContent),
                ),
                cap = 5,
            ),
        )
    }

    @Test
    fun `reference repost hashtag cap uses resolved target for kinds 6 and 16`() {
        val targetId = "1".repeat(64)
        val target = event(id = targetId, content = "#a #b #c #d #e #f")

        listOf(6, 16).forEach { repostKind ->
            val wrapper = event(kind = repostKind, repostInfo = referenceRepost(targetId))
            assertTrue(exceedsHashtagCap(wrapper, cap = 5) { id -> target.takeIf { id == targetId } })
        }
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
        repostInfo: RepostInfo? = null,
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
        repostInfo = repostInfo,
    )

    private fun referenceRepost(targetId: String): RepostInfo = RepostInfo(
        targetId = targetId,
        relayHint = null,
        addressCoordinate = null,
        addressRelayHint = null,
        targetAuthorHint = null,
        proxyUrl = null,
        payload = RepostPayload.ReferenceOnly,
    )

    private fun verifiedRepost(
        id: String = "2".repeat(64),
        pubkey: String = "3".repeat(64),
        content: String = "",
        tags: List<List<String>> = emptyList(),
    ): RepostInfo = RepostInfo(
        targetId = id,
        relayHint = null,
        addressCoordinate = null,
        addressRelayHint = null,
        targetAuthorHint = pubkey,
        proxyUrl = null,
        payload = RepostPayload.VerifiedEmbedded(
            VerifiedRepostEvent(
                id = id,
                pubkey = pubkey,
                kind = 1,
                content = content,
                createdAt = 1L,
                tags = tags,
            ),
        ),
    )

    private fun row(
        id: String = "id",
        pubkey: String = "pubkey",
        kind: Int = 1,
        content: String = "",
        tags: String = "[]",
        rootId: String? = null,
    ): FeedRow = FeedRow(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        createdAt = 1L,
        tags = tags,
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = rootId,
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
