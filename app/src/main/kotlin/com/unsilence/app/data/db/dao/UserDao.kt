package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unsilence.app.data.db.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class UserDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertOrIgnore(user: UserEntity)

    /**
     * Merge-safe profile update: COALESCE preserves existing non-null fields when
     * the incoming value is null, and created_at guard rejects older kind-0 events.
     */
    @Query("""
        UPDATE users SET
            name         = COALESCE(:name,        name),
            display_name = COALESCE(:displayName,  display_name),
            about        = COALESCE(:about,        about),
            picture      = COALESCE(:picture,      picture),
            banner       = COALESCE(:banner,       banner),
            nip05        = COALESCE(:nip05,        nip05),
            lud16        = COALESCE(:lud16,        lud16),
            created_at   = :createdAt,
            updated_at   = :updatedAt
        WHERE pubkey = :pubkey AND :createdAt >= created_at
    """)
    abstract suspend fun updateProfileSafe(
        pubkey: String,
        name: String?,
        displayName: String?,
        about: String?,
        picture: String?,
        banner: String?,
        nip05: String?,
        lud16: String?,
        createdAt: Long,
        updatedAt: Long,
    )

    @Transaction
    open suspend fun upsert(user: UserEntity) {
        insertOrIgnore(user)
        updateProfileSafe(
            pubkey = user.pubkey,
            name = user.name,
            displayName = user.displayName,
            about = user.about,
            picture = user.picture,
            banner = user.banner,
            nip05 = user.nip05,
            lud16 = user.lud16,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
        )
    }

    @Transaction
    open suspend fun upsertBatch(users: List<UserEntity>) {
        for (user in users) {
            upsert(user)
        }
    }

    @Query("SELECT * FROM users WHERE pubkey = :pubkey")
    abstract suspend fun getUser(pubkey: String): UserEntity?

    @Query("SELECT * FROM users WHERE pubkey = :pubkey")
    abstract fun userFlow(pubkey: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE pubkey IN (:pubkeys)")
    abstract suspend fun getUsersByPubkeys(pubkeys: List<String>): List<UserEntity>

    /** Batch existence check — returns only the pubkeys that already exist in Room. */
    @Query("SELECT pubkey FROM users WHERE pubkey IN (:pubkeys)")
    abstract suspend fun getExistingPubkeys(pubkeys: List<String>): List<String>

    @Query("SELECT pubkey FROM users")
    abstract suspend fun allPubkeys(): List<String>

    @Query("SELECT follower_count FROM users WHERE pubkey = :pubkey")
    abstract suspend fun getFollowerCount(pubkey: String): Long?

    @Query("SELECT follower_count_updated_at FROM users WHERE pubkey = :pubkey")
    abstract suspend fun getFollowerCountUpdatedAt(pubkey: String): Long?

    @Query("UPDATE users SET follower_count = :count, follower_count_updated_at = :updatedAt WHERE pubkey = :pubkey")
    abstract suspend fun updateFollowerCount(pubkey: String, count: Long, updatedAt: Long)

    @Query("SELECT pubkey FROM users WHERE updated_at < :olderThan")
    abstract suspend fun stalePubkeys(olderThan: Long): List<String>

    @Query("""
        SELECT * FROM users
        WHERE name         LIKE '%' || :query || '%'
           OR display_name LIKE '%' || :query || '%'
           OR about        LIKE '%' || :query || '%'
        ORDER BY display_name ASC
        LIMIT 50
    """)
    abstract fun searchUsers(query: String): Flow<List<UserEntity>>
}
