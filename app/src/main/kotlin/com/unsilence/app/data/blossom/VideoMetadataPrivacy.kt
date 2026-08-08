package com.unsilence.app.data.blossom

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

internal const val MAX_VIDEO_METADATA_SCAN_BYTES = 32L * 1024L * 1024L
private const val ISO_BOX_HEADER_BYTES = 8L
private const val ISO_EXTENDED_BOX_HEADER_BYTES = 16L
private const val SCAN_BUFFER_BYTES = 16 * 1024

internal enum class VideoLocationInspection {
    CLEAN,
    LOCATION_PRESENT,
    INDETERMINATE,
}

internal enum class VideoPassthroughPrivacyAction {
    ALLOW,
    TRANSCODE,
    REFUSE,
}

internal fun videoPassthroughPrivacyAction(
    inspection: VideoLocationInspection,
    originalRequested: Boolean,
): VideoPassthroughPrivacyAction = when (inspection) {
    VideoLocationInspection.CLEAN -> VideoPassthroughPrivacyAction.ALLOW
    VideoLocationInspection.LOCATION_PRESENT,
    VideoLocationInspection.INDETERMINATE,
    -> if (originalRequested) {
        VideoPassthroughPrivacyAction.REFUSE
    } else {
        VideoPassthroughPrivacyAction.TRANSCODE
    }
}

/**
 * Inspects ISO-BMFF metadata without decoding media payloads. Top-level mdat
 * bytes are skipped, so the work is bounded by container metadata rather than
 * video length. Any malformed structure fails closed as INDETERMINATE.
 */
internal fun inspectIsoBmffLocationMetadata(
    file: File,
    scanLimitBytes: Long = MAX_VIDEO_METADATA_SCAN_BYTES,
): VideoLocationInspection {
    if (scanLimitBytes <= 0L || file.length() < ISO_BOX_HEADER_BYTES) {
        return VideoLocationInspection.INDETERMINATE
    }

    return runCatching {
        RandomAccessFile(file, "r").use { source ->
            val fileLength = source.length()
            var position = 0L
            var scannedBytes = 0L
            var sawBox = false
            var sawMovieMetadata = false

            while (position < fileLength) {
                if (fileLength - position < ISO_BOX_HEADER_BYTES) {
                    return@use VideoLocationInspection.INDETERMINATE
                }
                source.seek(position)
                val compactSize = source.readInt().toLong() and 0xffff_ffffL
                val type = ByteArray(4).also(source::readFully)
                var headerSize = ISO_BOX_HEADER_BYTES
                val boxSize = when (compactSize) {
                    0L -> fileLength - position
                    1L -> {
                        if (fileLength - position < ISO_EXTENDED_BOX_HEADER_BYTES) {
                            return@use VideoLocationInspection.INDETERMINATE
                        }
                        headerSize = ISO_EXTENDED_BOX_HEADER_BYTES
                        source.readLong().takeIf { it >= ISO_EXTENDED_BOX_HEADER_BYTES }
                            ?: return@use VideoLocationInspection.INDETERMINATE
                    }
                    else -> compactSize
                }
                if (boxSize < headerSize || boxSize > fileLength - position) {
                    return@use VideoLocationInspection.INDETERMINATE
                }

                sawBox = true
                if (type.contentEquals(TYPE_MOOV)) sawMovieMetadata = true
                if (type.matchesLocationAtomType()) {
                    return@use VideoLocationInspection.LOCATION_PRESENT
                }

                val payloadSize = boxSize - headerSize
                if (type.shouldScanMetadataPayload() && payloadSize > 0L) {
                    if (payloadSize > scanLimitBytes - scannedBytes) {
                        return@use VideoLocationInspection.INDETERMINATE
                    }
                    source.seek(position + headerSize)
                    if (source.rangeContainsLocationPattern(payloadSize)) {
                        return@use VideoLocationInspection.LOCATION_PRESENT
                    }
                    scannedBytes += payloadSize
                }

                position += boxSize
            }

            if (sawBox && sawMovieMetadata && position == fileLength) {
                VideoLocationInspection.CLEAN
            } else {
                VideoLocationInspection.INDETERMINATE
            }
        }
    }.getOrDefault(VideoLocationInspection.INDETERMINATE)
}

private fun ByteArray.matchesLocationAtomType(): Boolean =
    contentEquals(TYPE_COPYRIGHT_XYZ) || contentEquals(TYPE_AT_XYZ)

private fun ByteArray.shouldScanMetadataPayload(): Boolean =
    !contentEquals(TYPE_MDAT) &&
        !contentEquals(TYPE_FREE) &&
        !contentEquals(TYPE_SKIP) &&
        !contentEquals(TYPE_WIDE) &&
        !contentEquals(TYPE_FTYP)

private fun RandomAccessFile.rangeContainsLocationPattern(byteCount: Long): Boolean {
    val maxPatternBytes = LOCATION_PATTERNS.maxOf(ByteArray::size)
    val buffer = ByteArray(SCAN_BUFFER_BYTES)
    var remaining = byteCount
    var carry = ByteArray(0)
    while (remaining > 0L) {
        val read = read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
        check(read > 0) { "Truncated ISO-BMFF metadata" }
        val window = ByteArray(carry.size + read)
        carry.copyInto(window)
        buffer.copyInto(window, destinationOffset = carry.size, endIndex = read)
        if (LOCATION_PATTERNS.any { pattern -> window.indexOf(pattern) >= 0 }) return true

        val carrySize = min(maxPatternBytes - 1, window.size)
        carry = window.copyOfRange(window.size - carrySize, window.size)
        remaining -= read
    }
    return false
}

private fun ByteArray.indexOf(pattern: ByteArray): Int {
    if (pattern.isEmpty()) return 0
    if (pattern.size > size) return -1
    for (start in 0..size - pattern.size) {
        var matches = true
        for (offset in pattern.indices) {
            if (this[start + offset] != pattern[offset]) {
                matches = false
                break
            }
        }
        if (matches) return start
    }
    return -1
}

private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

private val TYPE_MOOV = ascii("moov")
private val TYPE_MDAT = ascii("mdat")
private val TYPE_FREE = ascii("free")
private val TYPE_SKIP = ascii("skip")
private val TYPE_WIDE = ascii("wide")
private val TYPE_FTYP = ascii("ftyp")
private val TYPE_COPYRIGHT_XYZ = byteArrayOf(0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte())
private val TYPE_AT_XYZ = ascii("@xyz")

private val LOCATION_PATTERNS: List<ByteArray> = listOf(
    TYPE_COPYRIGHT_XYZ,
    TYPE_AT_XYZ,
    "©xyz".toByteArray(Charsets.UTF_8),
    ascii("com.apple.quicktime.location.ISO6709"),
    ascii("com.apple.quicktime.location.accuracy.horizontal"),
    ascii("exif:GPSLatitude"),
    ascii("exif:GPSLongitude"),
    ascii("GPSLatitude"),
    ascii("GPSLongitude"),
)
