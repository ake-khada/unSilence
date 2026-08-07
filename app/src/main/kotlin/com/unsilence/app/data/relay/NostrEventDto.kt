package com.unsilence.app.data.relay

import com.unsilence.app.data.auth.verifyNostrEventFields
import com.unsilence.app.data.memory.NostrEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Streaming wire representation shared by relay EVENT decoding and NIP-18
 * embedded-event decoding. Required fields intentionally have no defaults:
 * an id-only JSON object is a reference hint, not an event.
 */
@Serializable
internal data class EventDto(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val content: String,
    @SerialName("created_at") val createdAt: Long,
    val tags: List<List<String>>,
    val sig: String,
)

internal const val MAX_SIGNED_EVENT_TAGS = 2_000
internal const val MAX_SIGNED_EVENT_TAG_PARTS = 100
private val SIGNED_EVENT_HEX_64 = Regex("^[0-9a-f]{64}$")
private val SIGNED_EVENT_HEX_128 = Regex("^[0-9a-f]{128}$")

/**
 * Shared structural boundary for complete signed events embedded inside another
 * protocol payload. Shape checks deliberately run before native crypto so an
 * authenticated wrapper cannot buy unbounded allocation or verification work.
 */
internal fun EventDto.hasValidSignedEventShape(
    allowedKinds: IntRange = 0..65_535,
): Boolean =
    SIGNED_EVENT_HEX_64.matches(id) &&
        SIGNED_EVENT_HEX_64.matches(pubkey) &&
        SIGNED_EVENT_HEX_128.matches(sig) &&
        createdAt >= 0L &&
        kind in allowedKinds &&
        tags.size <= MAX_SIGNED_EVENT_TAGS &&
        tags.all { it.isNotEmpty() && it.size <= MAX_SIGNED_EVENT_TAG_PARTS }

/** One canonical-id + BIP-340 implementation for every embedded-event trust boundary. */
internal fun EventDto.hasValidCanonicalSignature(): Boolean = runCatching {
    verifyNostrEventFields(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        sig = sig,
    )
}.getOrDefault(false)

/** Build the raw outer event. Embedded repost data is attached only after outer verification. */
internal fun EventDto.toNostrEvent(relayUrl: String): NostrEvent {
    val (replyToId, rootId) = when (kind) {
        1111 -> parseNip22Threading(tags)
        1, 6, 16, 9734, 9735, 20, 21, 22, 34235, 34236, 30023 -> parseNip10Threading(tags)
        else -> Pair(null, null)
    }
    val (hasCw, cwReason) = parseContentWarning(tags)
    return NostrEvent(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        createdAt = createdAt,
        tags = tags,
        sig = sig,
        relayUrl = relayUrl,
        replyToId = replyToId,
        rootId = rootId,
        hasContentWarning = hasCw,
        contentWarningReason = cwReason,
        firstSeenAt = System.currentTimeMillis(),
        relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add(relayUrl) },
    )
}
