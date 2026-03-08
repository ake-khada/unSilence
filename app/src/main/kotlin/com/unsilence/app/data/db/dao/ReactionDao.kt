package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.unsilence.app.data.db.entity.ReactionEntity

@Dao
interface ReactionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(reaction: ReactionEntity)
}
