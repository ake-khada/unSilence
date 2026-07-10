package com.unsilence.app.data.blossom

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "VideoTranscoder"
internal const val VIDEO_BITRATE_480P = 1_500_000
internal const val VIDEO_BITRATE_720P = 3_000_000
internal const val VIDEO_BITRATE_1080P = 5_500_000

internal fun cappedVideoHeight(requestedHeight: Int, sourceHeight: Int): Int =
    if (sourceHeight > 0) minOf(requestedHeight, sourceHeight) else requestedHeight

internal fun isCompatibleOriginalVideo(containerMime: String?, videoMime: String?): Boolean {
    val normalizedContainer = containerMime?.substringBefore(';')?.trim()?.lowercase()
    val normalizedVideo = videoMime?.substringBefore(';')?.trim()?.lowercase()
    return normalizedContainer == MimeTypes.VIDEO_MP4 &&
        normalizedVideo in setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
}

@Singleton
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoTranscoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class Quality(
        val label: String,
        val heightPx: Int,
        val bitrate: Int,
    ) {
        SMALL("480p - compact", 480, VIDEO_BITRATE_480P),
        STANDARD("720p - balanced", 720, VIDEO_BITRATE_720P),
        HIGH("1080p - high quality", 1080, VIDEO_BITRATE_1080P),
        ORIGINAL("Original - compatible MP4 pass-through", 1080, VIDEO_BITRATE_1080P),
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
        val containerMime: String?,
        val videoMime: String?,
    )

    /** Prepares [uri] for upload, passing through compatible originals or transcoding to H.264. */
    suspend fun transcode(uri: Uri, quality: Quality): Result = withContext(Dispatchers.IO) {
        val source = extractSourceMetadata(uri)
        if (quality == Quality.ORIGINAL &&
            isCompatibleOriginalVideo(source.containerMime, source.videoMime)
        ) {
            return@withContext copyOriginal(uri, source)
        }

        if (quality == Quality.ORIGINAL) {
            Log.d(
                TAG,
                "Original source requires compatibility transcode: " +
                    "container=${source.containerMime}, video=${source.videoMime}",
            )
        }

        val targetHeight = cappedVideoHeight(quality.heightPx, source.height)
        val outFile = createTempFile("transcode", ".mp4")

        val mediaItem = MediaItem.fromUri(uri)
        val effects = Effects(
            /* audioProcessors = */ emptyList(),
            /* videoEffects = */ listOf(
                Presentation.createForHeight(targetHeight),
            ),
        )
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(effects)
            .build()

        // Transformer must be built and started on Main (Looper requirement)
        withContext(Dispatchers.Main) {
            val encoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(quality.bitrate)
                .build()
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(encoderSettings)
                .setEnableFallback(true)
                .build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory)
                .build()

            suspendCancellableCoroutine { cont ->
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        // ExportResult.width/height is the ENCODED buffer dimensions,
                        // which doesn't account for rotation. Read the output file's
                        // actual display dimensions so portrait video reports portrait
                        // imeta dim, not the underlying landscape buffer.
                        val display = extractDisplayDimensions(outFile)
                        val (w, h) = display ?: run {
                            // Fallback: use ExportResult, accept that rotated video
                            // may have wrong aspect in imeta.
                            Log.w(TAG, "Display dimension extraction failed, using encoded dimensions")
                            val ew = exportResult.width.takeIf { it > 0 } ?: targetHeight
                            val eh = exportResult.height.takeIf { it > 0 } ?: targetHeight
                            ew to eh
                        }

                        Log.d(TAG, "Transcode complete: ${outFile.length()} bytes, " +
                            "encoded ${exportResult.width}x${exportResult.height}, " +
                            "display ${w}x${h}, ${source.durationMs}ms, " +
                            "target ${targetHeight}p @ ${quality.bitrate}bps")

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
                        Log.e(TAG, "Transcode failed", exportException)
                        outFile.delete()
                        cont.resumeWithException(exportException)
                    }
                }

                transformer.addListener(listener)
                transformer.start(editedMediaItem, outFile.absolutePath)

                cont.invokeOnCancellation {
                    transformer.cancel()
                    outFile.delete()
                }
            }
        }
    }

    private fun copyOriginal(uri: Uri, source: SourceMetadata): Result {
        val output = createTempFile("original", ".mp4")
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                output.outputStream().buffered().use { sink -> stream.copyTo(sink) }
            } ?: error("Could not read video")
            val dimensions = when {
                source.width > 0 && source.height > 0 -> source.width to source.height
                else -> extractDisplayDimensions(output) ?: (0 to 0)
            }
            Log.d(TAG, "Original MP4 pass-through: ${output.length()} bytes")
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
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not extract source metadata", error)
        }

        val providerMime = context.contentResolver.getType(uri)
        val containerMime = listOf(providerMime, retrieverMime)
            .firstOrNull { it?.substringBefore(';')?.trim()?.lowercase() == MimeTypes.VIDEO_MP4 }
            ?: retrieverMime
            ?: providerMime

        return SourceMetadata(
            durationMs = durationMs,
            width = width,
            height = height,
            containerMime = containerMime,
            videoMime = extractVideoTrackMime(uri),
        )
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
