package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unsilence.app.data.db.entity.RelaySetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RelaySetDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(relaySet: RelaySetEntity)

    @Query("SELECT * FROM relay_sets ORDER BY is_default DESC, name ASC")
    fun allSetsFlow(): Flow<List<RelaySetEntity>>

    @Query("SELECT * FROM relay_sets WHERE is_default = 1 LIMIT 1")
    suspend fun defaultSet(): RelaySetEntity?

    @Query("SELECT * FROM relay_sets WHERE id = :id")
    suspend fun getById(id: String): RelaySetEntity?

    @Query("UPDATE relay_sets SET is_default = (id = :id)")
    suspend fun setDefault(id: String)

    @Query("UPDATE relay_sets SET filter_json = :filterJson WHERE id = :id")
    suspend fun updateFilterJson(id: String, filterJson: String)
}
