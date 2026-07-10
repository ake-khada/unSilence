package com.unsilence.app.data.blossom

/**
 * Per-attachment upload quality. Maps to ImageCompressor and
 * VideoTranscoder settings. ORIGINAL preserves images and passes through
 * compatible H.264/HEVC MP4 videos.
 */
enum class AttachmentQuality {
    SMALL,
    STANDARD,
    HIGH,
    ORIGINAL,
    ;

    /** (maxDimension, outputQuality) for the image quality ladder. */
    fun imageSettings(): Pair<Int, Int> = when (this) {
        SMALL    -> 1280 to 78
        STANDARD -> 2048 to 82
        HIGH     -> 2560 to 87
        ORIGINAL -> 0 to 100
    }

    /** Maps directly to the video quality ladder. */
    fun videoQuality(): VideoTranscoder.Quality = when (this) {
        SMALL    -> VideoTranscoder.Quality.SMALL
        STANDARD -> VideoTranscoder.Quality.STANDARD
        HIGH     -> VideoTranscoder.Quality.HIGH
        ORIGINAL -> VideoTranscoder.Quality.ORIGINAL
    }

    /** Rough output-size multiplier vs. original. Used for display estimates only. */
    fun sizeFactor(): Double = when (this) {
        SMALL    -> 0.15
        STANDARD -> 0.30
        HIGH     -> 0.55
        ORIGINAL -> 1.0
    }

    fun displayLabel(): String = when (this) {
        SMALL    -> "Small"
        STANDARD -> "Standard"
        HIGH     -> "High"
        ORIGINAL -> "Original"
    }

    fun imageSettingsLabel(): String = when (this) {
        SMALL -> "Small - 1280 px / q78"
        STANDARD -> "Standard - 2048 px / q82"
        HIGH -> "High - 2560 px / q87"
        ORIGINAL -> "Original - untouched"
    }

    companion object {
        fun fromImageMaxDimension(maxDimension: Int): AttachmentQuality = when {
            maxDimension == 0 -> ORIGINAL
            maxDimension <= 1280 -> SMALL
            maxDimension <= 2048 -> STANDARD
            else -> HIGH
        }
    }
}
