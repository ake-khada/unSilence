package com.unsilence.app.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReactionTagsTest {
    private fun Array<Array<String>>.tag(name: String): List<String>? =
        firstOrNull { it.firstOrNull() == name }?.toList()

    @Test
    fun `kind-1 target emits e p and k`() {
        val tags = buildReactionTags("event", "author", 1, null)

        assertEquals(listOf("e", "event"), tags.tag("e"))
        assertEquals(listOf("p", "author"), tags.tag("p"))
        assertEquals(listOf("k", "1"), tags.tag("k"))
        assertNull(tags.tag("a"))
    }

    @Test
    fun `addressable targets emit their coordinate`() {
        val article = buildReactionTags("article", "author", 30023, "slug")
        val video = buildReactionTags("video", "author", 34236, "clip")

        assertEquals(listOf("a", "30023:author:slug"), article.tag("a"))
        assertEquals(listOf("a", "34236:author:clip"), video.tag("a"))
        assertEquals(listOf("k", "34236"), video.tag("k"))
    }

    @Test
    fun `unknown target kind preserves legacy e and p tags only`() {
        val tags = buildReactionTags("event", "author", null, null)

        assertEquals(2, tags.size)
        assertEquals(listOf("e", "event"), tags.tag("e"))
        assertEquals(listOf("p", "author"), tags.tag("p"))
        assertNull(tags.tag("k"))
        assertNull(tags.tag("a"))
    }

    @Test
    fun `custom emoji tag is unchanged`() {
        val tags = buildReactionTags(
            targetId = "event",
            targetPubkey = "author",
            targetKind = 1,
            targetDTag = null,
            emoji = ":party:",
            customEmojiUrl = "https://emoji.example/party.webp",
        )

        assertEquals(
            listOf("emoji", "party", "https://emoji.example/party.webp"),
            tags.tag("emoji"),
        )
    }
}
