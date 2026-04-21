package com.unsilence.app.ui.feed

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VideoThumbCache"
private const val MAX_ENTRIES = 100
private const val MAX_BITMAP_BYTES = 64L * 1024 * 1024 // 64MB
private const val DOWNSAMPLE = 2 // inSampleSize — halves each dimension → ~4x smaller bitmaps

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
 *
 * Bounded: LRU eviction at [MAX_ENTRIES] entries OR [MAX_BITMAP_BYTES] total bitmap bytes,
 * whichever hits first. URLs in [visibleUrls] are never evicted.
 * Bitmaps are downsampled via [BitmapFactory.Options.inSampleSize] = [DOWNSAMPLE].
 */
@Singleton
class VideoThumbnailCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cache = ConcurrentHashMap<String, VideoThumbnail?>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val lastAccessedAt = ConcurrentHashMap<String, Long>()

    /**
     * URLs currently bound to a visible video cell. Protected from eviction.
     * Maintained by composables via [markVisible] / [markNotVisible].
     */
    val visibleUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Aspect ratios resolved from fetched thumbnails, keyed by video URL.
     * Read by [InlineVideoPlayer] so its container matches the preview card exactly — zero jump.
     */
    val resolvedAspectRatios = ConcurrentHashMap<String, Float>()

    /** Number of cached entries (including null/failure markers). */
    val entryCount: Int get() = cache.size

    /** Total bytes consumed by cached Bitmaps. Safe from any thread. */
    val bitmapBytes: Long get() {
        var total = 0L
        for ((_, thumb) in cache) {
            if (thumb != null) total += thumb.bitmap.allocationByteCount
        }
        return total
    }

    /** Mark a URL as currently visible on screen. */
    fun markVisible(url: String) { visibleUrls.add(url) }

    /** Mark a URL as no longer visible on screen. */
    fun markNotVisible(url: String) { visibleUrls.remove(url) }

    /** Return a cached thumbnail immediately, or null if not yet fetched. No I/O. */
    fun getCached(videoUrl: String): VideoThumbnail? {
        val thumb = cache[videoUrl]
        if (thumb != null) lastAccessedAt[videoUrl] = System.nanoTime()
        return thumb
    }

    /**
     * Return a cached first-frame thumbnail for [videoUrl], or fetch it on [Dispatchers.IO].
     * Returns null immediately if another coroutine is already fetching this URL,
     * or if extraction fails.
     */
    suspend fun getThumbnail(videoUrl: String): VideoThumbnail? {
        cache[videoUrl]?.let {
            lastAccessedAt[videoUrl] = System.nanoTime()
            return it
        }
        if (inFlight.putIfAbsent(videoUrl, true) != null) return null

        return withContext(Dispatchers.IO) {
            try {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(videoUrl, HashMap<String, String>())
                    val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        val ratio = frame.width.toFloat() / frame.height
                        val downsampled = downsample(frame)
                        val thumb = VideoThumbnail(bitmap = downsampled, aspectRatio = ratio)
                        cache[videoUrl] = thumb
                        lastAccessedAt[videoUrl] = System.nanoTime()
                        resolvedAspectRatios[videoUrl] = ratio
                        evictIfNeeded()
                        thumb
                    } else {
                        inFlight.remove(videoUrl)
                        null
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                inFlight.remove(videoUrl)
                throw e
            } catch (_: Exception) {
                inFlight.remove(videoUrl)
                null
            }
        }
    }

    /**
     * Downsample a bitmap by re-encoding to JPEG and decoding with inSampleSize.
     * Preserves aspect ratio. If the source is already small, returns it as-is.
     */
    private fun downsample(source: Bitmap): Bitmap {
        if (DOWNSAMPLE <= 1) return source
        // Skip downsampling for already-small bitmaps (under 200KB)
        if (source.allocationByteCount < 200 * 1024) return source

        val baos = ByteArrayOutputStream(source.allocationByteCount / 4)
        source.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        source.recycle()

        val bytes = baos.toByteArray()
        val opts = BitmapFactory.Options().apply { inSampleSize = DOWNSAMPLE }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size) // fallback without downsample
    }

    /**
     * Evict oldest-accessed entries until both caps are satisfied.
     * Never evicts URLs in [visibleUrls].
     */
    private fun evictIfNeeded() {
        if (cache.size <= MAX_ENTRIES && bitmapBytes <= MAX_BITMAP_BYTES) return

        // Build sorted eviction candidates — oldest access first, skip visible
        val candidates = lastAccessedAt.entries
            .filter { it.key !in visibleUrls }
            .sortedBy { it.value }

        var evicted = 0
        for (entry in candidates) {
            if (cache.size <= MAX_ENTRIES * 4 / 5 && bitmapBytes <= MAX_BITMAP_BYTES * 4 / 5) break
            val url = entry.key
            val thumb = cache.remove(url)
            lastAccessedAt.remove(url)
            // Don't remove from resolvedAspectRatios — lightweight floats, needed for layout stability
            thumb?.bitmap?.recycle()
            evicted++
        }
        if (evicted > 0) {
            Log.d(TAG, "Evicted $evicted thumbnails, remaining=${cache.size} (~${bitmapBytes / (1024 * 1024)}mb)")
        }
    }
}
