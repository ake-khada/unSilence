package com.unsilence.app.data

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.db.DatabaseMaintenanceJob
import com.unsilence.app.data.db.dao.EventDao
import com.unsilence.app.data.db.dao.EventStatsDao
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.dao.NostrRelaySetDao
import com.unsilence.app.data.db.dao.RelayConfigDao
import com.unsilence.app.data.db.dao.RelayTrustScoreDao
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.db.entity.RelayConfigEntity
import com.unsilence.app.data.wallet.NwcManager
import com.unsilence.app.data.media.MediaPreconnect
import com.unsilence.app.data.relay.ConnectionPurpose
import com.unsilence.app.data.relay.EventProcessor
import com.unsilence.app.data.relay.OutboxRouter
import com.unsilence.app.data.relay.ProfileResolver
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.ui.feed.SharedPlayerHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppBootstrapper"

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
    "wss://antiprimal.net",
    "wss://relay.ditto.pub",
)

/** Pubkey of the trustedrelays.xyz operator who publishes kind 30385 events (Geektoshi). */
private const val TRUST_SCORE_PROVIDER_PUBKEY =
    "daa41bedb68591363bf4407f687cb9789cc543ed024bb77c22d2c84d88f54153"

@Singleton
class AppBootstrapper @Inject constructor(
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val eventProcessor: EventProcessor,
    private val outboxRouter: OutboxRouter,
    private val maintenanceJob: DatabaseMaintenanceJob,
    private val eventDao: EventDao,
    private val signingManager: SigningManager,
    private val followDao: FollowDao,
    private val relayConfigDao: RelayConfigDao,
    private val userDao: UserDao,
    private val nwcManager: NwcManager,
    private val sharedPlayerHolder: SharedPlayerHolder,
    private val nostrRelaySetDao: NostrRelaySetDao,
    private val eventStatsDao: EventStatsDao,
    private val profileResolver: ProfileResolver,
    private val okHttpClient: OkHttpClient,
    private val relayTrustScoreDao: RelayTrustScoreDao,
) {
    private val bootstrapMutex = Mutex()

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
    suspend fun bootstrap(pubkeyHex: String) = bootstrapMutex.withLock {
        // ═══════════════════════════════════════════════════════════════════
        // Phase 1 (0ms): Feed connections — user sees content ASAP
        // ═══════════════════════════════════════════════════════════════════
        outboxRouter.start()
        nostrRelaySetDao.claimOrphaned(pubkeyHex)

        // Seed kind 99 indexer relays if none exist
        val existingIndexers = relayConfigDao.getIndexerRelayUrls()
        if (existingIndexers.isEmpty()) {
            relayConfigDao.insertAll(
                DEFAULT_INDEXER_URLS.map { url ->
                    RelayConfigEntity(kind = 99, relayUrl = url)
                }
            )
        }

        // Step 1: Connect to indexer relays
        val indexerUrls = relayConfigDao.getIndexerRelayUrls()
        val ready = relayPool.connectAndAwait(indexerUrls, timeoutMs = 5_000)
        Log.d(TAG, "Phase1 Step1: $ready indexer relay(s) connected")

        // Step 2: Fetch kind-3, wait for follows
        relayPool.fetchFollowList(pubkeyHex)
        val follows = withTimeoutOrNull(10_000L) {
            followDao.followsFlow().filter { it.isNotEmpty() }.first()
        }
        Log.d(TAG, "Phase1 Step2: ${follows?.size ?: 0} follows loaded")

        // Step 3: Fetch kind-10002 (relay list) — wait for response
        val relaysBefore = relayConfigDao.getAllReadWriteRelays()
        relayPool.fetchRelayLists(listOf(pubkeyHex))
        val freshRelays = withTimeoutOrNull(5_000L) {
            if (relaysBefore.isEmpty()) {
                relayConfigDao.getReadWriteRelays()
                    .filter { it.isNotEmpty() }
                    .first()
            } else {
                relayConfigDao.getReadWriteRelays()
                    .filter { it != relaysBefore }
                    .first()
            }
        }
        Log.d(TAG, "Phase1 Step3: kind-10002 ${if (freshRelays != null) "arrived (${freshRelays.size} relays)" else "timeout — using existing/fallback"}")

        // Step 4: Pre-load blocked relays before global connections
        relayPool.refreshBlockedRelays()

        // Step 5: Connect to global relays — feed subscriptions start HERE
        val readRelays = (freshRelays ?: relayConfigDao.getAllReadWriteRelays())
            .filter { it.marker == null || it.marker == "read" }
            .map { it.relayUrl }
            .take(8)
        val globalUrls = readRelays.ifEmpty { GLOBAL_RELAY_URLS }
        for (url in globalUrls) {
            normalizeRelayUrl(url)?.let { relayPool.addPurpose(it, ConnectionPurpose.PERSISTENT) }
        }
        relayPool.connect(globalUrls, isHomeFeed = true)
        Log.d(TAG, "Phase1 complete: feed subs active (${globalUrls.size} relays)")

        // ═══════════════════════════════════════════════════════════════════
        // Phase 2 (1000ms): Profile resolution + relay ecosystem
        // ═══════════════════════════════════════════════════════════════════
        delay(1000L)

        val followPubkeys = follows?.map { it.pubkey }.orEmpty() + pubkeyHex
        profileResolver.request(followPubkeys.distinct())
        Log.d(TAG, "Phase2: requested ${followPubkeys.size} profiles")

        relayPool.fetchRelayEcosystem(pubkeyHex, indexerUrls)
        Log.d(TAG, "Phase2: NIP-51 relay kinds (10006/10007/10012/30002) requested")

        // Seed kind 10007 search relays if none exist after fetch
        val existingSearch = relayConfigDao.searchRelayUrls()
        if (existingSearch.isEmpty()) {
            relayConfigDao.insertAll(
                DEFAULT_SEARCH_URLS.map { url ->
                    RelayConfigEntity(kind = 10007, relayUrl = url)
                }
            )
        }

        // ═══════════════════════════════════════════════════════════════════
        // Phase 3 (2500ms): Maintenance + media preconnect
        // ═══════════════════════════════════════════════════════════════════
        delay(1500L)

        maintenanceJob.start()

        val spamRemoved = eventDao.pruneJsonSpam()
        if (spamRemoved > 0) Log.d(TAG, "Phase3: cleaned $spamRemoved JSON-spam events")

        eventStatsDao.recalculateCounts()
        Log.d(TAG, "Phase3: recalculated engagement counts")

        // Fetch relay trust scores if stale (24 h cache)
        val lastTrustUpdate = relayTrustScoreDao.lastUpdatedAt() ?: 0L
        if (System.currentTimeMillis() - lastTrustUpdate > 24 * 60 * 60 * 1000L) {
            // Try kind 30385 via Nostr protocol first
            relayPool.fetchTrustScores(TRUST_SCORE_PROVIDER_PUBKEY)
            Log.d(TAG, "Phase3: fetching relay trust scores (kind 30385)")
            // Wait briefly for events to arrive, then fall back to HTTP API
            delay(5_000L)
            val gotScores = relayTrustScoreDao.lastUpdatedAt()?.let {
                it > lastTrustUpdate
            } == true
            if (!gotScores) {
                fetchTrustScoresHttp()
            }
        }

        MediaPreconnect.warmUp(okHttpClient)

        Log.d(TAG, "Bootstrap complete for $pubkeyHex")
    }

    /**
     * TEMPORARY fallback: fetch trust scores from the trustedrelays.xyz HTTP API.
     * TODO: Remove once the provider's kind 30385 pubkey is confirmed and events
     * are reliably available on relays. The kind 30385 path above is the correct
     * Nostr-native approach.
     */
    private suspend fun fetchTrustScoresHttp() = withContext(Dispatchers.IO) {
        try {
            val request = okhttp3.Request.Builder()
                .url("https://trustedrelays.xyz/api/relays")
                .build()
            val body = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext
                response.body?.string() ?: return@withContext
            }
            val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
            val arr = root["data"]?.jsonArray ?: return@withContext
            val entities = arr.mapNotNull { elem ->
                val obj = elem.jsonObject
                val url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val score = obj["score"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                com.unsilence.app.data.db.entity.RelayTrustScoreEntity(
                    relayUrl = url,
                    score = score,
                    reliability = obj["reliability"]?.jsonPrimitive?.intOrNull ?: 0,
                    quality = obj["quality"]?.jsonPrimitive?.intOrNull ?: 0,
                    accessibility = obj["accessibility"]?.jsonPrimitive?.intOrNull ?: 0,
                    confidence = obj["confidence"]?.jsonPrimitive?.content ?: "low",
                    observations = obj["observations"]?.jsonPrimitive?.intOrNull ?: 0,
                    policy = obj["policy"]?.jsonPrimitive?.content,
                    countryCode = obj["countryCode"]?.jsonPrimitive?.content,
                )
            }
            if (entities.isNotEmpty()) {
                relayTrustScoreDao.upsertAll(entities)
                Log.d(TAG, "Phase3: HTTP fallback loaded ${entities.size} trust scores")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Phase3: HTTP trust score fallback failed: ${e.message}")
        }
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
        nostrRelaySetDao.clearAllSets()
        nostrRelaySetDao.clearAllMembers()

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

        // 7. Clear profile resolver in-flight state
        profileResolver.clear()

        // 9. In-memory state already cleared by eventProcessor.stop() (seenIds)
        // and relayPool.disconnectAll() (connections map)

        Log.d(TAG, "Teardown complete")
    }
}
