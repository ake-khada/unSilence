package com.unsilence.app.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.OutboxRelayResolver
import com.unsilence.app.data.relay.SubRequest
import com.unsilence.app.data.relay.TimelineService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * New feed-display ViewModel. Uses TimelineService for relay subs (with
 * cache + threshold merge + post-EOSE live tail), InitGate to wait for
 * bootstrap completion, and OutboxRelayResolver for SubRequest grouping.
 *
 * Lives alongside the legacy FeedViewModel during the rewrite. P8 swaps
 * FeedScreen to consume this VM. P9 deletes FeedWindow / FeedWindowLoader /
 * FeedStateReducer / WindowKey / FeedWindowConfig.
 *
 * State flow:
 *   1. init → wait for InitGate.awaitReady()
 *   2. _feedType.collect → resubscribe via TimelineService.subscribeTimeline
 *   3. onEvents → _events.value = sorted desc
 *   4. onNew → if at top, merge into _events; else buffer in _pendingNew
 *   5. feedRows derived from _events × MES signal flows (profile + action + stats)
 *
 * Live engagement updates: every time MES bumps profileSignal / actionSignal /
 * statsSignal, feedRows re-emits with fresh FeedRow.feedRowsByIds() data.
 */
@HiltViewModel
class FeedViewModelV2 @Inject constructor(
    private val initGate: InitGate,
    private val timelineService: TimelineService,
    private val outboxResolver: OutboxRelayResolver,
    private val memoryEventStore: MemoryEventStore,
    private val keyManager: KeyManager,
) : ViewModel() {

    // ── Feed type ─────────────────────────────────────────────────────────

    private val _feedType = MutableStateFlow<FeedType>(FeedType.Following)
    val feedType: StateFlow<FeedType> = _feedType.asStateFlow()

    // ── Event buffer (source of truth) ────────────────────────────────────

    private val _events = MutableStateFlow<List<NostrEvent>>(emptyList())

    /** Live-tail events arrived while user scrolled down; flushed on tap-dot or back-to-top. */
    private val _pendingNew = MutableStateFlow<List<NostrEvent>>(emptyList())
    val pendingCount: StateFlow<Int> = _pendingNew
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val showDot: StateFlow<Boolean> = _pendingNew
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isAtTop = MutableStateFlow(true)

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    // ── Derived FeedRows: events × MES signals ────────────────────────────

    /**
     * UI-bound feed rows. Recomputes when:
     *   - _events changes (new batch from EOSE / live tail / loadMore)
     *   - profileSignal bumps (new kind-0 metadata)
     *   - actionSignal bumps (own reactions / reposts changed)
     *   - statsSignal bumps (engagement counts changed)
     *
     * feedRowsByIds returns set-ordered; we re-order to match _events
     * (which is already sorted desc by createdAt then id).
     */
    val feedRows: StateFlow<List<FeedRow>> = combine(
        _events,
        memoryEventStore.profileSignalFlow,
        memoryEventStore.actionSignalFlow,
        memoryEventStore.statsSignalFlow,
    ) { events, _, _, _ ->
        if (events.isEmpty()) return@combine emptyList()
        val ids = events.map { it.id }
        val rowsById = memoryEventStore.feedRowsByIds(ids.toSet()).associateBy { it.id }
        ids.mapNotNull { rowsById[it] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    // ── Subscription handle ───────────────────────────────────────────────

    private var currentHandle: TimelineService.TimelineHandle? = null

    init {
        viewModelScope.launch {
            // Wait for kind-3 + kind-10002 ready before subscribing.
            initGate.awaitReady()

            // Re-subscribe whenever feed type changes.
            _feedType.collect { type ->
                resubscribe(type)
            }
        }
    }

    private suspend fun resubscribe(type: FeedType) {
        currentHandle?.close()
        _events.value = emptyList()
        _pendingNew.value = emptyList()
        _isLoading.value = true

        val subRequests = buildSubRequests(type)
        if (subRequests.isEmpty()) {
            _isLoading.value = false
            return
        }

        currentHandle = timelineService.subscribeTimeline(
            subRequests = subRequests,
            onEvents = { events, eosed ->
                _events.value = events.distinctBy { it.id }
                if (eosed) _isLoading.value = false
            },
            onNew = { event ->
                if (_isAtTop.value) {
                    _events.update { current ->
                        (listOf(event) + current)
                            .distinctBy { it.id }
                            .sortedWith(EVENT_ORDER)
                    }
                } else {
                    _pendingNew.update { it + event }
                }
            },
        )
    }

    private fun buildSubRequests(type: FeedType): List<SubRequest> {
        val ownPubkey = keyManager.getPublicKeyHex()
        val blockedRelays = ownPubkey
            ?.let { memoryEventStore.getBlockedRelayUrls(it).toSet() }
            ?: emptySet()
        val readRelays = ownPubkey
            ?.let { memoryEventStore.getReadWriteRelayConfigs(it).map { c -> c.url } }
            ?: emptyList()

        val config = OutboxRelayResolver.Config(
            kinds = listOf(1, 6, 20, 21, 30023),
            limit = 300,
        )

        return when (type) {
            is FeedType.Following -> {
                val follows = ownPubkey
                    ?.let { memoryEventStore.getFollows(it) }
                    ?: emptySet()
                if (follows.isEmpty()) return emptyList()
                outboxResolver.resolveFollowing(
                    authors = follows,
                    fallbackRelays = readRelays.ifEmpty { GLOBAL_RELAY_URLS },
                    blockedRelays = blockedRelays,
                    config = config,
                )
            }
            is FeedType.Global -> outboxResolver.resolveGlobal(
                readRelays = readRelays,
                fallbackRelays = GLOBAL_RELAY_URLS,
                blockedRelays = blockedRelays,
                config = config,
            )
            is FeedType.SingleRelay -> outboxResolver.resolveSingleRelay(
                url = type.url,
                config = config,
            )
            is FeedType.RelaySet -> emptyList()  // P8 handles relay set feeds
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun setFeedType(type: FeedType) {
        if (_feedType.value == type) return
        _feedType.value = type
    }

    /**
     * Called by FeedScreen when scroll position changes. Tracks whether the
     * user is at the top of the feed (index 0 visible). Live-tail events are
     * merged immediately at top; buffered when scrolled down.
     */
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
            (pending + current)
                .distinctBy { it.id }
                .sortedWith(EVENT_ORDER)
        }
        _pendingNew.value = emptyList()
    }

    fun loadMore() {
        if (_isLoadingMore.value) return
        val handle = currentHandle ?: return
        val until = _events.value.lastOrNull()?.createdAt ?: return
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val older = timelineService.loadMoreTimeline(
                    timelineKey = handle.timelineKey,
                    until = until,
                    limit = 100,
                )
                if (older.isNotEmpty()) {
                    _events.update { current ->
                        (current + older)
                            .distinctBy { it.id }
                            .sortedWith(EVENT_ORDER)
                    }
                }
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            resubscribe(_feedType.value)
        }
    }

    override fun onCleared() {
        currentHandle?.close()
        currentHandle = null
        super.onCleared()
    }

    companion object {
        /**
         * Canonical ordering: desc by createdAt, ties broken by lexically
         * smaller id wins (matches NIP-01 retention rule and TimelineService.compareEventsDesc).
         */
        private val EVENT_ORDER: Comparator<NostrEvent> = Comparator { a, b ->
            when {
                a.createdAt != b.createdAt -> b.createdAt.compareTo(a.createdAt)
                a.id != b.id -> a.id.compareTo(b.id)
                else -> 0
            }
        }
    }
}
