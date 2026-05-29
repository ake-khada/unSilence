package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.model.buildVideoRenderModels
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.ui.feed.IMAGE_URL_REGEX
import com.unsilence.app.ui.feed.ImageDimensionCache
import com.unsilence.app.ui.feed.VIDEO_URL_REGEX
import com.unsilence.app.ui.feed.VideoThumbnailCache
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
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
    private val userRepository: UserRepository,
    private val thumbnailCache: VideoThumbnailCache,
    private val imageDimensionCache: ImageDimensionCache,
    private val profileResolver: ProfileResolver,
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
    private val profilesHydrated = LinkedHashSet<String>()
    private val refsHydrated = LinkedHashSet<String>()
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
            profilesHydrated.clear()
            refsHydrated.clear()
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
    // Per-post bounded download: kinds [1,6,7,9735] with #e:[postId],
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
     * Profile resolution — avatar, name, identity.
     *
     * @param fanOut When false, only fetches from indexer relays (fastest path).
     *   Source relay and hint relay fetches are skipped.
     */
    suspend fun hydrateProfiles(events: List<FeedRow>, fanOut: Boolean = true, excludeSourceRelay: String? = null) {
        if (events.isEmpty()) return
        val novelEvents = filterAndMarkNovel(events, profilesHydrated)
        if (novelEvents.isEmpty()) return

        val pubkeys = mutableSetOf<String>()
        val profileHints = mutableMapOf<String, MutableList<String>>()

        for (event in novelEvents) {
            pubkeys.add(event.pubkey)
            if (event.kind == 6) {
                extractRepostAuthorPubkey(event.content, event.tags)?.let { pubkeys.add(it) }
            }
            if (fanOut) {
                extractProfileHints(event.content).forEach { (pk, relays) ->
                    pubkeys.add(pk)
                    profileHints.getOrPut(pk) { mutableListOf() }.addAll(relays)
                }
            }
        }

        if (pubkeys.isEmpty()) return

        // Pre-filter: drop pubkeys already cached in Room — avoids launching
        // orchestration (relay REQs, ProfileResolver batching, logging) for pubkeys
        // that will immediately resolve to "all fresh, skipping."
        val unresolved = profileResolver.filterUnresolved(pubkeys)
        if (unresolved.isEmpty()) return

        userRepository.fetchMissingProfiles(unresolved.toList())

        if (fanOut) {
            val sourceRelays = novelEvents.map { it.relayUrl }.distinct()
                .filter { it != excludeSourceRelay }
            if (sourceRelays.isNotEmpty()) {
                relayPool.fetchProfilesFromSourceRelays(unresolved.toList(), sourceRelays)
            }
            if (profileHints.isNotEmpty()) {
                // Only fan out hints for unresolved pubkeys
                val unresolvedHints = profileHints.filterKeys { it in unresolved }
                if (unresolvedHints.isNotEmpty()) {
                    relayPool.fetchProfilesFromHints(unresolvedHints.mapValues { it.value.distinct() })
                }
            }
        }

        val skipped = events.size - novelEvents.size
        Log.d(TAG, "Phase1 profiles: ${novelEvents.size} novel cards (${skipped} skipped) → ${unresolved.size} pubkeys${if (!fanOut) " (indexer-only)" else ", ${novelEvents.map { it.relayUrl }.distinct().size} source relays"}")
    }

    /**
     * Phase 2: Referenced events + thumbnails. The slow path — ref fetches have a
     * 1500ms wait for relay responses. Also resolves ref-event author profiles.
     * Called from SLOW_SCROLL (after profiles) and IDLE.
     */
    suspend fun hydrateRefs(events: List<FeedRow>, feedRelay: String? = null) {
        if (events.isEmpty()) return
        val novelEvents = filterAndMarkNovel(events, refsHydrated)
        if (novelEvents.isEmpty()) return

        val referencedIds = mutableSetOf<String>()
        val relayHints = mutableMapOf<String, String>()
        for (event in novelEvents) {
            if (event.kind == 6) {
                extractRepostTargetId(event.tags)?.let { id ->
                    referencedIds.add(id)
                    // Use e-tag relay hint if present; fall back to the wrapper's own relay.
                    // Bridged reposts (mostr.pub) often omit the relay hint in e-tags,
                    // but the target event usually lives on the same relay as the wrapper.
                    val eTagRelay = extractRepostTargetRelay(event.tags)
                    relayHints[id] = eTagRelay ?: event.relayUrl
                }
            }
            extractQuotedEventIds(event.content).forEach { referencedIds.add(it) }
            // Thread parents: replies reference their parent (replyToId) and root (rootId).
            // Fetching these ensures Conversations tab parent notes resolve via hydrateRefs
            // even if the initial lookupEvent times out.
            event.replyToId?.let { id ->
                referencedIds.add(id)
                relayHints.putIfAbsent(id, event.relayUrl)
            }
            event.rootId?.let { id ->
                referencedIds.add(id)
                relayHints.putIfAbsent(id, event.relayUrl)
            }
        }

        // Short-circuit: if no refs to resolve, skip the entire pipeline
        // (Room lookups, relay fetches, 1500ms delay, author resolution, thumbnails).
        if (referencedIds.isEmpty()) return

        // Skip refs in the negative cache (now in RelayPool, shared across all entry points)
        referencedIds.removeAll { relayPool.isEventUnresolved(it) }
        if (referencedIds.isEmpty()) return

        // Fetch missing referenced events (check MemoryEventStore, not Room)
        val missingRefs = referencedIds.filter { memoryEventStore.getEventEntity(it) == null }
        if (missingRefs.isNotEmpty()) {
            // Broadcast fetch for all missing refs
            relayPool.fetchEventsByIds(missingRefs.toList())

            // Hint-relay coverage. The broadcast targets only 6 connected relays —
            // events that live exclusively on the wrapper's source relay (or an
            // explicit e-tag hint relay) won't be covered. Group missing refs by
            // hint URL and send ONE batched REQ per hint relay instead of one per
            // ref id; in field logs the per-id loop fires 30+ separate one-shot
            // REQs at the same hint relay, queue-saturating it. bypassDedup:
            // the broadcast already registered these ids in eventFetchInFlight,
            // and we want the hint REQ to fire anyway.
            val hintBatches = HashMap<String, MutableList<String>>()
            for (id in missingRefs) {
                val hint = relayHints[id] ?: continue
                hintBatches.getOrPut(hint) { mutableListOf() }.add(id)
            }
            // Cap fan-out: take the highest-value hint relays (most missing refs).
            // The broadcast already hit the 6 connected relays; the long tail of
            // single-ref obscure relays retries on the next hydration pass.
            val cappedHints = hintBatches.entries
                .filter { it.key != feedRelay }
                .sortedByDescending { it.value.size }
                .take(MAX_HINT_RELAYS_PER_PASS)
            if (hintBatches.size > cappedHints.size) {
                Log.d(TAG, "hint fan-out capped: ${hintBatches.size} → ${cappedHints.size} relays")
            }
            for (entry in cappedHints) {
                relayPool.fetchEventsByIdsFromRelay(entry.key, entry.value, bypassDedup = true)
            }
        }

        // Wait for missing refs to arrive from relays
        if (missingRefs.isNotEmpty()) {
            delay(1500)
        }

        // ── A.6 outbox fallback: try author's NIP-65 write relays for stragglers ─
        // Phase tracking: which fetch path resolved each originally-missing ref
        val phaseResolved = mutableMapOf<String, String>() // refId → "source"|"outbox1"|"outbox2"

        val afterSourceRelay = if (missingRefs.isNotEmpty()) {
            val resolved = missingRefs.filter { memoryEventStore.getEventEntity(it) != null }
            resolved.forEach { phaseResolved[it] = "source" }
            missingRefs.filter { memoryEventStore.getEventEntity(it) == null }
        } else emptyList()

        if (afterSourceRelay.isNotEmpty()) {
            try {
                // Extract p-tag pubkeys from referencing events to find ref authors.
                // A.6.2: for kind-6 reposts without p-tags (bridged content from mostr.pub
                // etc.), use the wrapper's own pubkey as fallback author for outbox routing.
                val refAuthorPubkeys = mutableSetOf<String>()
                for (event in novelEvents) {
                    val pTags = extractPTagPubkeys(event.tags)
                    if (pTags.isNotEmpty()) {
                        pTags.forEach { refAuthorPubkeys.add(it) }
                    } else if (event.kind == 6) {
                        refAuthorPubkeys.add(event.pubkey)
                    }
                }
                Log.d(TAG, "Outbox: ${afterSourceRelay.size} still-missing refs, ${refAuthorPubkeys.size} p-tag authors")

                // Phase 1: try write relays already cached in MemoryEventStore.
                // The per-author relay-list dump was useful during early outbox
                // debugging but produces multi-KB log lines — printing 60-relay
                // arrays once per author per hydration pass added measurable
                // Main-thread cost when this runs frequently.
                val cachedWriteRelays = refAuthorPubkeys
                    .flatMap { pk -> memoryEventStore.writeRelaysForRanked(pk) }
                    .distinct()
                    .filter { it != feedRelay }
                    .take(5)

                if (cachedWriteRelays.isNotEmpty()) {
                    // Batch: ONE REQ per write relay with all afterSourceRelay
                    // ids in `{"ids":[...]}`, instead of per-id REQs (which sent
                    // up to 5 single-id REQs per missing ref). Same shape as
                    // the hint-batch fix in ecf931e for hydrateRefs's primary
                    // hint loop. With 4 missing refs × 5 cached write relays
                    // that's 20 REQs collapsed to 5.
                    for (relay in cachedWriteRelays) {
                        relayPool.fetchEventsByIdsFromRelay(relay, afterSourceRelay, bypassDedup = true)
                    }
                    Log.d(TAG, "Outbox fallback: ${afterSourceRelay.size} refs → ${cachedWriteRelays.size} cached write relays (batched)")
                }

                // Phase 2: for authors without cached relay lists, fetch kind-10002
                val authorsWithoutRelayList = refAuthorPubkeys
                    .filter { memoryEventStore.writeRelaysFor(it).isEmpty() }
                    .take(5)
                if (authorsWithoutRelayList.isNotEmpty()) {
                    relayPool.fetchRelayLists(authorsWithoutRelayList.toList())
                    delay(2000) // Wait for kind-10002 to arrive via EventProcessor

                    // Check phase 1 resolution before phase 2 dispatch
                    afterSourceRelay.filter { memoryEventStore.getEventEntity(it) != null && it !in phaseResolved }
                        .forEach { phaseResolved[it] = "outbox1" }

                    // Now resolve newly-cached write relays
                    val newWriteRelays = authorsWithoutRelayList
                        .flatMap { memoryEventStore.writeRelaysForRanked(it) }
                        .distinct()
                        .filter { it != feedRelay }
                        .take(5)
                    if (newWriteRelays.isNotEmpty()) {
                        // Re-check which refs are still missing
                        val stillMissingAfterPhase1 = afterSourceRelay
                            .filter { memoryEventStore.getEventEntity(it) == null }
                        if (stillMissingAfterPhase1.isNotEmpty()) {
                            // Same batch-by-relay pattern as phase 1.
                            for (relay in newWriteRelays) {
                                relayPool.fetchEventsByIdsFromRelay(
                                    relay, stillMissingAfterPhase1, bypassDedup = true,
                                )
                            }
                            Log.d(TAG, "Outbox fallback phase 2: ${stillMissingAfterPhase1.size} refs → ${newWriteRelays.size} newly-resolved write relays (batched)")
                        }
                    }
                }

                // Final wait for outbox relay responses
                if (cachedWriteRelays.isNotEmpty() || authorsWithoutRelayList.isNotEmpty()) {
                    delay(2000)
                }

                // Check outbox1/outbox2 resolution
                afterSourceRelay.filter { memoryEventStore.getEventEntity(it) != null && it !in phaseResolved }
                    .forEach { phaseResolved[it] = "outbox2" }
            } finally {
                // Write negative cache even if coroutine was canceled during a delay.
                // Both getEventEntity and markEventUnresolved are non-suspending
                // ConcurrentHashMap ops — safe in a finally block without NonCancellable.
                val finallyMissing = afterSourceRelay.filter { memoryEventStore.getEventEntity(it) == null }
                for (id in finallyMissing) { relayPool.markEventUnresolved(id) }
                val resolvedViaOutbox = afterSourceRelay.size - finallyMissing.size
                if (resolvedViaOutbox > 0) {
                    Log.d(TAG, "Outbox resolved: $resolvedViaOutbox/${afterSourceRelay.size} refs via author write relays")
                }
            }
        } else if (missingRefs.isNotEmpty()) {
            // Source relay resolved everything
            missingRefs.filter { memoryEventStore.getEventEntity(it) != null }
                .forEach { phaseResolved[it] = "source" }
        }

        // ── DIAGNOSTIC: structured log per originally-missing ref ─────────────
        if (missingRefs.isNotEmpty()) {
            // Build refId → (referencedBy, referencedByKind) mapping
            val refToReferencer = mutableMapOf<String, Pair<String, Int>>()
            for (event in novelEvents) {
                if (event.kind == 6) {
                    extractRepostTargetId(event.tags)?.let { refToReferencer[it] = event.id to event.kind }
                }
                event.replyToId?.let { refToReferencer.putIfAbsent(it, event.id to event.kind) }
                event.rootId?.let { refToReferencer.putIfAbsent(it, event.id to event.kind) }
                extractQuotedEventIds(event.content).forEach { refToReferencer.putIfAbsent(it, event.id to event.kind) }
            }
            for (refId in missingRefs) {
                val entity = memoryEventStore.getEventEntity(refId)
                val (referencedBy, refByKind) = refToReferencer[refId] ?: ("unknown" to -1)
                val phase = phaseResolved[refId] ?: "unresolved"
                Log.d(TAG, "Outbox final: refId=${refId.take(12)} exists=${entity != null} " +
                    "kind=${entity?.kind} author=${entity?.pubkey?.take(12)} " +
                    "relayUrl=${entity?.relayUrl} " +
                    "contentLen=${entity?.content?.length ?: 0} " +
                    "referencedBy=${referencedBy.take(12)} referencedByKind=$refByKind " +
                    "phase=$phase")
            }
        }

        // Resolve authors for ALL referenced events (existing + newly fetched).
        // Previously only missing refs got author resolution — refs already in
        // MemoryEventStore were skipped, leaving embedded quote author profiles
        // unresolved (no name, avatar, or NIP-05).
        val allRefAuthors = referencedIds
            .mapNotNull { memoryEventStore.getEventEntity(it)?.pubkey }
        if (allRefAuthors.isNotEmpty()) {
            userRepository.fetchMissingProfiles(allRefAuthors)
        }

        val outboxResolved = afterSourceRelay.size - afterSourceRelay.count { memoryEventStore.getEventEntity(it) == null }
        val skipped = events.size - novelEvents.size
        Log.d(TAG, "Phase2 refs: ${novelEvents.size} novel cards (${skipped} skipped) → ${referencedIds.size} refs (${missingRefs.size} missing, ${afterSourceRelay.size} post-source, $outboxResolved outbox-resolved)")
    }

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
        if (row.kind == 6 && row.rootId != null) row.rootId!! else row.id

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
     * Each post gets ONE combined REQ (kinds [1,6,7,9735]) with its own #e and
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
                add(JsonPrimitive(1))
                add(JsonPrimitive(6))
                add(JsonPrimitive(7))
                add(JsonPrimitive(9735))
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

/** Extract pubkey → relay hints from nostr:nprofile1... URIs in content. */
fun extractProfileHints(content: String): Map<String, List<String>> {
    if (!content.contains("nostr:")) return emptyMap()
    val hints = mutableMapOf<String, List<String>>()
    NOSTR_URI_REGEX.findAll(content).forEach { match ->
        if (Nip19FailureCache.isKnownBad(match.value)) return@forEach
        runCatching {
            val entity = Nip19Parser.uriToRoute(match.value)?.entity
            if (entity is NProfile && entity.relay.isNotEmpty()) {
                hints[entity.hex] = entity.relay.map { it.url }
            }
        }.onFailure { Nip19FailureCache.markBad(match.value) }
    }
    return hints
}
