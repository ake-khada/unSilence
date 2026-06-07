package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * pinned relays or read-relay config change.
 *
 * Network-gated: no-ops when DNS-degraded or offline. Bounded: stays far
 * under POOL_SAFETY_CAP. Sockets only — never sends REQ to warm relays.
 */
@Singleton
class FeedRelayWarmer @Inject constructor(
    private val relayPool: RelayPool,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val memoryEventStore: MemoryEventStore,
    private val networkMonitor: NetworkMonitor,
    private val keyManager: KeyManager,
    private val relayCapabilitiesStore: RelayCapabilitiesStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeWarmUrls: Set<String> = emptySet()

    /**
     * Start reactive warming. Call once after bootstrap completes.
     * Recomputes warm set when pinned relays or relay metadata change.
     */
    fun start() {
        val pk = keyManager.getPublicKeyHex() ?: return
        scope.launch {
            relayPreferencesStore.pinnedRelaysFlow(pk)
                .collectLatest { pinned ->
                    if (networkMonitor.state.value == NetworkState.OFFLINE) return@collectLatest
                    recompute(pk, pinned.map { it.url })
                }
        }
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
            Log.d(TAG, "Warmed ${added.size} relay(s), total warm=${candidates.size}")
        }

        activeWarmUrls = candidates
    }
}
