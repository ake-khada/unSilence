package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unsilence.app.data.db.entity.RelayTrustScoreEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RelayTrustScoreDao {

    @Query("SELECT * FROM relay_trust_scores ORDER BY score DESC")
    fun allScoresFlow(): Flow<List<RelayTrustScoreEntity>>

    @Query("SELECT score FROM relay_trust_scores WHERE relay_url = :url")
    suspend fun getScoreValue(url: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(scores: List<RelayTrustScoreEntity>)

    @Query("SELECT MAX(updated_at) FROM relay_trust_scores")
    suspend fun lastUpdatedAt(): Long?
}
