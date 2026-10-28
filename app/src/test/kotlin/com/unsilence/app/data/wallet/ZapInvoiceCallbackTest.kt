package com.unsilence.app.data.wallet

import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Preservation checks for the production callback stage; no signer app, wallet or relay. */
class ZapInvoiceCallbackTest {
    @Test fun `signed public request reaches callback unchanged and returns invoice`() = exercise(
        MockResponse().setBody("""{"pr":"lnbc10n1fixture"}"""), expectedInvoice = "lnbc10n1fixture",
    )
    @Test fun `LNURL error response preserves callback request and propagates failure`() = exercise(
        MockResponse().setBody("""{"status":"ERROR","reason":"fixture unavailable"}"""),
        expectedError = "Lightning service: fixture unavailable",
    )
    @Test fun `HTTP callback failure preserves signed request and reason`() = exercise(
        MockResponse().setResponseCode(503).setBody("""{"status":"ERROR","reason":"fixture unavailable"}"""),
        expectedError = "Lightning service rejected the zap: fixture unavailable",
    )
    @Test fun `mismatched invoice amount is still rejected`() = exercise(
        MockResponse().setBody("""{"pr":"lnbc20n1fixture"}"""),
        expectedError = "Lightning service returned invoice for 2 sats instead of 1 sats",
    )

    private fun exercise(response: MockResponse, expectedInvoice: String? = null, expectedError: String? = null) = runBlocking {
        val fixture = ZapCryptoTestFixture()
        val relays = listOf("wss://receipt-a.example", "wss://receipt-b.example")
        val signed = fixture.sender.signer.sign(EventTemplate<Event>(
            createdAt = 1_700_000_000L, kind = 9734,
            tags = arrayOf((listOf("relays") + relays).toTypedArray(), arrayOf("amount", "1000"),
                arrayOf("p", fixture.recipient.pubkey), arrayOf("e", fixture.defaultTarget)),
            content = "Thanks + & \"hello\" café",
        ))
        val exactJson = toEventJson(signed)
        MockWebServer().use { server ->
            server.start(); server.enqueue(response)
            val client = OkHttpClient.Builder().dns { error("No external DNS allowed") }.build()
            try {
                val callback = server.url("/callback?token=keep").newBuilder().host("127.0.0.1").build()
                val result = fetchZapInvoice(client, callback.toString(), 1000L, exactJson)
                if (expectedError != null) {
                    assertTrue(result.isFailure)
                    assertEquals(expectedError, result.exceptionOrNull()!!.message)
                } else assertEquals(expectedInvoice, result.getOrThrow())
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method)
                val url = request.requestUrl!!
                assertEquals("keep", url.queryParameter("token"))
                assertEquals("1000", url.queryParameter("amount"))
                assertEquals(exactJson, url.queryParameter("nostr"))
                val received = NostrJson.parseToJsonElement(url.queryParameter("nostr")!!).jsonObject
                assertEquals(listOf("relays") + relays, received["tags"]!!.jsonArray.first().jsonArray.map { it.jsonPrimitive.content })
                assertEquals(signed.sig, received["sig"]!!.jsonPrimitive.content)
                assertEquals(1, server.requestCount)
                assertNull("Unexpected second HTTP hop", server.takeRequest(100, TimeUnit.MILLISECONDS))
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }
}
