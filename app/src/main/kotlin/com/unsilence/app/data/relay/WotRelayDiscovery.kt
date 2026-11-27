package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MAX_WOT_WRITE_RELAY_PROBES = 4
private const val WOT_DISCOVERY_RETRY_MS = 60_000L

internal const val WOT_DISCOVERY_SUB_PREFIX = "wot-discovery-"
internal const val WOT_ASSERTION_SUB_PREFIX = "wot-30382-"

internal fun isWotFetchRequest(id: String) =
    id.startsWith(WOT_DISCOVERY_SUB_PREFIX) || id.startsWith(WOT_ASSERTION_SUB_PREFIX)

/** Only the relay address is discovered. Every probe and score request uses the selected signer. */
internal class WotRelayDiscovery(
    private val writeRelays: suspend (String) -> List<String>,
    private val servesScores: suspend (String, String) -> Boolean,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Route(
        val owner: String?,
        val signer: String,
        val hint: String,
        val url: String?,
        val expires: Long,
        val nextCandidate: Int,
    )
    private val mutex = Mutex()
    private var cached: Route? = null

    suspend fun resolve(
        owner: String?, signer: String, hint: String, allowDiscovery: Boolean, rejected: String? = null,
        activate: (String) -> Boolean,
    ): String? {
        if (!allowDiscovery) return hint.takeIf(activate)
        return mutex.withLock {
            val previous = cached?.takeIf { it.owner == owner && it.signer == signer && it.hint == hint }
            if (previous != null && nowMs() < previous.expires &&
                (rejected == null || previous.url != rejected)
            ) return@withLock previous.url?.takeIf(activate)

            val preferred = listOfNotNull(previous?.url, hint).distinct().filter { it != rejected }
            var selected = preferred.firstOrNull { servesScores(signer, it) }
            var nextCandidate = 0
            if (selected == null) {
                val candidates = writeRelays(signer).mapNotNull(::normalizeRelayUrl)
                    .filter { it != rejected && it !in preferred }.distinct()
                // Bound each retry, and give longer signed lists a turn after the cooldown.
                if (candidates.isNotEmpty()) {
                    var index = (previous?.nextCandidate ?: 0) % candidates.size
                    for (attempt in 0 until minOf(MAX_WOT_WRITE_RELAY_PROBES, candidates.size)) {
                        val candidate = candidates[index]
                        index = (index + 1) % candidates.size
                        if (servesScores(signer, candidate)) {
                            selected = candidate
                            break
                        }
                    }
                    if (selected == null) nextCandidate = index
                }
            }
            val ttl = if (selected == null) WOT_DISCOVERY_RETRY_MS else WOT_STALENESS_MS
            cached = Route(owner, signer, hint, selected, nowMs() + ttl, nextCandidate)
            selected?.takeIf(activate)
        }
    }
}

internal fun NostrEvent.hasWotRank(): Boolean {
    val subject = tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)
    val rank = tags.firstOrNull { it.firstOrNull() == "rank" }?.getOrNull(1)?.toIntOrNull()
    return normalizeWotPubkey(subject) != null && rank != null && rank in 0..100
}
