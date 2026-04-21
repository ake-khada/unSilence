package com.unsilence.app.data.memory

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.ui.feed.ImageDimensionCache
import com.unsilence.app.ui.feed.VideoThumbnailCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MES/size"
private const val INTERVAL_MS = 60_000L

/**
 * Periodic logger for MemoryEventStore + media cache sizes.
 *
 * Runs every 60s while foregrounded (ProcessLifecycleOwner ON_START → ON_STOP).
 * Emits to logcat only — no new dependencies, no measurable overhead.
 */
@Singleton
class MesMetricsLogger @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val videoThumbnailCache: VideoThumbnailCache,
    private val imageDimensionCache: ImageDimensionCache,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var periodicJob: Job? = null

    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        startPeriodicLog()
    }

    override fun onStop(owner: LifecycleOwner) {
        periodicJob?.cancel()
        periodicJob = null
    }

    private fun startPeriodicLog() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (true) {
                delay(INTERVAL_MS)
                log()
            }
        }
    }

    private fun log() {
        val s = memoryEventStore.snapshotSize()
        val totalMb = s.totalEstimatedBytes / (1024.0 * 1024.0)

        // Video thumbnail cache
        val vtEntries = videoThumbnailCache.entryCount
        val vtMb = videoThumbnailCache.bitmapBytes / (1024.0 * 1024.0)

        // Image dimension cache
        val idcEntries = imageDimensionCache.entryCount

        // Summary line
        Log.d(
            TAG,
            "events=${s.eventCount} (~${mb(s.eventBytes)}) " +
                "profiles=${s.profileCount} (~${mb(s.profileBytes)}) " +
                "videoRM=${s.videoRenderModelEntries} imetaDims=${s.imetaImageDimEntries} " +
                "feedRowCache=${s.feedRowCacheEntries} " +
                "total~${String.format("%.1f", totalMb)}mb",
        )

        // Per-kind breakdown
        val kindStr = s.eventsByKind.entries
            .sortedByDescending { it.value }
            .joinToString(" ") { (k, v) -> "k$k=$v" }
        Log.d(TAG, "kinds: $kindStr")

        // Actor indexes
        Log.d(
            TAG,
            "actors: reacted=${s.reactedActors}/${s.reactedTargetsTotal} " +
                "reposted=${s.repostedActors}/${s.repostedTargetsTotal} " +
                "zapped=${s.zappedActors}/${s.zappedTargetsTotal}",
        )

        // External caches
        Log.d(
            TAG,
            "caches: videoThumb=${vtEntries} (~${String.format("%.1f", vtMb)}mb) " +
                "imgDim=${idcEntries} " +
                "follows=${s.followsEntries} followerCount=${s.followerCountEntries} " +
                "relayLists=${s.relayListEntries} trustScores=${s.trustScoreEntries} " +
                "monitors=${s.relayMonitorEntries} relaySets=${s.relaySetEntries}",
        )

        // Eviction anchor counters (cumulative since last snapshot, then reset)
        val (anchoredOwn, anchoredMentioned, anchoredViewed) = memoryEventStore.snapshotEvictionAnchors()
        if (anchoredOwn + anchoredMentioned + anchoredViewed > 0) {
            Log.d(TAG, "eviction: anchored own=$anchoredOwn mentioned=$anchoredMentioned viewed=$anchoredViewed")
        }

        // Relay fetch dedup metrics (resets peaks/counters on each snapshot)
        val rm = relayPool.snapshotRelayMetrics()
        Log.d(
            TAG,
            "relay: inFlightPeak=${rm.eventFetchInFlightPeak} " +
                "missingRefCache=${rm.missingRefCacheSize} " +
                "missingRefHits=${rm.missingRefCacheHits}",
        )
    }

    private fun mb(bytes: Long): String =
        String.format("%.1f", bytes / (1024.0 * 1024.0)) + "mb"
}
