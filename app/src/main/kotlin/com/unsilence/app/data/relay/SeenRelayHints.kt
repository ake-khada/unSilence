package com.unsilence.app.data.relay

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val NORMALIZED_BRIDGE_FALLBACK_RELAYS = normalizedRelayTargets(BRIDGE_FALLBACK_RELAY_URLS)
private val NORMALIZED_BRIDGE_FALLBACK_RELAY_SET = NORMALIZED_BRIDGE_FALLBACK_RELAYS.toSet()

/**
 * Per-lookup locality budget. Relay provenance is cumulative, so forwarding the
 * complete `relaysSeen` set would turn an additive hint into an unbounded fan-out.
 */
internal const val MAX_SEEN_RELAY_HINTS = 3

internal data class RelayResolutionTargets(
    val hints: List<String>,
    val fallback: List<String>,
) {
    val all: List<String> get() = hints + fallback
}

internal fun normalizedRelayTargets(relayUrls: Collection<String>): List<String> =
    relayUrls.mapNotNull(::normalizeRelayUrl).distinct()

/**
 * Normalize one-shot targets while retaining the historical feed-relay exclusion
 * for ordinary background fan-out. Locality/reference phases opt in because the
 * active browse relay is itself a first-class seen-relay hint.
 */
internal fun oneShotRelayTargets(
    relayUrls: Collection<String>,
    activeSingleRelayFeedUrl: String?,
    includeActiveFeedRelay: Boolean,
): List<String> {
    val activeFeedRelay = activeSingleRelayFeedUrl?.let(::normalizeRelayUrl)
    return normalizedRelayTargets(relayUrls).filter { relayUrl ->
        includeActiveFeedRelay || relayUrl != activeFeedRelay
    }
}

internal fun feedRowRelayHints(
    primaryRelay: String?,
    relaysSeen: Collection<String>,
    browseRelays: Collection<String> = emptyList(),
    additionalRelays: Collection<String> = emptyList(),
): List<String> = boundedSeenRelayHints(
    seenRelays = listOfNotNull(primaryRelay) + relaysSeen,
    browseRelays = browseRelays,
    additionalRelays = additionalRelays,
)

/**
 * Build the bounded locality hints for one row-backed fetch.
 *
 * The row's primary provenance stays first. Active SingleRelay/RelaySet targets
 * are then reserved a place before older accumulated provenance, followed by
 * protocol-declared hints. Every URL is canonicalized and deduplicated.
 */
internal fun boundedSeenRelayHints(
    seenRelays: Collection<String>,
    browseRelays: Collection<String> = emptyList(),
    additionalRelays: Collection<String> = emptyList(),
    maxHints: Int = MAX_SEEN_RELAY_HINTS,
): List<String> {
    if (maxHints <= 0) return emptyList()
    val seen = normalizedRelayTargets(seenRelays)
    val browse = normalizedRelayTargets(browseRelays)
    val additional = normalizedRelayTargets(additionalRelays)
    return buildList {
        seen.firstOrNull()?.let(::add)
        addAll(browse)
        addAll(seen.drop(1))
        addAll(additional)
    }.distinct().take(maxHints)
}

/**
 * Split a resolution ladder into locality-first hints and its additive fallback.
 * Relays selected for the hint phase are removed from later fan-out phases.
 */
internal fun relayResolutionTargets(
    seenRelays: Collection<String>,
    browseRelays: Collection<String> = emptyList(),
    additionalRelays: Collection<String> = emptyList(),
    fallbackRelays: Collection<String> = emptyList(),
    maxHints: Int = MAX_SEEN_RELAY_HINTS,
): RelayResolutionTargets {
    val hints = boundedSeenRelayHints(
        seenRelays = seenRelays,
        browseRelays = browseRelays,
        additionalRelays = additionalRelays,
        maxHints = maxHints,
    )
    val hinted = hints.toSet()
    val fallback = normalizedRelayTargets(fallbackRelays)
        .filterNot(hinted::contains)
    return RelayResolutionTargets(hints = hints, fallback = fallback)
}

/** One final lookup-only tier, deduped against every relay already attempted. */
internal fun bridgeFallbackRelayTargets(
    alreadyTriedRelays: Collection<String>,
): List<String> {
    val tried = normalizedRelayTargets(alreadyTriedRelays).toSet()
    return NORMALIZED_BRIDGE_FALLBACK_RELAYS.filterNot(tried::contains)
}

/** Bridge lookups share one unpurposed warm-pool socket during a request burst. */
internal fun isBridgeFallbackRelay(url: String): Boolean {
    val normalized = normalizeRelayUrl(url) ?: return false
    return normalized in NORMALIZED_BRIDGE_FALLBACK_RELAY_SET
}

/** NIP-05 bootstrap for identities bridged by mostr. */
internal fun profileDerivedBridgeOutbox(nip05: String?): List<String> =
    if (nip05?.trim()?.lowercase()?.endsWith("@mostr.pub") == true) {
        BRIDGE_FALLBACK_RELAY_URLS
    } else {
        emptyList()
    }

/** Shared with ProfileResolver's collection window; each wire REQ stays bounded. */
internal const val MAX_PROFILE_AUTHORS_PER_REQ = 100

/**
 * Invert row hints within one pass so a shared relay receives its authors together.
 * Every author keeps exactly its normalized, bounded destinations. This does not
 * coalesce concurrent passes or change profile freshness/negative caching.
 */
internal fun groupProfileHintFetches(
    pubkeys: Collection<String>,
    relayHintsByPubkey: Map<String, List<String>>,
): Map<List<String>, List<String>> {
    val authorsByRelay = linkedMapOf<String, MutableList<String>>()
    for (pubkey in pubkeys.distinct()) {
        for (relay in canonicalRelayHints(relayHintsByPubkey[pubkey].orEmpty())) {
            authorsByRelay.getOrPut(relay) { mutableListOf() }.add(pubkey)
        }
    }
    return authorsByRelay.mapKeys { (relay, _) -> listOf(relay) }
}

/** Submit all chunks together to reuse one ephemeral connection per target relay. */
internal fun profileRequestPayloads(
    pubkeys: List<String>,
    subIdBase: String,
    kinds: List<Int>,
): Map<String, String> = pubkeys.distinct().chunked(MAX_PROFILE_AUTHORS_PER_REQ)
    .mapIndexed { index, authors ->
        val subId = if (index == 0) subIdBase else "$subIdBase-$index"
        subId to buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { kinds.forEach { add(JsonPrimitive(it)) } })
                put("authors", buildJsonArray { authors.forEach { add(JsonPrimitive(it)) } })
            })
        }.toString()
    }.toMap()
