package com.unsilence.app.data.relay

import kotlinx.coroutines.Dispatchers
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

    private val cache = ConcurrentHashMap<String, OgMetadata?>()

    suspend fun fetch(url: String): OgMetadata? {
        cache[url]?.let { return it }
        // null sentinel means we already tried and failed
        if (cache.containsKey(url)) return null

        return withContext(Dispatchers.IO) {
            runCatching { doFetch(url) }.getOrNull()
        }.also { cache[url] = it }
    }

    private fun doFetch(url: String): OgMetadata? {
        // HEAD first to verify content-type is HTML
        val headReq = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "Mozilla/5.0 (compatible; unSilence/1.0)")
            .build()

        val headResp = runCatching { client.newCall(headReq).execute() }.getOrNull()
        val contentType = headResp?.header("Content-Type") ?: headResp?.header("content-type")
        headResp?.close()

        if (contentType != null && !contentType.contains("text/html", ignoreCase = true)) {
            return null
        }

        // GET with body size limit
        val getReq = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; unSilence/1.0)")
            .build()

        val response = client.newCall(getReq).execute()
        val body = response.use { resp ->
            if (!resp.isSuccessful) return null
            val ct = resp.header("Content-Type") ?: ""
            if (!ct.contains("text/html", ignoreCase = true)) return null
            // Read at most 50KB
            val source = resp.body.source()
            val buf = okio.Buffer()
            source.read(buf, 50_000)
            buf.readUtf8()
        }

        return parseOgTags(body, url)
    }

    companion object {
        private val OG_TAG_REGEX = Regex(
            """<meta\s+[^>]*property\s*=\s*["']og:(\w+)["'][^>]*content\s*=\s*["']([^"']+)["'][^>]*/?>|""" +
            """<meta\s+[^>]*content\s*=\s*["']([^"']+)["'][^>]*property\s*=\s*["']og:(\w+)["'][^>]*/?>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        internal fun parseOgTags(html: String, originalUrl: String): OgMetadata? {
            val tags = mutableMapOf<String, String>()
            for (match in OG_TAG_REGEX.findAll(html)) {
                // First alternative: property then content
                val key1 = match.groupValues[1]
                val val1 = match.groupValues[2]
                // Second alternative: content then property
                val key2 = match.groupValues[4]
                val val2 = match.groupValues[3]

                val key = key1.ifBlank { key2 }
                val value = val1.ifBlank { val2 }
                if (key.isNotBlank() && value.isNotBlank()) {
                    tags.putIfAbsent(key.lowercase(), value)
                }
            }

            val title = tags["title"]
            val image = tags["image"]
            // Require at least a title or image to be useful
            if (title.isNullOrBlank() && image.isNullOrBlank()) return null

            return OgMetadata(
                title       = title,
                description = tags["description"],
                imageUrl    = image,
                siteName    = tags["site_name"],
                url         = originalUrl,
            )
        }
    }
}
