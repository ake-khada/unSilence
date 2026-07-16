package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.ProfileRelayFacts
import com.unsilence.app.data.memory.RelayConfig

internal const val RELAY_IDENTITY_REFRESH_MS = 7L * 24 * 60 * 60 * 1_000
internal const val RELAY_IDENTITY_PREFETCH_CAP = 100

private data class RelayAccess(
    val read: Boolean,
    val write: Boolean,
)

internal fun parseNip65RelayTags(tags: List<List<String>>): List<RelayConfig> {
    val accessByUrl = linkedMapOf<String, RelayAccess>()
    tags.forEach { tag ->
        if (tag.size < 2 || tag[0] != "r") return@forEach
        val url = normalizeRelayUrl(tag[1]) ?: return@forEach
        val incoming = when (tag.getOrNull(2)?.lowercase()) {
            "read" -> RelayAccess(read = true, write = false)
            "write" -> RelayAccess(read = false, write = true)
            else -> RelayAccess(read = true, write = true)
        }
        val existing = accessByUrl[url]
        accessByUrl[url] = if (existing == null) {
            incoming
        } else {
            RelayAccess(
                read = existing.read || incoming.read,
                write = existing.write || incoming.write,
            )
        }
    }
    return accessByUrl.map { (url, access) ->
        RelayConfig(
            url = url,
            marker = when {
                access.read && access.write -> null
                access.read -> "read"
                else -> "write"
            },
        )
    }
}

internal fun parseNip51RelayTags(tags: List<List<String>>): List<String> =
    tags.asSequence()
        .filter { it.size >= 2 && it[0] == "relay" }
        .mapNotNull { normalizeRelayUrl(it[1]) }
        .distinct()
        .toList()

internal fun shouldAcceptProfileRelayEvent(
    storedCreatedAt: Long?,
    incomingCreatedAt: Long,
): Boolean = storedCreatedAt == null || incomingCreatedAt > storedCreatedAt

internal fun deriveProfileRelayCount(
    relayListPublished: Boolean,
    relays: Collection<RelayConfig>,
): Int? = if (relayListPublished) {
    relays.mapNotNull { normalizeRelayUrl(it.url) }.distinct().size
} else {
    null
}

internal fun shouldRefreshRelayIdentity(fetchedAt: Long?, now: Long): Boolean =
    fetchedAt == null || now - fetchedAt >= RELAY_IDENTITY_REFRESH_MS

/** Eagerly cover ordinary lists; oversized/untrusted lists continue hydrating by viewport. */
internal fun relayIdentityPrefetchUrls(facts: ProfileRelayFacts): List<String> =
    sequence {
        facts.relays.forEach { yield(it.url) }
        facts.searchRelays.forEach { yield(it) }
        facts.blockedRelays.forEach { yield(it) }
    }
        .mapNotNull(::normalizeRelayUrl)
        .distinct()
        .take(RELAY_IDENTITY_PREFETCH_CAP)
        .toList()
