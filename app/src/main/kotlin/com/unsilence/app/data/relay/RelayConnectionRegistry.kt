package com.unsilence.app.data.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Includes the client's 10-second connect budget and a bounded upgrade window. */
internal const val RECONNECT_HANDSHAKE_TIMEOUT_MS = 15_000L

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
 * Owns pooled membership, including atomic install/reuse, removal and teardown.
 *
 * A candidate is moved to CONNECTING before the map publishes it, so a second
 * caller can never mistake a just-installed channel for a stale DISCONNECTED
 * one. [lifecycleLock] also closes the foreground/background race: socket
 * creation and the pooled-map update are one lifecycle operation.
 */
internal class RelayConnectionRegistry(
    private val lifecycleLock: Any,
    private val createConnection: (String) -> RelayConnection,
) {
    private val pooled = ConcurrentHashMap<String, RelayConnection>()
    val connections: Map<String, RelayConnection> get() = pooled
    private val membership = MutableStateFlow<List<RelayConnection>>(emptyList())

    /**
     * Derive status from the current sockets, never from a lifecycle-refreshed cache.
     * Collection is demand-driven; membership changes cancel the previous observers,
     * so late callbacks from a removed/replaced socket cannot change its successor.
     * Ephemeral sockets are not registry members and are deliberately excluded.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionStates: Flow<Map<String, RelayState>> = membership.flatMapLatest { snapshot ->
        if (snapshot.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(snapshot.map { it.state }) { states ->
                snapshot.indices.associate { index -> snapshot[index].url to states[index] }
            }
        }
    }.distinctUntilChanged()

    fun acquire(
        url: String,
        transportAllowed: () -> Boolean,
        canCreateNew: () -> Boolean,
        beforeInstall: (url: String, replaced: RelayConnection?) -> Unit = { _, _ -> },
    ): RelayConnectionClaim? = synchronized(lifecycleLock) {
        if (!transportAllowed()) return@synchronized null

        // Capacity policy may evict another URL, so evaluate it outside the
        // ConcurrentHashMap mapping function (CHM forbids recursive updates).
        val observed = pooled[url]
        val mayCreate = observed != null || canCreateNew()

        var installed = false
        var replaced: RelayConnection? = null
        val selected = pooled.compute(url) { _, existing ->
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
        if (installed) membership.value = pooled.values.toList()
        replaced?.close()
        RelayConnectionClaim(selected, installed, replaced)
    }

    /**
     * Finish the handshake we installed, then hand its listener/replay to the pool.
     * Never leave CONNECTING behind for the next retry to mistake for another owner.
     * A terminal failure, timeout or cancellation retires only this exact socket.
     * Background-closed entries stay registered for foreground recovery.
     *
     * Returns whether the caller should retry after releasing its per-URL guard.
     * Callbacks run under the lifecycle lock; they must not suspend.
     */
    suspend fun completeReconnect(
        connection: RelayConnection,
        transportAllowed: () -> Boolean,
        onConnected: (RelayConnection) -> Unit,
        onRetired: (String) -> Unit,
    ): Boolean {
        var handedOff = false
        try {
            withTimeoutOrNull(RECONNECT_HANDSHAKE_TIMEOUT_MS) {
                connection.state.first { it != RelayState.CONNECTING }
            }
            currentCoroutineContext().ensureActive()
            return synchronized(lifecycleLock) {
                if (pooled[connection.url] !== connection) return@synchronized false
                if (!transportAllowed() || !connection.isConnected) return@synchronized true
                onConnected(connection)
                handedOff = true
                false
            }
        } finally {
            if (!handedOff) synchronized(lifecycleLock) {
                // onStop already closed this socket. Retain its URL so reconnectAll
                // sees it; the pool's retry also rechecks foreground eligibility.
                if (transportAllowed() && remove(connection.url, connection) != null) {
                    try {
                        onRetired(connection.url)
                    } finally {
                        connection.close()
                    }
                }
            }
        }
    }

    /** Include retired attempts waiting in backoff, but never replace another handshake. */
    fun reconnectUrls(pending: Collection<String>): List<String> =
        (pooled.keys + pending).filter { url ->
            val state = pooled[url]?.state?.value
            state != RelayState.CONNECTED && state != RelayState.CONNECTING
        }

    /** Detach before the caller closes the socket and clears its bookkeeping. */
    fun remove(url: String, expected: RelayConnection? = null): RelayConnection? =
        synchronized(lifecycleLock) {
            val removed = if (expected == null) {
                pooled.remove(url)
            } else {
                if (!pooled.remove(url, expected)) return@synchronized null
                expected
            } ?: return@synchronized null
            membership.value = pooled.values.toList()
            removed
        }

    /** Return detached sockets for map-before-close teardown by the owner. */
    fun clear(): List<RelayConnection> = synchronized(lifecycleLock) {
        val removed = pooled.values.toList()
        pooled.clear()
        membership.value = emptyList()
        removed
    }
}
