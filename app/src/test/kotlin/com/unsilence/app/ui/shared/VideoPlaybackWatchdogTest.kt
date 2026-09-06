package com.unsilence.app.ui.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackWatchdogTest {

    @Test
    fun `active scrolling never qualifies as starvation`() {
        assertFalse(
            shouldEvaluateVideoStarvation(
                activeVideoNoteId = null,
                hasVideoModels = true,
                isScrollInProgress = true,
                millisSinceLastScroll = 5_000L,
            ),
        )
    }

    @Test
    fun `watchdog leaves a full confirmation window after scrolling`() {
        assertFalse(
            shouldEvaluateVideoStarvation(
                activeVideoNoteId = null,
                hasVideoModels = true,
                isScrollInProgress = false,
                millisSinceLastScroll = VideoPlaybackScope.ACTIVATION_CONFIRMATION_MS - 1L,
            ),
        )
        assertTrue(
            shouldEvaluateVideoStarvation(
                activeVideoNoteId = null,
                hasVideoModels = true,
                isScrollInProgress = false,
                millisSinceLastScroll = VideoPlaybackScope.ACTIVATION_CONFIRMATION_MS,
            ),
        )
    }

    @Test
    fun `stationary unresolved scope qualifies only with models and no active video`() {
        assertTrue(
            shouldEvaluateVideoStarvation(
                activeVideoNoteId = null,
                hasVideoModels = true,
                isScrollInProgress = false,
                millisSinceLastScroll = null,
            ),
        )
        assertFalse(
            shouldEvaluateVideoStarvation(
                activeVideoNoteId = "active",
                hasVideoModels = true,
                isScrollInProgress = false,
                millisSinceLastScroll = null,
            ),
        )
        assertFalse(
            shouldEvaluateVideoStarvation(
                activeVideoNoteId = null,
                hasVideoModels = false,
                isScrollInProgress = false,
                millisSinceLastScroll = null,
            ),
        )
    }
}
