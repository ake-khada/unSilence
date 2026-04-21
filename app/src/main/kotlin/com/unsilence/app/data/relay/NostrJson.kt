package com.unsilence.app.data.relay

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Shared lenient JSON instance for Nostr wire parsing.
 * Nostr events from random relays may have unknown fields — we ignore them.
 */
val NostrJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Extract the original author's pubkey from a kind-6 repost event.
 * Tries embedded JSON content first (deprecated format), then falls back
 * to the "p" tag (NIP-18 standard format).
 *
 * @param content the event's content field (may be blank for e-tag reposts)
 * @param tags    the event's tags field as a JSON string (e.g. `[["e","..."],["p","..."]]`)
 * @return the original author's hex pubkey, or null if not found
 */
/** Serialise a signed Quartz [Event] to the Nostr wire JSON string. */
fun toEventJson(event: Event): String = buildJsonObject {
    put("id",         event.id)
    put("pubkey",     event.pubKey)
    put("created_at", event.createdAt)
    put("kind",       event.kind)
    put("tags", buildJsonArray {
        event.tags.forEach { row ->
            add(buildJsonArray { row.forEach { cell -> add(JsonPrimitive(cell)) } })
        }
    })
    put("content", event.content)
    put("sig",     event.sig)
}.toString()

fun extractRepostAuthorPubkey(content: String, tags: String): String? {
    // Try embedded JSON content first (deprecated NIP-18 format)
    if (content.isNotBlank()) {
        val fromContent = runCatching {
            NostrJson.parseToJsonElement(content).jsonObject["pubkey"]?.jsonPrimitive?.content
        }.getOrNull()
        if (fromContent != null) return fromContent
    }
    // Fall back to p-tag (current NIP-18 format)
    return runCatching {
        NostrJson.parseToJsonElement(tags).jsonArray
            .firstOrNull { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "p" }
            ?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content
    }.getOrNull()
}
