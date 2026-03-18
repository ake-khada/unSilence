package com.unsilence.app.data.relay

/**
 * Normalize a relay URL for consistent storage and comparison.
 * Rules: trim → strip http(s):// → prepend wss:// if missing → validate domain has dot → strip trailing slash.
 * Returns null if the URL is blank or has no valid domain.
 */
fun normalizeRelayUrl(raw: String): String? {
    var url = raw.trim().removeSuffix("/")
    if (url.isBlank()) return null
    url = url.removePrefix("https://").removePrefix("http://")
    if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
        url = "wss://$url"
    }
    val host = url.removePrefix("wss://").removePrefix("ws://").split("/").firstOrNull() ?: return null
    if (!host.contains(".")) return null
    return url
}

/** Hardcoded global relay defaults — single source of truth for fallbacks. */
val GLOBAL_RELAY_URLS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://nostr.mom",
    "wss://relay.nostr.net",
    "wss://relay.ditto.pub",
    "wss://relay.primal.net",
)
