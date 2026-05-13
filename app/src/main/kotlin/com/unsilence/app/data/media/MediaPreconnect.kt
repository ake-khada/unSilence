package com.unsilence.app.data.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import okhttp3.OkHttpClient
import okhttp3.Request

object MediaPreconnect {
    /** Common Nostr media CDN hosts. HEAD request warms up DNS + TLS. */
    private val MEDIA_HOSTS = listOf(
        "https://nostr.build",
        "https://void.cat",
        "https://image.nostr.build",
        "https://cdn.satellite.earth",
        "https://files.sovbit.host",
        "https://blossom.primal.net",
        "https://media.tenor.com",
    )

    suspend fun warmUp(client: OkHttpClient) = supervisorScope {
        MEDIA_HOSTS.forEach { url ->
            launch(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .head()
                        .build()
                    client.newCall(request).execute().use { /* discard */ }
                } catch (_: Exception) {
                    // Best effort — ignore failures
                }
            }
        }
    }
}
