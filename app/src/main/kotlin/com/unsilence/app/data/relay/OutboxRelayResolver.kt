package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.RelayTrustScoreEntity
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OutboxResolver"

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
 * Mirrors Jumble's pattern (client.service.ts:160-170): for each followed
 * author, gather their top-5 write relays, flatten + deduplicate into a
 * single flat URL list. ONE SubRequest with all authors + all relays.
 * Relays handle server-side author filtering.
 *
 * This produces a single Subscription → single EOSE threshold (0 for N=1
 * SubRequest) → events emit on FIRST relay's partial EOSE.
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

        // Build relay → authors-who-write-there map from per-author write relays.
        // Used to rank relays by coverage (how many followed authors they serve).
        val relayCoverage = HashMap<String, MutableSet<String>>()
        for (author in authors) {
            val authorRelays = metadata.writeRelaysFor(author)
                .mapNotNull { normalizeRelayUrl(it) }
                .filter { it !in blockedRelays }
                .filter { url ->
                    val score = trustScores[url]?.score
                    score == null || score >= config.minTrustScore
                }
                .take(5)
            for (relay in authorRelays) {
                relayCoverage.getOrPut(relay) { mutableSetOf() }.add(author)
            }
        }

        // Always include user's read relays — they're PERSISTENT-connected
        // and aggregate content from many authors.
        val baseUrls = fallbackRelays
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { it !in blockedRelays }

        // Add top write relays sorted by author coverage. Cap at 20 to
        // avoid connecting to hundreds of personal outbox relays.
        val topWriteRelays = relayCoverage.entries
            .sortedByDescending { it.value.size }
            .take(MAX_WRITE_RELAYS)
            .map { it.key }

        val finalUrls = (baseUrls + topWriteRelays).distinct()

        if (finalUrls.isEmpty()) return emptyList()

        Log.d(TAG, "resolveFollowing: ${authors.size} authors, " +
            "${relayCoverage.size} total write relays -> ${finalUrls.size} URLs " +
            "(${baseUrls.size} read + ${topWriteRelays.size} top-write)")

        return listOf(
            SubRequest(
                urls = finalUrls,
                filter = NostrFilter(
                    kinds = config.kinds,
                    authors = authors.toList(),
                    limit = config.limit,
                    since = config.since,
                ),
            )
        )
    }

    companion object {
        /** Max write relays to include beyond the user's read relays. */
        const val MAX_WRITE_RELAYS = 20
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
