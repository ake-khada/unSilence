package com.unsilence.app.data.relay

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
    return normalizedRelayTargets(BRIDGE_FALLBACK_RELAY_URLS)
        .filterNot(tried::contains)
}

/** NIP-05 bootstrap for identities bridged by mostr. */
internal fun profileDerivedBridgeOutbox(nip05: String?): List<String> =
    if (nip05?.trim()?.lowercase()?.endsWith("@mostr.pub") == true) {
        BRIDGE_FALLBACK_RELAY_URLS
    } else {
        emptyList()
    }

/** Group profile authors by their bounded locality target set for one wire REQ. */
internal fun groupProfileHintFetches(
    pubkeys: Collection<String>,
    relayHintsByPubkey: Map<String, List<String>>,
): Map<List<String>, List<String>> = pubkeys.mapNotNull { pubkey ->
    val hints = canonicalRelayHints(relayHintsByPubkey[pubkey].orEmpty())
    hints.takeIf { it.isNotEmpty() }?.let { it to pubkey }
}.groupBy(
    keySelector = { it.first },
    valueTransform = { it.second },
)
