package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [
        Index("relay_url", "kind", "created_at"),
        Index("reply_to_id", "kind"),
        Index("root_id", "kind"),
        Index("root_id", "created_at"),
        Index("pubkey", "kind", "created_at"),
        Index("created_at"),
    ],
)
data class EventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "pubkey")
    val pubkey: String,

    @ColumnInfo(name = "kind")
    val kind: Int,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** JSON-serialized List<List<String>> */
    @ColumnInfo(name = "tags")
    val tags: String,

    @ColumnInfo(name = "sig")
    val sig: String,

    /** Which relay delivered this event first */
    @ColumnInfo(name = "relay_url")
    val relayUrl: String,

    /** NIP-10: id of the direct parent (reply target) */
    @ColumnInfo(name = "reply_to_id")
    val replyToId: String? = null,

    /** NIP-10: id of the thread root */
    @ColumnInfo(name = "root_id")
    val rootId: String? = null,

    /** NIP-36: true if event has a content-warning tag */
    @ColumnInfo(name = "has_content_warning")
    val hasContentWarning: Boolean = false,

    @ColumnInfo(name = "content_warning_reason")
    val contentWarningReason: String? = null,

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long,

    /** Aggregated zap amount in sats from kind-9735 receipts */
    @ColumnInfo(name = "zap_total_sats")
    val zapTotalSats: Long = 0,
)
