package com.unsilence.app.ui.feed

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailTargetTest {
    private val landscape = IntSize(3840, 2160)

    @Test fun fitUsesBothAvailableDimensions() {
        assertEquals(IntSize(432, 243), ThumbnailTarget(IntSize(432, 500)).frameSize(landscape))
        assertEquals(IntSize(640, 360), ThumbnailTarget(IntSize(1200, 360)).frameSize(landscape))
    }

    @Test fun cropRetainsEnoughDetailAcrossTheShortAxis() {
        assertEquals(IntSize(768, 432), ThumbnailTarget(IntSize(432, 432), crop = true).frameSize(landscape))
        assertEquals(IntSize(432, 768), ThumbnailTarget(IntSize(432, 432), crop = true).frameSize(IntSize(2160, 3840)))
    }

    @Test fun largeDisplaysAndTinySourcesRespectTheSourceCeiling() {
        val tablet = ThumbnailTarget(IntSize(5000, 4000))
        assertEquals(IntSize(1920, 1080), tablet.frameSize(landscape))
        assertEquals(IntSize(160, 121), tablet.frameSize(IntSize(321, 243)))
        assertEquals(IntSize(1, 1), tablet.frameSize(IntSize(1, 1)))
    }
}
