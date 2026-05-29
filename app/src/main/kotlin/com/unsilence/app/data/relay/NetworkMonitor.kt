package com.unsilence.app.data.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NetworkMonitor"

enum class NetworkState { ONLINE, OFFLINE, UNKNOWN }

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

    init {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            // Seed with current state before callback fires
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            _state.value = when {
                active == null -> NetworkState.OFFLINE
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true -> NetworkState.ONLINE
                else -> NetworkState.UNKNOWN
            }

            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _state.value = NetworkState.ONLINE
                    Log.d(TAG, "Network available")
                }

                override fun onLost(network: Network) {
                    _state.value = NetworkState.OFFLINE
                    Log.w(TAG, "Network lost")
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    val validated = capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                    if (validated && _state.value != NetworkState.ONLINE) {
                        _state.value = NetworkState.ONLINE
                        Log.d(TAG, "Network validated")
                    }
                }
            })
        } else {
            Log.w(TAG, "ConnectivityManager unavailable — assuming ONLINE")
            _state.value = NetworkState.ONLINE
        }
    }
}
