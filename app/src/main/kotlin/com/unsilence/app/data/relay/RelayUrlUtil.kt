package com.unsilence.app.data.relay

import android.util.Log
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "RelayUrlUtil"
private const val MAX_INVALID_URL_WARNINGS = 64
private val ENCODED_CONTROL_OR_SPACE = Regex("%(?:0[0-9a-f]|1[0-9a-f]|20|7f)", RegexOption.IGNORE_CASE)
private val warnedInvalidUrls = ConcurrentHashMap.newKeySet<String>()

private fun warnInvalidUrl(reason: String, url: String) {
    val key = "$reason:$url"
    if (warnedInvalidUrls.size < MAX_INVALID_URL_WARNINGS && warnedInvalidUrls.add(key)) {
        Log.w(TAG, "Rejecting $reason: ${url.take(80)}")
    }
}

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
        warnInvalidUrl("URL with internal whitespace/control chars", url)
        return null
    }
    url = url.removePrefix("https://").removePrefix("http://")
    // Reject cleartext WebSocket — Android Network Security Policy blocks ws://
    // in release builds. No path to success; reject at the gate so the URL never
    // reaches the connection layer, the pool, or RelayCapabilitiesStore.
    if (url.startsWith("ws://")) {
        warnInvalidUrl("cleartext URL (Android NSP blocks)", url)
        return null
    }
    if (!url.startsWith("wss://")) {
        url = "wss://$url"
    }
    // URI parsers preserve encoded whitespace, so reject it explicitly before
    // structural validation. A polluted NIP-65 value such as
    // "wss://nos.lol/%20wss://relay.damus.io" otherwise becomes one expensive,
    // guaranteed-useless WebSocket connection.
    if (ENCODED_CONTROL_OR_SPACE.containsMatchIn(url)) {
        warnInvalidUrl("URL with encoded whitespace/control chars", url)
        return null
    }
    val parsed = runCatching { URI(url) }.getOrNull() ?: return null
    val host = parsed.host ?: return null
    if (parsed.scheme != "wss" || parsed.rawUserInfo != null || parsed.rawFragment != null) return null
    if (!host.contains(".") || host.startsWith(".") || host.endsWith(".")) return null
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
