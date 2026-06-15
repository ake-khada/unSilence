package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.FeedRow
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleCommentTreeTest {

    private fun row(id: String, replyToId: String?, createdAt: Long) = FeedRow(
        id = id, pubkey = "pk", kind = 1111, content = "c", createdAt = createdAt,
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
    fun `depth is capped at the max`() {
        // Linear chain r0 <- r1 <- ... <- r8 (depth would be 8) capped at 6.
        val comments = (0..8).map { row("r$it", replyToId = if (it == 0) null else "r${it - 1}", createdAt = it.toLong()) }
        val depths = flattenArticleComments(comments, maxDepth = 6).map { it.depth }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 6, 6), depths)
    }
}
