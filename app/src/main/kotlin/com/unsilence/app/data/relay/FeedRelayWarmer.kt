package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FeedWarmer"
private const val WARM_CAP = 10

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
    private val relayPreferencesStore: RelayPreferencesStore,
    private val memoryEventStore: MemoryEventStore,
    private val keyManager: KeyManager,
    private val relayCapabilitiesStore: RelayCapabilitiesStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var activeWarmUrls: Set<String> = emptySet()

    /**
     * Start the singular reactive collector. Cancel-relaunches on re-login (new pk).
     * Call from AppBootstrapper after bootstrap completes. Only ONE collector lives
     * at a time — safe to call multiple times (cancel-relaunch pattern).
     */
    fun start() {
        val pk = keyManager.getPublicKeyHex() ?: return
        job?.cancel()
        job = scope.launch {
            relayPreferencesStore.pinnedRelaysFlow(pk)
                .collectLatest { pinned ->
                    if (relayCapabilitiesStore.isNetworkDown) return@collectLatest
                    recompute(pk, pinned.map { it.url })
                }
        }
    }

    /**
     * Re-warm sockets dropped during background. Idempotent — [relayPool.connect]
     * is REUSE for live sockets, NEW only for reaped ones. No purpose/diff changes.
     * Call from UnsilenceApp ON_START.
     */
    fun onForeground() {
        if (relayCapabilitiesStore.isNetworkDown) return
        val urls = activeWarmUrls.toList()
        if (urls.isEmpty()) return
        relayPool.connect(urls)
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
        Log.w(TAG, "warmed +${added.size} -${removed.size} total=${candidates.size}")
    }
}
