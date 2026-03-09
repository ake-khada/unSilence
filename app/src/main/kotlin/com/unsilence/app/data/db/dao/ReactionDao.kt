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

    /** Batch insert for the event pipeline. Room wraps the list insert in a single transaction. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnoreBatch(reactions: List<ReactionEntity>)

    /**
     * All event IDs that [pubkey] has reacted to.
     * Room re-emits on every reactions table change — used by NoteActionsViewModel.
     */
    @Query("SELECT target_event_id FROM reactions WHERE pubkey = :pubkey")
    fun reactedEventIds(pubkey: String): Flow<List<String>>
}
