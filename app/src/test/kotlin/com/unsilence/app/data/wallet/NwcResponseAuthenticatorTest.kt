package com.unsilence.app.data.wallet

import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.toEventJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip04Dm.crypto.Nip04
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NwcResponseAuthenticatorTest {
    private val clientPrivate = "11".repeat(32).hexToByteArray()
    private val walletPrivate = "22".repeat(32).hexToByteArray()
    private val attackerPrivate = "33".repeat(32).hexToByteArray()

    private val clientKeys = KeyPair(privKey = clientPrivate)
    private val walletKeys = KeyPair(privKey = walletPrivate)
    private val attackerKeys = KeyPair(privKey = attackerPrivate)
    private val walletSigner = NostrSignerInternal(walletKeys)
    private val attackerSigner = NostrSignerInternal(attackerKeys)

    private val clientPubkey = clientKeys.pubKey.toHexKey()
    private val walletPubkey = walletKeys.pubKey.toHexKey()
    private val requestEventId = "ab".repeat(32)
    private val requestPayloadId = "request-47"

    @Test
    fun `valid signed payment success authenticates and completes successfully`() = runTest {
        val response = authenticate(paymentEvent(), paymentExpectation(), ::decrypt)

        assertNotNull(response)
        assertTrue(payInvoiceResult(response!!).isSuccess)
    }

    @Test
    fun `valid signed payment errors preserve the existing user messages`() = runTest {
        val cases = listOf(
            "PAYMENT_FAILED" to "No route found — recipient may be unreachable",
            "INSUFFICIENT_BALANCE" to "Insufficient wallet balance",
            "QUOTA_EXCEEDED" to "Wallet spending limit reached",
            "NOT_FOUND" to "Invoice expired or not found",
            "OTHER" to "wallet detail",
        )

        for ((code, expectedMessage) in cases) {
            val event = paymentEvent(errorCode = code, errorMessage = "wallet detail")
            val response = authenticate(event, paymentExpectation(), ::decrypt)
            val result = payInvoiceResult(response!!)

            assertTrue(result.isFailure)
            assertEquals(expectedMessage, result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun `payment response with a tampered signature is rejected`() = runTest {
        val forged = paymentEvent().withTamperedSignature()
        var decryptCalled = false

        val response = authenticate(forged, paymentExpectation()) {
            decryptCalled = true
            decrypt(it)
        }

        assertNull(response)
        assertFalse(decryptCalled)
    }

    @Test
    fun `valid response signed by a different pubkey is rejected before decryption`() = runTest {
        val forged = paymentEvent(
            signer = attackerSigner,
            encryptionPrivate = walletPrivate,
        )
        var decryptCalled = false

        val response = authenticate(forged, paymentExpectation()) {
            decryptCalled = true
            decrypt(it)
        }

        assertNull(response)
        assertFalse(decryptCalled)
    }

    @Test
    fun `get balance payload cannot satisfy a pending payment`() = runTest {
        val replay = balanceEvent()

        assertNull(authenticate(replay, paymentExpectation(), ::decrypt))
    }

    @Test
    fun `mismatched echoed payload id is rejected`() = runTest {
        val replay = paymentEvent(payloadId = "old-request")

        assertNull(authenticate(replay, paymentExpectation(), ::decrypt))
    }

    @Test
    fun `correlated rejection reports why a wallet response was ignored`() = runTest {
        var rejection: NwcCorrelatedRejection? = null

        val response = authenticateNwcResponse(
            event = paymentEvent(payloadId = "old-request"),
            expected = paymentExpectation(),
            decrypt = ::decrypt,
            onCorrelatedRejection = { rejection = it },
        )

        assertNull(response)
        assertEquals(NwcCorrelatedRejection.WRONG_PAYLOAD_ID, rejection)
    }

    @Test
    fun `standard response without optional payload id remains compatible`() = runTest {
        val response = authenticate(paymentEvent(payloadId = null), paymentExpectation(), ::decrypt)

        assertNotNull(response)
    }

    @Test
    fun `stale signed response event cannot satisfy a new request event id`() = runTest {
        val stale = paymentEvent(eTag = "cd".repeat(32))

        assertNull(authenticate(stale, paymentExpectation(), ::decrypt))
    }

    @Test
    fun `rejected payment forgery leaves the slot open for the genuine response`() = runTest {
        val completion = CompletableDeferred<Result<Unit>>()
        fun offer(event: JsonObject) {
            val response = authenticate(event, paymentExpectation(), ::decrypt) ?: return
            completion.complete(payInvoiceResult(response))
        }

        offer(paymentEvent().withTamperedSignature())
        assertFalse(completion.isCompleted)

        offer(paymentEvent())
        assertTrue(completion.isCompleted)
        assertTrue(completion.await().isSuccess)
    }

    @Test
    fun `valid signed balance success authenticates and returns msats`() = runTest {
        val response = authenticate(balanceEvent(balance = 98_765L), balanceExpectation(), ::decrypt)

        assertNotNull(response)
        assertEquals(98_765L, balanceMsats(response!!))
    }

    @Test
    fun `valid signed balance error completes with no balance`() = runTest {
        val event = balanceEvent(errorCode = "UNAUTHORIZED", errorMessage = "not allowed")
        val response = authenticate(event, balanceExpectation(), ::decrypt)

        assertNotNull(response)
        assertNull(balanceMsats(response!!))
    }

    @Test
    fun `balance response with a tampered signature is rejected`() = runTest {
        val forged = balanceEvent().withTamperedSignature()

        assertNull(authenticate(forged, balanceExpectation(), ::decrypt))
    }

    @Test
    fun `payment payload cannot satisfy a pending balance query`() = runTest {
        val replay = paymentEvent()

        assertNull(authenticate(replay, balanceExpectation(), ::decrypt))
    }

    @Test
    fun `balance response with a mismatched echoed payload id is rejected`() = runTest {
        val replay = balanceEvent(payloadId = "old-request")

        assertNull(authenticate(replay, balanceExpectation(), ::decrypt))
    }

    @Test
    fun `standard balance response without optional payload id remains compatible`() = runTest {
        val response = authenticate(balanceEvent(payloadId = null), balanceExpectation(), ::decrypt)

        assertNotNull(response)
    }

    @Test
    fun `stale signed balance response cannot satisfy a new request event id`() = runTest {
        val stale = balanceEvent(eTag = "cd".repeat(32))

        assertNull(authenticate(stale, balanceExpectation(), ::decrypt))
    }

    @Test
    fun `rejected balance forgery leaves the slot open for the genuine response`() = runTest {
        val completion = CompletableDeferred<Long?>()
        fun offer(event: JsonObject) {
            val response = authenticate(event, balanceExpectation(), ::decrypt) ?: return
            completion.complete(balanceMsats(response))
        }

        offer(balanceEvent().withTamperedSignature())
        assertFalse(completion.isCompleted)

        offer(balanceEvent(balance = 42_000L))
        assertTrue(completion.isCompleted)
        assertEquals(42_000L, completion.await())
    }

    @Test
    fun `balance response signed by a different pubkey is rejected`() = runTest {
        val forged = balanceEvent(
            signer = attackerSigner,
            encryptionPrivate = walletPrivate,
        )

        assertNull(authenticate(forged, balanceExpectation(), ::decrypt))
    }

    private fun paymentExpectation() = NwcResponseExpectation(
        walletPubkey = walletPubkey,
        requestEventId = requestEventId,
        requestPayloadId = requestPayloadId,
        resultType = NWC_PAY_INVOICE,
    )

    private fun balanceExpectation() = NwcResponseExpectation(
        walletPubkey = walletPubkey,
        requestEventId = requestEventId,
        requestPayloadId = requestPayloadId,
        resultType = NWC_GET_BALANCE,
    )

    private fun authenticate(
        event: JsonObject,
        expected: NwcResponseExpectation,
        decrypt: (String) -> String?,
    ): AuthenticatedNwcResponse? = authenticateNwcResponse(
        event = event,
        expected = expected,
        decrypt = decrypt,
        onCorrelatedRejection = {},
    )

    private fun decrypt(ciphertext: String): String =
        Nip04.decrypt(ciphertext, clientPrivate, walletKeys.pubKey)

    private suspend fun paymentEvent(
        payloadId: String? = requestPayloadId,
        eTag: String = requestEventId,
        errorCode: String? = null,
        errorMessage: String? = null,
        signer: NostrSignerInternal = walletSigner,
        encryptionPrivate: ByteArray = walletPrivate,
    ): JsonObject = responseEvent(
        resultType = NWC_PAY_INVOICE,
        payloadId = payloadId,
        eTag = eTag,
        errorCode = errorCode,
        errorMessage = errorMessage,
        result = buildJsonObject { put("preimage", "01".repeat(32)) },
        signer = signer,
        encryptionPrivate = encryptionPrivate,
    )

    private suspend fun balanceEvent(
        payloadId: String? = requestPayloadId,
        eTag: String = requestEventId,
        balance: Long = 123_456L,
        errorCode: String? = null,
        errorMessage: String? = null,
        signer: NostrSignerInternal = walletSigner,
        encryptionPrivate: ByteArray = walletPrivate,
    ): JsonObject = responseEvent(
        resultType = NWC_GET_BALANCE,
        payloadId = payloadId,
        eTag = eTag,
        errorCode = errorCode,
        errorMessage = errorMessage,
        result = buildJsonObject { put("balance", balance) },
        signer = signer,
        encryptionPrivate = encryptionPrivate,
    )

    private suspend fun responseEvent(
        resultType: String,
        payloadId: String?,
        eTag: String,
        errorCode: String?,
        errorMessage: String?,
        result: JsonObject,
        signer: NostrSignerInternal,
        encryptionPrivate: ByteArray,
    ): JsonObject {
        val payload = buildJsonObject {
            put("result_type", resultType)
            if (payloadId != null) put("id", payloadId)
            if (errorCode == null) {
                put("error", JsonNull)
                put("result", result)
            } else {
                put("error", buildJsonObject {
                    put("code", errorCode)
                    put("message", errorMessage ?: "wallet error")
                })
                put("result", JsonNull)
            }
        }.toString()
        val encrypted = Nip04.encrypt(payload, encryptionPrivate, clientKeys.pubKey)
        val signed = signer.sign(
            EventTemplate<Event>(
                createdAt = 1_786_200_000L,
                kind = NWC_RESPONSE_KIND,
                tags = arrayOf(
                    arrayOf("p", clientPubkey),
                    arrayOf("e", eTag),
                ),
                content = encrypted,
            ),
        )
        return NostrJson.parseToJsonElement(toEventJson(signed)).jsonObject
    }

    private fun JsonObject.withTamperedSignature(): JsonObject {
        val signature = (getValue("sig") as JsonPrimitive).content
        val replacement = if (signature.last() == '0') '1' else '0'
        return JsonObject(this + ("sig" to JsonPrimitive(signature.dropLast(1) + replacement)))
    }
}
