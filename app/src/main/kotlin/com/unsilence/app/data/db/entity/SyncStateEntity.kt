package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "subscription_key") val subscriptionKey: String,
    @ColumnInfo(name = "last_sync_at")     val lastSyncAt: Long,
    @ColumnInfo(name = "last_event_count") val lastEventCount: Int,
    @ColumnInfo(name = "source")           val source: String,
)
