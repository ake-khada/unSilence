package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "coverage",
    indices = [
        Index("scope_type", "scope_key"),
        Index("last_attempt_at"),
    ],
)
data class CoverageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "scope_type")
    val scopeType: String,

    @ColumnInfo(name = "scope_key")
    val scopeKey: String,

    @ColumnInfo(name = "since_ts", defaultValue = "0")
    val sinceTs: Long = 0L,

    @ColumnInfo(name = "until_ts", defaultValue = "0")
    val untilTs: Long = 0L,

    @ColumnInfo(name = "relay_set_id")
    val relaySetId: String,

    @ColumnInfo(name = "status", defaultValue = "pending")
    val status: String = "pending",

    @ColumnInfo(name = "eose_count", defaultValue = "0")
    val eoseCount: Int = 0,

    @ColumnInfo(name = "expected_relays", defaultValue = "0")
    val expectedRelays: Int = 0,

    @ColumnInfo(name = "oldest_seen_ts", defaultValue = "0")
    val oldestSeenTs: Long = 0L,

    @ColumnInfo(name = "newest_seen_ts", defaultValue = "0")
    val newestSeenTs: Long = 0L,

    @ColumnInfo(name = "last_attempt_at", defaultValue = "0")
    val lastAttemptAt: Long = 0L,

    @ColumnInfo(name = "last_success_at", defaultValue = "0")
    val lastSuccessAt: Long = 0L,

    @ColumnInfo(name = "stale_after_ms", defaultValue = "300000")
    val staleAfterMs: Long = 300_000L,
)
