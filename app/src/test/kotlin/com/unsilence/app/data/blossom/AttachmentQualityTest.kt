package com.unsilence.app.data.blossom

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentQualityTest {
    @Test
    fun `image ladder maps dimensions and quality together`() {
        assertEquals(1280 to 78, AttachmentQuality.SMALL.imageSettings())
        assertEquals(2048 to 82, AttachmentQuality.STANDARD.imageSettings())
        assertEquals(2560 to 87, AttachmentQuality.HIGH.imageSettings())
        assertEquals(0 to 100, AttachmentQuality.ORIGINAL.imageSettings())
    }

    @Test
    fun `legacy dimensions migrate to the nearest new tier`() {
        assertEquals(AttachmentQuality.SMALL, AttachmentQuality.fromImageMaxDimension(1024))
        assertEquals(AttachmentQuality.STANDARD, AttachmentQuality.fromImageMaxDimension(1600))
        assertEquals(AttachmentQuality.STANDARD, AttachmentQuality.fromImageMaxDimension(2048))
        assertEquals(AttachmentQuality.HIGH, AttachmentQuality.fromImageMaxDimension(2560))
        assertEquals(AttachmentQuality.ORIGINAL, AttachmentQuality.fromImageMaxDimension(0))
    }

    @Test
    fun `attachment tiers map directly to video tiers`() {
        assertEquals(VideoTranscoder.Quality.SMALL, AttachmentQuality.SMALL.videoQuality())
        assertEquals(VideoTranscoder.Quality.STANDARD, AttachmentQuality.STANDARD.videoQuality())
        assertEquals(VideoTranscoder.Quality.HIGH, AttachmentQuality.HIGH.videoQuality())
        assertEquals(VideoTranscoder.Quality.ORIGINAL, AttachmentQuality.ORIGINAL.videoQuality())
    }
}
