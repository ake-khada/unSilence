package com.unsilence.app.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposePublishPayloadTest {

    @Test
    fun `plain preview payload is the published template`() {
        assertGolden(
            state = state(blocks = listOf(PublishBlock.Text("hello"))),
            expected = PublishPayload(CREATED_AT, 1, "hello", emptyList()),
        )
    }

    @Test
    fun `reply preview payload is the published template`() {
        val parentPubkey = "a".repeat(64)
        val state = state(
            blocks = listOf(PublishBlock.Text("reply")),
            target = PublishTarget.Reply(
                rootEventId = "root-id",
                parentEventId = "parent-id",
                parentPubkey = parentPubkey,
            ),
            activeNotifyPubkeys = setOf(parentPubkey),
        )
        val expected = PublishPayload(
            createdAt = CREATED_AT,
            kind = 1,
            content = "reply",
            tags = listOf(
                listOf("e", "root-id", "", "root"),
                listOf("e", "parent-id", "", "reply"),
                listOf("p", parentPubkey),
            ),
            replyToId = "parent-id",
            rootId = "root-id",
        )

        assertGolden(state, expected)
        assertEquals("parent-id", expected.threadedReplyTargetId())
    }

    @Test
    fun `quote and media preview payload is the published template`() {
        val author = "b".repeat(64)
        val media = PublishMedia(
            url = "https://media.example/video.mp4",
            mimeType = "video/mp4",
            sha256 = "deadbeef",
            sizeBytes = 4_200_000,
            width = 1080,
            height = 1920,
            blurhash = "blur",
            thumbnailUrl = "https://media.example/poster.webp",
            durationMs = 3_400,
        )
        val quote = PublishQuote(
            eventId = "quoted-id",
            authorPubkey = author,
            relayHint = "wss://relay.example",
            inlineReference = "nostr:nevent1fixture",
        )
        val state = state(
            blocks = listOf(
                PublishBlock.Text("watch this"),
                PublishBlock.Media(media),
            ),
            target = PublishTarget.Note(quote),
            activeNotifyPubkeys = setOf(author),
        )
        val expected = PublishPayload(
            createdAt = CREATED_AT,
            kind = 1,
            content = "watch this\n\n${media.url}\n\n${quote.inlineReference}",
            tags = listOf(
                listOf(
                    "imeta",
                    "url ${media.url}",
                    "m video/mp4",
                    "x deadbeef",
                    "size 4200000",
                    "dim 1080x1920",
                    "blurhash blur",
                    "thumb https://media.example/poster.webp",
                    "duration 3",
                ),
                listOf("q", "quoted-id", "wss://relay.example", author),
                listOf("p", author),
            ),
        )

        assertGolden(state, expected)
    }

    @Test
    fun `poll preview payload is the published template`() {
        val state = state(
            blocks = listOf(PublishBlock.Text("Choose one")),
            target = PublishTarget.Poll(
                PublishPoll(
                    options = listOf(
                        PublishPollOption("a", " Alpha "),
                        PublishPollOption("b", "Beta"),
                        PublishPollOption("blank", ""),
                    ),
                    responseRelays = listOf("wss://one.example", "wss://one.example", "wss://two.example"),
                    multipleChoice = true,
                    durationSeconds = 3_600,
                )
            ),
        )
        val expected = PublishPayload(
            createdAt = CREATED_AT,
            kind = 1068,
            content = "Choose one",
            tags = listOf(
                listOf("option", "a", "Alpha"),
                listOf("option", "b", "Beta"),
                listOf("relay", "wss://one.example"),
                listOf("relay", "wss://two.example"),
                listOf("polltype", "multiplechoice"),
                listOf("endsAt", (CREATED_AT + 3_600).toString()),
            ),
        )

        assertGolden(state, expected)
    }

    @Test
    fun `content warning preview payload is the published template`() {
        val expected = PublishPayload(
            createdAt = CREATED_AT,
            kind = 1,
            content = "sensitive",
            tags = listOf(listOf("content-warning", "")),
        )

        assertGolden(
            state = state(
                blocks = listOf(PublishBlock.Text("sensitive")),
                isSensitive = true,
            ),
            expected = expected,
        )
        assertTrue(expected.hasContentWarning)
    }

    @Test
    fun `custom emoji preview payload is the published template`() {
        val expected = PublishPayload(
            createdAt = CREATED_AT,
            kind = 1,
            content = "hello :party_time: #Nostr",
            tags = listOf(
                listOf("emoji", "party_time", "https://emoji.example/party.webp"),
                listOf("t", "nostr"),
            ),
        )

        assertGolden(
            state = state(
                blocks = listOf(PublishBlock.Text(expected.content)),
                customEmojis = mapOf("party_time" to "https://emoji.example/party.webp"),
            ),
            expected = expected,
        )
    }

    @Test
    fun `selected emoji survives a late registry change in a reply payload`() {
        val emojiUrls = composeEmojiUrls(
            knownEmojis = emptyList(),
            selectedEmojis = mapOf("bpist" to "https://emoji.example/bpist.webp"),
        )
        val target = PublishTarget.Reply(
            rootEventId = "a".repeat(64),
            parentEventId = "b".repeat(64),
            parentPubkey = "c".repeat(64),
        )
        val expected = PublishPayload(
            createdAt = CREATED_AT,
            kind = 1,
            content = "reply :bpist:",
            tags = listOf(
                listOf("e", target.rootEventId, "", "root"),
                listOf("e", target.parentEventId, "", "reply"),
                listOf("emoji", "bpist", "https://emoji.example/bpist.webp"),
            ),
            replyToId = target.parentEventId,
            rootId = target.rootEventId,
        )

        assertGolden(
            state = state(
                blocks = listOf(PublishBlock.Text(expected.content)),
                target = target,
                customEmojis = emojiUrls,
            ),
            expected = expected,
        )
    }

    @Test
    fun `mention notification choice regenerates only the held payload`() {
        val mentionedPubkey = "1a".repeat(32)
        val base = state(
            blocks = listOf(PublishBlock.Text("hello mention")),
            mentionPubkeys = setOf(mentionedPubkey),
        )

        val muted = buildPublishPayload(base)
        val active = buildPublishPayload(base.copy(activeNotifyPubkeys = setOf(mentionedPubkey)))

        assertFalse(muted.tags.any { it.firstOrNull() == "p" })
        assertTrue(active.tags.contains(listOf("p", mentionedPubkey)))
        assertEquals(muted.content, active.content)
        assertEquals(muted.createdAt, active.createdAt)
    }

    @Test
    fun `article comment also uses the canonical builder`() {
        val target = ArticleCommentTarget(
            articleId = "article-id",
            articleCoord = "30023:${"c".repeat(64)}:slug",
            articlePubkey = "c".repeat(64),
            parentId = "comment-id",
            parentKind = 1111,
            parentPubkey = "d".repeat(64),
        )
        val payload = buildPublishPayload(
            state(
                blocks = listOf(PublishBlock.Text("article reply")),
                target = PublishTarget.ArticleComment(target),
            )
        )

        assertGolden(
            state = state(
                blocks = listOf(PublishBlock.Text("article reply")),
                target = PublishTarget.ArticleComment(target),
            ),
            expected = payload,
        )
        assertEquals(1111, payload.kind)
        assertEquals("comment-id", payload.threadedReplyTargetId())
    }

    private fun state(
        blocks: List<PublishBlock>,
        target: PublishTarget = PublishTarget.Note(),
        mentionPubkeys: Set<String> = emptySet(),
        activeNotifyPubkeys: Set<String> = emptySet(),
        customEmojis: Map<String, String> = emptyMap(),
        isSensitive: Boolean = false,
    ) = PublishPayloadState(
        createdAt = CREATED_AT,
        blocks = blocks,
        target = target,
        mentionPubkeys = mentionPubkeys,
        activeNotifyPubkeys = activeNotifyPubkeys,
        customEmojis = customEmojis,
        isSensitive = isSensitive,
    )

    private fun assertGolden(state: PublishPayloadState, expected: PublishPayload) {
        val payload = buildPublishPayload(state)
        assertEquals(expected, payload)

        val publishedTemplate = payload.signingTemplateSnapshot()
        assertEquals(
            PublishTemplateSnapshot(
                createdAt = payload.createdAt,
                kind = payload.kind,
                content = payload.content,
                tags = payload.tags,
            ),
            publishedTemplate,
        )
    }

    private companion object {
        const val CREATED_AT = 1_700_000_000L
    }
}
