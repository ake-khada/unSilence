package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unsilence.app.data.db.entity.FollowEntity
import kotlinx.coroutines.flow.Flow

/**
 * Abstract class (not interface) so we can provide a concrete @Transaction method
 * that atomically clears and re-inserts the entire follow list.
 */
@Dao
abstract class FollowDao {

    /**
     * Replace the entire follow list atomically.
     * Called whenever a fresh kind 3 (NIP-02) event arrives for the logged-in user.
     */
    @Transaction
    open suspend fun replaceAll(follows: List<FollowEntity>) {
        clearAll()
        if (follows.isNotEmpty()) insertAll(follows)
    }

    @Query("DELETE FROM follows")
    abstract suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(follows: List<FollowEntity>)

    /** Live stream of all followed pubkeys — emits on every follow list change. */
    @Query("SELECT * FROM follows ORDER BY followed_at DESC")
    abstract fun followsFlow(): Flow<List<FollowEntity>>

    @Query("SELECT pubkey FROM follows")
    abstract suspend fun allPubkeys(): List<String>

    @Query("SELECT COUNT(*) FROM follows")
    abstract suspend fun count(): Int

    /** Reactive follow count — re-emits on every follow list change. */
    @Query("SELECT COUNT(*) FROM follows")
    abstract fun countFlow(): Flow<Int>

    /** Reactive check: does this pubkey exist in the follows table? */
    @Query("SELECT COUNT(*) > 0 FROM follows WHERE pubkey = :pubkey")
    abstract fun isFollowingFlow(pubkey: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(follow: FollowEntity)

    @Query("DELETE FROM follows WHERE pubkey = :pubkey")
    abstract suspend fun delete(pubkey: String)
}
