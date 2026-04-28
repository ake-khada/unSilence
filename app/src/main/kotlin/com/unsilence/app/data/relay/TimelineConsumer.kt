package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.ui.feed.FeedContentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
     * UI-bound feed rows. Derived from _events × _contentFilter × MES signal
     * flows. matchesContentFilter is applied at the render boundary so that
     * Notes/Conversations tab switching doesn't require resubscribing.
     */
    val feedRows: StateFlow<List<FeedRow>> = combine(
        _events,
        _contentFilter,
        memoryEventStore.profileSignalFlow,
        memoryEventStore.actionSignalFlow,
        memoryEventStore.statsSignalFlow,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val events = args[0] as List<NostrEvent>
        val cf = args[1] as FeedContentFilter
        if (events.isEmpty()) return@combine emptyList()
        val filtered = events.filter { matchesContentFilter(it, cf) }
        if (filtered.isEmpty()) return@combine emptyList()
        val ids = filtered.map { it.id }
        val rowsById = memoryEventStore.feedRowsByIds(ids.toSet()).associateBy { it.id }
        ids.mapNotNull { rowsById[it] }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(ownerScope, SharingStarted.Eagerly, emptyList())

    // ── Subscription handle ───────────────────────────────────────────────

    private var currentHandle: TimelineService.TimelineHandle? = null
    private var subscribeJob: Job? = null
    private var currentSubRequests: List<SubRequest> = emptyList()
    private var sinceCursor: Long = 0L

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
        _events.update { current ->
            (events + current).distinctBy { it.id }.sortedWith(EVENT_ORDER)
        }
        _isLoading.value = false
    }

    private fun handleNewEvents(newEvents: List<NostrEvent>) {
        if (newEvents.isEmpty()) return
        if (_isAtTop.value) {
            _events.update { current ->
                (newEvents + current).distinctBy { it.id }.sortedWith(EVENT_ORDER)
            }
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
        _events.update { current ->
            (pending + current).distinctBy { it.id }.sortedWith(EVENT_ORDER)
        }
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
                    _events.update { current ->
                        (current + older).distinctBy { it.id }.sortedWith(EVENT_ORDER)
                    }
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

    private companion object {
        val EVENT_ORDER: Comparator<NostrEvent> = Comparator { a, b ->
            when {
                a.createdAt != b.createdAt -> b.createdAt.compareTo(a.createdAt)
                a.id != b.id -> a.id.compareTo(b.id)
                else -> 0
            }
        }
    }
}
