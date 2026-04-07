package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "pinned_relays",
    primaryKeys = ["pubkey", "url"],
    indices = [Index("pubkey")],
)
data class PinnedRelayEntity(
    @ColumnInfo(name = "pubkey")
    val pubkey: String,

    @ColumnInfo(name = "url")
    val url: String,

    @ColumnInfo(name = "display_label")
    val displayLabel: String?,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis() / 1000,
)
