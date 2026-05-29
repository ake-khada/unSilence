package com.unsilence.app.data.relay

import android.util.Log

private const val TAG = "RelayUrlUtil"

/**
 * Normalize a relay URL for consistent storage and comparison.
 * Rules: trim → strip http(s):// → reject ws:// (cleartext blocked by Android NSP) →
 * prepend wss:// if missing → validate domain has dot → strip trailing slash.
 * Returns null if the URL is blank, cleartext, or has no valid domain.
 */
fun normalizeRelayUrl(raw: String): String? {
    var url = raw.trim().removeSuffix("/")
    if (url.isBlank()) return null
    // Reject internal whitespace or control chars — "nostr.wine\twss" must never
    // reach okhttp's URL builder (crash: IllegalArgumentException on invalid host).
    if (url.any { it.isWhitespace() || it.isISOControl() }) {
        Log.w(TAG, "Rejecting URL with internal whitespace/control chars: ${url.take(80)}")
        return null
    }
    url = url.removePrefix("https://").removePrefix("http://")
    // Reject cleartext WebSocket — Android Network Security Policy blocks ws://
    // in release builds. No path to success; reject at the gate so the URL never
    // reaches the connection layer, the pool, or RelayCapabilitiesStore.
    if (url.startsWith("ws://")) {
        Log.w(TAG, "Rejecting cleartext URL (Android NSP blocks): $url")
        return null
    }
    if (!url.startsWith("wss://")) {
        url = "wss://$url"
    }
    val host = url.removePrefix("wss://").split("/").firstOrNull() ?: return null
    if (!host.contains(".")) return null
    return url
}

/** NIP-45 COUNT + WoT relay (antiprimal.net). */
const val ANTIPRIMAL_RELAY_URL = "wss://antiprimal.net"

/** Hardcoded global relay defaults — single source of truth for fallbacks. */
val GLOBAL_RELAY_URLS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://nostr.mom",
    "wss://relay.nostr.net",
    "wss://relay.primal.net",
)
