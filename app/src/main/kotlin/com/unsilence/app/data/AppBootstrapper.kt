package com.unsilence.app.data

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.db.AppDatabase
import com.unsilence.app.data.db.DatabaseMaintenanceJob
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.relay.EventProcessor
import com.unsilence.app.data.relay.OutboxRouter
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.GLOBAL_RELAY_URLS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppBootstrapper"

/** Indexer relays that specialise in kind 0/3/10002 metadata. */
private val INDEXER_RELAY_URLS = listOf(
    "wss://purplepag.es",
    "wss://user.kindpag.es",
    "wss://indexer.coracle.social",
)

@Singleton
class AppBootstrapper @Inject constructor(
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val appDatabase: AppDatabase,
    private val eventProcessor: EventProcessor,
    private val outboxRouter: OutboxRouter,
    private val maintenanceJob: DatabaseMaintenanceJob,
    private val signingManager: SigningManager,
    private val followDao: FollowDao,
    private val userDao: UserDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Sequential bootstrap for the logged-in user.
     *
     * Each step completes (or times out) before the next starts:
     * 1. Connect to indexer relays → wait for at least one connection
     * 2. Fetch kind-3 (contact list) → wait for follows to appear in Room
     * 3. Fetch kind-0 (own profile) → wait for profile to appear in Room
     * 4. Fetch kind-10002 (relay list) → fire and let OutboxRouter handle reactively
     * 5. Connect to global relays → opens persistent feed subscriptions
     */
    suspend fun bootstrap(pubkeyHex: String) {
        eventProcessor.start()
        // Register kind-3/kind-10002 handlers before any relay events can arrive
        outboxRouter.start()

        // ── Step 1: Connect to indexer relays ───────────────────────────────
        val ready = relayPool.connectAndAwait(INDEXER_RELAY_URLS, timeoutMs = 5_000)
        Log.d(TAG, "Step 1: $ready indexer relay(s) connected")

        // ── Step 2: Fetch kind-3, wait for follows ──────────────────────────
        relayPool.fetchFollowList(pubkeyHex)
        val follows = withTimeoutOrNull(10_000L) {
            followDao.followsFlow().filter { it.isNotEmpty() }.first()
        }
        Log.d(TAG, "Step 2: ${follows?.size ?: 0} follows loaded")

        // ── Step 2b: Fetch profiles for all followed pubkeys ──────────────
        // Preloads display names and avatars so the feed shows names, not hex.
        // Includes own pubkey. No need to block — Room flows update reactively.
        val followPubkeys = follows?.map { it.pubkey }.orEmpty() + pubkeyHex
        relayPool.fetchProfiles(followPubkeys.distinct())
        Log.d(TAG, "Step 2b: requested ${followPubkeys.size} profiles")

        // ── Step 3: Wait for own profile to arrive ────────────────────────
        val profile = withTimeoutOrNull(10_000L) {
            userDao.userFlow(pubkeyHex).filterNotNull().first()
        }
        Log.d(TAG, "Step 3: profile ${if (profile != null) "loaded" else "timeout"}")

        // ── Step 4: Fetch kind-10002 (relay list) ───────────────────────────
        // OutboxRouter reactively observes relayListDao and connects to write
        // relays when kind-10002 data arrives. No need to block here.
        relayPool.fetchRelayLists(listOf(pubkeyHex))
        Log.d(TAG, "Step 4: kind-10002 requested")

        // ── Step 5: Connect to global relays (feed subscriptions) ───────────
        relayPool.connect(GLOBAL_RELAY_URLS)
        Log.d(TAG, "Step 5: global relays connecting")

        maintenanceJob.start()
        Log.d(TAG, "Bootstrap complete for $pubkeyHex")
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
