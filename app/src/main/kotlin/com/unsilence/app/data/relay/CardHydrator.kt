package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.model.buildVideoRenderModels
import com.unsilence.app.ui.feed.IMAGE_URL_REGEX
import com.unsilence.app.ui.feed.ImageDimensionCache
import com.unsilence.app.ui.feed.VIDEO_URL_REGEX
import com.unsilence.app.ui.feed.VideoThumbnailCache
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CardHydrator"

/** Bound on each per-phase hydrated-id memo. Sized so a tall warm-zone
 *  fan-out (~150 events on aggressive scroll) plus a feed swap fits with
 *  headroom; oldest IDs evict FIFO when the set exceeds this. */
private const val HYDRATED_CAP = 500

/** Max distinct hint relays a single hydration pass will fan out to. The broadcast
 *  (fetchEventsByIds → 6 relays) covers the common case; this bounds the supplementary
 *  hint loop. Long-tail single-ref relays retry next pass. */
internal const val MAX_HINT_RELAYS_PER_PASS = 12

private val NOSTR_URI_REGEX = Regex("nostr:[a-z0-9]+", RegexOption.IGNORE_CASE)

/** Negative cache for NIP-19 bech32 URIs that fail to decode. Thread-safe. */
object Nip19FailureCache {
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private const val MAX_SIZE = 10_000 // ~400KB at 40-byte avg string length

    fun isKnownBad(uri: String): Boolean = failures.containsKey(uri)

    fun markBad(uri: String) {
        // Soft cap — remove one arbitrary entry instead of wiping the whole cache.
        // Known-bad strings persist across the session.
        if (failures.size >= MAX_SIZE) {
            val victim = failures.keys.firstOrNull()
            if (victim != null) failures.remove(victim)
        }
        failures[uri] = true
    }
}

/**
 * Unified card hydration: resolves ALL missing data for visible cards.
 *
 * Handles:
 *  - Author profiles (kind 0)
 *  - Repost original-author profiles (NIP-18 p-tag)
 *  - Referenced events for reposts (kind 6 e-tag) and quotes (nostr:nevent/note)
 *  - Referenced event author profiles
 */
@Singleton
class CardHydrator @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val thumbnailCache: VideoThumbnailCache,
    private val imageDimensionCache: ImageDimensionCache,
    private val outboxResolver: OutboxRelayResolver,
) {
    // ── Per-phase hydrated-id memo ───────────────────────────────────────
    // hydrateVisibleCards re-fires on every viewport change (debounce 300ms).
    // During a slow scroll the warm zone overlaps the previous pass by 30+
    // events, so each phase repeats the same content regex scans, ref id
    // extraction, and relay fetch orchestration for events already done.
    // Track per-phase completion in bounded LRU sets and filter at entry.
    // Downstream caches (UserRepository, RelayPool eventFetchInFlight,
    // ImageDimensionCache) already dedup the actual fetches — these sets
    // skip the upstream orchestration cost only.
    private val mediaHydrated = LinkedHashSet<String>()
    private val hydratedLock = Any()

    private fun filterAndMarkNovel(
        events: List<FeedRow>,
        set: LinkedHashSet<String>,
    ): List<FeedRow> {
        if (events.isEmpty()) return events
        return synchronized(hydratedLock) {
            val novel = events.filter { it.id !in set }
            for (e in novel) {
                if (set.add(e.id) && set.size > HYDRATED_CAP) {
                    val iter = set.iterator()
                    if (iter.hasNext()) { iter.next(); iter.remove() }
                }
            }
            novel
        }
    }

    /** Drop the per-phase memos. Called on logout / feed-switch teardown
     *  if the caller wants a clean slate. Safe to call concurrently with
     *  hydrate* — the lock guards both reads and writes. */
    fun resetHydratedMemo() {
        synchronized(hydratedLock) {
            mediaHydrated.clear()
        }
        backfillScope.coroutineContext.cancelChildren()
        pendingBackfillIds.clear()
        ownEngagementInFlight.clear()
        ownEngagementChecked.clear()
        engagementTracker.clear()
        engagementInFlight.clear()
        pendingEngagementIds.clear()
    }

    // ── Engagement count fetch ─────────────────────────────────────────
    // Per-post bounded download: kinds [1,6,16,7,9735] with #e:[postId],
    // limit 100. Targets the user's NIP-65 read relays (same as fetchThread).
    // Events flow through EventProcessor → MES aggregates → statsFlow → card display.
    //
    // Freshness tiers gate re-fetch based on post age:
    //   <1h→2min, <6h→10min, <24h→1h, <7d→6h, ≥7d→fetch once.

    /** Per-post engagement fetch state: when we last fetched, capped flag. */
    internal data class EngagementFetchState(
        val lastFetchedAt: Long = 0L,
        val capped: Boolean = false,
    )

    /** Tracks per-post engagement fetch state. Cleared on logout/reset. */
    internal val engagementTracker = ConcurrentHashMap<String, EngagementFetchState>()

    /** Posts whose engagement REQ is currently in flight. */
    private val engagementInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Pending post IDs accumulated from hydrateVisibleCards, awaiting debounced dispatch. */
    private val pendingEngagementIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Debounce job for engagement fetch — cancelled and relaunched on each accumulation. */
    private var engagementDebounceJob: Job? = null


    // ── Own-engagement backfill ─────────────────────────────────────────
    // Fetches the user's own kind-7/6 events targeting visible posts from
    // their write relays. Results flow through EventProcessor → MES →
    // actor indexes → _actionSignal → icons light up. Self-healing: once
    // backfilled, the snapshot persists the engagement for future starts.
    //
    // Non-blocking: hydrateVisibleCards accumulates novel IDs into a
    // pending buffer. A debounced coroutine (250ms) coalesces and dispatches
    // in the background. The checked transition is gated on real EOSE via
    // RelayPool.oneShotEoseCallbacks.

    private val backfillScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Posts whose backfill REQ reached EOSE — never re-checked this session. */
    internal val ownEngagementChecked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Posts whose backfill REQ is in flight — prevents duplicate dispatch. */
    private val ownEngagementInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Pending IDs accumulated from hydrateVisibleCards, awaiting debounced dispatch. */
    private val pendingBackfillIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The debounce job — cancelled and relaunched on each new accumulation. */
    private var backfillDebounceJob: Job? = null

    /**
     * Independent media pipeline — no relay queries, no dependencies on ref resolution.
     *
     * @param mmrAllowed When true, MediaMetadataRetriever is used for video thumbnails
     *   (REST-only — 300ms/video codec work). When false, only image dimensions are
     *   resolved (IDLE-safe — BitmapFactory header-only, ~50ms each).
     */
    suspend fun hydrateMedia(events: List<FeedRow>, mmrAllowed: Boolean = false, mmrCap: Int = 3) {
        if (events.isEmpty()) return
        val novelEvents = filterAndMarkNovel(events, mediaHydrated)
        if (novelEvents.isEmpty()) return

        // Image dimensions (always — lightweight header-only BitmapFactory decode)
        val imageUrls = mutableListOf<String>()
        for (event in novelEvents) {
            if (event.kind == 30023) continue
            val content = event.content
            val afterVideos = VIDEO_URL_REGEX.replace(content, "")
            IMAGE_URL_REGEX.findAll(afterVideos).forEach { imageUrls.add(it.value) }
        }
        val uniqueImageUrls = imageUrls.distinct().filter { imageDimensionCache.getCached(it) == null }
        if (uniqueImageUrls.isNotEmpty()) {
            imageDimensionCache.resolveAll(uniqueImageUrls)
            Log.d(TAG, "Media: resolved ${uniqueImageUrls.size} image dims")
        }

        // Video thumbnails via MediaMetadataRetriever (REST-only, capped at 3)
        if (mmrAllowed) {
            var thumbnailCount = 0
            for (event in novelEvents) {
                if (thumbnailCount >= mmrCap) break
                if (event.kind == 30023) continue
                val models = buildVideoRenderModels(event)
                for (model in models) {
                    if (thumbnailCount >= mmrCap) break
                    // Skip if poster URL exists (Coil handles it) or dims already resolved
                    if (!model.posterUrl.isNullOrBlank()) continue
                    if (model.widthPx != null && model.heightPx != null) continue
                    if (thumbnailCache.resolvedAspectRatios.containsKey(model.videoUrl)) continue
                    try {
                        withContext(Dispatchers.IO) { thumbnailCache.getThumbnail(model.videoUrl) }
                        thumbnailCount++
                        Log.d(TAG, "Media: MMR thumbnail ${model.videoUrl.take(60)}")
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Media: MMR thumbnail failed: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Full hydration: profiles + refs + media. Used by IDLE state where
     * there's no urgency to split phases.
     *
     * Coalescing: when a previous pass fired <COALESCE_COOLDOWN_MS ago AND
     * the current warm zone has only ≤COALESCE_NOVEL_THRESHOLD novel cards,
     * skip this pass entirely. Field logs showed sustained 7+ hydrator
     * firings per minute where each pass had only 1-2 novel events; each
     * tiny pass still amplified into per-event relay fetches at the source-
     * relay + hint-relay level. Holding a pass means those 1-2 events stay
     * "novel" and merge into the next pass — same coverage, fewer one-shots,
     * less radio churn. Cap is 2s so worst-case profile/ref delay is
     * bounded; per-card avatar autofetch covers visible-but-unhydrated rows
     * in the meantime.
     *
     * Bypass on big novel batches (cold start, fast scroll, feed swap):
     * fires immediately so the first paint isn't delayed.
     */
    @Volatile private var lastFullHydrationAt = 0L

    /**
     * @param viewportIds Event IDs for engagement fetch (viewport + look-ahead).
     *   Computed by the caller from the correctly-ordered event list so that
     *   feedRowsByIds re-sort cannot misalign indices. Warm-zone events still
     *   get media + own-engagement hydration.
     */
    suspend fun hydrateVisibleCards(events: List<FeedRow>, feedRelay: String? = null, viewportIds: Set<String> = emptySet()) {
        if (events.isEmpty()) return

        // Profile + ref hydration removed — per-card self-fetch paths handle
        // these (AvatarImage 800ms autofetch for profiles, QuoteCard/EmptyRepostBody
        // produceState for refs). Warm-zone batch dispatch was the burst source
        // causing Choreographer frame skips (30-69 frames) on relay-heavy feeds.
        // hydrateMedia remains load-bearing for layout stability (image dims).
        hydrateMedia(events, mmrAllowed = false)

        // Own-engagement backfill: accumulate novel IDs, dispatch after 250ms
        // debounce so hydrateVisibleCards returns immediately.
        accumulateOwnEngagement(events.map { it.id })

        // Engagement counts: viewport + forward look-ahead (IDs from caller).
        accumulateEngagement(events.filter { it.id in viewportIds })
    }

    /**
     * Engagement-only entry point for surfaces that handle their own media/profile
     * hydration (e.g. profile screen). Accumulates viewport + look-ahead posts
     * for debounced per-post engagement fetch. Same dedup + freshness gating as
     * the feed path.
     *
     * @param rows   full post list for the surface.
     * @param first  index of the first visible item.
     * @param last   index of the last visible item.
     */
    fun hydrateEngagement(rows: List<FeedRow>, first: Int, last: Int) {
        val start = first.coerceAtLeast(0)
        val end = (last + 1 + ENGAGEMENT_LOOKAHEAD).coerceAtMost(rows.size)
        if (start >= end) return
        accumulateEngagement(rows.subList(start, end))
    }

    /** Engagement target ID: for kind-6 reposts use the original event (rootId),
     *  for everything else use the event's own ID. Matches FeedRow.engagementId. */
    private fun engagementIdFor(row: FeedRow): String =
        if ((row.kind == 6 || row.kind == 16) && row.rootId != null) row.rootId!! else row.id

    /**
     * Filter novel IDs and add to pending buffer. Launches a debounced
     * background dispatch — each call resets the 250ms timer so rapid
     * viewport changes coalesce into a single REQ.
     */
    internal fun accumulateOwnEngagement(eventIds: List<String>) {
        val ownPk = memoryEventStore.ownPubkey ?: return
        if (eventIds.isEmpty()) return

        val novel = eventIds.filter { id ->
            !memoryEventStore.isOwnEngaged(id) &&
                id !in ownEngagementChecked &&
                id !in ownEngagementInFlight &&
                id !in pendingBackfillIds
        }
        if (novel.isEmpty()) return

        pendingBackfillIds.addAll(novel)

        // Cancel only the pending debounce delay — an in-flight dispatch
        // (separate coroutine) keeps running undisturbed.
        backfillDebounceJob?.cancel()
        backfillDebounceJob = backfillScope.launch {
            delay(250)
            // Dispatch in a separate coroutine so future accumulations
            // cancel only the delay, not the REQ round-trip.
            backfillScope.launch { dispatchOwnEngagement(ownPk) }
        }
    }

    /**
     * Dispatch the accumulated pending IDs as a single batched REQ to write relays.
     * EOSE-gated: moves IDs from in-flight → checked only when real EOSE arrives.
     */
    private suspend fun dispatchOwnEngagement(ownPk: String) {
        // Drain pending buffer
        val batch = pendingBackfillIds.toList()
        pendingBackfillIds.clear()
        if (batch.isEmpty()) return

        batch.forEach { ownEngagementInFlight.add(it) }

        val subId = "own-eng-${System.nanoTime()}"
        val req = buildOwnEngagementReq(subId, ownPk, batch)

        val writeRelays = memoryEventStore.writeRelaysFor(ownPk)
        val targetUrls = writeRelays.ifEmpty { relayPool.connectedRelayUrls() }
        if (targetUrls.isEmpty()) {
            batch.forEach { ownEngagementInFlight.remove(it) }
            return
        }

        // Register EOSE callback BEFORE dispatch so we don't miss a fast EOSE
        val eoseDeferred = CompletableDeferred<Unit>()
        relayPool.oneShotEoseCallbacks[subId] = eoseDeferred

        try {
            relayPool.sendOneShotBatch(targetUrls, listOf(req), listOf(subId))

            // Wait for real EOSE (or timeout). sendOneShotBatch returns immediately
            // for pool-reused relays (fire-and-forget), so the deferred is our
            // only signal that EOSE actually arrived.
            val eoseReceived = withTimeoutOrNull(10_000) { eoseDeferred.await() } != null

            if (eoseReceived) {
                batch.forEach {
                    ownEngagementInFlight.remove(it)
                    ownEngagementChecked.add(it)
                }
                Log.d(TAG, "Own-engagement backfill: ${batch.size} posts checked (EOSE) → ${targetUrls.size} relay(s)")
            } else {
                // Timeout without EOSE — remove from in-flight, stays retry-eligible
                batch.forEach { ownEngagementInFlight.remove(it) }
                relayPool.cleanupOneShotSub(subId)
                Log.w(TAG, "Own-engagement backfill: ${batch.size} posts timed out (no EOSE)")
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            batch.forEach { ownEngagementInFlight.remove(it) }
            relayPool.cleanupOneShotSub(subId)
            throw e
        } catch (_: Exception) {
            batch.forEach { ownEngagementInFlight.remove(it) }
            relayPool.cleanupOneShotSub(subId)
            Log.w(TAG, "Own-engagement backfill failed for ${batch.size} posts")
        }
    }

    // ── Engagement count fetch ─────────────────────────────────────────

    /**
     * Filter visible posts by freshness tier and accumulate for debounced dispatch.
     * Each call resets the 250ms timer so rapid viewport changes coalesce.
     */
    internal fun accumulateEngagement(events: List<FeedRow>) {
        if (events.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000L

        val novel = events.filter { row ->
            val engId = engagementIdFor(row)
            engId !in engagementInFlight &&
                engId !in pendingEngagementIds &&
                isEngagementStale(engId, row.createdAt, nowMs, nowSec)
        }
        if (novel.isEmpty()) return

        for (row in novel) {
            pendingEngagementIds.add(engagementIdFor(row))
        }

        engagementDebounceJob?.cancel()
        engagementDebounceJob = backfillScope.launch {
            delay(250)
            backfillScope.launch { dispatchEngagement() }
        }
    }

    /**
     * Returns true if this post's engagement counts are stale per freshness tiers.
     * Posts aged ≥7d that have been fetched once are never re-fetched.
     */
    internal fun isEngagementStale(
        eventId: String,
        postCreatedAt: Long,
        nowMs: Long = System.currentTimeMillis(),
        nowSec: Long = nowMs / 1000L,
    ): Boolean {
        val state = engagementTracker[eventId]
        if (state == null) return true // never fetched

        val ageSec = nowSec - postCreatedAt
        val staleSec = engagementFreshnessInterval(ageSec)
        if (staleSec == Long.MAX_VALUE) return false // ≥7d, fetched once — done

        val elapsedMs = nowMs - state.lastFetchedAt
        return elapsedMs >= staleSec * 1000L
    }

    /**
     * Dispatch per-post engagement REQs via outbox-routed relay resolution.
     *
     * Each post gets ONE combined REQ (kinds [1,6,16,7,9735]) with its own #e and
     * limit=ENGAGEMENT_LIMIT. Per-post dispatch is a spec invariant: the per-post
     * limit cap ensures bounded download per post.
     *
     * Relay targeting: post author's NIP-65 write relays (top 4 by trust+RTT) +
     * user's read relays (top 2) as secondary catch-net. Reactors fan their
     * kind-7/9735 broadcasts to the post author's write relays — that's where
     * engagement propagates. GLOBAL fallback when neither kind-10002 is known.
     * EOSE-gated completion via oneShotEoseCallbacks.
     */
    private suspend fun dispatchEngagement() {
        val batch = pendingEngagementIds.toList()
        pendingEngagementIds.clear()
        if (batch.isEmpty()) return

        batch.forEach { engagementInFlight.add(it) }

        // Resolve own read relays + blocked relays once per batch.
        val ownPk = memoryEventStore.ownPubkey
        val ownReadRelays = if (ownPk != null) {
            memoryEventStore.getReadWriteRelayConfigs(ownPk)
                .filter { it.marker == null || it.marker == "read" }
                .mapNotNull { normalizeRelayUrl(it.url) }
        } else emptyList()
        val blockedRelays = ownPk
            ?.let { memoryEventStore.getBlockedRelayUrls(it).toSet() }
            ?: emptySet()

        val nowMs = System.currentTimeMillis()

        // 1. Resolve each post's outbox relays (same resolution — same coverage).
        val idToRelays: Map<String, List<String>> = batch.associateWith { engId ->
            val authorPubkey = memoryEventStore.getEventEntity(engId)?.pubkey
            if (authorPubkey != null) {
                outboxResolver.resolveEngagementRelays(
                    authorPubkey = authorPubkey,
                    ownReadRelays = ownReadRelays,
                    blockedRelays = blockedRelays,
                )
            } else {
                ownReadRelays.ifEmpty { GLOBAL_RELAY_URLS }
            }
        }

        // 2. Invert → one chunked REQ per relay (coverage-ranked, capped).
        val relayBatches = coalesceByRelay(idToRelays, MAX_ENGAGEMENT_RELAYS, ENGAGEMENT_BATCH_CHUNK)
        if (relayBatches.isEmpty()) {
            batch.forEach { engagementInFlight.remove(it) }
            return
        }

        // 3. Fire one sub per (relay, chunk).
        for ((relay, ids) in relayBatches) {
            val subId = "eng-${System.nanoTime()}"
            val req = buildBatchedEngagementReq(subId, ids)

            val eoseDeferred = CompletableDeferred<Unit>()
            relayPool.oneShotEoseCallbacks[subId] = eoseDeferred

            backfillScope.launch {
                try {
                    relayPool.sendOneShotBatch(listOf(relay), listOf(req), listOf(subId))
                    val eosed = withTimeoutOrNull(ENGAGEMENT_BATCH_TIMEOUT_MS) { eoseDeferred.await() } != null
                    markEngagementFetched(ids, nowMs)
                    if (!eosed) relayPool.cleanupOneShotSub(subId)
                } finally {
                    backfillScope.launch {
                        delay(30_000)
                        relayPool.cleanupOneShotSub(subId)
                    }
                }
            }
        }

        // 4. Backstop: flush any post not marked by a covering sub within the window.
        backfillScope.launch {
            delay(ENGAGEMENT_BATCH_TIMEOUT_MS + 500)
            markEngagementFetched(batch.filter { it in engagementInFlight }, nowMs)
        }

        Log.d(TAG, "Engagement: ${batch.size} posts → ${relayBatches.size} REQ(s) across " +
            "${relayBatches.map { it.first }.distinct().size} relay(s)")
    }

    /** Per-post completion: snapshot stats, set capped, update freshness tracker, clear in-flight. Idempotent. */
    private fun markEngagementFetched(ids: List<String>, nowMs: Long) {
        for (id in ids) {
            if (id !in engagementInFlight) continue
            val stats = memoryEventStore.currentStatsSnapshot(id)
            val total = stats.replyCount + stats.repostCount + stats.reactionCount + stats.zapCount
            val capped = total >= ENGAGEMENT_LIMIT
            if (capped) memoryEventStore.markEngagementCapped(id)
            engagementTracker[id] = EngagementFetchState(lastFetchedAt = nowMs, capped = capped)
            engagementInFlight.remove(id)
        }
    }
}

/** Build the one-shot REQ JSON for own-engagement backfill. Package-private for testing. */
internal fun buildOwnEngagementReq(subId: String, ownPk: String, eventIds: List<String>): String =
    buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(subId))
        add(buildJsonObject {
            put("authors", buildJsonArray { add(JsonPrimitive(ownPk)) })
            put("kinds", buildJsonArray {
                add(JsonPrimitive(7))
                add(JsonPrimitive(6))
                add(JsonPrimitive(16)) // own kind-16 generic reposts light up "reposted" state
            })
            put("#e", buildJsonArray { eventIds.forEach { add(JsonPrimitive(it)) } })
        })
    }.toString()

/** Per-post engagement REQ limit. Posts reaching this show "N+" in the UI. */
internal const val ENGAGEMENT_LIMIT = 100

/** Number of posts BEYOND the viewport to prefetch engagement for.
 *  Covers ~2 screenfuls of scroll — by the time a post becomes visible,
 *  its reaction/zap counts are already in MES.  Bounded by the debounce
 *  and freshness tiers, so fling-through doesn't fan out wastefully. */
const val ENGAGEMENT_LOOKAHEAD = 12

/**
 * Freshness interval in seconds based on post age. Returns how long to wait
 * before re-fetching engagement for a post of the given age.
 *
 * | Post age   | Re-fetch after |
 * |------------|----------------|
 * | < 1 hour   | 2 minutes      |
 * | < 6 hours  | 10 minutes     |
 * | < 24 hours | 1 hour         |
 * | < 7 days   | 6 hours        |
 * | ≥ 7 days   | never (once)   |
 */
internal fun engagementFreshnessInterval(postAgeSec: Long): Long = when {
    postAgeSec < 3_600L       -> 120L          // <1h → 2min
    postAgeSec < 21_600L      -> 600L          // <6h → 10min
    postAgeSec < 86_400L      -> 3_600L        // <24h → 1h
    postAgeSec < 604_800L     -> 21_600L       // <7d → 6h
    else                      -> Long.MAX_VALUE // ≥7d → fetch once
}

/** Min interval between full hydration passes when novel count is small.
 *  Picked so worst-case profile/ref latency on a slow trickle of new events
 *  stays under ~2s — within the per-card avatar autofetch debounce window. */
private const val COALESCE_COOLDOWN_MS = 2_000L

/** Novel-event threshold below which a pass within COALESCE_COOLDOWN_MS is
 *  deferred. 3 lets tiny live-tail batches (1-2 events) coalesce while still
 *  firing immediately for fast scrolls / feed swaps where ≥4 cards are new. */
private const val COALESCE_NOVEL_THRESHOLD = 3

private const val MAX_ENGAGEMENT_RELAYS = 25
// per-post engagement budget = ENGAGEMENT_BATCH_LIMIT / ENGAGEMENT_BATCH_CHUNK.
// 500 / 5 = 100 events/post/relay — identical to the pre-Sprint-C per-post limit.
// DO NOT raise this without raising the limit proportionally, or posts in a chunk
// starve each other (counts silently drop to 0). Relays widely honor limit=500 but
// often cap higher values, so the budget is tuned via chunk size, not limit.
private const val ENGAGEMENT_BATCH_CHUNK = 5
private const val ENGAGEMENT_BATCH_LIMIT = 500
private const val ENGAGEMENT_BATCH_TIMEOUT_MS = 10_000L

/**
 * Invert a per-item → relays map into a minimal set of per-relay REQ batches.
 * Ranks relays by coverage (how many items list them) desc, keeps the top
 * [maxRelays], chunks each kept relay's id list into [chunkSize]. High-coverage
 * relays (own read relays appear for every item) sort first and are always kept,
 * so capping only trims long-tail single-item relays.
 *
 * @return (relayUrl, idsChunk) pairs — one REQ per element. Order: high-coverage first.
 */
internal fun coalesceByRelay(
    itemToRelays: Map<String, List<String>>,
    maxRelays: Int,
    chunkSize: Int,
): List<Pair<String, List<String>>> {
    if (itemToRelays.isEmpty()) return emptyList()
    val relayToIds = HashMap<String, MutableList<String>>()
    for ((id, relays) in itemToRelays) {
        for (r in relays.distinct()) relayToIds.getOrPut(r) { mutableListOf() }.add(id)
    }
    return relayToIds.entries
        .sortedByDescending { it.value.size }
        .take(maxRelays)
        .flatMap { (relay, ids) -> ids.chunked(chunkSize).map { relay to it } }
}

/** Build batched engagement REQ: engagement kinds, multiple #e ids, shared limit. */
internal fun buildBatchedEngagementReq(subId: String, eventIds: List<String>): String =
    buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(subId))
        add(buildJsonObject {
            put("kinds", buildJsonArray {
                add(JsonPrimitive(1))   // replies
                add(JsonPrimitive(6))   // note reposts
                add(JsonPrimitive(16))  // generic reposts (NIP-18) — count toward repost totals
                add(JsonPrimitive(7))   // reactions
                add(JsonPrimitive(9735)) // zaps
            })
            put("#e", buildJsonArray { eventIds.forEach { add(JsonPrimitive(it)) } })
            put("limit", JsonPrimitive(ENGAGEMENT_BATCH_LIMIT))
        })
    }.toString()

/** Extract the relay hint (index 2) from the first "e" tag in a repost's tags. */
fun extractRepostTargetRelay(tagsJson: String): String? {
    return try {
        val parsed = NostrJson.parseToJsonElement(tagsJson).jsonArray
        val eTag = parsed.firstOrNull { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "e" }
        eTag?.jsonArray?.getOrNull(2)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}

/** Extract the repost target event ID from the first "e" tag in a tags JSON string. */
fun extractRepostTargetId(tagsJson: String): String? {
    return try {
        val parsed = NostrJson.parseToJsonElement(tagsJson).jsonArray
        val eTag = parsed.firstOrNull { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "e" }
        val result = eTag?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content
        if (result == null) {
            Log.d("CardHydrator", "extractRepostTargetId: no e-tag found in ${parsed.size} tags, input=${tagsJson.take(200)}")
        }
        result
    } catch (e: Exception) {
        Log.w("CardHydrator", "extractRepostTargetId parse failed: ${e.message}, input=${tagsJson.take(200)}")
        null
    }
}

/** Extract quoted event IDs from nostr:nevent1.../nostr:note1... URIs in content. */
fun extractQuotedEventIds(content: String): List<String> {
    if (!content.contains("nostr:")) return emptyList()
    return NOSTR_URI_REGEX.findAll(content).mapNotNull { match ->
        if (Nip19FailureCache.isKnownBad(match.value)) return@mapNotNull null
        runCatching {
            when (val entity = Nip19Parser.uriToRoute(match.value)?.entity) {
                is NEvent -> entity.hex
                is NNote -> entity.hex
                else -> null
            }
        }.onFailure { Nip19FailureCache.markBad(match.value) }.getOrNull()
    }.toList()
}

/** Extract all p-tag pubkeys from a tags JSON string. */
fun extractPTagPubkeys(tagsJson: String): List<String> {
    return try {
        NostrJson.parseToJsonElement(tagsJson).jsonArray
            .filter { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "p" }
            .mapNotNull { it.jsonArray.getOrNull(1)?.jsonPrimitive?.content }
    } catch (_: Exception) { emptyList() }
}

