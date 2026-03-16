package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Kind 30002 (NIP-51) parameterized replaceable relay sets. */
@Entity(
    tableName = "nostr_relay_sets",
    indices = [
        Index(value = ["d_tag", "owner_pubkey"], unique = true),
    ],
)
data class NostrRelaySetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "d_tag")
    val dTag: String,

    @ColumnInfo(name = "owner_pubkey", defaultValue = "")
    val ownerPubkey: String = "",

    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "image")
    val image: String? = null,

    @ColumnInfo(name = "event_created_at", defaultValue = "0")
    val eventCreatedAt: Long = 0L,
)
