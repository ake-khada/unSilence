package com.unsilence.app.ui.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.memory.FeedFilter
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.OutboxRelayResolver
import com.unsilence.app.data.relay.SubRequest
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.relay.normalizeRelayUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val TAG = "FeedVMv2"

/**
 * New feed-display ViewModel. Uses TimelineService for relay subs (with
 * cache + threshold merge + post-EOSE live tail), InitGate to wait for
 * bootstrap completion, and OutboxRelayResolver for SubRequest grouping.
 *
 * State flow:
 *   1. init → wait for InitGate.awaitFeedConnections() (Phase1 complete)
 *   2. _feedType.collectLatest → resubscribe via TimelineService
 *   3. Pre-populate from MES cache (snapshot-restored events)
 *   4. onEvents → merge relay batch
 *   5. onNew → if at top, sorted-insert into _events; else buffer in _pendingNew
 *   6. feedRows derived from _events × _contentFilter × MES signal flows
 */
@HiltViewModel
class FeedViewModelV2 @Inject constructor(
    private val initGate: InitGate,
    private val timelineService: TimelineService,
    private val outboxResolver: OutboxRelayResolver,
    private val memoryEventStore: MemoryEventStore,
    private val keyManager: KeyManager,
) : ViewModel() {

    // ── Feed type + content filter ───────────────────────────────────────

    private val _feedType = MutableStateFlow<FeedType>(FeedType.Following)
    val feedType: StateFlow<FeedType> = _feedType.asStateFlow()

    private val _contentFilter = MutableStateFlow(FeedContentFilter.NOTES_ONLY)
    val contentFilter: StateFlow<FeedContentFilter> = _contentFilter.asStateFlow()

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

    // ── Derived FeedRows: events × contentFilter × MES signals ───────────

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
    }.flowOn(Dispatchers.Default)
     .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Subscription handle ───────────────────────────────────────────────

    private var currentHandle: TimelineService.TimelineHandle? = null

    init {
        Log.w(TAG, "VM_BORN ownPubkey=${keyManager.getPublicKeyHex()?.take(8)}")
        viewModelScope.launch {
            // collectLatest cancels previous resubscribe on rapid feedType changes.
            // No InitGate wait — resubscribe polls MES for cache (handles snapshot
            // restore timing) and Subscription.subscribe retries sendToRelay for 10s
            // (handles connection timing).
            _feedType.collectLatest { type ->
                Log.w(TAG, "VM_FEEDTYPE_EMIT $type")
                resubscribe(type)
            }
        }
        // Diagnostic: log feed state every 30s
        viewModelScope.launch {
            while (true) {
                delay(30_000L)
                Log.w(TAG, "STATUS events=${_events.value.size} pending=${_pendingNew.value.size} feedRows=${feedRows.value.size} isLoading=${_isLoading.value} isAtTop=${_isAtTop.value}")
            }
        }
    }

    private suspend fun resubscribe(type: FeedType) {
        currentHandle?.close()
        _events.value = emptyList()
        _pendingNew.value = emptyList()
        _isLoading.value = true

        val ownPubkey = keyManager.getPublicKeyHex()

        // Fast path: MES might already have data (warm resume with snapshot).
        var cached = loadCachedEvents(type, ownPubkey)

        if (cached.isEmpty()) {
            // Slow path: snapshot might still be restoring. Wait for Phase1
            // (snapshot + relay connections) with a generous timeout.
            Log.w(TAG, "CACHE_MISS $type — waiting for Phase1")
            withTimeoutOrNull(120_000L) { initGate.awaitFeedConnections() }
            cached = loadCachedEvents(type, ownPubkey)
        }

        if (cached.isNotEmpty()) {
            _events.value = cached
            _isLoading.value = false
            Log.w(TAG, "CACHE_HIT $type count=${cached.size}")
        }

        val subRequests = buildSubRequests(type)
        if (subRequests.isEmpty()) {
            Log.w(TAG, "NO_SUB_REQUESTS $type")
            _isLoading.value = false
            return
        }

        Log.w(TAG, "SUBSCRIBE $type subs=${subRequests.size} urls=${subRequests.flatMap { it.urls }.distinct().size}")

        val sinceCursor: Long = cached.firstOrNull()?.createdAt?.plus(1)
            ?: (System.currentTimeMillis() / 1000L - 60)

        currentHandle = timelineService.subscribeTimeline(
            subRequests = subRequests,
            onEvents = { batch, eosed ->
                Log.w(TAG, "ON_EVENTS $type batch=${batch.size} eosed=$eosed")
                if (cached.isEmpty() && _events.value.isEmpty()) {
                    _events.value = batch
                } else {
                    val newOnes = batch.filter { it.createdAt >= sinceCursor }
                    if (newOnes.isNotEmpty()) handleNewEvents(newOnes)
                }
                if (eosed) _isLoading.value = false
            },
            onNew = { event -> handleNewEvents(listOf(event)) },
        )
    }

    /** Merge new live-tail events: at-top → immediate, scrolled → pending buffer. */
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

    private fun loadCachedEvents(type: FeedType, ownPubkey: String?): List<NostrEvent> {
        val kinds = setOf(1, 6, 20, 21, 30023)
        val filter = when (type) {
            is FeedType.Following -> {
                val follows = ownPubkey
                    ?.let { memoryEventStore.getFollows(it) }
                    ?: return emptyList()
                if (follows.isEmpty()) return emptyList()
                FeedFilter(kinds = kinds, followedPubkeys = follows, contentFilter = 0)
            }
            is FeedType.Global -> FeedFilter(kinds = kinds, contentFilter = 0)
            is FeedType.SingleRelay -> FeedFilter(
                kinds = kinds,
                contentFilter = 0,
                relayUrls = setOf(type.url),
            )
            is FeedType.RelaySet -> {
                val members = ownPubkey
                    ?.let { memoryEventStore.getSetMembers(it, type.dTag) }
                    ?: return emptyList()
                if (members.isEmpty()) return emptyList()
                FeedFilter(kinds = kinds, contentFilter = 0, relayUrls = members.toSet())
            }
        }
        return memoryEventStore.feedEvents(filter, 300)
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
            is FeedType.RelaySet -> {
                val members = ownPubkey
                    ?.let { memoryEventStore.getSetMembers(it, type.dTag) }
                    ?: emptySet()
                val setUrls = members.mapNotNull { normalizeRelayUrl(it) }
                    .filter { it !in blockedRelays }
                    .ifEmpty { readRelays.ifEmpty { GLOBAL_RELAY_URLS } }
                outboxResolver.resolveGlobal(
                    readRelays = setUrls,
                    fallbackRelays = GLOBAL_RELAY_URLS,
                    blockedRelays = blockedRelays,
                    config = config,
                )
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun setFeedType(type: FeedType) {
        Log.w(TAG, "VM_SET_FT $type (was=${_feedType.value})")
        if (_feedType.value == type) return
        _feedType.value = type
    }

    fun setContentFilter(f: FeedContentFilter) {
        Log.w(TAG, "VM_SET_CF $f (was=${_contentFilter.value})")
        if (_contentFilter.value == f) return
        _contentFilter.value = f
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

    /**
     * Render-boundary filter. Notes/Conversations tabs work without
     * resubscribing relays — _events accumulates all kinds, filter here.
     */
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
