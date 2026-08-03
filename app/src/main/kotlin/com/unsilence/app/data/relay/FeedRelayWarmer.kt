package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FeedWarmer"
private const val WARM_CAP = 10
private const val FOREGROUND_WARM_RECHECK_DELAY_MS = 6_000L
private const val FOREGROUND_WARM_STAGGER_MS = 200L

/**
 * Pre-warms feed-switcher relays as connected sockets (no subscription).
 * On feed switch, `connectAndAwait` hits the REUSE fast path — saving the
 * WS+TLS handshake (~700ms measured) from the critical path.
 *
 * Warm set: pinned SingleRelay URLs + user's read relays + GLOBAL_RELAY_URLS,
 * deduped, skip-checked, capped at [WARM_CAP]. Reactive: recomputes when
 * pinned relays change.
 *
 * Network-gated via [RelayCapabilitiesStore.isNetworkDown] (OFFLINE || DNS-degraded).
 * Bounded: stays far under POOL_SAFETY_CAP. Sockets only — never sends REQ.
 */
@Singleton
class FeedRelayWarmer @Inject constructor(
    private val relayPool: RelayPool,
    private val memoryEventStore: MemoryEventStore,
    private val keyManager: KeyManager,
    private val relayCapabilitiesStore: RelayCapabilitiesStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var foregroundJob: Job? = null
    @Volatile private var activeWarmUrls: Set<String> = emptySet()

    /**
     * Start the singular reactive collector. Cancel-relaunches on re-login (new pk).
     * Call from AppBootstrapper after bootstrap completes. Only ONE collector lives
     * at a time — safe to call multiple times (cancel-relaunch pattern).
     */
    fun start() {
        val pk = keyManager.getPublicKeyHex() ?: return
        job?.cancel()
        foregroundJob?.cancel()
        job = scope.launch {
            // Carousel relays = the user's kind-10012 favorites (the local pinned store is retired).
            memoryEventStore.favoriteRelayConfigsFlow(pk)
                .collectLatest { favs ->
                    if (relayCapabilitiesStore.isNetworkDown) return@collectLatest
                    recompute(pk, favs.mapNotNull { it.url })
                }
        }
    }

    /**
     * Re-check warm sockets after RelayPool has scheduled its prioritized foreground
     * recovery. The delay avoids racing that recovery and replacing a channel which
     * is already reconnecting; missing warm-only channels are then trickled in.
     */
    fun onForeground() {
        if (relayCapabilitiesStore.isNetworkDown) return
        foregroundJob?.cancel()
        foregroundJob = scope.launch {
            delay(FOREGROUND_WARM_RECHECK_DELAY_MS)
            val urls = activeWarmUrls.toList()
            for ((index, url) in urls.withIndex()) {
                if (relayCapabilitiesStore.isNetworkDown) return@launch
                if (index > 0) delay(FOREGROUND_WARM_STAGGER_MS)
                relayPool.connect(listOf(url))
            }
        }
    }

    /** Cancel a pending warm-only recheck when foreground is lost. */
    fun onBackground() {
        foregroundJob?.cancel()
        foregroundJob = null
    }

    private fun recompute(pk: String, pinnedUrls: List<String>) {
        val readRelays = memoryEventStore.readRelaysFor(pk)
        val candidates = (pinnedUrls + readRelays + GLOBAL_RELAY_URLS)
            .mapNotNull { normalizeRelayUrl(it) }
            .distinct()
            .filter { !relayCapabilitiesStore.shouldSkip(it) }
            .take(WARM_CAP)
            .toSet()

        // Diff: remove purpose from relays no longer in warm set
        val removed = activeWarmUrls - candidates
        for (url in removed) {
            relayPool.removePurpose(url, ConnectionPurpose.FEED_WARM)
        }

        // Add purpose + connect for new entries
        val added = candidates - activeWarmUrls
        for (url in added) {
            relayPool.addPurpose(url, ConnectionPurpose.FEED_WARM)
        }
        if (added.isNotEmpty()) {
            relayPool.connect(added.toList())
        }

        activeWarmUrls = candidates
        Log.d(TAG, "warmed +${added.size} -${removed.size} total=${candidates.size}")
    }
}
