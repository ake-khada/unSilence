package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "relay_configs",
    indices = [
        Index("kind"),
        Index("owner_pubkey", "kind"),
    ],
)
data class RelayConfigEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "kind")
    val kind: Int,

    @ColumnInfo(name = "relay_url")
    val relayUrl: String,

    /** "read", "write", or null (both) — only meaningful for kind 10002. */
    @ColumnInfo(name = "marker")
    val marker: String? = null,

    /** For kind 10012 favorites: "a" tag reference like "30002:pubkey:d-tag". */
    @ColumnInfo(name = "set_ref")
    val setRef: String? = null,

    @ColumnInfo(name = "owner_pubkey", defaultValue = "")
    val ownerPubkey: String = "",

    @ColumnInfo(name = "event_created_at", defaultValue = "0")
    val eventCreatedAt: Long = 0L,
)
