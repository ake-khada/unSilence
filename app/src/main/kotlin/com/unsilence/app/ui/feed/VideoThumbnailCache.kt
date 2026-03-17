package com.unsilence.app.ui.feed

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First-frame thumbnail with its native aspect ratio.
 */
data class VideoThumbnail(
    val bitmap: Bitmap,
    val aspectRatio: Float,  // width / height
)

/**
 * In-memory cache of video first-frame thumbnails extracted via [MediaMetadataRetriever].
 *
 * [MediaMetadataRetriever.setDataSource] with a URL uses HTTP range requests — it fetches
 * ONLY the video headers (moov atom) and first keyframe, typically a few hundred KB,
 * NOT the entire file. This is lightweight enough for scrolling lists.
 *
 * Each URL is fetched at most once; the result (including null on failure) is cached.
 */
@Singleton
class VideoThumbnailCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cache = ConcurrentHashMap<String, VideoThumbnail?>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    /**
     * Aspect ratios resolved from fetched thumbnails, keyed by video URL.
     * Read by [InlineVideoPlayer] so its container matches the preview card exactly — zero jump.
     */
    val resolvedAspectRatios = ConcurrentHashMap<String, Float>()

    /**
     * Return a cached first-frame thumbnail for [videoUrl], or fetch it on [Dispatchers.IO].
     * Returns null immediately if another coroutine is already fetching this URL,
     * or if extraction fails.
     */
    suspend fun getThumbnail(videoUrl: String): VideoThumbnail? {
        cache[videoUrl]?.let { return it }
        if (inFlight.putIfAbsent(videoUrl, true) != null) return null

        return withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(videoUrl, HashMap<String, String>())
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                frame?.let {
                    val ratio = it.width.toFloat() / it.height
                    val thumb = VideoThumbnail(
                        bitmap = it,
                        aspectRatio = ratio,
                    )
                    cache[videoUrl] = thumb
                    resolvedAspectRatios[videoUrl] = ratio
                    thumb
                }
            } catch (_: Exception) {
                inFlight.remove(videoUrl)
                null
            }
        }
    }
}
