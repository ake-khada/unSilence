package com.unsilence.app.data.repository

import com.unsilence.app.data.db.dao.EventDao
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.dao.ReactionDao
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.db.entity.ReactionEntity
import com.unsilence.app.domain.model.FeedFilter
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventRepository @Inject constructor(
    private val eventDao: EventDao,
    private val reactionDao: ReactionDao,
) {
    /**
     * Live feed from Room — UI subscribes to this.
     * Automatically re-emits whenever new events or reactions are inserted.
     */
    fun feedFlow(relayUrls: List<String>, filter: FeedFilter): Flow<List<FeedRow>> {
        val sinceTimestamp = filter.sinceHours?.let {
            System.currentTimeMillis() / 1000L - it * 3600L
        } ?: 0L
        return eventDao.feedFlow(
            relayUrls        = relayUrls,
            kinds            = filter.enabledKinds,
            sinceTimestamp   = sinceTimestamp,
            requireReposts   = if (filter.requireReposts)   1 else 0,
            requireReactions = if (filter.requireReactions) 1 else 0,
            requireReplies   = if (filter.requireReplies)   1 else 0,
            requireZaps      = if (filter.requireZaps)      1 else 0,
        )
    }

    fun threadFlow(eventId: String): Flow<List<FeedRow>> =
        eventDao.threadFlow(eventId)

    fun followingFeedFlow(): Flow<List<FeedRow>> =
        eventDao.followingFeedFlow()

    fun userPostsFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>> =
        eventDao.userPostsFlow(pubkey, limit)

    /** Optimistic insert for locally-authored events. */
    suspend fun insertEvent(entity: EventEntity) =
        eventDao.insertOrIgnore(entity)

    /** Optimistic insert for locally-authored reactions. */
    suspend fun insertReaction(entity: ReactionEntity) =
        reactionDao.insertOrIgnore(entity)

    /** Fetch a single event by ID (used to reconstruct JSON for repost content). */
    suspend fun getEventById(id: String): EventEntity? =
        eventDao.getEventById(id)

    /** All event IDs the given pubkey has reacted to. Room-backed reactive flow. */
    fun reactedEventIds(pubkey: String): Flow<List<String>> =
        reactionDao.reactedEventIds(pubkey)

    /** All event IDs the given pubkey has reposted. Room-backed reactive flow. */
    fun repostedEventIds(pubkey: String): Flow<List<String>> =
        eventDao.repostedEventIds(pubkey)

    /** All event IDs the given pubkey has zapped. Room-backed reactive flow. */
    fun zappedEventIds(pubkey: String): Flow<List<String>> =
        eventDao.zappedEventIds(pubkey)

    /** NIP-50 content search — re-emits as search results arrive from the relay. */
    fun searchNotes(query: String): Flow<List<FeedRow>> =
        eventDao.searchNotes(query)

    suspend fun pruneExpired() =
        eventDao.pruneExpired(System.currentTimeMillis() / 1000L)
}
