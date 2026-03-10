package com.unsilence.app.data

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.db.AppDatabase
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.GLOBAL_RELAY_URLS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppBootstrapper"

@Singleton
class AppBootstrapper @Inject constructor(
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val appDatabase: AppDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Connect to default relays and fire the initial Nostr requests for the logged-in user.
     * Called after any login path: nsec import, key generation, or Amber.
     */
    fun bootstrap(pubkeyHex: String) {
        relayPool.connect(GLOBAL_RELAY_URLS)
        relayPool.fetchProfiles(listOf(pubkeyHex))
        relayPool.fetchFollowList(pubkeyHex)
        relayPool.fetchRelayLists(listOf(pubkeyHex))
        Log.d(TAG, "Bootstrapped for $pubkeyHex")
    }

    /**
     * Disconnect all relays, wipe the local DB, and clear stored credentials.
     * Called on logout.
     */
    fun teardown() {
        relayPool.disconnectAll()
        keyManager.clear()
        scope.launch { appDatabase.clearAllTables() }
        Log.d(TAG, "Teardown complete")
    }
}
