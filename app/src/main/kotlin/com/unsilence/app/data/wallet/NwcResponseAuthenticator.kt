package com.unsilence.app.data.wallet

import com.unsilence.app.data.auth.verifyNostrEventFields
import com.unsilence.app.data.relay.NostrJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal const val NWC_RESPONSE_KIND = 23195
internal const val NWC_PAY_INVOICE = "pay_invoice"
internal const val NWC_GET_BALANCE = "get_balance"

/**
 * Correlation and trust inputs for one outstanding NIP-47 request.
 *
 * [requestEventId] is the protocol correlation key: it must appear in the
 * authenticated response event's `e` tag. [requestPayloadId] is a legacy
 * extension emitted by unSilence. It is checked when a wallet echoes it, but
 * its absence is accepted because NIP-47 does not require a payload `id`.
 */
internal data class NwcResponseExpectation(
    val walletPubkey: String,
    val requestEventId: String,
    val requestPayloadId: String,
    val resultType: String,
)

internal data class AuthenticatedNwcResponse(
    val payload: JsonObject,
)

/**
 * Authenticates and decrypts one NIP-47 response event.
 *
 * The order is deliberate: reject cheap mismatches before Schnorr work, and
 * authenticate the complete outer event before decrypting attacker-controlled
 * ciphertext. Any malformed or unauthenticated input returns null so the real
 * wallet response can still arrive and satisfy the pending request.
 */
internal fun authenticateNwcResponse(
    event: JsonObject,
    expected: NwcResponseExpectation,
    decrypt: (String) -> String?,
): AuthenticatedNwcResponse? {
    return try {
        val kind = event.numberField("kind")?.toIntExactOrNull() ?: return null
        if (kind != NWC_RESPONSE_KIND) return null

        val pubkey = event.stringField("pubkey") ?: return null
        if (pubkey != expected.walletPubkey) return null

        val tags = event.nostrTags() ?: return null
        if (tags.none { it.size >= 2 && it[0] == "e" && it[1] == expected.requestEventId }) {
            return null
        }

        val id = event.stringField("id") ?: return null
        val createdAt = event.numberField("created_at") ?: return null
        val content = event.stringField("content") ?: return null
        val signature = event.stringField("sig") ?: return null
        if (!verifyNostrEventFields(id, pubkey, createdAt, kind, tags, content, signature)) {
            return null
        }

        val plaintext = decrypt(content) ?: return null
        val payload = NostrJson.parseToJsonElement(plaintext) as? JsonObject ?: return null
        if (payload.stringField("result_type") != expected.resultType) return null

        // Older NIP-47 payloads do not define an id. If a wallet mirrors the
        // extension unSilence sends, however, it must match this request.
        if (payload.containsKey("id") && payload.stringField("id") != expected.requestPayloadId) {
            return null
        }

        AuthenticatedNwcResponse(payload)
    } catch (_: Exception) {
        null
    }
}

/** Preserve the existing payment error-code-to-message contract. */
internal fun payInvoiceResult(response: AuthenticatedNwcResponse): Result<Unit> {
    val errorElement = response.payload["error"]
    if (errorElement == null || errorElement is JsonNull) return Result.success(Unit)

    val error = errorElement as? JsonObject
    val code = error?.stringField("code")
    val rawMessage = error?.stringField("message")
    val userMessage = when (code) {
        "PAYMENT_FAILED" -> "No route found — recipient may be unreachable"
        "INSUFFICIENT_BALANCE" -> "Insufficient wallet balance"
        "QUOTA_EXCEEDED" -> "Wallet spending limit reached"
        "NOT_FOUND" -> "Invoice expired or not found"
        else -> rawMessage?.take(80) ?: "Payment failed"
    }
    return Result.failure(Exception(userMessage))
}

internal fun balanceMsats(response: AuthenticatedNwcResponse): Long? {
    val errorElement = response.payload["error"]
    if (errorElement != null && errorElement !is JsonNull) return null
    val result = response.payload["result"] as? JsonObject ?: return null
    return (result["balance"] as? JsonPrimitive)?.longOrNull
}

private fun JsonObject.stringField(name: String): String? {
    val value = this[name] as? JsonPrimitive ?: return null
    if (!value.isString) return null
    return value.content
}

private fun JsonObject.numberField(name: String): Long? {
    val value = this[name] as? JsonPrimitive ?: return null
    if (value.isString) return null
    return value.longOrNull
}

private fun JsonObject.nostrTags(): List<List<String>>? {
    val outer = this["tags"] as? JsonArray ?: return null
    val parsed = ArrayList<List<String>>(outer.size)
    for (tagElement in outer) {
        val tagArray = tagElement as? JsonArray ?: return null
        val tag = ArrayList<String>(tagArray.size)
        for (valueElement in tagArray) {
            val value = valueElement as? JsonPrimitive ?: return null
            if (!value.isString) return null
            tag += value.content
        }
        parsed += tag
    }
    return parsed
}

private fun Long.toIntExactOrNull(): Int? {
    if (this !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
    return toInt()
}
