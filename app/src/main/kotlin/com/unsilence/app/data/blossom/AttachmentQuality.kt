package com.unsilence.app.data.blossom

/**
 * Per-attachment upload quality. Maps to ImageCompressor and
 * VideoTranscoder settings. ORIGINAL is image-only — videos
 * always transcode for cross-client compatibility.
 */
enum class AttachmentQuality {
    SMALL,
    STANDARD,
    HIGH,
    ORIGINAL,
    ;

    /** (maxDimension, jpegQuality) for image compression. Matches Settings slider steps. */
    fun imageSettings(): Pair<Int, Int> = when (this) {
        SMALL    -> 1024 to 75
        STANDARD -> 1600 to 85
        HIGH     -> 2048 to 90
        ORIGINAL -> 0 to 100
    }

    /** Maps to VideoTranscoder.Quality. ORIGINAL falls back to STANDARD. */
    fun videoQuality(): VideoTranscoder.Quality = when (this) {
        SMALL    -> VideoTranscoder.Quality.SMALL
        STANDARD -> VideoTranscoder.Quality.STANDARD
        HIGH     -> VideoTranscoder.Quality.HIGH
        ORIGINAL -> VideoTranscoder.Quality.STANDARD
    }

    /** Rough output-size multiplier vs. original. Used for display estimates only. */
    fun sizeFactor(): Double = when (this) {
        SMALL    -> 0.10
        STANDARD -> 0.25
        HIGH     -> 0.50
        ORIGINAL -> 1.0
    }

    fun displayLabel(): String = when (this) {
        SMALL    -> "Small"
        STANDARD -> "Standard"
        HIGH     -> "High"
        ORIGINAL -> "Original"
    }
}
