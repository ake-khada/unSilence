package com.unsilence.app.data.wallet

import com.unsilence.app.data.network.UntrustedHttpNetworkGuard
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class LnurlSafetyTest {
    private val cap = 1024 * 1024

    @Test fun `metadata and callback client checks the connected peer on each redirect hop`() {
        val client = lnurlHttpClient(OkHttpClient())
        assertTrue(client.followRedirects)
        assertEquals(1, client.networkInterceptors.count { it === UntrustedHttpNetworkGuard })
    }

    @Test fun `accepts exactly one MiB including multibyte UTF-8`() {
        val text = "é".repeat(cap / 2)
        assertEquals(text, body(Buffer().writeUtf8(text), -1).readLnurlBody())
    }

    @Test fun `rejects a known oversize length before reading`() {
        val source = Buffer().writeUtf8("unused")
        assertNull(body(source, cap + 1L).readLnurlBody())
        assertEquals(6L, source.size)
    }

    @Test fun `unknown length and decoded bodies stop at the first excess byte`() {
        val source = Buffer().write(ByteArray(cap + 64))
        assertTrue(body(source, -1).readLnurlBody() == null)
        assertEquals(63L, source.size)
    }

    @Test fun `invoice callback rejects oversized success and error documents`() = runBlocking {
        fun callbackClient(code: Int, json: String) = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body((json + " ".repeat(cap)).toResponseBody()).build()
        }.build()
        val url = "https://wallet.example/callback"
        val success = callbackClient(200, """{"pr":"lnbc10n1fixture"}""")
        assertTrue(fetchZapInvoice(success, url, 1000L, "{}").isFailure)
        val error = callbackClient(503, """{"status":"ERROR","reason":"fixture reason"}""")
        assertEquals("Lightning service rejected the zap: HTTP 503",
            fetchZapInvoice(error, url, 1000L, "{}").exceptionOrNull()?.message)
    }

    private fun body(bytes: Buffer, length: Long) = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength() = length
        override fun source() = bytes
    }
}
