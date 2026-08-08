package com.unsilence.app.data.blossom

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "VideoTranscoder"
internal const val VIDEO_BITRATE_480P = 1_500_000
internal const val VIDEO_BITRATE_720P = 3_000_000
internal const val VIDEO_BITRATE_1080P = 5_500_000
internal const val AUDIO_BITRATE_BPS = 128_000
internal const val SAVINGS_THRESHOLD = 0.85
internal const val MAX_TRANSCODED_SOURCE_BITRATE_RATIO = 0.80
internal const val OUTPUT_AUDIO_BITRATE_RESERVE_BPS = 160_000
internal const val MIN_VIDEO_BITRATE_BPS = 250_000

class OriginalVideoPrivacyException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

private class UnsafeVideoPassthroughException(
    val inspection: VideoLocationInspection,
) : IOException("Unsafe video passthrough: $inspection")

// AV1 pass-through avoids a minutes-long software decode/re-encode for already
// compact sources. This accepts reduced playback coverage on pre-A17 iOS in
// favor of preserving quality, size, and upload latency; H.264 and HEVC remain
// the broadly compatible paths.
internal val PASSTHROUGH_CODECS = setOf(
    MimeTypes.VIDEO_H264,
    MimeTypes.VIDEO_H265,
    MimeTypes.VIDEO_AV1,
)

internal fun cappedVideoHeight(requestedHeight: Int, sourceHeight: Int): Int =
    if (sourceHeight > 0) minOf(requestedHeight, sourceHeight) else requestedHeight

internal fun needsReencode(
    sourceBytes: Long,
    durationMs: Long,
    tier: VideoTranscoder.Quality,
): Boolean {
    if (tier == VideoTranscoder.Quality.ORIGINAL) return false
    if (sourceBytes <= 0L || durationMs <= 0L) return true
    val estimatedOutputBytes =
        (tier.bitrate.toDouble() + AUDIO_BITRATE_BPS) / 8.0 * (durationMs / 1_000.0)
    return estimatedOutputBytes < sourceBytes.toDouble() * SAVINGS_THRESHOLD
}

internal fun compatibilityTranscodeQuality(sourceBitrateBps: Long): VideoTranscoder.Quality =
    when {
        sourceBitrateBps <= VIDEO_BITRATE_480P -> VideoTranscoder.Quality.SMALL
        sourceBitrateBps <= VIDEO_BITRATE_720P -> VideoTranscoder.Quality.STANDARD
        else -> VideoTranscoder.Quality.HIGH
    }

internal fun derivedSourceBitrateBps(fileSizeBytes: Long, durationMs: Long): Long? {
    if (fileSizeBytes <= 0L || durationMs <= 0L) return null
    val safeBytes = fileSizeBytes.coerceAtMost(Long.MAX_VALUE / 8_000L)
    return safeBytes * 8_000L / durationMs
}

internal fun targetVideoBitrateBps(
    sourceBitrateBps: Long,
    tier: VideoTranscoder.Quality,
): Int {
    if (tier == VideoTranscoder.Quality.ORIGINAL || sourceBitrateBps == Long.MAX_VALUE) {
        return tier.bitrate
    }
    val sourceRelativeBudget =
        (sourceBitrateBps.toDouble() * MAX_TRANSCODED_SOURCE_BITRATE_RATIO).toLong() -
            OUTPUT_AUDIO_BITRATE_RESERVE_BPS
    return minOf(
        tier.bitrate.toLong(),
        sourceRelativeBudget.coerceAtLeast(MIN_VIDEO_BITRATE_BPS.toLong()),
    ).toInt()
}

internal fun forcedVideoEncodeHeight(targetHeight: Int, sourceHeight: Int, forceEncode: Boolean): Int {
    if (!forceEncode || sourceHeight <= 2 || targetHeight != sourceHeight) return targetHeight
    val evenSourceHeight = sourceHeight - sourceHeight % 2
    return (evenSourceHeight - 2).coerceAtLeast(2)
}

internal fun isCompatibleOriginalVideo(containerMime: String?, videoMime: String?): Boolean {
    val normalizedContainer = containerMime?.substringBefore(';')?.trim()?.lowercase()
    val normalizedVideo = videoMime?.substringBefore(';')?.trim()?.lowercase()
    return normalizedContainer == MimeTypes.VIDEO_MP4 &&
        normalizedVideo in PASSTHROUGH_CODECS
}

@Singleton
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoTranscoder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    enum class Quality(
        val label: String,
        val heightPx: Int,
        val bitrate: Int,
    ) {
        SMALL("480p - compact", 480, VIDEO_BITRATE_480P),
        STANDARD("720p - balanced", 720, VIDEO_BITRATE_720P),
        HIGH("1080p - high quality", 1080, VIDEO_BITRATE_1080P),
        ORIGINAL("Original - privacy-checked MP4 pass-through", 1080, VIDEO_BITRATE_1080P),
    }

    data class Result(
        val file: File,
        val mimeType: String,
        val durationMs: Long,
        val width: Int,
        val height: Int,
    )

    private data class SourceMetadata(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val bitrateBps: Long,
        val fileSizeBytes: Long,
        val containerMime: String?,
        val videoMime: String?,
    )

    /** Prepares [uri] for upload, passing through compatible originals or transcoding to H.264. */
    suspend fun transcode(
        uri: Uri,
        quality: Quality,
        onProgress: (Int) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val source = extractSourceMetadata(uri)
        val compatibleSource = isCompatibleOriginalVideo(source.containerMime, source.videoMime)
        val tierNeedsReencode = needsReencode(source.fileSizeBytes, source.durationMs, quality)
        var privacyRequiresReencode = false
        if (compatibleSource && (quality == Quality.ORIGINAL || !tierNeedsReencode)) {
            try {
                return@withContext copyOriginal(uri, source)
            } catch (unsafe: UnsafeVideoPassthroughException) {
                when (
                    videoPassthroughPrivacyAction(
                        inspection = unsafe.inspection,
                        originalRequested = quality == Quality.ORIGINAL,
                    )
                ) {
                    VideoPassthroughPrivacyAction.REFUSE ->
                        throw unsafe.asOriginalPrivacyException()
                    VideoPassthroughPrivacyAction.TRANSCODE -> {
                        // Privacy overrides the normal "not enough byte
                        // savings" passthrough for lower quality tiers.
                        privacyRequiresReencode = true
                        Log.w(
                            TAG,
                            "Video passthrough rejected by privacy inspection; " +
                                "forcing transcode (${unsafe.inspection})",
                        )
                    }
                    VideoPassthroughPrivacyAction.ALLOW ->
                        error("Unsafe passthrough exception reported a clean video")
                }
            }
        }

        val transcodeQuality = if (quality == Quality.ORIGINAL || !tierNeedsReencode) {
            compatibilityTranscodeQuality(source.bitrateBps)
        } else {
            quality
        }
        val targetHeight = cappedVideoHeight(transcodeQuality.heightPx, source.height)
        // Media3 1.5.1 has no explicit force-transcode API. If bitrate alone
        // requires an encode, an unchanged Presentation can be optimized to a
        // transmux. Removing two pixels (while keeping an even H.264 height)
        // makes the video effect non-no-op and guarantees encoder creation.
        val encodeHeight = forcedVideoEncodeHeight(
            targetHeight = targetHeight,
            sourceHeight = source.height,
            forceEncode = tierNeedsReencode || privacyRequiresReencode,
        )
        // A resolution or compatibility transcode must not spend more bits than
        // the compact source. Keep room for Transformer's AAC output so the
        // complete MP4 is materially smaller instead of merely resizing pixels.
        val targetVideoBitrate = targetVideoBitrateBps(source.bitrateBps, transcodeQuality)
        val outFile = createTempFile("transcode", ".mp4")

        val mediaItem = MediaItem.fromUri(uri)
        val effects = Effects(
            /* audioProcessors = */ emptyList(),
            /* videoEffects = */ listOf(
                Presentation.createForHeight(encodeHeight),
            ),
        )
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(effects)
            .build()

        // Transformer must be built and started on Main (Looper requirement)
        val transcoded = withContext(Dispatchers.Main) {
            val encoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(targetVideoBitrate)
                .build()
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(encoderSettings)
                .setEnableFallback(true)
                .build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory)
                // Transformer otherwise forwards source container metadata to
                // FrameworkMuxer, including the ISO-6709 location atom. The
                // in-app muxer's metadata provider is the supported filtering
                // seam. Drop all source-level metadata; video orientation is
                // written separately from the output track format by
                // InAppMuxer and therefore remains intact.
                .setMuxerFactory(
                    InAppMuxer.Factory.Builder()
                        .setMetadataProvider { metadataEntries -> metadataEntries.clear() }
                        .build(),
                )
                .build()

            suspendCancellableCoroutine { cont ->
                val progressHandler = Handler(Looper.getMainLooper())
                val progressHolder = ProgressHolder()
                var polling = true
                val progressPoll = object : Runnable {
                    override fun run() {
                        if (!polling) return
                        if (transformer.getProgress(progressHolder) ==
                            Transformer.PROGRESS_STATE_AVAILABLE
                        ) {
                            onProgress(progressHolder.progress.coerceIn(0, 100))
                        }
                        progressHandler.postDelayed(this, 500L)
                    }
                }
                fun stopProgressPolling() {
                    polling = false
                    progressHandler.removeCallbacks(progressPoll)
                }
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        stopProgressPolling()
                        if (!cont.isActive) {
                            outFile.delete()
                            return
                        }
                        onProgress(100)
                        val videoWasTranscoded = exportResult.videoConversionProcess ==
                            ExportResult.CONVERSION_PROCESS_TRANSCODED ||
                            exportResult.videoConversionProcess ==
                            ExportResult.CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED
                        if (!videoWasTranscoded) {
                            outFile.delete()
                            cont.resumeWithException(
                                IllegalStateException(
                                    "Video export did not create an encoder " +
                                        "(conversion=${exportResult.videoConversionProcess})",
                                ),
                            )
                            return
                        }

                        // ExportResult.width/height is the ENCODED buffer dimensions,
                        // which doesn't account for rotation. Read the output file's
                        // actual display dimensions so portrait video reports portrait
                        // imeta dim, not the underlying landscape buffer.
                        val display = extractDisplayDimensions(outFile)
                        val (w, h) = display ?: run {
                            // Fallback: use ExportResult, accept that rotated video
                            // may have wrong aspect in imeta.
                            Log.w(TAG, "Display dimension extraction failed, using encoded dimensions")
                            val ew = exportResult.width.takeIf { it > 0 } ?: encodeHeight
                            val eh = exportResult.height.takeIf { it > 0 } ?: encodeHeight
                            ew to eh
                        }

                        cont.resume(Result(
                            file = outFile,
                            mimeType = "video/mp4",
                            durationMs = source.durationMs,
                            width = w,
                            height = h,
                        ))
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        stopProgressPolling()
                        if (!cont.isActive) {
                            outFile.delete()
                            return
                        }
                        Log.e(TAG, "Transcode failed", exportException)
                        outFile.delete()
                        cont.resumeWithException(exportException)
                    }
                }

                transformer.addListener(listener)
                transformer.start(editedMediaItem, outFile.absolutePath)
                progressHandler.post(progressPoll)

                cont.invokeOnCancellation {
                    stopProgressPolling()
                    transformer.cancel()
                    outFile.delete()
                }
            }
        }
        when (val inspection = inspectPreparedVideoLocation(transcoded.file)) {
            VideoLocationInspection.CLEAN -> transcoded
            VideoLocationInspection.LOCATION_PRESENT,
            VideoLocationInspection.INDETERMINATE,
            -> {
                transcoded.file.delete()
                throw OriginalVideoPrivacyException(
                    "Video could not be prepared without private location metadata " +
                        "($inspection).",
                )
            }
        }
    }

    private fun copyOriginal(uri: Uri, source: SourceMetadata): Result {
        val output = createTempFile("original", ".mp4")
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                output.outputStream().buffered().use { sink -> stream.copyTo(sink) }
            } ?: error("Could not read video")
            val inspection = inspectPreparedVideoLocation(output)
            if (inspection != VideoLocationInspection.CLEAN) {
                throw UnsafeVideoPassthroughException(inspection)
            }
            val dimensions = when {
                source.width > 0 && source.height > 0 -> source.width to source.height
                else -> extractDisplayDimensions(output) ?: (0 to 0)
            }
            return Result(
                file = output,
                mimeType = MimeTypes.VIDEO_MP4,
                durationMs = source.durationMs,
                width = dimensions.first,
                height = dimensions.second,
            )
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    private fun extractSourceMetadata(uri: Uri): SourceMetadata {
        var durationMs = 0L
        var width = 0
        var height = 0
        var retrieverBitrateBps: Long? = null
        var retrieverMime: String? = null
        try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val encodedWidth = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val encodedHeight = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) {
                    width = encodedHeight
                    height = encodedWidth
                } else {
                    width = encodedWidth
                    height = encodedHeight
                }
                retrieverMime = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                retrieverBitrateBps = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not extract source metadata", error)
        }

        val providerMime = context.contentResolver.getType(uri)
        val containerMime = listOf(providerMime, retrieverMime)
            .firstOrNull { it?.substringBefore(';')?.trim()?.lowercase() == MimeTypes.VIDEO_MP4 }
            ?: retrieverMime
            ?: providerMime
        val fileSizeBytes = extractSourceFileSize(uri)
        val bitrateBps = retrieverBitrateBps
            ?: derivedSourceBitrateBps(fileSizeBytes, durationMs)
            ?: Long.MAX_VALUE.also {
                Log.w(
                    TAG,
                    "Source bitrate unavailable; forcing encode " +
                        "(size=$fileSizeBytes, duration=$durationMs)",
                )
            }

        return SourceMetadata(
            durationMs = durationMs,
            width = width,
            height = height,
            bitrateBps = bitrateBps,
            fileSizeBytes = fileSizeBytes,
            containerMime = containerMime,
            videoMime = extractVideoTrackMime(uri),
        )
    }

    private fun inspectPreparedVideoLocation(file: File): VideoLocationInspection {
        val platformLocation = runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            }
        }.onFailure { error ->
            Log.w(TAG, "Platform video location inspection failed; checking ISO metadata", error)
        }.getOrNull()
        if (!platformLocation.isNullOrBlank()) {
            return VideoLocationInspection.LOCATION_PRESENT
        }
        return inspectIsoBmffLocationMetadata(file)
    }

    private fun extractSourceFileSize(uri: Uri): Long {
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0L }
            }
        }.getOrNull()?.let { return it }

        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it > 0L }
            }
        }.getOrNull()?.let { return it }

        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun extractVideoTrackMime(uri: Uri): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount)
                .asSequence()
                .mapNotNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                }
                .firstOrNull { mime -> mime.startsWith("video/") }
        } catch (error: Exception) {
            Log.w(TAG, "Could not inspect source video track", error)
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * Reads the output file's width, height, and rotation metadata
     * and returns the display dimensions — i.e. what a player
     * would actually show, accounting for the rotation tag.
     *
     * H.264 video buffers are always encoded in landscape orientation.
     * Phone-recorded portrait video is stored as a landscape buffer
     * (e.g. 1920x1080) with rotation = 90°, and the player rotates
     * for display. ExportResult.width/height reports the encoded
     * buffer dimensions, NOT the display dimensions — which is wrong
     * for NIP-92 imeta where readers expect display (post-rotation)
     * aspect.
     *
     * Returns (displayWidth, displayHeight) or null if metadata
     * extraction failed.
     */
    private fun extractDisplayDimensions(file: File): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
            val h = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            if (w == null || h == null || w <= 0 || h <= 0) return null

            when (rotation) {
                90, 270 -> h to w   // swap for portrait
                else    -> w to h   // 0 or 180 — encoded matches display
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractDisplayDimensions failed", e)
            null
        } finally {
            retriever.release()
        }
    }

    private fun createTempFile(prefix: String, suffix: String): File {
        val dir = File(context.cacheDir, "video_transcode")
        dir.mkdirs()
        return File.createTempFile(prefix, suffix, dir)
    }
}

private fun UnsafeVideoPassthroughException.asOriginalPrivacyException():
    OriginalVideoPrivacyException = when (inspection) {
    VideoLocationInspection.LOCATION_PRESENT -> OriginalVideoPrivacyException(
        "Original quality is unavailable because this video contains location data. " +
            "Choose High quality to remove it.",
        this,
    )
    VideoLocationInspection.INDETERMINATE -> OriginalVideoPrivacyException(
        "Original quality is unavailable because this video's location metadata " +
            "could not be checked safely. Choose High quality to remove metadata.",
        this,
    )
    VideoLocationInspection.CLEAN -> OriginalVideoPrivacyException(
        "Original video privacy inspection failed.",
        this,
    )
}
