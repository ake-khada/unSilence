package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.ui.feed.FeedContentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Generalized timeline state holder, modelled after Jumble's NoteList.
 *
 * Takes [SubRequest]s as input, exposes feedRows + blue-dot + pagination
 * controls. Does NOT decide which subRequests to use — that's the caller's
 * job (FeedViewModel for the home/global feeds, ProfileViewModel for
 * a profile's own posts, etc.).
 *
 * Lifecycle:
 *   1. Caller creates an instance with a CoroutineScope (typically
 *      viewModelScope of the owning ViewModel).
 *   2. Caller calls [subscribe] with fresh subRequests + initial
 *      cached events. Closes any previous subscription.
 *   3. State flows ([feedRows], [pendingCount], [showDot], [isLoading],
 *      [isLoadingMore]) update reactively.
 *   4. Caller forwards UI events: [onViewportChanged], [onDotTapped],
 *      [loadMore], [refresh].
 *   5. On owner cleanup, caller calls [close].
 */
class TimelineConsumer(
    private val timelineService: TimelineService,
    private val memoryEventStore: MemoryEventStore,
    private val ownerScope: CoroutineScope,
    initialContentFilter: FeedContentFilter = FeedContentFilter.NOTES_ONLY,
) {
    // ── Content filter (render-boundary) ──────────────────────────────────

    private val _contentFilter = MutableStateFlow(initialContentFilter)
    fun setContentFilter(f: FeedContentFilter) {
        if (_contentFilter.value == f) return
        _contentFilter.value = f
    }

    // ── Event state ───────────────────────────────────────────────────────

    private val _events = MutableStateFlow<List<NostrEvent>>(emptyList())
    val events: StateFlow<List<NostrEvent>> = _events.asStateFlow()
    private val _pendingNew = MutableStateFlow<List<NostrEvent>>(emptyList())
    private val _isAtTop = MutableStateFlow(true)
    private val _isLoading = MutableStateFlow(true)
    private val _isLoadingMore = MutableStateFlow(false)

    val pendingCount: StateFlow<Int> = _pendingNew
        .map { it.size }
        .stateIn(ownerScope, SharingStarted.Eagerly, 0)
    val showDot: StateFlow<Boolean> = _pendingNew
        .map { it.isNotEmpty() }
        .stateIn(ownerScope, SharingStarted.Eagerly, false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /**
     * UI-bound feed rows. Derived ONLY from _events × _contentFilter.
     *
     * Per-card reactivity replaces every list-level signal trigger:
     *   - Profile updates re-compose only the affected card via
     *     [profileFlow(pubkey)] in [com.unsilence.app.ui.feed.EventCard].
     *   - Engagement counts (replyCount / repostCount / reactionCount /
     *     zapTotalSats) re-compose only the affected card via
     *     [MemoryEventStore.statsFlow(eventId)].
     *
     * matchesContentFilter is applied at the render boundary so that
     * Notes/Conversations tab switching doesn't require resubscribing.
     *
     * Bounded recompute work — `_events` is unbounded by design (loadMore
     * continues to extend it), but feedRows hard-caps the per-emit work
     * at FEED_DISPLAY_CAP rows by `take`-ing from the head (newest first).
     * Field logs showed sustained 500-700ms Main-thread hangs with `_events`
     * sitting at 2600+ entries: filter+map+sortedByDescending+toFeedRow on
     * every relay tick was the dominant Default-pool consumer and the
     * Compose recompose driver. With the cap the per-emit work is constant
     * regardless of how deep the user has paged.
     *
     * Sampled emission window — at 9 events/sec sustained the prior
     * `combine + distinctUntilChanged` was emitting on every relay tick
     * (and `distinctUntilChanged` itself was scanning the whole 2600-element
     * list element-wise to compare against the previous emission). `sample`
     * collapses bursts into one emission per FEED_SAMPLE_MS window — Compose
     * recomposes at most ~10×/sec instead of the full 9×/sec event rate
     * times each upstream emission cycle. New posts still appear within
     * one window (~100 ms), well below the user-perceived limit.
     */
    @OptIn(FlowPreview::class)
    val feedRows: StateFlow<List<FeedRow>> =
        combine(_events, _contentFilter) { events, cf ->
            if (events.isEmpty()) return@combine emptyList()
            // _events is already sorted newest-first by mergeSorted. Take
            // first matches up to the display cap WITHOUT scanning the full
            // tail — `asSequence` lets `take` short-circuit.
            val displayed = events.asSequence()
                .filter { matchesContentFilter(it, cf) }
                .take(FEED_DISPLAY_CAP)
                .toList()
            if (displayed.isEmpty()) return@combine emptyList()
            val ids = displayed.map { it.id }
            val rowsById = memoryEventStore.feedRowsByIds(ids.toSet()).associateBy { it.id }
            ids.mapNotNull { rowsById[it] }
        }
            .sample(FEED_SAMPLE_MS)
            .flowOn(Dispatchers.Default)
            .stateIn(ownerScope, SharingStarted.Eagerly, emptyList())

    // ── Subscription handle ───────────────────────────────────────────────

    private var currentHandle: TimelineService.TimelineHandle? = null
    private var subscribeJob: Job? = null
    private var currentSubRequests: List<SubRequest> = emptyList()
    private var sinceCursor: Long = 0L

    // ── Arrival coalescing ────────────────────────────────────────────────
    //
    // High-volume relays (Ditto, primal cache, etc.) deliver events in
    // many small batches per second. Each batch hitting handleNewEvents
    // synchronously fires _events.update → eventsTrigger → feedRowsByIds
    // → Compose recomposition. Field log on Ditto showed 14 frame drops
    // (max 72 frames / 1200ms) clustered around batch arrivals even with
    // hydration on background threads — Compose on Main couldn't keep up
    // with N _events updates per second.
    //
    // Solution: enqueue arrivals on an unbounded channel, drainer wakes
    // every 100ms and applies ONE coalesced merge. Worst-case tail-in
    // latency is 100ms (imperceptible) and N batches arriving in 100ms
    // produce one Compose recompose instead of N.
    //
    // Channel.UNLIMITED so trySend never drops. Drainer is bound to
    // ownerScope and cancelled in close().

    private val arrivalQueue = Channel<List<NostrEvent>>(Channel.UNLIMITED)
    private val arrivalDrainerJob: Job

    init {
        arrivalDrainerJob = ownerScope.launch {
            // Adaptive coalescing: the drain window starts at ARRIVAL_WINDOW_MIN_MS
            // and stretches toward ARRIVAL_WINDOW_MAX_MS as arrival rate climbs.
            // On Following (steady ~10 events/sec) the window stays near the
            // minimum and posts tail in promptly. On Ditto-class firehoses
            // (~100+ events/sec) it stretches to absorb the burst and produce
            // one Compose recompose per ~500ms instead of per-batch. Each
            // applied batch updates `currentWindowMs` based on what we just saw.
            var currentWindowMs = ARRIVAL_WINDOW_MIN_MS
            while (true) {
                // Suspend until at least one batch arrives. CancellationException
                // exits the loop when ownerScope is cancelled.
                val first = arrivalQueue.receive()
                val buffer = ArrayList<NostrEvent>(first.size + 32)
                buffer.addAll(first)
                val windowStartNs = System.nanoTime()
                // Drain anything that arrives within currentWindowMs.
                withTimeoutOrNull(currentWindowMs) {
                    while (true) {
                        val next = arrivalQueue.receive()
                        buffer.addAll(next)
                    }
                }
                val elapsedMs = ((System.nanoTime() - windowStartNs) / 1_000_000L).coerceAtLeast(1L)
                val rate = (buffer.size * 1000L) / elapsedMs   // events/sec for this window
                applyArrival(buffer)
                // Update window for the NEXT batch based on observed rate.
                // Linear interpolation: ≤ARRIVAL_LOW_RATE → MIN, ≥ARRIVAL_HIGH_RATE → MAX.
                currentWindowMs = when {
                    rate <= ARRIVAL_LOW_RATE -> ARRIVAL_WINDOW_MIN_MS
                    rate >= ARRIVAL_HIGH_RATE -> ARRIVAL_WINDOW_MAX_MS
                    else -> {
                        val span = ARRIVAL_WINDOW_MAX_MS - ARRIVAL_WINDOW_MIN_MS
                        val t = (rate - ARRIVAL_LOW_RATE).toDouble() /
                            (ARRIVAL_HIGH_RATE - ARRIVAL_LOW_RATE)
                        ARRIVAL_WINDOW_MIN_MS + (span * t).toLong()
                    }
                }
            }
        }
    }

    /**
     * Subscribe to a new set of subRequests. Closes previous subscription.
     *
     * [initialCachedEvents] is the snapshot/MES-derived initial fill —
     * shown immediately while the relay subs warm up. Pass an empty list
     * for fresh subscriptions with no cache.
     */
    fun subscribe(
        subRequests: List<SubRequest>,
        initialCachedEvents: List<NostrEvent> = emptyList(),
    ) {
        currentHandle?.close()
        currentHandle = null
        subscribeJob?.cancel()
        subscribeJob = null

        // Drain stale arrivals from the previous subscription. Without this,
        // a queued batch from the closed sub would be applied to the new
        // feed's _events on the next drainer tick.
        while (arrivalQueue.tryReceive().isSuccess) { /* discard */ }

        currentSubRequests = subRequests
        _events.value = initialCachedEvents
        _pendingNew.value = emptyList()
        _isLoading.value = initialCachedEvents.isEmpty()

        if (subRequests.isEmpty()) {
            _isLoading.value = false
            return
        }

        sinceCursor = initialCachedEvents.firstOrNull()?.createdAt?.plus(1)
            ?: (System.currentTimeMillis() / 1000L - 60)

        subscribeJob = ownerScope.launch {
            val handle = timelineService.subscribeTimeline(
                subRequests = subRequests,
                onEvents = { batch, eosed ->
                    if (initialCachedEvents.isEmpty() && _events.value.isEmpty()) {
                        _events.value = batch
                    } else {
                        val newOnes = batch.filter { it.createdAt >= sinceCursor }
                        if (newOnes.isNotEmpty()) handleNewEvents(newOnes)
                    }
                    // Hide loading as soon as we have ANY events — relay batch arrived OR
                    // cache populated. EOSE may take 30s+ on slow outbox relays; user
                    // shouldn't see a spinner over already-populated content.
                    if (_events.value.isNotEmpty()) _isLoading.value = false
                },
                onNew = { event -> handleNewEvents(listOf(event)) },
            )
            currentHandle = handle
        }
    }

    /**
     * Merge cached events (e.g. from snapshot restore) into the current event
     * list without disrupting an active subscription. Unlike [subscribe], this
     * does NOT close the current handle or reset pending state.
     */
    fun addCachedEvents(events: List<NostrEvent>) {
        if (events.isEmpty()) return
        _events.update { current -> mergeSorted(current, events) }
        _isLoading.value = false
    }

    /**
     * Subscription tap entry point. Enqueues the batch on the arrival queue;
     * the drainer coalesces into 100ms windows and calls [applyArrival].
     * Non-suspending — must not block the WebSocket consume thread.
     */
    private fun handleNewEvents(newEvents: List<NostrEvent>) {
        if (newEvents.isEmpty()) return
        arrivalQueue.trySend(newEvents)
    }

    /**
     * Apply one coalesced batch (drainer-only entry point). Reads `_isAtTop`
     * at apply time so events queued while at-top still go to `_events` even
     * if the drainer wake is slightly delayed; events queued while scrolled
     * down go to `_pendingNew`.
     */
    private fun applyArrival(newEvents: List<NostrEvent>) {
        if (newEvents.isEmpty()) return
        if (_isAtTop.value) {
            _events.update { current -> mergeSorted(current, newEvents) }
        } else {
            _pendingNew.update { current ->
                (current + newEvents).distinctBy { it.id }
            }
        }
    }

    fun onViewportChanged(firstVisibleIndex: Int) {
        val atTop = firstVisibleIndex <= 0
        if (_isAtTop.value != atTop) _isAtTop.value = atTop
        if (atTop) flushPending()
    }

    fun onDotTapped() = flushPending()

    private fun flushPending() {
        val pending = _pendingNew.value
        if (pending.isEmpty()) return
        _events.update { current -> mergeSorted(current, pending) }
        _pendingNew.value = emptyList()
    }

    fun loadMore() {
        if (_isLoadingMore.value) return
        val handle = currentHandle ?: return
        val until = _events.value.lastOrNull()?.createdAt ?: return
        ownerScope.launch {
            _isLoadingMore.value = true
            try {
                val older = timelineService.loadMoreTimeline(
                    timelineKey = handle.timelineKey,
                    until = until,
                    limit = 100,
                )
                if (older.isNotEmpty()) {
                    _events.update { current -> mergeSorted(current, older) }
                }
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun refresh(initialCachedEvents: List<NostrEvent> = _events.value) {
        subscribe(currentSubRequests, initialCachedEvents)
    }

    fun close() {
        subscribeJob?.cancel()
        subscribeJob = null
        arrivalDrainerJob.cancel()
        // Drain anything still queued so it's eligible for GC.
        while (arrivalQueue.tryReceive().isSuccess) { /* discard */ }
        currentHandle?.close()
        currentHandle = null
        currentSubRequests = emptyList()
    }

    private fun matchesContentFilter(evt: NostrEvent, cf: FeedContentFilter): Boolean =
        when (cf) {
            FeedContentFilter.NOTES_ONLY ->
                evt.kind == 6 || (evt.replyToId == null && evt.rootId == null)
            FeedContentFilter.REPLIES_ONLY ->
                evt.kind != 6 && (evt.replyToId != null || evt.rootId != null)
        }

    /**
     * Merge new events into a sorted current list via binary insertion.
     *
     * Replaces the previous `(new + current).distinctBy.sortedWith(EVENT_ORDER)`
     * which allocated and sorted the entire list on every batch (O(N log N)).
     * Each batch from a SubRequest hits this — with 13 SubRequests delivering
     * a few batches each, that's 30+ full sorts of a growing list during a
     * single feed warm-up.
     *
     * Binary insertion: O(M log N + M) where M=newEvents, N=current. The
     * O(N) shift is unavoidable when inserting into an ArrayList, but we
     * skip the per-batch quicksort overhead.
     *
     * Assumes `current` is already sorted by EVENT_ORDER. Snapshots and
     * relay batches respect createdAt-desc ordering, so this is true after
     * the initial subscribe() set.
     */
    private fun mergeSorted(current: List<NostrEvent>, newEvents: List<NostrEvent>): List<NostrEvent> {
        if (newEvents.isEmpty()) return current
        if (current.isEmpty()) {
            // First batch — sort once.
            return newEvents.distinctBy { it.id }.sortedWith(EVENT_ORDER)
        }

        // Build id set for dedup (cheaper than distinctBy on the merged list).
        val seen = HashSet<String>(current.size + newEvents.size)
        for (e in current) seen.add(e.id)

        // Filter out events we already have, then sort the new ones.
        val novelSorted = newEvents
            .asSequence()
            .filter { seen.add(it.id) }
            .sortedWith(EVENT_ORDER)
            .toList()
        if (novelSorted.isEmpty()) return current

        // Two-pointer merge — both inputs are sorted by EVENT_ORDER (createdAt DESC).
        val result = ArrayList<NostrEvent>(current.size + novelSorted.size)
        var i = 0
        var j = 0
        while (i < current.size && j < novelSorted.size) {
            if (EVENT_ORDER.compare(current[i], novelSorted[j]) <= 0) {
                result.add(current[i]); i++
            } else {
                result.add(novelSorted[j]); j++
            }
        }
        while (i < current.size) { result.add(current[i]); i++ }
        while (j < novelSorted.size) { result.add(novelSorted[j]); j++ }
        return result
    }

    private companion object {
        /** Hard cap on the number of FeedRows the UI sees at any moment.
         *  `_events` itself is uncapped (loadMore extends it tailward), but
         *  rendering scales with this constant — Compose's LazyColumn keys
         *  diff and the per-emit work in feedRows both bound on it. 500 is
         *  comfortably more than even the deepest practical scroll position
         *  before the user pages further. */
        const val FEED_DISPLAY_CAP = 500

        /** Sampling window (ms) for feedRows emissions. Coalesces relay-event
         *  bursts into one Compose recompose per window. 100 ms is below the
         *  user-perceived "live update" threshold for new posts tailing in. */
        const val FEED_SAMPLE_MS = 100L

        /** Minimum coalescing window (ms). Used at low arrival rates so live-
         *  tail posts appear with imperceptible delay. */
        const val ARRIVAL_WINDOW_MIN_MS = 100L

        /** Maximum coalescing window (ms). Used under firehose load so a
         *  Ditto-class relay can't outrun Compose. The trade-off is up to
         *  500ms tail-in delay; still well below human "feels laggy" threshold
         *  for live updates and the user is by definition NOT at top of a
         *  firehose feed if they're getting hundreds of events/sec. */
        const val ARRIVAL_WINDOW_MAX_MS = 500L

        /** Below this rate (events/sec) keep the minimum window. */
        const val ARRIVAL_LOW_RATE = 20L

        /** At or above this rate, stretch to the maximum window. */
        const val ARRIVAL_HIGH_RATE = 100L

        val EVENT_ORDER: Comparator<NostrEvent> = Comparator { a, b ->
            when {
                a.createdAt != b.createdAt -> b.createdAt.compareTo(a.createdAt)
                a.id != b.id -> a.id.compareTo(b.id)
                else -> 0
            }
        }
    }
}
