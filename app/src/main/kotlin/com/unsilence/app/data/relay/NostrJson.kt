package com.unsilence.app.data.relay

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
