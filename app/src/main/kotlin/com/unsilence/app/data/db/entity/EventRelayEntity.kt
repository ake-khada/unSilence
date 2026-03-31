package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "event_relays",
    primaryKeys = ["event_id", "relay_url"],
    indices = [
        Index(value = ["relay_url", "seen_at", "event_id"], name = "idx_event_relays_by_relay"),
    ],
)
data class EventRelayEntity(
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "relay_url") val relayUrl: String,
    @ColumnInfo(name = "seen_at") val seenAt: Long,
)
