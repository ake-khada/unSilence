package com.unsilence.app.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
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
 * v4 → v5: Add zap_total_sats column to events for displaying total zap amount.
 * v5 → v6: Add follower_count and follower_count_updated_at columns to users for NIP-45 cache.
 * v6 → v7: Add own_relays table for relay management screen.
 * v7 → v8: Add created_at column to own_relays for replaceable event semantics.
 * v8 → v9: Add event_stats, tags, and event_relays tables with indexes.
 *           Backfill event_relays from events.relay_url, tags from events.tags JSON,
 *           and event_stats from existing engagement data.
 * v9 → v10: NIP-51 relay ecosystem tables (relay_configs, nostr_relay_sets,
 *            nostr_relay_set_members) + coverage ledger table. Migrate own_relays
 *            data into relay_configs (kind 10002).
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── Step 1: Migrate non-built-in relay sets to NIP-51 nostr_relay_sets ──
        val cursor = db.query("SELECT id, name, relay_urls FROM relay_sets WHERE is_built_in = 0")
        try {
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                val relayUrlsJson = cursor.getString(2) ?: continue

                // Derive d-tag from name
                var dTag = name.lowercase().replace(Regex("[^a-z0-9-]"), "-")

                // Handle collision: check if dTag already exists
                var suffix = 1
                while (true) {
                    val checkCursor = db.query(
                        "SELECT COUNT(*) FROM nostr_relay_sets WHERE d_tag = ?",
                        arrayOf(dTag)
                    )
                    val exists = checkCursor.use { c ->
                        c.moveToFirst() && c.getInt(0) > 0
                    }
                    if (!exists) break
                    suffix++
                    dTag = "${name.lowercase().replace(Regex("[^a-z0-9-]"), "-")}-$suffix"
                }

                val now = System.currentTimeMillis() / 1000L

                db.execSQL(
                    "INSERT OR IGNORE INTO nostr_relay_sets (d_tag, owner_pubkey, title, event_created_at) VALUES (?, '', ?, ?)",
                    arrayOf<Any>(dTag, name, now)
                )

                // Parse relay URLs, normalize, and insert members
                try {
                    val urls = org.json.JSONArray(relayUrlsJson)
                    for (i in 0 until urls.length()) {
                        var url = urls.optString(i) ?: continue
                        url = url.trim().removeSuffix("/")
                        if (url.isBlank()) continue
                        url = url.removePrefix("https://").removePrefix("http://")
                        if (!url.startsWith("wss://") && !url.startsWith("ws://")) url = "wss://$url"
                        db.execSQL(
                            "INSERT OR IGNORE INTO nostr_relay_set_members (set_d_tag, owner_pubkey, relay_url) VALUES (?, '', ?)",
                            arrayOf(dTag, url)
                        )
                    }
                } catch (_: Exception) { }
            }
        } finally {
            cursor.close()
        }

        // ── Step 2: Drop relay_sets table ──
        db.execSQL("DROP TABLE IF EXISTS relay_sets")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── relay_configs: unified relay storage for kinds 10002/10006/10007/10012 ──
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS relay_configs (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                kind INTEGER NOT NULL,
                relay_url TEXT NOT NULL,
                marker TEXT,
                set_ref TEXT,
                owner_pubkey TEXT NOT NULL DEFAULT '',
                event_created_at INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_relay_configs_kind ON relay_configs(kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_relay_configs_owner_pubkey_kind ON relay_configs(owner_pubkey, kind)")

        // ── nostr_relay_sets: NIP-51 kind 30002 parameterized replaceable relay sets ──
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS nostr_relay_sets (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                d_tag TEXT NOT NULL,
                owner_pubkey TEXT NOT NULL DEFAULT '',
                title TEXT,
                description TEXT,
                image TEXT,
                event_created_at INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_nostr_relay_sets_d_tag_owner_pubkey ON nostr_relay_sets(d_tag, owner_pubkey)")

        // ── nostr_relay_set_members: relay URLs belonging to a kind 30002 set ──
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS nostr_relay_set_members (
                set_d_tag TEXT NOT NULL,
                owner_pubkey TEXT NOT NULL DEFAULT '',
                relay_url TEXT NOT NULL,
                PRIMARY KEY(set_d_tag, owner_pubkey, relay_url)
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_nostr_relay_set_members_set_d_tag ON nostr_relay_set_members(set_d_tag)")

        // ── coverage: ledger tracking what time ranges have been fetched ──
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS coverage (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                scope_type TEXT NOT NULL,
                scope_key TEXT NOT NULL,
                since_ts INTEGER NOT NULL DEFAULT 0,
                until_ts INTEGER NOT NULL DEFAULT 0,
                relay_set_id TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                eose_count INTEGER NOT NULL DEFAULT 0,
                expected_relays INTEGER NOT NULL DEFAULT 0,
                oldest_seen_ts INTEGER NOT NULL DEFAULT 0,
                newest_seen_ts INTEGER NOT NULL DEFAULT 0,
                last_attempt_at INTEGER NOT NULL DEFAULT 0,
                last_success_at INTEGER NOT NULL DEFAULT 0,
                stale_after_ms INTEGER NOT NULL DEFAULT 300000
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_coverage_scope_type_scope_key ON coverage(scope_type, scope_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_coverage_last_attempt_at ON coverage(last_attempt_at)")

        // ── Migrate own_relays → relay_configs (kind 10002) ──
        db.execSQL("""
            INSERT OR IGNORE INTO relay_configs (kind, relay_url, marker, owner_pubkey, event_created_at)
            SELECT 10002, url,
                CASE WHEN `read` = 1 AND `write` = 1 THEN NULL
                     WHEN `read` = 1 THEN 'read'
                     WHEN `write` = 1 THEN 'write'
                     ELSE NULL END,
                '', created_at
            FROM own_relays
        """)

        // own_relays kept as dead table for safety — will be dropped in a future migration.
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── Step 0: Ensure events columns that may be missing from older schemas ─
        // These were added to EventEntity without migrations at some point.
        // ALTER TABLE ADD COLUMN throws if the column already exists, so we catch.
        try { db.execSQL("ALTER TABLE events ADD COLUMN cached_at INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE events ADD COLUMN has_content_warning INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE events ADD COLUMN content_warning_reason TEXT") } catch (_: Exception) {}

        // ── Step 1: Create new tables ──────────────────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS event_stats (
                event_id TEXT NOT NULL PRIMARY KEY,
                reply_count INTEGER NOT NULL DEFAULT 0,
                repost_count INTEGER NOT NULL DEFAULT 0,
                reaction_count INTEGER NOT NULL DEFAULT 0,
                zap_count INTEGER NOT NULL DEFAULT 0,
                zap_total_sats INTEGER NOT NULL DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tags (
                event_id TEXT NOT NULL,
                tag_name TEXT NOT NULL,
                tag_pos INTEGER NOT NULL,
                tag_value TEXT NOT NULL,
                extra TEXT,
                PRIMARY KEY (event_id, tag_name, tag_pos)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS event_relays (
                event_id TEXT NOT NULL,
                relay_url TEXT NOT NULL,
                seen_at INTEGER NOT NULL,
                PRIMARY KEY (event_id, relay_url)
            )
        """)

        // ── Step 2: Create indexes ─────────────────────────────────────────
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_kind_ts ON events(kind, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_pubkey_kind_ts ON events(pubkey, kind, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_root_kind ON events(root_id, kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_reply_kind ON events(reply_to_id, kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_ts ON events(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tags_lookup ON tags(tag_name, tag_value, event_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tags_e ON tags(tag_value, event_id) WHERE tag_name='e'")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tags_p ON tags(tag_value, event_id) WHERE tag_name='p'")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_relays_by_relay ON event_relays(relay_url, seen_at DESC, event_id)")

        // ── Step 3: Data migration ─────────────────────────────────────────

        // 3a. Populate event_relays from existing events
        db.execSQL("""
            INSERT OR IGNORE INTO event_relays (event_id, relay_url, seen_at)
            SELECT id, relay_url, created_at FROM events WHERE relay_url IS NOT NULL
        """)

        // 3b. Populate tags from existing events.tags JSON (Kotlin parsing)
        migrateTags(db)

        // 3c. Populate event_stats from existing engagement data
        db.execSQL("""
            INSERT OR IGNORE INTO event_stats (event_id, reply_count, repost_count, reaction_count, zap_count, zap_total_sats)
            SELECT e.id,
                (SELECT COUNT(*) FROM events r WHERE (r.reply_to_id = e.id OR r.root_id = e.id) AND r.kind = 1),
                (SELECT COUNT(*) FROM events r WHERE r.root_id = e.id AND r.kind = 6),
                (SELECT COUNT(*) FROM reactions r WHERE r.target_event_id = e.id),
                (SELECT COUNT(*) FROM events z WHERE z.root_id = e.id AND z.kind = 9735),
                0
            FROM events e WHERE e.kind IN (1, 6, 20, 21, 30023)
        """)
    }

    private fun migrateTags(db: SupportSQLiteDatabase) {
        val cursor = db.query("SELECT id, tags FROM events")
        var count = 0
        var tagBatch = mutableListOf<ContentValues>()

        try {
            while (cursor.moveToNext()) {
                val eventId = cursor.getString(0)
                val tagsJson = cursor.getString(1) ?: continue

                try {
                    // Parse tags JSON: [["tagName","value","extra",...], ...]
                    val tagsStr = tagsJson.trim()
                    if (tagsStr.isEmpty() || tagsStr == "[]") continue

                    // Simple JSON array parsing using org.json which is available on Android
                    val jsonArray = org.json.JSONArray(tagsStr)
                    for (i in 0 until jsonArray.length()) {
                        val tag = jsonArray.optJSONArray(i) ?: continue
                        if (tag.length() < 2) continue
                        val tagName = tag.optString(0) ?: continue
                        val tagValue = tag.optString(1) ?: continue
                        val extra = if (tag.length() > 2) tag.optString(2) else null

                        val cv = ContentValues().apply {
                            put("event_id", eventId)
                            put("tag_name", tagName)
                            put("tag_pos", i)
                            put("tag_value", tagValue)
                            put("extra", extra)
                        }
                        tagBatch.add(cv)

                        if (tagBatch.size >= 500) {
                            flushTagBatch(db, tagBatch)
                            tagBatch = mutableListOf()
                        }
                    }
                } catch (_: Exception) {
                    // Skip malformed tags JSON
                }

                count++
                if (count % 10_000 == 0) {
                    android.util.Log.d("Migration_8_9", "Migrated tags for $count events")
                }
            }

            // Flush remaining
            if (tagBatch.isNotEmpty()) {
                flushTagBatch(db, tagBatch)
            }
            android.util.Log.d("Migration_8_9", "Tag migration complete: $count events processed")
        } finally {
            cursor.close()
        }
    }

    private fun flushTagBatch(db: SupportSQLiteDatabase, batch: List<ContentValues>) {
        for (cv in batch) {
            db.insert("tags", SQLiteDatabase.CONFLICT_IGNORE, cv)
        }
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE own_relays ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS own_relays (
                url TEXT NOT NULL PRIMARY KEY,
                `read` INTEGER NOT NULL DEFAULT 1,
                `write` INTEGER NOT NULL DEFAULT 1
            )
        """)
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE users ADD COLUMN follower_count INTEGER")
        db.execSQL("ALTER TABLE users ADD COLUMN follower_count_updated_at INTEGER")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN zap_total_sats INTEGER NOT NULL DEFAULT 0")
    }
}

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
