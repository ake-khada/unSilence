package com.unsilence.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.unsilence.app.data.db.dao.EventDao
import com.unsilence.app.data.db.dao.EventStatsDao
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.dao.ReactionDao
import com.unsilence.app.data.db.dao.RelayListDao
import com.unsilence.app.data.db.dao.RelayTrustScoreDao
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.db.entity.EventRelayEntity
import com.unsilence.app.data.db.entity.EventStatsEntity
import com.unsilence.app.data.db.entity.FollowEntity
import com.unsilence.app.data.db.entity.ReactionEntity
import com.unsilence.app.data.db.entity.RelayListEntity
import com.unsilence.app.data.db.entity.RelayTrustScoreEntity
import com.unsilence.app.data.db.entity.TagEntity
import com.unsilence.app.data.db.entity.UserEntity

@Database(
    entities = [
        EventEntity::class,
        UserEntity::class,
        ReactionEntity::class,
        FollowEntity::class,
        RelayListEntity::class,
        EventStatsEntity::class,
        TagEntity::class,
        EventRelayEntity::class,
        RelayTrustScoreEntity::class,
    ],
    version = 19,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun userDao(): UserDao
    abstract fun reactionDao(): ReactionDao
    abstract fun followDao(): FollowDao
    abstract fun relayListDao(): RelayListDao
    abstract fun eventStatsDao(): EventStatsDao
    abstract fun relayTrustScoreDao(): RelayTrustScoreDao
}
