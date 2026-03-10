package com.unsilence.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room schema migrations.
 *
 * v1 → v2: Add follows and relay_list_metadata tables (NIP-65 outbox routing).
 *           No existing table is modified — pure additions, so zero data loss.
 */
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
