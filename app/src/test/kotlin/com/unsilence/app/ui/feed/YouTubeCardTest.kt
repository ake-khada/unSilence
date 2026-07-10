package com.unsilence.app.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeCardTest {

    @Test
    fun `thumbnail chain prefers ytimg webp and retains jpeg fallbacks`() {
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi_webp/aiozSvD4nqY/hqdefault.webp",
                "https://i.ytimg.com/vi/aiozSvD4nqY/hqdefault.jpg",
                "https://img.youtube.com/vi/aiozSvD4nqY/hqdefault.jpg",
            ),
            youtubeThumbnailUrls("aiozSvD4nqY"),
        )
    }
}
