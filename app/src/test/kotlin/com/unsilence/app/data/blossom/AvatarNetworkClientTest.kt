package com.unsilence.app.data.blossom

import coil3.Extras
import coil3.network.*
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AvatarNetworkClientTest {
    private val url = "https://origin.example/${"ab".repeat(32)}.gif"
    private val request = NetworkRequest(url, extras = Extras.Builder().apply {
        set(AvatarRequestKey, AvatarRequest("cd".repeat(32), url))
    }.build())

    @Test fun `working original streams unchanged even when hash or size differ`() = runBlocking {
        for (body in listOf("transformed origin".toByteArray(), ByteArray(AvatarTransport.MAX_BYTES.toInt() + 1))) {
            val response = NetworkResponse(body = NetworkResponseBody(Buffer().write(body)))
            val client = AvatarNetworkClient(fake(response)) { _, _, _ -> error("Working original must not recover") }
            client.executeRequest(request) {
                assertSame(response, it)
                val sink = Buffer(); it.body!!.writeTo(sink)
                assertArrayEquals(body, sink.readByteArray())
            }
        }
    }

    @Test fun `404 invokes recovery and returns only its verified bytes`() = runBlocking {
        var recoveries = 0
        val client = AvatarNetworkClient(fake(NetworkResponse(404))) { author, original, blob ->
            assertEquals("cd".repeat(32), author); assertEquals(url, original); assertEquals("ab".repeat(32), blob.hash)
            recoveries++
            VerifiedAvatar(byteArrayOf(1, 2, 3), "image/gif")
        }
        client.executeRequest(request) {
            assertEquals(200, it.code)
            assertEquals("image/gif", it.headers["Content-Type"])
            val sink = Buffer(); it.body!!.writeTo(sink); assertArrayEquals(byteArrayOf(1, 2, 3), sink.readByteArray())
        }
        assertEquals(1, recoveries)
    }

    @Test fun `denials rate limiting and unmarked images never initiate recovery`() = runBlocking {
        for (code in listOf(301, 302, 307, 308, 401, 403, 429, 304)) {
            val client = AvatarNetworkClient(fake(NetworkResponse(code))) { _, _, _ -> error("No fallback") }
            client.executeRequest(request) { assertEquals(code, it.code) }
        }
        AvatarNetworkClient(fake(NetworkResponse(404))) { _, _, _ -> error("Not an avatar") }
            .executeRequest(NetworkRequest(url)) { assertEquals(404, it.code) }
    }

    @Test fun `Coil downstream IO failure never triggers another fetch`() = runBlocking {
        val client = AvatarNetworkClient(fake(NetworkResponse(200))) { _, _, _ -> error("Do not recover decoder or cache failure") }
        try { client.executeRequest(request) { throw IOException("cache failure") }; fail() }
        catch (expected: IOException) { assertEquals("cache failure", expected.message) }
    }

    @Test fun `exhausted recovery does not pass failed bytes to Coil`() = runBlocking {
        val client = AvatarNetworkClient(fake(NetworkResponse(404))) { _, _, _ -> null }
        try { client.executeRequest(request) { error("No image must escape") }; fail() }
        catch (expected: IOException) { assertEquals("avatar unavailable", expected.message) }
    }

    private fun fake(response: NetworkResponse) = object : NetworkClient {
        override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T = block(response)
    }
}
