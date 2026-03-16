package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unsilence.app.data.db.entity.CoverageEntity

@Dao
interface CoverageDao {

    @Query("""
        SELECT * FROM coverage
        WHERE scope_type = :scopeType AND scope_key = :scopeKey
        ORDER BY last_attempt_at DESC LIMIT 1
    """)
    suspend fun findLatest(scopeType: String, scopeKey: String): CoverageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CoverageEntity): Long

    @Query("""
        UPDATE coverage SET
            status = 'complete',
            eose_count = :eoseCount,
            oldest_seen_ts = :oldestTs,
            newest_seen_ts = :newestTs,
            last_success_at = :now
        WHERE id = :id
    """)
    suspend fun markComplete(id: Long, eoseCount: Int, oldestTs: Long, newestTs: Long, now: Long)

    @Query("UPDATE coverage SET status = 'failed' WHERE id = :id")
    suspend fun markFailed(id: Long)

    @Query("DELETE FROM coverage WHERE scope_key = :scopeKey")
    suspend fun deleteByScopeKey(scopeKey: String)
}
