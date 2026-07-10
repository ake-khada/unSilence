package com.unsilence.app.data.blossom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCompressorTest {
    @Test
    fun `tiny image decodes without sampling`() {
        assertEquals(1, calculateImageSampleSize(640, 480))
    }

    @Test
    fun `exact power of two dimensions land on the target`() {
        assertEquals(1, calculateImageSampleSize(2048, 1024))
        assertEquals(2, calculateImageSampleSize(4096, 2048))
        assertEquals(4, calculateImageSampleSize(4097, 2048))
    }

    @Test
    fun `hundred megapixel image is sampled below decode cap`() {
        val sample = calculateImageSampleSize(10_000, 10_000)
        assertEquals(8, sample)
        assertTrue((10_000 + sample - 1) / sample <= MAX_IMAGE_DECODE_DIMENSION)
    }

    @Test
    fun `invalid dimensions use safe default`() {
        assertEquals(1, calculateImageSampleSize(0, 10_000))
        assertEquals(1, calculateImageSampleSize(10_000, -1))
    }
}
