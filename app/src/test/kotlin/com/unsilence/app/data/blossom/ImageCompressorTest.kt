package com.unsilence.app.data.blossom

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCompressorTest {
    @Test
    fun `original writable formats use orientation-only metadata policy`() {
        assertEquals(
            OriginalImageMetadataMode.EXIF_ORIENTATION_ONLY,
            originalImageMetadataMode("image/jpeg"),
        )
        assertEquals(
            OriginalImageMetadataMode.EXIF_ORIENTATION_ONLY,
            originalImageMetadataMode("IMAGE/PNG; charset=binary"),
        )
        assertEquals(
            OriginalImageMetadataMode.EXIF_ORIENTATION_ONLY,
            originalImageMetadataMode("image/webp"),
        )
    }

    @Test
    fun `read-only metadata containers reencode and gif preserves animation`() {
        assertEquals(OriginalImageMetadataMode.REENCODE, originalImageMetadataMode("image/heic"))
        assertEquals(OriginalImageMetadataMode.REENCODE, originalImageMetadataMode("image/heif"))
        assertEquals(OriginalImageMetadataMode.REENCODE, originalImageMetadataMode("image/avif"))
        assertEquals(OriginalImageMetadataMode.REENCODE, originalImageMetadataMode("image/x-adobe-dng"))
        assertEquals(OriginalImageMetadataMode.COPY, originalImageMetadataMode("image/gif"))
    }

    @Test
    fun `orientation-only policy removes private attributes and retains rotation`() {
        val attributes = mutableMapOf(
            ExifInterface.TAG_ORIENTATION to ExifInterface.ORIENTATION_ROTATE_90.toString(),
            ExifInterface.TAG_GPS_LATITUDE to "51/1,30/1,0/1",
            ExifInterface.TAG_GPS_LONGITUDE to "0/1,7/1,28/1",
            ExifInterface.TAG_GPS_PROCESSING_METHOD to "GPS",
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:08:08 12:34:56",
            ExifInterface.TAG_MAKE to "Fixture Camera",
            ExifInterface.TAG_MODEL to "Fixture Model",
            ExifInterface.TAG_SOFTWARE to "Fixture Software",
            ExifInterface.TAG_BODY_SERIAL_NUMBER to "secret-serial",
            ExifInterface.TAG_XMP to "<xmpmeta>private</xmpmeta>",
        )

        assertTrue(
            applyOrientationOnlyExifPolicy(
                orientation = ExifInterface.ORIENTATION_ROTATE_90,
                hasAttribute = attributes::containsKey,
                setAttribute = { tag, value ->
                    if (value == null) attributes.remove(tag) else attributes[tag] = value
                },
            )
        )

        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_90.toString(),
            attributes[ExifInterface.TAG_ORIENTATION],
        )
        assertTrue(PRIVACY_CRITICAL_EXIF_TAGS.none(attributes::containsKey))
        assertFalse(ExifInterface.TAG_ORIENTATION in EXIF_TAGS_TO_REMOVE)
        assertTrue(
            "The allowlist must cover the complete ExifInterface tag surface",
            EXIF_TAGS_TO_REMOVE.size >= 140,
        )
    }

    @Test
    fun `clean orientation-only image is not rewritten`() {
        val attributes = mutableMapOf(
            ExifInterface.TAG_ORIENTATION to ExifInterface.ORIENTATION_ROTATE_270.toString(),
        )
        var writes = 0

        assertFalse(
            applyOrientationOnlyExifPolicy(
                orientation = ExifInterface.ORIENTATION_ROTATE_270,
                hasAttribute = attributes::containsKey,
                setAttribute = { _, _ -> writes++ },
            )
        )
        assertEquals(0, writes)
    }

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
