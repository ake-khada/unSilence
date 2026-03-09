package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unsilence.app.data.db.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)

    /** Batch upsert for the event pipeline. Room wraps the list insert in a single transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBatch(users: List<UserEntity>)

    @Query("SELECT * FROM users WHERE pubkey = :pubkey")
    suspend fun getUser(pubkey: String): UserEntity?

    @Query("SELECT * FROM users WHERE pubkey = :pubkey")
    fun userFlow(pubkey: String): Flow<UserEntity?>

    @Query("SELECT pubkey FROM users")
    suspend fun allPubkeys(): List<String>

    /**
     * Full-text-style search across name, display_name, and about fields.
     * Re-emits whenever the users table changes (i.e. as search results arrive from the relay).
     */
    @Query("""
        SELECT * FROM users
        WHERE name         LIKE '%' || :query || '%'
           OR display_name LIKE '%' || :query || '%'
           OR about        LIKE '%' || :query || '%'
        ORDER BY display_name ASC
        LIMIT 50
    """)
    fun searchUsers(query: String): Flow<List<UserEntity>>
}
