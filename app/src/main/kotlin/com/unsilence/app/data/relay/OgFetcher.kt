package com.unsilence.app.data.relay

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
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
    baseClient: OkHttpClient,
) {
    private val client = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cache = ConcurrentHashMap<String, OgMetadata>()
    private val attempted = ConcurrentHashMap<String, Boolean>()

    /** True if the URL has already been fetched (or attempted). */
    fun hasCached(url: String): Boolean = cache.containsKey(url) || attempted.containsKey(url)

    suspend fun fetch(url: String): OgMetadata? {
        cache[url]?.let { return it }
        if (attempted.containsKey(url)) return null

        return withContext(Dispatchers.IO) {
            try {
                doFetch(url)
            } catch (e: CancellationException) {
                throw e          // let coroutine cancellation propagate
            } catch (_: Exception) {
                null
            }
        }.also { attempted[url] = true; if (it != null) cache[url] = it }
    }

    /** Execute an OkHttp call with coroutine cancellation propagation. */
    private suspend fun executeWithCancellation(call: okhttp3.Call): okhttp3.Response {
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            try {
                val response = call.execute()
                cont.resume(response) { _, _, _ -> response.close() }
            } catch (e: Exception) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        }
    }

    private suspend fun doFetch(url: String): OgMetadata? {
        // Skip HEAD — many sites block it or return wrong content-type.
        // Go straight to GET with body size limit.
        return executeWithCancellation(
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
            )
        ).use { response ->
            if (!response.isSuccessful) return null
            val ct = response.header("Content-Type") ?: ""
            if (ct.isNotBlank() && !ct.contains("text/html", ignoreCase = true)
                && !ct.contains("application/xhtml", ignoreCase = true)) return null
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
        // Realistic browser UA — many sites (yahoo.co.jp, etc.) 403 bot-like UAs.
        private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val MAX_BODY_SIZE = 50_000L

        // Matches property= or name= with og: prefix, in either order with content=
        private val OG_TAG_REGEX = Regex(
            """<meta\s+[^>]*(?:property|name)\s*=\s*["']og:(\w+)["'][^>]*content\s*=\s*["']([^"']+)["'][^>]*/?>|""" +
            """<meta\s+[^>]*content\s*=\s*["']([^"']+)["'][^>]*(?:property|name)\s*=\s*["']og:(\w+)["'][^>]*/?>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        // Fallback: twitter card tags (twitter:image, twitter:title, etc.)
        private val TWITTER_TAG_REGEX = Regex(
            """<meta\s+[^>]*(?:property|name)\s*=\s*["']twitter:(\w+)["'][^>]*content\s*=\s*["']([^"']+)["'][^>]*/?>|""" +
            """<meta\s+[^>]*content\s*=\s*["']([^"']+)["'][^>]*(?:property|name)\s*=\s*["']twitter:(\w+)["'][^>]*/?>""",
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

        /** Resolve a potentially relative URL against the page's base URL. */
        private fun resolveUrl(raw: String, pageUrl: String): String {
            val decoded = decodeHtmlEntities(raw).trim()
            return when {
                decoded.startsWith("http://") || decoded.startsWith("https://") -> decoded
                decoded.startsWith("//") -> "https:$decoded"
                decoded.startsWith("/") -> {
                    // Prepend scheme + host from page URL
                    runCatching {
                        val uri = java.net.URI(pageUrl)
                        "${uri.scheme}://${uri.host}$decoded"
                    }.getOrDefault(decoded)
                }
                else -> {
                    // Relative path — resolve against page URL directory
                    runCatching {
                        java.net.URI(pageUrl).resolve(decoded).toString()
                    }.getOrDefault(decoded)
                }
            }
        }

        internal fun parseOgTags(html: String, originalUrl: String): OgMetadata? {
            val ogTags = mutableMapOf<String, String>()
            for (match in OG_TAG_REGEX.findAll(html)) {
                val key1 = match.groupValues[1]
                val val1 = match.groupValues[2]
                val key2 = match.groupValues[4]
                val val2 = match.groupValues[3]
                val key = key1.ifBlank { key2 }
                val value = val1.ifBlank { val2 }
                if (key.isNotBlank() && value.isNotBlank()) {
                    ogTags.putIfAbsent(key.lowercase(), decodeHtmlEntities(value))
                }
            }

            // Fallback: twitter card tags fill any gaps
            val twitterTags = mutableMapOf<String, String>()
            for (match in TWITTER_TAG_REGEX.findAll(html)) {
                val key1 = match.groupValues[1]
                val val1 = match.groupValues[2]
                val key2 = match.groupValues[4]
                val val2 = match.groupValues[3]
                val key = key1.ifBlank { key2 }
                val value = val1.ifBlank { val2 }
                if (key.isNotBlank() && value.isNotBlank()) {
                    twitterTags.putIfAbsent(key.lowercase(), decodeHtmlEntities(value))
                }
            }

            val title = ogTags["title"] ?: twitterTags["title"]
                ?: TITLE_TAG_REGEX.find(html)?.groupValues?.get(1)?.let { decodeHtmlEntities(it) }
            val image = (ogTags["image"] ?: twitterTags["image"])
                ?.let { resolveUrl(it, originalUrl) }
            val description = ogTags["description"] ?: twitterTags["description"]
            val siteName = ogTags["site_name"]

            Log.d(TAG, "og:image=$image for $originalUrl")

            // Require at least a title or image to be useful
            if (title.isNullOrBlank() && image.isNullOrBlank()) return null

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
