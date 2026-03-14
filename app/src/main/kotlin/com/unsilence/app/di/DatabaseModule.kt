package com.unsilence.app.di

import android.content.Context
import androidx.room.Room
import com.unsilence.app.data.db.AppDatabase
import com.unsilence.app.data.db.MIGRATION_1_2
import com.unsilence.app.data.db.MIGRATION_2_3
import com.unsilence.app.data.db.MIGRATION_3_4
import com.unsilence.app.data.db.MIGRATION_4_5
import com.unsilence.app.data.db.MIGRATION_5_6
import com.unsilence.app.data.db.dao.EventDao
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.dao.NotificationsDao
import com.unsilence.app.data.db.dao.ReactionDao
import com.unsilence.app.data.db.dao.RelayListDao
import com.unsilence.app.data.db.dao.RelaySetDao
import com.unsilence.app.data.db.dao.UserDao
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()

    @Provides fun provideEventDao(db: AppDatabase): EventDao = db.eventDao()
    @Provides fun provideUserDao(db: AppDatabase): UserDao = db.userDao()
    @Provides fun provideRelaySetDao(db: AppDatabase): RelaySetDao = db.relaySetDao()
    @Provides fun provideReactionDao(db: AppDatabase): ReactionDao = db.reactionDao()
    @Provides fun provideFollowDao(db: AppDatabase): FollowDao = db.followDao()
    @Provides fun provideRelayListDao(db: AppDatabase): RelayListDao = db.relayListDao()
    @Provides fun provideNotificationsDao(db: AppDatabase): NotificationsDao = db.notificationsDao()
}
