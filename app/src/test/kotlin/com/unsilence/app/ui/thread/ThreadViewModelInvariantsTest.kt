package com.unsilence.app.ui.thread

import com.unsilence.app.data.memory.FeedRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the DFS walk logic extracted from ThreadViewModel.
 * Verifies cycle protection: circular reply chains must NOT cause stack overflow.
 */
class ThreadViewModelInvariantsTest {

    private fun row(
        id: String,
        replyToId: String? = null,
        rootId: String? = null,
        createdAt: Long = 1000L,
    ): FeedRow = FeedRow(
        id = id,
        pubkey = "pk",
        kind = 1,
        content = "test",
        createdAt = createdAt,
        tags = "[]",
        relayUrl = "wss://relay.test",
        replyToId = replyToId,
        rootId = rootId,
        hasContentWarning = false,
        contentWarningReason = null,
        zapTotalSats = 0,
        authorName = null,
        authorDisplayName = null,
        authorPicture = null,
        authorNip05 = null,
        reactionCount = 0,
        replyCount = 0,
        repostCount = 0,
        zapCount = 0,
    )

    // ── Normal tree ──────────────────────────────────────────────────────────

    @Test
    fun `simple thread tree flattens correctly with depths`() {
        // Tree:  focused
        //          ├─ A (depth 1)
        //          │   └─ C (depth 2)
        //          └─ B (depth 1)
        val focused = "root"
        val replies = listOf(
            row("A", replyToId = focused, createdAt = 100),
            row("B", replyToId = focused, createdAt = 200),
            row("C", replyToId = "A", createdAt = 150),
        )
        val result = flattenThreadReplies(focused, replies, coordinateScoped = false)

        assertEquals(3, result.size)
        // A comes first (createdAt 100), then its child C, then B (createdAt 200)
        assertEquals("A", result[0].row.id)
        assertEquals(1, result[0].depth)
        assertEquals("C", result[1].row.id)
        assertEquals(2, result[1].depth)
        assertEquals("B", result[2].row.id)
        assertEquals(1, result[2].depth)
    }

    @Test
    fun `muted parent remains as a placeholder and keeps its child attached`() {
        val focused = "root"
        val replies = listOf(
            row("A", replyToId = focused, createdAt = 100),
            row("B", replyToId = focused, createdAt = 200),
            row("C", replyToId = "A", createdAt = 150),
        )

        val result = flattenThreadReplies(
            focusedId = focused,
            replyRows = replies,
            coordinateScoped = false,
            mutedIds = setOf("A"),
        )

        assertEquals(listOf("A", "C", "B"), result.map { it.row.id })
        assertTrue(result[0].muted)
        assertEquals(1, result[0].depth)
        assertFalse(result[1].muted)
        assertEquals(2, result[1].depth)
    }

    @Test
    fun `muted leaf replies are dropped rather than replaced with placeholders`() {
        val replies = (1..30).map { index ->
            row(
                id = "spam-$index",
                replyToId = "root",
                createdAt = index.toLong(),
            )
        }

        val result = flattenThreadReplies(
            focusedId = "root",
            replyRows = replies,
            coordinateScoped = false,
            mutedIds = replies.mapTo(HashSet()) { it.id },
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `muted parent with only muted descendants collapses wholesale`() {
        val replies = listOf(
            row("muted-parent", replyToId = "root", createdAt = 100),
            row("muted-child", replyToId = "muted-parent", createdAt = 200),
            row("visible-sibling", replyToId = "root", createdAt = 300),
        )

        val result = flattenThreadReplies(
            focusedId = "root",
            replyRows = replies,
            coordinateScoped = false,
            mutedIds = setOf("muted-parent", "muted-child"),
        )

        assertEquals(listOf("visible-sibling"), result.map { it.row.id })
    }

    // ── Circular reference ───────────────────────────────────────────────────

    @Test
    fun `circular reply chain does not cause stack overflow`() {
        // A replies to B, B replies to A — both claim root = "root"
        val focused = "root"
        val replies = listOf(
            row("A", replyToId = "B", rootId = focused, createdAt = 100),
            row("B", replyToId = "A", rootId = focused, createdAt = 200),
        )
        // Without cycle protection this would recurse forever.
        // With the visited set it should terminate with a finite list.
        val result = flattenThreadReplies(focused, replies, coordinateScoped = false)

        // Both nodes form a disconnected cycle (A→B, B→A, neither is a child of "root").
        // Walk from "root" correctly finds nothing. The key invariant: no crash, finite, no dupes.
        assertTrue("Result should be finite", result.size <= 2)
        val ids = result.map { it.row.id }.toSet()
        assertEquals("No duplicate IDs", ids.size, result.size)
    }

    // ── Self-referencing node ────────────────────────────────────────────────

    @Test
    fun `self-referencing reply does not cause stack overflow`() {
        val focused = "root"
        val replies = listOf(
            row("A", replyToId = "A", rootId = focused, createdAt = 100),
        )
        val result = flattenThreadReplies(focused, replies, coordinateScoped = false)

        // A's parent is itself, but since the groupBy key is replyToId ("A"),
        // walk("root", 1) won't find it — only walk("A", ...) would.
        // Either way: finite, no crash, no duplicates.
        assertTrue("Result should be finite", result.size <= 1)
        assertEquals("No duplicate IDs", result.map { it.row.id }.toSet().size, result.size)
    }

    // ── Depth cap ────────────────────────────────────────────────────────────

    @Test
    fun `depth is capped at 10`() {
        val focused = "root"
        // Chain: root -> d1 -> ... -> d11.
        val replies = (1..11).map { depth ->
            row(
                id = "d$depth",
                replyToId = if (depth == 1) focused else "d${depth - 1}",
                createdAt = depth * 100L,
            )
        }
        val result = flattenThreadReplies(focused, replies, coordinateScoped = false)

        assertEquals(11, result.size)
        assertEquals((1..10).toList() + 10, result.map { it.depth })
    }

    // ── Three-node cycle ─────────────────────────────────────────────────────

    @Test
    fun `three-node cycle does not cause stack overflow`() {
        // A -> B -> C -> A (circular)
        val focused = "root"
        val replies = listOf(
            row("A", replyToId = "C", rootId = focused, createdAt = 100),
            row("B", replyToId = "A", rootId = focused, createdAt = 200),
            row("C", replyToId = "B", rootId = focused, createdAt = 300),
        )
        val result = flattenThreadReplies(focused, replies, coordinateScoped = false)

        assertTrue("Result should be finite", result.size <= 3)
        val ids = result.map { it.row.id }
        assertEquals("No duplicate IDs", ids.toSet().size, ids.size)
    }
}
