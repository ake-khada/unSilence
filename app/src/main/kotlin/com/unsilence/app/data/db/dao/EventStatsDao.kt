package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unsilence.app.data.db.entity.EventStatsEntity

@Dao
abstract class EventStatsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertOrIgnore(stats: EventStatsEntity)

    @Query("UPDATE event_stats SET reply_count = reply_count + 1 WHERE event_id = :eventId")
    abstract suspend fun incrementReplyCountInternal(eventId: String)

    @Transaction
    open suspend fun incrementReplyCount(eventId: String) {
        insertOrIgnore(EventStatsEntity(eventId = eventId))
        incrementReplyCountInternal(eventId)
    }

    @Query("UPDATE event_stats SET repost_count = repost_count + 1 WHERE event_id = :eventId")
    abstract suspend fun incrementRepostCountInternal(eventId: String)

    @Transaction
    open suspend fun incrementRepostCount(eventId: String) {
        insertOrIgnore(EventStatsEntity(eventId = eventId))
        incrementRepostCountInternal(eventId)
    }

    @Query("UPDATE event_stats SET reaction_count = reaction_count + 1 WHERE event_id = :eventId")
    abstract suspend fun incrementReactionCountInternal(eventId: String)

    @Transaction
    open suspend fun incrementReactionCount(eventId: String) {
        insertOrIgnore(EventStatsEntity(eventId = eventId))
        incrementReactionCountInternal(eventId)
    }

    @Query("UPDATE event_stats SET zap_count = zap_count + 1, zap_total_sats = zap_total_sats + :sats WHERE event_id = :eventId")
    abstract suspend fun incrementZapStatsInternal(eventId: String, sats: Long)

    @Transaction
    open suspend fun incrementZapStats(eventId: String, sats: Long) {
        insertOrIgnore(EventStatsEntity(eventId = eventId))
        incrementZapStatsInternal(eventId, sats)
    }

    /**
     * Batch all stat updates into ONE Room transaction.
     * This triggers a single Room Flow re-emission instead of N separate ones.
     * Each pair is (eventId, updateType) where updateType is the stat to increment.
     */
    @Transaction
    open suspend fun batchIncrementStats(
        replyTargets: List<String>,
        repostTargets: List<String>,
        reactionTargets: List<String>,
        zapTargets: List<Pair<String, Long>>,
    ) {
        // Ensure rows exist for all targets
        val allIds = replyTargets + repostTargets + reactionTargets + zapTargets.map { it.first }
        for (id in allIds.distinct()) {
            insertOrIgnore(EventStatsEntity(eventId = id))
        }
        // Increment stats
        for (id in replyTargets) incrementReplyCountInternal(id)
        for (id in repostTargets) incrementRepostCountInternal(id)
        for (id in reactionTargets) incrementReactionCountInternal(id)
        for ((id, sats) in zapTargets) incrementZapStatsInternal(id, sats)
    }
}
