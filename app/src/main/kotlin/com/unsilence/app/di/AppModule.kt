package com.unsilence.app.di

import android.content.ContentResolver
import android.content.Context
import androidx.core.util.AtomicFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)    // WebSocket: no read timeout
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)  // keep-alive
            .build()

    @Provides
    @Singleton
    @MediaClient
    fun provideMediaClient(baseClient: OkHttpClient): OkHttpClient {
        val mediaDispatcher = Dispatcher().apply {
            maxRequests = 8
            maxRequestsPerHost = 4
        }
        return baseClient.newBuilder()
            .dispatcher(mediaDispatcher)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)  // no overall call timeout for streaming
            .pingInterval(0, TimeUnit.SECONDS) // no keep-alive needed for media HTTP
            .build()
    }

    @Provides
    @Singleton
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    @Provides
    @Singleton
    fun provideSnapshotFile(@ApplicationContext context: Context): AtomicFile {
        val dir = File(context.filesDir, "snapshots")
        dir.mkdirs()
        return AtomicFile(File(dir, "memory_events.snapshot"))
    }
}
