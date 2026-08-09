package com.unsilence.app.data.relay

import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates relay connections through one injectable WebSocket boundary.
 *
 * Keeping construction out of [RelayPool] lets JVM tests count and control
 * sockets without opening the network, while production still uses the shared
 * OkHttp client and capability store.
 */
@Singleton
class RelayConnectionFactory @Inject constructor(
    private val client: OkHttpClient,
    private val capabilitiesStore: RelayCapabilitiesStore,
) {
    fun create(url: String): RelayConnection =
        RelayConnection(url, client, capabilitiesStore)
}

internal data class RelayConnectionClaim(
    val connection: RelayConnection,
    val installed: Boolean,
    val replaced: RelayConnection?,
)

/**
 * Owns the atomic install-or-reuse decision for pooled relay connections.
 *
 * A candidate is moved to CONNECTING before the map publishes it, so a second
 * caller can never mistake a just-installed channel for a stale DISCONNECTED
 * one. [lifecycleLock] also closes the foreground/background race: socket
 * creation and the pooled-map update are one lifecycle operation.
 */
internal class RelayConnectionRegistry(
    internal val connections: ConcurrentHashMap<String, RelayConnection>,
    private val lifecycleLock: Any,
    private val createConnection: (String) -> RelayConnection,
) {
    fun acquire(
        url: String,
        transportAllowed: () -> Boolean,
        canCreateNew: () -> Boolean,
        beforeInstall: (url: String, replaced: RelayConnection?) -> Unit = { _, _ -> },
    ): RelayConnectionClaim? = synchronized(lifecycleLock) {
        if (!transportAllowed()) return@synchronized null

        // Capacity policy may evict another URL, so evaluate it outside the
        // ConcurrentHashMap mapping function (CHM forbids recursive updates).
        val observed = connections[url]
        val mayCreate = observed != null || canCreateNew()

        var installed = false
        var replaced: RelayConnection? = null
        val selected = connections.compute(url) { _, existing ->
            if (existing != null &&
                (existing.state.value == RelayState.CONNECTED ||
                    existing.state.value == RelayState.CONNECTING)
            ) {
                return@compute existing
            }
            if (existing == null && !mayCreate) return@compute null

            val candidate = createConnection(url)
            try {
                beforeInstall(url, existing)
                // Start before publication. Concurrent acquires therefore see
                // CONNECTING and reuse this exact object instead of replacing it.
                candidate.connect()
            } catch (t: Throwable) {
                candidate.close()
                throw t
            }
            installed = true
            replaced = existing
            candidate
        } ?: return@synchronized null

        // Map-before-close: ConcurrentHashMap.compute has published the winner.
        // Closing afterward makes the old listener observe identity mismatch.
        replaced?.close()
        RelayConnectionClaim(selected, installed, replaced)
    }
}
