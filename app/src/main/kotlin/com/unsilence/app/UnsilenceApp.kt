package com.unsilence.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.gif.GifDecoder
import coil3.video.VideoFrameDecoder
import coil3.bitmapFactoryMaxParallelism
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.unsilence.app.data.memory.MesMetricsLogger
import com.unsilence.app.data.memory.SnapshotScheduler
import com.unsilence.app.data.media.DirectBufferAnimatedImageDecoderFactory
import com.unsilence.app.data.relay.FeedRelayWarmer
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.Subscription
import com.unsilence.app.ui.feed.SharedPlayerHolder
import com.unsilence.app.di.ImageClient
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class UnsilenceApp : Application(), SingletonImageLoader.Factory, androidx.work.Configuration.Provider {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ImageClientEntryPoint {
        @ImageClient fun imageClient(): OkHttpClient
    }

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var snapshotScheduler: SnapshotScheduler
    @Inject lateinit var mesMetricsLogger: MesMetricsLogger
    @Inject lateinit var relayPool: RelayPool
    @Inject lateinit var feedRelayWarmer: FeedRelayWarmer
    @Inject lateinit var sharedPlayerHolder: SharedPlayerHolder
    @Inject lateinit var subscription: Subscription

    override fun onCreate() {
        super.onCreate()

        // Closeable-leak diagnostic in debug builds. Field logs show sustained
        // 'A resource failed to call close' bursts from the FinalizerDaemon
        // after every ~30-60s GC, but without stack traces it's impossible to
        // pinpoint which OkHttp Response / FileInputStream / etc. wasn't closed.
        // detectLeakedClosableObjects + penaltyLog gives us a stack trace at
        // the point of leak in Android's StrictMode log channel. Debug-only:
        // production builds keep the original quieter behaviour. Detected via
        // FLAG_DEBUGGABLE on the application — works without a buildConfig step.
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }

        snapshotScheduler.attach()
        mesMetricsLogger.attach()

        // Lifecycle-driven codec teardown — replaces the 15s retention timer.
        // Codec stays alive for the entire foreground session; torn down only on
        // app background or memory pressure.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                subscription.pauseAll()
                feedRelayWarmer.onBackground()
                relayPool.suspendSocketsForBackground()
                sharedPlayerHolder.releaseForLifecycle("app backgrounded")
            }
            override fun onStart(owner: LifecycleOwner) {
                relayPool.resumeSocketsForForeground()
                subscription.resumeAll()
                feedRelayWarmer.onForeground()
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
        val imageClient = EntryPointAccessors
            .fromApplication(this, ImageClientEntryPoint::class.java)
            .imageClient()
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    // Keep back-scroll reuse without letting decoded feed images
                    // dominate the normal application heap.
                    .maxSizePercent(context, 0.08)
                    .build()
            }
            .bitmapFactoryMaxParallelism(3)
            .allowHardware(true)
            .allowRgb565(true)
            .crossfade(false)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { imageClient }))
                if (Build.VERSION.SDK_INT >= 28) {
                    add(DirectBufferAnimatedImageDecoderFactory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }
}
