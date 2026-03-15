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

    @Query("""
        UPDATE users SET
            name = :name,
            display_name = :displayName,
            about = :about,
            picture = :picture,
            banner = :banner,
            nip05 = :nip05,
            lud16 = :lud16,
            updated_at = :updatedAt
        WHERE pubkey = :pubkey
    """)
    abstract suspend fun updateProfile(
        pubkey: String,
        name: String?,
        displayName: String?,
        about: String?,
        picture: String?,
        banner: String?,
        nip05: String?,
        lud16: String?,
        updatedAt: Long,
    )

    @Transaction
    open suspend fun upsert(user: UserEntity) {
        insertOrIgnore(user)
        updateProfile(
            pubkey = user.pubkey,
            name = user.name,
            displayName = user.displayName,
            about = user.about,
            picture = user.picture,
            banner = user.banner,
            nip05 = user.nip05,
            lud16 = user.lud16,
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
