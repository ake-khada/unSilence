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

    @Query("SELECT * FROM users WHERE pubkey = :pubkey")
    suspend fun getUser(pubkey: String): UserEntity?

    @Query("SELECT * FROM users WHERE pubkey = :pubkey")
    fun userFlow(pubkey: String): Flow<UserEntity?>

    @Query("SELECT pubkey FROM users")
    suspend fun allPubkeys(): List<String>
}
