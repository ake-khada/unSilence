package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    @ColumnInfo(name = "pubkey")
    val pubkey: String,

    @ColumnInfo(name = "name")
    val name: String? = null,

    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    @ColumnInfo(name = "about")
    val about: String? = null,

    @ColumnInfo(name = "picture")
    val picture: String? = null,

    @ColumnInfo(name = "nip05")
    val nip05: String? = null,

    @ColumnInfo(name = "lud16")
    val lud16: String? = null,

    @ColumnInfo(name = "banner")
    val banner: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "follower_count")
    val followerCount: Long? = null,

    @ColumnInfo(name = "follower_count_updated_at")
    val followerCountUpdatedAt: Long? = null,
)
