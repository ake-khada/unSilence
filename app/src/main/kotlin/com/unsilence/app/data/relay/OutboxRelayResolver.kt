package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.RelayTrustScoreEntity
import javax.inject.Inject
import javax.inject.Singleton

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
 * Builds [SubRequest] groups for outbox-routed feeds. Given a set of author
 * pubkeys and per-author write-relay lists from MES (kind-10002), produces a
 * compact list of (relay, authors-at-relay) groups via coverage-greedy
 * selection.
 *
 * Algorithm:
 *   1. For each author, get write relays from MES (filtered by blocked URLs
 *      and minimum trust score).
 *   2. Build relay → authors-at-that-relay map.
 *   3. Greedy pick: select relay with highest unique-author coverage, mark
 *      its authors as covered, repeat until target coverage % met or maxRelays
 *      reached. Skip relays that add zero new authors.
 *   4. For each selected relay, emit one [SubRequest] with the unique authors
 *      assigned to it (each author appears in exactly one SubRequest).
 *
 * Authors with NO known write relays fall back to [fallbackRelays] grouped
 * into a single SubRequest. This handles new follows whose kind-10002 hasn't
 * been fetched yet.
 *
 * Coverage-greedy outbox relay selection that emits grouped SubRequests.
 */
@Singleton
class OutboxRelayResolver @Inject constructor(
    private val metadata: RelayMetadataSource,
) {
    data class Config(
        val kinds: List<Int>,
        val limit: Int,
        val maxRelays: Int = 16,
        val coverageTarget: Double = 0.95,
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

        // Per-author: their (filtered) write relays
        val authorRelays = HashMap<String, List<String>>(authors.size)
        val authorsWithoutRelays = mutableListOf<String>()
        for (author in authors) {
            val raw = metadata.writeRelaysFor(author)
            val filtered = raw
                .mapNotNull { normalizeRelayUrl(it) }
                .filter { it !in blockedRelays }
                .filter { url ->
                    val score = trustScores[url]?.score
                    score == null || score >= config.minTrustScore
                }
            if (filtered.isEmpty()) {
                authorsWithoutRelays.add(author)
            } else {
                authorRelays[author] = filtered
            }
        }

        // Relay → authors that publish to it
        val relayCoverage = HashMap<String, HashSet<String>>()
        for ((author, relays) in authorRelays) {
            for (relay in relays) {
                relayCoverage.getOrPut(relay) { HashSet() }.add(author)
            }
        }

        // Greedy coverage-pick: each iteration picks the relay with the most
        // not-yet-covered authors. Each author ends up in exactly one group
        // (the first relay that picks them).
        val coveredAuthors = HashSet<String>()
        val groups = mutableListOf<Pair<String, Set<String>>>()
        val targetCoverageCount = (authors.size * config.coverageTarget).toInt().coerceAtLeast(1)

        while (groups.size < config.maxRelays && coveredAuthors.size < targetCoverageCount) {
            val (bestRelay, bestNewAuthors) = relayCoverage.entries
                .map { (relay, candidateAuthors) ->
                    relay to (candidateAuthors - coveredAuthors)
                }
                .filter { (_, newAuthors) -> newAuthors.isNotEmpty() }
                .maxByOrNull { (_, newAuthors) -> newAuthors.size }
                ?: break  // no more relays add coverage

            groups.add(bestRelay to bestNewAuthors)
            coveredAuthors.addAll(bestNewAuthors)
        }

        // Build SubRequests from groups
        val subRequests = groups.map { (relay, groupAuthors) ->
            SubRequest(
                urls = listOf(relay),
                filter = NostrFilter(
                    kinds = config.kinds,
                    authors = groupAuthors.toList(),
                    limit = config.limit,
                    since = config.since,
                ),
            )
        }.toMutableList()

        // Fallback bucket for authors with no known write relays
        if (authorsWithoutRelays.isNotEmpty() && fallbackRelays.isNotEmpty()) {
            subRequests.add(
                SubRequest(
                    urls = fallbackRelays,
                    filter = NostrFilter(
                        kinds = config.kinds,
                        authors = authorsWithoutRelays,
                        limit = config.limit,
                        since = config.since,
                    ),
                )
            )
        }

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
