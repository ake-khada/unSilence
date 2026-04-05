package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.db.dao.UserDao
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
 *  3. **200 ms batching** — collects pubkeys into a single REQ instead of N individual ones.
 *
 * Actual relay send is delegated to [RelayPool.fetchProfiles], which keeps its own
 * 5-min TTL dedup as a last-resort safety net.
 */
@Singleton
class ProfileResolver @Inject constructor(
    private val userDao: UserDao,
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
        private const val BATCH_WINDOW_MS = 200L
        private const val MAX_BATCH_SIZE = 150
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
     * Profile-screen fetch: same dedup/staleness checks, but hits [maxRelays]
     * indexer relays instead of the default 1 (scroll mode).
     */
    fun requestWithFanout(pubkeys: List<String>, maxRelays: Int = 4) {
        scope.launch { processBatch(pubkeys, maxRelays) }
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
        val batch = mutableSetOf<String>()
        while (true) {
            // Wait for first item
            val first = withTimeoutOrNull(BATCH_WINDOW_MS) { requestChannel.receive() }
            if (first != null) {
                batch.add(first)
                // Drain any queued items without blocking
                while (batch.size < MAX_BATCH_SIZE) {
                    val next = requestChannel.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }
            }
            if (batch.isNotEmpty()) {
                processBatch(batch.toList())
                batch.clear()
            }
        }
    }

    private suspend fun processBatch(pubkeys: List<String>, maxRelays: Int = DEFAULT_SCROLL_RELAYS) {
        val now = System.currentTimeMillis()

        // 1. In-flight guard
        val notInFlight = pubkeys.filter { pk ->
            val last = inFlight[pk]
            last == null || (now - last) > IN_FLIGHT_TTL_MS
        }
        if (notInFlight.isEmpty()) return

        // 2. Room staleness check — skip profiles updated within 6 hours
        //    Profiles with no avatar get a shorter 1-hour retry window
        val staleThreshold = now / 1000 - STALE_THRESHOLD_SECONDS
        val noPictureThreshold = now / 1000 - 3600L
        // Batch lookup in chunks of 999 (SQLite IN clause limit)
        val toFetch = mutableListOf<String>()
        for (chunk in notInFlight.chunked(999)) {
            val existing = userDao.getUsersByPubkeys(chunk).associateBy { it.pubkey }
            for (pk in chunk) {
                val user = existing[pk]
                if (user == null || user.updatedAt < staleThreshold ||
                    (user.picture.isNullOrBlank() && user.updatedAt < noPictureThreshold)) {
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

        Log.d(TAG, "Batch ${pubkeys.size} → ${toFetch.size} to fetch (${pubkeys.size - toFetch.size} fresh/in-flight)")
        relayPool.get().fetchProfiles(toFetch, maxRelays = maxRelays)
    }
}
