package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.RelayTrustScoreEntity
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OutboxResolver"
private const val MAX_WRITE_RELAYS_PER_AUTHOR = 4
private const val MAX_OUTBOX_RELAYS = 10

/**
 * Read-only relay metadata source. Implemented by [com.unsilence.app.data.memory.MemoryEventStore]
 * in production; faked in tests. Decouples the resolver from the full MES
 * surface and enables trivial unit tests.
 */
interface RelayMetadataSource {
    fun writeRelaysFor(pubkey: String): List<String>
    fun getTrustScores(): Map<String, RelayTrustScoreEntity>
}

/**
 * Builds [SubRequest] lists for outbox-routed feeds.
 *
 * Mirrors Jumble's generateSubRequestsForPubkeys (client.service.ts:1542):
 * produces N SubRequests, ONE PER RELAY, each with `urls: [oneRelay]` ×
 * `authors: [authorsAtThisRelay]`. Fallback relays (user's read relays)
 * carry all authors; write relays carry only the authors who publish there.
 *
 * Per-relay routing means each relay gets a targeted author list instead of
 * the full 250+ author set, and each relay's EOSE is independent.
 */
@Singleton
class OutboxRelayResolver @Inject constructor(
    private val metadata: RelayMetadataSource,
) {
    data class Config(
        val kinds: List<Int>,
        val limit: Int,
        val minTrustScore: Int = 30,
        val since: Long? = null,
    )

    /**
     * @param authors The follows set (or any author group to subscribe to).
     * @param fallbackRelays Default relays for authors with no known write relays
     *                       (typically GLOBAL_RELAY_URLS).
     * @param blockedRelays Normalized URLs to exclude.
     */
    fun resolveFollowing(
        authors: Set<String>,
        fallbackRelays: List<String>,
        blockedRelays: Set<String>,
        config: Config,
    ): List<SubRequest> {
        if (authors.isEmpty()) return emptyList()

        val trustScores = metadata.getTrustScores()
        val sortedAuthors = authors.sorted()

        // Build relay → covered-authors map (per-relay SubRequest routing).
        val relayCoverage = HashMap<String, MutableSet<String>>()
        for (author in sortedAuthors) {
            val authorRelays = metadata.writeRelaysFor(author)
                .mapNotNull { normalizeRelayUrl(it) }
                .filter { it !in blockedRelays }
                .filter { url ->
                    val score = trustScores[url]?.score
                    score == null || score >= config.minTrustScore
                }
                .take(MAX_WRITE_RELAYS_PER_AUTHOR)
            for (relay in authorRelays) {
                relayCoverage.getOrPut(relay) { mutableSetOf() }.add(author)
            }
        }

        // Fallback relays — always included, each as own SubRequest with ALL authors.
        val baseUrlSet = fallbackRelays
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { it !in blockedRelays }
            .distinct()
            .toSet()

        // Write relays (exclude fallbacks — they already carry all authors).
        val writeRelayUrls = relayCoverage.keys.filter { it !in baseUrlSet }

        // Coverage pruning: greedy set cover if >10 write relays.
        val selectedWriteRelays: List<String> = if (writeRelayUrls.size > MAX_OUTBOX_RELAYS) {
            val uncovered = sortedAuthors.toMutableSet()
            val selected = mutableListOf<String>()
            val remaining = writeRelayUrls.toMutableList()
            while (selected.size < MAX_OUTBOX_RELAYS && uncovered.isNotEmpty() && remaining.isNotEmpty()) {
                val best = remaining.maxByOrNull { url ->
                    relayCoverage[url]?.count { it in uncovered } ?: 0
                } ?: break
                val newCoverage = relayCoverage[best]?.count { it in uncovered } ?: 0
                if (newCoverage == 0) break
                selected.add(best)
                uncovered.removeAll(relayCoverage[best] ?: emptySet())
                remaining.remove(best)
            }
            selected
        } else {
            writeRelayUrls
        }

        val subRequests = mutableListOf<SubRequest>()

        // Fallback relays: each gets all authors.
        for (url in baseUrlSet.sorted()) {
            subRequests.add(SubRequest(
                urls = listOf(url),
                filter = NostrFilter(
                    kinds = config.kinds,
                    authors = sortedAuthors,
                    limit = config.limit,
                    since = config.since,
                ),
            ))
        }

        // Write relays: each gets only its covered authors (sorted for stable cache keys).
        for (url in selectedWriteRelays.sorted()) {
            val relayAuthors = relayCoverage[url]?.sorted() ?: continue
            subRequests.add(SubRequest(
                urls = listOf(url),
                filter = NostrFilter(
                    kinds = config.kinds,
                    authors = relayAuthors,
                    limit = config.limit,
                    since = config.since,
                ),
            ))
        }

        if (subRequests.isEmpty()) return emptyList()

        Log.d(TAG, "resolveFollowing: ${authors.size} authors -> ${subRequests.size} SubRequests " +
            "(${baseUrlSet.size} fallback + ${selectedWriteRelays.size} write)")

        return subRequests
    }

    /**
     * Builds a single SubRequest for the Global feed — one filter against
     * the user's read relays (or fallback if none configured).
     */
    fun resolveGlobal(
        readRelays: List<String>,
        fallbackRelays: List<String>,
        blockedRelays: Set<String>,
        config: Config,
    ): List<SubRequest> {
        val urls = (readRelays.takeIf { it.isNotEmpty() } ?: fallbackRelays)
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { it !in blockedRelays }
        if (urls.isEmpty()) return emptyList()
        return listOf(
            SubRequest(
                urls = urls,
                filter = NostrFilter(
                    kinds = config.kinds,
                    limit = config.limit,
                    since = config.since,
                ),
            )
        )
    }

    /**
     * Builds a single SubRequest for a single-relay feed (e.g. relay browse).
     */
    fun resolveSingleRelay(
        url: String,
        config: Config,
    ): List<SubRequest> {
        val normalized = normalizeRelayUrl(url) ?: return emptyList()
        return listOf(
            SubRequest(
                urls = listOf(normalized),
                filter = NostrFilter(
                    kinds = config.kinds,
                    limit = config.limit,
                    since = config.since,
                ),
            )
        )
    }
}
