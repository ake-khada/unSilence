package com.unsilence.app.ui.feed

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.unsilence.app.data.media.GuardedRemoteMediaDataSource
import com.unsilence.app.data.network.parseAllowedUntrustedHttpUrl
import com.unsilence.app.di.MediaClient
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
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
private const val REMOTE_MMR_CONCURRENCY = 3

/**
 * First-frame thumbnail with its native aspect ratio.
 */
data class VideoThumbnail(
    val bitmap: Bitmap,
    val aspectRatio: Float = bitmap.width.toFloat() / bitmap.height,
    internal val sourceSize: IntSize = IntSize(bitmap.width, bitmap.height),
    internal val decodedFor: IntSize = sourceSize,
)

internal data class ThumbnailTarget(val size: IntSize, val crop: Boolean = false) {
    fun frameSize(source: IntSize): IntSize {
        val horizontal = size.width.toDouble() / source.width
        val vertical = size.height.toDouble() / source.height
        val scale = (if (crop) maxOf(horizontal, vertical) else minOf(horizontal, vertical)).coerceAtMost(0.5)
        return IntSize(
            (source.width * scale).roundToInt().coerceIn(1, (source.width / 2).coerceAtLeast(1)),
            (source.height * scale).roundToInt().coerceIn(1, (source.height / 2).coerceAtLeast(1)),
        )
    }

    fun fits(frame: VideoThumbnail): Boolean {
        val needed = frameSize(frame.sourceSize)
        // Compare requested box sizes. The codec can round the output or correct its pixel aspect.
        return frame.decodedFor.width >= needed.width && frame.decodedFor.height >= needed.height
    }
}

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
    fun extract(input: VideoThumbnailInput, target: ThumbnailTarget): VideoThumbnail?
}

/**
 * In-memory cache of video first-frame thumbnails extracted via [MediaMetadataRetriever].
 *
 * Remote extraction gives [MediaMetadataRetriever] a bounded [android.media.MediaDataSource]
 * backed by the guarded media client. The platform never receives the attacker-controlled URL.
 *
 * A URL shares one extraction at a time and one cached frame across surfaces.
 *
 * Bounded: LRU eviction at [MAX_ENTRIES] entries OR [MAX_BITMAP_BYTES] total bitmap bytes,
 * whichever hits first. URLs in [visibleUrls] are never evicted.
 * Decode to the requested display size, up to half source dimensions, via [MediaMetadataRetriever.getScaledFrameAtTime]
 * (API 27+) or [Bitmap.createScaledBitmap] fallback — no JPEG round-trip.
 */
@Singleton
@androidx.compose.runtime.Stable
class VideoThumbnailCache internal constructor(
    private val frameExtractor: VideoThumbnailFrameExtractor,
    private val warmTarget: () -> ThumbnailTarget,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @MediaClient mediaClient: OkHttpClient,
    ) : this(PlatformVideoThumbnailFrameExtractor(context, mediaClient), {
        val metrics = context.resources.displayMetrics
        ThumbnailTarget(IntSize(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1)))
    })

    internal constructor(extractFrame: (VideoThumbnailInput) -> VideoThumbnail?) :
        this(
            VideoThumbnailFrameExtractor { input, _ -> extractFrame(input) },
            { ThumbnailTarget(IntSize(Int.MAX_VALUE, Int.MAX_VALUE)) },
        )

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
        thumbnailJob(url, target = null).await()
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
        getCached(videoUrl)?.let { return it }
        if (videoUrl in failedUrls) return null
        return thumbnailJob(videoUrl, target = null).await()
    }

    internal suspend fun getThumbnail(videoUrl: String, target: ThumbnailTarget): VideoThumbnail? {
        while (true) {
            val cached = getCached(videoUrl)
            if (cached != null && target.fits(cached)) return cached
            if (videoUrl in failedUrls) return cached

            val pending = thumbnailJob(videoUrl, target)
            val frame = pending.await() ?: return getCached(videoUrl)
            if (target.fits(frame)) return frame
            // We joined a smaller request. Keep its frame visible during the upgrade.
            inFlight.remove(videoUrl, pending)
        }
    }

    private fun thumbnailJob(videoUrl: String, target: ThumbnailTarget?): Deferred<VideoThumbnail?> {
        inFlight[videoUrl]?.let { return it }
        val deferred = extractionScope.async(start = CoroutineStart.LAZY) {
            extractThumbnail(videoUrl, target)
        }
        val raced = inFlight.putIfAbsent(videoUrl, deferred)
        if (raced != null) {
            deferred.cancel()
            return raced
        }
        deferred.invokeOnCompletion { inFlight.remove(videoUrl, deferred) }
        deferred.start()
        return deferred
    }

    private suspend fun extractThumbnail(videoUrl: String, target: ThumbnailTarget?): VideoThumbnail? {
        // A warm request has no measured surface. It can use any existing frame.
        cache[videoUrl]?.let { if (target == null || target.fits(it)) return it }
        if (videoUrl in failedUrls) return null
        val input = classifyVideoThumbnailInput(videoUrl)
        if (input is VideoThumbnailInput.Rejected) {
            failedUrls.add(videoUrl)
            return null
        }
        val isRemote = input is VideoThumbnailInput.Remote
        val decodeTarget = target ?: warmTarget()

        return withContext(Dispatchers.IO) {
            try {
                if (isRemote) {
                    // Remote MMR: bound parallel extraction. The timeout
                    // applies to extraction, not time spent queued behind an
                    // earlier prewarm, so look-ahead jobs do not poison the
                    // negative cache before they get a turn.
                    remoteMmrSemaphore.acquire()
                    val result = try {
                        withTimeoutOrNull(8_000L) { frameExtractor.extract(input, decodeTarget) }
                    } finally {
                        remoteMmrSemaphore.release()
                    }
                    if (result == null) { failedUrls.add(videoUrl) }
                    result?.also { rememberThumbnail(videoUrl, it) }
                } else {
                    // Local file: no concurrency limit, no timeout
                    frameExtractor.extract(input, decodeTarget)?.also { rememberThumbnail(videoUrl, it) }
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
    override fun extract(input: VideoThumbnailInput, target: ThumbnailTarget): VideoThumbnail? {
        if (input is VideoThumbnailInput.Rejected) return null
        MediaMetadataRetriever().use { retriever ->
            when (input) {
                is VideoThumbnailInput.Remote -> setGuardedRemoteSource(retriever, input.url)
                is VideoThumbnailInput.LocalUri -> retriever.setDataSource(context, input.raw.toUri())
                is VideoThumbnailInput.LocalPath -> retriever.setDataSource(input.raw)
                VideoThumbnailInput.Rejected -> return null
            }
            return decodeScaledFrame(retriever, target)
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

    private fun decodeScaledFrame(retriever: MediaMetadataRetriever, target: ThumbnailTarget): VideoThumbnail? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (width != null && width > 0 && height != null && height > 0) {
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                val source = if (rotation == 90 || rotation == 270) IntSize(height, width) else IntSize(width, height)
                val size = target.frameSize(source)
                val frame = retriever.getScaledFrameAtTime(
                    0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size.width, size.height,
                )
                if (frame != null) return VideoThumbnail(frame, sourceSize = source, decodedFor = size)
            }
        }

        val full = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val source = IntSize(full.width, full.height)
        if (full.allocationByteCount < 200 * 1024) return VideoThumbnail(full, sourceSize = source)
        val size = target.frameSize(source)
        val scaled = full.scale(size.width, size.height, filter = true)
        if (scaled !== full) full.recycle()
        return VideoThumbnail(scaled, sourceSize = source, decodedFor = size)
    }
}
