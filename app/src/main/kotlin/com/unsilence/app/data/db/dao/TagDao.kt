package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unsilence.app.data.db.entity.TagEntity

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<TagEntity>)

    @Query("SELECT tag_value FROM tags WHERE event_id = :eventId AND tag_name = :tagName")
    suspend fun getTagValues(eventId: String, tagName: String): List<String>

    @Query("SELECT event_id FROM tags WHERE tag_name = :tagName AND tag_value = :tagValue")
    suspend fun findEventsByTag(tagName: String, tagValue: String): List<String>
}
