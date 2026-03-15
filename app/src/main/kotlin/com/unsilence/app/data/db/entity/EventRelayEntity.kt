package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "event_relays", primaryKeys = ["event_id", "relay_url"])
data class EventRelayEntity(
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "relay_url") val relayUrl: String,
    @ColumnInfo(name = "seen_at") val seenAt: Long,
)
