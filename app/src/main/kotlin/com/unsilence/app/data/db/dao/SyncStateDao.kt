package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unsilence.app.data.db.entity.SyncStateEntity

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE subscription_key = :key")
    suspend fun get(key: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state")
    suspend fun all(): List<SyncStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query("UPDATE sync_state SET last_sync_at = :ts, last_event_count = last_event_count + :delta, source = :src WHERE subscription_key = :key")
    suspend fun updateTimestamp(key: String, ts: Long, delta: Int, src: String)
}
