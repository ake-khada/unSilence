package com.unsilence.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.unsilence.app.data.db.dao.CoverageDao
import com.unsilence.app.data.db.dao.EventDao
import com.unsilence.app.data.db.dao.EventStatsDao
import com.unsilence.app.data.db.dao.EventRelayDao
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.dao.NostrRelaySetDao
import com.unsilence.app.data.db.dao.NotificationsDao
import com.unsilence.app.data.db.dao.ReactionDao
import com.unsilence.app.data.db.dao.RelayConfigDao
import com.unsilence.app.data.db.dao.RelayListDao
import com.unsilence.app.data.db.dao.TagDao
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.db.entity.CoverageEntity
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.db.entity.EventRelayEntity
import com.unsilence.app.data.db.entity.EventStatsEntity
import com.unsilence.app.data.db.entity.FollowEntity
import com.unsilence.app.data.db.entity.NostrRelaySetEntity
import com.unsilence.app.data.db.entity.NostrRelaySetMemberEntity
import com.unsilence.app.data.db.entity.OwnRelayEntity
import com.unsilence.app.data.db.entity.ReactionEntity
import com.unsilence.app.data.db.entity.RelayConfigEntity
import com.unsilence.app.data.db.entity.RelayListEntity
import com.unsilence.app.data.db.entity.TagEntity
import com.unsilence.app.data.db.entity.UserEntity

@Database(
    entities = [
        EventEntity::class,
        UserEntity::class,
        ReactionEntity::class,
        FollowEntity::class,
        RelayListEntity::class,
        OwnRelayEntity::class,         // kept for migration — dead table after v10
        EventStatsEntity::class,
        TagEntity::class,
        EventRelayEntity::class,
        RelayConfigEntity::class,
        NostrRelaySetEntity::class,
        NostrRelaySetMemberEntity::class,
        CoverageEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun userDao(): UserDao
    abstract fun reactionDao(): ReactionDao
    abstract fun followDao(): FollowDao
    abstract fun relayListDao(): RelayListDao
    abstract fun notificationsDao(): NotificationsDao
    abstract fun eventStatsDao(): EventStatsDao
    abstract fun tagDao(): TagDao
    abstract fun eventRelayDao(): EventRelayDao
    abstract fun relayConfigDao(): RelayConfigDao
    abstract fun nostrRelaySetDao(): NostrRelaySetDao
    abstract fun coverageDao(): CoverageDao
}
