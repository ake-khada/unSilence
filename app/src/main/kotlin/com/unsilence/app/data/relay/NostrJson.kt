package com.unsilence.app.data.relay

import kotlinx.serialization.json.Json

/**
 * Shared lenient JSON instance for Nostr wire parsing.
 * Nostr events from random relays may have unknown fields — we ignore them.
 */
val NostrJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
