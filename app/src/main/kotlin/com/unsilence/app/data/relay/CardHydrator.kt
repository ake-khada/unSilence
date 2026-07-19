package com.unsilence.app.data.relay

import android.content.Context
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Size
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.model.buildVideoRenderModels
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.ui.feed.IMAGE_URL_REGEX
import com.unsilence.app.ui.feed.ImageDimensionCache
import com.unsilence.app.ui.feed.VIDEO_URL_REGEX
import com.unsilence.app.ui.feed.VideoThumbnailCache
import dagger.hilt.android.qualifiers.ApplicationContext
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CardHydrator"

/** Bound on each per-phase hydrated-id memo. Sized so a tall warm-zone
 *  fan-out (~150 events on aggressive scroll) plus a feed swap fits with
 *  headroom; oldest IDs evict FIFO when the set exceeds this. */
private const val HYDRATED_CAP = 500

private const val PREFETCH_KEY_CAP = 768
private const val MAX_PREFETCH_WIDTH_PX = 1600
private const val MAX_MEDIA_PER_CARD = 4

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
 * Bounded card hydration for the viewport and immediate look-ahead.
 *
 * Handles:
 *  - Image dimensions needed for stable media layout
 *  - The signed-in user's reaction/repost state
 *  - Public engagement counts with age-based freshness
 *
 * Profiles and referenced events self-resolve in their card composables through
 * shared batched resolvers; duplicating them here previously amplified fetches.
 */
@Singleton
class CardHydrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val userRepository: UserRepository,
    private val thumbnailCache: VideoThumbnailCache,
    private val imageDimensionCache: ImageDimensionCache,
    private val ogFetcher: OgFetcher,
    private val outboxResolver: OutboxRelayResolver,
) {
    private val imageLoader by lazy { SingletonImageLoader.get(context) }

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

    private val imagePrefetched: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val imagePrefetchOrder = ConcurrentLinkedQueue<String>()
    private val imageDimensionWarmed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val imageDimensionWarmOrder = ConcurrentLinkedQueue<String>()
    private val ogWarmed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val ogWarmOrder = ConcurrentLinkedQueue<String>()
    private val ogWarmSemaphore = kotlinx.coroutines.sync.Semaphore(2)

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
        imagePrefetched.clear()
        imagePrefetchOrder.clear()
        imageDimensionWarmed.clear()
        imageDimensionWarmOrder.clear()
        ogWarmed.clear()
        ogWarmOrder.clear()
        backfillScope.coroutineContext.cancelChildren()
        pendingBackfillTargets.clear()
        ownEngagementInFlight.clear()
        ownEngagementChecked.clear()
        engagementTracker.clear()
        engagementInFlight.clear()
        pendingEngagementTargets.clear()
    }

    // ── Engagement count fetch ─────────────────────────────────────────
    // Per-post bounded download: kinds [1,6,16,7,9735] with #e:[postId],
    // limit 100. Targets the user's NIP-65 read relays (same as fetchThread).
    // Events flow through EventProcessor → MES aggregates → statsFlow → card display.
    //
    // Freshness tiers gate re-fetch based on post age:
    //   <1h→2min, <6h→10min, <24h→1h, <7d→6h, ≥7d→fetch once.

    /** Per-post engagement fetch state: when we last fetched, capped flag, and
     *  whether the article coordinate (#a/#A) was fetched — so an old id-only
     *  fetch can't mark an article "fresh" and suppress the coordinate fetch. */
    internal data class EngagementFetchState(
        val lastFetchedAt: Long = 0L,
        val capped: Boolean = false,
        val coordFetched: Boolean = false,
    )

    /** Tracks per-post engagement fetch state. Cleared on logout/reset. */
    internal val engagementTracker = ConcurrentHashMap<String, EngagementFetchState>()

    /** Posts whose engagement REQ is currently in flight. */
    private val engagementInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Pending engagement targets (id → target) awaiting debounced dispatch. */
    private val pendingEngagementTargets = ConcurrentHashMap<String, EngagementTarget>()

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

    /** Pending own-engagement targets (id → target) awaiting debounced dispatch. */
    private val pendingBackfillTargets = ConcurrentHashMap<String, EngagementTarget>()

    /** The debounce job — cancelled and relaunched on each new accumulation. */
    private var backfillDebounceJob: Job? = null

    /**
     * Independent media pipeline — no relay queries, no dependencies on ref resolution.
     *
     * @param mmrAllowed When true, MediaMetadataRetriever is used for video thumbnails
     *   (REST-only — 300ms/video codec work). When false, only image dimensions are
     *   resolved (IDLE-safe — BitmapFactory header-only, ~50ms each).
     */
    fun warmUpcomingAssets(
        events: List<FeedRow>,
        cardWidthPx: Int,
        maxRows: Int = 12,
        maxImagePrefetches: Int = 4,
        maxOgFetches: Int = 2,
        maxVideoThumbnails: Int = 8,
        maxProfileFetches: Int = 16,
        maxReferenceFetches: Int = 4,
        maxArticleFetches: Int = 2,
    ) {
        if (events.isEmpty() || cardWidthPx <= 0) return

        var imagePrefetches = 0
        var ogFetches = 0
        var videoThumbnails = 0
        var referenceFetches = 0
        var articleFetches = 0
        val profileCandidates = linkedMapOf<String, List<String>>()
        val referenceCandidates = ArrayList<ReferenceCandidate>()
        val articleCandidates = ArrayList<ArticleCandidate>()
        val warmedCachedRefs = HashSet<String>()

        // Do not memoize at row granularity here. Per-asset bounded sets below
        // already dedupe real work, while row-level marking can starve assets:
        // a row may be marked "warmed" in a pass where another asset cap was
        // spent before its primary image or OG preview was attempted.
        for (row in events.take(maxRows)) {
            val model = memoryEventStore.getOrParseEventModel(row.id) ?: row.toEventModel()
            collectProfileCandidates(row, model, profileCandidates)
            val referenceStart = referenceCandidates.size
            collectReferenceCandidates(model, row, referenceCandidates, articleCandidates)

            if (imagePrefetches < maxImagePrefetches) {
                for (candidate in imageCandidates(model)) {
                    if (imagePrefetches >= maxImagePrefetches) break
                    warmImageDimensions(candidate.url)
                    if (prefetchSizedImage(candidate.url, cardWidthPx, candidate.aspectRatio)) {
                        imagePrefetches++
                    }
                }
            }

            if (ogFetches < maxOgFetches) {
                val url = model.media.ogCandidate?.url
                if (!url.isNullOrBlank()) {
                    val countedAsFetch = warmOgMetadata(
                        url = url,
                        cardWidthPx = cardWidthPx,
                    )
                    if (countedAsFetch) ogFetches++
                }
            }

            videoThumbnails += warmVideoThumbnails(
                model = model,
                remaining = maxVideoThumbnails - videoThumbnails,
            )

            // If a referenced event is already cached, warm its nested assets now.
            // If not cached, collect it below for a bounded relay prefetch.
            if (maxReferenceFetches > 0 && warmedCachedRefs.size < maxReferenceFetches) {
                val newReferences = referenceCandidates.subList(referenceStart, referenceCandidates.size)
                for (ref in newReferences) {
                    if (warmedCachedRefs.size >= maxReferenceFetches) break
                    if (!warmedCachedRefs.add(ref.eventId)) continue
                    val warmed = warmCachedReferenceAssets(
                        eventId = ref.eventId,
                        cardWidthPx = cardWidthPx,
                        remainingVideoThumbnails = maxVideoThumbnails - videoThumbnails,
                        remainingImagePrefetches = maxImagePrefetches - imagePrefetches,
                        profileCandidates = profileCandidates,
                    )
                    videoThumbnails += warmed.videoThumbnails
                    imagePrefetches += warmed.imagePrefetches
                }
            }

            if (imagePrefetches >= maxImagePrefetches &&
                ogFetches >= maxOgFetches &&
                videoThumbnails >= maxVideoThumbnails &&
                profileCandidates.size >= maxProfileFetches &&
                referenceCandidates.size >= maxReferenceFetches &&
                articleCandidates.size >= maxArticleFetches
            ) break
        }

        if (profileCandidates.isNotEmpty() && maxProfileFetches > 0) {
            val batch = profileCandidates.entries.take(maxProfileFetches)
            val pubkeys = batch.map { it.key }
            val hintsByPubkey = batch.associate { it.key to it.value }
            backfillScope.launch {
                userRepository.fetchMissingProfiles(pubkeys, hintsByPubkey)
            }
        }

        if (maxReferenceFetches > 0) {
            val missingRefs = mergeReferenceCandidates(referenceCandidates)
                .asSequence()
                .filter { memoryEventStore.getEventEntity(it.eventId) == null }
                .take(maxReferenceFetches)
                .toList()
            if (missingRefs.isNotEmpty()) {
                backfillScope.launch { warmReferencedEvents(missingRefs, cardWidthPx) }
            }
        }

        if (maxArticleFetches > 0) {
            val missingArticles = articleCandidates
                .asSequence()
                .filter { memoryEventStore.articleRowByCoord(it.coord) == null }
                .distinctBy { it.coord }
                .take(maxArticleFetches)
                .toList()
            for (article in missingArticles) {
                articleFetches++
                backfillScope.launch {
                    val targets = relayResolutionTargets(
                        seenRelays = article.hints,
                        fallbackRelays = memoryEventStore.lookupWriteRelaysFor(article.author),
                    )
                    if (targets.hints.isNotEmpty()) {
                        relayPool.fetchArticleByCoord(targets.hints, article.author, article.dTag)
                    }
                    if (memoryEventStore.articleRowByCoord(article.coord) == null &&
                        targets.fallback.isNotEmpty()
                    ) {
                        relayPool.fetchArticleByCoord(targets.fallback, article.author, article.dTag)
                    }
                    if (memoryEventStore.articleRowByCoord(article.coord) == null) {
                        val bridgeTargets = bridgeFallbackRelayTargets(targets.all)
                        if (bridgeTargets.isNotEmpty()) {
                            relayPool.fetchArticleByCoord(
                                bridgeTargets,
                                article.author,
                                article.dTag,
                            )
                        }
                    }
                }
                if (articleFetches >= maxArticleFetches) break
            }
        }
    }

    private data class ImageCandidate(
        val url: String,
        val aspectRatio: Float,
    )

    private data class ReferenceCandidate(
        val eventId: String,
        val hints: List<String>,
        val authorPubkey: String?,
    )

    private data class ArticleCandidate(
        val coord: String,
        val author: String,
        val dTag: String,
        val hints: List<String>,
    )

    private data class WarmedReferenceAssets(
        val imagePrefetches: Int,
        val videoThumbnails: Int,
    )

    private fun imageCandidates(model: EventModel): List<ImageCandidate> = buildList {
        val articleImage = model.article?.image
        if (!articleImage.isNullOrBlank()) add(ImageCandidate(articleImage, 16f / 9f))

        for (image in model.media.images.take(MAX_MEDIA_PER_CARD)) {
            add(ImageCandidate(image.url, feedSafeAspect(image.imetaAspect)))
        }

        for (video in model.media.videos.take(MAX_MEDIA_PER_CARD).map { it.model }) {
            val poster = video.posterUrl
            if (!poster.isNullOrBlank()) {
                add(ImageCandidate(poster, feedSafeAspect(video.aspectRatio)))
            }
        }

        for (youtube in model.media.youtubes.take(MAX_MEDIA_PER_CARD)) {
            add(ImageCandidate("https://img.youtube.com/vi/${youtube.videoId}/hqdefault.jpg", 16f / 9f))
        }
    }

    private fun warmVideoThumbnails(model: EventModel, remaining: Int): Int {
        if (remaining <= 0) return 0
        var warmed = 0
        for (video in model.media.videos.map { it.model }) {
            if (warmed >= remaining) break
            if (thumbnailCache.getCached(video.videoUrl) != null) continue
            warmed++
            backfillScope.launch { thumbnailCache.warmThumbnail(video.videoUrl) }
        }
        return warmed
    }

    private fun collectProfileCandidates(
        row: FeedRow,
        model: EventModel,
        out: MutableMap<String, List<String>>,
    ) {
        val hints = rowRelayHints(row)
        fun add(pubkey: String) {
            if (pubkey.isBlank()) return
            out[pubkey] = boundedSeenRelayHints(
                seenRelays = out[pubkey].orEmpty() + hints,
            )
        }
        add(row.pubkey)
        add(model.pubkey)
        add(model.sourcePubkey)
        model.repost?.targetAuthorPubkey?.let(::add)
        extractPTagPubkeys(row.tags).forEach(::add)
        for (segment in model.segments) {
            when (segment) {
                is Segment.MentionPubkey -> add(segment.pubkeyHex)
                is Segment.QuoteEvent -> segment.author?.let(::add)
                is Segment.QuoteAddress -> add(segment.author)
                else -> Unit
            }
        }
    }

    private fun collectReferenceCandidates(
        model: EventModel,
        row: FeedRow,
        refs: MutableList<ReferenceCandidate>,
        articles: MutableList<ArticleCandidate>,
    ) {
        val rowHints = rowRelayHints(row)
        if (row.kind == 1) {
            val parentId = row.replyToId ?: row.rootId
            if (!parentId.isNullOrBlank() && parentId != row.id) {
                refs.add(
                    ReferenceCandidate(
                        eventId = parentId,
                        hints = rowHints,
                        authorPubkey = null,
                    ),
                )
            }
        }
        model.repost?.targetId?.let { id ->
            refs.add(
                ReferenceCandidate(
                    eventId = id,
                    hints = boundedSeenRelayHints(
                        seenRelays = rowHints,
                        additionalRelays = listOfNotNull(
                            model.repost.relayHint,
                            model.repost.addressRelayHint,
                        ),
                    ),
                    authorPubkey = model.repost.targetAuthorPubkey,
                ),
            )
        }
        for (segment in model.segments) {
            when (segment) {
                is Segment.QuoteEvent -> refs.add(
                    ReferenceCandidate(
                        eventId = segment.eventId,
                        hints = boundedSeenRelayHints(
                            seenRelays = rowHints,
                            additionalRelays = segment.hints,
                        ),
                        authorPubkey = segment.author,
                    ),
                )
                is Segment.QuoteAddress -> {
                    if (segment.kind == 30023) {
                        val coord = "30023:${segment.author}:${segment.dTag}"
                        articles.add(
                            ArticleCandidate(
                                coord = coord,
                                author = segment.author,
                                dTag = segment.dTag,
                                hints = boundedSeenRelayHints(
                                    seenRelays = rowHints,
                                    additionalRelays = segment.hints,
                                ),
                            ),
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    private fun warmCachedReferenceAssets(
        eventId: String,
        cardWidthPx: Int,
        remainingVideoThumbnails: Int,
        remainingImagePrefetches: Int,
        profileCandidates: MutableMap<String, List<String>>? = null,
    ): WarmedReferenceAssets {
        val refEvent = memoryEventStore.getNostrEvent(eventId) ?: return WarmedReferenceAssets(0, 0)
        val refRow = memoryEventStore.synthesizeFeedRow(refEvent)
        val refModel = memoryEventStore.getOrParseEventModel(eventId)
            ?: refRow.toEventModel()
        profileCandidates?.let { collectProfileCandidates(refRow, refModel, it) }

        val videoThumbnails = warmVideoThumbnails(
            model = refModel,
            remaining = remainingVideoThumbnails,
        )

        var imagePrefetches = 0
        if (remainingImagePrefetches > 0) {
            for (candidate in imageCandidates(refModel)) {
                if (imagePrefetches >= remainingImagePrefetches) break
                warmImageDimensions(candidate.url)
                if (prefetchSizedImage(candidate.url, cardWidthPx, candidate.aspectRatio)) {
                    imagePrefetches++
                }
            }
        }

        return WarmedReferenceAssets(
            imagePrefetches = imagePrefetches,
            videoThumbnails = videoThumbnails,
        )
    }

    private fun rowRelayHints(row: FeedRow): List<String> = feedRowRelayHints(
        primaryRelay = row.relayUrl,
        relaysSeen = row.relaysSeen +
            memoryEventStore.getNostrEvent(row.id)?.relaysSeen.orEmpty(),
        browseRelays = relayPool.activeFeedRelayHints(),
    )

    private fun mergeReferenceCandidates(
        refs: List<ReferenceCandidate>,
    ): List<ReferenceCandidate> {
        val merged = linkedMapOf<String, ReferenceCandidate>()
        for (ref in refs) {
            val existing = merged[ref.eventId]
            merged[ref.eventId] = if (existing == null) {
                ref
            } else {
                existing.copy(
                    hints = boundedSeenRelayHints(existing.hints + ref.hints),
                    authorPubkey = existing.authorPubkey ?: ref.authorPubkey,
                )
            }
        }
        return merged.values.toList()
    }

    private suspend fun warmReferencedEvents(refs: List<ReferenceCandidate>, cardWidthPx: Int) {
        val noHintIds = mutableListOf<String>()
        val coalescedHintFetches = mutableListOf<Pair<String, List<String>>>()
        val awaitingIds = LinkedHashSet<String>()
        val refsById = refs.associateBy { it.eventId }
        val triedRelaysById = mutableMapOf<String, MutableSet<String>>()
        for (ref in refs) {
            if (memoryEventStore.getEventEntity(ref.eventId) != null) {
                warmCachedReferenceAssets(
                    eventId = ref.eventId,
                    cardWidthPx = cardWidthPx,
                    remainingVideoThumbnails = MAX_MEDIA_PER_CARD,
                    remainingImagePrefetches = MAX_MEDIA_PER_CARD,
                )
                continue
            }
            if (relayPool.isEventUnresolved(ref.eventId)) continue
            awaitingIds.add(ref.eventId)
            if (ref.hints.isNotEmpty()) {
                coalescedHintFetches += ref.eventId to ref.hints
                triedRelaysById.getOrPut(ref.eventId) { linkedSetOf() }.addAll(ref.hints)
            } else {
                noHintIds.add(ref.eventId)
            }
        }
        // Independent hinted references must enter the same 150ms window together. Awaiting
        // them serially here would turn the coalescer into one delayed REQ per reference.
        coroutineScope {
            val coalescedJobs = coalescedHintFetches.map { (eventId, hints) ->
                async { relayPool.fetchEventById(eventId, hints) }
            }
            coalescedJobs.awaitAll()
        }
        if (noHintIds.isNotEmpty()) relayPool.fetchEventsByIds(noHintIds.distinct())

        // Phase 2: only unresolved hinted references advance to the target author's
        // outbox. Remove relays already queried by the locality phase.
        val ownRelayBatches = linkedMapOf<List<String>, MutableList<String>>()
        for (eventId in coalescedHintFetches.map { it.first }) {
            if (memoryEventStore.getEventEntity(eventId) != null) continue
            val ref = refsById[eventId] ?: continue
            val ownRelays = ref.authorPubkey
                ?.let(memoryEventStore::lookupWriteRelaysFor)
                .orEmpty()
            val targets = relayResolutionTargets(
                seenRelays = ref.hints,
                fallbackRelays = ownRelays,
            ).fallback
            if (targets.isEmpty()) continue
            triedRelaysById.getOrPut(eventId) { linkedSetOf() }.addAll(targets)
            ownRelayBatches.getOrPut(normalizedRelayTargets(targets).sorted()) { mutableListOf() }
                .add(eventId)
        }
        coroutineScope {
            ownRelayBatches.map { (targets, eventIds) ->
                async {
                    relayPool.fetchEventsByIdsFromTargets(
                        eventIds = eventIds.distinct(),
                        targetRelayUrls = targets,
                        bypassDedup = true,
                    )
                }
            }.awaitAll()
        }

        // Phase 3: broaden only unresolved hinted references to the ordinary pool,
        // excluding locality/outbox relays already queried for each group.
        val globalFallbackBatches = linkedMapOf<List<String>, MutableList<String>>()
        for (eventId in coalescedHintFetches.map { it.first }) {
            if (memoryEventStore.getEventEntity(eventId) != null) continue
            val tried = normalizedRelayTargets(triedRelaysById[eventId].orEmpty()).sorted()
            globalFallbackBatches.getOrPut(tried) { mutableListOf() }.add(eventId)
        }
        coroutineScope {
            globalFallbackBatches.map { (tried, eventIds) ->
                async {
                    relayPool.fetchEventsByIdsWithHints(
                        eventIds = eventIds.distinct(),
                        relayHints = emptyList(),
                        bypassDedup = true,
                        excludedRelayUrls = tried,
                    )
                }
            }.awaitAll()
        }

        coroutineScope {
            for (eventId in awaitingIds) {
                launch {
                    val entity = memoryEventStore.getEventEntity(eventId)
                        ?: withTimeoutOrNull(5_000L) {
                            memoryEventStore.eventEntityFlow(eventId).filterNotNull().first()
                        }
                    if (entity != null) {
                        warmCachedReferenceAssets(
                            eventId = eventId,
                            cardWidthPx = cardWidthPx,
                            remainingVideoThumbnails = MAX_MEDIA_PER_CARD,
                            remainingImagePrefetches = MAX_MEDIA_PER_CARD,
                        )
                    }
                }
            }
        }
    }

    private fun feedSafeAspect(aspectRatio: Float?): Float {
        val ratio = aspectRatio?.takeIf { it > 0f } ?: (16f / 9f)
        return ratio.coerceIn(0.2f, 5f)
    }

    private fun warmImageDimensions(url: String) {
        val key = url.substringBefore('#')
        if (!markBounded(key, imageDimensionWarmed, imageDimensionWarmOrder, PREFETCH_KEY_CAP)) return
        if (imageDimensionCache.getCached(url) != null) return
        backfillScope.launch {
            runCatching { imageDimensionCache.resolve(url) }
        }
    }

    /**
     * @return true if this call consumed one image-prefetch budget slot.
     */
    private fun prefetchSizedImage(url: String?, widthPx: Int, aspectRatio: Float): Boolean {
        if (url.isNullOrBlank()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val safeWidth = widthPx.coerceIn(1, MAX_PREFETCH_WIDTH_PX)
        val safeAspect = feedSafeAspect(aspectRatio)
        val heightPx = (safeWidth / safeAspect).toInt().coerceIn(100, 4000)
        val key = "${url.substringBefore('#')}@$safeWidth:$heightPx"
        if (!markBounded(key, imagePrefetched, imagePrefetchOrder, PREFETCH_KEY_CAP)) return false

        val request = ImageRequest.Builder(context)
            .data(url)
            .size(Size(Dimension.Pixels(safeWidth), Dimension.Pixels(heightPx)))
            .build()
        imageLoader.enqueue(request)
        return true
    }

    /**
     * @return true if this call started a new OG fetch, false for already-warmed
     * or already-cached URLs. Cached positive entries still get their image
     * opportunistically prefetched without consuming the OG network budget.
     */
    private fun warmOgMetadata(
        url: String,
        cardWidthPx: Int,
    ): Boolean {
        val key = url.substringBefore('#')
        val cached = ogFetcher.hasCached(url)
        val isNewWarm = markBounded(key, ogWarmed, ogWarmOrder, PREFETCH_KEY_CAP)
        if (!isNewWarm) return false

        backfillScope.launch {
            ogWarmSemaphore.acquire()
            try {
                val metadata = ogFetcher.fetch(url)
                val imageUrl = metadata?.imageUrl
                if (!imageUrl.isNullOrBlank()) prefetchSizedImage(imageUrl, cardWidthPx, 16f / 9f)
            } finally {
                ogWarmSemaphore.release()
            }
        }
        return !cached && isNewWarm
    }

    private fun markBounded(
        key: String,
        set: MutableSet<String>,
        order: ConcurrentLinkedQueue<String>,
        maxEntries: Int,
    ): Boolean {
        if (!set.add(key)) return false
        order.add(key)
        while (set.size > maxEntries) {
            val oldest = order.poll() ?: break
            set.remove(oldest)
        }
        return true
    }

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

        // Video thumbnails via MediaMetadataRetriever (REST-only, capped)
        if (mmrAllowed) {
            var thumbnailCount = 0
            for (event in novelEvents) {
                if (thumbnailCount >= mmrCap) break
                if (event.kind == 30023) continue
                val models = buildVideoRenderModels(event)
                for (model in models) {
                    if (thumbnailCount >= mmrCap) break
                    // A known poster or dimensions do not make the first frame
                    // ready for initial paint. Skip only if the bitmap itself is
                    // already cached.
                    if (thumbnailCache.getCached(model.videoUrl) != null) continue
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
     * @param viewportIds Event IDs for engagement fetch (viewport + look-ahead).
     *   Computed by the caller from the correctly-ordered event list so that
     *   feedRowsByIds re-sort cannot misalign indices. The larger warm zone is
     *   retained in memory but intentionally does not trigger network hydration.
     */
    suspend fun hydrateVisibleCards(events: List<FeedRow>, viewportIds: Set<String> = emptySet()) {
        if (events.isEmpty()) return

        // Hydrate only the viewport plus its bounded look-ahead. The caller's
        // warm zone is intentionally much larger for event retention, but doing
        // image-header probes, model parsing, and engagement routing for that
        // entire zone spends radio/CPU on cards a fast fling may never display.
        // An empty viewport set is retained as a safe fallback for non-feed callers.
        val priorityEvents = if (viewportIds.isEmpty()) events
            else events.filter { it.id in viewportIds }

        // Profile + ref hydration removed — per-card self-fetch paths handle
        // these (AvatarImage 800ms autofetch for profiles, QuoteCard/EmptyRepostBody
        // produceState for refs). Warm-zone batch dispatch was the burst source
        // causing Choreographer frame skips (30-69 frames) on relay-heavy feeds.
        // hydrateMedia remains load-bearing for layout stability (image dims).
        // Keep video MMR on the visible composable path only. A settled-scroll
        // background extraction can race the player/poster path and was one of
        // the regressions behind dark video cards during fast scrolling.
        hydrateMedia(priorityEvents, mmrAllowed = false)

        // Own-engagement backfill: accumulate novel targets (id+coord+author),
        // dispatch after 250ms debounce so hydrateVisibleCards returns immediately.
        accumulateOwnEngagement(priorityEvents)

        // Engagement counts: viewport + forward look-ahead (IDs from caller).
        accumulateEngagement(priorityEvents)
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
        if (row.kind == 6 || row.kind == 16) row.rootId ?: row.id else row.id

    /**
     * Everything the engagement pipeline needs, captured from the rendered row
     * BEFORE dispatch loses context. Carries addressable article/video coordinates
     * so #a/#A engagement can be fetched alongside event-id engagement.
     */
    internal data class EngagementTarget(
        val id: String,
        val coord: String?,
        val authorPubkey: String?,
        val createdAt: Long,
        /** Where this event was actually seen — current NIP-65 write relays
         *  may not cover where an old article or its zap receipts live. */
        val sourceRelays: List<String> = emptyList(),
    )

    private fun engagementTargetFor(row: FeedRow): EngagementTarget {
        // Prefer MES's cached parse (computeIfAbsent) over re-parsing the row — a
        // longform body is expensive and this runs per row per hydrate pass.
        val model = memoryEventStore.getOrParseEventModel(row.id) ?: row.toEventModel()
        val coord = when (model.effectiveKind) {
            30023 -> model.article?.dTag?.let { "30023:${model.pubkey}:$it" }
                ?: memoryEventStore.articleCoordForEvent(model.engagementId)
            34235, 34236 -> memoryEventStore.articleCoordForEvent(model.engagementId)
            else -> null
        }?.also { memoryEventStore.registerArticleCoord(model.engagementId, it) }
        val source = buildList {
            if (row.relayUrl.isNotBlank()) add(row.relayUrl)
            memoryEventStore.getNostrEvent(model.engagementId)?.relaysSeen?.let { addAll(it) }
            addAll(memoryEventStore.relayHintsForEvent(model.engagementId))
        }.mapNotNull { normalizeRelayUrl(it) }.distinct()
        return EngagementTarget(
            id = model.engagementId,
            coord = coord,
            authorPubkey = model.pubkey,
            createdAt = model.createdAt,
            sourceRelays = source,
        )
    }

    /**
     * Filter novel IDs and add to pending buffer. Launches a debounced
     * background dispatch — each call resets the 250ms timer so rapid
     * viewport changes coalesce into a single REQ.
     */
    internal fun accumulateOwnEngagement(rows: List<FeedRow>) {
        val ownPk = memoryEventStore.ownPubkey ?: return
        if (rows.isEmpty()) return

        var added = false
        for (row in rows) {
            // Fast reject before engagementTargetFor() parses/caches the full
            // EventModel. Overlapping viewport passes mostly hit this path.
            val quickId = engagementIdFor(row)
            if (memoryEventStore.isOwnEngaged(quickId)) continue
            if (quickId in ownEngagementChecked || quickId in ownEngagementInFlight ||
                pendingBackfillTargets.containsKey(quickId)) continue

            val t = engagementTargetFor(row)
            if (memoryEventStore.isOwnEngaged(t.id)) continue
            if (t.id in ownEngagementChecked || t.id in ownEngagementInFlight ||
                pendingBackfillTargets.containsKey(t.id)) continue
            pendingBackfillTargets[t.id] = t
            added = true
        }
        if (!added) return

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
        val targets = pendingBackfillTargets.values.toList()
        pendingBackfillTargets.clear()
        if (targets.isEmpty()) return
        val batch = targets.map { it.id }

        batch.forEach { ownEngagementInFlight.add(it) }

        val subId = "own-eng-${System.nanoTime()}"
        // Article rows: also fetch own coordinate-targeted reactions (#a/#A) —
        // coord carried from the row, so embedded/boosted longform works too.
        val coords = targets.mapNotNull { it.coord }
        val req = buildOwnEngagementReq(subId, ownPk, batch, coords)

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

        var added = false
        for (row in events) {
            // Most feed rows are ordinary kind-1 notes, whose engagement target
            // is known without parsing. Reject fresh/in-flight rows before the
            // more expensive EventModel + relay-hint derivation. Reposts and
            // articles still take the full path because they may need #a/#A.
            val quickId = engagementIdFor(row)
            if (quickId in engagementInFlight || pendingEngagementTargets.containsKey(quickId)) continue
            if (row.kind != 6 && row.kind != 16 && row.kind != 30023 &&
                !isEngagementStale(quickId, row.createdAt, hasCoord = false, nowMs, nowSec)
            ) continue

            val t = engagementTargetFor(row)
            if (t.id in engagementInFlight || pendingEngagementTargets.containsKey(t.id)) continue
            if (!isEngagementStale(t.id, t.createdAt, t.coord != null, nowMs, nowSec)) continue
            pendingEngagementTargets[t.id] = t
            added = true
        }
        if (!added) return

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
        hasCoord: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        nowSec: Long = nowMs / 1000L,
    ): Boolean {
        val state = engagementTracker[eventId]
        if (state == null) return true // never fetched

        // Article whose coordinate was never fetched (e.g. an old id-only fetch) —
        // force one re-fetch so #a/#A likes/zaps land.
        if (hasCoord && !state.coordFetched) return true

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
        val targets = pendingEngagementTargets.values.toList()
        pendingEngagementTargets.clear()
        if (targets.isEmpty()) return
        val batch = targets.map { it.id }
        val targetById = targets.associateBy { it.id }
        // IDs whose coordinate was fetched this pass (drives coordFetched state).
        val coordIds = targets.filter { it.coord != null }.map { it.id }.toSet()

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

        // 1. Resolve each post's outbox relays from the carried author pubkey
        //    (NOT getEventEntity — a boosted/embedded article's target may be absent
        //    from eventsById, which would drop it to the global fallback).
        val idToRelays: Map<String, List<String>> = batch.associateWith { engId ->
            val target = targetById[engId]
            val authorPubkey = target?.authorPubkey
            val base = if (authorPubkey != null) {
                outboxResolver.resolveEngagementRelays(
                    authorPubkey = authorPubkey,
                    ownReadRelays = ownReadRelays,
                    blockedRelays = blockedRelays,
                )
            } else {
                ownReadRelays.ifEmpty { GLOBAL_RELAY_URLS }
            }
            // Merge the event's own source/seen relays — covers old articles whose
            // engagement isn't on the author's current write relays.
            (base + (target?.sourceRelays ?: emptyList())).distinct()
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
            // Article rows: also fetch coordinate-targeted reactions/zaps (#a/#A) —
            // coord carried from the row, so embedded/boosted longform works too.
            val coords = ids.mapNotNull { targetById[it]?.coord }
            val req = buildBatchedEngagementReq(subId, ids, coords)

            val eoseDeferred = CompletableDeferred<Unit>()
            relayPool.oneShotEoseCallbacks[subId] = eoseDeferred

            backfillScope.launch {
                try {
                    relayPool.sendOneShotBatch(listOf(relay), listOf(req), listOf(subId))
                    val eosed = withTimeoutOrNull(ENGAGEMENT_BATCH_TIMEOUT_MS) { eoseDeferred.await() } != null
                    markEngagementFetched(ids, nowMs, coordIds)
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
            markEngagementFetched(batch.filter { it in engagementInFlight }, nowMs, coordIds)
        }

        Log.d(TAG, "Engagement: ${batch.size} posts → ${relayBatches.size} REQ(s) across " +
            "${relayBatches.map { it.first }.distinct().size} relay(s)")
    }

    /** Per-post completion: snapshot stats, set capped, update freshness tracker, clear
     *  in-flight. [coordIds] are the ids whose article coordinate was fetched this pass —
     *  recorded so an id-only fetch can't later be mistaken for a coordinate fetch. Idempotent. */
    private fun markEngagementFetched(ids: List<String>, nowMs: Long, coordIds: Set<String>) {
        for (id in ids) {
            if (id !in engagementInFlight) continue
            val stats = memoryEventStore.currentStatsSnapshot(id)
            val total = stats.replyCount + stats.repostCount + stats.reactionCount + stats.zapCount
            val capped = total >= ENGAGEMENT_LIMIT
            if (capped) memoryEventStore.markEngagementCapped(id)
            engagementTracker[id] = EngagementFetchState(
                lastFetchedAt = nowMs,
                capped = capped,
                coordFetched = id in coordIds,
            )
            engagementInFlight.remove(id)
        }
    }
}

/**
 * Build the one-shot REQ JSON for own-engagement backfill. Package-private for testing.
 *
 * Emits OR'd filters: the #e filter (reactions/reposts by event id) plus, when any
 * articles are visible, author-scoped #a/#A filters for kind-7 — own likes on a
 * long-form target the article COORDINATE, not its event id, so without these
 * hasReacted never lights for articles.
 */
internal fun buildOwnEngagementReq(
    subId: String,
    ownPk: String,
    eventIds: List<String>,
    coords: List<String> = emptyList(),
): String =
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
        if (coords.isNotEmpty()) {
            add(buildJsonObject {
                put("authors", buildJsonArray { add(JsonPrimitive(ownPk)) })
                put("kinds", buildJsonArray { add(JsonPrimitive(7)) })
                put("#a", buildJsonArray { coords.forEach { add(JsonPrimitive(it)) } })
            })
            add(buildJsonObject {
                put("authors", buildJsonArray { add(JsonPrimitive(ownPk)) })
                put("kinds", buildJsonArray { add(JsonPrimitive(7)) })
                put("#A", buildJsonArray { coords.forEach { add(JsonPrimitive(it)) } })
            })
        }
    }.toString()

/** Per-post engagement REQ limit. Posts reaching this show "N+" in the UI. */
internal const val ENGAGEMENT_LIMIT = 100

/** Number of posts BEYOND the viewport to prefetch engagement for.
 *  Covers roughly one screenful of scroll — by the time a post becomes visible,
 *  its reaction/zap counts are already in MES.  Bounded by the debounce
 *  and freshness tiers, while avoiding relay fan-out for cards skipped by
 *  a fast fling. */
const val ENGAGEMENT_LOOKAHEAD = 6

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

private const val MAX_ENGAGEMENT_RELAYS = 12
// per-post engagement budget = ENGAGEMENT_BATCH_LIMIT / ENGAGEMENT_BATCH_CHUNK.
// 500 / 5 = 100 events/post/relay — identical to the pre-Sprint-C per-post limit.
// DO NOT raise this without raising the limit proportionally, or posts in a chunk
// starve each other (counts silently drop to 0). Relays widely honor limit=500 but
// often cap higher values, so the budget is tuned via chunk size, not limit.
// internal: ProfilePipeline reuses this as the single source of truth for its
// engagement chunk size, so the two paths can't drift apart.
internal const val ENGAGEMENT_BATCH_CHUNK = 5
private const val ENGAGEMENT_BATCH_LIMIT = 500
private const val ENGAGEMENT_BATCH_TIMEOUT_MS = 10_000L

/**
 * Invert a per-item → relays map into a minimal set of per-relay REQ batches.
 * Uses greedy set-cover first (each selected relay covers the most still-uncovered
 * posts), then fills remaining slots by total coverage for redundancy. If a hard
 * cap would leave a post with zero queried relays, the result soft-overflows by
 * the minimum number of relays needed to retain complete item coverage.
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

    val selected = LinkedHashSet<String>()
    val uncovered = itemToRelays.keys.toMutableSet()

    // Coverage phase: prefer relays that add the most new posts.
    while (selected.size < maxRelays && uncovered.isNotEmpty()) {
        val best = relayToIds.entries
            .asSequence()
            .filter { it.key !in selected }
            .map { entry -> Triple(entry.key, entry.value.count { it in uncovered }, entry.value.size) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }
                .thenByDescending { it.third }
                .thenBy { it.first })
            .firstOrNull()
            ?: break
        selected.add(best.first)
        uncovered.removeAll(relayToIds[best.first].orEmpty().toSet())
    }

    // Redundancy phase: spend unused budget on the broadest remaining relays.
    relayToIds.entries
        .asSequence()
        .filter { it.key !in selected }
        .sortedWith(compareByDescending<Map.Entry<String, MutableList<String>>> { it.value.size }
            .thenBy { it.key })
        .take((maxRelays - selected.size).coerceAtLeast(0))
        .forEach { selected.add(it.key) }

    // Coverage is non-negotiable: add one preferred relay for any item a very
    // small cap could not cover. This is bounded by the item count.
    if (uncovered.isNotEmpty()) {
        for (id in uncovered.toList()) {
            val fallback = itemToRelays[id].orEmpty().firstOrNull() ?: continue
            selected.add(fallback)
            uncovered.removeAll(relayToIds[fallback].orEmpty().toSet())
        }
    }

    return selected.flatMap { relay ->
        relayToIds[relay].orEmpty().chunked(chunkSize).map { relay to it }
    }
}

/**
 * Build batched engagement REQ as OR'd filters: the #e filter (all engagement
 * kinds by event id) plus coordinate filters for addressable content. The uppercase
 * #A filter also fetches NIP-22 comments rooted at the coordinate.
 */
internal fun buildBatchedEngagementReq(
    subId: String,
    eventIds: List<String>,
    coords: List<String> = emptyList(),
): String =
    buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(subId))
        add(buildJsonObject {
            put("kinds", buildJsonArray {
                add(JsonPrimitive(1))   // replies
                add(JsonPrimitive(1111)) // NIP-22 comments on event-addressed videos
                add(JsonPrimitive(6))   // note reposts
                add(JsonPrimitive(16))  // generic reposts (NIP-18) — count toward repost totals
                add(JsonPrimitive(7))   // reactions
                add(JsonPrimitive(9735)) // zaps
            })
            put("#e", buildJsonArray { eventIds.forEach { add(JsonPrimitive(it)) } })
            put("limit", JsonPrimitive(ENGAGEMENT_BATCH_LIMIT))
        })
        if (coords.isNotEmpty()) {
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(7)); add(JsonPrimitive(9735)) })
                put("#a", buildJsonArray { coords.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(ENGAGEMENT_BATCH_LIMIT))
            })
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(7))
                    add(JsonPrimitive(9735))
                    add(JsonPrimitive(1111))
                })
                put("#A", buildJsonArray { coords.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(ENGAGEMENT_BATCH_LIMIT))
            })
        }
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
