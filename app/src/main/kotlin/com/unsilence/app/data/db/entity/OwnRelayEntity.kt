package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "own_relays")
data class OwnRelayEntity(
    @PrimaryKey
    @ColumnInfo(name = "url")
    val url: String,

    @ColumnInfo(name = "read")
    val read: Boolean = true,

    @ColumnInfo(name = "write")
    val write: Boolean = true,

    /** kind-10002 created_at epoch seconds — used for replaceable event semantics. */
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = 0L,
)
