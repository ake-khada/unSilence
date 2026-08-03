package com.unsilence.app.data.relay

/**
 * Substring-level scanners over raw `["EVENT", subId, {...}]` wire messages.
 *
 * Shared by [EventProcessor] (top-level dedup + streaming decode into MES)
 * and its subscription-envelope dispatch. Centralising the scan avoids
 * parsing into a JsonElement tree just to extract `id` / `subId` or slice the
 * event object substring.
 */

/** Extract the subscription id from EVENT/EOSE/CLOSED wire messages. */
internal fun extractSubscriptionIdFromRaw(raw: String): String? {
    val firstComma = raw.indexOf(',')
    if (firstComma < 0) return null
    val quoteOpen = raw.indexOf('"', firstComma + 1)
    if (quoteOpen < 0) return null
    val quoteClose = raw.indexOf('"', quoteOpen + 1)
    if (quoteClose < 0) return null
    return raw.substring(quoteOpen + 1, quoteClose)
}

/**
 * Extract the 64-hex event id from a raw EVENT message without JSON parsing.
 *
 * Format: ["EVENT","sub-id",{"id":"<64-hex>","pubkey":...}]
 * Scans for the literal `"id":"` marker and validates the next 64 chars are
 * lowercase hex (Nostr spec).
 */
internal fun extractEventIdFromRaw(raw: String): String? {
    val marker = "\"id\":\""
    val markerIdx = raw.indexOf(marker)
    if (markerIdx < 0) return null
    val idStart = markerIdx + marker.length
    if (idStart + 64 > raw.length) return null
    val id = raw.substring(idStart, idStart + 64)
    if (!id.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return id
}

/**
 * Walk [raw] to find the start of the inner event object — the first `{`
 * outside of a JSON string literal. The preamble is `["EVENT","sub-id",`
 * and neither EVENT nor sub-id can legitimately contain a `{`, so a linear
 * scan suffices.
 */
internal fun findEventObjectStart(raw: String): Int {
    var i = 0
    var inString = false
    var escape = false
    while (i < raw.length) {
        val c = raw[i]
        if (escape) { escape = false; i++; continue }
        if (c == '\\') { escape = true; i++; continue }
        if (c == '"') { inString = !inString; i++; continue }
        if (!inString && c == '{') return i
        i++
    }
    return -1
}

/**
 * Find the `}` that matches the opening `{` at [openIdx], respecting string
 * literal boundaries. Used to slice out the event object's JSON substring
 * for streaming decode.
 */
internal fun findMatchingBraceEnd(raw: String, openIdx: Int): Int {
    var depth = 0
    var inString = false
    var escape = false
    var i = openIdx
    while (i < raw.length) {
        val c = raw[i]
        if (escape) { escape = false; i++; continue }
        if (c == '\\') { escape = true; i++; continue }
        if (c == '"') { inString = !inString; i++; continue }
        if (!inString) {
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        i++
    }
    return -1
}
