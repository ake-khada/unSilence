package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unsilence.app.data.db.entity.PinnedRelayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PinnedRelayDao {

    @Query("SELECT * FROM pinned_relays ORDER BY added_at ASC")
    fun allFlow(): Flow<List<PinnedRelayEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PinnedRelayEntity)

    @Query("DELETE FROM pinned_relays WHERE relay_url = :url")
    suspend fun deleteByUrl(url: String)
}
