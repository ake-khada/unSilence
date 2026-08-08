package com.unsilence.app.data.media

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GuardedRemoteMediaDataSourceTest {
    @Test
    fun `readAt maps requested offset and length to an exact range`() {
        val source = "0123456789abcdef".encodeToByteArray()
        val ranges = mutableListOf<String>()
        val reader = GuardedHttpRangeReader(
            url = "https://media.example/video.mp4".toHttpUrl(),
            callFactory = rangeClient(source, ranges),
            maxNetworkBytes = 64,
            minimumFetchBytes = 1,
            maxCachedBytes = 32,
        )

        val output = ByteArray(4)
        assertEquals(4, reader.readAt(5, output, 0, output.size))
        assertArrayEquals("5678".encodeToByteArray(), output)
        assertEquals(listOf("bytes=5-8"), ranges)
        assertEquals(source.size.toLong(), reader.size())
        reader.close()
    }

    @Test
    fun `separate seeks assemble the original bytes`() {
        val source = "abcdefghijklmno".encodeToByteArray()
        val ranges = mutableListOf<String>()
        val reader = GuardedHttpRangeReader(
            url = "https://media.example/video.mp4".toHttpUrl(),
            callFactory = rangeClient(source, ranges),
            maxNetworkBytes = 64,
            minimumFetchBytes = 3,
            maxCachedBytes = 32,
        )
        val output = ByteArray(9)

        assertEquals(3, reader.readAt(0, output, 0, 3))
        assertEquals(3, reader.readAt(3, output, 3, 3))
        assertEquals(3, reader.readAt(6, output, 6, 3))

        assertArrayEquals("abcdefghi".encodeToByteArray(), output)
        assertEquals(listOf("bytes=0-2", "bytes=3-5", "bytes=6-8"), ranges)
        reader.close()
    }

    @Test
    fun `server ignoring ranges cannot stream beyond extraction cap`() {
        val source = ByteArray(32) { it.toByte() }
        val ranges = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                ranges += chain.request().header("Range").orEmpty()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(source.toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()
        val reader = GuardedHttpRangeReader(
            url = "https://media.example/video.mp4".toHttpUrl(),
            callFactory = client,
            maxNetworkBytes = 8,
            minimumFetchBytes = 1,
            maxCachedBytes = 8,
        )

        val output = ByteArray(2)
        assertEquals(2, reader.readAt(6, output, 0, output.size))
        assertArrayEquals(byteArrayOf(6, 7), output)
        assertEquals(8L, reader.networkBytesRead)
        assertEquals(listOf("bytes=6-7"), ranges)
        assertThrows(RemoteMediaByteLimitException::class.java) {
            reader.readAt(8, ByteArray(1), 0, 1)
        }
        assertEquals(8L, reader.networkBytesRead)
        reader.close()
    }

    @Test
    fun `range beyond source reports end of stream`() {
        val source = "short".encodeToByteArray()
        val reader = GuardedHttpRangeReader(
            url = "https://media.example/video.mp4".toHttpUrl(),
            callFactory = rangeClient(source, mutableListOf()),
            maxNetworkBytes = 64,
            minimumFetchBytes = 1,
        )

        assertEquals(-1, reader.readAt(source.size.toLong(), ByteArray(1), 0, 1))
        reader.close()
    }

    private fun rangeClient(source: ByteArray, ranges: MutableList<String>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val header = request.header("Range").orEmpty()
                ranges += header
                val match = RANGE.matchEntire(header)
                    ?: error("missing or invalid range: $header")
                val start = match.groupValues[1].toLong()
                val requestedEnd = match.groupValues[2].toLong()
                if (start >= source.size) {
                    return@Interceptor Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(416)
                        .message("Range Not Satisfiable")
                        .header("Content-Range", "bytes */${source.size}")
                        .body(ByteArray(0).toResponseBody())
                        .build()
                }
                val end = requestedEnd.coerceAtMost(source.lastIndex.toLong()).toInt()
                val body = source.copyOfRange(start.toInt(), end + 1)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes $start-$end/${source.size}")
                    .body(body.toResponseBody("video/mp4".toMediaType()))
                    .build()
            })
            .build()

    private companion object {
        val RANGE = Regex("""bytes=(\d+)-(\d+)""")
    }
}
