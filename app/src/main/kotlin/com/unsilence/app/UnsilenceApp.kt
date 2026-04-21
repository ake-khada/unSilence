package com.unsilence.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.unsilence.app.data.memory.MesMetricsLogger
import com.unsilence.app.data.memory.SnapshotScheduler
import com.unsilence.app.ui.feed.SharedPlayerHolder
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class UnsilenceApp : Application(), SingletonImageLoader.Factory, androidx.work.Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var snapshotScheduler: SnapshotScheduler
    @Inject lateinit var mesMetricsLogger: MesMetricsLogger
    @Inject lateinit var sharedPlayerHolder: SharedPlayerHolder

    override fun onCreate() {
        super.onCreate()
        snapshotScheduler.attach()
        mesMetricsLogger.attach()

        // Lifecycle-driven codec teardown — replaces the 15s retention timer.
        // Codec stays alive for the entire foreground session; torn down only on
        // app background or memory pressure.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                sharedPlayerHolder.releaseForLifecycle("app backgrounded")
            }
        })
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                when {
                    level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
                        sharedPlayerHolder.release()
                    level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                        sharedPlayerHolder.releaseForLifecycle("memory pressure level=$level")
                }
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            override fun onLowMemory() {
                sharedPlayerHolder.release()
            }
        })
    }

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(64L * 1024 * 1024)  // 64MB cap — was unbounded (25% of app heap default)
                    .build()
            }
            .crossfade(true)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .addInterceptor { chain ->
                                    chain.proceed(
                                        chain.request().newBuilder()
                                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                                            .build()
                                    )
                                }
                                .build()
                        }
                    )
                )
            }
            .build()
    }
}
