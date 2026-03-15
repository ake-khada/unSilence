package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unsilence.app.data.db.entity.EventRelayEntity

@Dao
interface EventRelayDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entity: EventRelayEntity)

    @Query("SELECT relay_url FROM event_relays WHERE event_id = :eventId")
    suspend fun getRelaysForEvent(eventId: String): List<String>
}
