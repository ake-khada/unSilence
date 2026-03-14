package com.unsilence.app.data

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.db.AppDatabase
import com.unsilence.app.data.db.DatabaseMaintenanceJob
import com.unsilence.app.data.relay.EventProcessor
import com.unsilence.app.data.relay.OutboxRouter
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
    private val eventProcessor: EventProcessor,
    private val outboxRouter: OutboxRouter,
    private val maintenanceJob: DatabaseMaintenanceJob,
    private val signingManager: SigningManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Connect to default relays and fire the initial Nostr requests for the logged-in user.
     * Called after any login path: nsec import, key generation, or Amber.
     */
    fun bootstrap(pubkeyHex: String) {
        eventProcessor.start()
        // OutboxRouter registers kind-3/kind-10002 handlers with EventProcessor
        // and sends fetchFollowList. Must happen BEFORE relay connections open
        // so no early events are missed.
        outboxRouter.start()
        relayPool.connect(GLOBAL_RELAY_URLS)
        relayPool.fetchProfiles(listOf(pubkeyHex))
        relayPool.fetchRelayLists(listOf(pubkeyHex))
        maintenanceJob.start()
        Log.d(TAG, "Bootstrapped for $pubkeyHex")
    }

    /**
     * Full teardown on logout. Order matters:
     * 1. Cancel persistent subs (send CLOSE messages while connections are still alive)
     * 2. Disconnect all WebSockets
     * 3. Clear all Room tables
     * 4. Clear KeyManager (EncryptedSharedPreferences)
     * 5. Cancel child scopes (OutboxRouter, EventProcessor)
     * 6. Reset in-memory state (seenIds, connection map)
     */
    fun teardown() {
        // 1. Cancel persistent subscriptions
        relayPool.clearPersistentSubs()

        // 2. Disconnect all WebSockets
        relayPool.disconnectAll()

        // 3. Clear all Room tables
        scope.launch { appDatabase.clearAllTables() }

        // 4. Clear credentials and cached signer
        keyManager.clear()
        signingManager.clear()

        // 5. Cancel child scopes (NOT this scope — it must survive for next login)
        outboxRouter.stop()
        eventProcessor.stop()
        maintenanceJob.stop()

        // 6. In-memory state already cleared by eventProcessor.stop() (seenIds)
        // and relayPool.disconnectAll() (connections map)

        Log.d(TAG, "Teardown complete")
    }
}
