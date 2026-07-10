package com.unsilence.app.data.blossom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCompressorTest {
    @Test
    fun `tiny image decodes without sampling`() {
        assertEquals(1, calculateImageSampleSize(640, 480, 1280))
    }

    @Test
    fun `sampled decode stays between target and twice target`() {
        assertEquals(1, calculateImageSampleSize(4096, 2048, 2048))
        assertEquals(2, calculateImageSampleSize(4097, 2048, 2048))
        assertEquals(2, calculateImageSampleSize(8192, 4096, 2048))
        assertEquals(4, calculateImageSampleSize(8193, 4096, 2048))
    }

    @Test
    fun `hundred megapixel image is sampled below decode cap`() {
        val target = 1280
        val sample = calculateImageSampleSize(10_000, 10_000, target)
        assertEquals(4, sample)
        val decodedLongest = (10_000 + sample - 1) / sample
        assertTrue(decodedLongest in target..(target * 2))
    }

    @Test
    fun `invalid dimensions use safe default`() {
        assertEquals(1, calculateImageSampleSize(0, 10_000, 2048))
        assertEquals(1, calculateImageSampleSize(10_000, -1, 2048))
        assertEquals(1, calculateImageSampleSize(10_000, 10_000, 0))
    }
}
