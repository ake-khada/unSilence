package com.unsilence.app.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSizingTest {
    @Test
    fun `every portrait video keeps its native full-width frame`() {
        listOf(9f / 16f, 1f / 2f, 9f / 21f, 0.25f).forEach { ratio ->
            assertEquals(ratio, feedVideoAspectRatio(ratio), 0.001f)
        }
    }

    @Test
    fun `landscape fallback and square grid sizing remain stable`() {
        assertEquals(16f / 9f, feedVideoAspectRatio(16f / 9f), 0.001f)
        assertEquals(16f / 9f, feedVideoAspectRatio(null), 0.001f)
        assertEquals(1f, feedVideoAspectRatio(9f / 21f, forceSquare = true), 0.001f)
    }

    @Test
    fun `aspect correction requires a material mismatch`() {
        assertEquals(false, shouldCorrectVideoAspectRatio(1f, 1.02f))
        assertEquals(true, shouldCorrectVideoAspectRatio(1f, 1.021f))
        assertEquals(true, shouldCorrectVideoAspectRatio(9f / 16f, 1f / 2f))
    }

    @Test
    fun `decoded aspect includes pixel shape and rejects invalid frames`() {
        assertEquals(16f / 9f, decodedVideoAspectRatio(1920, 1080)!!, 0.001f)
        assertEquals(1.25f, decodedVideoAspectRatio(720, 576)!!, 0.001f)
        assertEquals(1.5f, decodedVideoAspectRatio(720, 576, 1.2f)!!, 0.001f)
        assertEquals(null, decodedVideoAspectRatio(0, 1080))
    }
}
