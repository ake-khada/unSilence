package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unsilence.app.data.db.entity.OwnRelayEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class OwnRelayDao {

    @Query("SELECT * FROM own_relays ORDER BY url ASC")
    abstract fun allFlow(): Flow<List<OwnRelayEntity>>

    @Query("SELECT * FROM own_relays ORDER BY url ASC")
    abstract suspend fun getAll(): List<OwnRelayEntity>

    @Query("SELECT url FROM own_relays WHERE `write` = 1")
    abstract suspend fun writeRelayUrls(): List<String>

    @Query("SELECT MAX(created_at) FROM own_relays")
    abstract suspend fun maxCreatedAt(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(relay: OwnRelayEntity)

    @Query("DELETE FROM own_relays WHERE url = :url")
    abstract suspend fun delete(url: String)

    @Transaction
    open suspend fun replaceAll(relays: List<OwnRelayEntity>) {
        clearAll()
        relays.forEach { upsert(it) }
    }

    @Query("DELETE FROM own_relays")
    abstract suspend fun clearAll()
}
