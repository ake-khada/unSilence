package com.unsilence.app.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.OutboxRelayResolver
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.data.memory.ReactionInfo
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.WotHydrationCoalescer
import com.unsilence.app.data.relay.wotLookupSnapshot
import com.unsilence.app.data.relay.wotSubjectsForFeedRows
import com.unsilence.app.ui.shared.TimelineCardData
import java.util.concurrent.ConcurrentHashMap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class DepthRow(val row: FeedRow, val depth: Int)

data class ThreadUiState(
    val focusedNote: FeedRow? = null,
    val replies: List<DepthRow> = emptyList(),
    val loading: Boolean = true,
    val focusedReplyId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ThreadViewModel @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val userRepository: UserRepository,
    private val outboxResolver: OutboxRelayResolver,
    private val cardHydrator: CardHydrator,
    private val wotHydrationCoalescer: WotHydrationCoalescer,
    private val relayPreferencesStore: com.unsilence.app.data.relay.RelayPreferencesStore,
    private val timelineCardData: TimelineCardData,
) : ViewModel() {

    /** NIP-36 sensitive-content display mode (shared with feed). */
    val sensitiveContentMode: StateFlow<com.unsilence.app.data.memory.SensitiveContentMode> =
        relayPreferencesStore.sensitiveContentModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                com.unsilence.app.data.memory.SensitiveContentMode.BLUR)

    val feedWotDisplayMode: StateFlow<FeedWotDisplayMode> =
        relayPreferencesStore.feedWotDisplayModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, FeedWotDisplayMode.NUMBERS)

    private val _uiState = MutableStateFlow(ThreadUiState())
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()
    private val _wotSubjects = MutableStateFlow<Set<String>>(emptySet())
    val wotLookups: StateFlow<Map<String, WotLookup>> =
        combine(_wotSubjects, memoryEventStore.wotSignalFlow) { subjects, _ ->
            wotLookupSnapshot(subjects, memoryEventStore::wotFor)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val eventIdFlow = MutableStateFlow<String?>(null)
    /** Set when the thread root is supported addressable content — replies then
     *  include kind-1111 #A roots instead of relying on the #e-only index. */
    private val coordFlow = MutableStateFlow<String?>(null)
    @Volatile private var articleCommentRelays: List<String> = emptyList()
    private val fetchedReplyParents = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var tappedId: String? = null

    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    val userAvatarUrl: StateFlow<String?> = pubkeyHex?.let { pk ->
        userRepository.userFlow(pk)
            .map { it?.picture }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    init {
        viewModelScope.launch {
            combine(eventIdFlow.filterNotNull(), coordFlow) { id, coord -> id to coord }
                .flatMapLatest { (id, coord) ->
                    if (coord != null) memoryEventStore.articleCommentsFlow(coord).map { Triple(id, true, it) }
                    else memoryEventStore.threadFeedRowFlow(id).map { Triple(id, false, it) }
                }
                .collect { (focusedId, articleMode, rows) ->
                    // Article mode: the focused article isn't in the comment list, and
                    // replies include NIP-22 kind-1111 (not just kind-1).
                    val focused = if (articleMode) {
                        memoryEventStore.feedRowsByIds(setOf(focusedId)).firstOrNull()
                    } else {
                        rows.firstOrNull { it.id == focusedId }
                    }
                    val replyRows = if (articleMode) {
                        rows.filter { it.id != focusedId && (it.kind == 1 || it.kind == 1111) }
                    } else {
                        rows.filter { it.id != focusedId && it.kind == 1 }
                    }

                    // Comment cards need live engagement — hydrate, same as the reader.
                    // Also stage-fetch replies-to-comments (descendants with no #a/#A
                    // tag) so nested replies show; dedupe so it can't loop.
                    if (articleMode && rows.isNotEmpty()) {
                        cardHydrator.hydrateEngagement(rows, 0, rows.size - 1)
                        val novel = rows.map { it.id }.filter { fetchedReplyParents.add(it) }
                        if (novel.isNotEmpty() && articleCommentRelays.isNotEmpty()) {
                            viewModelScope.launch { relayPool.fetchCommentReplies(articleCommentRelays, novel) }
                        }
                    }

                    // Build parent→children map
                    val childrenOf = replyRows.groupBy { it.replyToId ?: it.rootId ?: focusedId }
                        .mapValues { (_, v) -> v.sortedBy { it.createdAt } }

                    // DFS flatten with bounded depth; visited set prevents
                    // stack overflow from circular reply chains (malicious or bridged)
                    val flatList = mutableListOf<DepthRow>()
                    val visited = mutableSetOf<String>()
                    fun walk(parentId: String, depth: Int) {
                        childrenOf[parentId]?.forEach { row ->
                            if (visited.add(row.id)) {
                                flatList.add(DepthRow(row, depth.coerceAtMost(MAX_REPLY_DEPTH)))
                                walk(row.id, depth + 1)
                            }
                        }
                    }
                    walk(focusedId, 1)

                    _uiState.value = ThreadUiState(
                        focusedNote    = focused,
                        replies        = flatList,
                        loading        = false,
                        focusedReplyId = tappedId.takeIf { it != focusedId },
                    )
                    val subjects = wotSubjectsForFeedRows(
                        listOfNotNull(focused) + flatList.map { it.row },
                        modelProvider = memoryEventStore::getEventModel,
                    )
                    _wotSubjects.value = subjects
                    wotHydrationCoalescer.requestHydration(subjects)
                }
        }
    }

    // ── Per-event stats (reactive counts for thread cards) ─────────────
    fun statsFlow(eventId: String): StateFlow<EventStats> =
        timelineCardData.statsFlow(eventId, viewModelScope)

    // ── Engagement drawer data (contributor indexes) ─────────────────────
    fun zapDetailsForEvent(eventId: String): List<ZapDetail> =
        timelineCardData.zapDetailsForEvent(eventId)
    fun repostPubkeysForEvent(eventId: String): List<String> =
        timelineCardData.repostPubkeysForEvent(eventId)
    fun reactionsForEvent(eventId: String): List<ReactionInfo> =
        timelineCardData.reactionsForEvent(eventId)

    // ── Profile flow (reactive avatar/name for drawer chips) ─────────────

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        timelineCardData.profileFlow(pubkey, viewModelScope)

    /** Wipe stale state so next open doesn't flash old content. */
    fun clearThread() {
        eventIdFlow.value = null
        coordFlow.value = null
        articleCommentRelays = emptyList()
        fetchedReplyParents.clear()
        tappedId = null
        _wotSubjects.value = emptySet()
        _uiState.value = ThreadUiState()
    }

    /**
     * Point the thread at [rootId]. Supported addressable content switches to
     * coordinate-aware comments; anything else uses the standard #e thread fetch.
     */
    private suspend fun applyRoot(rootId: String, urls: List<String>) {
        eventIdFlow.value = rootId
        val rootEvent = memoryEventStore.getEventEntity(rootId)
        val coord = if (rootEvent?.kind in setOf(30023, 34235, 34236)) {
            memoryEventStore.articleCoordForEvent(rootId)
        } else null
        if (coord != null) {
            memoryEventStore.registerArticleCoord(rootId, coord)
            coordFlow.value = coord
            val relays = (urls +
                (memoryEventStore.getNostrEvent(rootId)?.relaysSeen ?: emptySet()) +
                memoryEventStore.relayHintsForEvent(rootId)).distinct()
            articleCommentRelays = relays
            relayPool.fetchArticleComments(relays, coord)
        } else {
            coordFlow.value = null
            relayPool.fetchThread(urls, rootId)
        }
    }

    fun loadThread(eventId: String) {
        tappedId = eventId
        // Clear stale state immediately — prevents flash of old thread content
        _uiState.value = ThreadUiState(loading = true)
        viewModelScope.launch {
            val ownPubkey = pubkeyHex ?: ""
            val ownReadRelays = memoryEventStore.getReadWriteRelayConfigs(ownPubkey)
                .filter { it.marker == null || it.marker == "read" }
                .mapNotNull { normalizeRelayUrl(it.url) }
            val blockedRelays = memoryEventStore.getBlockedRelayUrls(ownPubkey).toSet()

            // Best-guess root from local MES — instant, no network
            val event = memoryEventStore.getEventEntity(eventId)
            val bestGuessRoot = event?.rootId ?: event?.replyToId ?: eventId

            // Resolve relays based on the thread root's author. If we don't
            // have the event yet (cold tap), fall back to own read + GLOBAL.
            val rootAuthorPubkey = event?.pubkey
                ?: memoryEventStore.getEventEntity(bestGuessRoot)?.pubkey

            val urls = if (rootAuthorPubkey != null) {
                outboxResolver.resolveEngagementRelays(
                    authorPubkey = rootAuthorPubkey,
                    ownReadRelays = ownReadRelays,
                    blockedRelays = blockedRelays,
                )
            } else {
                ownReadRelays.ifEmpty { GLOBAL_RELAY_URLS }
            }

            if (eventIdFlow.value == bestGuessRoot) return@launch

            applyRoot(bestGuessRoot, urls)

            // Refine root in background — walk UP the reply chain fetching ancestors
            launch {
                val trueRoot = withTimeoutOrNull(8_000) {
                    resolveThreadRoot(eventId, urls)
                } ?: return@launch
                if (trueRoot != bestGuessRoot && eventIdFlow.value == bestGuessRoot) {
                    // Re-resolve relays for the true root's author — may differ
                    // from the tapped event's author.
                    val trueRootEvent = memoryEventStore.getEventEntity(trueRoot)
                    val trueRootAuthor = trueRootEvent?.pubkey
                    val refinedUrls = if (trueRootAuthor != null && trueRootAuthor != rootAuthorPubkey) {
                        outboxResolver.resolveEngagementRelays(
                            authorPubkey = trueRootAuthor,
                            ownReadRelays = ownReadRelays,
                            blockedRelays = blockedRelays,
                        )
                    } else urls

                    applyRoot(trueRoot, refinedUrls)
                }
            }
        }
    }

    /**
     * Walk UP the reply chain, fetching missing ancestors, to the true root.
     * Best-effort: returns the highest id reached if a relay never returns one.
     */
    private suspend fun resolveThreadRoot(startId: String, hints: List<String>): String {
        val visited = mutableSetOf<String>()
        var currentId = startId
        repeat(50) {                                       // hop cap — pathological guard
            if (!visited.add(currentId)) return currentId   // cycle guard
            val current = memoryEventStore.getEventEntity(currentId)
                ?: fetchAncestor(currentId, hints)
                ?: return currentId                        // unreachable — best-effort root
            val parentId = current.replyToId ?: current.rootId
            if (parentId == null || parentId == currentId) return currentId  // root
            currentId = parentId
        }
        return currentId
    }

    private suspend fun fetchAncestor(id: String, hints: List<String>): EventEntity? {
        withContext(Dispatchers.IO) { relayPool.fetchEventById(id, hints) }
        return withTimeoutOrNull(3_000) {
            memoryEventStore.eventEntityFlow(id).filterNotNull().first()
        }
    }

}
