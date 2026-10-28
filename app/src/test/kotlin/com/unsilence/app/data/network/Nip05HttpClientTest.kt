package com.unsilence.app.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Local HTTP proxy for synthetic onion names: exercises the unchanged network guard. */
class Nip05HttpClientTest {
    private val pubkey = "ab".repeat(32)
    private val key = Nip05VerificationCacheKey(pubkey, "alice@identity.onion")
    private val validBody = """{"names":{"alice":"$pubkey"}}"""

    @Test fun `301 target is never requested`() = assertRedirectIgnored(301)
    @Test fun `302 target is never requested`() = assertRedirectIgnored(302)
    @Test fun `307 target is never requested`() = assertRedirectIgnored(307)
    @Test fun `308 target is never requested`() = assertRedirectIgnored(308)

    private fun assertRedirectIgnored(code: Int) = withClient { server, client ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains("/.well-known/nostr.json")) {
                    MockResponse().setResponseCode(code)
                        .setHeader("Location", "http://target.onion/identity")
                } else MockResponse().setBody(validBody)
        }
        val status = runBlocking { client.resolve(key) }
        // Assert transport behavior first: a matching target must never rescue a redirect.
        assertEquals("redirect target received a request", 1, server.requestCount)
        assertEquals(Nip05VerificationStatus.UNVERIFIED, status)
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.path!!.contains("/.well-known/nostr.json?name=alice"))
    }

    @Test fun `direct 200 matching identity remains verified`() = assertBody(validBody, Nip05VerificationStatus.VERIFIED)
    @Test fun `direct 200 wrong name remains unverified`() =
        assertBody("""{"names":{"bob":"$pubkey"}}""", Nip05VerificationStatus.UNVERIFIED)
    @Test fun `direct 200 wrong pubkey remains unverified`() =
        assertBody("""{"names":{"alice":"${"cd".repeat(32)}"}}""", Nip05VerificationStatus.UNVERIFIED)
    @Test fun `oversized direct body remains unverified`() =
        assertBody(validBody + " ".repeat(Nip05HttpClient.MAX_BODY_BYTES.toInt()), Nip05VerificationStatus.UNVERIFIED)

    private fun assertBody(body: String, expected: Nip05VerificationStatus) = withClient { server, client ->
        server.enqueue(MockResponse().setBody(body))
        assertEquals(expected, runBlocking { client.resolve(key) })
        assertEquals(1, server.requestCount)
    }

    private fun withClient(block: (MockWebServer, Nip05HttpClient) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val base = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.port)))
                .dns { error("Fixture must use only its local proxy, never external DNS") }
                .build()
            try { block(server, Nip05HttpClient(base)) }
            finally { base.connectionPool.evictAll(); base.dispatcher.executorService.shutdownNow() }
        }
    }
}
