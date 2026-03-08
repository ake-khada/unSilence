package com.unsilence.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.unsilence.app.data.db.dao.EventDao
import com.unsilence.app.data.db.dao.ReactionDao
import com.unsilence.app.data.db.dao.RelaySetDao
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.db.entity.ReactionEntity
import com.unsilence.app.data.db.entity.RelaySetEntity
import com.unsilence.app.data.db.entity.UserEntity

@Database(
    entities = [
        EventEntity::class,
        UserEntity::class,
        RelaySetEntity::class,
        ReactionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun userDao(): UserDao
    abstract fun relaySetDao(): RelaySetDao
    abstract fun reactionDao(): ReactionDao
}
