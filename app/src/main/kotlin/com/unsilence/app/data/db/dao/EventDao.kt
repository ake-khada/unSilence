package com.unsilence.app.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unsilence.app.data.db.entity.EventEntity
import kotlinx.coroutines.flow.Flow

/** Flattened result of the feed query (event + author fields + engagement counts). */
@androidx.compose.runtime.Immutable
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
     * Feed query: events seen on any of [relayUrls] (via the event_relays junction
     * table), filtered by kind and time window, with engagement counts, newest-first.
     *
     * Uses a semi-join subquery instead of INNER JOIN to avoid row duplication when
     * an event is associated with multiple relays in the list.
     *
     * [contentFilter]: 0 = all (notes + replies), 1 = notes only, 2 = replies only.
     * Engagement filters applied via OR — each is opt-in (0 = skip check, 1 = require ≥ 1).
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
            COALESCE(s.zap_total_sats, 0) AS zap_total_sats,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            u.nip05           AS author_nip05,
            COALESCE(s.reaction_count, 0) AS reaction_count,
            COALESCE(s.reply_count, 0)    AS reply_count,
            COALESCE(s.repost_count, 0)   AS repost_count,
            COALESCE(s.zap_count, 0)      AS zap_count
        FROM events e
        LEFT JOIN users       u ON u.pubkey  = e.pubkey
        LEFT JOIN event_stats s ON s.event_id = e.id
        WHERE e.id IN (SELECT er.event_id FROM event_relays er WHERE er.relay_url IN (:relayUrls))
          AND e.kind      IN (:kinds)
          AND ((:contentFilter = 0)
              OR (:contentFilter = 1 AND ((e.reply_to_id IS NULL AND e.root_id IS NULL) OR e.kind = 6))
              OR (:contentFilter = 2 AND (
                  ((e.reply_to_id IS NOT NULL OR e.root_id IS NOT NULL) AND e.kind != 6)
                  OR e.id IN (
                      SELECT e2.reply_to_id FROM events e2
                      WHERE e2.reply_to_id IS NOT NULL
                      AND e2.id IN (SELECT er2.event_id FROM event_relays er2 WHERE er2.relay_url IN (:relayUrls))
                  )
              )))
          AND (:sinceTimestamp = 0 OR e.created_at > :sinceTimestamp)
          AND ((:requireReposts = 0 AND :requireReactions = 0 AND :requireReplies = 0 AND :requireZaps = 0)
              OR (:requireReposts   = 1 AND COALESCE(s.repost_count, 0)   >= 1)
              OR (:requireReactions = 1 AND COALESCE(s.reaction_count, 0) >= 1)
              OR (:requireReplies   = 1 AND COALESCE(s.reply_count, 0)    >= 1)
              OR (:requireZaps      = 1 AND COALESCE(s.zap_count, 0)      >= 1))
        ORDER BY e.created_at DESC
        LIMIT :limit
    """)
    fun feedFlow(
        relayUrls: List<String>,
        kinds: List<Int>,
        sinceTimestamp: Long,
        contentFilter: Int,
        requireReposts: Int,
        requireReactions: Int,
        requireReplies: Int,
        requireZaps: Int,
        limit: Int = 300,
    ): Flow<List<FeedRow>>

    /**
     * Following feed: top-level events from followed pubkeys, with filter support.
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
            COALESCE(s.zap_total_sats, 0) AS zap_total_sats,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            u.nip05           AS author_nip05,
            COALESCE(s.reaction_count, 0) AS reaction_count,
            COALESCE(s.reply_count, 0)    AS reply_count,
            COALESCE(s.repost_count, 0)   AS repost_count,
            COALESCE(s.zap_count, 0)      AS zap_count
        FROM events e
        LEFT JOIN  users       u ON u.pubkey   = e.pubkey
        LEFT JOIN  event_stats s ON s.event_id = e.id
        WHERE e.kind IN (:kinds)
          AND (
              (e.pubkey IN (SELECT pubkey FROM follows) AND (
                  (:contentFilter = 0)
                  OR (:contentFilter = 1 AND ((e.reply_to_id IS NULL AND e.root_id IS NULL) OR e.kind = 6))
                  OR (:contentFilter = 2 AND (e.reply_to_id IS NOT NULL OR e.root_id IS NOT NULL) AND e.kind != 6)
              ))
              OR (:contentFilter = 2 AND e.id IN (
                  SELECT e2.reply_to_id FROM events e2
                  WHERE e2.reply_to_id IS NOT NULL
                  AND e2.pubkey IN (SELECT pubkey FROM follows)
              ))
          )
          AND (:sinceTimestamp = 0 OR e.created_at > :sinceTimestamp)
          AND ((:requireReposts = 0 AND :requireReactions = 0 AND :requireReplies = 0 AND :requireZaps = 0)
               OR (:requireReposts   = 1 AND COALESCE(s.repost_count, 0)   >= 1)
               OR (:requireReactions = 1 AND COALESCE(s.reaction_count, 0) >= 1)
               OR (:requireReplies   = 1 AND COALESCE(s.reply_count, 0)    >= 1)
               OR (:requireZaps      = 1 AND COALESCE(s.zap_count, 0)      >= 1))
        ORDER BY e.created_at DESC
        LIMIT :limit
    """)
    fun followingFeedFlow(
        kinds: List<Int>,
        sinceTimestamp: Long,
        contentFilter: Int,
        requireReposts: Int,
        requireReactions: Int,
        requireReplies: Int,
        requireZaps: Int,
        limit: Int = 300,
    ): Flow<List<FeedRow>>

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
            COALESCE(s.zap_total_sats, 0) AS zap_total_sats,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            u.nip05           AS author_nip05,
            COALESCE(s.reaction_count, 0) AS reaction_count,
            COALESCE(s.reply_count, 0)    AS reply_count,
            COALESCE(s.repost_count, 0)   AS repost_count,
            COALESCE(s.zap_count, 0)      AS zap_count
        FROM events e
        LEFT JOIN users       u ON u.pubkey   = e.pubkey
        LEFT JOIN event_stats s ON s.event_id = e.id
        WHERE e.pubkey = :pubkey
          AND ((e.kind = 1 AND e.reply_to_id IS NULL AND e.root_id IS NULL)
               OR e.kind = 6)
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
            COALESCE(s.zap_total_sats, 0) AS zap_total_sats,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            u.nip05           AS author_nip05,
            COALESCE(s.reaction_count, 0) AS reaction_count,
            COALESCE(s.reply_count, 0)    AS reply_count,
            COALESCE(s.repost_count, 0)   AS repost_count,
            COALESCE(s.zap_count, 0)      AS zap_count
        FROM events e
        LEFT JOIN users       u ON u.pubkey   = e.pubkey
        LEFT JOIN event_stats s ON s.event_id = e.id
        WHERE e.pubkey = :pubkey
          AND ((e.kind = 1 AND e.reply_to_id IS NULL AND e.root_id IS NULL) OR e.kind = 6)
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
            COALESCE(s.zap_total_sats, 0) AS zap_total_sats,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            u.nip05           AS author_nip05,
            COALESCE(s.reaction_count, 0) AS reaction_count,
            COALESCE(s.reply_count, 0)    AS reply_count,
            COALESCE(s.repost_count, 0)   AS repost_count,
            COALESCE(s.zap_count, 0)      AS zap_count
        FROM events e
        LEFT JOIN users       u ON u.pubkey   = e.pubkey
        LEFT JOIN event_stats s ON s.event_id = e.id
        WHERE e.pubkey = :pubkey AND e.kind = 1
          AND (e.reply_to_id IS NOT NULL OR e.root_id IS NOT NULL)
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
            COALESCE(s.zap_total_sats, 0) AS zap_total_sats,
            u.name            AS author_name,
            u.display_name    AS author_display_name,
            u.picture         AS author_picture,
            u.nip05           AS author_nip05,
            0 AS reaction_count, 0 AS reply_count, 0 AS repost_count, 0 AS zap_count
        FROM events e
        LEFT JOIN users       u ON u.pubkey   = e.pubkey
        LEFT JOIN event_stats s ON s.event_id = e.id
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
            COALESCE(s.zap_total_sats, 0) AS zap_total_sats,
            u.name AS author_name, u.display_name AS author_display_name, u.picture AS author_picture,
            u.nip05 AS author_nip05,
            COALESCE(s.reaction_count, 0) AS reaction_count,
            COALESCE(s.reply_count, 0)    AS reply_count,
            COALESCE(s.repost_count, 0)   AS repost_count,
            COALESCE(s.zap_count, 0)      AS zap_count
        FROM events e
        LEFT JOIN users       u ON u.pubkey   = e.pubkey
        LEFT JOIN event_stats s ON s.event_id = e.id
        WHERE e.id = :eventId
           OR ((e.reply_to_id = :eventId OR e.root_id = :eventId) AND e.kind = 1)
        ORDER BY e.created_at ASC
    """)
    fun threadFlow(eventId: String): Flow<List<FeedRow>>

    /** Batch existence check — returns only the IDs that already exist in Room. */
    @Query("SELECT id FROM events WHERE id IN (:ids)")
    suspend fun getExistingIds(ids: List<String>): List<String>

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
     * NIP-50 content search: kind 1/30023 events whose content contains [query].
     * Engagement counts omitted (0) for performance — search results don't need live counts.
     * Re-emits as new search results arrive from the relay via EventProcessor.
     */
    @Query("""
        SELECT
            e.id, e.pubkey, e.kind, e.content, e.created_at, e.tags, e.relay_url,
            e.reply_to_id, e.root_id, e.has_content_warning, e.content_warning_reason, e.cached_at,
            COALESCE(s.zap_total_sats, 0) AS zap_total_sats,
            u.name AS author_name, u.display_name AS author_display_name, u.picture AS author_picture,
            u.nip05 AS author_nip05,
            0 AS reaction_count, 0 AS reply_count, 0 AS repost_count, 0 AS zap_count
        FROM events e
        LEFT JOIN users       u ON u.pubkey   = e.pubkey
        LEFT JOIN event_stats s ON s.event_id = e.id
        WHERE e.kind IN (1, 30023)
          AND e.content LIKE '%' || :query || '%'
        ORDER BY e.created_at DESC
        LIMIT 50
    """)
    fun searchNotes(query: String): Flow<List<FeedRow>>

    /**
     * Fetch events by a set of known IDs. Used by search to retrieve NIP-50 relay results
     * that were stored in Room but may not match a local LIKE query.
     * Re-emits reactively as the events table changes.
     */
    @Query("""
        SELECT
            e.id, e.pubkey, e.kind, e.content, e.created_at, e.tags, e.relay_url,
            e.reply_to_id, e.root_id, e.has_content_warning, e.content_warning_reason, e.cached_at,
            COALESCE(s.zap_total_sats, 0) AS zap_total_sats,
            u.name AS author_name, u.display_name AS author_display_name, u.picture AS author_picture,
            u.nip05 AS author_nip05,
            0 AS reaction_count, 0 AS reply_count, 0 AS repost_count, 0 AS zap_count
        FROM events e
        LEFT JOIN users       u ON u.pubkey   = e.pubkey
        LEFT JOIN event_stats s ON s.event_id = e.id
        WHERE e.id IN (:eventIds)
        ORDER BY e.created_at DESC
    """)
    fun eventsByIds(eventIds: List<String>): Flow<List<FeedRow>>

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
        SELECT t.event_id FROM tags t
        WHERE t.tag_name = 'expiration'
          AND CAST(t.tag_value AS INTEGER) < :nowSeconds
          AND CAST(t.tag_value AS INTEGER) > 0
    """)
    suspend fun findExpiredIds(nowSeconds: Long): List<String>

    @Query("DELETE FROM events WHERE id IN (:ids)")
    suspend fun deleteEventsByIds(ids: List<String>)

    @Query("DELETE FROM tags WHERE event_id IN (:ids)")
    suspend fun deleteTagsByEventIds(ids: List<String>)

    @Query("DELETE FROM event_stats WHERE event_id IN (:ids)")
    suspend fun deleteStatsByEventIds(ids: List<String>)

    @Query("DELETE FROM event_relays WHERE event_id IN (:ids)")
    suspend fun deleteRelaysByEventIds(ids: List<String>)

    @Transaction
    suspend fun pruneExpired(nowSeconds: Long) {
        val ids = findExpiredIds(nowSeconds)
        if (ids.isEmpty()) return
        // Room binds at most 999 params; chunk for safety.
        for (chunk in ids.chunked(500)) {
            deleteTagsByEventIds(chunk)
            deleteStatsByEventIds(chunk)
            deleteRelaysByEventIds(chunk)
            deleteEventsByIds(chunk)
        }
    }

    /** Find kind-1 events whose content is machine-generated spam. */
    @Query("SELECT id FROM events WHERE kind = 1 AND (content LIKE '{%' OR content LIKE 'xitchat-broadcast-v1-%')")
    suspend fun findJsonSpamIds(): List<String>

    /** Remove all JSON-spam kind-1 events and their related rows. */
    @Transaction
    suspend fun pruneJsonSpam(): Int {
        val ids = findJsonSpamIds()
        if (ids.isEmpty()) return 0
        for (chunk in ids.chunked(500)) {
            deleteTagsByEventIds(chunk)
            deleteStatsByEventIds(chunk)
            deleteRelaysByEventIds(chunk)
            deleteEventsByIds(chunk)
        }
        return ids.size
    }

    /** Latest created_at for events seen on any of [relayUrls]. Used by browse session to
     *  build a `since` filter so the relay only sends genuinely new events. */
    @Query("""
        SELECT MAX(e.created_at) FROM events e
        WHERE e.id IN (SELECT er.event_id FROM event_relays er WHERE er.relay_url IN (:relayUrls))
    """)
    suspend fun maxCreatedAtForRelays(relayUrls: List<String>): Long?
}
