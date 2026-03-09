package com.unsilence.app.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A single row in the unified notifications list.
 *
 * [notifType] discriminates between the five notification kinds:
 *   "reaction" | "reply" | "repost" | "zap" | "mention"
 *
 * [targetNoteId] is the note to open when the user taps the row:
 *   - reaction/repost/zap → the user's note that was acted on
 *   - reply               → the reply event itself (opens the thread)
 *   - mention             → the mentioning event
 *
 * [targetNoteContent] is the snippet shown below the action text — the user's
 *   original note for reactions/reposts/zaps/replies, the mentioning note's
 *   text for mentions.
 */
data class NotificationRow(
    @ColumnInfo(name = "id")                   val id: String,
    @ColumnInfo(name = "notif_type")           val notifType: String,
    @ColumnInfo(name = "actor_pubkey")         val actorPubkey: String,
    @ColumnInfo(name = "actor_name")           val actorName: String?,
    @ColumnInfo(name = "actor_display_name")   val actorDisplayName: String?,
    @ColumnInfo(name = "actor_picture")        val actorPicture: String?,
    @ColumnInfo(name = "target_note_id")       val targetNoteId: String?,
    @ColumnInfo(name = "target_note_content")  val targetNoteContent: String,
    @ColumnInfo(name = "created_at")           val createdAt: Long,
)

@Dao
interface NotificationsDao {

    /**
     * Live notifications for [userPubkey], newest-first.
     *
     * Five branches:
     *  1. Reactions (kind 7, from the reactions table)
     *  2. Replies   (kind 1 events that reply to the user's own notes)
     *  3. Reposts   (kind 6 events whose root_id is one of the user's notes)
     *  4. Zaps      (kind 9735 events whose root_id is one of the user's notes)
     *  5. Mentions  (kind 1 events with a p-tag matching userPubkey, that are NOT
     *                replies to the user's own notes — those are already in branch 2)
     *
     * Room tracks all referenced tables (reactions, events, users) and re-emits
     * the flow whenever any of them are written to by EventProcessor.
     */
    @Query("""
        SELECT
            r.event_id                  AS id,
            'reaction'                  AS notif_type,
            r.pubkey                    AS actor_pubkey,
            u.name                      AS actor_name,
            u.display_name              AS actor_display_name,
            u.picture                   AS actor_picture,
            r.target_event_id           AS target_note_id,
            COALESCE(te.content, '')    AS target_note_content,
            r.created_at                AS created_at
        FROM reactions r
        LEFT JOIN users  u  ON u.pubkey = r.pubkey
        LEFT JOIN events te ON te.id    = r.target_event_id
        WHERE te.pubkey = :userPubkey
          AND r.pubkey  != :userPubkey

        UNION ALL

        SELECT
            ev.id                       AS id,
            'reply'                     AS notif_type,
            ev.pubkey                   AS actor_pubkey,
            u.name                      AS actor_name,
            u.display_name              AS actor_display_name,
            u.picture                   AS actor_picture,
            ev.id                       AS target_note_id,
            COALESCE(te.content, '')    AS target_note_content,
            ev.created_at               AS created_at
        FROM events ev
        LEFT JOIN users  u  ON u.pubkey  = ev.pubkey
        LEFT JOIN events te ON te.id     = ev.reply_to_id
        WHERE ev.kind    = 1
          AND ev.pubkey != :userPubkey
          AND ev.reply_to_id IN (SELECT id FROM events WHERE pubkey = :userPubkey)

        UNION ALL

        SELECT
            ev.id                       AS id,
            'repost'                    AS notif_type,
            ev.pubkey                   AS actor_pubkey,
            u.name                      AS actor_name,
            u.display_name              AS actor_display_name,
            u.picture                   AS actor_picture,
            ev.root_id                  AS target_note_id,
            COALESCE(te.content, '')    AS target_note_content,
            ev.created_at               AS created_at
        FROM events ev
        LEFT JOIN users  u  ON u.pubkey = ev.pubkey
        LEFT JOIN events te ON te.id    = ev.root_id
        WHERE ev.kind    = 6
          AND ev.pubkey != :userPubkey
          AND ev.root_id IN (SELECT id FROM events WHERE pubkey = :userPubkey)

        UNION ALL

        SELECT
            ev.id                       AS id,
            'zap'                       AS notif_type,
            ev.pubkey                   AS actor_pubkey,
            u.name                      AS actor_name,
            u.display_name              AS actor_display_name,
            u.picture                   AS actor_picture,
            ev.root_id                  AS target_note_id,
            COALESCE(te.content, '')    AS target_note_content,
            ev.created_at               AS created_at
        FROM events ev
        LEFT JOIN users  u  ON u.pubkey = ev.pubkey
        LEFT JOIN events te ON te.id    = ev.root_id
        WHERE ev.kind    = 9735
          AND ev.root_id IN (SELECT id FROM events WHERE pubkey = :userPubkey)

        UNION ALL

        SELECT
            ev.id                       AS id,
            'mention'                   AS notif_type,
            ev.pubkey                   AS actor_pubkey,
            u.name                      AS actor_name,
            u.display_name              AS actor_display_name,
            u.picture                   AS actor_picture,
            ev.id                       AS target_note_id,
            COALESCE(ev.content, '')    AS target_note_content,
            ev.created_at               AS created_at
        FROM events ev
        LEFT JOIN users u ON u.pubkey = ev.pubkey
        WHERE ev.kind    = 1
          AND ev.pubkey != :userPubkey
          AND ev.tags LIKE '%"p","' || :userPubkey || '"%'
          AND (ev.reply_to_id IS NULL
               OR ev.reply_to_id NOT IN (SELECT id FROM events WHERE pubkey = :userPubkey))

        ORDER BY created_at DESC
        LIMIT 100
    """)
    fun notificationsFlow(userPubkey: String): Flow<List<NotificationRow>>
}
