package com.unsilence.app.ui.feed

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.unsilence.app.data.media.GuardedRemoteMediaDataSource
import com.unsilence.app.data.network.parseAllowedUntrustedHttpUrl
import com.unsilence.app.di.MediaClient
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VideoThumbCache"
private const val MAX_ENTRIES = 30
private const val MAX_BITMAP_BYTES = 48L * 1024 * 1024 // 48MB
private const val DOWNSAMPLE = 2 // dst-dimension divisor — halves each dimension → ~4x fewer pixels
private const val REMOTE_MMR_CONCURRENCY = 3

/**
 * First-frame thumbnail with its native aspect ratio.
 */
data class VideoThumbnail(
    val bitmap: Bitmap,
    val aspectRatio: Float,  // width / height
)

internal sealed interface VideoThumbnailInput {
    data class Remote(val url: HttpUrl) : VideoThumbnailInput
    data class LocalUri(val raw: String) : VideoThumbnailInput
    data class LocalPath(val raw: String) : VideoThumbnailInput
    data object Rejected : VideoThumbnailInput
}

/** Classify without resolving DNS. Remote inputs are admitted only through the
 *  same cheap URL policy whose per-hop backstop lives on [MediaClient]. */
internal fun classifyVideoThumbnailInput(raw: String): VideoThumbnailInput {
    val value = raw.trim()
    if (value.isEmpty() || value.startsWith("//")) return VideoThumbnailInput.Rejected
    if (value.startsWith('/')) return VideoThumbnailInput.LocalPath(value)

    return when (value.substringBefore(':', missingDelimiterValue = "").lowercase()) {
        "content", "file", "android.resource" -> VideoThumbnailInput.LocalUri(value)
        "http", "https" -> parseAllowedUntrustedHttpUrl(value)
            ?.let(VideoThumbnailInput::Remote)
            ?: VideoThumbnailInput.Rejected
        else -> VideoThumbnailInput.Rejected
    }
}

internal fun interface VideoThumbnailFrameExtractor {
    fun extract(input: VideoThumbnailInput): VideoThumbnail?
}

/**
 * In-memory cache of video first-frame thumbnails extracted via [MediaMetadataRetriever].
 *
 * Remote extraction gives [MediaMetadataRetriever] a bounded [android.media.MediaDataSource]
 * backed by the guarded media client. The platform never receives the attacker-controlled URL.
 *
 * Each URL is fetched at most once; the result (including null on failure) is cached.
 *
 * Bounded: LRU eviction at [MAX_ENTRIES] entries OR [MAX_BITMAP_BYTES] total bitmap bytes,
 * whichever hits first. URLs in [visibleUrls] are never evicted.
 * Bitmaps are decoded at half source dimensions via [MediaMetadataRetriever.getScaledFrameAtTime]
 * (API 27+) or [Bitmap.createScaledBitmap] fallback — no JPEG round-trip.
 */
@Singleton
@androidx.compose.runtime.Stable
class VideoThumbnailCache private constructor(
    private val frameExtractor: VideoThumbnailFrameExtractor,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @MediaClient mediaClient: OkHttpClient,
    ) : this(PlatformVideoThumbnailFrameExtractor(context, mediaClient))

    internal constructor(extractFrame: (VideoThumbnailInput) -> VideoThumbnail?) :
        this(VideoThumbnailFrameExtractor(extractFrame))

    private val cache = ConcurrentHashMap<String, VideoThumbnail?>()
    private val failedUrls = ConcurrentHashMap.newKeySet<String>() // negative cache — skip re-stall
    private val extractionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<VideoThumbnail?>>()
    private val lastAccessedAt = ConcurrentHashMap<String, Long>()
    private val remoteMmrSemaphore = kotlinx.coroutines.sync.Semaphore(REMOTE_MMR_CONCURRENCY)

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

    /**
     * Pre-warm a thumbnail in the background. No-op if already cached or in-flight.
     * Called by TimelineConsumer's warm-zone hydration before the card is visible.
     */
    suspend fun warmThumbnail(url: String) {
        if (cache.containsKey(url) || url in failedUrls) return
        thumbnailJob(url).await()
    }

    /** Return a cached thumbnail immediately, or null if not yet fetched. No I/O. */
    fun getCached(videoUrl: String): VideoThumbnail? {
        val thumb = cache[videoUrl]
        if (thumb != null) lastAccessedAt[videoUrl] = System.nanoTime()
        return thumb
    }

    /**
     * Return a cached first-frame thumbnail for [videoUrl], or fetch it on [Dispatchers.IO].
     * If another coroutine is already fetching this URL, await that shared job
     * so visible cards repaint when a pre-viewport warm completes.
     */
    suspend fun getThumbnail(videoUrl: String): VideoThumbnail? {
        cache[videoUrl]?.let {
            lastAccessedAt[videoUrl] = System.nanoTime()
            return it
        }
        // Negative cache: skip URLs that already failed (DNS-blocked, timeout)
        if (videoUrl in failedUrls) {
            return null
        }

        return thumbnailJob(videoUrl).await()?.also {
            lastAccessedAt[videoUrl] = System.nanoTime()
        }
    }

    private fun thumbnailJob(videoUrl: String): Deferred<VideoThumbnail?> {
        val existing = inFlight[videoUrl]
        if (existing != null) {
            return existing
        }

        val deferred = extractionScope.async(start = CoroutineStart.LAZY) {
            extractThumbnail(videoUrl)
        }
        val raced = inFlight.putIfAbsent(videoUrl, deferred)
        if (raced != null) {
            deferred.cancel()
            return raced
        }
        deferred.start()
        deferred.invokeOnCompletion {
            inFlight.remove(videoUrl, deferred)
        }
        return deferred
    }

    private suspend fun extractThumbnail(videoUrl: String): VideoThumbnail? {
        cache[videoUrl]?.let { return it }
        if (videoUrl in failedUrls) return null
        val input = classifyVideoThumbnailInput(videoUrl)
        if (input is VideoThumbnailInput.Rejected) {
            failedUrls.add(videoUrl)
            return null
        }
        val isRemote = input is VideoThumbnailInput.Remote

        return withContext(Dispatchers.IO) {
            try {
                if (isRemote) {
                    // Remote MMR: bound parallel extraction. The timeout
                    // applies to extraction, not time spent queued behind an
                    // earlier prewarm, so look-ahead jobs do not poison the
                    // negative cache before they get a turn.
                    remoteMmrSemaphore.acquire()
                    val result = try {
                        withTimeoutOrNull(8_000L) { frameExtractor.extract(input) }
                    } finally {
                        remoteMmrSemaphore.release()
                    }
                    if (result == null) { failedUrls.add(videoUrl) }
                    result?.also { rememberThumbnail(videoUrl, it) }
                } else {
                    // Local file: no concurrency limit, no timeout
                    frameExtractor.extract(input)?.also { rememberThumbnail(videoUrl, it) }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isRemote) failedUrls.add(videoUrl)
                null
            }
        }
    }

    private fun rememberThumbnail(videoUrl: String, thumbnail: VideoThumbnail) {
        cache[videoUrl] = thumbnail
        lastAccessedAt[videoUrl] = System.nanoTime()
        resolvedAspectRatios[videoUrl] = thumbnail.aspectRatio
        evictIfNeeded()
    }

    /**
     * Evict oldest-accessed entries until both caps are satisfied.
     * Never evicts URLs in [visibleUrls].
     */
    private fun evictIfNeeded() {
        if (cache.size <= MAX_ENTRIES && bitmapBytes <= MAX_BITMAP_BYTES) return

        // Build sorted eviction candidates — oldest access first, skip visible.
        // Snapshot accessed-at values before sorting; lastAccessedAt is
        // concurrently mutated by the touch path. Same TimSort contract bug
        // as MES eviction sites — see CLAUDE.md rule #24.
        val candidateUrls = lastAccessedAt.keys.filter { it !in visibleUrls }
        val accessSnapshot = HashMap<String, Long>(candidateUrls.size)
        for (u in candidateUrls) accessSnapshot[u] = lastAccessedAt[u] ?: 0L
        val candidates = candidateUrls.sortedBy { accessSnapshot[it] ?: 0L }

        var evicted = 0
        for (url in candidates) {
            if (cache.size <= MAX_ENTRIES * 4 / 5 && bitmapBytes <= MAX_BITMAP_BYTES * 4 / 5) break
            cache.remove(url)
            lastAccessedAt.remove(url)
            // Don't remove from resolvedAspectRatios — lightweight floats, needed for layout stability
            // Do not recycle here: Compose may still hold a reference to an evicted bitmap.
            // Dropping the cache reference lets the bitmap be collected when UI releases it.
            evicted++
        }
        if (evicted > 0) {
            Log.d(TAG, "Evicted $evicted thumbnails, remaining=${cache.size} (~${bitmapBytes / (1024 * 1024)}mb)")
        }
    }
}

private class PlatformVideoThumbnailFrameExtractor(
    private val context: Context,
    private val mediaClient: OkHttpClient,
) : VideoThumbnailFrameExtractor {
    override fun extract(input: VideoThumbnailInput): VideoThumbnail? {
        if (input is VideoThumbnailInput.Rejected) return null
        MediaMetadataRetriever().use { retriever ->
            when (input) {
                is VideoThumbnailInput.Remote -> setGuardedRemoteSource(retriever, input.url)
                is VideoThumbnailInput.LocalUri -> retriever.setDataSource(context, Uri.parse(input.raw))
                is VideoThumbnailInput.LocalPath -> retriever.setDataSource(input.raw)
                VideoThumbnailInput.Rejected -> return null
            }
            val frame = decodeScaledFrame(retriever) ?: return null
            return VideoThumbnail(
                bitmap = frame,
                aspectRatio = frame.width.toFloat() / frame.height,
            )
        }
    }

    private fun setGuardedRemoteSource(retriever: MediaMetadataRetriever, url: HttpUrl) {
        val dataSource = GuardedRemoteMediaDataSource(url, mediaClient)
        try {
            retriever.setDataSource(dataSource)
        } catch (failure: Throwable) {
            dataSource.close()
            throw failure
        }
        // MediaMetadataRetriever owns and closes a successfully attached MediaDataSource.
    }

    /**
     * Decode the first keyframe already downsampled. On API 27+ the codec emits the
     * frame at target size — the full-res bitmap is NEVER allocated (no ~33MB transient
     * for a 4K source) and there is no JPEG encode/decode round-trip. dstW/dstH are a
     * bounding box; the result keeps the source aspect ratio, so a transposed box on a
     * rotated video is still correct. API 26 / missing-metadata path falls back to one
     * native bilinear downscale — still no round-trip.
     */
    private fun decodeScaledFrame(retriever: MediaMetadataRetriever): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (w > 0 && h > 0) {
                retriever.getScaledFrameAtTime(
                    0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (w / DOWNSAMPLE).coerceAtLeast(1),
                    (h / DOWNSAMPLE).coerceAtLeast(1),
                )?.let { return it }
            }
        }
        val full = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        if (full.allocationByteCount < 200 * 1024) return full
        return Bitmap.createScaledBitmap(
            full,
            (full.width / DOWNSAMPLE).coerceAtLeast(1),
            (full.height / DOWNSAMPLE).coerceAtLeast(1),
            true,
        ).also { if (it !== full) full.recycle() }
    }
}
