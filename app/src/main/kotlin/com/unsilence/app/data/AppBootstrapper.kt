package com.unsilence.app.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.work.BackgroundSyncWorker
import java.util.concurrent.TimeUnit
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.wallet.NwcManager
import com.unsilence.app.data.media.MediaPreconnect
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.RelayConfig
import com.unsilence.app.data.memory.SnapshotScheduler
import com.unsilence.app.data.relay.ConnectionPurpose
import com.unsilence.app.data.relay.EventProcessor
import com.unsilence.app.data.relay.ProfileResolver
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.ANTIPRIMAL_RELAY_URL
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.ui.feed.SharedPlayerHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppBootstrapper"
private const val FRESHNESS_WINDOW_SEC = 6 * 3600L  // 6 hours

/** Default indexer relays — only used for first-launch seeding. */
private val DEFAULT_INDEXER_URLS = listOf(
    "wss://purplepag.es",
    "wss://indexer.coracle.social",
    "wss://user.kindpag.es",
    "wss://directory.yabu.me",
    "wss://profiles.nostr1.com",
)

/** Default search relays — seeded if none found after bootstrap fetch. */
private val DEFAULT_SEARCH_URLS = listOf(
    "wss://nostr.wine",
    "wss://relay.noswhere.com",
    "wss://search.nos.today",
    ANTIPRIMAL_RELAY_URL,
)

/** Pubkey of the trustedrelays.xyz operator who publishes kind 30385 events. */
private const val TRUST_SCORE_PROVIDER_PUBKEY =
    "ad3cdbe9fb09b8edf7b3e0e5286d66e58b58eaa64d061bbcf3a935edf8abf421"

@Singleton
class AppBootstrapper @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val eventProcessor: EventProcessor,
    private val signingManager: SigningManager,
    private val nwcManager: NwcManager,
    private val sharedPlayerHolder: SharedPlayerHolder,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val profileResolver: ProfileResolver,
    private val okHttpClient: OkHttpClient,
    private val snapshotScheduler: SnapshotScheduler,
    private val memoryEventStore: MemoryEventStore,
    private val initGate: InitGate,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bootstrapMutex = Mutex()
    private var bootstrapJob: Job? = null

    /**
     * Sequential bootstrap for the logged-in user.
     *
     * Each step completes (or times out) before the next starts:
     * 1. Connect to indexer relays → wait for at least one connection
     * 2. Fetch kind-3 (contact list) → wait for follows to appear in Room
     * 3. Fetch kind-0 (own profile) → wait for profile to appear in Room
     * 4. Fetch kind-10002 (relay list) → wait for response (5s timeout)
     * 4b. Fetch NIP-51 relay kinds (10006, 10007, 10012, 30002)
     * 5. Connect to global relays → opens persistent feed subscriptions
     *
     * Guarded by a Mutex so concurrent calls (e.g. config change + init)
     * don't interleave steps.
     */
    suspend fun bootstrap(pubkeyHex: String) {
        // Cancel any in-progress bootstrap (e.g. from previous login session).
        // Without this, the old bootstrap holds the mutex for minutes
        // (MediaPreconnect.warmUp can hang) and the new bootstrap starves.
        bootstrapJob?.cancel()
        bootstrapJob = scope.launch { doBootstrap(pubkeyHex) }
        bootstrapJob?.join()
    }

    private suspend fun doBootstrap(pubkeyHex: String) = bootstrapMutex.withLock {
        // ═══════════════════════════════════════════════════════════════════
        // Phase 1 (0ms): Feed connections — user sees content ASAP
        // ═══════════════════════════════════════════════════════════════════
        memoryEventStore.ownPubkey = pubkeyHex
        eventProcessor.start()

        // Seed kind 99 indexer relays if none exist (DataStore).
        // Suspending read waits for DataStore disk load — snapshot() would race.
        val existingIndexers = relayPreferencesStore.indexerRelayUrlsSuspending()
        if (existingIndexers.isEmpty()) {
            relayPreferencesStore.setIndexerUrls(DEFAULT_INDEXER_URLS)
        }

        // Register indexer relays as PERSISTENT so sendOneShotBatch always reuses
        // them instead of opening ephemeral WebSockets. Indexers carry no feed
        // subscriptions — idle cost is just WebSocket keep-alive pings.
        // Must happen BEFORE snapshot restore — EventProcessor fires batches
        // during the 21s snapshot parse.
        val indexerUrls = existingIndexers.ifEmpty { DEFAULT_INDEXER_URLS }
        for (rawUrl in indexerUrls) {
            normalizeRelayUrl(rawUrl)?.let { relayPool.addPurpose(it, ConnectionPurpose.PERSISTENT) }
        }

        // Step 1: Connect to indexer relays BEFORE snapshot restore.
        // connectAndAwait adds to connections map immediately, enabling
        // sendOneShotBatch reuse during the 21s snapshot parse window.
        // No MES dependency — pure WebSocket establishment.
        val ready = relayPool.connectAndAwait(indexerUrls, timeoutMs = 5_000)
        Log.d(TAG, "Phase1 Step1: $ready indexer relay(s) connected")

        // Phase 1.5: Launch snapshot restore in background — does NOT block bootstrap.
        // Follows-first snapshot format fires _followsSignal early so downstream
        // steps can await follows via signal flows without waiting for full parse.
        val snapshotAgeSec = snapshotScheduler.getSnapshotAgeSeconds()
        val snapshotFresh = snapshotAgeSec < FRESHNESS_WINDOW_SEC
        val snapshotJob = scope.launch {
            snapshotScheduler.restoreIfPresent()
            Log.d(TAG, "Phase1.5: snapshot restore complete (background)")
        }

        // Step 2: Wait for follows in MES, with snapshot-fresh fast path.
        val followsCached = memoryEventStore.getFollows(pubkeyHex)?.isNotEmpty() == true

        var follows: Set<String>?
        if (followsCached && snapshotFresh) {
            follows = memoryEventStore.getFollows(pubkeyHex)
            Log.d(TAG, "Phase1 Step2: follows snapshot-fresh (snapshot ${snapshotAgeSec}s old, ${follows?.size} follows) — skipping refetch")
        } else if (snapshotFresh) {
            // Snapshot is fresh but follows not yet in MES — wait briefly for
            // background restore. If the wait yields nothing (corrupted snapshot
            // missing follows section, or restore is slower than expected), fall
            // through to relay fetch. NEVER accept a null follows result when
            // we have a relay connection available.
            follows = withTimeoutOrNull(3_000L) {
                memoryEventStore.followsFlow(pubkeyHex).filter { it.isNotEmpty() }.first()
            }
            if (follows.isNullOrEmpty()) {
                Log.w(TAG, "Phase1 Step2: snapshot follows missing — fetching from relay")
                relayPool.fetchFollowList(pubkeyHex)
                follows = withTimeoutOrNull(10_000L) {
                    memoryEventStore.followsFlow(pubkeyHex).filter { it.isNotEmpty() }.first()
                }
            }
            Log.d(TAG, "Phase1 Step2: follows resolved (count=${follows?.size})")
        } else {
            relayPool.fetchFollowList(pubkeyHex)
            follows = withTimeoutOrNull(10_000L) {
                memoryEventStore.followsFlow(pubkeyHex).filter { it.isNotEmpty() }.first()
            }
            Log.d(TAG, "Phase1 Step2: ${follows?.size ?: 0} follows loaded from relay (snapshot ${snapshotAgeSec}s old)")
        }
        initGate.signalFollowsReady()
        Log.d(TAG, "InitGate: follows signaled")

        // Step 3: Fetch kind-10002 (relay list) — wait for response via MES.
        val relaysBefore = memoryEventStore.getReadWriteRelayConfigs(pubkeyHex)

        var freshRelays: List<RelayConfig>?
        if (relaysBefore.isNotEmpty() && snapshotFresh) {
            freshRelays = relaysBefore
            Log.d(TAG, "Phase1 Step3: kind-10002 snapshot-fresh (snapshot ${snapshotAgeSec}s old, ${freshRelays.size} relays) — skipping refetch")
        } else if (snapshotFresh) {
            // Snapshot being parsed in background — relay configs arrive during
            // events section. Wait briefly for them.
            freshRelays = withTimeoutOrNull(2_000L) {
                memoryEventStore.readWriteRelayConfigsFlow(pubkeyHex)
                    .filter { it.isNotEmpty() }
                    .first()
            }
            if (freshRelays.isNullOrEmpty()) {
                Log.w(TAG, "Phase1 Step3: snapshot relay-configs missing — fetching from relay")
                relayPool.fetchRelayLists(listOf(pubkeyHex))
                freshRelays = withTimeoutOrNull(5_000L) {
                    memoryEventStore.readWriteRelayConfigsFlow(pubkeyHex)
                        .filter { it.isNotEmpty() }
                        .first()
                }
            }
            Log.d(TAG, "Phase1 Step3: kind-10002 from background snapshot (${freshRelays?.size ?: "timeout"})")
        } else {
            relayPool.fetchRelayLists(listOf(pubkeyHex))
            freshRelays = withTimeoutOrNull(5_000L) {
                if (relaysBefore.isEmpty()) {
                    memoryEventStore.readWriteRelayConfigsFlow(pubkeyHex)
                        .filter { it.isNotEmpty() }
                        .first()
                } else {
                    memoryEventStore.readWriteRelayConfigsFlow(pubkeyHex)
                        .filter { it != relaysBefore }
                        .first()
                }
            }
            Log.d(TAG, "Phase1 Step3: kind-10002 ${if (freshRelays != null) "arrived (${freshRelays.size} relays)" else "timeout — using existing/fallback"} (snapshot ${snapshotAgeSec}s old)")
        }
        initGate.signalRelaysReady()
        Log.d(TAG, "InitGate: relays signaled")

        // Step 4: Pre-load blocked relays before global connections
        relayPool.refreshBlockedRelays()

        // Step 5: Connect to global relays — feed subscriptions start HERE
        val readRelays = (freshRelays ?: memoryEventStore.getReadWriteRelayConfigs(pubkeyHex))
            .filter { it.marker == null || it.marker == "read" }
            .map { it.url }
            .take(8)
        val globalUrls = readRelays.ifEmpty { GLOBAL_RELAY_URLS }
        for (url in globalUrls) {
            normalizeRelayUrl(url)?.let { relayPool.addPurpose(it, ConnectionPurpose.PERSISTENT) }
        }
        relayPool.connectAndAwait(globalUrls, timeoutMs = 5_000)
        initGate.signalFeedConnectionsReady()
        Log.d(TAG, "Phase1 complete: relay connections active (${globalUrls.size} relays)")

        // ═══════════════════════════════════════════════════════════════════
        // Phase 2 (1000ms): Profile resolution + relay ecosystem
        // ═══════════════════════════════════════════════════════════════════
        delay(1000L)

        val followPubkeys = (follows?.toList().orEmpty()) + pubkeyHex
        val staleFollows = profileResolver.filterUnresolved(followPubkeys.distinct().toSet())
        if (staleFollows.isNotEmpty()) {
            profileResolver.request(staleFollows.toList())
        }
        Log.d(TAG, "Phase2: ${staleFollows.size}/${followPubkeys.size} profiles need fetch")

        relayPool.fetchRelayEcosystem(pubkeyHex, indexerUrls)
        Log.d(TAG, "Phase2: NIP-51 relay kinds (10006/10007/10012/30002) requested")

        // Fetch kind-10002 (relay lists) for ALL follows. Outbox routing in
        // OutboxRelayResolver requires writeRelaysFor(author) to return real data
        // for each followed author. Without this, every author falls back to
        // user's read relays, and queries on relays that don't have those
        // authors' content time out at 30s with zero events.
        //
        // Indexer relays (purplepag.es, user.kindpag.es) aggregate kind-10002
        // globally — one REQ with 251 authors returns all relay lists.
        val followsToFetchRelayLists = follows?.toList().orEmpty()
        if (followsToFetchRelayLists.isNotEmpty()) {
            val staleAuthors = followsToFetchRelayLists.filter { author ->
                memoryEventStore.getReadWriteRelayConfigs(author).isEmpty()
            }
            if (staleAuthors.isNotEmpty()) {
                Log.d(TAG, "Phase2: fetching kind-10002 for ${staleAuthors.size}/${followsToFetchRelayLists.size} follows")
                relayPool.fetchRelayLists(staleAuthors)
            } else {
                Log.d(TAG, "Phase2: kind-10002 cached for all ${followsToFetchRelayLists.size} follows")
            }
        }

        // Seed kind 10007 search relays in MES if none exist after fetch
        val existingSearch = memoryEventStore.getSearchRelayUrls(pubkeyHex)
        if (existingSearch.isEmpty()) {
            for (url in DEFAULT_SEARCH_URLS) {
                memoryEventStore.addSearchRelay(pubkeyHex, url)
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // Phase 3 (2500ms): Maintenance + media preconnect
        // ═══════════════════════════════════════════════════════════════════
        delay(1500L)

        // Collect all relay URLs the user has configured (read/write, search, favorites, indexers)
        // to fetch health data only for those specific relays.
        val userRelayUrls = buildSet {
            addAll(memoryEventStore.getReadWriteRelayConfigs(pubkeyHex).map { it.url })
            addAll(memoryEventStore.getSearchRelayUrls(pubkeyHex))
            addAll(memoryEventStore.getFavoriteRelayConfigs(pubkeyHex).mapNotNull { it.url })
            addAll(relayPreferencesStore.indexerRelayUrlsSnapshot())
            addAll(GLOBAL_RELAY_URLS)
        }.toList()
        Log.d(TAG, "Phase3: fetching relay health for ${userRelayUrls.size} configured relays")

        // Fetch trust scores + relay monitors concurrently, targeted to user's relays only.
        // Snapshot-persisted so data is available immediately on next restart.
        val trustJob = scope.launch { relayPool.fetchTrustScores(TRUST_SCORE_PROVIDER_PUBKEY, userRelayUrls) }
        val monitorStalenessMs = 12L * 60 * 60 * 1000 // 12 hours
        val monitorAge = System.currentTimeMillis() - relayPreferencesStore.lastMonitorFetchAt()
        val hasMonitors = memoryEventStore.relayMonitorCount() > 0
        val monitorJob = if (hasMonitors && monitorAge < monitorStalenessMs) {
            Log.d(TAG, "Phase3: relay monitors fresh (age=${monitorAge / 60_000}min, count=${memoryEventStore.relayMonitorCount()}), skipping fetch")
            null
        } else {
            Log.d(TAG, "Phase3: fetching relay monitors (age=${monitorAge / 60_000}min, hasMonitors=$hasMonitors)")
            scope.launch {
                relayPool.fetchRelayMonitors()
                relayPreferencesStore.setLastMonitorFetchAt(System.currentTimeMillis())
            }
        }
        trustJob.join()
        monitorJob?.join()

        // Media preconnect is fire-and-forget — never block the bootstrap mutex.
        // supervisorScope inside warmUp waits for all HEAD requests; if one hangs
        // the entire bootstrap stalls (measured: 2m27s in production).
        scope.launch { MediaPreconnect.warmUp(okHttpClient) }

        scheduleBackgroundSync()

        Log.d(TAG, "Bootstrap complete for $pubkeyHex")
    }

    private fun scheduleBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                BackgroundSyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        Log.d(TAG, "Background sync worker scheduled (30min interval, stub implementation)")
    }

    /**
     * Full teardown on logout. Order matters:
     * 1. Cancel persistent subs (send CLOSE messages while connections are still alive)
     * 2. Disconnect all WebSockets
     * 3. Clear user-specific state — events/profiles/stats are reusable cache
     * 4. Clear KeyManager, SigningManager, NwcManager credentials
     * 5. Cancel child scopes (EventProcessor)
     * 6. Reset in-memory state (seenIds, connection map)
     *
     * No exitProcess — singletons survive. bootstrap() restarts subsystems.
     */
    suspend fun teardown() {
        // 0. Cancel in-progress bootstrap — releases the mutex immediately
        bootstrapJob?.cancel()
        bootstrapJob = null

        // 1. Clear relay pool caches
        relayPool.clearCaches()

        // 2. Disconnect all WebSockets
        relayPool.disconnectAll()

        // 3. Clear ALL in-memory state — eventsById, profiles, stats, follows, relays.
        //    clearUserState() preserved eventsById which leaked old user's cached events
        //    into the new user's Global feed after re-login.
        memoryEventStore.clear()

        // 3b. Delete snapshot file — prevents restoreIfPresent() from reloading
        //     old user's events into MES on next bootstrap.
        snapshotScheduler.deleteSnapshot()

        // 4. Clear credentials and cached signer
        keyManager.clear()
        signingManager.clear()
        nwcManager.clear()

        // 5. Cancel child scopes (NOT this scope — it must survive for next login)
        eventProcessor.stop()

        // 6. Release shared ExoPlayer (must be on Main — ExoPlayer thread affinity)
        withContext(Dispatchers.Main) { sharedPlayerHolder.release() }

        // 7. Clear profile resolver in-flight state
        profileResolver.clear()

        // In-memory state already cleared by eventProcessor.stop() (seenIds)
        // and relayPool.disconnectAll() (connections map)

        Log.d(TAG, "Teardown complete")
    }
}
