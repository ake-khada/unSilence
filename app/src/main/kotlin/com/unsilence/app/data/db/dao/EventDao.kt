package com.unsilence.app.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unsilence.app.data.db.entity.EventEntity
import kotlinx.coroutines.flow.Flow

/** Flattened result of the feed query (event + author fields + engagement counts). */
data class FeedRow(
    // ── Event ──────────────────────────────────────────────
    @ColumnInfo(name = "id")                    val id: String,
    @ColumnInfo(name = "pubkey")                val pubkey: String,
    @ColumnInfo(name = "kind")                  val kind: Int,
    @ColumnInfo(name = "content")               val content: String,
    @ColumnInfo(name = "created_at")            val createdAt: Long,
    @ColumnInfo(name = "tags")                  val tags: String,
    @ColumnInfo(name = "relay_url")             val relayUrl: String,
    @ColumnInfo(name = "reply_to_id")           val replyToId: String?,
    @ColumnInfo(name = "root_id")               val rootId: String?,
    @ColumnInfo(name = "has_content_warning")   val hasContentWarning: Boolean,
    @ColumnInfo(name = "content_warning_reason") val contentWarningReason: String?,
    @ColumnInfo(name = "cached_at")             val cachedAt: Long,
    // ── Author (may be null until profile arrives) ─────────
    @ColumnInfo(name = "author_name")           val authorName: String?,
    @ColumnInfo(name = "author_display_name")   val authorDisplayName: String?,
    @ColumnInfo(name = "author_picture")        val authorPicture: String?,
    // ── Engagement ─────────────────────────────────────────
    @ColumnInfo(name = "reaction_count")        val reactionCount: Int,
    @ColumnInfo(name = "reply_count")           val replyCount: Int,
)

@Dao
interface EventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(event: EventEntity)

    /**
     * Feed query: events from [relayUrls], filtered by kind, content type (notes only by default),
     * with reaction/reply counts, ordered newest-first.
     *
     * NOTES_ONLY = reply_to_id IS NULL AND root_id IS NULL.
     * Engagement filter applied via HAVING.
     */
    @Query("""
        SELECT
            e.id,
            e.pubkey,
            e.kind,
            e.content,
            e.created_at,
            e.tags,
            e.relay_url,
            e.reply_to_id,
            e.root_id,
            e.has_content_warning,
            e.content_warning_reason,
            e.cached_at,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            COUNT(DISTINCT r.event_id)              AS reaction_count,
            COUNT(DISTINCT rep.id)                  AS reply_count
        FROM events e
        LEFT JOIN users     u   ON u.pubkey        = e.pubkey
        LEFT JOIN reactions r   ON r.target_event_id = e.id
        LEFT JOIN events    rep ON rep.reply_to_id  = e.id
        WHERE e.relay_url IN (:relayUrls)
          AND e.kind      IN (:kinds)
          AND e.reply_to_id IS NULL
          AND e.root_id     IS NULL
        GROUP BY e.id
        HAVING COUNT(DISTINCT r.event_id) >= :minReactions
        ORDER BY e.created_at DESC
        LIMIT 300
    """)
    fun feedFlow(
        relayUrls: List<String>,
        kinds: List<Int>,
        minReactions: Int,
    ): Flow<List<FeedRow>>

    /**
     * Following feed: top-level kind 1/6/20/21 events from followed pubkeys only.
     * Uses an INNER JOIN on the follows table — no relay URL filter (events may have
     * arrived from any relay) and no minReactions threshold (see everything from follows).
     */
    @Query("""
        SELECT
            e.id,
            e.pubkey,
            e.kind,
            e.content,
            e.created_at,
            e.tags,
            e.relay_url,
            e.reply_to_id,
            e.root_id,
            e.has_content_warning,
            e.content_warning_reason,
            e.cached_at,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            COUNT(DISTINCT r.event_id)  AS reaction_count,
            COUNT(DISTINCT rep.id)      AS reply_count
        FROM events e
        INNER JOIN follows     f   ON f.pubkey          = e.pubkey
        LEFT JOIN  users       u   ON u.pubkey           = e.pubkey
        LEFT JOIN  reactions   r   ON r.target_event_id  = e.id
        LEFT JOIN  events      rep ON rep.reply_to_id    = e.id
        WHERE e.kind        IN (1, 6, 20, 21)
          AND e.reply_to_id IS NULL
          AND e.root_id     IS NULL
        GROUP BY e.id
        ORDER BY e.created_at DESC
        LIMIT 300
    """)
    fun followingFeedFlow(): Flow<List<FeedRow>>

    /** Top-level posts by a single author, newest-first. Used by the profile screen. */
    @Query("""
        SELECT
            e.id,
            e.pubkey,
            e.kind,
            e.content,
            e.created_at,
            e.tags,
            e.relay_url,
            e.reply_to_id,
            e.root_id,
            e.has_content_warning,
            e.content_warning_reason,
            e.cached_at,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            COUNT(DISTINCT r.event_id)  AS reaction_count,
            COUNT(DISTINCT rep.id)      AS reply_count
        FROM events e
        LEFT JOIN users     u   ON u.pubkey         = e.pubkey
        LEFT JOIN reactions r   ON r.target_event_id = e.id
        LEFT JOIN events    rep ON rep.reply_to_id   = e.id
        WHERE e.pubkey        = :pubkey
          AND e.kind          = 1
          AND e.reply_to_id  IS NULL
          AND e.root_id      IS NULL
        GROUP BY e.id
        ORDER BY e.created_at DESC
        LIMIT 100
    """)
    fun userPostsFlow(pubkey: String): Flow<List<FeedRow>>

    /** All events for thread view (includes replies). */
    @Query("""
        SELECT
            e.id, e.pubkey, e.kind, e.content, e.created_at, e.tags, e.relay_url,
            e.reply_to_id, e.root_id, e.has_content_warning, e.content_warning_reason, e.cached_at,
            u.name AS author_name, u.display_name AS author_display_name, u.picture AS author_picture,
            COUNT(DISTINCT r.event_id) AS reaction_count,
            COUNT(DISTINCT rep.id)     AS reply_count
        FROM events e
        LEFT JOIN users     u   ON u.pubkey        = e.pubkey
        LEFT JOIN reactions r   ON r.target_event_id = e.id
        LEFT JOIN events    rep ON rep.reply_to_id  = e.id
        WHERE e.id = :eventId OR e.reply_to_id = :eventId OR e.root_id = :eventId
        GROUP BY e.id
        ORDER BY e.created_at ASC
    """)
    fun threadFlow(eventId: String): Flow<List<FeedRow>>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    /** Prune expired events (NIP-40). Called periodically. */
    @Query("""
        DELETE FROM events
        WHERE id IN (
            SELECT e.id FROM events e
            WHERE e.tags LIKE '%expiration%'
              AND CAST(
                    json_extract(e.tags, '$[*][1]') AS INTEGER
                  ) < :nowSeconds
        )
    """)
    suspend fun pruneExpired(nowSeconds: Long)
}
