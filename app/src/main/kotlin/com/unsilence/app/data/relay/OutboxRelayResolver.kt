package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.RelayMonitorEntity
import com.unsilence.app.data.memory.RelayTrustScoreEntity
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OutboxResolver"
private const val MAX_WRITE_RELAYS_PER_AUTHOR = 4
private const val MAX_OUTBOX_RELAYS = 10
/** Top-N write relays (by quality, post-set-cover) that should be opened
 *  immediately on feed subscribe. The remaining write relays are tagged
 *  [SubTier.SLOW] and only connected after the fast tier EOSEs / times out
 *  (see TimelineService.subscribeTimeline). User read/fallback relays are
 *  always FAST regardless of count. */
private const val FAST_WRITE_RELAYS = 3
/** Treat unknown trust scores as average (not great, not bad). Matches the
 *  pre-existing convention in [com.unsilence.app.data.memory.MemoryEventStore.writeRelaysForRanked]. */
private const val DEFAULT_TRUST_SCORE = 50
/** Treat unknown RTTs as median. Picked so that monitored low-RTT relays
 *  beat unknown ones, and unknown relays beat monitored high-RTT ones. */
private const val DEFAULT_RTT_MS = 250

/**
 * Read-only relay metadata source. Implemented by [com.unsilence.app.data.memory.MemoryEventStore]
 * in production; faked in tests. Decouples the resolver from the full MES
 * surface and enables trivial unit tests.
 */
interface RelayMetadataSource {
    fun writeRelaysFor(pubkey: String): List<String>
    fun getTrustScores(): Map<String, RelayTrustScoreEntity>
    /** NIP-66 RTT data per relay (kind-30166 monitor events). Empty until
     *  [com.unsilence.app.data.relay.RelayPool.fetchRelayMonitors] populates MES. */
    fun getRelayMonitors(): Map<String, RelayMonitorEntity> = emptyMap()
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
        val onlyReplies: Boolean = false,
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
        val monitors = metadata.getRelayMonitors()
        val sortedAuthors = authors.sorted()

        // Quality comparator: higher trust first, then lower RTT first. Unknown
        // values get the DEFAULT_TRUST_SCORE / DEFAULT_RTT_MS placeholders so
        // monitored fast relays outrank unknown ones, but unknown relays still
        // outrank monitored-slow ones. Lexicographic url tiebreak keeps the
        // ranking deterministic for stable cache keys / set-cover behaviour.
        val relayQuality = Comparator<String> { a, b ->
            val sa = trustScores[a]?.score ?: DEFAULT_TRUST_SCORE
            val sb = trustScores[b]?.score ?: DEFAULT_TRUST_SCORE
            if (sa != sb) return@Comparator sb.compareTo(sa)
            val ra = monitors[a]?.rttRead ?: DEFAULT_RTT_MS
            val rb = monitors[b]?.rttRead ?: DEFAULT_RTT_MS
            if (ra != rb) return@Comparator ra.compareTo(rb)
            a.compareTo(b)
        }

        // Build relay → covered-authors map (per-relay SubRequest routing).
        // Per-author candidates are sorted by [relayQuality] before the
        // MAX_WRITE_RELAYS_PER_AUTHOR cap, so each author contributes its
        // best-known relays first.
        val relayCoverage = HashMap<String, MutableSet<String>>()
        for (author in sortedAuthors) {
            val authorRelays = metadata.writeRelaysFor(author)
                .mapNotNull { normalizeRelayUrl(it) }
                .filter { it !in blockedRelays }
                .filter { url ->
                    val score = trustScores[url]?.score
                    score == null || score >= config.minTrustScore
                }
                .distinct()
                .sortedWith(relayQuality)
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

        // Coverage pruning: greedy set cover if >MAX_OUTBOX_RELAYS write relays.
        // Pick by max remaining coverage; tiebreak by relay quality so that on
        // equal coverage, the higher-trust / faster-RTT relay wins.
        //
        // Comparator returns >0 when `a` is the better candidate (max-by-this
        // semantics). Coverage is the primary signal; quality breaks ties.
        val pickBest = Comparator<Pair<String, Int>> { a, b ->
            val cov = a.second.compareTo(b.second)
            if (cov != 0) cov
            else relayQuality.compare(b.first, a.first)  // higher-quality URL wins
        }
        val selectedWriteRelays: List<String> = if (writeRelayUrls.size > MAX_OUTBOX_RELAYS) {
            val uncovered = sortedAuthors.toMutableSet()
            val selected = mutableListOf<String>()
            val remaining = writeRelayUrls.toMutableList()
            while (selected.size < MAX_OUTBOX_RELAYS && uncovered.isNotEmpty() && remaining.isNotEmpty()) {
                val best = remaining
                    .map { url -> url to (relayCoverage[url]?.count { it in uncovered } ?: 0) }
                    .filter { it.second > 0 }
                    .maxWithOrNull(pickBest)
                    ?.first ?: break
                selected.add(best)
                uncovered.removeAll(relayCoverage[best] ?: emptySet())
                remaining.remove(best)
            }
            selected
        } else {
            // Within-cap: emit relays in quality order so the resulting
            // SubRequest lists are deterministic and best-relay-first.
            writeRelayUrls.sortedWith(relayQuality)
        }

        val replyTags = if (config.onlyReplies) mapOf("e" to emptyList<String>()) else null
        val subRequests = mutableListOf<SubRequest>()

        // Fallback relays: each gets all authors. Always FAST tier — these
        // are user read relays the client has to be connected to anyway.
        for (url in baseUrlSet.sorted()) {
            subRequests.add(SubRequest(
                urls = listOf(url),
                filter = NostrFilter(
                    kinds = config.kinds,
                    authors = sortedAuthors,
                    limit = config.limit,
                    since = config.since,
                    tags = replyTags,
                ),
                tier = SubTier.FAST,
                onlyReplies = config.onlyReplies,
            ))
        }

        // Tier write relays by quality before the alphabetical sort destroys
        // the order. Top-FAST_WRITE_RELAYS by quality stay FAST; the rest
        // are SLOW so TimelineService can defer their WebSocket handshake.
        val fastWriteRelays = selectedWriteRelays.sortedWith(relayQuality)
            .take(FAST_WRITE_RELAYS).toSet()

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
                    tags = replyTags,
                ),
                tier = if (url in fastWriteRelays) SubTier.FAST else SubTier.SLOW,
                onlyReplies = config.onlyReplies,
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
                    tags = if (config.onlyReplies) mapOf("e" to emptyList<String>()) else null,
                ),
                onlyReplies = config.onlyReplies,
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
                    tags = if (config.onlyReplies) mapOf("e" to emptyList<String>()) else null,
                ),
                tier = SubTier.FAST,
                onlyReplies = config.onlyReplies,
            )
        )
    }
}
