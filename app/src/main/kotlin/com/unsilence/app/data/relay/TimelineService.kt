package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TimelineService"

/** Max time we wait for the FAST tier to EOSE before kicking off the SLOW
 *  tier anyway. Picked so that on a healthy network the user already has
 *  events on screen by the time obscure outbox relays start handshaking,
 *  but a slow / partial FAST tier still gets long-tail coverage. */
private const val SLOW_TIER_WATCHDOG_MS = 2_000L

/** Poll interval while waiting for FAST EOSEs. Coarse — we just need to
 *  wake up promptly when allEosed flips. */
private const val SLOW_TIER_POLL_MS = 50L

/** Min interval between intermediate (pre-EOSE) k-way merges in
 *  subscribeTimeline. EOSE-driven invocations always merge — never gated —
 *  so the final emit can never lose tail events. */
private const val INTERMEDIATE_MERGE_MIN_INTERVAL_MS = 250L

/** Max refs persisted per timeline to bound snapshot disk usage. */
private const val PERSISTED_REFS_CAP = 500

/** Cap initial cache emit to viewport-relevant size. Remaining refs stay
 *  in Timeline for loadMoreTimeline to page through on scroll. Prevents
 *  burst-work that saturates the main thread on tab switch (ANR). */
private const val INITIAL_CACHE_EMIT_CAP = 60

/** One targeted REQ page. Larger repairs progress on subsequent scroll pages. */
private const val TIMELINE_ID_REPAIR_CAP = MAX_EVENT_IDS_PER_REQ

/** At most two targeted REQs per page, including relay-hint rotation after a miss. */
private const val TIMELINE_ID_REPAIR_REQUEST_CAP = 2
private const val CATCH_UP_PAGES_PER_REPLAY = 3
private const val CATCH_UP_PAGE_LIMIT = 300
private const val CATCH_UP_COOLDOWN_MS = 1_000L

/**
 * EOSE-aware multi-relay timeline subscription with persistent ref cache.
 *
 * Mirrors Jumble's client.subscribeTimeline (client.service.ts:328) and
 * _subscribeTimeline (client.service.ts:576) — the scheduler that wraps
 * the lower-level Subscription primitive (P1) with:
 *
 *   1. Per-subscribe-call cache of TimelineRef (id, createdAt) — second
 *      subscribe to the same {urls, filter} displays the cache, but derives
 *      since only from confirmed per-relay coverage, never the newest row.
 *
 *   2. Pre-EOSE buffering — events arriving before EOSE are collected,
 *      sorted by compareEventsDesc, sliced to filter.limit. Single
 *      onEvents(events, eosed) callback per relay-EOSE.
 *
 *   3. Threshold merge — onEvents fires once half of the SubRequests in
 *      the call have EOSE'd, so the user sees content as fast as possible
 *      even when one relay is slow.
 *
 *   4. Post-EOSE live tail — events with created_at > globalEosedAt are
 *      fired via onNew and inserted into the timeline refs at the correct
 *      sorted position. Events older than head are dropped.
 *
 * Threading: subscribeTimeline is suspend (IO). Mutations to per-call
 * state happen under per-state-object synchronized blocks. Timeline cache
 * itself is a ConcurrentHashMap; refs and coverage are immutable snapshots.
 * Missing-middle pages are bounded per relay and share two request slots.
 */
@Singleton
class TimelineService @Inject constructor(
    private val subscription: Subscription,
    private val eventLoader: TimelineEventLoader,
) {
    /** Dispatcher for fire-and-forget subscribe coroutines. Tests override with Unconfined. */
    internal var subscribeDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
    internal data class Timeline(
        val refs: List<TimelineRef>,
        val filter: NostrFilter,
        val urls: List<String>,
        val coverage: Map<String, TimelineCoverage> = emptyMap(),
    )

    private val timelines = ConcurrentHashMap<String, Timeline>()
    private val multiKeys = ConcurrentHashMap<String, List<String>>()
    private val seqCounter = AtomicLong(0)
    private val payloadMismatchLoggedKeys = ConcurrentHashMap.newKeySet<String>()
    private val liveReferenceLock = Any()
    private val timelineLock = Any()
    private val liveReferenceIdsByTimeline = HashMap<String, Set<String>>()
    private val liveReferenceCounts = HashMap<String, Int>()
    private val catchUpSlots = Semaphore(2)
    private val sessions = ConcurrentHashMap<String, CatchUpSession>()
    private val manualCatchUpCursor = AtomicLong(0)
    private val storeEpoch = AtomicLong(0)
    private data class CatchUpBudget(val epoch: Long, var attempts: Int = 0, var lastAt: Long = 0)
    private val catchUpBudgets = LinkedHashMap<String, CatchUpBudget>(16, 0.75f, true)

    /** Share limits across author subsets and metadata-driven resubscriptions. */
    private fun budget(url: String): CatchUpBudget {
        val epoch = subscription.replayEpoch(url)
        val old = catchUpBudgets[url]
        if (old?.epoch == epoch) return old
        if (old == null && catchUpBudgets.size >= 128) catchUpBudgets.remove(catchUpBudgets.keys.first())
        return CatchUpBudget(epoch, lastAt = old?.lastAt ?: 0).also { catchUpBudgets[url] = it }
    }

    private fun automaticBudgetAvailable(url: String): Boolean = synchronized(catchUpBudgets) {
        budget(url).attempts < CATCH_UP_PAGES_PER_REPLAY
    }

    /** Owned by a timeline handle, never by an account-independent scope. */
    private inner class CatchUpSession(
        val key: String,
        val request: SubRequest,
        val scope: CoroutineScope,
        val seedHead: Long?,
        val onHistory: (List<NostrEvent>) -> Unit,
    ) {
        private var generation = 0
        private val jobs = HashMap<String, Job>()
        private val lastAttempt = HashMap<String, Long>()
        private val completions = HashMap<String, RelayCompletion>()
        private var displayComplete = false
        private var displayFloor: Long? = null

        fun replay(urls: Set<String> = request.urls.toSet()) = synchronized(this) {
            generation++
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            completions.clear()
            displayComplete = false
            synchronized(timelineLock) {
                val old = timelines[key] ?: Timeline(emptyList(), request.filter, request.urls)
                // With legacy/unknown coverage, catch up to the previous head,
                // not through the entire seeded history. Existing holes below
                // that head are not certified or repaired by this replay.
                val previousHead = listOfNotNull(seedHead, old.refs.firstOrNull()?.createdAt)
                    .maxOrNull()?.coerceAtMost(System.currentTimeMillis() / 1000L)
                putTimeline(key, old.copy(urls = request.urls, coverage = request.urls.associateWith { url ->
                    val previous = old.coverage[url] ?: TimelineCoverage()
                    if (url in urls) previous.replay(previousHead) else previous
                }))
            }
        }

        fun completion(page: RelayCompletion) {
            synchronized(this) {
                if (sessions[key] !== this) return
                completions[page.url] = page
                if (!displayComplete) return
            }
            finish(displayFloor)
        }

        fun finish(commonFloor: Long?) {
            val urls = synchronized(this) {
                if (sessions[key] !== this) return
                displayComplete = true
                displayFloor = commonFloor
                synchronized(timelineLock) {
                    val old = timelines[key] ?: return
                    val updated = old.coverage.toMutableMap()
                    for ((url, page) in completions) {
                        val coverage = updated[url] ?: continue
                        val bounded = if (coverage.floor == null && coverage.head == null)
                            coverage.copy(floor = commonFloor) else coverage
                        updated[url] = bounded.completed(page, request.filter.limit, System.currentTimeMillis() / 1000)
                    }
                    putTimeline(key, old.copy(coverage = updated))
                }
                request.urls.also { completions.clear() }
            }
            urls.forEach { start(it, automatic = true) }
        }

        fun start(url: String, automatic: Boolean): Job? {
            val job = synchronized(this) {
                if (sessions[key] !== this) return null
                jobs[url]?.takeIf { it.isActive }?.let { return it }
                if (timelines[key]?.coverage?.get(url)?.pending != true) return null
                if (automatic && !automaticBudgetAvailable(url)) return null
                val epoch = generation
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    do {
                        if (!automatic && !subscription.canCatchUp(url)) {
                            synchronized(this@CatchUpSession) { lastAttempt[url] = System.currentTimeMillis() }
                            break
                        }
                        // Also bounds repeated manual scroll requests and synthetic
                        // completion retries. The transport's longer cooldown wins.
                        val wait = synchronized(catchUpBudgets) {
                            CATCH_UP_COOLDOWN_MS - (System.currentTimeMillis() - budget(url).lastAt)
                        }
                        delay(maxOf(wait, if (automatic) CATCH_UP_COOLDOWN_MS else 0L))
                        val page = catchUpSlots.withPermit {
                            val before = synchronized(this@CatchUpSession) {
                                if (generation != epoch || sessions[key] !== this@CatchUpSession) return@withPermit PageResult()
                                val coverage = timelines[key]?.coverage?.get(url) ?: return@withPermit PageResult()
                                if (!coverage.pending) return@withPermit PageResult()
                                synchronized(catchUpBudgets) {
                                    val budget = budget(url)
                                    if (automatic && budget.attempts >= CATCH_UP_PAGES_PER_REPLAY) return@withPermit PageResult()
                                    if (automatic) budget.attempts++
                                    budget.lastAt = System.currentTimeMillis()
                                }
                                lastAttempt[url] = System.currentTimeMillis()
                                coverage
                            }
                            if (!subscription.canCatchUp(url)) return@withPermit PageResult()
                            fetchPage(url, before, epoch)
                        }
                        currentCoroutineContext().ensureActive()
                        if (sessions[key] !== this@CatchUpSession) break
                        if (page.events.isNotEmpty()) onHistory(page.events)
                        if (!page.continuePaging) break
                    } while (automatic)
                }
                jobs[url] = job
                job
            }
            job.start()
            return job
        }

        private suspend fun fetchPage(url: String, before: TimelineCoverage, epoch: Int): PageResult {
            val pageEvents = Collections.synchronizedList(mutableListOf<NostrEvent>())
            val completion = CompletableDeferred<RelayCompletion>()
            val limit = minOf(request.filter.limit ?: CATCH_UP_PAGE_LIMIT, CATCH_UP_PAGE_LIMIT).coerceAtLeast(1)
            var handle: Subscription.Handle? = null
            val result = try {
                withTimeoutOrNull(10_000) {
                    handle = subscription.subscribe(
                        urls = listOf(url),
                        filter = request.filter.copy(
                            since = before.floor ?: request.filter.since,
                            until = before.until ?: request.filter.until,
                            limit = limit,
                        ),
                        onevent = { pageEvents.add(it) },
                        onRelayCompletion = { completion.complete(it) },
                        replayable = false,
                        retryFailedSends = false,
                    )
                    completion.await()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null // retain the window; a failed wait is not coverage
            } finally {
                handle?.close()
            } ?: RelayCompletion(url, RelayCompletionReason.Watchdog, 0, null, null)
            currentCoroutineContext().ensureActive()
            val sorted = synchronized(pageEvents) { TimelineMerge.sort(pageEvents) }
            synchronized(this) {
                if (generation != epoch || sessions[key] !== this) return PageResult()
                if (!subscription.canCatchUp(url)) return PageResult()
                synchronized(timelineLock) {
                    if (sessions[key] !== this) return PageResult()
                    val old = timelines[key] ?: return PageResult()
                    // A late genuine EOSE may have completed the original page.
                    if (old.coverage[url] != before) return PageResult()
                    val after = before.completed(result, limit, System.currentTimeMillis() / 1000)
                    val existingIds = old.refs.mapTo(HashSet()) { it.id }
                    val novel = sorted.filter { it.id !in existingIds }
                    putTimeline(key, old.copy(
                        refs = mergeRefs(old.refs, sorted.map { TimelineRef(it.id, it.createdAt) }),
                        coverage = old.coverage + (url to after),
                    ))
                    // Retry synthetic completions in the same window. A genuine
                    // saturated timestamp with no cursor progress cannot be paged
                    // safely; keep it unresolved, without spinning.
                    return PageResult(novel, after.pending &&
                        (result.reason != RelayCompletionReason.Genuine || after.until != before.until))
                }
            }
        }

        fun scheduleManualPage() {
            if (!synchronized(this) { displayComplete }) return
            val running = synchronized(this) { jobs.values.filter { it.isActive } }
            if (running.isNotEmpty()) {
                return
            }
            val url = timelines[key]?.coverage?.entries
                ?.filter { it.value.pending }
                ?.minByOrNull { synchronized(this) { lastAttempt[it.key] ?: 0L } }?.key ?: return
            // Owned by the handle and delivered through onHistory. Never join
            // this job from load-more: its rows are above the reader's bottom.
            start(url, automatic = false)
        }
    }

    private data class PageResult(val events: List<NostrEvent> = emptyList(), val continuePaging: Boolean = false)

    /** Ref-index merge only; all displayed event lists go through TimelineMerge. */
    private fun mergeRefs(current: List<TimelineRef>, incoming: List<TimelineRef>): List<TimelineRef> {
        if (incoming.isEmpty()) return current
        val ids = current.mapTo(HashSet()) { it.id }
        val order = compareByDescending<TimelineRef> { it.createdAt }.thenBy { it.id }
        val novel = incoming.filter { ids.add(it.id) }.sortedWith(order)
        if (novel.isEmpty()) return current
        val merged = ArrayList<TimelineRef>(current.size + novel.size)
        var i = 0
        var j = 0
        while (i < current.size && j < novel.size) {
            if (order.compare(current[i], novel[j]) <= 0) merged.add(current[i++])
            else merged.add(novel[j++])
        }
        while (i < current.size) merged.add(current[i++])
        while (j < novel.size) merged.add(novel[j++])
        return merged
    }

    /**
     * Replace a timeline and its bounded reverse-reference index atomically.
     * Admission control asks [isLiveReferenced] on its hot path; rebuilding a
     * union of every timeline for every event would cost more than the sweep it
     * replaces. Reference counts preserve IDs shared by multiple timelines.
     */
    private fun putTimeline(key: String, timeline: Timeline) {
        val newIds = timeline.refs
            .asSequence()
            .take(PERSISTED_REFS_CAP)
            .mapTo(HashSet()) { it.id }
        synchronized(liveReferenceLock) {
            val oldIds = liveReferenceIdsByTimeline.put(key, newIds).orEmpty()
            for (id in oldIds) {
                if (id in newIds) continue
                val remaining = (liveReferenceCounts[id] ?: 1) - 1
                if (remaining <= 0) liveReferenceCounts.remove(id)
                else liveReferenceCounts[id] = remaining
            }
            for (id in newIds) {
                if (id in oldIds) continue
                liveReferenceCounts[id] = (liveReferenceCounts[id] ?: 0) + 1
            }
            timelines[key] = timeline
        }
    }

    private data class CachedRefCandidate(
        val ref: TimelineRef,
        val relayHints: List<String>,
    )

    private data class CachedTimelinePage(
        val candidates: List<CachedRefCandidate>,
        val resolution: TimelineEventResolution,
        val contiguousEvents: List<NostrEvent>,
    )

    private fun snapshotTimelineKeys(timelineKey: String): List<String> {
        val live = multiKeys[timelineKey] ?: return listOf(timelineKey)
        return synchronized(live) { ArrayList(live) }
    }

    interface TimelineHandle {
        val timelineKey: String
        fun close()
    }

    suspend fun subscribeTimeline(
        subRequests: List<SubRequest>,
        onEvents: (events: List<NostrEvent>, eosed: Boolean) -> Unit,
        onNew: (event: NostrEvent) -> Unit = {},
        onClose: ((url: String, reason: String) -> Unit)? = null,
        needSort: Boolean = true,
        seedHead: Long? = null,
        onHistory: ((List<NostrEvent>) -> Unit)? = null,
    ): TimelineHandle {
        val multiKey = generateMultiKey(subRequests)
        val perSubKeys = Collections.synchronizedList(mutableListOf<String>())
        val perSubTimelines = Array<List<NostrEvent>>(subRequests.size) { emptyList() }
        val perSubEosed = Array(subRequests.size) { AtomicBoolean(false) }
        val eosedCount = AtomicInteger(0)
        val multiHandles = Collections.synchronizedList(mutableListOf<Subscription.Handle>())

        // Cross-sub dedup for onNew (Jumble's newEventIdSet)
        val newEventIdSet = ConcurrentHashMap.newKeySet<String>()

        // Time gate for intermediate (pre-EOSE) merges. Every per-relay EOSE
        // on every sub used to re-run the full k-way mergeTimelines — O(N×K)
        // per batch with most intermediate results discarded. Initialized to
        // 0 so the very first batch merges immediately (first paint).
        // Skipped batches are never lost: events stay accumulated in
        // perSubTimelines and ride the next merge; each sub's final EOSE
        // (eosed=true) merges unconditionally.
        val lastIntermediateMergeAtMs = AtomicLong(0L)

        // Fire-and-forget: launch all subscribeSingle calls concurrently and
        // return immediately. Jumble doesn't block on the subscribe loop —
        // the handle is returned right away, callbacks fire as EOSEs arrive.
        // The subScope is owned by the TimelineHandle; close() cancels it.
        val subScope = CoroutineScope(SupervisorJob() + subscribeDispatcher)
        val catchUps = subRequests.map { request ->
            val normalized = request.copy(urls = request.urls.mapNotNull(::normalizeRelayUrl).distinct())
            val key = generateTimelineKey(request.urls, request.filter)
            perSubKeys.add(key)
            CatchUpSession(key, normalized, subScope, seedHead) { history ->
                if (onHistory != null) onHistory(history) else onEvents(history, false)
            }.also {
                sessions[key] = it
                it.replay()
            }
        }

        // ── Lazy outbox: defer SLOW-tier connects ────────────────────────
        // The resolver tags low-priority outbox write relays as SubTier.SLOW.
        // We launch FAST-tier subscribeSingles immediately, then either wait
        // for ALL FAST tier to EOSE or for SLOW_TIER_WATCHDOG_MS, whichever
        // comes first, before launching SLOW. If the user closes/swaps the
        // feed before then, subScope.cancel() drops the deferred coroutines
        // and those WebSockets are never opened.
        val fastIndices = subRequests.withIndex()
            .filter { it.value.tier == SubTier.FAST }
            .map { it.index }
        val slowIndices = subRequests.withIndex()
            .filter { it.value.tier == SubTier.SLOW }
            .map { it.index }
        val fastEosedCount = AtomicInteger(0)

        val launchSingle: (Int) -> Unit = { index ->
            val sr = subRequests[index]
            subScope.launch {
                subscribeSingle(
                    index = index,
                    subRequest = sr,
                    onPerSubEvents = { events, eosed ->
                        perSubTimelines[index] = events
                        if (eosed && perSubEosed[index].compareAndSet(false, true)) {
                            eosedCount.incrementAndGet()
                            if (sr.tier == SubTier.FAST) fastEosedCount.incrementAndGet()
                        }
                        // Merge + emit so cached events render immediately and
                        // pre-EOSE batches stream through. The allEosed flag
                        // tells consumers when the load is final. Intermediate
                        // (non-EOSE) batches are time-gated — see
                        // lastIntermediateMergeAtMs above.
                        val nowMs = System.currentTimeMillis()
                        val shouldMerge = eosed ||
                            nowMs - lastIntermediateMergeAtMs.get() >= INTERMEDIATE_MERGE_MIN_INTERVAL_MS
                        if (shouldMerge) {
                            lastIntermediateMergeAtMs.set(nowMs)
                            val merged = mergeTimelines(perSubTimelines.toList(), sr.filter.limit)
                            val allEosed = eosedCount.get() >= subRequests.size
                            if (merged.isNotEmpty() || allEosed) {
                                try { onEvents(merged, allEosed) } catch (t: Throwable) {
                                    Log.w(TAG, "onEvents threw", t)
                                }
                            }
                            if (allEosed) {
                                if (onHistory != null) {
                                    // A busy relay's newest page can crowd a quiet
                                    // relay out of the first-display merge. Those
                                    // rows are still part of the covered history.
                                    val displayedIds = merged.mapTo(HashSet()) { it.id }
                                    val overflow = mergeTimelines(perSubTimelines.toList(), null)
                                        .filter { it.id !in displayedIds }
                                    if (overflow.isNotEmpty()) onHistory(overflow)
                                }
                                val floor = (perSubTimelines.mapNotNull { it.lastOrNull()?.createdAt } +
                                    listOfNotNull(seedHead)).minOrNull()
                                catchUps.forEach { it.finish(floor) }
                            }
                        }
                    },
                    onReplayCycleStarted = {
                        if (perSubEosed[index].compareAndSet(true, false)) {
                            eosedCount.decrementAndGet()
                            if (sr.tier == SubTier.FAST) fastEosedCount.decrementAndGet()
                        }
                    },
                    onNew = { evt ->
                        if (newEventIdSet.add(evt.id)) {
                            try { onNew(evt) } catch (t: Throwable) {
                                Log.w(TAG, "onNew threw", t)
                            }
                        }
                    },
                    onClose = onClose,
                    needSort = needSort,
                    outerHandleCollector = multiHandles,
                    catchUp = catchUps[index],
                )
            }
        }

        for (index in fastIndices) launchSingle(index)

        if (slowIndices.isNotEmpty()) {
            subScope.launch {
                if (fastIndices.isEmpty()) {
                    // No FAST tier to gate on — fall back to a fixed delay so
                    // we never block the slow tier indefinitely.
                    delay(SLOW_TIER_WATCHDOG_MS)
                } else {
                    val deadline = System.currentTimeMillis() + SLOW_TIER_WATCHDOG_MS
                    while (fastEosedCount.get() < fastIndices.size &&
                        System.currentTimeMillis() < deadline) {
                        delay(SLOW_TIER_POLL_MS)
                    }
                }
                Log.d(TAG, "lazy outbox: launching ${slowIndices.size} SLOW-tier sub(s) " +
                    "(fastEosed=${fastEosedCount.get()}/${fastIndices.size})")
                for (index in slowIndices) launchSingle(index)
            }
        }

        // perSubKeys grows as subs complete — store the live reference.
        // loadMoreTimeline reads it later when the user scrolls down.
        multiKeys[multiKey] = perSubKeys

        return object : TimelineHandle {
            override val timelineKey = multiKey
            @Volatile private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                subScope.cancel()
                catchUps.forEach { sessions.remove(it.key, it) }
                // Snapshot before iterating — subScope coroutines may still be
                // adding to the synchronizedList concurrently.
                val snapshot = synchronized(multiHandles) { ArrayList(multiHandles) }
                snapshot.forEach { runCatching { it.close() } }
                multiKeys.remove(multiKey)
            }
        }
    }

    private suspend fun subscribeSingle(
        index: Int,
        subRequest: SubRequest,
        onPerSubEvents: (events: List<NostrEvent>, eosed: Boolean) -> Unit,
        onReplayCycleStarted: () -> Unit,
        onNew: (NostrEvent) -> Unit,
        onClose: ((String, String) -> Unit)?,
        needSort: Boolean,
        outerHandleCollector: MutableList<Subscription.Handle>,
        catchUp: CatchUpSession,
    ) {
        val key = generateTimelineKey(subRequest.urls, subRequest.filter)

        // ── Cache lookup. Jumble client.service.ts:600-610 ──────────────────
        val cached: Timeline? = if (needSort) timelines[key] else null
        var since: Long? = null
        var cachedEvents: List<NostrEvent> = emptyList()
        if (cached != null && cached.refs.isNotEmpty()) {
            val cachedRefs = cached.refs.distinctBy { it.id }
            val resolution = eventLoader.getEvents(cachedRefs.map { it.id })
            logPayloadMismatchOnce(key, cachedRefs.size, resolution)
            cachedEvents = contiguousResolvedEvents(cachedRefs, resolution)
            if (cachedEvents.isNotEmpty()) {
                // Cap initial emit to viewport-relevant size. The remainder
                // is still in cached.refs and will be retrieved by
                // loadMoreTimeline as the user scrolls past the initial batch.
                val initialEmit = if (cachedEvents.size > INITIAL_CACHE_EMIT_CAP) {
                    cachedEvents.take(INITIAL_CACHE_EMIT_CAP)
                } else {
                    cachedEvents
                }
                onPerSubEvents(initialEmit, false)
                Log.d(TAG, "cache emit capped: ${initialEmit.size}/${cachedEvents.size} for $key")
            }
        }
        // A row at the cache head proves only that this row arrived. Each relay
        // must have a certified boundary before a delta query can skip history.
        val heads = cached?.coverage?.values?.mapNotNull { it.head }.orEmpty()
        if (heads.size == subRequest.urls.size && heads.isNotEmpty()) {
            since = heads.minOrNull()?.coerceAtMost(System.currentTimeMillis() / 1000L)
        }

        // ── Per-sub state. All mutations under stateLock. ──────────────────
        val stateLock = Any()
        val events = mutableListOf<NostrEvent>()
        var eosedAt: Long? = null
        var eosed = false

        val effectiveFilter = if (since != null) subRequest.filter.copy(since = since) else subRequest.filter
        Log.d(TAG, "subscribeSingle[$index]: key=${key.take(16)} urls=${subRequest.urls.size} tier=${subRequest.tier} since=$since cachedEvents=${cachedEvents.size}")

        val handle = subscription.subscribe(
            urls = subRequest.urls,
            filter = effectiveFilter,
            onevent = { evt ->
                if (sessions[key] !== catchUp) return@subscribe
                synchronized(stateLock) {
                    if (eosedAt == null) {
                        // Pre-EOSE: buffer
                        events.add(evt)
                    } else {
                        // Post-EOSE: live tail
                        val cutoff = eosedAt!!
                        if (evt.createdAt > cutoff) {
                            onNew(evt)
                            insertIntoTimelineRefs(key, evt)
                        }
                    }
                }
            },
            onReplayCycleStarted = { urls ->
                synchronized(stateLock) {
                    events.clear()
                    eosed = false
                    eosedAt = null
                }
                catchUp.replay(urls)
                onReplayCycleStarted()
            },
            onRelayCompletion = catchUp::completion,
            oneose = { allRelaysEosed ->
                if (sessions[key] !== catchUp) return@subscribe
                val toEmit: List<NostrEvent>
                synchronized(stateLock) {
                    if (allRelaysEosed && !eosed) {
                        eosed = true
                        if (eosedAt == null) {
                            eosedAt = System.currentTimeMillis() / 1000L
                        }
                    }
                }

                // Jumble client.service.ts:661-666
                if (!needSort) {
                    toEmit = synchronized(stateLock) { events.toList() }
                } else {
                    val snapshot = synchronized(stateLock) { events.toList() }
                    val sorted = snapshot.sortedWith(compareEventsDesc)
                    val limit = effectiveFilter.limit
                    val sliced = if (limit != null && sorted.size > limit) sorted.take(limit) else sorted
                    // Jumble line 666: events.concat(cachedEvents).slice(0, filter.limit)
                    // Append cached events not present in the new batch (older history)
                    val combined = TimelineMerge.merge(sliced, cachedEvents, capTail = false)
                    toEmit = if (limit != null && combined.size > limit) combined.take(limit) else combined
                }
                onPerSubEvents(toEmit, allRelaysEosed)

                // ── On full EOSE: merge into persistent timeline cache ─────
                // Jumble client.service.ts:675-703
                if (allRelaysEosed) {
                    val snapshot = synchronized(stateLock) { events.toList() }
                    val sortedFinal = if (needSort) snapshot.sortedWith(compareEventsDesc) else snapshot
                    val limit = effectiveFilter.limit
                    val newRefs = if (limit != null && sortedFinal.size > limit) {
                        sortedFinal.take(limit).map { TimelineRef(it.id, it.createdAt) }
                    } else {
                        sortedFinal.map { TimelineRef(it.id, it.createdAt) }
                    }
                    synchronized(timelineLock) {
                        if (sessions[key] === catchUp) {
                            val existing = timelines[key] ?: Timeline(emptyList(), subRequest.filter, subRequest.urls)
                            putTimeline(key, existing.copy(refs = mergeRefs(existing.refs, newRefs)))
                        }
                    }
                }
            },
            onclose = onClose ?: { _, _ -> },
            replayFilterProvider = replay@{ url ->
                val original = subRequest.filter
                if (sessions[key] !== catchUp) return@replay original
                // ConcurrentHashMap publishes immutable Timeline/coverage snapshots.
                // Read one snapshot without taking the EOSE writer's locks or loading events.
                val head = timelines[key]?.coverage?.get(url)?.head
                if (head == null || sessions[key] !== catchUp) return@replay original
                val confirmed = head.coerceAtMost(System.currentTimeMillis() / 1000L)
                original.copy(since = maxOf(original.since ?: confirmed, confirmed))
            },
        )

        catchUp.scope.coroutineContext[Job]?.invokeOnCompletion { handle.close() }
        outerHandleCollector.add(handle)
    }

    /**
     * Insert a live-tail event into the timeline refs at the correct sorted
     * position. Mirrors Jumble client.service.ts:639-654.
     */
    private fun insertIntoTimelineRefs(key: String, evt: NostrEvent) {
        synchronized(timelineLock) {
            val timeline = timelines[key] ?: return
            val refs = timeline.refs
            if (refs.isEmpty()) return

            var idx = 0
            while (idx < refs.size) {
                val ref = refs[idx]
                if (evt.createdAt > ref.createdAt) break
                if (evt.createdAt == ref.createdAt) {
                    if (evt.id == ref.id) return  // already in cache
                    if (evt.id < ref.id) break
                }
                idx++
            }
            if (idx >= refs.size) return  // older than tail — drop

            val newRefs = refs.toMutableList()
            newRefs.add(idx, TimelineRef(evt.id, evt.createdAt))
            putTimeline(key, timeline.copy(refs = newRefs))
        }
    }

    /**
     * Load older events from the timeline cache. Mirrors Jumble's
     * _loadMoreTimeline (client.service.ts:718).
     */
    suspend fun loadMoreTimeline(
        timelineKey: String,
        until: Long,
        limit: Int,
    ): List<NostrEvent> = loadCachedTimelinePage(timelineKey, until, limit).contiguousEvents

    private suspend fun loadCachedTimelinePage(
        timelineKey: String,
        until: Long,
        limit: Int,
    ): CachedTimelinePage {
        if (limit <= 0) {
            return CachedTimelinePage(
                candidates = emptyList(),
                resolution = TimelineEventResolution(emptyList(), emptyList()),
                contiguousEvents = emptyList(),
            )
        }
        val keys = snapshotTimelineKeys(timelineKey)
        val gathered = LinkedHashMap<String, Pair<TimelineRef, LinkedHashSet<String>>>()
        for (k in keys) {
            val tl = timelines[k] ?: continue
            for (ref in tl.refs) {
                if (ref.createdAt >= until) continue
                val candidate = gathered.getOrPut(ref.id) { ref to linkedSetOf() }
                candidate.second.addAll(tl.urls)
            }
        }
        val candidates = gathered.values
            .sortedWith { a, b -> compareTimelineRefsDesc.compare(a.first, b.first) }
            .take(limit)
            .map { (ref, hints) -> CachedRefCandidate(ref, hints.toList()) }
        val resolution = eventLoader.getEvents(candidates.map { it.ref.id })
        logPayloadMismatchOnce(timelineKey, candidates.size, resolution)
        return CachedTimelinePage(
            candidates = candidates,
            resolution = resolution,
            contiguousEvents = contiguousResolvedEvents(candidates.map { it.ref }, resolution),
        )
    }

    private fun contiguousResolvedEvents(
        refs: List<TimelineRef>,
        resolution: TimelineEventResolution,
    ): List<NostrEvent> {
        val eventsById = resolution.events.associateBy { it.id }
        val contiguous = ArrayList<NostrEvent>(refs.size)
        for (ref in refs) {
            val event = eventsById[ref.id] ?: break
            contiguous += event
        }
        return contiguous
    }

    private fun logPayloadMismatchOnce(
        timelineKey: String,
        requestedCount: Int,
        resolution: TimelineEventResolution,
    ) {
        if (resolution.missingIds.isEmpty() || !payloadMismatchLoggedKeys.add(timelineKey)) return
        Log.w(
            TAG,
            "timeline payload miss key=${timelineKey.take(12)} requested=$requestedCount " +
                "resolved=${resolution.events.size} missing=${resolution.missingIds.size}",
        )
    }

    private suspend fun repairMissingTimelineRefs(page: CachedTimelinePage) {
        if (page.resolution.missingIds.isEmpty()) return
        val missing = page.resolution.missingIds.toHashSet()
        val groups = linkedMapOf<List<String>, MutableList<String>>()
        var repairIdCount = 0
        for (candidate in page.candidates) {
            if (candidate.ref.id !in missing) continue
            val hints = candidate.relayHints.distinct()
            groups.getOrPut(hints) { mutableListOf() }.add(candidate.ref.id)
            repairIdCount++
            if (repairIdCount >= TIMELINE_ID_REPAIR_CAP) break
        }
        var requestCount = 0
        for ((hints, ids) in groups) {
            var unresolved = ids.toList()
            val hintBatches = if (hints.isEmpty()) listOf(emptyList())
                else hints.chunked(MAX_SEEN_RELAY_HINTS)
            for (hintBatch in hintBatches) {
                if (requestCount >= TIMELINE_ID_REPAIR_REQUEST_CAP) return
                unresolved = eventLoader.repairEvents(unresolved, hintBatch).missingIds
                requestCount++
                if (unresolved.isEmpty()) break
            }
        }
    }

    /**
     * Fetch older events from relays when the local cache is exhausted.
     * Sends a relay REQ with `until` to paginate backwards, waits for EOSE,
     * merges results into the timeline cache, and returns the events.
     */
    suspend fun fetchOlderTimeline(
        timelineKey: String,
        until: Long,
        limit: Int,
    ): List<NostrEvent> {
        val epoch = storeEpoch.get()
        val keys = snapshotTimelineKeys(timelineKey)
        val pending = keys.filter { key -> timelines[key]?.coverage?.values?.any { it.pending } == true }
        if (pending.isNotEmpty()) {
            // Schedule one gap page per gesture, independent of the automatic
            // budget and the older-page result. Neither slow nor successful gap
            // work may consume the gesture instead of extending the bottom.
            // Rotate keys so an unavailable first relay cannot starve the rest.
            val key = pending[(manualCatchUpCursor.getAndIncrement() % pending.size).toInt()]
            sessions[key]?.scheduleManualPage()
        }
        // Resolve the known ref page before guessing a time window. Return only
        // the contiguous prefix so the next cursor cannot advance across a hole.
        var cachedPage = loadCachedTimelinePage(timelineKey, until, limit)
        if (cachedPage.resolution.missingIds.isNotEmpty()) {
            repairMissingTimelineRefs(cachedPage)
            cachedPage = loadCachedTimelinePage(timelineKey, until, limit)
        }
        val cached = cachedPage.contiguousEvents
        if (storeEpoch.get() != epoch) return emptyList()
        if (cached.isNotEmpty()) return cached

        // No usable cached prefix (no refs, or targeted repair failed): preserve
        // the existing time-window fallback so offline/quirky relays cannot strand
        // pagination permanently.
        // Keys sharing the same filter are coalesced into ONE REQ across the
        // union of their relay groups (first-seen order, deduped) — the old
        // per-key loop sent duplicate until-paginated REQs to overlapping
        // relay groups and blocked up to 10s per group sequentially. Keys
        // with distinct filters (outbox per-relay author subsets) cannot
        // share a REQ without leaking authors to the wrong relays, so they
        // stay separate.
        val allFetched = mutableListOf<NostrEvent>()
        val keyedTimelines = keys.mapNotNull { k -> timelines[k]?.let { k to it } }
        for ((filter, members) in keyedTimelines.groupBy { it.second.filter }) {
            val unionUrls = members.flatMap { it.second.urls }.distinct().filter(subscription::canRequestHistory)
            if (unionUrls.isEmpty()) continue
            val paginationFilter = filter.copy(
                until = until,
                since = null,
                limit = limit,
            )
            val events = Collections.synchronizedList(mutableListOf<NostrEvent>())
            val eoseSignal = CompletableDeferred<Unit>()

            val handle = subscription.subscribe(
                urls = unionUrls,
                filter = paginationFilter,
                onevent = { evt -> events.add(evt) },
                oneose = { allEosed -> if (allEosed) eoseSignal.complete(Unit) },
            )
            try {
                withTimeoutOrNull(10_000) { eoseSignal.await() }
            } finally {
                handle.close()
            }
            if (storeEpoch.get() != epoch) return emptyList()

            // The relay's until is inclusive, while this API's cursor is
            // exclusive (as in the cached path). Never return the boundary row
            // or unrelated newer events, even if a relay ignores the filter.
            val sorted = synchronized(events) { TimelineMerge.sort(events.filter { it.createdAt < until }) }
            allFetched.addAll(sorted)

            // Merge into timeline cache (append older refs). The union fetch
            // is attributed to every key in the group — same filter, superset
            // of each key's urls, so each key's cache gets at least what its
            // own REQ would have returned.
            for ((k, _) in members) {
                val newRefs = sorted.map { TimelineRef(it.id, it.createdAt) }
                synchronized(timelineLock) {
                    val existing = timelines[k]
                    if (existing != null) putTimeline(k, existing.copy(refs = mergeRefs(existing.refs, newRefs)))
                }
            }
        }

        // Combine cached + relay-fetched, dedup, sort DESC
        val combined = TimelineMerge.merge(cached, TimelineMerge.sort(allFetched), capTail = false).take(limit)
        return combined
    }

    // ── Snapshot persistence ──────────────────────────────────────────────

    /**
     * Snapshot writer entry. Persist only the contiguous newest ref prefix
     * whose payloads are present in the event selection written beside it.
     * This keeps every new snapshot internally resolvable; legacy snapshots
     * remain protected by the read-time repair path.
     */
    internal fun snapshotData(selectedContentEventIds: Set<String>): Map<String, Timeline> {
        val result = HashMap<String, Timeline>(timelines.size)
        for ((key, tl) in timelines) {
            val cappedSize = minOf(tl.refs.size, PERSISTED_REFS_CAP)
            var coveredSize = 0
            while (
                coveredSize < cappedSize &&
                tl.refs[coveredSize].id in selectedContentEventIds
            ) {
                coveredSize++
            }
            val refs = if (coveredSize == tl.refs.size) {
                tl.refs
            } else {
                tl.refs.subList(0, coveredSize).toList()
            }
            // Snapshot payload selection can truncate even a completed range.
            // A truncated prefix also loses the history below its last row.
            // Treat that watermark as unknown rather than certifying omitted
            // payloads. Unknown-head catch-up remains bounded at the newest
            // seed/cache row; it does not reconstruct arbitrary older holes.
            val head = if (refs.size == tl.refs.size) refs.firstOrNull()?.createdAt else null
            val coverage = tl.coverage.mapValues { (_, value) ->
                value.copy(head = if (head == null) null else value.head?.coerceAtMost(head))
            }
            result[key] = tl.copy(refs = refs, coverage = coverage)
        }
        return result
    }

    /**
     * Point-in-time union of refs whose payloads back the live timeline cache.
     *
     * [Timeline.refs] is immutable and replaced copy-on-write, so copying the
     * timeline values first gives eviction a stable view without taking a lock
     * on the subscription hot path. Protection is deliberately bounded to the
     * same newest [PERSISTED_REFS_CAP] entries that a timeline may persist;
     * deeper history remains relay-reconstructible and must not become an
     * unbounded memory anchor.
     */
    internal fun liveReferencedIds(): Set<String> {
        return synchronized(liveReferenceLock) { liveReferenceCounts.keys.toSet() }
    }

    internal fun isLiveReferenced(eventId: String): Boolean =
        synchronized(liveReferenceLock) { eventId in liveReferenceCounts }

    /**
     * Snapshot reader entry. Validates each entry's key by recomputing
     * from urls+filter; mismatches (schema drift) are skipped.
     */
    internal fun restoreFromSnapshot(entries: Map<String, Timeline>) {
        for ((key, timeline) in entries) {
            val recomputed = generateTimelineKey(timeline.urls, timeline.filter)
            val legacyKey = sha256(timeline.urls.sorted().joinToString(",") + "|" + timeline.filter.toJsonObject()).take(16)
            if (recomputed != key && legacyKey != key) {
                Log.w(TAG, "timeline key mismatch persisted=${key.take(8)} recomputed=${recomputed.take(8)} — skipping")
                continue
            }
            val urls = timeline.urls.mapNotNull(::normalizeRelayUrl).distinct()
            val coverage = if (timeline.coverage.keys.all { it in urls }) timeline.coverage else emptyMap()
            putTimeline(recomputed, timeline.copy(urls = urls, coverage = coverage))
        }
        Log.d(TAG, "restored ${timelines.size} timelines from snapshot")
    }

    fun clear() {
        storeEpoch.incrementAndGet()
        sessions.values.map { it.scope }.distinct().forEach { it.cancel() }
        synchronized(timelineLock) {
            sessions.clear()
            synchronized(liveReferenceLock) {
                timelines.clear()
                liveReferenceIdsByTimeline.clear()
                liveReferenceCounts.clear()
            }
        }
        synchronized(catchUpBudgets) { catchUpBudgets.clear() }
        multiKeys.clear()
        payloadMismatchLoggedKeys.clear()
    }

    // ── Test helpers ────────────────────────────────────────────────────────
    internal fun resetForTest() {
        clear()
    }

    internal fun timelineForTest(key: String): List<TimelineRef>? = timelines[key]?.refs
    internal fun timelineStateForTest(key: String): Timeline? = timelines[key]

    // ── Internals ──────────────────────────────────────────────────────────
    private fun generateMultiKey(subRequests: List<SubRequest>): String {
        val parts = subRequests.flatMap { it.urls + it.filter.toJsonObject().toString() }.sorted()
        return sha256(parts.joinToString("|")).take(16) + "-multi"
    }

    internal fun generateTimelineKey(urls: List<String>, filter: NostrFilter): String {
        val payload = urls.mapNotNull(::normalizeRelayUrl).distinct().sorted().joinToString(",") + "|" + filter.toJsonObject().toString()
        return sha256(payload).take(16)
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Sort key for Nostr events. DESC by createdAt; ties broken by
         * lexically-smaller id wins (matches NIP-01 replaceable retention
         * and Jumble's compareEvents in lib/event.ts:439).
         */
        val compareEventsDesc: Comparator<NostrEvent> = Comparator { a, b ->
            when {
                a.createdAt != b.createdAt -> b.createdAt.compareTo(a.createdAt)
                a.id != b.id -> a.id.compareTo(b.id)
                else -> 0
            }
        }

        val compareTimelineRefsDesc: Comparator<TimelineRef> = Comparator { a, b ->
            when {
                a.createdAt != b.createdAt -> b.createdAt.compareTo(a.createdAt)
                a.id != b.id -> a.id.compareTo(b.id)
                else -> 0
            }
        }
    }
}

/**
 * K-way merge of multiple per-relay timelines. Mirrors Jumble's
 * mergeTimelines in lib/timeline.ts. Each input is expected to be already
 * sorted desc by [TimelineService.compareEventsDesc]. Output is sorted
 * desc, dedup'd by id, sliced to [limit].
 */
fun mergeTimelines(timelines: List<List<NostrEvent>>, limit: Int? = null): List<NostrEvent> {
    if (timelines.isEmpty()) return emptyList()
    if (timelines.size == 1) return timelines[0]
    return timelines.fold(emptyList()) { acc, current ->
        mergeTwo(acc, current, limit)
    }
}

private fun mergeTwo(a: List<NostrEvent>, b: List<NostrEvent>, limit: Int?): List<NostrEvent> {
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    val out = ArrayList<NostrEvent>(minOf(a.size + b.size, limit ?: Int.MAX_VALUE))
    var i = 0
    var j = 0
    val seen = HashSet<String>(a.size + b.size)
    while (i < a.size && j < b.size) {
        val cmp = TimelineService.compareEventsDesc.compare(a[i], b[j])
        when {
            cmp < 0 -> { if (seen.add(a[i].id)) out.add(a[i]); i++ }
            cmp > 0 -> { if (seen.add(b[j].id)) out.add(b[j]); j++ }
            else -> { if (seen.add(a[i].id)) out.add(a[i]); i++; j++ }
        }
        if (limit != null && out.size >= limit) return out
    }
    while (i < a.size && (limit == null || out.size < limit)) {
        if (seen.add(a[i].id)) out.add(a[i]); i++
    }
    while (j < b.size && (limit == null || out.size < limit)) {
        if (seen.add(b[j].id)) out.add(b[j]); j++
    }
    return out
}
