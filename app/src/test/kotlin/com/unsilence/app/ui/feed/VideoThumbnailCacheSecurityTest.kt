package com.unsilence.app.ui.feed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoThumbnailCacheSecurityTest {
    @Test
    fun `content and file inputs stay on local context path`() {
        assertTrue(
            classifyVideoThumbnailInput("content://media/external/video/1") is
                VideoThumbnailInput.LocalUri,
        )
        assertTrue(
            classifyVideoThumbnailInput("file:///data/user/0/app/cache/video.mp4") is
                VideoThumbnailInput.LocalUri,
        )
        assertTrue(
            classifyVideoThumbnailInput("/data/user/0/app/cache/video.mp4") is
                VideoThumbnailInput.LocalPath,
        )
    }

    @Test
    fun `only policy-approved remote URL reaches frame extractor`() = runBlocking {
        val inputs = mutableListOf<VideoThumbnailInput>()
        val cache = VideoThumbnailCache { input ->
            inputs += input
            null
        }

        assertNull(cache.getThumbnail("https://192.168.1.1/video.mp4"))
        assertNull(cache.getThumbnail("http://media.example/video.mp4"))
        assertEquals(0, inputs.size)

        assertNull(cache.getThumbnail("https://media.example/video.mp4"))
        assertEquals(1, inputs.size)
        assertTrue(inputs.single() is VideoThumbnailInput.Remote)
    }

    @Test
    fun `remote extraction failure is negative cached without retry storm`() = runBlocking {
        var attempts = 0
        val cache = VideoThumbnailCache {
            attempts++
            null
        }
        val url = "https://media.example/unreachable.mp4"

        assertNull(cache.getThumbnail(url))
        assertNull(cache.getThumbnail(url))
        assertEquals(1, attempts)
    }
}
