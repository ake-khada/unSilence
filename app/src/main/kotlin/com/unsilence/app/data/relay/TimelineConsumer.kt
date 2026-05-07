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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
     * Profile reactivity is handled per-card: EventCard observes
     * profileFlow(pubkey) directly, so a profile arriving for author X
     * only re-composes cards by X — no list-wide pass.
     *
     * Stats signals (kind-7/9735) still bump global `_statsSignal` and
     * stale FeedRow stats fields; sampled at 250 ms so an engagement
     * flood doesn't drive list rebuilds. statsSignal-driven recomputes
     * are now purely a fallback while we don't have per-event statsFlow.
     *
     * _events and _contentFilter changes fire immediately — new posts
     * tail in without waiting for the sample window.
     *
     * matchesContentFilter is applied at the render boundary so that
     * Notes/Conversations tab switching doesn't require resubscribing.
     */
    @OptIn(FlowPreview::class)
    val feedRows: StateFlow<List<FeedRow>> = run {
        val eventsTrigger = combine(_events, _contentFilter) { _, _ -> Unit }
        // Stats signal: low-frequency relative to profiles. Sampled so a
        // burst of kind-7 reactions doesn't trigger N rebuilds.
        val statsTrigger = memoryEventStore.statsSignalFlow.sample(250)
        merge(eventsTrigger, statsTrigger.map { Unit })
            .map {
                val events = _events.value
                val cf = _contentFilter.value
                if (events.isEmpty()) return@map emptyList()
                val filtered = events.filter { matchesContentFilter(it, cf) }
                if (filtered.isEmpty()) return@map emptyList()
                val ids = filtered.map { it.id }
                val rowsById = memoryEventStore.feedRowsByIds(ids.toSet()).associateBy { it.id }
                ids.mapNotNull { rowsById[it] }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(ownerScope, SharingStarted.Eagerly, emptyList())
    }

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
            while (true) {
                // Suspend until at least one batch arrives. CancellationException
                // exits the loop when ownerScope is cancelled.
                val first = arrivalQueue.receive()
                val buffer = ArrayList<NostrEvent>(first.size + 32)
                buffer.addAll(first)
                // Drain anything that arrives in the next ARRIVAL_WINDOW_MS.
                // withTimeoutOrNull cancels the inner receive when the window
                // closes; the outer receive blocks until next batch.
                withTimeoutOrNull(ARRIVAL_WINDOW_MS) {
                    while (true) {
                        val next = arrivalQueue.receive()
                        buffer.addAll(next)
                    }
                }
                applyArrival(buffer)
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
        /** Coalescing window for the arrival queue drainer (ms). 100ms is
         *  imperceptible as feed-tail latency but enough to absorb a Ditto
         *  burst of ~10 batches/sec into a single Compose recomposition. */
        const val ARRIVAL_WINDOW_MS = 100L

        val EVENT_ORDER: Comparator<NostrEvent> = Comparator { a, b ->
            when {
                a.createdAt != b.createdAt -> b.createdAt.compareTo(a.createdAt)
                a.id != b.id -> a.id.compareTo(b.id)
                else -> 0
            }
        }
    }
}
