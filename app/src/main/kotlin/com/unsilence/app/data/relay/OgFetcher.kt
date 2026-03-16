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
        // HEAD first to verify content-type is HTML
        val headResp = executeWithCancellation(
            client.newCall(
                Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", UA)
                    .build()
            )
        )
        try {
            val contentType = headResp.header("Content-Type")
                ?: headResp.header("content-type")
            if (contentType != null && !contentType.contains("text/html", ignoreCase = true)) {
                return null
            }
        } finally {
            headResp.close()
        }

        // GET with body size limit
        val response = executeWithCancellation(
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .build()
            )
        )
        response.use {
            if (!it.isSuccessful) return null
            val ct = it.header("Content-Type") ?: ""
            if (!ct.contains("text/html", ignoreCase = true)) return null
            // Read at most 50KB
            val source = it.body.source()
            val buf = okio.Buffer()
            source.read(buf, MAX_BODY_SIZE)
            val body = buf.readUtf8()
            return parseOgTags(body, url)
        }
    }

    companion object {
        private const val TAG = "OgFetcher"
        private const val UA = "Mozilla/5.0 (compatible; unSilence/1.0)"
        private const val MAX_BODY_SIZE = 50_000L

        // Matches property= or name= with og: prefix, in either order with content=
        private val OG_TAG_REGEX = Regex(
            """<meta\s+[^>]*(?:property|name)\s*=\s*["']og:(\w+)["'][^>]*content\s*=\s*["']([^"']+)["'][^>]*/?>|""" +
            """<meta\s+[^>]*content\s*=\s*["']([^"']+)["'][^>]*(?:property|name)\s*=\s*["']og:(\w+)["'][^>]*/?>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        /** Decode common HTML entities in attribute values. */
        private fun decodeHtmlEntities(s: String): String = s
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

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
            val tags = mutableMapOf<String, String>()
            for (match in OG_TAG_REGEX.findAll(html)) {
                // First alternative: property/name then content
                val key1 = match.groupValues[1]
                val val1 = match.groupValues[2]
                // Second alternative: content then property/name
                val key2 = match.groupValues[4]
                val val2 = match.groupValues[3]

                val key = key1.ifBlank { key2 }
                val value = val1.ifBlank { val2 }
                if (key.isNotBlank() && value.isNotBlank()) {
                    tags.putIfAbsent(key.lowercase(), decodeHtmlEntities(value))
                }
            }

            val title = tags["title"]
            val image = tags["image"]?.let { resolveUrl(it, originalUrl) }
            Log.d(TAG, "og:image=$image for $originalUrl (raw=${tags["image"]})")

            // Require at least a title or image to be useful
            if (title.isNullOrBlank() && image.isNullOrBlank()) return null

            return OgMetadata(
                title       = decodeHtmlEntities(title ?: ""),
                description = tags["description"]?.let { decodeHtmlEntities(it) },
                imageUrl    = image,
                siteName    = tags["site_name"]?.let { decodeHtmlEntities(it) },
                url         = originalUrl,
            )
        }
    }
}
