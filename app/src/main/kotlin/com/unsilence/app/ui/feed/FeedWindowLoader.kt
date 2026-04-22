package com.unsilence.app.ui.feed

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.ProfileResolver
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FeedWindow"

data class WindowResult(
    val eventIds: Set<String>,
    val oldestCreatedAt: Long,
    val newestCreatedAt: Long,
    val authorsInWindow: Int,
    val topRelays: List<String>,
    val completedAt: Long,
)

@Singleton
class FeedWindowLoader @Inject constructor(
    private val relayPool: RelayPool,
    private val memoryEventStore: MemoryEventStore,
    private val keyManager: KeyManager,
    private val profileResolver: ProfileResolver,
    private val relayPreferencesStore: RelayPreferencesStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Per-feed structured concurrency: cancels prior loadWindow on same feed
    private var activeLoadJob: Job? = null

    // Engagement refresh lifecycle
    private var engagementRefreshJob: Job? = null
    private var lastTopRelays: List<String> = emptyList()
    private var lastRefreshTimestamp: Long = 0L

    /**
     * Load a window of events for the given feed type.
     *
     * Phase A: Event discovery — ephemeral REQs for content kinds
     * Phase B: Parallel hydration — relay lists, engagement, profiles, refs, media
     * Phase C: MES writes happen per worker; signals fire; reducer renders
     *
     * Returns [WindowResult] after event discovery completes (UI can render partial).
     * Hydration workers continue in the background.
     */
    suspend fun loadWindow(feedType: FeedType, cursor: Long?): WindowResult {
        // Cancel any prior load on this feed
        activeLoadJob?.cancel()

        val startMs = System.currentTimeMillis()
        val ownPubkey = keyManager.getPublicKeyHex() ?: ""

        // ── Phase A: Event discovery ──────────────────────────────────────
        val discoveredIds = discoverEvents(feedType, cursor, ownPubkey)

        // Read discovered events from MES to extract metadata
        val events = memoryEventStore.eventsByIds(discoveredIds)
        val authors = events.map { it.pubkey }.toSet()
        val eventIds = events.map { it.id }.toSet()
        val oldest = events.minOfOrNull { it.createdAt } ?: Long.MAX_VALUE
        val newest = events.maxOfOrNull { it.createdAt } ?: 0L

        // ── Phase B: Parallel hydration (background) ──────────────────────
        activeLoadJob = scope.launch {
            val topRelays = hydrateWindow(eventIds, authors, ownPubkey)
            lastTopRelays = topRelays

            val durationMs = System.currentTimeMillis() - startMs
            val coverage = if (authors.isNotEmpty()) {
                val covered = authors.count { pk ->
                    val writeRelays = memoryEventStore.writeRelaysFor(pk)
                    writeRelays.any { it in topRelays }
                }
                covered * 100 / authors.size
            } else 0

            Log.d(TAG, "feedType=$feedType cursor=$cursor → " +
                "events=${eventIds.size} authors=${authors.size} " +
                "topRelays=${topRelays.size}/${FeedWindowConfig.ENGAGEMENT_RELAY_FANOUT} " +
                "[coverage=$coverage%] duration=$durationMs ms")
        }

        return WindowResult(
            eventIds = eventIds,
            oldestCreatedAt = oldest,
            newestCreatedAt = newest,
            authorsInWindow = authors.size,
            topRelays = emptyList(), // populated async; caller reads via startEngagementRefresh
            completedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Load the next page. Same as loadWindow but with a cursor.
     */
    suspend fun loadMore(feedType: FeedType, currentCursor: Long): WindowResult {
        return loadWindow(feedType, cursor = currentCursor)
    }

    /**
     * Start periodic engagement refresh for the current window.
     * Re-fetches engagement from cached top-N relays every 120s.
     */
    fun startEngagementRefresh(feedType: FeedType, windowIds: Set<String>) {
        stopEngagementRefresh()
        lastRefreshTimestamp = System.currentTimeMillis()

        engagementRefreshJob = scope.launch {
            while (isActive) {
                delay(FeedWindowConfig.ENGAGEMENT_REFRESH_INTERVAL_MS)
                val topRelays = lastTopRelays
                if (topRelays.isEmpty() || windowIds.isEmpty()) continue

                val sinceTimestamp = lastRefreshTimestamp / 1000  // seconds
                lastRefreshTimestamp = System.currentTimeMillis()

                Log.d(TAG, "Engagement refresh: ${windowIds.size} events, " +
                    "${topRelays.size} relays, since=$sinceTimestamp")

                fetchEngagementFromRelays(topRelays, windowIds.toList(), sinceTimestamp)
            }
        }
    }

    fun stopEngagementRefresh() {
        engagementRefreshJob?.cancel()
        engagementRefreshJob = null
    }

    // ── Phase A: Event Discovery ──────────────────────────────────────────

    /**
     * Send ephemeral REQs for content events. Events flow through
     * EventProcessor → MES as usual. We just orchestrate the REQs.
     */
    private suspend fun discoverEvents(
        feedType: FeedType,
        cursor: Long?,
        ownPubkey: String,
    ): Set<String> {
        val subId = "window-${System.nanoTime()}"
        val urls = resolveDiscoveryRelays(feedType, ownPubkey)
        if (urls.isEmpty()) return emptySet()

        val filter = buildJsonObject {
            put("kinds", buildJsonArray {
                add(JsonPrimitive(1))
                add(JsonPrimitive(6))
                add(JsonPrimitive(20))
                add(JsonPrimitive(21))
                add(JsonPrimitive(30023))
            })
            put("limit", JsonPrimitive(FeedWindowConfig.WINDOW_SIZE))
            if (cursor != null) {
                put("until", JsonPrimitive(cursor))
            }
            // Following feed: filter by followed authors
            if (feedType is FeedType.Following) {
                val follows = memoryEventStore.getFollows(ownPubkey)
                if (!follows.isNullOrEmpty()) {
                    put("authors", buildJsonArray {
                        follows.forEach { add(JsonPrimitive(it)) }
                    })
                }
            }
        }

        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(filter)
        }.toString()

        // Snapshot event IDs before discovery so we can identify new arrivals
        val before = snapshotRelevantEventIds(feedType, ownPubkey)

        relayPool.sendOneShotBatch(
            urls = urls,
            reqs = listOf(req),
            subIds = listOf(subId),
            timeoutMs = FeedWindowConfig.EVENT_DISCOVERY_TIMEOUT_MS,
        )

        // Events have been processed by EventProcessor → MES by the time
        // sendOneShotBatch returns. Diff against pre-snapshot.
        val after = snapshotRelevantEventIds(feedType, ownPubkey)
        return after - before
    }

    /**
     * Snapshot current event IDs relevant to this feed type from MES.
     * Used to diff before/after discovery to identify new arrivals.
     */
    private fun snapshotRelevantEventIds(feedType: FeedType, ownPubkey: String): Set<String> {
        val filter = buildMemoryFilter(feedType, ownPubkey)
        val events = memoryEventStore.feedEvents(filter, FeedWindowConfig.WINDOW_SIZE * 2)
        return events.map { it.id }.toSet()
    }

    private fun buildMemoryFilter(feedType: FeedType, ownPubkey: String): com.unsilence.app.data.memory.FeedFilter {
        return when (feedType) {
            is FeedType.Following -> com.unsilence.app.data.memory.FeedFilter(
                kinds = setOf(1, 6, 20, 21, 30023),
                followedPubkeys = memoryEventStore.getFollows(ownPubkey) ?: emptySet(),
            )
            is FeedType.Global -> com.unsilence.app.data.memory.FeedFilter(
                kinds = setOf(1, 6, 20, 21, 30023),
                relayUrls = resolveGlobalUrls(ownPubkey).toSet(),
            )
            is FeedType.SingleRelay -> com.unsilence.app.data.memory.FeedFilter(
                kinds = setOf(1, 6, 20, 21, 30023),
                relayUrls = setOfNotNull(normalizeRelayUrl(feedType.url)),
            )
            is FeedType.RelaySet -> com.unsilence.app.data.memory.FeedFilter(
                kinds = setOf(1, 6, 20, 21, 30023),
                relayUrls = memoryEventStore.getSetMembers(ownPubkey, feedType.dTag)
                    .mapNotNull { normalizeRelayUrl(it) }.toSet(),
            )
        }
    }

    // ── Phase B: Parallel Hydration ───────────────────────────────────────

    /**
     * Run all hydration workers in parallel. Returns top-N relay list.
     */
    private suspend fun hydrateWindow(
        eventIds: Set<String>,
        authors: Set<String>,
        ownPubkey: String,
    ): List<String> = coroutineScope {
        // B.4 Media worker (pure local — parse imeta, populate sidecar caches)
        // Runs first since it's instant and improves first-render layout
        launch { hydrateMedia(eventIds) }

        // B.4 Profiles worker (parallel with relay list fetch)
        val profilesJob = async { hydrateProfiles(authors) }

        // B.1 + B.2: Relay list fetch → top-N ranking
        val topRelaysJob = async { fetchRelayListsAndRank(authors, ownPubkey) }

        // B.4 Refs worker (parallel)
        val refsJob = async { hydrateRefs(eventIds) }

        // Wait for top relays before engagement
        val topRelays = topRelaysJob.await()

        // B.3 Engagement batch (needs top-N relays)
        if (topRelays.isNotEmpty() && eventIds.isNotEmpty()) {
            fetchEngagementFromRelays(topRelays, eventIds.toList(), sinceTimestamp = null)
        }

        // Wait for remaining workers
        profilesJob.await()
        refsJob.await()

        topRelays
    }

    // ── B.1 + B.2: Relay lists + top-N ranking ───────────────────────────

    private suspend fun fetchRelayListsAndRank(
        authors: Set<String>,
        ownPubkey: String,
    ): List<String> {
        val now = System.currentTimeMillis()
        val freshnessCutoff = now - FeedWindowConfig.RELAY_LIST_FRESHNESS_TTL_MS

        // Check which authors need fresh relay lists
        val stale = authors.filter { pk ->
            val rl = memoryEventStore.getRelayList(pk)
            rl == null || memoryEventStore.getProfileLastUpdated(pk) < freshnessCutoff
        }

        if (stale.isNotEmpty()) {
            // Batch-fetch kind-10002 via indexer relays
            withTimeoutOrNull(FeedWindowConfig.HYDRATION_WORKER_TIMEOUT_MS) {
                relayPool.fetchRelayLists(stale)
                // Wait for at least some to arrive
                delay(2000)
            }
            Log.d(TAG, "B.1: fetched relay lists for ${stale.size}/${authors.size} stale authors")
        }

        // B.2: Aggregate write-relay frequency across window authors
        val relayFrequency = mutableMapOf<String, Int>()
        for (pk in authors) {
            val writeRelays = memoryEventStore.writeRelaysFor(pk)
            for (url in writeRelays) {
                val normalized = normalizeRelayUrl(url) ?: continue
                relayFrequency[normalized] = (relayFrequency[normalized] ?: 0) + 1
            }
        }

        // Filter blocked relays and low-trust relays
        val trustScores = memoryEventStore.getTrustScores()
        val blockedUrls = ownPubkey.takeIf { it.isNotEmpty() }?.let {
            memoryEventStore.getBlockedRelayUrls(it).toSet()
        } ?: emptySet()

        val ranked = relayFrequency.entries
            .filter { (url, _) -> url !in blockedUrls }
            .filter { (url, _) ->
                val trust = trustScores[url]?.score
                // Accept if no trust data (don't fail on absent data) or score >= 30
                trust == null || trust >= 30
            }
            .sortedByDescending { it.value }
            .take(FeedWindowConfig.ENGAGEMENT_RELAY_FANOUT)
            .map { it.key }

        Log.d(TAG, "B.2: top-${ranked.size} relays from ${relayFrequency.size} unique write relays")
        return ranked
    }

    // ── B.3: Engagement fetch ────────────────────────────────────────────

    private suspend fun fetchEngagementFromRelays(
        relayUrls: List<String>,
        eventIds: List<String>,
        sinceTimestamp: Long?,
    ) {
        if (relayUrls.isEmpty() || eventIds.isEmpty()) return

        // Build engagement REQ
        val subId = "window-eng-${System.nanoTime()}"
        val filter = buildJsonObject {
            put("kinds", buildJsonArray {
                add(JsonPrimitive(7))   // reactions
                add(JsonPrimitive(6))   // reposts
                add(JsonPrimitive(9735)) // zap receipts
            })
            put("#e", buildJsonArray {
                eventIds.forEach { add(JsonPrimitive(it)) }
            })
            if (sinceTimestamp != null) {
                put("since", JsonPrimitive(sinceTimestamp))
            }
        }
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(filter)
        }.toString()

        // Parallel ephemeral fetch to all top-N relays
        coroutineScope {
            relayUrls.map { url ->
                async {
                    relayPool.sendOneShotBatch(
                        urls = listOf(url),
                        reqs = listOf(req),
                        subIds = listOf(subId),
                        timeoutMs = FeedWindowConfig.HYDRATION_WORKER_TIMEOUT_MS,
                    )
                }
            }.forEach { it.await() }
        }

        // Events have flowed through EventProcessor → MES.
        // Invalidate feed row cache so engagement counts refresh.
        memoryEventStore.invalidateFeedRowCache(eventIds)

        Log.d(TAG, "B.3: engagement fetched from ${relayUrls.size} relays " +
            "for ${eventIds.size} events" +
            if (sinceTimestamp != null) " (since=$sinceTimestamp)" else "")
    }

    // ── B.4: Profiles ────────────────────────────────────────────────────

    private suspend fun hydrateProfiles(authors: Set<String>) {
        val now = System.currentTimeMillis()
        val freshnessCutoff = now - FeedWindowConfig.PROFILE_FRESHNESS_TTL_MS

        val stale = authors.filter { pk ->
            memoryEventStore.getProfileLastUpdated(pk) < freshnessCutoff
        }

        if (stale.isEmpty()) {
            Log.d(TAG, "B.4 profiles: all ${authors.size} fresh")
            return
        }

        withTimeoutOrNull(FeedWindowConfig.HYDRATION_WORKER_TIMEOUT_MS) {
            profileResolver.request(stale)
            // ProfileResolver batches internally (300ms window, 100 pubkey cap).
            // Wait for some to arrive.
            delay(3000)
        }

        Log.d(TAG, "B.4 profiles: ${stale.size}/${authors.size} stale → fetched")
    }

    // ── B.4: Refs ────────────────────────────────────────────────────────

    private suspend fun hydrateRefs(eventIds: Set<String>) {
        val events = memoryEventStore.eventsByIds(eventIds)

        // Extract referenced event IDs (e-tags: repost targets, quotes, replies)
        val referencedIds = mutableSetOf<String>()
        for (event in events) {
            // Repost target (kind 6 e-tag)
            if (event.kind == 6) {
                event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }
                    ?.get(1)?.let { referencedIds.add(it) }
            }
            // Reply parent and root
            event.replyToId?.let { referencedIds.add(it) }
            event.rootId?.let { referencedIds.add(it) }
            // Quoted events (q-tags)
            event.tags.filter { it.size >= 2 && it[0] == "q" }
                .forEach { referencedIds.add(it[1]) }
        }

        // Filter out already-known events and negative cache
        val missing = referencedIds.filter { id ->
            memoryEventStore.getEventEntity(id) == null && !relayPool.isEventUnresolved(id)
        }

        if (missing.isEmpty()) {
            Log.d(TAG, "B.4 refs: ${referencedIds.size} refs, all resolved")
            return
        }

        withTimeoutOrNull(FeedWindowConfig.EVENT_DISCOVERY_TIMEOUT_MS) {
            relayPool.fetchEventsByIds(missing)
            delay(2000) // allow events to arrive
        }

        Log.d(TAG, "B.4 refs: ${missing.size}/${referencedIds.size} missing → fetched")
    }

    // ── B.4: Media (pure local) ──────────────────────────────────────────

    private fun hydrateMedia(eventIds: Set<String>) {
        val events = memoryEventStore.eventsByIds(eventIds)
        var imetaCount = 0

        for (event in events) {
            // Parse imeta tags and populate sidecar cache
            val imetaDims = mutableMapOf<String, Float>()
            for (tag in event.tags) {
                if (tag.size < 2 || tag[0] != "imeta") continue
                var url: String? = null
                var width = 0
                var height = 0
                for (i in 1 until tag.size) {
                    val part = tag[i]
                    when {
                        part.startsWith("url ") -> url = part.removePrefix("url ")
                        part.startsWith("dim ") -> {
                            val dim = part.removePrefix("dim ")
                            val parts = dim.split("x")
                            if (parts.size == 2) {
                                width = parts[0].toIntOrNull() ?: 0
                                height = parts[1].toIntOrNull() ?: 0
                            }
                        }
                    }
                }
                if (url != null && width > 0 && height > 0) {
                    val ratio = width.toFloat() / height
                    // Clamp to 0.2..5.0 per ImageDimensionCache contract
                    imetaDims[url] = ratio.coerceIn(0.2f, 5.0f)
                }
            }
            if (imetaDims.isNotEmpty()) {
                memoryEventStore.putImetaImageDims(event.id, imetaDims)
                imetaCount++
            }
        }

        if (imetaCount > 0) {
            Log.d(TAG, "B.4 media: parsed imeta dims for $imetaCount/${events.size} events")
        }
    }

    // ── Relay resolution helpers ──────────────────────────────────────────

    private fun resolveDiscoveryRelays(feedType: FeedType, ownPubkey: String): List<String> {
        return when (feedType) {
            is FeedType.Following -> {
                // For following feed, use all connected relays (outbox routing
                // has already connected to write relays of followed authors)
                resolveGlobalUrls(ownPubkey)
            }
            is FeedType.Global -> resolveGlobalUrls(ownPubkey)
            is FeedType.SingleRelay -> listOfNotNull(normalizeRelayUrl(feedType.url))
            is FeedType.RelaySet -> {
                memoryEventStore.getSetMembers(ownPubkey, feedType.dTag)
                    .mapNotNull { normalizeRelayUrl(it) }
                    .ifEmpty { resolveGlobalUrls(ownPubkey) }
            }
        }
    }

    private fun resolveGlobalUrls(ownPubkey: String): List<String> {
        if (ownPubkey.isEmpty()) return GLOBAL_RELAY_URLS
        val readRelays = memoryEventStore.getReadWriteRelayConfigs(ownPubkey)
            .filter { it.marker == null || it.marker == "read" }
            .mapNotNull { normalizeRelayUrl(it.url) }
        return readRelays.ifEmpty { GLOBAL_RELAY_URLS }
    }
}
