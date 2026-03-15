package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "event_stats")
data class EventStatsEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "reply_count") val replyCount: Int = 0,
    @ColumnInfo(name = "repost_count") val repostCount: Int = 0,
    @ColumnInfo(name = "reaction_count") val reactionCount: Int = 0,
    @ColumnInfo(name = "zap_count") val zapCount: Int = 0,
    @ColumnInfo(name = "zap_total_sats") val zapTotalSats: Long = 0,
)
