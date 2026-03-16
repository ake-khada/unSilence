package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Members of a kind 30002 (NIP-51) relay set, scoped by owner. */
@Entity(
    tableName = "nostr_relay_set_members",
    primaryKeys = ["set_d_tag", "owner_pubkey", "relay_url"],
    indices = [Index("set_d_tag")],
)
data class NostrRelaySetMemberEntity(
    @ColumnInfo(name = "set_d_tag")
    val setDTag: String,

    @ColumnInfo(name = "owner_pubkey", defaultValue = "")
    val ownerPubkey: String = "",

    @ColumnInfo(name = "relay_url")
    val relayUrl: String,
)
