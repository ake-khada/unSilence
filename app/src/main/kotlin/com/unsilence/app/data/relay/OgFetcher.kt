package com.unsilence.app.data.relay

import android.util.Log
import android.content.Context
import com.unsilence.app.data.BROWSER_USER_AGENT
import com.unsilence.app.data.network.UntrustedHttpNetworkGuard
import com.unsilence.app.data.network.isAllowedUntrustedHttpUrl
import com.unsilence.app.data.network.parseAllowedUntrustedHttpUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Cache
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@androidx.compose.runtime.Immutable
data class OgMetadata(
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
    val url: String,
)

@Singleton
class OgFetcher @Inject constructor(
    @ApplicationContext context: Context,
    baseClient: OkHttpClient,
) {
    private val client = baseClient.newBuilder()
        .cache(Cache(File(context.cacheDir, "og_http"), 8L * 1024 * 1024))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .addNetworkInterceptor(UntrustedHttpNetworkGuard)
        .build()

    private val cache = ConcurrentHashMap<String, OgMetadata>()
    private val cacheOrder = ConcurrentLinkedQueue<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val failureOrder = ConcurrentLinkedQueue<String>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<OgMetadata?>>()

    /** True if the URL is cached or has a still-live negative-cache entry. */
    fun hasCached(url: String): Boolean {
        val key = cacheKey(url)
        return cache.containsKey(key) || isRecentFailure(key)
    }

    suspend fun fetch(url: String): OgMetadata? {
        val key = cacheKey(url)
        cache[key]?.let { return it }
        if (isRecentFailure(key)) return null
        if (!isAllowedPreviewUrl(key)) {
            Log.d(TAG, "og fetch: blocked URL policy for ${key.take(80)}")
            return null
        }

        val deferred = CompletableDeferred<OgMetadata?>()
        val existing = inFlight.putIfAbsent(key, deferred)
        if (existing != null) {
            return try {
                existing.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (!existing.isCancelled) throw e
                inFlight.remove(key, existing)
                fetch(url)
            }
        }

        try {
            val result = withContext(Dispatchers.IO) {
                try {
                    doFetch(key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "og fetch: exception for $key: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
            if (result != null) {
                putCached(key, result)
                failedAt.remove(key)
            } else {
                recordFailure(key)
            }
            deferred.complete(result)
            return result
        } catch (e: CancellationException) {
            // Other callers may be awaiting the shared deferred. Propagate
            // cancellation instead of leaving them suspended indefinitely.
            deferred.cancel(e)
            throw e
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    private fun isRecentFailure(key: String): Boolean {
        val failed = failedAt[key] ?: return false
        if (System.currentTimeMillis() - failed < NEGATIVE_CACHE_TTL_MS) return true
        failedAt.remove(key, failed)
        return false
    }

    private fun putCached(key: String, value: OgMetadata) {
        if (cache.put(key, value) == null) cacheOrder.add(key)
        while (cache.size > MAX_CACHE_ENTRIES) {
            val oldest = cacheOrder.poll() ?: break
            cache.remove(oldest)
        }
    }

    private fun recordFailure(key: String) {
        if (failedAt.put(key, System.currentTimeMillis()) == null) failureOrder.add(key)
        while (failedAt.size > MAX_FAILURE_ENTRIES) {
            val oldest = failureOrder.poll() ?: break
            failedAt.remove(oldest)
        }
    }

    private fun cacheKey(url: String): String = url.substringBefore('#')

    private fun isAllowedPreviewUrl(url: String): Boolean {
        return parseAllowedUntrustedHttpUrl(url) != null
    }

    /**
     * Execute [call] asynchronously and parse the response inside the OkHttp
     * callback, where `response.use { ... }` guarantees close on every exit
     * path (success, exception, cancellation).
     *
     * Why not the previous `executeWithCancellation` returning a Response and
     * letting the caller `.use {}` it: that pattern hands a closeable across
     * a coroutine resume boundary. Even with `cont.resume(value, onCancellation)`
     * the cancellation contract is brittle when the continuation is cancelled
     * after the dispatcher has scheduled the resume but before the consumer
     * runs. Field logs showed sustained `A resource failed to call close` bursts
     * after GC despite the prior fix. The canonical fix is to never leak the
     * Response across the resume — consume it entirely inside the OkHttp callback.
     *
     * The result we resume the continuation with is just an `OgMetadata?`
     * (a value, no native resources), so the resume cancellation path is
     * loss-tolerant.
     */
    private suspend fun executeAndParse(
        call: Call,
        parse: (Response) -> OgMetadata?,
    ): OgMetadata? = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val result: OgMetadata? = try {
                        parse(resp)
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWithException(t)
                        return
                    }
                    if (cont.isActive) cont.resume(result)
                }
            }
        })
    }

    private suspend fun doFetch(url: String): OgMetadata? {
        // Skip HEAD — many sites block it or return wrong content-type.
        // Go straight to GET with body size limit.
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("sec-ch-ua", "\"Google Chrome\";v=\"130\", \"Chromium\";v=\"130\", \"Not?A_Brand\";v=\"99\"")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
            .build()
        return executeAndParse(client.newCall(request)) { response ->
            if (!response.isSuccessful) {
                Log.d(TAG, "og fetch: HTTP ${response.code} for $url")
                return@executeAndParse null
            }
            val ct = response.header("Content-Type") ?: ""
            if (ct.isNotBlank() && !ct.contains("text/html", ignoreCase = true)
                && !ct.contains("application/xhtml", ignoreCase = true)) {
                Log.d(TAG, "og fetch: bad content-type '$ct' for $url")
                return@executeAndParse null
            }
            // Read up to MAX_BODY_SIZE bytes. A single source.read() may return
            // less than requested on network sources (first TCP segment only),
            // so loop until we've accumulated MAX_BODY_SIZE or hit EOF.
            val source = response.body.source()
            val buf = okio.Buffer()
            while (buf.size < MAX_BODY_SIZE) {
                val remaining = MAX_BODY_SIZE - buf.size
                val read = source.read(buf, remaining)
                if (read == -1L) break  // EOF
            }
            val body = buf.readUtf8()
            parseOgTags(body, url)
        }
    }

    companion object {
        private const val TAG = "OgFetcher"
        private const val UA = BROWSER_USER_AGENT
        private const val MAX_BODY_SIZE = 50_000L
        private const val MAX_CACHE_ENTRIES = 256
        private const val MAX_FAILURE_ENTRIES = 512
        private const val NEGATIVE_CACHE_TTL_MS = 10 * 60 * 1000L

        // Matches property= or name= with og: prefix, in either order with content=.
        // Handles both quoted (content="val") and unquoted (content=val) attributes
        // — minified HTML often drops quotes from values without spaces.
        private val OG_TAG_REGEX = Regex(
            """<meta\s+[^>]*(?:property|name)\s*=\s*["']?og:(\w+)["']?[\s/][^>]*content\s*=\s*(?:["']([^"']+)["']|([^\s>"']+))[^>]*/?>|""" +
            """<meta\s+[^>]*content\s*=\s*(?:["']([^"']+)["']|([^\s>"']+))[\s/][^>]*(?:property|name)\s*=\s*["']?og:(\w+)["']?[^>]*/?>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        // Fallback: twitter card tags (twitter:image, twitter:title, etc.)
        private val TWITTER_TAG_REGEX = Regex(
            """<meta\s+[^>]*(?:property|name)\s*=\s*["']?twitter:(\w+)["']?[\s/][^>]*content\s*=\s*(?:["']([^"']+)["']|([^\s>"']+))[^>]*/?>|""" +
            """<meta\s+[^>]*content\s*=\s*(?:["']([^"']+)["']|([^\s>"']+))[\s/][^>]*(?:property|name)\s*=\s*["']?twitter:(\w+)["']?[^>]*/?>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        // Fallback: HTML <title> tag
        private val TITLE_TAG_REGEX = Regex(
            """<title[^>]*>([^<]+)</title>""",
            RegexOption.IGNORE_CASE,
        )

        /** Decode HTML entities (named + numeric) in attribute values. */
        private fun decodeHtmlEntities(s: String): String =
            android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .trim()

        /** Resolve an OG image through OkHttp's HTTP(S)-only URL model, then
         *  apply the same cheap policy used at every untrusted fetch boundary. */
        internal fun resolveAllowedImageUrl(raw: String, pageUrl: String): String? {
            val base = parseAllowedUntrustedHttpUrl(pageUrl) ?: return null
            val resolved = base.resolve(raw.trim()) ?: return null
            return resolved.takeIf(::isAllowedUntrustedHttpUrl)?.toString()
        }

        /** Extract key and value from a 6-group OG/Twitter regex match. */
        private fun extractKeyValue(match: MatchResult): Pair<String, String> {
            // Pattern 1 (property before content): groups 1=key, 2=quoted-val, 3=unquoted-val
            // Pattern 2 (content before property): groups 4=quoted-val, 5=unquoted-val, 6=key
            val key = match.groupValues[1].ifBlank { match.groupValues[6] }
            val value = match.groupValues[2].ifBlank {
                match.groupValues[3].ifBlank {
                    match.groupValues[4].ifBlank { match.groupValues[5] }
                }
            }
            return key to value
        }

        internal fun parseOgTags(html: String, originalUrl: String): OgMetadata? {
            val ogTags = mutableMapOf<String, String>()
            for (match in OG_TAG_REGEX.findAll(html)) {
                val (key, value) = extractKeyValue(match)
                if (key.isNotBlank() && value.isNotBlank()) {
                    ogTags.putIfAbsent(key.lowercase(), decodeHtmlEntities(value))
                }
            }

            // Fallback: twitter card tags fill any gaps
            val twitterTags = mutableMapOf<String, String>()
            for (match in TWITTER_TAG_REGEX.findAll(html)) {
                val (key, value) = extractKeyValue(match)
                if (key.isNotBlank() && value.isNotBlank()) {
                    twitterTags.putIfAbsent(key.lowercase(), decodeHtmlEntities(value))
                }
            }

            val title = ogTags["title"] ?: twitterTags["title"]
                ?: TITLE_TAG_REGEX.find(html)?.groupValues?.get(1)?.let { decodeHtmlEntities(it) }
            val image = (ogTags["image"] ?: twitterTags["image"])
                ?.let { resolveAllowedImageUrl(it, originalUrl) }
            val description = ogTags["description"] ?: twitterTags["description"]
            val siteName = ogTags["site_name"]

            Log.d(TAG, "og:image=$image for $originalUrl")

            // Require at least a title or image to be useful
            if (title.isNullOrBlank() && image.isNullOrBlank()) {
                Log.d(TAG, "og fetch: no title or image in HTML for $originalUrl")
                return null
            }

            return OgMetadata(
                title       = title ?: "",
                description = description,
                imageUrl    = image,
                siteName    = siteName,
                url         = originalUrl,
            )
        }
    }
}
