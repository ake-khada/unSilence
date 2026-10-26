package com.unsilence.app.data.relay

/**
 * Bounded best-effort recipient delivery for a signed Compose event, or deletion
 * of that original event. The caller supplies only cached verified read lists;
 * no relay hints, defaults for recipients, or metadata fetching belong here.
 */
internal fun recipientPublishRelays(
    ownTargets: List<String>,
    signedAuthor: String,
    signedTags: List<List<String>>,
    cachedReadRelays: (String) -> List<String>,
    blocked: Set<String>,
): List<String> {
    val blockedUrls = blocked.mapNotNull(::normalizeRelayUrl).toSet()
    val targets = ownTargets.mapNotNull(::normalizeRelayUrl).filter { it !in blockedUrls }.toMutableSet()
    val ownCount = targets.size
    val author = signedAuthor
    val recipients = signedTags.asSequence()
        .filter { it.size >= 2 && it[0] == "p" }
        .map { it[1] }
        .filter { key -> key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' } }
        .filter { it != author }
        .distinct()
        .map { pubkey ->
            cachedReadRelays(pubkey).asSequence().mapNotNull(::normalizeRelayUrl)
                .filter { it !in blockedUrls && it !in targets }
                .distinct().take(4).toList().iterator()
        }.toList()
    // One new target per recipient per round. Shared targets do not consume a turn.
    while (targets.size - ownCount < 8) {
        var added = false
        for (relays in recipients) {
            while (relays.hasNext()) {
                if (targets.add(relays.next())) { added = true; break }
            }
            if (targets.size - ownCount == 8) return targets.toList()
        }
        if (!added) break
    }
    return targets.toList()
}
