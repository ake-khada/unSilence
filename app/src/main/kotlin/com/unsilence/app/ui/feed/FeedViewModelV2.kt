package com.unsilence.app.ui.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.memory.FeedFilter
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.OutboxRelayResolver
import com.unsilence.app.data.relay.SubRequest
import com.unsilence.app.data.relay.TimelineConsumer
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.relay.normalizeRelayUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val TAG = "FeedVMv2"

@HiltViewModel
class FeedViewModelV2 @Inject constructor(
    private val initGate: InitGate,
    private val timelineService: TimelineService,
    private val outboxResolver: OutboxRelayResolver,
    private val memoryEventStore: MemoryEventStore,
    private val keyManager: KeyManager,
) : ViewModel() {

    private val consumer = TimelineConsumer(
        timelineService = timelineService,
        memoryEventStore = memoryEventStore,
        ownerScope = viewModelScope,
    )

    // ── Expose consumer flows ────────────────────────────────────────────

    val feedRows = consumer.feedRows
    val showDot = consumer.showDot
    val pendingCount = consumer.pendingCount
    val isLoading = consumer.isLoading
    val isLoadingMore = consumer.isLoadingMore

    // ── Feed type (V2-specific) ──────────────────────────────────────────

    private val _feedType = MutableStateFlow<FeedType>(FeedType.Following)
    val feedType: StateFlow<FeedType> = _feedType.asStateFlow()

    fun setFeedType(type: FeedType) {
        Log.w(TAG, "VM_SET_FT $type (was=${_feedType.value})")
        if (_feedType.value == type) return
        _feedType.value = type
    }

    fun setContentFilter(f: FeedContentFilter) {
        Log.w(TAG, "VM_SET_CF $f")
        consumer.setContentFilter(f)
    }

    // ── User actions ─────────────────────────────────────────────────────

    fun onViewportChanged(idx: Int) = consumer.onViewportChanged(idx)
    fun onDotTapped() = consumer.onDotTapped()
    fun loadMore() = consumer.loadMore()

    fun refresh() {
        viewModelScope.launch {
            resubscribe(_feedType.value)
        }
    }

    // ── Init ─────────────────────────────────────────────────────────────

    init {
        Log.w(TAG, "VM_BORN ownPubkey=${keyManager.getPublicKeyHex()?.take(8)}")
        viewModelScope.launch {
            _feedType.collectLatest { type ->
                Log.w(TAG, "VM_FEEDTYPE_EMIT $type")
                resubscribe(type)
            }
        }
        // Diagnostic: log feed state every 30s
        viewModelScope.launch {
            while (true) {
                delay(30_000L)
                Log.w(TAG, "STATUS feedRows=${feedRows.value.size} isLoading=${isLoading.value}")
            }
        }
    }

    // ── Feed-type-specific subscription logic ────────────────────────────

    private suspend fun resubscribe(type: FeedType) {
        val ownPubkey = keyManager.getPublicKeyHex()

        // Fast path: MES might already have data (warm resume with snapshot).
        var cached = loadCachedEvents(type, ownPubkey)

        if (cached.isEmpty()) {
            // Slow path: snapshot might still be restoring. Wait for Phase1.
            Log.w(TAG, "CACHE_MISS $type — waiting for Phase1")
            withTimeoutOrNull(120_000L) { initGate.awaitFeedConnections() }
            cached = loadCachedEvents(type, ownPubkey)
        }

        if (cached.isNotEmpty()) {
            Log.w(TAG, "CACHE_HIT $type count=${cached.size}")
        }

        val subRequests = buildSubRequests(type)
        if (subRequests.isEmpty()) {
            Log.w(TAG, "NO_SUB_REQUESTS $type")
        } else {
            Log.w(TAG, "SUBSCRIBE $type subs=${subRequests.size} urls=${subRequests.flatMap { it.urls }.distinct().size}")
        }

        consumer.subscribe(subRequests, cached)
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

    override fun onCleared() {
        consumer.close()
        super.onCleared()
    }
}
