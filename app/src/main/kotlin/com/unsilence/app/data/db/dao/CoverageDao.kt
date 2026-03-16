package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unsilence.app.data.db.entity.CoverageEntity

@Dao
abstract class CoverageDao {

    @Query("""
        SELECT * FROM coverage
        WHERE scope_type = :scopeType AND scope_key = :scopeKey AND relay_set_id = :relaySetId
        ORDER BY last_attempt_at DESC LIMIT 1
    """)
    abstract suspend fun findLatest(scopeType: String, scopeKey: String, relaySetId: String): CoverageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(entity: CoverageEntity): Long

    @Query("""
        UPDATE coverage SET
            status = 'complete',
            eose_count = :eoseCount,
            oldest_seen_ts = :oldestTs,
            newest_seen_ts = :newestTs,
            last_success_at = :now
        WHERE id = :id
    """)
    abstract suspend fun markComplete(id: Long, eoseCount: Int, oldestTs: Long, newestTs: Long, now: Long)

    @Query("UPDATE coverage SET status = 'partial', eose_count = :eoseCount, last_success_at = :now WHERE id = :id")
    abstract suspend fun markPartial(id: Long, eoseCount: Int, now: Long)

    @Query("UPDATE coverage SET status = 'failed' WHERE id = :id")
    abstract suspend fun markFailed(id: Long)

    @Query("UPDATE coverage SET status = 'failed' WHERE scope_type = :scopeType AND scope_key = :scopeKey AND relay_set_id = :relaySetId AND status = 'pending'")
    abstract suspend fun markFailedByScope(scopeType: String, scopeKey: String, relaySetId: String)

    @Query("DELETE FROM coverage WHERE scope_key = :scopeKey")
    abstract suspend fun deleteByScopeKey(scopeKey: String)

    @Transaction
    open suspend fun ensureOrUpdate(
        scopeType: String,
        scopeKey: String,
        relaySetId: String,
        staleAfterMs: Long,
    ): CoverageEntity {
        val existing = findLatest(scopeType, scopeKey, relaySetId)
        if (existing != null) {
            val age = System.currentTimeMillis() - existing.lastSuccessAt
            val isTerminal = existing.status in listOf("complete", "partial")
            if (isTerminal && age < staleAfterMs) return existing
            if (existing.status == "pending") return existing
        }
        val entity = CoverageEntity(
            scopeType = scopeType,
            scopeKey = scopeKey,
            relaySetId = relaySetId,
            status = "pending",
            lastAttemptAt = System.currentTimeMillis(),
            staleAfterMs = staleAfterMs,
        )
        val id = insert(entity)
        return entity.copy(id = id)
    }
}
