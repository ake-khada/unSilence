package com.unsilence.app.data.repository

import com.unsilence.app.data.db.dao.EventDao
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.domain.model.FeedFilter
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventRepository @Inject constructor(
    private val eventDao: EventDao,
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

    suspend fun pruneExpired() =
        eventDao.pruneExpired(System.currentTimeMillis() / 1000L)
}
