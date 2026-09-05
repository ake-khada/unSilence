package com.unsilence.app.data.zap

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.PendingPrivateZapDecrypt
import com.unsilence.app.data.memory.VerifiedPrivateZap
import com.unsilence.app.data.memory.ZapAttribution
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.MAX_EMBEDDED_EVENT_JSON_CHARS
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.hasValidCanonicalSignature
import com.unsilence.app.data.relay.hasValidSignedEventShape
import com.vitorpamplona.quartz.lightning.LnInvoiceUtil
import kotlinx.serialization.decodeFromString

/** Authority learned from the signed-in user's own LNURL-pay metadata. */
internal sealed interface OwnZapServiceAuthority {
    data object Unavailable : OwnZapServiceAuthority
    data object Unsupported : OwnZapServiceAuthority
    data class Trusted(val zapperPubkeys: Set<String>) : OwnZapServiceAuthority {
        constructor(zapperPubkey: String) : this(setOf(zapperPubkey))
    }
}

internal enum class ZapReceiptRejection {
    MALFORMED_RECIPIENT,
    OWN_AUTHORITY_UNSUPPORTED,
    WRONG_ZAPPER,
    MISSING_OR_INVALID_INVOICE,
    INVALID_REQUEST_AMOUNT,
    AMOUNT_MISMATCH,
    UNVERIFIED_REQUEST_WITHOUT_ZAPPER_AUTHORITY,
}

internal enum class ZapRequestFailure {
    MISSING_DESCRIPTION,
    MALFORMED_DESCRIPTION,
    INVALID_EVENT_SHAPE,
    INVALID_SIGNATURE,
    RECIPIENT_MISMATCH,
    TARGET_MISMATCH,
}

internal data class PrivateZapEnvelope(
    val ciphertext: String,
    val signerPubkey: String,
    val recipientPubkey: String,
    val targetTagName: String,
    val targetId: String,
)

private data class ZapTarget(val tagName: String, val value: String)

internal data class AuthenticatedZapReceipt(
    val receiptId: String,
    val recipientPubkey: String,
    val targetId: String?,
    val sats: Long,
    val attribution: ZapAttribution,
    val requestSignerPubkey: String?,
    val privateEnvelope: PrivateZapEnvelope?,
    val requestFailureRenderedAnonymous: ZapRequestFailure?,
    val selfClaimedClient: String?,
)

internal sealed interface ZapReceiptDecision {
    data class Accepted(val receipt: AuthenticatedZapReceipt) : ZapReceiptDecision
    data object PendingOwnAuthority : ZapReceiptDecision
    data class Rejected(
        val reason: ZapReceiptRejection,
        val requestFailure: ZapRequestFailure? = null,
        val selfClaimedClient: String? = null,
    ) : ZapReceiptDecision
}

private sealed interface RequestCheck {
    val amount: RequestAmount
    val selfClaimedClient: String?

    data class Verified(
        val event: EventDto,
        val attribution: ZapAttribution,
        override val amount: RequestAmount,
        val privateEnvelope: PrivateZapEnvelope?,
        override val selfClaimedClient: String?,
    ) : RequestCheck

    data class Invalid(
        val reason: ZapRequestFailure,
        override val amount: RequestAmount = RequestAmount.Absent,
        override val selfClaimedClient: String? = null,
    ) : RequestCheck
}

private sealed interface RequestAmount {
    data object Absent : RequestAmount
    data object Invalid : RequestAmount
    data class Present(val millisats: Long) : RequestAmount
}

private sealed interface OptionalTagValue {
    data object Absent : OptionalTagValue
    data object Invalid : OptionalTagValue
    data class Present(val value: String) : OptionalTagValue
}

/**
 * Applies the three independent NIP-57 gates in cheapest-first order:
 * recipient zapper authority, embedded-request attribution, and invoice amount.
 * The outer kind-9735 signature has already passed EventProcessor verification.
 */
internal fun authenticateZapReceipt(
    event: NostrEvent,
    ownPubkey: String?,
    ownAuthority: OwnZapServiceAuthority,
): ZapReceiptDecision {
    if (event.kind != 9735) {
        return ZapReceiptDecision.Rejected(ZapReceiptRejection.MALFORMED_RECIPIENT)
    }

    val recipient = event.tags.singleRequiredValue("p")
        ?: return ZapReceiptDecision.Rejected(ZapReceiptRejection.MALFORMED_RECIPIENT)
    if (!recipient.isHex64()) {
        return ZapReceiptDecision.Rejected(ZapReceiptRejection.MALFORMED_RECIPIENT)
    }

    val ownReceipt = ownPubkey != null && recipient == ownPubkey
    if (ownReceipt) {
        when (ownAuthority) {
            OwnZapServiceAuthority.Unavailable -> return ZapReceiptDecision.PendingOwnAuthority
            OwnZapServiceAuthority.Unsupported -> {
                return ZapReceiptDecision.Rejected(ZapReceiptRejection.OWN_AUTHORITY_UNSUPPORTED)
            }
            is OwnZapServiceAuthority.Trusted -> if (event.pubkey !in ownAuthority.zapperPubkeys) {
                return ZapReceiptDecision.Rejected(ZapReceiptRejection.WRONG_ZAPPER)
            }
        }
    }

    val invoiceMsats = event.tags.singleRequiredValue("bolt11")
        ?.let(::decodeBolt11AmountMsats)
        ?.takeIf { it > 0L }
        ?: return ZapReceiptDecision.Rejected(ZapReceiptRejection.MISSING_OR_INVALID_INVOICE)

    val target = event.targetTagValue()
    val request = verifyEmbeddedZapRequest(event, recipient, target)
    if (!ownReceipt && request is RequestCheck.Invalid) {
        return ZapReceiptDecision.Rejected(
            reason = ZapReceiptRejection.UNVERIFIED_REQUEST_WITHOUT_ZAPPER_AUTHORITY,
            requestFailure = request.reason,
            selfClaimedClient = request.selfClaimedClient,
        )
    }

    when (val requestAmount = request.amount) {
        RequestAmount.Invalid -> {
            return ZapReceiptDecision.Rejected(
                ZapReceiptRejection.INVALID_REQUEST_AMOUNT,
                requestFailure = (request as? RequestCheck.Invalid)?.reason,
                selfClaimedClient = request.selfClaimedClient,
            )
        }
        is RequestAmount.Present -> if (requestAmount.millisats <= 0L) {
            return ZapReceiptDecision.Rejected(
                ZapReceiptRejection.INVALID_REQUEST_AMOUNT,
                requestFailure = (request as? RequestCheck.Invalid)?.reason,
                selfClaimedClient = request.selfClaimedClient,
            )
        } else if (requestAmount.millisats != invoiceMsats) {
            return ZapReceiptDecision.Rejected(
                ZapReceiptRejection.AMOUNT_MISMATCH,
                requestFailure = (request as? RequestCheck.Invalid)?.reason,
                selfClaimedClient = request.selfClaimedClient,
            )
        }
        RequestAmount.Absent -> Unit
    }

    val sats = invoiceMsats / 1_000L
    val verified = request as? RequestCheck.Verified
    val invalid = request as? RequestCheck.Invalid
    return ZapReceiptDecision.Accepted(
        AuthenticatedZapReceipt(
            receiptId = event.id,
            recipientPubkey = recipient,
            targetId = target?.value,
            sats = sats,
            attribution = verified?.attribution ?: ZapAttribution.Unverified,
            requestSignerPubkey = verified?.event?.pubkey,
            privateEnvelope = verified?.privateEnvelope,
            requestFailureRenderedAnonymous = invalid?.reason,
            selfClaimedClient = verified?.selfClaimedClient ?: invalid?.selfClaimedClient,
        )
    )
}

private fun verifyEmbeddedZapRequest(
    receipt: NostrEvent,
    recipientPubkey: String,
    receiptTarget: ZapTarget?,
): RequestCheck {
    val descriptionValues = receipt.tags.filter { it.getOrNull(0) == "description" }
    if (descriptionValues.isEmpty()) return RequestCheck.Invalid(ZapRequestFailure.MISSING_DESCRIPTION)
    if (descriptionValues.size != 1) return RequestCheck.Invalid(ZapRequestFailure.MALFORMED_DESCRIPTION)
    val raw = descriptionValues.single().getOrNull(1)
        ?.takeIf { it.isNotBlank() && it.length <= MAX_EMBEDDED_EVENT_JSON_CHARS }
        ?: return RequestCheck.Invalid(ZapRequestFailure.MALFORMED_DESCRIPTION)
    val request = runCatching { NostrJson.decodeFromString<EventDto>(raw) }.getOrNull()
        ?: return RequestCheck.Invalid(ZapRequestFailure.MALFORMED_DESCRIPTION)
    val client = request.selfClaimedClient()
    if (!request.hasValidSignedEventShape(9734..9734)) {
        return RequestCheck.Invalid(
            reason = ZapRequestFailure.INVALID_EVENT_SHAPE,
            selfClaimedClient = client,
        )
    }

    // Amount integrity is independent of sender attribution. Carry the parsed
    // amount even when a later recipient/target/signature gate fails so a
    // malformed or tampered request cannot become an amount-check bypass merely
    // by degrading to anonymous behind a trusted zapper receipt.
    val amount = when (val amountTag = request.tags.optionalSingleValue("amount")) {
        OptionalTagValue.Absent -> RequestAmount.Absent
        OptionalTagValue.Invalid -> RequestAmount.Invalid
        is OptionalTagValue.Present -> amountTag.value.toLongOrNull()
            ?.let(RequestAmount::Present)
            ?: RequestAmount.Invalid
    }

    val requestRecipient = request.tags.singleRequiredValue("p")
    if (requestRecipient != recipientPubkey) {
        return RequestCheck.Invalid(ZapRequestFailure.RECIPIENT_MISMATCH, amount, client)
    }
    if (!request.targetsMatch(receipt, receiptTarget)) {
        return RequestCheck.Invalid(ZapRequestFailure.TARGET_MISMATCH, amount, client)
    }
    if (!request.hasValidCanonicalSignature()) {
        return RequestCheck.Invalid(ZapRequestFailure.INVALID_SIGNATURE, amount, client)
    }
    val anonTag = request.tags.optionalSingleValue("anon")
    val isAnonymous = anonTag !is OptionalTagValue.Absent
    val privateEnvelope = receiptTarget?.let { target ->
        (anonTag as? OptionalTagValue.Present)?.value?.takeIf(String::isNotBlank)?.let { ciphertext ->
            PrivateZapEnvelope(
                ciphertext = ciphertext,
                signerPubkey = request.pubkey,
                recipientPubkey = recipientPubkey,
                targetTagName = target.tagName,
                targetId = target.value,
            )
        }
    }
    val attribution = if (isAnonymous) {
        ZapAttribution.IntentionallyAnonymous
    } else {
        ZapAttribution.Identified(request.pubkey, request.content.takeIf(String::isNotBlank))
    }
    return RequestCheck.Verified(request, attribution, amount, privateEnvelope, client)
}

private fun EventDto.targetsMatch(
    receipt: NostrEvent,
    selectedReceiptTarget: ZapTarget?,
): Boolean {
    val receiptE = receipt.tags.optionalSingleValue("e")
    val requestE = tags.optionalSingleValue("e")
    val receiptA = receipt.tags.optionalSingleValue("a")
    val requestA = tags.optionalSingleValue("a")
    if (receiptE is OptionalTagValue.Invalid || requestE is OptionalTagValue.Invalid ||
        receiptA is OptionalTagValue.Invalid || requestA is OptionalTagValue.Invalid
    ) return false
    if (receiptE != requestE || receiptA != requestA) return false
    return when (selectedReceiptTarget) {
        null -> receiptE is OptionalTagValue.Absent && receiptA is OptionalTagValue.Absent
        else -> true
    }
}

private fun NostrEvent.targetTagValue(): ZapTarget? {
    val e = tags.optionalSingleValue("e")
    if (e is OptionalTagValue.Present) return ZapTarget("e", e.value)
    val a = tags.optionalSingleValue("a")
    return (a as? OptionalTagValue.Present)?.let { ZapTarget("a", it.value) }
}

private fun List<List<String>>.singleRequiredValue(name: String): String? {
    val matches = filter { it.getOrNull(0) == name }
    if (matches.size != 1) return null
    return matches.single().getOrNull(1)?.takeIf(String::isNotBlank)
}

private fun List<List<String>>.optionalSingleValue(name: String): OptionalTagValue {
    val matches = filter { it.getOrNull(0) == name }
    if (matches.isEmpty()) return OptionalTagValue.Absent
    if (matches.size != 1) return OptionalTagValue.Invalid
    val value = matches.single().getOrNull(1) ?: return OptionalTagValue.Invalid
    return OptionalTagValue.Present(value)
}

private fun EventDto.selfClaimedClient(): String? = tags
    .firstOrNull { it.getOrNull(0) == "client" }
    ?.getOrNull(1)
    ?.trim()
    ?.filter { !it.isISOControl() }
    ?.takeIf { it.isNotEmpty() }
    ?.take(80)

private fun String.isHex64(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

/** Validates the full BOLT-11 checksum before converting its amount to millisats. */
internal fun decodeBolt11AmountMsats(invoice: String): Long? = runCatching {
    LnInvoiceUtil.getAmountInSats(invoice.trim())
        .movePointRight(3)
        .longValueExact()
}.getOrNull()

/**
 * Authenticates the decrypted private-zap event before its claimed human
 * identity or message can replace the anonymous receipt presentation.
 */
internal fun authenticatePrivateZapPayload(
    plaintext: String,
    pending: PendingPrivateZapDecrypt,
): VerifiedPrivateZap? {
    if (plaintext.length > MAX_EMBEDDED_EVENT_JSON_CHARS) return null
    val event = runCatching { NostrJson.decodeFromString<EventDto>(plaintext) }.getOrNull()
        ?: return null
    if (!event.hasValidSignedEventShape(9733..9734)) return null
    if (event.tags.singleRequiredValue("p") != pending.recipientPubkey) return null
    if (event.tags.singleRequiredValue(pending.targetTagName) != pending.targetId) return null
    if (!event.hasValidCanonicalSignature()) return null
    return VerifiedPrivateZap(
        senderPubkey = event.pubkey,
        comment = event.content.takeIf(String::isNotBlank),
    )
}
