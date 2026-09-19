package com.unsilence.app.ui.feed

import com.unsilence.app.ui.shared.CardDataFlow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.ReactionInfo
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.WotHydrationCoalescer
import com.unsilence.app.data.relay.wotLookupSnapshot
import com.unsilence.app.ui.shared.TimelineCardData
import com.unsilence.app.ui.shared.ModeratedReplyRow
import com.unsilence.app.ui.shared.markLikelyCoordinatedSpam
import com.unsilence.app.ui.shared.mutedTimelineRowIds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class ArticleCommentsState(
    val rows: List<FeedRow> = emptyList(),
    val mutedIds: Set<String> = emptySet(),
    val depthRows: List<ModeratedReplyRow> = emptyList(),
)

internal data class ArticleContent(val row: FeedRow, val model: EventModel)

/**
 * Owns the article reader's COMMENT machinery: fetch comments by the article's
 * a-coordinate (NIP-22 kind-1111 + legacy kind-1), expose them as a flow, and
 * hydrate engagement for the rendered comment cards. Also provides the
 * display-side providers (profile/stats/contributors) the comment EventCards
 * need — all MES-backed, so comments render uniformly regardless of which screen
 * opened the reader (the host's per-screen VM isn't reused for comment cards).
 * Comment ACTIONS/lookups/caches come from a NoteActionsViewModel in the reader.
 */
@HiltViewModel
class ArticleReaderViewModel @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val keyManager: KeyManager,
    private val relayPool: RelayPool,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val cardHydrator: CardHydrator,
    private val timelineCardData: TimelineCardData,
    private val wotHydrationCoalescer: WotHydrationCoalescer,
    private val initGate: InitGate,
) : ViewModel() {

    val snapshotReady: Boolean get() = initGate.snapshotReady

    internal fun cachedArticle(eventId: String): ArticleContent? {
        val row = memoryEventStore.feedRowsByIds(setOf(eventId)).firstOrNull() ?: return null
        val model = memoryEventStore.getEventModel(eventId)?.takeIf { it.article != null } ?: return null
        return ArticleContent(row, model)
    }

    internal fun articleFlow(eventId: String): Flow<ArticleContent?> =
        memoryEventStore.eventEntityFlow(eventId).map {
            // Parsing remains on Default; only trusted MES payloads can become a reader.
            memoryEventStore.getOrParseEventModel(eventId)
            cachedArticle(eventId)
        }.flowOn(Dispatchers.Default)

    suspend fun restoreArticle(eventId: String, relayHints: List<String>) {
        initGate.awaitSnapshot()
        if (memoryEventStore.getNostrEvent(eventId) == null) {
            relayPool.fetchEventById(eventId, relayHints)
        }
    }

    /** NIP-36 sensitive-content display mode (shared with feed). */
    val sensitiveContentMode: StateFlow<com.unsilence.app.data.memory.SensitiveContentMode> =
        relayPreferencesStore.sensitiveContentMode

    private val _wotSubjects = MutableStateFlow<Set<String>>(emptySet())
    val wotLookups: StateFlow<Map<String, WotLookup>> =
        combine(_wotSubjects, memoryEventStore.wotSignalFlow) { subjects, _ ->
            wotLookupSnapshot(subjects, memoryEventStore::wotFor)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val feedWotDisplayMode: StateFlow<FeedWotDisplayMode> =
        relayPreferencesStore.feedWotDisplayModeFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), FeedWotDisplayMode.NUMBERS)

    /**
     * Comments for an article coordinate (oldest-first), with muted rows retained as placeholders.
     * A deep-linked comment remains visible even when its content matches a spam shape.
     */
    fun commentsFlow(
        coord: String,
        protectedEventIds: Set<String> = emptySet(),
    ): Flow<ArticleCommentsState> =
        combine(
            memoryEventStore.articleCommentsFlow(coord),
            memoryEventStore.ownMuteListFlow(),
            memoryEventStore.wotSignalFlow,
            memoryEventStore.followsSignalFlow,
        ) { rows, muteList, _, _ ->
            val mutedIds = mutedTimelineRowIds(
                rows = rows,
                muteList = muteList,
                eventProvider = memoryEventStore::getNostrEvent,
            )
            val ownPubkey = keyManager.getPublicKeyHex()
            val followedPubkeys = ownPubkey
                ?.let(memoryEventStore::getFollows)
                .orEmpty()
            val trustCache = HashMap<String, Boolean>()
            ArticleCommentsState(
                rows = rows,
                mutedIds = mutedIds,
                depthRows = markLikelyCoordinatedSpam(
                    rows = flattenArticleComments(rows, mutedIds = mutedIds),
                    protectedEventIds = protectedEventIds,
                    isTrustedAuthor = { pubkey ->
                        trustCache.getOrPut(pubkey) {
                            pubkey == ownPubkey ||
                                pubkey in followedPubkeys ||
                                memoryEventStore.wotFor(pubkey) is WotLookup.Scored
                        }
                    },
                ),
            )
        }.flowOn(Dispatchers.Default)

    /**
     * Fetch comments from the article's likely relays: author write relays, the
     * article's seen/hint relays, the rendered row's relay, and indexers — current
     * NIP-65 write relays alone may not cover where an old article's comments live.
     */
    @Volatile private var lastCommentCoord: String? = null

    suspend fun fetchComments(coord: String, articleId: String, authorPubkey: String, fallbackRelayUrl: String?) {
        if (coord.isBlank()) return
        // New article → reset the reply-fetch dedupe so a prior failed child fetch
        // retries on reopen.
        if (coord != lastCommentCoord) {
            lastCommentCoord = coord
            fetchedReplyParents.clear()
        }
        // Ensure MES knows id⇄coord in every entry point (quote/boost/search), so
        // replyCount merges #A comments + stats invalidations target the article id.
        if (articleId.isNotBlank()) memoryEventStore.registerArticleCoord(articleId, coord)
        relayPool.fetchArticleComments(articleRelays(authorPubkey, articleId, fallbackRelayUrl), coord)
    }

    /** Already-fetched comment ids (dedupe so the replies fetch can't loop). */
    private val fetchedReplyParents = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Staged fetch of replies-to-comments — descendants that carry no #a/#A tag
     *  and so aren't returned by fetchComments. Driven by the comment list. */
    suspend fun fetchCommentReplies(parentIds: List<String>, author: String, articleId: String, fallbackRelayUrl: String?) {
        val novel = parentIds.filter { fetchedReplyParents.add(it) }
        if (novel.isEmpty()) return
        try {
            relayPool.fetchCommentReplies(articleRelays(author, articleId, fallbackRelayUrl), novel)
        } catch (cancelled: CancellationException) {
            fetchedReplyParents.removeAll(novel.toSet())
            throw cancelled
        }
    }

    private fun articleRelays(author: String, articleId: String, fallbackRelayUrl: String?): List<String> =
        buildSet {
            addAll(memoryEventStore.writeRelaysFor(author))
            memoryEventStore.getNostrEvent(articleId)?.relaysSeen?.let { addAll(it) }
            addAll(memoryEventStore.relayHintsForEvent(articleId))
            fallbackRelayUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(relayPreferencesStore.indexerRelayUrlsSnapshot())
        }.toList()

    /** Hydrate engagement (reactions/zaps/reposts/replies) for rendered rows. */
    fun hydrateEngagement(rows: List<FeedRow>) {
        if (rows.isEmpty()) return
        cardHydrator.hydrateEngagement(rows, 0, rows.size - 1)
    }

    fun requestWotHydration(pubkeys: Collection<String>) {
        val subjects = pubkeys.toSet()
        _wotSubjects.update { current -> current + subjects }
        wotHydrationCoalescer.requestHydration(subjects)
    }

    // ── Display providers for comment EventCards (MES-backed, cached) ──────────

    fun profileFlow(pubkey: String): CardDataFlow<UserEntity?> =
        timelineCardData.profileFlow(pubkey)

    fun statsFlow(eventId: String): CardDataFlow<EventStats> =
        timelineCardData.statsFlow(eventId)

    fun zapDetailsForEvent(eventId: String): List<ZapDetail> = timelineCardData.zapDetailsForEvent(eventId)
    fun repostPubkeysForEvent(eventId: String): List<String> = timelineCardData.repostPubkeysForEvent(eventId)
    fun reactionsForEvent(eventId: String): List<ReactionInfo> = timelineCardData.reactionsForEvent(eventId)
}
