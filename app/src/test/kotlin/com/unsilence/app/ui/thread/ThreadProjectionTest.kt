package com.unsilence.app.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadProjectionTest {
    private val focusedId = "video"

    @Test
    fun `coordinate child with missing parent stays visible at root`() {
        assertEquals(
            focusedId,
            threadProjectionParentId(
                replyToId = "missing-parent",
                rootId = null,
                focusedId = focusedId,
                availableReplyIds = setOf("child"),
                coordinateScoped = true,
            ),
        )
    }

    @Test
    fun `coordinate child reattaches when parent arrives`() {
        assertEquals(
            "parent",
            threadProjectionParentId(
                replyToId = "parent",
                rootId = null,
                focusedId = focusedId,
                availableReplyIds = setOf("parent", "child"),
                coordinateScoped = true,
            ),
        )
    }

    @Test
    fun `direct and unthreaded coordinate comments attach to root`() {
        assertEquals(
            focusedId,
            threadProjectionParentId(
                replyToId = focusedId,
                rootId = focusedId,
                focusedId = focusedId,
                availableReplyIds = emptySet(),
                coordinateScoped = true,
            ),
        )
        assertEquals(
            focusedId,
            threadProjectionParentId(
                replyToId = null,
                rootId = null,
                focusedId = focusedId,
                availableReplyIds = emptySet(),
                coordinateScoped = true,
            ),
        )
    }

    @Test
    fun `ordinary thread does not invent a root attachment`() {
        assertEquals(
            "missing-parent",
            threadProjectionParentId(
                replyToId = "missing-parent",
                rootId = null,
                focusedId = focusedId,
                availableReplyIds = emptySet(),
                coordinateScoped = false,
            ),
        )
    }
}
