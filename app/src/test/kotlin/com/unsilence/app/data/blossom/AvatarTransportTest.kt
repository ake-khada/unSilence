package com.unsilence.app.data.blossom

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest

class AvatarTransportTest {
    private val bytes = byteArrayOf(71, 73, 70, 56, 57, 97, 1, 2, 3, 4)
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `matching image bytes and animation format reach caller unchanged`() = fixture { server, client ->
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "image/gif"))
        val image = client.fetch("http://media.onion/$hash.gif", hash)!!
        assertArrayEquals(bytes, image.bytes)
        assertEquals("image/gif", image.contentType)
        val request = server.takeRequest()
        assertEquals("http://media.onion/$hash.gif", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun `mismatch and HTTP failure return no image`() = fixture { server, client ->
        server.enqueue(MockResponse().setBody("wrong"))
        server.enqueue(MockResponse().setResponseCode(404).setBody(Buffer().write(bytes)))
        assertNull(client.fetch("http://media.onion/$hash.gif", hash))
        assertNull(client.fetch("http://media.onion/$hash.gif", hash))
    }

    @Test fun `redirects stay guarded and bounded`() = fixture { server, client ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1/private"))
        assertNull(client.fetch("http://media.onion/$hash.gif", hash))
        assertEquals(1, server.requestCount)
        repeat(4) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://other.onion/$hash.gif")) }
        assertNull(client.fetch("http://media.onion/$hash.gif", hash))
        assertEquals(5, server.requestCount)
    }

    @Test fun `chunked oversized responses cannot become images`() = fixture { server, client ->
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(AvatarTransport.MAX_BYTES.toInt() + 1)), 8192))
        assertNull(client.fetch("http://media.onion/$hash.gif", hash))
    }

    private fun fixture(block: suspend (MockWebServer, AvatarTransport) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.port)))
                .dns { error("No external DNS in avatar fixtures") }.build()
            try { runBlocking { block(server, AvatarTransport(client)) } }
            finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }
}
