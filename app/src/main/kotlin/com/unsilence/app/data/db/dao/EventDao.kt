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
    @ColumnInfo(name = "zap_total_sats")        val zapTotalSats: Long,
    // ── Author (may be null until profile arrives) ─────────
    @ColumnInfo(name = "author_name")           val authorName: String?,
    @ColumnInfo(name = "author_display_name")   val authorDisplayName: String?,
    @ColumnInfo(name = "author_picture")        val authorPicture: String?,
    @ColumnInfo(name = "author_nip05")          val authorNip05: String?,
    // ── Engagement ─────────────────────────────────────────
    @ColumnInfo(name = "reaction_count")        val reactionCount: Int,
    @ColumnInfo(name = "reply_count")           val replyCount: Int,
    @ColumnInfo(name = "repost_count")          val repostCount: Int,
    @ColumnInfo(name = "zap_count")             val zapCount: Int,
)

@Dao
interface EventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(event: EventEntity)

    /** Batch insert for the event pipeline. Room wraps the list insert in a single transaction. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnoreBatch(events: List<EventEntity>)

    /**
     * Feed query: events from [relayUrls], filtered by kind and time window,
     * with reaction/reply/repost counts, ordered newest-first.
     *
     * Top-level posts only: reply_to_id IS NULL AND root_id IS NULL.
     * Engagement filters applied via HAVING — each is opt-in (0 = skip check, 1 = require ≥ 1).
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
            e.zap_total_sats,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            u.nip05           AS author_nip05,
            COUNT(DISTINCT r.event_id)  AS reaction_count,
            COUNT(DISTINCT rep.id)      AS reply_count,
            COUNT(DISTINCT rp.id)       AS repost_count,
            COUNT(DISTINCT z.id)        AS zap_count
        FROM events e
        LEFT JOIN users     u   ON u.pubkey          = e.pubkey
        LEFT JOIN reactions r   ON r.target_event_id = e.id
        LEFT JOIN events    rep ON (rep.reply_to_id = e.id OR rep.root_id = e.id) AND rep.kind = 1
        LEFT JOIN events    rp  ON rp.root_id        = e.id AND rp.kind = 6
        LEFT JOIN events    z   ON z.root_id         = e.id AND z.kind  = 9735
        WHERE e.relay_url IN (:relayUrls)
          AND e.kind      IN (:kinds)
          AND ((e.reply_to_id IS NULL AND e.root_id IS NULL) OR e.kind = 6)
          AND (:sinceTimestamp = 0 OR e.created_at > :sinceTimestamp)
        GROUP BY e.id
        HAVING ((:requireReposts = 0 AND :requireReactions = 0 AND :requireReplies = 0 AND :requireZaps = 0)
            OR (:requireReposts   = 1 AND COUNT(DISTINCT rp.id)      >= 1)
            OR (:requireReactions = 1 AND COUNT(DISTINCT r.event_id) >= 1)
            OR (:requireReplies   = 1 AND COUNT(DISTINCT rep.id)     >= 1)
            OR (:requireZaps      = 1 AND COUNT(DISTINCT z.id)       >= 1))
        ORDER BY e.created_at DESC
        LIMIT :limit
    """)
    fun feedFlow(
        relayUrls: List<String>,
        kinds: List<Int>,
        sinceTimestamp: Long,
        requireReposts: Int,
        requireReactions: Int,
        requireReplies: Int,
        requireZaps: Int,
        limit: Int = 300,
    ): Flow<List<FeedRow>>

    /**
     * Following feed: top-level kind 1/6/20/21 events from followed pubkeys only.
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
            e.zap_total_sats,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            u.nip05           AS author_nip05,
            COUNT(DISTINCT r.event_id)  AS reaction_count,
            COUNT(DISTINCT rep.id)      AS reply_count,
            COUNT(DISTINCT rp.id)       AS repost_count,
            COUNT(DISTINCT z.id)        AS zap_count
        FROM events e
        INNER JOIN follows     f   ON f.pubkey          = e.pubkey
        LEFT JOIN  users       u   ON u.pubkey           = e.pubkey
        LEFT JOIN  reactions   r   ON r.target_event_id  = e.id
        LEFT JOIN  events      rep ON (rep.reply_to_id = e.id OR rep.root_id = e.id) AND rep.kind = 1
        LEFT JOIN  events      rp  ON rp.root_id         = e.id AND rp.kind = 6
        LEFT JOIN  events      z   ON z.root_id          = e.id AND z.kind  = 9735
        WHERE e.kind IN (1, 6, 20, 21, 30023)
          AND ((e.reply_to_id IS NULL AND e.root_id IS NULL) OR e.kind = 6)
        GROUP BY e.id
        ORDER BY e.created_at DESC
        LIMIT :limit
    """)
    fun followingFeedFlow(limit: Int = 300): Flow<List<FeedRow>>

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
            e.zap_total_sats,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            u.nip05           AS author_nip05,
            COUNT(DISTINCT r.event_id)  AS reaction_count,
            COUNT(DISTINCT rep.id)      AS reply_count,
            COUNT(DISTINCT rp.id)       AS repost_count,
            COUNT(DISTINCT z.id)        AS zap_count
        FROM events e
        LEFT JOIN users     u   ON u.pubkey          = e.pubkey
        LEFT JOIN reactions r   ON r.target_event_id = e.id
        LEFT JOIN events    rep ON (rep.reply_to_id = e.id OR rep.root_id = e.id) AND rep.kind = 1
        LEFT JOIN events    rp  ON rp.root_id        = e.id AND rp.kind = 6
        LEFT JOIN events    z   ON z.root_id         = e.id AND z.kind  = 9735
        WHERE e.pubkey = :pubkey
          AND ((e.kind = 1 AND e.reply_to_id IS NULL AND e.root_id IS NULL)
               OR e.kind = 6)
        GROUP BY e.id
        ORDER BY e.created_at DESC
        LIMIT :limit
    """)
    fun userPostsFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>>

    /** Notes tab: kind 1 top-level posts + kind 6 reposts. */
    @Query("""
        SELECT
            e.id, e.pubkey, e.kind, e.content, e.created_at, e.tags,
            e.relay_url, e.reply_to_id, e.root_id,
            e.has_content_warning, e.content_warning_reason, e.cached_at,
            e.zap_total_sats,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            u.nip05           AS author_nip05,
            COUNT(DISTINCT r.event_id)  AS reaction_count,
            COUNT(DISTINCT rep.id)      AS reply_count,
            COUNT(DISTINCT rp.id)       AS repost_count,
            COUNT(DISTINCT z.id)        AS zap_count
        FROM events e
        LEFT JOIN users     u   ON u.pubkey          = e.pubkey
        LEFT JOIN reactions r   ON r.target_event_id = e.id
        LEFT JOIN events    rep ON (rep.reply_to_id = e.id OR rep.root_id = e.id) AND rep.kind = 1
        LEFT JOIN events    rp  ON rp.root_id        = e.id AND rp.kind  = 6
        LEFT JOIN events    z   ON z.root_id         = e.id AND z.kind   = 9735
        WHERE e.pubkey = :pubkey
          AND ((e.kind = 1 AND e.reply_to_id IS NULL AND e.root_id IS NULL) OR e.kind = 6)
        GROUP BY e.id
        ORDER BY e.created_at DESC
        LIMIT :limit
    """)
    fun userNotesFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>>

    /** Replies tab: kind 1 events that are replies (have reply_to_id or root_id). */
    @Query("""
        SELECT
            e.id, e.pubkey, e.kind, e.content, e.created_at, e.tags,
            e.relay_url, e.reply_to_id, e.root_id,
            e.has_content_warning, e.content_warning_reason, e.cached_at,
            e.zap_total_sats,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            u.nip05           AS author_nip05,
            COUNT(DISTINCT r.event_id)  AS reaction_count,
            COUNT(DISTINCT rep.id)      AS reply_count,
            COUNT(DISTINCT rp.id)       AS repost_count,
            COUNT(DISTINCT z.id)        AS zap_count
        FROM events e
        LEFT JOIN users     u   ON u.pubkey          = e.pubkey
        LEFT JOIN reactions r   ON r.target_event_id = e.id
        LEFT JOIN events    rep ON (rep.reply_to_id = e.id OR rep.root_id = e.id) AND rep.kind = 1
        LEFT JOIN events    rp  ON rp.root_id        = e.id AND rp.kind  = 6
        LEFT JOIN events    z   ON z.root_id         = e.id AND z.kind   = 9735
        WHERE e.pubkey = :pubkey AND e.kind = 1
          AND (e.reply_to_id IS NOT NULL OR e.root_id IS NOT NULL)
        GROUP BY e.id
        ORDER BY e.created_at DESC
        LIMIT :limit
    """)
    fun userRepliesFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>>

    /** Longform tab: kind 30023 articles (NIP-23). */
    @Query("""
        SELECT
            e.id, e.pubkey, e.kind, e.content, e.created_at, e.tags,
            e.relay_url, e.reply_to_id, e.root_id,
            e.has_content_warning, e.content_warning_reason, e.cached_at,
            e.zap_total_sats,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            u.nip05           AS author_nip05,
            0 AS reaction_count, 0 AS reply_count, 0 AS repost_count, 0 AS zap_count
        FROM events e
        LEFT JOIN users u ON u.pubkey = e.pubkey
        WHERE e.pubkey = :pubkey AND e.kind = 30023
        ORDER BY e.created_at DESC
        LIMIT :limit
    """)
    fun userLongformFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>>

    /** All events for thread view (includes replies). */
    @Query("""
        SELECT
            e.id, e.pubkey, e.kind, e.content, e.created_at, e.tags, e.relay_url,
            e.reply_to_id, e.root_id, e.has_content_warning, e.content_warning_reason, e.cached_at,
            e.zap_total_sats,
            u.name AS author_name, u.display_name AS author_display_name, u.picture AS author_picture,
            u.nip05 AS author_nip05,
            COUNT(DISTINCT r.event_id) AS reaction_count,
            COUNT(DISTINCT rep.id)     AS reply_count,
            COUNT(DISTINCT rp.id)      AS repost_count,
            COUNT(DISTINCT z.id)       AS zap_count
        FROM events e
        LEFT JOIN users     u   ON u.pubkey          = e.pubkey
        LEFT JOIN reactions r   ON r.target_event_id = e.id
        LEFT JOIN events    rep ON (rep.reply_to_id = e.id OR rep.root_id = e.id) AND rep.kind = 1
        LEFT JOIN events    rp  ON rp.root_id        = e.id AND rp.kind = 6
        LEFT JOIN events    z   ON z.root_id         = e.id AND z.kind  = 9735
        WHERE e.id = :eventId
           OR ((e.reply_to_id = :eventId OR e.root_id = :eventId) AND e.kind = 1)
        GROUP BY e.id
        ORDER BY e.created_at ASC
    """)
    fun threadFlow(eventId: String): Flow<List<FeedRow>>

    /** Fetch a single event by ID (used to reconstruct JSON for reposts). */
    @Query("SELECT * FROM events WHERE id = :id LIMIT 1")
    suspend fun getEventById(id: String): EventEntity?

    /** Reactive flow for a single event by ID. Emits null until the event arrives in Room. */
    @Query("SELECT * FROM events WHERE id = :eventId LIMIT 1")
    fun flowById(eventId: String): Flow<EventEntity?>

    /**
     * All event IDs that [pubkey] has reacted to.
     * Room re-emits whenever the reactions table changes — drives the heart Cyan state.
     */
    @Query("SELECT target_event_id FROM reactions WHERE pubkey = :pubkey")
    fun reactedEventIds(pubkey: String): Flow<List<String>>

    /**
     * All event IDs that [pubkey] has reposted (kind 6 events with root_id = original).
     * Room re-emits whenever the events table changes.
     */
    @Query("SELECT root_id FROM events WHERE kind = 6 AND pubkey = :pubkey AND root_id IS NOT NULL")
    fun repostedEventIds(pubkey: String): Flow<List<String>>

    /**
     * All event IDs that [pubkey] has zapped (kind 9734 zap requests, root_id = zapped event).
     * Zap requests are stored with root_id = zapped event ID (positional NIP-10 e-tag parse).
     * Room re-emits whenever the events table changes.
     */
    @Query("SELECT root_id FROM events WHERE kind = 9734 AND pubkey = :pubkey AND root_id IS NOT NULL")
    fun zappedEventIds(pubkey: String): Flow<List<String>>

    /**
     * NIP-50 content search: kind 1 events whose content contains [query].
     * Engagement counts omitted (0) for performance — search results don't need live counts.
     * Re-emits as new search results arrive from the relay via EventProcessor.
     */
    @Query("""
        SELECT
            e.id, e.pubkey, e.kind, e.content, e.created_at, e.tags, e.relay_url,
            e.reply_to_id, e.root_id, e.has_content_warning, e.content_warning_reason, e.cached_at,
            e.zap_total_sats,
            u.name AS author_name, u.display_name AS author_display_name, u.picture AS author_picture,
            u.nip05 AS author_nip05,
            0 AS reaction_count, 0 AS reply_count, 0 AS repost_count, 0 AS zap_count
        FROM events e
        LEFT JOIN users u ON u.pubkey = e.pubkey
        WHERE e.kind = 1
          AND e.content LIKE '%' || :query || '%'
        ORDER BY e.created_at DESC
        LIMIT 50
    """)
    fun searchNotes(query: String): Flow<List<FeedRow>>

    /** Increment the zap sats total for the given event. Called by EventProcessor for kind-9735. */
    @Query("UPDATE events SET zap_total_sats = zap_total_sats + :sats WHERE id = :eventId")
    suspend fun addZapSats(eventId: String, sats: Long)

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    /** Delete the [limit] oldest events by created_at. Used by FIFO pruning. */
    @Query("""
        DELETE FROM events
        WHERE id IN (
            SELECT id FROM events
            ORDER BY created_at ASC
            LIMIT :limit
        )
    """)
    suspend fun deleteOldest(limit: Int)

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
