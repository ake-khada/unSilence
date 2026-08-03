package com.unsilence.app.data.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NetworkMonitor"

/** Collapse rapid callbacks (VPN toggle can fire multiple times in seconds). */
private const val NETWORK_CHANGE_DEBOUNCE_MS = 2_000L

enum class NetworkState { ONLINE, OFFLINE, UNKNOWN }

data class NetworkConditions(
    val state: NetworkState = NetworkState.UNKNOWN,
    val isMetered: Boolean = true,
    val isCellular: Boolean = false,
) {
    /** Conservative gate for speculative work; user-requested loads still run. */
    val isConstrained: Boolean
        get() = state != NetworkState.ONLINE || isMetered || isCellular
}

/**
 * Wraps [ConnectivityManager.registerDefaultNetworkCallback] to expose
 * the device's network reachability as a [StateFlow].
 *
 * Note: on censored networks, ConnectivityManager may report ONLINE even
 * when specific hosts are blocked. The DNS-degraded heuristic in
 * [RelayCapabilitiesStore] catches that case by looking at per-relay
 * DNS failure patterns.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val _state = MutableStateFlow(NetworkState.UNKNOWN)
    val state: StateFlow<NetworkState> = _state.asStateFlow()
    private val _conditions = MutableStateFlow(NetworkConditions())
    val conditions: StateFlow<NetworkConditions> = _conditions.asStateFlow()
    val currentConditions: NetworkConditions get() = _conditions.value

    /** Emits when the default network identity changes (VPN toggle, WiFi↔cellular).
     *  DNS resolvability is a property of the current network — relay DNS-dead state
     *  should be re-evaluated on every identity change. */
    private val _networkChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val networkChanged: SharedFlow<Unit> = _networkChanged.asSharedFlow()

    private val lastDefaultNetwork = AtomicReference<Network?>(null)
    private val lastNetworkChangedAt = AtomicLong(0L)

    init {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            fun updateConditions(state: NetworkState, caps: NetworkCapabilities?) {
                _state.value = state
                _conditions.value = NetworkConditions(
                    state = state,
                    isMetered = runCatching { cm.isActiveNetworkMetered }.getOrDefault(true),
                    isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
                )
            }
            // Seed with current state before callback fires
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            lastDefaultNetwork.set(active)
            val initialState = when {
                active == null -> NetworkState.OFFLINE
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true -> NetworkState.ONLINE
                else -> NetworkState.UNKNOWN
            }
            updateConditions(initialState, caps)

            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val prev = lastDefaultNetwork.getAndSet(network)
                    val currentCaps = cm.getNetworkCapabilities(network)
                    val availableState = if (
                        currentCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    ) NetworkState.ONLINE else NetworkState.UNKNOWN
                    updateConditions(availableState, currentCaps)
                    if (prev != null && prev != network) {
                        val now = System.currentTimeMillis()
                        if (now - lastNetworkChangedAt.get() >= NETWORK_CHANGE_DEBOUNCE_MS) {
                            lastNetworkChangedAt.set(now)
                            _networkChanged.tryEmit(Unit)
                            Log.w(TAG, "Default network changed — DNS resolvability may differ")
                        }
                    }
                    Log.d(TAG, "Network available")
                }

                override fun onLost(network: Network) {
                    // Android can report the old default network as lost after a
                    // replacement has already become available. Only the network
                    // we still consider current may move the app to OFFLINE.
                    if (lastDefaultNetwork.compareAndSet(network, null)) {
                        updateConditions(NetworkState.OFFLINE, null)
                        Log.w(TAG, "Default network lost")
                    } else {
                        Log.d(TAG, "Ignoring loss of superseded network")
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    val current = lastDefaultNetwork.get()
                    if (current != null && current != network) return
                    lastDefaultNetwork.compareAndSet(null, network)
                    val validated = capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                    val nextState = if (validated) NetworkState.ONLINE else NetworkState.UNKNOWN
                    updateConditions(nextState, capabilities)
                    if (validated) {
                        Log.d(TAG, "Network validated")
                    }
                }
            })
        } else {
            Log.w(TAG, "ConnectivityManager unavailable — assuming ONLINE")
            _state.value = NetworkState.ONLINE
            _conditions.value = NetworkConditions(
                state = NetworkState.ONLINE,
                isMetered = true,
                isCellular = false,
            )
        }
    }
}
