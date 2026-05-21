package com.unsilence.app.data.blossom

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
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

@Singleton
class VideoTranscoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class Quality(val label: String, val heightPx: Int) {
        SMALL("360p — smallest files", 360),
        STANDARD("480p — fast uploads", 480),
        HIGH("720p — high quality", 720),
    }

    data class Result(
        val file: File,
        val mimeType: String,
        val durationMs: Long,
        val width: Int,
        val height: Int,
    )

    /**
     * Transcode [uri] to H.264/AAC MP4 at the given [quality].
     */
    suspend fun transcode(uri: Uri, quality: Quality): Result = withContext(Dispatchers.IO) {
        val durationMs = extractDuration(uri)
        val outFile = createTempFile("transcode", ".mp4")

        val mediaItem = MediaItem.fromUri(uri)
        val effects = Effects(
            /* audioProcessors = */ emptyList(),
            /* videoEffects = */ listOf(
                Presentation.createForHeight(quality.heightPx),
            ),
        )
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(effects)
            .build()

        // Transformer must be built and started on Main (Looper requirement)
        withContext(Dispatchers.Main) {
            val transformer = Transformer.Builder(context).build()

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
                            val ew = exportResult.width.takeIf { it > 0 } ?: quality.heightPx
                            val eh = exportResult.height.takeIf { it > 0 } ?: quality.heightPx
                            ew to eh
                        }

                        Log.d(TAG, "Transcode complete: ${outFile.length()} bytes, " +
                            "encoded ${exportResult.width}x${exportResult.height}, " +
                            "display ${w}x${h}, ${durationMs}ms")

                        cont.resume(Result(
                            file = outFile,
                            mimeType = "video/mp4",
                            durationMs = durationMs,
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

    private fun extractDuration(uri: Uri): Long {
        return try {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, uri)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract duration", e)
            0L
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
