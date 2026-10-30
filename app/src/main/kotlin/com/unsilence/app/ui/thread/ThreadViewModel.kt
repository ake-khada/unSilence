package com.unsilence.app.ui.thread

import com.unsilence.app.ui.shared.CardDataFlow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
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
import com.unsilence.app.data.relay.boundedSeenRelayHints
import com.unsilence.app.data.relay.wotLookupSnapshot
import com.unsilence.app.data.relay.wotSubjectsForFeedRows
import com.unsilence.app.ui.shared.TimelineCardData
import com.unsilence.app.ui.shared.collectLatestWhileActive
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import com.unsilence.app.ui.shared.ModeratedReplyRow
import com.unsilence.app.ui.shared.markLikelyCoordinatedSpam
import com.unsilence.app.ui.shared.mutedTimelineRowIds
import com.unsilence.app.ui.shared.pruneFullyMutedSubtrees
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

typealias DepthRow = ModeratedReplyRow

data class ThreadUiState(
    val focusedNote: FeedRow? = null,
    val replies: List<DepthRow> = emptyList(),
    val loading: Boolean = true,
    val focusedReplyId: String? = null,
)

/**
 * Coordinate membership or an explicit parsed event root identifies the thread.
 * If a relay returns a child before its parent, attach that orphan to the known
 * root until the parent arrives instead of counting a comment that cannot render.
 */
internal fun threadProjectionParentId(
    replyToId: String?,
    rootId: String?,
    focusedId: String,
    availableReplyIds: Set<String>,
    coordinateScoped: Boolean,
): String {
    val parentId = replyToId ?: rootId ?: return focusedId
    return if (
        (coordinateScoped || rootId == focusedId) &&
        parentId != focusedId &&
        parentId !in availableReplyIds
    ) focusedId else parentId
}

/**
 * Flatten replies while retaining a muted parent only when a visible descendant needs its
 * place in the tree. Fully muted subtrees disappear; retained descendants keep their depth.
 */
internal fun flattenThreadReplies(
    focusedId: String,
    replyRows: List<FeedRow>,
    coordinateScoped: Boolean,
    mutedIds: Set<String> = emptySet(),
): List<DepthRow> {
    val availableReplyIds = replyRows.mapTo(HashSet(replyRows.size)) { it.id }
    val childrenOf = replyRows.groupBy { row ->
        threadProjectionParentId(
            replyToId = row.replyToId,
            rootId = row.rootId,
            focusedId = focusedId,
            availableReplyIds = availableReplyIds,
            coordinateScoped = coordinateScoped,
        )
    }.mapValues { (_, rows) -> rows.sortedBy { it.createdAt } }

    val flatList = mutableListOf<DepthRow>()
    val visited = mutableSetOf<String>()
    fun walk(parentId: String, depth: Int) {
        childrenOf[parentId]?.forEach { row ->
            if (visited.add(row.id)) {
                flatList.add(
                    DepthRow(
                        row = row,
                        depth = depth,
                        muted = row.id in mutedIds,
                    )
                )
                walk(row.id, depth + 1)
            }
        }
    }
    walk(focusedId, 1)
    return pruneFullyMutedSubtrees(
        rows = flatList,
        depthOf = { it.depth },
        isMuted = { it.muted },
    ).map { it.copy(depth = it.depth.coerceAtMost(MAX_REPLY_DEPTH)) }
}

/** Relay locality for the next hop in an ancestor walk. The relay that supplied
 * the current event leads, followed by active browse and declared parent hints. */
internal fun nextAncestorRelayHints(
    sourceRelaysSeen: Collection<String>,
    browseRelays: Collection<String>,
    parentRelayHints: Collection<String>,
    initialHints: Collection<String>,
): List<String> = boundedSeenRelayHints(
    seenRelays = sourceRelaysSeen,
    browseRelays = browseRelays,
    additionalRelays = parentRelayHints + initialHints,
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
    private val eventReferenceResolver: com.unsilence.app.ui.feed.EventReferenceResolver,
) : ViewModel() {

    /** NIP-36 sensitive-content display mode (shared with feed). */
    val sensitiveContentMode: StateFlow<com.unsilence.app.data.memory.SensitiveContentMode> =
        relayPreferencesStore.sensitiveContentMode

    val feedWotDisplayMode: StateFlow<FeedWotDisplayMode> =
        relayPreferencesStore.feedWotDisplayModeFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedWotDisplayMode.NUMBERS)

    private val _uiState = MutableStateFlow(ThreadUiState())
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()
    private val _baseUiState = MutableStateFlow(ThreadUiState())
    private val _wotSubjects = MutableStateFlow<Set<String>>(emptySet())
    val wotLookups: StateFlow<Map<String, WotLookup>> =
        combine(_wotSubjects, memoryEventStore.wotSignalFlow) { subjects, _ ->
            wotLookupSnapshot(subjects, memoryEventStore::wotFor)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val eventIdFlow = MutableStateFlow<String?>(null)
    // Non-navigation clients (e.g. the immersive engagement sheet) remain active
    // until clearThread; navigation explicitly supplies its resumed lifecycle.
    private val screenActive = MutableStateFlow(false)

    fun setScreenActive(active: Boolean) {
        screenActive.value = active
        if (!active) loadJob?.cancel()
        retainRoot(eventIdFlow.value.takeIf { active })
        if (active) wotHydrationCoalescer.requestHydration(_wotSubjects.value)
    }

    private var retainedRootId: String? = null
    private var rootRetention: AutoCloseable? = null

    private fun retainRoot(id: String?) {
        if (retainedRootId == id) return
        val next = id?.let(memoryEventStore::retainRequestedEvent)
        rootRetention?.close()
        rootRetention = next
        retainedRootId = id
    }

    override fun onCleared() {
        retainRoot(null)
        super.onCleared()
    }

    /** Include authors of visible quotes/parents that resolved after the row batch. */
    fun requestWotHydration(pubkeys: Collection<String>) {
        if (pubkeys.isEmpty()) return
        _wotSubjects.update { current -> current + pubkeys }
        if (screenActive.value) wotHydrationCoalescer.requestHydration(pubkeys)
    }
    @Volatile private var articleCommentRelays: List<String> = emptyList()
    private var threadRelayUrls: List<String> = emptyList()
    private val completedThreadQueries = HashSet<Pair<String, String>>()
    private val fetchedArticleCoords = HashSet<String>()
    private val fetchedReplyDescendants = HashSet<String>()
    private val fetchedMissingParents = HashSet<String>()
    @Volatile private var tappedId: String? = null
    private var loadJob: Job? = null

    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    val userAvatarUrl: StateFlow<String?> = pubkeyHex?.let { pk ->
        userRepository.userFlow(pk)
            .map { it?.picture }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    init {
        viewModelScope.launch {
            eventIdFlow
                .flatMapLatest { id ->
                    if (id == null) return@flatMapLatest emptyFlow()
                    // A repost target can arrive after the drawer opens. Re-evaluate
                    // its coordinate on every feed insert so the thread promotes from
                    // #e lookup to #A lookup without requiring a page-away/page-back.
                    memoryEventStore.feedSignalFlow
                        .map { id to memoryEventStore.articleCoordForEvent(id) }
                        .distinctUntilChanged()
                }
                .flatMapLatest { (id, coord) ->
                    // Children belong to this active root/coordinate observation.
                    // Hiding, replacing or clearing it cancels their one-shots too.
                    channelFlow {
                        val descendants = HashSet<String>()
                        val parents = HashSet<String>()
                        val rowsFlow = if (coord != null) {
                            val relays = (
                                articleCommentRelays +
                                    memoryEventStore.getNostrEvent(id)?.relaysSeen.orEmpty() +
                                    memoryEventStore.relayHintsForEvent(id)
                                ).distinct()
                            articleCommentRelays = relays
                            if (coord !in fetchedArticleCoords && relays.isNotEmpty()) launch {
                                if (relayPool.fetchArticleComments(relays, coord) && eventIdFlow.value == id) fetchedArticleCoords.add(coord)
                            }
                            memoryEventStore.articleCommentsFlow(coord)
                        } else {
                            launch {
                                relayPool.fetchThread(threadRelayUrls, id, completedThreadQueries) { query, relay ->
                                    if (eventIdFlow.value == id) completedThreadQueries.add(query to relay)
                                }
                            }
                            memoryEventStore.threadFeedRowFlow(id)
                        }
                        rowsFlow.collect { rows ->
                            if (coord != null && rows.isNotEmpty() && articleCommentRelays.isNotEmpty()) {
                                val relays = articleCommentRelays
                                val rowIds = rows.mapTo(HashSet(rows.size)) { it.id }
                                val missing = rows.asSequence()
                                    .mapNotNull { it.replyToId }
                                    .filter { it != id && it !in rowIds }
                                    .filter { memoryEventStore.getEventEntity(it) == null }
                                    .filter { it !in fetchedMissingParents && parents.add(it) }
                                    .toList()
                                if (missing.isNotEmpty()) launch {
                                    if (relayPool.fetchCommentParents(relays, missing) && eventIdFlow.value == id) fetchedMissingParents.addAll(missing.take(200))
                                }
                                val novel = rows.map { it.id }
                                    .filter { it !in fetchedReplyDescendants && descendants.add(it) }
                                if (novel.isNotEmpty()) launch {
                                    if (relayPool.fetchCommentReplies(relays, novel) && eventIdFlow.value == id) fetchedReplyDescendants.addAll(novel.take(200))
                                }
                            }
                            send(Triple(id, coord != null, rows))
                        }
                    }
                }
                .combine(memoryEventStore.ownMuteListFlow()) { source, muteList ->
                    source to muteList
                }
                .collectLatestWhileActive(screenActive) { (source, muteList) ->
                    val (focusedId, articleMode, rows) = source
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
                        rows.filter { it.id != focusedId && (it.kind == 1 || it.kind == 1111) }
                    }
                    val mutedReplyIds = mutedTimelineRowIds(
                        rows = replyRows,
                        muteList = muteList,
                        eventProvider = memoryEventStore::getNostrEvent,
                    )

                    // Comment cards need live engagement — hydrate, same as the reader.
                    // Also stage-fetch replies-to-comments (descendants with no #a/#A
                    // tag) so nested replies show; dedupe so it can't loop.
                    if (articleMode && rows.isNotEmpty()) {
                        val visibleRows = rows.filterNot { it.id in mutedReplyIds }
                        if (visibleRows.isNotEmpty()) {
                            cardHydrator.hydrateEngagement(visibleRows, 0, visibleRows.size - 1)
                        }
                    }

                    val flatList = flattenThreadReplies(
                        focusedId = focusedId,
                        replyRows = replyRows,
                        coordinateScoped = articleMode,
                        mutedIds = mutedReplyIds,
                    )
                    _baseUiState.value = ThreadUiState(
                        focusedNote    = focused,
                        replies        = flatList,
                        loading        = false,
                        focusedReplyId = tappedId.takeIf { it != focusedId },
                    )
                    val subjects = wotSubjectsForFeedRows(
                        listOfNotNull(focused) + flatList.filterNot { it.muted }.map { it.row },
                        cachedModelProvider = memoryEventStore::getEventModel,
                    )
                    _wotSubjects.value = subjects
                    wotHydrationCoalescer.requestHydration(subjects)
                }
        }

        viewModelScope.launch {
            combine(
                _baseUiState,
                memoryEventStore.wotSignalFlow,
                memoryEventStore.followsSignalFlow,
            ) { baseState, _, _ -> baseState }
                .map { baseState ->
                    val ownPubkey = pubkeyHex
                    val followedPubkeys = ownPubkey
                        ?.let(memoryEventStore::getFollows)
                        .orEmpty()
                    val trustCache = HashMap<String, Boolean>()
                    baseState.copy(
                        replies = markLikelyCoordinatedSpam(
                            rows = baseState.replies,
                            protectedEventIds = setOfNotNull(baseState.focusedReplyId),
                            isTrustedAuthor = { pubkey ->
                                trustCache.getOrPut(pubkey) {
                                    pubkey == ownPubkey ||
                                        pubkey in followedPubkeys ||
                                        memoryEventStore.wotFor(pubkey) is WotLookup.Scored
                                }
                            },
                        ),
                    )
                }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collectLatestWhileActive(screenActive) { _uiState.value = it }
        }
    }

    // ── Per-event stats (reactive counts for thread cards) ─────────────
    fun statsFlow(eventId: String): CardDataFlow<EventStats> =
        timelineCardData.statsFlow(eventId)

    // ── Engagement drawer data (contributor indexes) ─────────────────────
    fun zapDetailsForEvent(eventId: String): List<ZapDetail> =
        timelineCardData.zapDetailsForEvent(eventId)
    fun repostPubkeysForEvent(eventId: String): List<String> =
        timelineCardData.repostPubkeysForEvent(eventId)
    fun reactionsForEvent(eventId: String): List<ReactionInfo> =
        timelineCardData.reactionsForEvent(eventId)

    // ── Profile flow (reactive avatar/name for drawer chips) ─────────────

    fun profileFlow(pubkey: String): CardDataFlow<UserEntity?> =
        timelineCardData.profileFlow(pubkey)

    /** Wipe stale state so next open doesn't flash old content. */
    fun clearThread() {
        screenActive.value = false
        retainRoot(null)
        loadJob?.cancel()
        loadJob = null
        eventIdFlow.value = null
        articleCommentRelays = emptyList()
        threadRelayUrls = emptyList()
        completedThreadQueries.clear()
        fetchedArticleCoords.clear()
        fetchedReplyDescendants.clear()
        fetchedMissingParents.clear()
        tappedId = null
        _wotSubjects.value = emptySet()
        _baseUiState.value = ThreadUiState()
        _uiState.value = ThreadUiState()
    }

    /**
     * Point the thread at [rootId]. Supported addressable content switches to
     * coordinate-aware comments; anything else uses the standard #e thread fetch.
     */
    private suspend fun applyRoot(rootId: String, urls: List<String>) {
        retainRoot(rootId.takeIf { screenActive.value })
        threadRelayUrls = urls
        if (eventIdFlow.value != rootId) {
            completedThreadQueries.clear()
            fetchedArticleCoords.clear()
            fetchedReplyDescendants.clear()
            fetchedMissingParents.clear()
        }
        val rootEvent = memoryEventStore.getEventEntity(rootId)
        val coord = if (rootEvent?.kind in setOf(30023, 34235, 34236)) {
            memoryEventStore.articleCoordForEvent(rootId)
        } else null
        articleCommentRelays = (urls +
            (memoryEventStore.getNostrEvent(rootId)?.relaysSeen ?: emptySet()) +
            memoryEventStore.relayHintsForEvent(rootId)).distinct()
        if (coord != null) {
            memoryEventStore.registerArticleCoord(rootId, coord)
        }
        // Set last: the reactive collector observes a fully registered coordinate
        // and complete relay set on its first emission.
        eventIdFlow.value = rootId
    }

    fun loadThread(eventId: String, relayHints: List<String> = emptyList()) {
        setScreenActive(true)
        // A saved navigation entry is resumed, not loaded as a new visit. Preserve
        // its refined root and reply focus, and avoid another ancestor walk.
        if (tappedId == eventId && (eventIdFlow.value != null || loadJob?.isActive == true)) return
        loadJob?.cancel()
        tappedId = eventId
        // Protect the cold entry before its first hinted lookup, not only after
        // applyRoot. It will be replaced by the resolved conversation root.
        retainRoot(eventId)
        loadJob = viewModelScope.launch {
            val ownPubkey = pubkeyHex ?: ""
            val ownReadRelays = memoryEventStore.readRelaysFor(ownPubkey)
            val blockedRelays = memoryEventStore.getBlockedRelayUrls(ownPubkey).toSet()

            // External nevent hints are load-bearing when the event is absent from
            // MES: fetchEventById can open an ephemeral hinted connection, while the
            // broader thread fetch below only uses already-pooled connections.
            var event = memoryEventStore.getEventEntity(eventId)
            val effectiveHints = boundedSeenRelayHints(
                seenRelays = relayHints +
                    memoryEventStore.getNostrEvent(eventId)?.relaysSeen.orEmpty(),
                browseRelays = relayPool.activeFeedRelayHints(),
                additionalRelays = memoryEventStore.relayHintsForEvent(eventId),
            )
            if (event == null && effectiveHints.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    relayPool.fetchEventById(eventId, effectiveHints, bypassDedup = true)
                }
                event = memoryEventStore.getEventEntity(eventId)
            }

            // Best-guess root from local MES — instant for normal in-app taps.
            val bestGuessRoot = event?.rootId ?: event?.replyToId ?: eventId

            // Resolve relays based on the thread root's author. If we don't
            // have the event yet (cold tap), fall back to own read + GLOBAL.
            val rootAuthorPubkey = event?.pubkey
                ?: memoryEventStore.getEventEntity(bestGuessRoot)?.pubkey

            val resolvedUrls = if (rootAuthorPubkey != null) {
                (
                    memoryEventStore.lookupWriteRelaysFor(rootAuthorPubkey) +
                        outboxResolver.resolveEngagementRelays(
                            authorPubkey = rootAuthorPubkey,
                            ownReadRelays = ownReadRelays,
                            blockedRelays = blockedRelays,
                        )
                    ).distinct()
            } else {
                ownReadRelays.ifEmpty { GLOBAL_RELAY_URLS }
            }
            val urls = (effectiveHints + resolvedUrls).distinct()

            if (eventIdFlow.value == bestGuessRoot) {
                retainRoot(bestGuessRoot.takeIf { screenActive.value })
                return@launch
            }

            // Clear stale state only after confirming this is a different thread.
            // Clearing before the same-root early return strands the drawer in Loading.
            _baseUiState.value = ThreadUiState(loading = true)
            _uiState.value = ThreadUiState(loading = true)

            applyRoot(bestGuessRoot, urls)

            // Refine root in background — walk UP the reply chain fetching ancestors
            launch {
                val trueRoot = resolveThreadRoot(
                    startId = eventId,
                    initialHints = effectiveHints,
                    fallbackRelays = resolvedUrls,
                )
                if (trueRoot != bestGuessRoot && eventIdFlow.value == bestGuessRoot) {
                    // Re-resolve relays for the true root's author — may differ
                    // from the tapped event's author.
                    val trueRootEvent = memoryEventStore.getEventEntity(trueRoot)
                    val trueRootAuthor = trueRootEvent?.pubkey
                    val refinedUrls = if (trueRootAuthor != null && trueRootAuthor != rootAuthorPubkey) {
                        (
                            memoryEventStore.lookupWriteRelaysFor(trueRootAuthor) +
                                outboxResolver.resolveEngagementRelays(
                                    authorPubkey = trueRootAuthor,
                                    ownReadRelays = ownReadRelays,
                                    blockedRelays = blockedRelays,
                                )
                            ).distinct()
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
    private suspend fun resolveThreadRoot(
        startId: String,
        initialHints: List<String>,
        fallbackRelays: List<String>,
    ): String {
        var currentHints = initialHints
        return walkThreadAncestors(
            startId = startId,
            lookup = { id ->
                memoryEventStore.getEventEntity(id) ?: eventReferenceResolver.lookup(
                    com.unsilence.app.ui.feed.EventReferenceTarget(
                        eventId = id,
                        address = null,
                        authorPubkey = null,
                        relayHints = currentHints,
                    ),
                    fallbackRelays = fallbackRelays,
                )
            },
            onParent = { currentId, parentId ->
                currentHints = nextAncestorRelayHints(
                    sourceRelaysSeen = memoryEventStore.getNostrEvent(currentId)?.relaysSeen.orEmpty(),
                    browseRelays = relayPool.activeFeedRelayHints(),
                    parentRelayHints = memoryEventStore.relayHintsForEvent(parentId),
                    initialHints = initialHints,
                )
            },
        )
    }

}
