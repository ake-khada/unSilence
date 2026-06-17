package com.unsilence.app.ui.feed

import androidx.collection.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.ReactionInfo
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val userRepository: UserRepository,
    private val relayPool: RelayPool,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val cardHydrator: CardHydrator,
) : ViewModel() {

    /** NIP-36 sensitive-content display mode (shared with feed). */
    val sensitiveContentMode: StateFlow<com.unsilence.app.data.memory.SensitiveContentMode> =
        relayPreferencesStore.sensitiveContentModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                com.unsilence.app.data.memory.SensitiveContentMode.BLUR)

    /** Comments for an article coordinate (oldest-first), live from MES. */
    fun commentsFlow(coord: String): Flow<List<FeedRow>> =
        memoryEventStore.articleCommentsFlow(coord)

    /**
     * Fetch comments from the article's likely relays: author write relays, the
     * article's seen/hint relays, the rendered row's relay, and indexers — current
     * NIP-65 write relays alone may not cover where an old article's comments live.
     */
    @Volatile private var lastCommentCoord: String? = null

    fun fetchComments(coord: String, articleId: String, authorPubkey: String, fallbackRelayUrl: String?) {
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
        viewModelScope.launch {
            relayPool.fetchArticleComments(articleRelays(authorPubkey, articleId, fallbackRelayUrl), coord)
        }
    }

    /** Already-fetched comment ids (dedupe so the replies fetch can't loop). */
    private val fetchedReplyParents = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Staged fetch of replies-to-comments — descendants that carry no #a/#A tag
     *  and so aren't returned by fetchComments. Driven by the comment list. */
    fun fetchCommentReplies(parentIds: List<String>, author: String, articleId: String, fallbackRelayUrl: String?) {
        val novel = parentIds.filter { fetchedReplyParents.add(it) }
        if (novel.isEmpty()) return
        viewModelScope.launch {
            relayPool.fetchCommentReplies(articleRelays(author, articleId, fallbackRelayUrl), novel)
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

    /** Hydrate engagement (reactions/zaps/reposts/replies) for the rendered
     *  comment rows, so comment cards don't show stale zero counts. */
    fun hydrateCommentEngagement(rows: List<FeedRow>) {
        if (rows.isEmpty()) return
        cardHydrator.hydrateEngagement(rows, 0, rows.size - 1)
    }

    // ── Quoted/embedded article resolution (for the canonical card) ───────────

    /** The article FeedRow for a coordinate, live from MES (null until resolved). */
    fun articleRowFlow(coord: String): Flow<FeedRow?> = memoryEventStore.articleRowByCoordFlow(coord)

    /** Fire-and-forget fetch of an absent article by coord, then hydrate its
     *  engagement so the embedded card's action bar is correct. No-op if cached. */
    fun ensureArticle(coord: String, author: String, dTag: String, hints: List<String>) {
        if (memoryEventStore.articleRowByCoord(coord) != null) {
            memoryEventStore.articleRowByCoord(coord)?.let { hydrateCommentEngagement(listOf(it)) }
            return
        }
        viewModelScope.launch {
            val relays = buildSet {
                addAll(hints)
                addAll(memoryEventStore.writeRelaysFor(author))
                addAll(relayPreferencesStore.indexerRelayUrlsSnapshot())
            }.toList()
            relayPool.fetchArticleByCoord(relays, author, dTag)
        }
    }

    // ── Display providers for comment EventCards (MES-backed, cached) ──────────

    private val profileCache = LruCache<String, StateFlow<UserEntity?>>(300)
    private val statsCache = LruCache<String, StateFlow<EventStats>>(300)

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        synchronized(profileCache) {
            profileCache.get(pubkey) ?: userRepository.userFlow(pubkey)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
                .also { profileCache.put(pubkey, it) }
        }

    fun statsFlow(eventId: String): StateFlow<EventStats> =
        synchronized(statsCache) {
            statsCache.get(eventId) ?: memoryEventStore.statsFlow(eventId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), memoryEventStore.currentStatsSnapshot(eventId))
                .also { statsCache.put(eventId, it) }
        }

    fun zapDetailsForEvent(eventId: String): List<ZapDetail> = memoryEventStore.zapDetailsForEvent(eventId)
    fun repostPubkeysForEvent(eventId: String): List<String> = memoryEventStore.repostPubkeysForEvent(eventId)
    fun reactionsForEvent(eventId: String): List<ReactionInfo> = memoryEventStore.reactionsForEvent(eventId)
}
