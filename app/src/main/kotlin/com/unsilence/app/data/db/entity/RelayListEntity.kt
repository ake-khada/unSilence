package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * NIP-65 relay list metadata (kind 10002) for a single pubkey.
 * Stores only write relays — these are the relays we connect to when
 * fetching that user's posts for the Following feed.
 */
@Entity(tableName = "relay_list_metadata")
data class RelayListEntity(
    @PrimaryKey
    @ColumnInfo(name = "pubkey")
    val pubkey: String,

    /** JSON-encoded List<String> of write relay URLs. */
    @ColumnInfo(name = "write_relays")
    val writeRelays: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
