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
import com.unsilence.app.data.relay.bridgeFallbackRelayTargets
import com.unsilence.app.data.relay.boundedSeenRelayHints
import com.unsilence.app.data.relay.relayResolutionTargets
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
import kotlinx.coroutines.flow.distinctUntilChanged
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

/**
 * Coordinate queries prove every supplied row belongs to the same thread. If a
 * relay returns a child before its parent, attach that orphan to the root until
 * the missing parent arrives instead of counting a comment that cannot render.
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
        coordinateScoped &&
        parentId != focusedId &&
        parentId !in availableReplyIds
    ) focusedId else parentId
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
    @Volatile private var articleCommentRelays: List<String> = emptyList()
    private val fetchedArticleCoords = ConcurrentHashMap.newKeySet<String>()
    private val fetchedReplyDescendants = ConcurrentHashMap.newKeySet<String>()
    private val fetchedMissingParents = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var tappedId: String? = null

    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    val userAvatarUrl: StateFlow<String?> = pubkeyHex?.let { pk ->
        userRepository.userFlow(pk)
            .map { it?.picture }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    init {
        viewModelScope.launch {
            eventIdFlow.filterNotNull()
                .flatMapLatest { id ->
                    // A repost target can arrive after the drawer opens. Re-evaluate
                    // its coordinate on every feed insert so the thread promotes from
                    // #e lookup to #A lookup without requiring a page-away/page-back.
                    memoryEventStore.feedSignalFlow
                        .map { id to memoryEventStore.articleCoordForEvent(id) }
                        .distinctUntilChanged()
                }
                .flatMapLatest { (id, coord) ->
                    if (coord != null) {
                        val relays = (
                            articleCommentRelays +
                                (memoryEventStore.getNostrEvent(id)?.relaysSeen ?: emptySet()) +
                                memoryEventStore.relayHintsForEvent(id)
                            ).distinct()
                        articleCommentRelays = relays
                        if (fetchedArticleCoords.add(coord) && relays.isNotEmpty()) {
                            viewModelScope.launch { relayPool.fetchArticleComments(relays, coord) }
                        }
                        memoryEventStore.articleCommentsFlow(coord).map { Triple(id, true, it) }
                    } else {
                        memoryEventStore.threadFeedRowFlow(id).map { Triple(id, false, it) }
                    }
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
                        rows.filter { it.id != focusedId && (it.kind == 1 || it.kind == 1111) }
                    }

                    // Comment cards need live engagement — hydrate, same as the reader.
                    // Also stage-fetch replies-to-comments (descendants with no #a/#A
                    // tag) so nested replies show; dedupe so it can't loop.
                    if (articleMode && rows.isNotEmpty()) {
                        cardHydrator.hydrateEngagement(rows, 0, rows.size - 1)
                        val rowIds = rows.mapTo(HashSet(rows.size)) { it.id }
                        val missingParents = rows.asSequence()
                            .mapNotNull { it.replyToId }
                            .filter { it != focusedId && it !in rowIds }
                            .filter { memoryEventStore.getEventEntity(it) == null }
                            .filter { fetchedMissingParents.add(it) }
                            .toList()
                        if (missingParents.isNotEmpty() && articleCommentRelays.isNotEmpty()) {
                            viewModelScope.launch {
                                relayPool.fetchCommentParents(articleCommentRelays, missingParents)
                            }
                        }

                        val novel = rows.map { it.id }.filter { fetchedReplyDescendants.add(it) }
                        if (novel.isNotEmpty() && articleCommentRelays.isNotEmpty()) {
                            viewModelScope.launch { relayPool.fetchCommentReplies(articleCommentRelays, novel) }
                        }
                    }

                    // Build parent→children map
                    val availableReplyIds = replyRows.mapTo(HashSet(replyRows.size)) { it.id }
                    val childrenOf = replyRows.groupBy { row ->
                        threadProjectionParentId(
                            replyToId = row.replyToId,
                            rootId = row.rootId,
                            focusedId = focusedId,
                            availableReplyIds = availableReplyIds,
                            coordinateScoped = articleMode,
                        )
                    }
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
        articleCommentRelays = emptyList()
        fetchedArticleCoords.clear()
        fetchedReplyDescendants.clear()
        fetchedMissingParents.clear()
        tappedId = null
        _wotSubjects.value = emptySet()
        _uiState.value = ThreadUiState()
    }

    /**
     * Point the thread at [rootId]. Supported addressable content switches to
     * coordinate-aware comments; anything else uses the standard #e thread fetch.
     */
    private suspend fun applyRoot(rootId: String, urls: List<String>) {
        if (eventIdFlow.value != rootId) {
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
        } else {
            relayPool.fetchThread(urls, rootId)
        }
        // Set last: the reactive collector observes a fully registered coordinate
        // and complete relay set on its first emission.
        eventIdFlow.value = rootId
    }

    fun loadThread(eventId: String, relayHints: List<String> = emptyList()) {
        tappedId = eventId
        viewModelScope.launch {
            val ownPubkey = pubkeyHex ?: ""
            val ownReadRelays = memoryEventStore.getReadWriteRelayConfigs(ownPubkey)
                .filter { it.marker == null || it.marker == "read" }
                .mapNotNull { normalizeRelayUrl(it.url) }
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

            if (eventIdFlow.value == bestGuessRoot) return@launch

            // Clear stale state only after confirming this is a different thread.
            // Clearing before the same-root early return strands the drawer in Loading.
            _uiState.value = ThreadUiState(loading = true)

            applyRoot(bestGuessRoot, urls)

            // Refine root in background — walk UP the reply chain fetching ancestors
            launch {
                val trueRoot = withTimeoutOrNull(8_000) {
                    resolveThreadRoot(
                        startId = eventId,
                        initialHints = effectiveHints,
                        fallbackRelays = resolvedUrls,
                    )
                } ?: return@launch
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
        val visited = mutableSetOf<String>()
        var currentId = startId
        var currentHints = initialHints
        repeat(50) {                                       // hop cap — pathological guard
            if (!visited.add(currentId)) return currentId   // cycle guard
            val current = memoryEventStore.getEventEntity(currentId)
                ?: fetchAncestor(currentId, currentHints, fallbackRelays)
                ?: return currentId                        // unreachable — best-effort root
            val parentId = current.replyToId ?: current.rootId
            if (parentId == null || parentId == currentId) return currentId  // root
            currentHints = nextAncestorRelayHints(
                sourceRelaysSeen = memoryEventStore.getNostrEvent(currentId)?.relaysSeen.orEmpty(),
                browseRelays = relayPool.activeFeedRelayHints(),
                parentRelayHints = memoryEventStore.relayHintsForEvent(parentId),
                initialHints = initialHints,
            )
            currentId = parentId
        }
        return currentId
    }

    private suspend fun fetchAncestor(
        id: String,
        hints: List<String>,
        fallbackRelays: List<String>,
    ): EventEntity? {
        val targets = relayResolutionTargets(
            seenRelays = hints,
            browseRelays = relayPool.activeFeedRelayHints(),
            fallbackRelays = fallbackRelays,
        )
        if (targets.hints.isNotEmpty()) {
            withContext(Dispatchers.IO) { relayPool.fetchEventById(id, targets.hints) }
        }
        if (memoryEventStore.getEventEntity(id) == null && targets.fallback.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                relayPool.fetchEventsByIdsFromTargets(
                    eventIds = listOf(id),
                    targetRelayUrls = targets.fallback,
                    bypassDedup = true,
                    excludedRelayUrls = targets.hints,
                )
            }
        }
        if (memoryEventStore.getEventEntity(id) == null) {
            val bridgeTargets = bridgeFallbackRelayTargets(targets.all)
            if (bridgeTargets.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    relayPool.fetchEventsByIdsFromBridgeOnMiss(
                        eventIds = listOf(id),
                        alreadyTriedRelayUrls = targets.all,
                    )
                }
            }
        }
        return withTimeoutOrNull(3_000) {
            memoryEventStore.eventEntityFlow(id).filterNotNull().first()
        }
    }

}
