package com.unsilence.app.data.blossom

import coil3.Extras
import coil3.network.NetworkRequest
import coil3.network.okhttp.asNetworkClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

class AvatarSoft404Test {
    private val hash = "ab".repeat(32)
    private val original = "https://origin.example/$hash.gif"
    private val author = "cd".repeat(32)
    private fun marked(url: String = original) = NetworkRequest(url, extras = Extras.Builder().apply {
        set(AvatarRequestKey, AvatarRequest(author, url))
    }.build())

    @Test fun `redirect to nostr build explicit soft404 recovers the original hash`() = runBlocking {
        // Real OkHttp redirects and Coil's response bridge; all sockets use this local proxy.
        // The production untrusted-network guard is unchanged and tested independently.
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://cdn.nostr.build/missing.jpg"))
            server.enqueue(MockResponse().setHeader("X-Status", "404").setBody("placeholder"))
            val http = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.port)))
                .dns { error("No external DNS") }.build()
            var recoveries = 0
            val url = "http://original.onion/$hash.gif"
            try {
                val client = AvatarNetworkClient(http.asNetworkClient()) { who, source, blob ->
                    assertEquals(author, who); assertEquals(url, source); assertEquals(hash, blob.hash)
                    recoveries++
                    VerifiedAvatar("recovered".toByteArray(), "image/gif")
                }
                client.executeRequest(marked(url)) { response ->
                    val sink = Buffer(); response.body!!.writeTo(sink)
                    assertEquals("recovered", sink.readUtf8())
                }
                assertEquals(1, recoveries)
                assertEquals(2, server.requestCount)
            } finally {
                http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test fun `explicit nostr build status is recognized only on successful final response`() = runBlocking {
        for (host in listOf("nostr.build", "cdn.nostr.build")) {
            withResponse(host, "404") { http ->
                var recovered = false
                AvatarNetworkClient(http.asNetworkClient()) { _, _, _ ->
                    recovered = true; VerifiedAvatar(byteArrayOf(1), "image/gif")
                }.executeRequest(marked()) { }
                assertTrue(recovered)
            }
        }
        for (code in listOf(301, 403, 429)) {
            withResponse("cdn.nostr.build", "404", code) { http ->
                AvatarNetworkClient(http.asNetworkClient()) { _, _, _ -> error("Preserve denial/redirect handling") }
                    .executeRequest(marked()) { assertEquals(code, it.code) }
            }
        }
    }

    @Test fun `normal nostr build originals and other host headers remain unchanged`() = runBlocking {
        val cases = listOf("cdn.nostr.build" to null, "cdn.nostr.build" to "200",
            "cdn.nostr.build" to "4040", "nostr.build.attacker.example" to "404",
            "notnostr.build" to "404", "other.example" to "404")
        for ((host, status) in cases) withResponse(host, status) { http ->
            AvatarNetworkClient(http.asNetworkClient()) { _, _, _ -> error("Working origin must not recover") }
                .executeRequest(marked()) {
                    val sink = Buffer(); it.body!!.writeTo(sink)
                    assertEquals("original bytes", sink.readUtf8())
                }
        }
    }

    @Test fun `unmarked or non hash named images never enter soft404 recovery`() = runBlocking {
        for (request in listOf(NetworkRequest(original), marked("https://origin.example/avatar.gif"))) {
            withResponse("cdn.nostr.build", "404") { http ->
                AvatarNetworkClient(http.asNetworkClient()) { _, _, _ -> error("Not eligible for recovery") }
                    .executeRequest(request) { assertEquals(200, it.code) }
            }
        }
    }

    @Test fun `unavailable recovery does not deliver or cache the placeholder`() = runBlocking {
        withResponse("cdn.nostr.build", "404") { http ->
            var attempts = 0
            val client = AvatarNetworkClient(http.asNetworkClient()) { _, _, _ -> attempts++; null }
            try {
                client.executeRequest(marked()) { error("Missing-image bytes must not reach Coil") }
                fail("Expected unavailable avatar")
            } catch (expected: IOException) { assertEquals("avatar unavailable", expected.message) }
            assertEquals(1, attempts)
        }
    }

    private suspend fun withResponse(host: String, status: String?, code: Int = 200, block: suspend (OkHttpClient) -> Unit) {
        // Application interceptor produces the final response without any DNS or socket.
        val http = OkHttpClient.Builder().dns { error("No external DNS") }.addInterceptor {
            Response.Builder().request(Request.Builder().url("https://$host/image.gif").build())
                .protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body("original bytes".toResponseBody())
                .apply { status?.let { header("x-status", it) } }.build()
        }.build()
        try { block(http) }
        finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow() }
    }
}
