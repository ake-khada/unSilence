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
    private val cache = ConcurrentHashMap<String, Bitmap?>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    /**
     * Return a cached first-frame bitmap for [videoUrl], or fetch it on [Dispatchers.IO].
     * Returns null immediately if another coroutine is already fetching this URL,
     * or if extraction fails.
     */
    suspend fun getThumbnail(videoUrl: String): Bitmap? {
        cache[videoUrl]?.let { return it }
        if (inFlight.putIfAbsent(videoUrl, true) != null) return null

        return withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(videoUrl, HashMap<String, String>())
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                frame?.also { cache[videoUrl] = it }
            } catch (_: Exception) {
                inFlight.remove(videoUrl)
                null
            }
        }
    }
}
