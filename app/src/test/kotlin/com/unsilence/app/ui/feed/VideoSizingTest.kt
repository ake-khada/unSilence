package com.unsilence.app.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSizingTest {
    @Test
    fun `short portrait video keeps its native full-width frame`() {
        assertEquals(9f / 16f, feedVideoAspectRatio(9f / 16f, shortForm = true), 0.001f)
        assertEquals(1f / 2f, feedVideoAspectRatio(1f / 2f, shortForm = true), 0.001f)
    }

    @Test
    fun `normal portrait video keeps existing nine by sixteen frame`() {
        assertEquals(9f / 16f, feedVideoAspectRatio(9f / 16f), 0.001f)
    }

    @Test
    fun `short landscape and square grid sizing remain unchanged`() {
        assertEquals(16f / 9f, feedVideoAspectRatio(16f / 9f, shortForm = true), 0.001f)
        assertEquals(1f, feedVideoAspectRatio(9f / 16f, forceSquare = true, shortForm = true), 0.001f)
    }
}
