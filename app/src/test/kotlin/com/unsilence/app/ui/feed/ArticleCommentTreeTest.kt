package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.ui.shared.ReplyListItem
import com.unsilence.app.ui.shared.markLikelyCoordinatedSpam
import com.unsilence.app.ui.shared.replyListItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleCommentTreeTest {

    private fun row(
        id: String,
        replyToId: String?,
        createdAt: Long,
        pubkey: String = "pk",
        content: String = "c",
    ) = FeedRow(
        id = id, pubkey = pubkey, kind = 1111, content = content, createdAt = createdAt,
        tags = "[]", relayUrl = "wss://r", replyToId = replyToId, rootId = null,
        hasContentWarning = false, contentWarningReason = null, zapTotalSats = 0,
        authorName = null, authorDisplayName = null, authorPicture = null, authorNip05 = null,
        reactionCount = 0, replyCount = 0, repostCount = 0, zapCount = 0,
    )

    @Test
    fun `child nests under its parent, other roots stay flat`() {
        val comments = listOf(
            row("A", replyToId = "article", createdAt = 1),  // direct (parent = article, not in set)
            row("B", replyToId = "A", createdAt = 2),         // child of A
            row("C", replyToId = null, createdAt = 3),        // direct
        )
        val flat = flattenArticleComments(comments)
        assertEquals(listOf("A" to 0, "B" to 1, "C" to 0), flat.map { it.row.id to it.depth })
    }

    @Test
    fun `child appears under parent even when chronologically later than the next root`() {
        val comments = listOf(
            row("A", replyToId = null, createdAt = 1),
            row("C", replyToId = null, createdAt = 2),
            row("B", replyToId = "A", createdAt = 3),  // newest, but child of A
        )
        // A, then its child B (despite being newest), then root C.
        assertEquals(listOf("A", "B", "C"), flattenArticleComments(comments).map { it.row.id })
    }

    @Test
    fun `muted parent stays in the tree and preserves its child depth`() {
        val comments = listOf(
            row("A", replyToId = null, createdAt = 1),
            row("B", replyToId = "A", createdAt = 2),
            row("C", replyToId = null, createdAt = 3),
        )

        val flat = flattenArticleComments(comments, mutedIds = setOf("A"))

        assertEquals(listOf("A", "B", "C"), flat.map { it.row.id })
        assertTrue(flat[0].muted)
        assertEquals(0, flat[0].depth)
        assertFalse(flat[1].muted)
        assertEquals(1, flat[1].depth)
    }

    @Test
    fun `muted leaf comment is dropped`() {
        val comments = listOf(
            row("visible", replyToId = null, createdAt = 1),
            row("muted-leaf", replyToId = null, createdAt = 2),
        )

        val flat = flattenArticleComments(comments, mutedIds = setOf("muted-leaf"))

        assertEquals(listOf("visible"), flat.map { it.row.id })
    }

    @Test
    fun `muted comment parent with only muted descendants collapses wholesale`() {
        val comments = listOf(
            row("muted-parent", replyToId = null, createdAt = 1),
            row("muted-child", replyToId = "muted-parent", createdAt = 2),
            row("visible-root", replyToId = null, createdAt = 3),
        )

        val flat = flattenArticleComments(
            comments,
            mutedIds = setOf("muted-parent", "muted-child"),
        )

        assertEquals(listOf("visible-root"), flat.map { it.row.id })
    }

    @Test
    fun `tagged seed spam in article comments collapses through the shared projection`() {
        val comments = listOf(
            row(
                id = "spam",
                replyToId = "article",
                createdAt = 1,
                pubkey = "unknown",
                content = "one, two, three, four, five, six, seven, eight, @spammer",
            ),
            row(
                id = "visible",
                replyToId = "article",
                createdAt = 2,
                pubkey = "friend",
                content = "A normal comment remains visible.",
            ),
        )

        val marked = markLikelyCoordinatedSpam(flattenArticleComments(comments))
        val projected = replyListItems(marked, emptySet())

        assertEquals(2, projected.size)
        assertTrue(projected.first() is ReplyListItem.SpamCluster)
        assertEquals("visible", (projected.last() as ReplyListItem.Reply).key)
    }

    @Test
    fun `deep-linked article comment is protected from spam collapse`() {
        val focused = row(
            id = "focused",
            replyToId = "article",
            createdAt = 1,
            pubkey = "unknown",
            content = "one, two, three, four, five, six, seven, eight, @spammer",
        )

        val marked = markLikelyCoordinatedSpam(
            rows = flattenArticleComments(listOf(focused)),
            protectedEventIds = setOf(focused.id),
        )

        assertEquals(null, marked.single().spamClusterId)
        assertTrue(replyListItems(marked, emptySet()).single() is ReplyListItem.Reply)
    }

    @Test
    fun `depth is capped at the max`() {
        // Linear chain r0 <- r1 <- ... <- r8 (depth would be 8) capped at 6.
        val comments = (0..8).map { row("r$it", replyToId = if (it == 0) null else "r${it - 1}", createdAt = it.toLong()) }
        val depths = flattenArticleComments(comments, maxDepth = 6).map { it.depth }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 6, 6), depths)
    }
}
