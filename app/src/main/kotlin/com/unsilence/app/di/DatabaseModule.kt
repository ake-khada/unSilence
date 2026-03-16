package com.unsilence.app.di

import android.content.Context
import androidx.room.Room
import com.unsilence.app.data.db.AppDatabase
import com.unsilence.app.data.db.MIGRATION_1_2
import com.unsilence.app.data.db.MIGRATION_2_3
import com.unsilence.app.data.db.MIGRATION_3_4
import com.unsilence.app.data.db.MIGRATION_4_5
import com.unsilence.app.data.db.MIGRATION_5_6
import com.unsilence.app.data.db.MIGRATION_6_7
import com.unsilence.app.data.db.MIGRATION_7_8
import com.unsilence.app.data.db.MIGRATION_8_9
import com.unsilence.app.data.db.MIGRATION_9_10
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
import com.unsilence.app.data.db.dao.RelaySetDao
import com.unsilence.app.data.db.dao.TagDao
import com.unsilence.app.data.db.dao.UserDao
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "unsilence.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10,
            )
            .build()

    @Provides fun provideEventDao(db: AppDatabase): EventDao = db.eventDao()
    @Provides fun provideUserDao(db: AppDatabase): UserDao = db.userDao()
    @Provides fun provideRelaySetDao(db: AppDatabase): RelaySetDao = db.relaySetDao()
    @Provides fun provideReactionDao(db: AppDatabase): ReactionDao = db.reactionDao()
    @Provides fun provideFollowDao(db: AppDatabase): FollowDao = db.followDao()
    @Provides fun provideRelayListDao(db: AppDatabase): RelayListDao = db.relayListDao()
    @Provides fun provideNotificationsDao(db: AppDatabase): NotificationsDao = db.notificationsDao()
    @Provides fun provideEventStatsDao(db: AppDatabase): EventStatsDao = db.eventStatsDao()
    @Provides fun provideTagDao(db: AppDatabase): TagDao = db.tagDao()
    @Provides fun provideEventRelayDao(db: AppDatabase): EventRelayDao = db.eventRelayDao()
    @Provides fun provideRelayConfigDao(db: AppDatabase): RelayConfigDao = db.relayConfigDao()
    @Provides fun provideNostrRelaySetDao(db: AppDatabase): NostrRelaySetDao = db.nostrRelaySetDao()
    @Provides fun provideCoverageDao(db: AppDatabase): CoverageDao = db.coverageDao()
}
