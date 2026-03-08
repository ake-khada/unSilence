package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reactions",
    indices = [Index("target_event_id")],
)
data class ReactionEntity(
    /** The kind 7 event id */
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,

    @ColumnInfo(name = "target_event_id")
    val targetEventId: String,

    @ColumnInfo(name = "pubkey")
    val pubkey: String,

    /** "+" / "-" / emoji / ":shortcode:" */
    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
