package com.unsilence.app.data.blossom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTranscoderTest {
    @Test
    fun `quality ladder uses explicit target heights and bitrates`() {
        assertEquals(480, VideoTranscoder.Quality.SMALL.heightPx)
        assertEquals(VIDEO_BITRATE_480P, VideoTranscoder.Quality.SMALL.bitrate)
        assertEquals(720, VideoTranscoder.Quality.STANDARD.heightPx)
        assertEquals(VIDEO_BITRATE_720P, VideoTranscoder.Quality.STANDARD.bitrate)
        assertEquals(1080, VideoTranscoder.Quality.HIGH.heightPx)
        assertEquals(VIDEO_BITRATE_1080P, VideoTranscoder.Quality.HIGH.bitrate)
        assertEquals(1080, VideoTranscoder.Quality.ORIGINAL.heightPx)
        assertEquals(VIDEO_BITRATE_1080P, VideoTranscoder.Quality.ORIGINAL.bitrate)
    }

    @Test
    fun `presentation height never upscales`() {
        assertEquals(480, cappedVideoHeight(720, 480))
        assertEquals(720, cappedVideoHeight(720, 2160))
        assertEquals(720, cappedVideoHeight(720, 0))
    }

    @Test
    fun `original accepts AVC and HEVC MP4 only`() {
        assertTrue(isCompatibleOriginalVideo("video/mp4", "video/avc"))
        assertTrue(isCompatibleOriginalVideo("video/mp4; codecs=hvc1", "video/hevc"))
        assertFalse(isCompatibleOriginalVideo("video/quicktime", "video/avc"))
        assertFalse(isCompatibleOriginalVideo("video/mp4", "video/x-vnd.on2.vp9"))
        assertFalse(isCompatibleOriginalVideo(null, "video/avc"))
    }
}
