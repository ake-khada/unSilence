package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unsilence.app.data.db.entity.RelayListEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RelayListDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RelayListEntity)

    /** All stored relay lists — emits whenever any entry is upserted. */
    @Query("SELECT * FROM relay_list_metadata")
    fun allFlow(): Flow<List<RelayListEntity>>

    @Query("SELECT * FROM relay_list_metadata")
    suspend fun getAll(): List<RelayListEntity>

    /** Single relay list lookup by pubkey. Returns null if not cached. */
    @Query("SELECT * FROM relay_list_metadata WHERE pubkey = :pubkey")
    suspend fun getByPubkey(pubkey: String): RelayListEntity?
}
