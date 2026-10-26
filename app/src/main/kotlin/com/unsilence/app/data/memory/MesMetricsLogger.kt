package com.unsilence.app.data.memory

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.os.Debug
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.unsilence.app.data.relay.RelayTrafficMetrics
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.Subscription
import com.unsilence.app.ui.feed.ImageDimensionCache
import com.unsilence.app.ui.feed.VideoThumbnailCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MES/size"
private const val INTERVAL_MS = 60_000L

/**
 * Periodic logger for MemoryEventStore + media cache sizes.
 *
 * Runs every 60s while foregrounded (ProcessLifecycleOwner ON_START → ON_STOP).
 * Emits release-visible warning-level probes to logcat. The one-minute cadence
 * keeps field overhead bounded while retaining evidence through R8.
 */
@Singleton
class MesMetricsLogger @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val videoThumbnailCache: VideoThumbnailCache,
    private val imageDimensionCache: ImageDimensionCache,
    private val subscription: Subscription,
    trafficMetrics: RelayTrafficMetrics,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var periodicJob: Job? = null
    private val writer = Mutex()
    private val trafficSnapshot = RelayTrafficSnapshot(trafficMetrics)

    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        startPeriodicLog()
    }

    override fun onStop(owner: LifecycleOwner) {
        periodicJob?.cancel()
        periodicJob = null
        // One finite, cheap stop sample; no MES traversal and no new worker loop.
        scope.launch { writer.withLock { logTraffic("stop") } }
    }

    private fun startPeriodicLog() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (true) {
                delay(INTERVAL_MS)
                writer.withLock {
                    logTraffic("periodic")
                    log()
                }
            }
        }
    }

    private fun logTraffic(reason: String) {
        val uid = Process.myUid()
        Log.w("MES/traffic", trafficSnapshot.sample(
            SystemClock.elapsedRealtime(), Process.myPid(), uid,
            TrafficStats.getUidRxBytes(uid), TrafficStats.getUidTxBytes(uid), reason,
        ))
    }

    private fun log() {
        val s = memoryEventStore.snapshotSize()
        val totalMb = s.totalEstimatedBytes / (1024.0 * 1024.0)
        val runtime = Runtime.getRuntime()
        val heapUsedBytes = runtime.totalMemory() - runtime.freeMemory()
        val heapCommittedBytes = runtime.totalMemory()
        val heapMaxBytes = runtime.maxMemory()
        val nativeHeapBytes = Debug.getNativeHeapAllocatedSize()

        // Video thumbnail cache
        val vtEntries = videoThumbnailCache.entryCount
        val vtMb = videoThumbnailCache.bitmapBytes / (1024.0 * 1024.0)

        // Image dimension cache
        val idcEntries = imageDimensionCache.entryCount

        // Summary line
        Log.w(
            TAG,
            "events=${s.eventCount} (~${mb(s.eventBytes)}) " +
                "profiles=${s.profileCount} (~${mb(s.profileBytes)}) " +
                "videoRM=${s.videoRenderModelEntries} imetaDims=${s.imetaImageDimEntries} " +
                "eventModels=${s.eventModelEntries} " +
                "feedRowCache=${s.feedRowCacheEntries} " +
                "mesTotal~${String.format("%.1f", totalMb)}mb " +
                "heapUsed=${mb(heapUsedBytes)} heapCommitted=${mb(heapCommittedBytes)} " +
                "heapMax=${mb(heapMaxBytes)} nativeHeap=${mb(nativeHeapBytes)}",
        )

        // Per-kind breakdown
        val kindStr = s.eventsByKind.entries
            .sortedByDescending { it.value }
            .joinToString(" ") { (k, v) -> "k$k=$v" }
        Log.w(TAG, "kinds: $kindStr")

        // Actor indexes
        Log.w(
            TAG,
            "actors: reacted=${s.reactedActors}/${s.reactedTargetsTotal} " +
                "reposted=${s.repostedActors}/${s.repostedTargetsTotal} " +
                "zapped=${s.zappedActors}/${s.zappedTargetsTotal}",
        )

        // External caches
        Log.w(
            TAG,
            "caches: videoThumb=${vtEntries} (~${String.format("%.1f", vtMb)}mb) " +
                "imgDim=${idcEntries} " +
                "follows=${s.followsEntries} followerCount=${s.followerCountEntries} " +
                "relayLists=${s.relayListEntries} " +
                "monitors=${s.relayMonitorEntries} identities=${s.relayIdentityEntries} " +
                "relaySets=${s.relaySetEntries}",
        )

        // Interval work plus the last pass's live-ref union. Tier 1 is the
        // strongest bounded protection; tier 3 is the expected eviction pool.
        val eviction = memoryEventStore.snapshotEvictionMetrics()
        val evictionKindStr = eviction.evictedByKind.entries
            .sortedBy { it.key }
            .joinToString(",") { (kind, count) -> "k$kind:$count" }
            .ifEmpty { "none" }
        val rejectedKindStr = eviction.admissionRejectedByKind.entries
            .sortedBy { it.key }
            .joinToString(",") { (kind, count) -> "k$kind:$count" }
            .ifEmpty { "none" }
        Log.w(
            TAG,
            "eviction: passes=${eviction.passes} evicted=${eviction.evicted} " +
                "tier1=${eviction.tier1} tier2=${eviction.tier2} tier3=${eviction.tier3} " +
                "requested=${eviction.requested} " +
                "admitReplaced=${eviction.admissionReplaced} " +
                "admitRejected=${eviction.admissionRejected} " +
                "sweepEvicted=${eviction.evicted - eviction.admissionReplaced} " +
                "evictedByKind=$evictionKindStr " +
                "rejectedByKind=$rejectedKindStr " +
                "lastPassAnchoredOwn=${eviction.anchoredOwn} " +
                "lastPassAnchoredMentioned=${eviction.anchoredMentioned} " +
                "lastPassAnchoredViewed=${eviction.anchoredViewed} " +
                "lastPassAnchoredProfileRefs=${eviction.anchoredProfileRefs} " +
                "profileAnchorSet=${s.profileAnchoredRefEntries} " +
                "liveTimelineRefs=${eviction.liveTimelineRefs}",
        )

        // Relay fetch dedup metrics (resets peaks/counters on each snapshot)
        val rm = relayPool.snapshotRelayMetrics()
        Log.w(
            TAG,
            "relay: inFlightPeak=${rm.eventFetchInFlightPeak} " +
                "missingRefCache=${rm.missingRefCacheSize} " +
                "missingRefHits=${rm.missingRefCacheHits} " +
                "idLookupSent=${rm.idLookupSent} " +
                "idBackoffSkips=${rm.idLookupBackoffSkips} " +
                "idBackoffOpened=${rm.idLookupBackoffOpened} " +
                "idBackoffRelays=${rm.idLookupBackoffRelays} " +
                "timelineCatchUpReqs=${subscription.snapshotCatchUpRequestCount()}",
        )
    }

    private fun mb(bytes: Long): String =
        String.format("%.1f", bytes / (1024.0 * 1024.0)) + "mb"
}
