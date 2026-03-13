package com.unsilence.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room schema migrations.
 *
 * v1 → v2: Add follows and relay_list_metadata tables (NIP-65 outbox routing).
 *           No existing table is modified — pure additions, so zero data loss.
 * v2 → v3: Replace single-column indexes on events with composite indexes to fix
 *           feedFlow query performance ("Long db operation" x27 per session).
 *           Also adds index on reactions(target_event_id) if not already present.
 * v3 → v4: Add (root_id, created_at) index for thread queries.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_root_id_created_at` ON `events` (`root_id`, `created_at`)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Drop old single-column indexes replaced by composite ones below.
        // index_events_created_at is kept (same single-column index is still declared).
        db.execSQL("DROP INDEX IF EXISTS index_events_pubkey")
        db.execSQL("DROP INDEX IF EXISTS index_events_kind")
        db.execSQL("DROP INDEX IF EXISTS index_events_relay_url")
        db.execSQL("DROP INDEX IF EXISTS index_events_reply_to_id")
        db.execSQL("DROP INDEX IF EXISTS index_events_root_id")

        // Composite indexes that make feedFlow queries fast.
        db.execSQL("CREATE INDEX IF NOT EXISTS index_events_relay_url_kind_created_at ON events(relay_url, kind, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_events_reply_to_id_kind ON events(reply_to_id, kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_events_root_id_kind ON events(root_id, kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_events_pubkey_kind_created_at ON events(pubkey, kind, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_events_created_at ON events(created_at)")

        // Reactions lookup index (safe to re-run; may already exist on fresh installs).
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reactions_target_event_id ON reactions(target_event_id)")
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS follows (
                pubkey     TEXT NOT NULL,
                followed_at INTEGER NOT NULL,
                PRIMARY KEY(pubkey)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS relay_list_metadata (
                pubkey      TEXT NOT NULL,
                write_relays TEXT NOT NULL,
                updated_at  INTEGER NOT NULL,
                PRIMARY KEY(pubkey)
            )
            """.trimIndent()
        )
    }
}
