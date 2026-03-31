package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pinned_relays")
data class PinnedRelayEntity(
    @PrimaryKey
    @ColumnInfo(name = "relay_url")
    val relayUrl: String,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "added_at", defaultValue = "0")
    val addedAt: Long = System.currentTimeMillis() / 1000,
)
