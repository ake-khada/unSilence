package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per followed pubkey for the logged-in user.
 * The entire table is replaced atomically when a new kind 3 event arrives (NIP-02).
 */
@Entity(tableName = "follows")
data class FollowEntity(
    @PrimaryKey
    @ColumnInfo(name = "pubkey")
    val pubkey: String,

    /** Timestamp from the kind 3 event that established this follow. */
    @ColumnInfo(name = "followed_at")
    val followedAt: Long,
)
