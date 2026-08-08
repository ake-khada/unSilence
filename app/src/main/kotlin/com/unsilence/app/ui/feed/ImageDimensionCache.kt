package com.unsilence.app.ui.feed

import android.graphics.BitmapFactory
import android.util.Log
import com.unsilence.app.data.network.parseAllowedUntrustedHttpUrl
import com.unsilence.app.di.ImageClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ImageDimCache"
private const val MIN_ASPECT_RATIO = 0.2f  // tallest allowed (1:5)
private const val MAX_ASPECT_RATIO = 5.0f  // widest allowed  (5:1)

/**
 * In-memory cache of image aspect ratios (width / height).
 *
 * Resolves dimensions via [BitmapFactory.Options.inJustDecodeBounds] which reads
 * only the image header (IHDR/SOF/VP8 — typically < 1 KB) without decoding the
 * full bitmap. An HTTP Range header limits the download to the first 32 KB as a
 * safety net. The response stream is additionally capped in-process, so a
 * server that ignores Range cannot turn a dimension probe into a full download.
 *
 * Used by [CardHydrator] during hydration (pre-fetch) and by [MediaImage] as a
 * secondary dimension source after imeta tags. When [MediaImage] resolves
 * dimensions from Coil's decoded bitmap, it writes back here via [put] so
 * subsequent renders (scroll-away-and-back) are instant.
 */
@Singleton
@androidx.compose.runtime.Stable
class ImageDimensionCache @Inject constructor(
    @ImageClient baseClient: OkHttpClient,
) {
    /** url → width/height aspect ratio */
    private val cache = ConcurrentHashMap<String, Float>()
    private val insertionOrder = ConcurrentLinkedQueue<String>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    private val client = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Number of cached aspect ratios. */
    val entryCount: Int get() = cache.size

    /** Return cached aspect ratio, or null if not yet resolved. No I/O. */
    fun getCached(url: String): Float? = cache[cacheKey(url)]

    /** Store a known aspect ratio (e.g. from Coil's decoded bitmap). Clamped to [0.2, 5.0]. */
    fun put(url: String, aspectRatio: Float) {
        if (aspectRatio > 0f) putBounded(cacheKey(url), aspectRatio.coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO))
    }

    /**
     * Resolve aspect ratio for [url] via lightweight HTTP header decode.
     * Returns null if another coroutine is already resolving this URL or on failure.
     * Result is cached for future reads.
     */
    suspend fun resolve(url: String): Float? {
        val requestUrl = allowedImageDimensionUrl(url)
        if (requestUrl == null) {
            Log.d(TAG, "Rejected untrusted URL: ${url.take(40)}")
            return null
        }
        val key = cacheKey(url)
        cache[key]?.let { return it }
        if (inFlight.putIfAbsent(key, true) != null) return null

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(requestUrl)
                    .header("Range", "bytes=0-32767")
                    .header("User-Agent", com.unsilence.app.data.BROWSER_USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        return@withContext null
                    }
                    val headerBytes = response.body.byteStream().use { stream ->
                        val bytes = ByteArray(MAX_PROBE_BYTES)
                        var count = 0
                        while (count < bytes.size) {
                            val read = stream.read(bytes, count, bytes.size - count)
                            if (read <= 0) break
                            count += read
                        }
                        if (count == bytes.size) bytes else bytes.copyOf(count)
                    }
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(headerBytes, 0, headerBytes.size, options)
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        val ratio = (options.outWidth.toFloat() / options.outHeight)
                            .coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO)
                        putBounded(key, ratio)
                        ratio
                    } else {
                        null
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Resolve failed: ${url.take(60)} — ${e.message}")
                null
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /**
     * Batch-resolve aspect ratios for multiple URLs. Skips URLs already cached
     * or in-flight. Capped at [maxBatch] concurrent resolves to limit hydration cost.
     */
    suspend fun resolveAll(urls: List<String>, maxBatch: Int = 6) {
        val missing = urls.distinctBy(::cacheKey).filter { getCached(it) == null }.take(maxBatch)
        if (missing.isEmpty()) return
        for (url in missing) {
            resolve(url)
        }
        if (missing.isNotEmpty()) {
            Log.d(TAG, "Batch resolved: ${missing.size} URLs, ${missing.count { getCached(it) != null }} success")
        }
    }

    private fun putBounded(key: String, value: Float) {
        if (cache.put(key, value) == null) insertionOrder.add(key)
        while (cache.size > MAX_ENTRIES) {
            val oldest = insertionOrder.poll() ?: break
            cache.remove(oldest)
        }
    }

    /** URL fragments never reach the HTTP server; removing them deduplicates
     *  equivalent image references without collapsing meaningful query params. */
    private fun cacheKey(url: String): String = url.substringBefore('#')

    private companion object {
        const val MAX_ENTRIES = 512
        const val MAX_PROBE_BYTES = 32 * 1024
    }
}

/** Reject unsafe destinations and NIP-19 tokens that leaked through content
 *  matching before a dimension probe is enqueued. */
internal fun allowedImageDimensionUrl(url: String): HttpUrl? {
    val parsed = parseAllowedUntrustedHttpUrl(url) ?: return null
    val host = parsed.host.lowercase()
    if (host.startsWith("npub") || host.startsWith("nevent") ||
        host.startsWith("note") || host.startsWith("naddr") ||
        host.startsWith("nprofile")
    ) {
        return null
    }
    return parsed
}
