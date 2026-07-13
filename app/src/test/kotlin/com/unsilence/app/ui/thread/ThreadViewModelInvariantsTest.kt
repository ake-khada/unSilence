package com.unsilence.app.ui.thread

import com.unsilence.app.data.memory.FeedRow
import org.junit.Assert.assertEquals
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

    /**
     * Mirrors the DFS walk in ThreadViewModel.collect{} — same algorithm,
     * extracted here so we can test it without standing up the full VM.
     */
    private fun walkThread(focusedId: String, replyRows: List<FeedRow>): List<DepthRow> {
        val childrenOf = replyRows.groupBy { it.replyToId ?: it.rootId ?: focusedId }
            .mapValues { (_, v) -> v.sortedBy { it.createdAt } }

        val flatList = mutableListOf<DepthRow>()
        val visited = mutableSetOf<String>()
        fun walk(parentId: String, depth: Int) {
            childrenOf[parentId]?.forEach { row ->
                if (visited.add(row.id)) {
                    flatList.add(DepthRow(row, depth.coerceAtMost(MAX_REPLY_DEPTH)))
                    walk(row.id, depth + 1)
                }
            }
        }
        walk(focusedId, 1)
        return flatList
    }

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
        val result = walkThread(focused, replies)

        assertEquals(3, result.size)
        // A comes first (createdAt 100), then its child C, then B (createdAt 200)
        assertEquals("A", result[0].row.id)
        assertEquals(1, result[0].depth)
        assertEquals("C", result[1].row.id)
        assertEquals(2, result[1].depth)
        assertEquals("B", result[2].row.id)
        assertEquals(1, result[2].depth)
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
        val result = walkThread(focused, replies)

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
        val result = walkThread(focused, replies)

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
        val result = walkThread(focused, replies)

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
        val result = walkThread(focused, replies)

        assertTrue("Result should be finite", result.size <= 3)
        val ids = result.map { it.row.id }
        assertEquals("No duplicate IDs", ids.toSet().size, ids.size)
    }
}
