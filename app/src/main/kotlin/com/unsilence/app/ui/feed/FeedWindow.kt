package com.unsilence.app.ui.feed

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.ShowType
import com.unsilence.app.ui.profile.ProfileTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "FeedWindow"
private const val WINDOW_BATCH = 300
private const val RENDERED_CAP = 1500

@androidx.compose.runtime.Immutable
data class WindowSnapshot(
    val rows: List<FeedRow> = emptyList(),
    val pendingCount: Int = 0,
    val showDot: Boolean = false,
    val isLoadingInitial: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val oldestCreatedAt: Long = Long.MAX_VALUE,
)

private sealed class WindowEvent {
    data class ContentInsert(val event: NostrEvent) : WindowEvent()
    data class EngagementUpdate(val targetId: String) : WindowEvent()
    data class ProfileUpdate(val pubkey: String) : WindowEvent()
    data object FlushPending : WindowEvent()
    data object IsAtTop : WindowEvent()
    data object NotAtTop : WindowEvent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class FeedWindow(
    val key: WindowKey,
    private val mes: MemoryEventStore,
    private val loader: FeedWindowLoader,
    private val keyManager: KeyManager,
    parentScope: CoroutineScope,
) {
    // Single-threaded dispatcher serializes all state mutations — no races
    private val scope = CoroutineScope(
        SupervisorJob(parentScope.coroutineContext[Job]) +
            Dispatchers.Default.limitedParallelism(1)
    )

    private var rendered: MutableList<FeedRow> = mutableListOf()
    private var renderedIds = HashSet<String>()
    private val pendingTop: MutableList<FeedRow> = mutableListOf()
    private val pendingTopIds = HashSet<String>()
    private var hasMore = true
    private var isLoadingInitial = true
    private var isLoadingMore = false
    private var isAtTop = true

    // Filter snapshot resolved at activate time
    private var resolvedFollowedPubkeys: Set<String>? = null
    private var resolvedRelayUrls: Set<String>? = null
    private var resolvedKinds: Set<Int> = emptySet()
    private var resolvedContentFilter: Int = 0
    private var resolvedAuthorPubkey: String? = null
    private var resolvedFeedFilter: FeedFilter? = null  // post-query filters (sinceHours, engagement, media)

    private val _snapshot = MutableStateFlow(WindowSnapshot())
    val snapshot: StateFlow<WindowSnapshot> = _snapshot.asStateFlow()

    private val events = Channel<WindowEvent>(Channel.UNLIMITED)
    private var drainJob: Job? = null
    private var listener: MemoryEventStore.FeedListener? = null

    val hasLoaded: Boolean get() = !isLoadingInitial

    fun activate() {
        if (drainJob?.isActive == true) return
        Log.d(TAG, "activate key=$key hasLoaded=$hasLoaded")

        resolveFilter()
        registerListener()
        startDrain()

        if (rendered.isEmpty() && hasMore) {
            scope.launch { doInitialLoad() }
        } else {
            publish()
        }
    }

    fun deactivate() {
        Log.d(TAG, "deactivate key=$key")
        listener?.let { mes.unregisterFeedListener(it) }
        listener = null
        drainJob?.cancel()
        drainJob = null
    }

    fun loadMore() {
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        publish()

        scope.launch {
            try {
                val cursor = rendered.lastOrNull()?.createdAt ?: return@launch
                val batch = loader.loadBatchFor(key, cursor = cursor, limit = WINDOW_BATCH)
                if (batch.isEmpty()) {
                    hasMore = false
                } else {
                    val novel = batch.filter { it.id !in renderedIds && it.id !in pendingTopIds }
                    rendered.addAll(novel)
                    for (r in novel) renderedIds.add(r.id)
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "loadMore failed for $key", e)
            } finally {
                isLoadingMore = false
                publish()
            }
        }
    }

    fun flushPending() {
        events.trySend(WindowEvent.FlushPending)
    }

    fun onScrollChanged(firstVisibleIndex: Int, firstVisibleOffset: Int) {
        val atTop = firstVisibleIndex == 0 && firstVisibleOffset == 0
        if (atTop != isAtTop) {
            events.trySend(if (atTop) WindowEvent.IsAtTop else WindowEvent.NotAtTop)
        }
    }

    fun release() {
        Log.d(TAG, "release key=$key")
        deactivate()
        loader.stopEngagementRefreshFor(key)
        scope.cancel()
        events.close()
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun resolveFilter() {
        when (val k = key) {
            is WindowKey.Home -> {
                val pubkey = keyManager.getPublicKeyHex() ?: ""
                resolvedKinds = k.filter.enabledKinds.toSet()
                resolvedContentFilter = k.contentFilter.value
                resolvedAuthorPubkey = null
                resolvedFeedFilter = k.filter
                when (val ft = k.feedType) {
                    is FeedType.Following -> {
                        resolvedFollowedPubkeys = mes.getFollows(pubkey) ?: emptySet()
                        resolvedRelayUrls = null
                    }
                    is FeedType.Global -> {
                        resolvedFollowedPubkeys = null
                        resolvedRelayUrls = resolveGlobalUrls(pubkey).toSet()
                    }
                    is FeedType.RelaySet -> {
                        resolvedFollowedPubkeys = null
                        resolvedRelayUrls = mes.getSetMembers(pubkey, ft.dTag)
                            .mapNotNull { normalizeRelayUrl(it) }
                            .ifEmpty { resolveGlobalUrls(pubkey) }.toSet()
                    }
                    is FeedType.SingleRelay -> {
                        resolvedFollowedPubkeys = null
                        resolvedRelayUrls = setOfNotNull(normalizeRelayUrl(ft.url))
                    }
                }
            }
            is WindowKey.Profile -> {
                resolvedAuthorPubkey = k.pubkey
                resolvedFollowedPubkeys = null
                resolvedRelayUrls = null
                resolvedFeedFilter = null
                val (cf, kinds) = when (k.tab) {
                    ProfileTab.NOTES -> 1 to setOf(1, 6)
                    ProfileTab.REPLIES -> 2 to setOf(1, 6)
                    ProfileTab.LONGFORM -> 0 to setOf(30023)
                }
                resolvedContentFilter = cf
                resolvedKinds = kinds
            }
        }
    }

    private fun registerListener() {
        val l = object : MemoryEventStore.FeedListener {
            override fun onContentInsert(event: NostrEvent) {
                if (matchesFilter(event)) events.trySend(WindowEvent.ContentInsert(event))
            }
            override fun onEngagementUpdate(targetId: String) {
                if (targetId in renderedIds) events.trySend(WindowEvent.EngagementUpdate(targetId))
            }
            override fun onProfileUpdate(pubkey: String) {
                events.trySend(WindowEvent.ProfileUpdate(pubkey))
            }
        }
        listener = l
        mes.registerFeedListener(l)
    }

    private fun matchesFilter(event: NostrEvent): Boolean {
        if (event.kind !in resolvedKinds) return false
        when (resolvedContentFilter) {
            1 -> if (event.kind != 6 && (event.replyToId != null || event.rootId != null)) return false
            2 -> if ((event.replyToId == null && event.rootId == null) || event.kind == 6) return false
        }
        resolvedAuthorPubkey?.let { if (event.pubkey != it) return false }
        resolvedFollowedPubkeys?.let { if (event.pubkey !in it) return false }
        resolvedRelayUrls?.let { urls ->
            if (urls.isNotEmpty() && event.relaysSeen.none { normalizeRelayUrl(it) in urls }) return false
        }
        return true
    }

    /** Check post-query filters on a FeedRow before buffering (live-tail + loadBatch). */
    private fun passesPostQueryFilters(row: FeedRow): Boolean {
        val filter = resolvedFeedFilter ?: return true
        return passesAllFilters(row, filter)
    }

    private fun startDrain() {
        drainJob = scope.launch {
            for (ev in events) {
                applyEvent(ev)
                // Drain bursts
                while (true) {
                    val next = events.tryReceive().getOrNull() ?: break
                    applyEvent(next)
                }
                publish()
            }
        }
    }

    private fun applyEvent(ev: WindowEvent) {
        when (ev) {
            is WindowEvent.ContentInsert -> {
                val row = mes.feedRowsByIds(setOf(ev.event.id)).firstOrNull() ?: return
                if (row.id in renderedIds || row.id in pendingTopIds) return
                if (!passesPostQueryFilters(row)) return
                if (rendered.isEmpty() || isAtTop) {
                    // Insert at sorted position (descending by createdAt)
                    val idx = rendered.indexOfFirst { it.createdAt < row.createdAt }
                    if (idx == -1) rendered.add(row) else rendered.add(idx, row)
                    renderedIds.add(row.id)
                    trimRendered()
                } else {
                    pendingTop.add(0, row)
                    pendingTopIds.add(row.id)
                }
            }
            is WindowEvent.EngagementUpdate -> {
                val idx = rendered.indexOfFirst { it.id == ev.targetId }
                if (idx >= 0) {
                    val fresh = mes.feedRowsByIds(setOf(ev.targetId)).firstOrNull() ?: return
                    rendered[idx] = fresh
                }
            }
            is WindowEvent.ProfileUpdate -> {
                val ids = rendered.asSequence().filter { it.pubkey == ev.pubkey }.map { it.id }.toSet()
                if (ids.isEmpty()) return
                val fresh = mes.feedRowsByIds(ids).associateBy { it.id }
                for (i in rendered.indices) {
                    fresh[rendered[i].id]?.let { rendered[i] = it }
                }
            }
            WindowEvent.FlushPending -> {
                if (pendingTop.isEmpty()) return
                // Sort pending newest-first, then merge into rendered maintaining order
                pendingTop.sortByDescending { it.createdAt }
                for (row in pendingTop) {
                    val idx = rendered.indexOfFirst { it.createdAt < row.createdAt }
                    if (idx == -1) rendered.add(row) else rendered.add(idx, row)
                    renderedIds.add(row.id)
                }
                pendingTop.clear()
                pendingTopIds.clear()
                trimRendered()
            }
            WindowEvent.IsAtTop -> {
                isAtTop = true
                if (pendingTop.isNotEmpty()) applyEvent(WindowEvent.FlushPending)
            }
            WindowEvent.NotAtTop -> {
                isAtTop = false
            }
        }
    }

    private fun trimRendered() {
        while (rendered.size > RENDERED_CAP) {
            val dropped = rendered.removeAt(rendered.size - 1)
            renderedIds.remove(dropped.id)
        }
    }

    private suspend fun doInitialLoad() {
        try {
            val batch = loader.loadBatchFor(key, cursor = null, limit = WINDOW_BATCH)
            rendered = batch.toMutableList()
            renderedIds = HashSet<String>(batch.size).apply { for (r in batch) add(r.id) }
            isLoadingInitial = false
            if (batch.isEmpty()) hasMore = false
            loader.startEngagementRefreshFor(key, renderedIds)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "initial load failed for $key", e)
            isLoadingInitial = false
        } finally {
            publish()
        }
    }

    private fun publish() {
        _snapshot.value = WindowSnapshot(
            rows = rendered.toList(),
            pendingCount = pendingTop.size,
            showDot = pendingTop.isNotEmpty(),
            isLoadingInitial = isLoadingInitial,
            isLoadingMore = isLoadingMore,
            hasMore = hasMore,
            oldestCreatedAt = rendered.lastOrNull()?.createdAt ?: Long.MAX_VALUE,
        )
    }

    private fun resolveGlobalUrls(pubkey: String): List<String> {
        if (pubkey.isEmpty()) return GLOBAL_RELAY_URLS
        val readRelays = mes.getReadWriteRelayConfigs(pubkey)
            .filter { it.marker == null || it.marker == "read" }
            .mapNotNull { normalizeRelayUrl(it.url) }
        return readRelays.ifEmpty { GLOBAL_RELAY_URLS }
    }

    companion object {
        private val IMAGE_REGEX = Regex(
            """https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?|https?://(?:image\.nostr\.build|i\.nostr\.build|nostr\.build|blossom\.primal\.net)/\S+""",
            RegexOption.IGNORE_CASE,
        )
        private val VIDEO_REGEX = Regex(
            """https?://\S+\.(?:mp4|mov|webm|m3u8|m4v|avi)(?:\?\S*)?""",
            RegexOption.IGNORE_CASE,
        )
        private val IMETA_IMAGE_REGEX = Regex(""""image/""", RegexOption.IGNORE_CASE)
        private val IMETA_VIDEO_REGEX = Regex(""""video/""", RegexOption.IGNORE_CASE)

        private fun hasImage(row: FeedRow): Boolean =
            IMAGE_REGEX.containsMatchIn(row.content) || IMETA_IMAGE_REGEX.containsMatchIn(row.tags)

        private fun hasVideo(row: FeedRow): Boolean =
            VIDEO_REGEX.containsMatchIn(row.content) || IMETA_VIDEO_REGEX.containsMatchIn(row.tags)

        fun applyMediaFilter(rows: List<FeedRow>, types: Set<ShowType>): List<FeedRow> =
            rows.filter { row -> passesMediaFilter(row, types) }

        /** Single-row media type check — used by both batch filter and live-tail. */
        fun passesMediaFilter(row: FeedRow, types: Set<ShowType>): Boolean =
            when (row.kind) {
                1 -> {
                    val img = hasImage(row)
                    val vid = hasVideo(row)
                    (ShowType.TEXT in types && !img && !vid) ||
                    (ShowType.IMAGES in types && img) ||
                    (ShowType.VIDEO in types && vid)
                }
                else -> true
            }

        /**
         * Shared post-query filter: sinceHours, engagement minimums, media type.
         * Called from both loadBatchFor (initial batch) and live-tail ContentInsert
         * so the two paths never diverge — no phantom blue dots.
         *
         * Caveat: live-tail applies engagement minimums at insert time, but
         * engagement counts accumulate later. A post with replyCount=0 at
         * insert time gets filtered even if it might later cross minReplies.
         * Acceptable v1 limitation — engagement minimums are off by default.
         */
        fun passesAllFilters(row: FeedRow, filter: FeedFilter): Boolean {
            // Time filter
            val sinceTs = filter.sinceHours?.let {
                System.currentTimeMillis() / 1000L - it * 3600L
            } ?: 0L
            if (sinceTs > 0 && row.createdAt < sinceTs) return false

            // Engagement minimums
            if (row.replyCount < filter.minReplies) return false
            if (row.repostCount < filter.minReposts) return false
            if (row.reactionCount < filter.minReactions) return false
            if (row.zapTotalSats < filter.minZapSats) return false

            // Media type filter
            if (filter.needsMediaFilter && !passesMediaFilter(row, filter.showTypes)) return false

            return true
        }
    }
}
