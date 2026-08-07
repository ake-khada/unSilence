package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.model.RepostInfo
import com.unsilence.app.data.model.RepostPayload
import com.unsilence.app.data.model.VerifiedRepostEvent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A verified long-form body may legitimately reach 200k chars. Leave bounded
 * room for the remaining event fields and escaping, while refusing unbounded
 * attacker-controlled JSON before either a tree parse or native crypto.
 */
internal const val MAX_EMBEDDED_EVENT_JSON_CHARS = 256 * 1024
private val HEX_64 = Regex("^[0-9a-f]{64}$")

/**
 * Parses NIP-18 exactly once and makes its trust state explicit.
 *
 * The wrapper event authenticates only the reposter's assertion. Inner author,
 * body, tags, timestamp, and kind become renderable only when the embedded
 * event is structurally complete, canonically id-checked, Schnorr-verified,
 * and consistent with the wrapper's primary `e`/`a` reference. Everything
 * else is reference-only protocol data and is never exposed as post text.
 */
internal fun parseRepostInfo(
    rawKind: Int,
    content: String,
    tags: List<List<String>>,
    verifyEmbedded: (EventDto) -> Boolean = ::verifyEmbeddedEvent,
): RepostInfo? {
    if (rawKind != 6 && rawKind != 16) return null

    val eTag = tags.firstOrNull { it.getOrNull(0) == "e" }
    val eTargetId = eTag?.getOrNull(1)?.normalizedHex64()
    val relayHint = eTag?.getOrNull(2)?.safeHint()
    // NIP-18 uses lowercase a. Uppercase A belongs to NIP-22 threading
    // and must not be reinterpreted as the repost target.
    val addressTag = tags.firstOrNull { it.getOrNull(0) == "a" }
    val addressCoordinate = addressTag?.getOrNull(1)?.validAddressCoordinate()
    val addressRelayHint = addressTag?.getOrNull(2)?.safeHint()
    val coordinateAuthor = addressCoordinate?.split(':', limit = 3)?.getOrNull(1)
    val pAuthorHint = tags.firstOrNull { it.getOrNull(0) == "p" }
        ?.getOrNull(1)
        ?.normalizedHex64()
    val proxyUrl = tags.firstOrNull { tag ->
        tag.getOrNull(0) == "proxy" && tag.getOrNull(2) == "activitypub"
    }?.getOrNull(1)?.safeHttpUrl()

    var decoded: EventDto? = null
    var contentIdHint: String? = null
    if (content.isNotBlank() && content.length <= MAX_EMBEDDED_EVENT_JSON_CHARS) {
        decoded = runCatching { NostrJson.decodeFromString<EventDto>(content) }.getOrNull()
        contentIdHint = decoded?.id?.normalizedHex64() ?: parseReferenceId(content)
    }

    val verified = decoded
        ?.takeIf(::hasValidEmbeddedShape)
        ?.takeIf { candidate -> embeddedMatchesWrapper(candidate, eTargetId, addressCoordinate) }
        // Cheap structural/reference checks precede native crypto so a signed
        // wrapper with an obviously mismatched payload cannot buy a verify.
        ?.takeIf { candidate -> runCatching { verifyEmbedded(candidate) }.getOrDefault(false) }
        ?.let { candidate ->
            VerifiedRepostEvent(
                id = candidate.id,
                pubkey = candidate.pubkey,
                kind = candidate.kind,
                content = candidate.content,
                createdAt = candidate.createdAt,
                tags = candidate.tags,
            )
        }

    val payload = verified?.let(RepostPayload::VerifiedEmbedded) ?: RepostPayload.ReferenceOnly
    return RepostInfo(
        targetId = eTargetId ?: verified?.id ?: contentIdHint,
        relayHint = relayHint,
        addressCoordinate = addressCoordinate,
        addressRelayHint = addressRelayHint,
        targetAuthorHint = verified?.pubkey ?: coordinateAuthor ?: pAuthorHint,
        proxyUrl = proxyUrl,
        payload = payload,
    )
}

private fun verifyEmbeddedEvent(event: EventDto): Boolean = event.hasValidCanonicalSignature()

private fun hasValidEmbeddedShape(event: EventDto): Boolean = event.hasValidSignedEventShape()

private fun embeddedMatchesWrapper(
    event: EventDto,
    eTargetId: String?,
    addressCoordinate: String?,
): Boolean {
    // Be liberal about the wrapper kind: older clients have used kind 6 for
    // non-kind-1 targets. The signed inner kind is authoritative and safe to
    // render; rejecting it would lose existing article/video reposts.
    if (eTargetId != null && eTargetId != event.id) return false
    if (addressCoordinate != null && addressCoordinate != event.addressCoordinate()) return false
    return true
}

private fun EventDto.addressCoordinate(): String {
    val dTag = tags.firstOrNull { it.getOrNull(0) == "d" }?.getOrNull(1).orEmpty()
    return "$kind:$pubkey:$dTag"
}

/** An incomplete JSON object may contribute only a bounded, validated id hint. */
private fun parseReferenceId(content: String): String? = runCatching {
    NostrJson.parseToJsonElement(content).jsonObject["id"]?.jsonPrimitive?.content?.normalizedHex64()
}.getOrNull()

private fun String.normalizedHex64(): String? =
    lowercase().takeIf(HEX_64::matches)

private fun String.safeHint(): String? =
    trim().takeIf { it.isNotEmpty() && it.length <= 2_048 && it.none(Char::isISOControl) }

private fun String.safeHttpUrl(): String? =
    trim().takeIf { value ->
        value.length <= 2_048 &&
            value.none(Char::isISOControl) &&
            (value.startsWith("https://") || value.startsWith("http://"))
    }

private fun String.validAddressCoordinate(): String? {
    if (length > 1_024 || any(Char::isISOControl)) return null
    val parts = split(':', limit = 3)
    if (parts.size != 3) return null
    val kind = parts[0].toIntOrNull()?.takeIf { it in 0..65_535 } ?: return null
    val author = parts[1].normalizedHex64() ?: return null
    return "$kind:$author:${parts[2]}"
}

/** Attach the parsed trust state after the outer event has passed verification. */
internal fun NostrEvent.withParsedRepostMetadata(): NostrEvent {
    if (kind != 6 && kind != 16) return this
    val parsed = repostInfo ?: parseRepostInfo(kind, content, tags)
    val (hasWarning, warningReason) = effectiveContentWarning(tags, parsed)
    if (parsed === repostInfo &&
        hasWarning == hasContentWarning &&
        warningReason == contentWarningReason
    ) return this
    return copy(
        hasContentWarning = hasWarning,
        contentWarningReason = warningReason,
        repostInfo = parsed,
    )
}

/**
 * Content that is safe to interpret as user-authored text or scan for network
 * references. A repost wrapper's raw JSON is excluded unless its embedded
 * event passed canonical-id and signature verification.
 */
internal fun NostrEvent.authenticatedContentOrNull(): String? = when {
    kind != 6 && kind != 16 -> content
    else -> (repostInfo?.payload as? RepostPayload.VerifiedEmbedded)?.event?.content
}

internal fun parseContentWarning(tags: List<List<String>>): Pair<Boolean, String?> {
    val warning = tags.firstOrNull { it.getOrNull(0) == "content-warning" }
        ?: return false to null
    return true to warning.getOrNull(1)?.takeIf { it.isNotBlank() }
}

/** Only a verified inner event can contribute an effective content warning. */
internal fun effectiveContentWarning(
    wrapperTags: List<List<String>>,
    repostInfo: RepostInfo?,
): Pair<Boolean, String?> {
    val (wrapperWarning, wrapperReason) = parseContentWarning(wrapperTags)
    val verified = (repostInfo?.payload as? RepostPayload.VerifiedEmbedded)?.event
    val (innerWarning, innerReason) = verified?.let { parseContentWarning(it.tags) } ?: (false to null)
    return (wrapperWarning || innerWarning) to (wrapperReason ?: innerReason)
}
