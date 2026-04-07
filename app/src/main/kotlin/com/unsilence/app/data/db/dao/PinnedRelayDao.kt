package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unsilence.app.data.db.entity.PinnedRelayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PinnedRelayDao {

    @Query("SELECT * FROM pinned_relays WHERE pubkey = :pubkey ORDER BY added_at ASC")
    fun pinnedFor(pubkey: String): Flow<List<PinnedRelayEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(relay: PinnedRelayEntity)

    @Query("DELETE FROM pinned_relays WHERE pubkey = :pubkey AND url = :url")
    suspend fun delete(pubkey: String, url: String)
}
