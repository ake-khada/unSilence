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
    fun feedFlow(relayUrls: List<String>, filter: FeedFilter): Flow<List<FeedRow>> =
        eventDao.feedFlow(
            relayUrls    = relayUrls,
            kinds        = filter.enabledKinds,
            minReactions = filter.minReactions,
        )

    fun threadFlow(eventId: String): Flow<List<FeedRow>> =
        eventDao.threadFlow(eventId)

    fun followingFeedFlow(): Flow<List<FeedRow>> =
        eventDao.followingFeedFlow()

    fun userPostsFlow(pubkey: String): Flow<List<FeedRow>> =
        eventDao.userPostsFlow(pubkey)

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

    suspend fun pruneExpired() =
        eventDao.pruneExpired(System.currentTimeMillis() / 1000L)
}
