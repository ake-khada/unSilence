package com.unsilence.app.di

import android.content.ContentResolver
import android.content.Context
import androidx.core.util.AtomicFile
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.unsilence.app.data.network.UntrustedHttpNetworkGuard
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

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImageClient

private const val VIDEO_CACHE_DIRECTORY = "video-cache"
private const val VIDEO_CACHE_MAX_BYTES = 200L * 1024L * 1024L

@Module
@InstallIn(SingletonComponent::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
            .addNetworkInterceptor(UntrustedHttpNetworkGuard)
            .build()
    }

    @Provides
    @Singleton
    fun provideVideoCache(@ApplicationContext context: Context): SimpleCache =
        SimpleCache(
            File(context.cacheDir, VIDEO_CACHE_DIRECTORY),
            LeastRecentlyUsedCacheEvictor(VIDEO_CACHE_MAX_BYTES),
            StandaloneDatabaseProvider(context),
        )

    @Provides
    @Singleton
    fun provideMediaDataSourceFactory(
        videoCache: SimpleCache,
        @MediaClient mediaClient: OkHttpClient,
    ): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(videoCache)
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(mediaClient))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    @Provides
    @Singleton
    @ImageClient
    fun provideImageClient(baseClient: OkHttpClient): OkHttpClient {
        val imageDispatcher = Dispatcher().apply {
            maxRequests = 12
            maxRequestsPerHost = 6
        }
        return baseClient.newBuilder()
            .dispatcher(imageDispatcher)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .addNetworkInterceptor(UntrustedHttpNetworkGuard)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", com.unsilence.app.data.BROWSER_USER_AGENT)
                        .build()
                )
            }
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
