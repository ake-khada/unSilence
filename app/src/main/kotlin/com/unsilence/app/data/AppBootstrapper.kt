package com.unsilence.app.data

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.db.DatabaseMaintenanceJob
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.dao.RelayConfigDao
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.wallet.NwcManager
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.EventProcessor
import com.unsilence.app.data.relay.OutboxRouter
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.ui.feed.SharedPlayerHolder
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppBootstrapper"

/** Indexer relays that specialise in kind 0/3/10002 metadata. */
private val INDEXER_RELAY_URLS = listOf(
    "wss://purplepag.es",
    "wss://user.kindpag.es",
    "wss://indexer.coracle.social",
    "wss://antiprimal.net",
)

@Singleton
class AppBootstrapper @Inject constructor(
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val eventProcessor: EventProcessor,
    private val outboxRouter: OutboxRouter,
    private val maintenanceJob: DatabaseMaintenanceJob,
    private val signingManager: SigningManager,
    private val followDao: FollowDao,
    private val relayConfigDao: RelayConfigDao,
    private val userDao: UserDao,
    private val nwcManager: NwcManager,
    private val sharedPlayerHolder: SharedPlayerHolder,
    private val cardHydrator: CardHydrator,
) {
    /**
     * Sequential bootstrap for the logged-in user.
     *
     * Each step completes (or times out) before the next starts:
     * 1. Connect to indexer relays → wait for at least one connection
     * 2. Fetch kind-3 (contact list) → wait for follows to appear in Room
     * 3. Fetch kind-0 (own profile) → wait for profile to appear in Room
     * 4. Fetch kind-10002 (relay list) → fire and let OutboxRouter handle reactively
     * 4b. Fetch NIP-51 relay kinds (10006, 10007, 10012, 30002)
     * 5. Connect to global relays → opens persistent feed subscriptions
     */
    suspend fun bootstrap(pubkeyHex: String) {
        // EventProcessor drainers start in init{} with immutable kindHandlers —
        // no registration race. Just start OutboxRouter's internal flows.
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

        // ── Step 4b: Fetch NIP-51 relay ecosystem kinds ─────────────────────
        // One-shot: blocked relays, search relays, favorites, relay sets.
        // EventProcessor routes these through OutboxRouter handlers.
        relayPool.fetchRelayEcosystem(pubkeyHex, INDEXER_RELAY_URLS)
        Log.d(TAG, "Step 4b: NIP-51 relay kinds (10006/10007/10012/30002) requested")

        // ── Step 4c: Pre-load blocked relays before opening global connections ──
        relayPool.refreshBlockedRelays()
        Log.d(TAG, "Step 4c: blocked relay snapshot loaded")

        // ── Step 5: Connect to global relays (feed subscriptions) ───────────
        relayPool.connect(GLOBAL_RELAY_URLS, isHomeFeed = true)
        Log.d(TAG, "Step 5: global relays connecting")

        maintenanceJob.start()
        Log.d(TAG, "Bootstrap complete for $pubkeyHex")
    }

    /**
     * Full teardown on logout. Order matters:
     * 1. Cancel persistent subs (send CLOSE messages while connections are still alive)
     * 2. Disconnect all WebSockets
     * 3. Clear user-specific Room tables (follows, relay_configs) — keep events/users as cache
     * 4. Clear KeyManager, SigningManager, NwcManager credentials
     * 5. Cancel child scopes (OutboxRouter, EventProcessor)
     * 6. Reset in-memory state (seenIds, connection map)
     */
    suspend fun teardown() {
        // 1. Cancel persistent subscriptions
        relayPool.clearPersistentSubs()

        // 2. Disconnect all WebSockets
        relayPool.disconnectAll()

        // 3. Clear only user-specific tables — events/users/reactions are reusable cache
        followDao.clearAll()
        relayConfigDao.clearAll()

        // 4. Clear credentials and cached signer
        keyManager.clear()
        signingManager.clear()
        nwcManager.clear()

        // 5. Cancel child scopes (NOT this scope — it must survive for next login)
        outboxRouter.stop()
        eventProcessor.stop()
        maintenanceJob.stop()

        // 6. Release shared ExoPlayer
        sharedPlayerHolder.release()

        // 7. Clear card hydration cache (prevents stale data across accounts)
        cardHydrator.clearCache()

        // 8. In-memory state already cleared by eventProcessor.stop() (seenIds)
        // and relayPool.disconnectAll() (connections map)

        Log.d(TAG, "Teardown complete")
    }
}
