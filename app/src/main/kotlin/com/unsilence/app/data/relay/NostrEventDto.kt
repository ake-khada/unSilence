package com.unsilence.app.data.relay

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
