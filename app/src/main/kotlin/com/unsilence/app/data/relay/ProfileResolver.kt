package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.MemoryEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileResolver"

/**
 * Centralized profile-fetch coordinator.
 *
 * All call sites that need kind-0 profiles should call [request] instead of going
 * directly to RelayPool. This class provides:
 *
 *  1. **In-flight guard** (30 s TTL) — prevents duplicate REQs for the same pubkey.
 *  2. **Room staleness check** (6 h) — skips fetch for recently-updated profiles.
 *  3. **300 ms deadline-anchored batching** — first arrival starts a 300ms window;
 *     all pubkeys arriving within the window merge into one REQ.
 *
 * Actual relay send is delegated to [RelayPool.fetchProfiles], which keeps its own
 * 5-min TTL dedup as a last-resort safety net.
 */
@Singleton
class ProfileResolver @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: dagger.Lazy<RelayPool>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** In-flight guard: pubkey → timestamp of last request dispatch. */
    private val inFlight = ConcurrentHashMap<String, Long>()

    private var requestChannel = Channel<String>(capacity = Channel.UNLIMITED)

    /** Active drain + eviction jobs — cancelled on [clear]. */
    private var drainJob: Job? = null
    private var evictJob: Job? = null

    companion object {
        private const val IN_FLIGHT_TTL_MS = 15_000L
        private const val STALE_THRESHOLD_SECONDS = 6 * 3600L
        private const val BATCH_WINDOW_MS = 300L
        private const val MAX_BATCH_SIZE = 100
        private const val DEFAULT_SCROLL_RELAYS = 3
    }

    init {
        startInternal()
    }

    @Synchronized
    private fun startInternal() {
        if (drainJob?.isActive == true) return
        drainJob = scope.launch { drainLoop() }
        evictJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                val cutoff = System.currentTimeMillis() - IN_FLIGHT_TTL_MS
                inFlight.entries.removeIf { it.value < cutoff }
            }
        }
    }

    /**
     * Request profile fetch for [pubkeys]. Non-blocking — pubkeys are enqueued
     * and batched before hitting the relay. Auto-starts the drain loop if stopped.
     */
    fun request(pubkeys: List<String>) {
        startInternal()
        for (pk in pubkeys) {
            requestChannel.trySend(pk)
        }
    }

    /**
     * Profile-screen fetch: bypasses [processBatch] staleness checks because the
     * user explicitly navigated to this profile. Only [RelayPool.fetchProfiles]'s
     * 2-minute attempt TTL gates redundant requests. Hits [maxRelays] indexer relays
     * for better coverage than the default scroll mode.
     */
    fun requestWithFanout(pubkeys: List<String>, maxRelays: Int = 4) {
        relayPool.get().fetchProfiles(pubkeys, maxRelays)
    }

    /**
     * Returns the subset of [pubkeys] that do NOT have a fresh, complete profile
     * in MemoryEventStore.
     *
     * "Fresh" = profile exists AND was cached locally within STALE_THRESHOLD_SECONDS (6h).
     * "Complete" = profile has a non-blank picture. Profiles without a picture get a
     * shorter 1h retry window — matches the same logic in [processBatch].
     *
     * Uses profileUpdatedAt (local cache time), NOT event createdAt.
     * Used by CardHydrator to skip orchestration for already-resolved pubkeys.
     */
    fun filterUnresolved(pubkeys: Set<String>): Set<String> {
        if (pubkeys.isEmpty()) return emptySet()
        val now = System.currentTimeMillis()
        val freshnessThreshold = now - STALE_THRESHOLD_SECONDS * 1000
        val noPictureThreshold = now - 3600_000L
        return pubkeys.filterTo(mutableSetOf()) { pk ->
            val lastUpdated = memoryEventStore.getProfileLastUpdated(pk)
            if (lastUpdated < freshnessThreshold) return@filterTo true
            // Profile looks fresh by timestamp but may lack actual data.
            // Re-fetch if no picture and last update was >1h ago.
            val user = memoryEventStore.getUserEntity(pk)
            if (user == null) return@filterTo true // timestamp set but no entity — defensive
            user.picture.isNullOrBlank() && lastUpdated < noPictureThreshold
        }
    }

    /** Cancel work, drain queued requests, clear caches. Called on logout. */
    @Synchronized
    fun clear() {
        drainJob?.cancel()
        drainJob = null
        evictJob?.cancel()
        evictJob = null
        // Close old channel (unblocks any suspended receive) and replace
        requestChannel.close()
        requestChannel = Channel(capacity = Channel.UNLIMITED)
        inFlight.clear()
        Log.d(TAG, "Cleared: jobs cancelled, channel reset, in-flight cleared")
    }

    private suspend fun drainLoop() {
        while (true) {
            // Block until the first pubkey arrives — no busy-wait
            val first = requestChannel.receive()
            val batch = mutableSetOf(first)
            val startNs = System.nanoTime()
            val deadlineNs = startNs + BATCH_WINDOW_MS * 1_000_000L

            // Accumulate until deadline or max batch size
            while (batch.size < MAX_BATCH_SIZE) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0) break
                val next = withTimeoutOrNull(remainingNs / 1_000_000L) {
                    requestChannel.receive()
                } ?: break
                batch.add(next)
            }

            val windowMs = (System.nanoTime() - startNs) / 1_000_000L
            processBatch(batch.toList(), windowMs = windowMs)
        }
    }

    private suspend fun processBatch(pubkeys: List<String>, maxRelays: Int = DEFAULT_SCROLL_RELAYS, windowMs: Long = -1) {
        val now = System.currentTimeMillis()

        // 1. In-flight guard
        val notInFlight = pubkeys.filter { pk ->
            val last = inFlight[pk]
            last == null || (now - last) > IN_FLIGHT_TTL_MS
        }
        if (notInFlight.isEmpty()) return

        // 2. MemoryEventStore staleness check — skip profiles cached locally within 6 hours
        //    Profiles with no picture get a shorter 1-hour retry window
        val staleThreshold = now - STALE_THRESHOLD_SECONDS * 1000
        val noPictureThreshold = now - 3600_000L
        val toFetch = mutableListOf<String>()
        for (pk in notInFlight) {
            val lastUpdated = memoryEventStore.getProfileLastUpdated(pk)
            if (lastUpdated < staleThreshold) {
                toFetch.add(pk)
            } else {
                val user = memoryEventStore.getUserEntity(pk)
                if (user != null && user.picture.isNullOrBlank() && lastUpdated < noPictureThreshold) {
                    toFetch.add(pk)
                }
            }
        }

        if (toFetch.isEmpty()) {
            Log.d(TAG, "Batch ${pubkeys.size} → all fresh, skipping")
            return
        }

        // Mark in-flight
        toFetch.forEach { inFlight[it] = now }

        val windowStr = if (windowMs >= 0) " collected in ${windowMs}ms" else ""
        Log.d(TAG, "Batch ${pubkeys.size}$windowStr → ${toFetch.size} to fetch (${pubkeys.size - toFetch.size} fresh/in-flight)")
        relayPool.get().fetchProfiles(toFetch, maxRelays = maxRelays)
    }
}
