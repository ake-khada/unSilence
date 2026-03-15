package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "tags", primaryKeys = ["event_id", "tag_name", "tag_pos"])
data class TagEntity(
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "tag_name") val tagName: String,
    @ColumnInfo(name = "tag_pos") val tagPos: Int,
    @ColumnInfo(name = "tag_value") val tagValue: String,
    val extra: String? = null,
)
