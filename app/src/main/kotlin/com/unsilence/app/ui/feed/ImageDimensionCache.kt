package com.unsilence.app.ui.feed

import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
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
 * safety net; servers that ignore Range still work because BitmapFactory stops
 * reading after the header.
 *
 * Used by [CardHydrator] during hydration (pre-fetch) and by [MediaImage] as a
 * secondary dimension source after imeta tags. When [MediaImage] resolves
 * dimensions from Coil's decoded bitmap, it writes back here via [put] so
 * subsequent renders (scroll-away-and-back) are instant.
 */
@Singleton
@androidx.compose.runtime.Stable
class ImageDimensionCache @Inject constructor(
    baseClient: OkHttpClient,
) {
    /** url → width/height aspect ratio */
    private val cache = ConcurrentHashMap<String, Float>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    private val client = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Number of cached aspect ratios. */
    val entryCount: Int get() = cache.size

    /** Return cached aspect ratio, or null if not yet resolved. No I/O. */
    fun getCached(url: String): Float? = cache[url]

    /** Store a known aspect ratio (e.g. from Coil's decoded bitmap). Clamped to [0.2, 5.0]. */
    fun put(url: String, aspectRatio: Float) {
        if (aspectRatio > 0f) cache[url] = aspectRatio.coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO)
    }

    /**
     * Resolve aspect ratio for [url] via lightweight HTTP header decode.
     * Returns null if another coroutine is already resolving this URL or on failure.
     * Result is cached for future reads.
     */
    suspend fun resolve(url: String): Float? {
        if (!isResolvableUrl(url)) return null
        cache[url]?.let { return it }
        if (inFlight.putIfAbsent(url, true) != null) return null

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-32767")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        inFlight.remove(url)
                        return@withContext null
                    }
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    response.body.byteStream().use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        val ratio = (options.outWidth.toFloat() / options.outHeight)
                            .coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO)
                        cache[url] = ratio
                        ratio
                    } else {
                        inFlight.remove(url)
                        null
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                inFlight.remove(url)
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Resolve failed: ${url.take(60)} — ${e.message}")
                inFlight.remove(url)
                null
            }
        }
    }

    /**
     * Batch-resolve aspect ratios for multiple URLs. Skips URLs already cached
     * or in-flight. Capped at [maxBatch] concurrent resolves to limit hydration cost.
     */
    suspend fun resolveAll(urls: List<String>, maxBatch: Int = 6) {
        val missing = urls.filter { cache[it] == null }.take(maxBatch)
        if (missing.isEmpty()) return
        for (url in missing) {
            resolve(url)
        }
        if (missing.isNotEmpty()) {
            Log.d(TAG, "Batch resolved: ${missing.size} URLs, ${missing.count { cache[it] != null }} success")
        }
    }

    /** Reject non-HTTP URLs and NIP-19 tokens that leaked through content regex matching. */
    private fun isResolvableUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.d(TAG, "Rejected non-http: ${url.take(40)}")
            return false
        }
        // NIP-19 bech32 tokens embedded in malformed URLs (e.g. "https://npub1...")
        val host = url.substring(url.indexOf("://") + 3).substringBefore('/')
        if (host.startsWith("npub") || host.startsWith("nevent") ||
            host.startsWith("note") || host.startsWith("naddr") ||
            host.startsWith("nprofile")
        ) {
            Log.d(TAG, "Rejected NIP-19 host: ${url.take(40)}")
            return false
        }
        return true
    }
}
