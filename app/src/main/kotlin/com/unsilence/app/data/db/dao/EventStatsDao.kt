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
}
