package com.unsilence.app.data.blossom

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTranscoderTest {
    @Test
    fun `location atom refuses original passthrough`() {
        withTempMp4(
            moviePayload = box(
                byteArrayOf(0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()),
                "+51.5007-000.1246/".toByteArray(),
            ),
        ) { file ->
            val inspection = inspectIsoBmffLocationMetadata(file)
            assertEquals(VideoLocationInspection.LOCATION_PRESENT, inspection)
            assertEquals(
                VideoPassthroughPrivacyAction.REFUSE,
                videoPassthroughPrivacyAction(inspection, originalRequested = true),
            )
        }
    }

    @Test
    fun `location atom forces a lower quality transcode`() {
        withTempMp4(moviePayload = "@xyz+51.5-0.1/".toByteArray()) { file ->
            val inspection = inspectIsoBmffLocationMetadata(file)
            assertEquals(VideoLocationInspection.LOCATION_PRESENT, inspection)
            assertEquals(
                VideoPassthroughPrivacyAction.TRANSCODE,
                videoPassthroughPrivacyAction(inspection, originalRequested = false),
            )
        }
    }

    @Test
    fun `apple ISO6709 metadata is detected across scan chunks`() {
        val prefix = ByteArray(16_377) { 0x5A }
        val key = "com.apple.quicktime.location.ISO6709".toByteArray()
        withTempMp4(moviePayload = prefix + key) { file ->
            assertEquals(
                VideoLocationInspection.LOCATION_PRESENT,
                inspectIsoBmffLocationMetadata(file),
            )
        }
    }

    @Test
    fun `location-free mp4 permits passthrough`() {
        withTempMp4(moviePayload = box("mvhd", ByteArray(32))) { file ->
            val inspection = inspectIsoBmffLocationMetadata(file)
            assertEquals(VideoLocationInspection.CLEAN, inspection)
            assertEquals(
                VideoPassthroughPrivacyAction.ALLOW,
                videoPassthroughPrivacyAction(inspection, originalRequested = true),
            )
        }
    }

    @Test
    fun `malformed or over-budget metadata refuses original passthrough`() {
        val malformed = File.createTempFile("video-privacy-malformed-", ".mp4")
        try {
            malformed.writeBytes(
                byteArrayOf(0, 0, 1, 0) + "moov".toByteArray() + ByteArray(8)
            )
            val malformedInspection = inspectIsoBmffLocationMetadata(malformed)
            assertEquals(VideoLocationInspection.INDETERMINATE, malformedInspection)
            assertEquals(
                VideoPassthroughPrivacyAction.REFUSE,
                videoPassthroughPrivacyAction(malformedInspection, originalRequested = true),
            )
        } finally {
            malformed.delete()
        }

        withTempMp4(moviePayload = ByteArray(128)) { file ->
            assertEquals(
                VideoLocationInspection.INDETERMINATE,
                inspectIsoBmffLocationMetadata(file, scanLimitBytes = 64),
            )
        }
    }

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
    fun `compact field fixture is not worth reencoding`() {
        assertFalse(
            needsReencode(
                sourceBytes = 46_627_445L,
                durationMs = 232_133L,
                tier = VideoTranscoder.Quality.SMALL,
            )
        )
    }

    @Test
    fun `high bitrate 4k camera clip is worth reencoding`() {
        val durationMs = 60_000L
        val sourceBytesAt40Mbps = 300_000_000L
        assertTrue(needsReencode(sourceBytesAt40Mbps, durationMs, VideoTranscoder.Quality.HIGH))
    }

    @Test
    fun `savings threshold is strict at 85 percent`() {
        // Small for eight seconds estimates exactly 1,628,000 output bytes.
        assertFalse(needsReencode(1_915_294L, 8_000L, VideoTranscoder.Quality.SMALL))
        assertTrue(needsReencode(1_915_295L, 8_000L, VideoTranscoder.Quality.SMALL))
    }

    @Test
    fun `original never uses tier reencode policy`() {
        assertFalse(needsReencode(Long.MAX_VALUE, 1L, VideoTranscoder.Quality.ORIGINAL))
    }

    @Test
    fun `bitrate fallback derives bits per second from size and duration`() {
        assertEquals(1_500_000L, derivedSourceBitrateBps(33_750_000L, 180_000L))
        assertEquals(1_911_111L, derivedSourceBitrateBps(43_000_000L, 180_000L))
        assertEquals(null, derivedSourceBitrateBps(43_000_000L, 0L))
    }

    @Test
    fun `encode bitrate is capped below compact source total bitrate`() {
        assertEquals(
            1_125_456,
            targetVideoBitrateBps(1_606_820L, VideoTranscoder.Quality.SMALL),
        )
        assertEquals(
            VIDEO_BITRATE_480P,
            targetVideoBitrateBps(4_000_000L, VideoTranscoder.Quality.SMALL),
        )
        assertEquals(
            VIDEO_BITRATE_1080P,
            targetVideoBitrateBps(1_606_820L, VideoTranscoder.Quality.ORIGINAL),
        )
        assertEquals(
            VIDEO_BITRATE_480P,
            targetVideoBitrateBps(Long.MAX_VALUE, VideoTranscoder.Quality.SMALL),
        )
        assertEquals(
            MIN_VIDEO_BITRATE_BPS,
            targetVideoBitrateBps(200_000L, VideoTranscoder.Quality.SMALL),
        )
    }

    @Test
    fun `same-height bitrate encode uses non-no-op even presentation`() {
        assertEquals(478, forcedVideoEncodeHeight(480, 480, forceEncode = true))
        assertEquals(480, forcedVideoEncodeHeight(480, 720, forceEncode = true))
        assertEquals(480, forcedVideoEncodeHeight(480, 480, forceEncode = false))
    }

    @Test
    fun `mp4 passthrough accepts AVC HEVC and AV1`() {
        assertTrue(isCompatibleOriginalVideo("video/mp4", "video/avc"))
        assertTrue(isCompatibleOriginalVideo("video/mp4; codecs=hvc1", "video/hevc"))
        assertTrue(isCompatibleOriginalVideo("video/mp4", "video/av01"))
        assertFalse(isCompatibleOriginalVideo("video/quicktime", "video/avc"))
        assertFalse(isCompatibleOriginalVideo("video/mp4", "video/x-vnd.on2.vp9"))
        assertFalse(isCompatibleOriginalVideo(null, "video/avc"))
    }

    @Test
    fun `compatibility transcode selects smallest bitrate tier capped at high`() {
        assertEquals(VideoTranscoder.Quality.SMALL, compatibilityTranscodeQuality(1_500_000L))
        assertEquals(VideoTranscoder.Quality.STANDARD, compatibilityTranscodeQuality(1_500_001L))
        assertEquals(VideoTranscoder.Quality.HIGH, compatibilityTranscodeQuality(3_000_001L))
        assertEquals(VideoTranscoder.Quality.HIGH, compatibilityTranscodeQuality(40_000_000L))
    }

    private fun withTempMp4(moviePayload: ByteArray, assertion: (File) -> Unit) {
        val file = File.createTempFile("video-privacy-", ".mp4")
        try {
            file.writeBytes(
                box("ftyp", "isom\u0000\u0000\u0002\u0000isommp42".toByteArray()) +
                    box("moov", moviePayload) +
                    box("mdat", ByteArray(64)),
            )
            assertion(file)
        } finally {
            file.delete()
        }
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        box(type.toByteArray(Charsets.ISO_8859_1), payload)

    private fun box(type: ByteArray, payload: ByteArray): ByteArray {
        require(type.size == 4)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(8 + payload.size)
                output.write(type)
                output.write(payload)
            }
            bytes.toByteArray()
        }
    }
}
