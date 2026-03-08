package com.unsilence.app.di

import android.content.Context
import androidx.room.Room
import com.unsilence.app.data.db.AppDatabase
import com.unsilence.app.data.db.dao.EventDao
import com.unsilence.app.data.db.dao.ReactionDao
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
            .fallbackToDestructiveMigration()   // dev builds; replace with migrations before release
            .build()

    @Provides fun provideEventDao(db: AppDatabase): EventDao = db.eventDao()
    @Provides fun provideUserDao(db: AppDatabase): UserDao = db.userDao()
    @Provides fun provideRelaySetDao(db: AppDatabase): RelaySetDao = db.relaySetDao()
    @Provides fun provideReactionDao(db: AppDatabase): ReactionDao = db.reactionDao()
}
