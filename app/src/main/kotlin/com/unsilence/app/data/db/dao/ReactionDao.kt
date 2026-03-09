package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unsilence.app.data.db.entity.ReactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(reaction: ReactionEntity)

    /**
     * All event IDs that [pubkey] has reacted to.
     * Room re-emits on every reactions table change — used by NoteActionsViewModel.
     */
    @Query("SELECT target_event_id FROM reactions WHERE pubkey = :pubkey")
    fun reactedEventIds(pubkey: String): Flow<List<String>>
}
