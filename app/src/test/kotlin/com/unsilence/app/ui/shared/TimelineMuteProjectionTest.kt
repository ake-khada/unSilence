package com.unsilence.app.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineMuteProjectionTest {

    @Test
    fun `muted leaf is dropped`() {
        val rows = listOf(
            node("root", depth = 0),
            node("spam", depth = 1, muted = true),
        )

        assertEquals(listOf("root"), prune(rows).map { it.id })
    }

    @Test
    fun `muted parent stays when it connects a visible child`() {
        val rows = listOf(
            node("muted-parent", depth = 0, muted = true),
            node("live-child", depth = 1),
        )

        val result = prune(rows)

        assertEquals(listOf("muted-parent", "live-child"), result.map { it.id })
        assertEquals(listOf(0, 1), result.map { it.depth })
    }

    @Test
    fun `parent with only muted descendants disappears as one subtree`() {
        val rows = listOf(
            node("live-root", depth = 0),
            node("muted-parent", depth = 0, muted = true),
            node("muted-child", depth = 1, muted = true),
            node("muted-grandchild", depth = 2, muted = true),
            node("next-live-root", depth = 0),
        )

        assertEquals(
            listOf("live-root", "next-live-root"),
            prune(rows).map { it.id },
        )
    }

    private fun prune(rows: List<Node>): List<Node> =
        pruneFullyMutedSubtrees(
            rows = rows,
            depthOf = { it.depth },
            isMuted = { it.muted },
        )

    private fun node(id: String, depth: Int, muted: Boolean = false) =
        Node(id = id, depth = depth, muted = muted)

    private data class Node(
        val id: String,
        val depth: Int,
        val muted: Boolean,
    )
}
