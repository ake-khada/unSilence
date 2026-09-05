package com.unsilence.app.data

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.unsilence.app.data.blossom.BlossomServersStore
import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.init.InitSession
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.work.BackgroundSyncWorker
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.wallet.NwcManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.SnapshotScheduler
import com.unsilence.app.data.memory.WotProviderDescriptor
import com.unsilence.app.data.relay.AccountMetadataFetchResult
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.ConnectionPurpose
import com.unsilence.app.data.relay.EventProcessor
import com.unsilence.app.data.relay.MuteListFetchResult
import com.unsilence.app.data.relay.ProfileResolver
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayMessageTap
import com.unsilence.app.data.relay.RelayTapMessage
import com.unsilence.app.data.relay.WotProviderSource
import com.unsilence.app.data.relay.TrendingClient
import com.unsilence.app.data.relay.canMaterializeEmptyContactList
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.ANTIPRIMAL_RELAY_URL
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.shouldSkipBootstrapWotFetch
import com.unsilence.app.data.relay.wotProviderDescriptorFromPrefs
import com.unsilence.app.data.relay.wotTargetsHash
import com.unsilence.app.data.repository.MuteListRepository
import com.unsilence.app.data.repository.MuteSyncState
import com.unsilence.app.data.settings.SettingsStore
import com.unsilence.app.data.wallet.ZapPreferencesStore
import com.unsilence.app.ui.feed.SharedPlayerHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppBootstrapper"
private const val FRESHNESS_WINDOW_SEC = 6 * 3600L  // 6 hours
private val ACCOUNT_METADATA_RECOVERY_DELAYS_MS = listOf(10_000L, 30_000L)
private val MUTE_RECOVERY_DELAYS_MS = listOf(10_000L, 20_000L, 40_000L)

/**
 * Coalesces verification of the same replaceable mute-list revision.
 *
 * A kind-10000 commonly arrives from several relays. Without this gate every
 * copy can trigger the same NIP-44 decrypt, which is wasted work for nsec users
 * and can produce repeated authorization prompts for Amber users.
 */
internal class MuteEventVerificationGate {
    private val lock = Any()
    private val inFlight = mutableSetOf<String>()
    private var verifiedEventId: String? = null

    fun tryBegin(eventId: String): Boolean = synchronized(lock) {
        if (eventId == verifiedEventId) return@synchronized false
        inFlight.add(eventId)
    }

    fun finish(eventId: String, verified: Boolean) = synchronized(lock) {
        inFlight.remove(eventId)
        if (verified) verifiedEventId = eventId
    }

    fun markVerified(eventId: String?) = synchronized(lock) {
        verifiedEventId = eventId
        if (eventId != null) inFlight.remove(eventId)
    }

    fun reset() = synchronized(lock) {
        verifiedEventId = null
        inFlight.clear()
    }
}

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
    private val relayCapabilitiesStore: com.unsilence.app.data.relay.RelayCapabilitiesStore,
    private val profileResolver: ProfileResolver,
    private val snapshotScheduler: SnapshotScheduler,
    private val memoryEventStore: MemoryEventStore,
    private val initGate: InitGate,
    private val muteListRepository: MuteListRepository,
    private val cardHydrator: CardHydrator,
    private val profilePipeline: com.unsilence.app.data.relay.ProfilePipeline,
    private val privateZapRepository: com.unsilence.app.data.repository.PrivateZapRepository,
    private val ownZapReceiptAuthorityCoordinator: com.unsilence.app.data.zap.OwnZapReceiptAuthorityCoordinator,
    private val outboxRelayResolver: com.unsilence.app.data.relay.OutboxRelayResolver,
    private val feedRelayWarmer: com.unsilence.app.data.relay.FeedRelayWarmer,
    private val trendingClient: TrendingClient,
    private val zapPreferencesStore: ZapPreferencesStore,
    private val blossomServersStore: BlossomServersStore,
    private val settingsStore: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Guards both doBootstrap and teardown so they never interleave on the shared
    // singletons (RelayPool, EventProcessor, MES, KeyManager).
    private val bootstrapMutex = Mutex()
    private var bootstrapJob: Job? = null
    @Volatile private var accountMetadataRecoveryJob: Job? = null
    @Volatile private var muteRecoveryJob: Job? = null
    private val muteEventVerificationGate = MuteEventVerificationGate()
    // Monotonic session generation. Bumped by both bootstrap() (login) and teardown()
    // (logout). Each holds its captured gen and, inside the mutex, bails if a newer
    // session has superseded it — so a fast logout↔relogin can't corrupt the winner.
    private val sessionGen = java.util.concurrent.atomic.AtomicInteger(0)
    /** Non-null only after the initial kind-10000 fetch/settle pass for this session. */
    @Volatile private var muteBootstrapSettledForPubkey: String? = null

    /**
     * MES correctly drops a relay copy whose ID was restored from the snapshot.
     * This verified tap still observes that copy, allowing reconnect replay to
     * prove freshness and reopen the publish gate without another request loop.
     */
    private val ownMuteRelayTap = RelayMessageTap { message ->
        val event = (message as? RelayTapMessage.VerifiedEvent)?.event
            ?: return@RelayMessageTap
        val settledOwner = muteBootstrapSettledForPubkey ?: return@RelayMessageTap
        if (event.kind == 10000 && event.pubkey == settledOwner) {
            scope.launch { handleOwnMuteListEvent(event) }
        }
    }

    /**
     * Emitted when bootstrap detects Amber NIP-44 permissions are missing
     * (DecryptFailed or EncryptRoundTripFailed). MainActivity collects
     * this and launches Amber's consent screen automatically.
     */
    private val _amberReauthorizeRequiredFlow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val amberReauthorizeRequiredFlow: SharedFlow<Unit> =
        _amberReauthorizeRequiredFlow.asSharedFlow()

    /** Public trigger for FiltersScreen "Retry" button. */
    suspend fun requestAmberReauthorize() {
        _amberReauthorizeRequiredFlow.emit(Unit)
    }

    /**
     * Bootstrap for the logged-in user.
     *
     * Latency-critical account discovery is bounded and consolidated:
     * 1. Connect to indexer relays → wait for at least one connection
     * 2. Fetch kind 0/3/10002 in one request while warming cold-start fallback relays
     * 3. Select own read relays, or Trusted Global defaults when none exist
     * 4. Continue NIP-51, mute, profile and maintenance hydration off the entry path
     *
     * Guarded by a Mutex so concurrent calls (e.g. config change + init)
     * don't interleave steps.
     */
    suspend fun bootstrap(pubkeyHex: String, initSession: InitSession) {
        // Claim a new session generation; supersedes any in-flight teardown/bootstrap.
        val myGen = sessionGen.incrementAndGet()
        // Cancel any in-progress bootstrap (e.g. from previous login session).
        // Without this, the old bootstrap holds the mutex for minutes
        // (MediaPreconnect.warmUp can hang) and the new bootstrap starves.
        bootstrapJob?.cancel()
        bootstrapJob = scope.launch { doBootstrap(pubkeyHex, myGen, initSession) }
        bootstrapJob?.join()
    }

    private suspend fun doBootstrap(
        pubkeyHex: String,
        myGen: Int,
        initSession: InitSession,
    ) = bootstrapMutex.withLock {
        // If a newer session (another login, or a logout) supervened while we waited
        // for the mutex, abandon this run — the newer one is authoritative.
        if (sessionGen.get() != myGen || !initGate.isCurrent(initSession, pubkeyHex)) {
            Log.w(TAG, "SESSION-FENCE: bootstrap aborted — superseded (gen=${sessionGen.get()} myGen=$myGen)")
            return@withLock
        }
        // ═══════════════════════════════════════════════════════════════════
        // Phase 1 (0ms): Feed connections — user sees content ASAP
        // ═══════════════════════════════════════════════════════════════════
        claimAccountOwner(pubkeyHex)
        ownZapReceiptAuthorityCoordinator.start(pubkeyHex)
        accountMetadataRecoveryJob?.cancel()
        accountMetadataRecoveryJob = null
        muteRecoveryJob?.cancel()
        muteRecoveryJob = null
        muteBootstrapSettledForPubkey = null
        muteEventVerificationGate.reset()
        muteListRepository.markPublishUnsafe("bootstrap in progress")
        muteListRepository.markSnapshotPending()
        // The encrypted journal is tiny and synchronous. Restore it before the
        // large background snapshot so an immediate force-stop cannot erase a
        // mute made during a prior offline session.
        muteListRepository.restoreDurablePending(pubkeyHex)
        // Provider declarations are identity claims. Select/reset the session owner's
        // declaration before EventProcessor, snapshot restore, or a fetch can observe it.
        val sessionWotPrefs = relayPreferencesStore.ensureWotPrefsOwner(pubkeyHex)
        memoryEventStore.setActiveWotProvider(sessionWotPrefs.pubkey, sessionWotPrefs.relay)
        eventProcessor.start()
        eventProcessor.registerTap(ownMuteRelayTap)

        // Once per login: clear ProfilePipeline session state so the own-post
        // below-head gap heal runs exactly once per session, not once per process
        // (ProfilePipeline is @Singleton and survives logout/login).
        profilePipeline.resetForSession()

        // Seed kind 99 indexer relays if none exist (DataStore).
        // Suspending read waits for DataStore disk load — snapshot() would race.
        val existingIndexers = relayPreferencesStore.indexerRelayUrlsSuspending()
        if (existingIndexers.isEmpty()) {
            relayPreferencesStore.setIndexerUrls(DEFAULT_INDEXER_URLS)
        }

        // Load per-relay learned capabilities before any REQs go out.
        relayCapabilitiesStore.load()

        // Register indexer relays as PERSISTENT so sendOneShotBatch always reuses
        // them instead of opening ephemeral WebSockets. Indexers carry no feed
        // subscriptions — idle cost is just WebSocket keep-alive pings.
        // Must happen BEFORE snapshot restore — EventProcessor fires batches
        // during the 21s snapshot parse.
        val indexerUrls = existingIndexers.ifEmpty { DEFAULT_INDEXER_URLS }
        // Seed current-session NIP-42 eligibility before the first connection.
        // Phase 3 replaces this with the full read/write/search/indexer union.
        relayPool.setIntegralRelays(indexerUrls)
        for (rawUrl in indexerUrls) {
            normalizeRelayUrl(rawUrl)?.let { relayPool.addPurpose(it, ConnectionPurpose.PERSISTENT) }
        }

        // Step 1: Connect to indexer relays BEFORE snapshot restore.
        // connectAndAwait adds to connections map immediately, enabling
        // sendOneShotBatch reuse during the 21s snapshot parse window.
        // No MES dependency — pure WebSocket establishment.
        val ready = relayPool.connectAndAwait(indexerUrls, timeoutMs = 5_000)
        Log.d(TAG, "Phase1 Step1: $ready indexer relay(s) connected")

        // Wire MES mute-list callbacks BEFORE snapshot restore. Every accepted
        // own event closes the publish gate until that exact event is verified.
        memoryEventStore.isSelfPublishedCheck = { eventId ->
            muteListRepository.isSelfPublished(eventId)
        }
        memoryEventStore.ownMuteListEventCallback = { event ->
            muteListRepository.markPublishUnsafe("own kind-10000 awaiting verification")
            scope.launch { handleOwnMuteListEvent(event) }
        }
        // Phase 1.5: Launch snapshot restore in background — does NOT block bootstrap.
        // Follows-first snapshot format fires _followsSignal early so downstream
        // steps can await follows via signal flows without waiting for full parse.
        val snapshotAgeSec = snapshotScheduler.getSnapshotAgeSeconds()
        val snapshotFresh = snapshotAgeSec < FRESHNESS_WINDOW_SEC
        scope.launch {
            snapshotScheduler.restoreIfPresent()
            if (sessionGen.get() == myGen && keyManager.getPublicKeyHex() == pubkeyHex) {
                muteListRepository.markSnapshotReady()
                Log.d(TAG, "Phase1.5: snapshot restore complete (background)")
            } else {
                Log.w(TAG, "SESSION-FENCE: stale snapshot completion ignored (gen=$myGen)")
            }
        }

        // A truly cold identity needs useful content even when it has never published
        // account metadata. Warm the safe fallback while the single combined metadata
        // lookup runs; established accounts with a fresh snapshot keep their own relays.
        val coldFallbackUrls = if (snapshotFresh) emptyList() else GLOBAL_RELAY_URLS
        coldFallbackUrls.forEach { rawUrl ->
            normalizeRelayUrl(rawUrl)?.let { relayPool.addPurpose(it, ConnectionPurpose.PERSISTENT) }
        }
        relayPool.connect(coldFallbackUrls)

        // Steps 2–3: kind 0, 3 and 10002 are one logical account-discovery request.
        // Serial waits made a verified empty identity pay every timeout in succession.
        val followsBefore = memoryEventStore.getFollows(pubkeyHex)
        val relaysBefore = memoryEventStore.getReadWriteRelayConfigs(pubkeyHex)
        val hasFreshGraphAndRelays = snapshotFresh &&
            followsBefore?.isNotEmpty() == true && relaysBefore.isNotEmpty()
        val metadataFetch = if (hasFreshGraphAndRelays) {
            null
        } else {
            relayPool.fetchAccountMetadata(pubkeyHex, indexerUrls)
        }
        metadataFetch?.let { materializeConfirmedEmptyAccountLists(pubkeyHex, it) }
        val follows = memoryEventStore.getFollows(pubkeyHex)
        val freshRelays = memoryEventStore.getReadWriteRelayConfigs(pubkeyHex)
        Log.d(
            TAG,
            "Phase1 account discovery: follows=${follows?.size ?: "unresolved"} " +
                "relays=${freshRelays.size} graphResponded=${metadataFetch?.hasGraphResponse ?: true} " +
                "realEose=${metadataFetch?.eoseRelays?.size ?: 0}",
        )
        if (follows == null && metadataFetch != null && !metadataFetch.hasGraphResponse) {
            startAccountMetadataRecovery(pubkeyHex, indexerUrls, myGen, initSession)
        }
        initGate.signalFollowsReady(initSession)
        Log.d(TAG, "InitGate: follows signaled")
        initGate.signalRelaysReady(initSession)
        Log.d(TAG, "InitGate: relays signaled")

        // Step 4: Pre-load blocked relays before global connections
        relayPool.refreshBlockedRelays()

        // Step 5: Connect to global relays — feed subscriptions start HERE
        val readRelays = freshRelays
            .filter { it.marker == null || it.marker == "read" }
            .map { it.url }
            .take(8)
        val globalUrls = readRelays.ifEmpty { GLOBAL_RELAY_URLS }
        coldFallbackUrls
            .filterNot { candidate -> globalUrls.any { normalizeRelayUrl(it) == normalizeRelayUrl(candidate) } }
            .forEach { candidate ->
                normalizeRelayUrl(candidate)?.let {
                    relayPool.removePurpose(it, ConnectionPurpose.PERSISTENT)
                }
            }
        for (url in globalUrls) {
            normalizeRelayUrl(url)?.let { relayPool.addPurpose(it, ConnectionPurpose.PERSISTENT) }
        }
        relayPool.connectAndAwait(globalUrls, timeoutMs = 5_000)
        initGate.signalFeedConnectionsReady(initSession)

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

        val muteFetch = relayPool.fetchMuteList(pubkeyHex, indexerUrls)
        Log.d(
            TAG,
            "Phase2: NIP-51 mute list evidence=" +
                "${muteFetch.hasFreshnessEvidence} event=${muteFetch.receivedEvent != null} " +
                "eose=${muteFetch.eoseRelays.size}/${muteFetch.expectedRelays.size}",
        )

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
            // Build the coverage-ranked outbox allowlist. Ephemeral connections
            // to relays outside this set are skipped to shrink the DNS failure
            // surface. Must run AFTER kind-10002 is fetched/cached so
            // writeRelaysFor() returns real data for each author.
            // Blocked relays are enforced at shouldSkip/connectAndAwait level —
            // pass empty here, the allowlist is coverage-only.
            val allowlist = outboxRelayResolver.selectOutboxRelays(
                follows = follows ?: emptySet(),
                blockedRelays = relayPool.getBlockedUrls(),
            )
            relayPool.setOutboxAllowlist(allowlist)
        }

        // Settle the mute list: wait for the relay fetch to complete (or timeout)
        // before allowing any publish. The safety gate prevents premature publish
        // from replacing the user's complete relay-side mute list with a stub.
        val settleOutcome = settleMuteList(pubkeyHex, muteFetch)
        muteBootstrapSettledForPubkey = pubkeyHex
        val muteReady = applyMuteSettleOutcome(
            pubkeyHex = pubkeyHex,
            outcome = settleOutcome,
            reasonPrefix = "bootstrap settled",
        )
        if (!muteReady && settleOutcome.result in setOf(
                MuteSettleResult.NoEventFound,
                MuteSettleResult.Timeout,
            )
        ) {
            startMuteListReconnectRecovery(pubkeyHex, indexerUrls, myGen)
        }

        // Ensure own write relays are connected so subscribeOwnMuteList can attach
        // to all of them. Without this, write relays not in Phase 1's set are
        // silently skipped and Amethyst-side mute updates published only to those
        // relays never reach us.
        val ownWriteUrls = memoryEventStore.writeRelaysFor(pubkeyHex)
            .mapNotNull { normalizeRelayUrl(it) }
        if (ownWriteUrls.isNotEmpty()) {
            for (url in ownWriteUrls) {
                relayPool.addPurpose(url, ConnectionPurpose.PERSISTENT)
            }
            relayPool.connectAndAwait(ownWriteUrls, timeoutMs = 5_000)
        }

        // Open persistent subscription for own kind-10000 on write relays.
        // Cross-client mute changes (Amethyst, etc.) arrive in real time.
        relayPool.subscribeOwnMuteList(pubkeyHex)

        // Seed kind 10007 search relays in MES if none exist after fetch
        val existingSearch = memoryEventStore.getSearchRelayUrls(pubkeyHex)
        if (existingSearch.isEmpty()) {
            for (url in DEFAULT_SEARCH_URLS) {
                memoryEventStore.addSearchRelay(pubkeyHex, url)
            }
        }

        // NIP-30 custom emoji: fetch user's emoji list, then resolve subscribed sets.
        // Non-blocking — launched in background so it doesn't delay Phase 3.
        scope.launch {
            relayPool.fetchUserEmojiList(pubkeyHex, indexerUrls)
            // Wait for kind-10030 to land in MES (up to 5s)
            var waited = 0L
            while (waited < 5_000L && memoryEventStore.getUserEmojiList(pubkeyHex) == null) {
                delay(250L)
                waited += 250L
            }
            val emojiList = memoryEventStore.getUserEmojiList(pubkeyHex)
            if (emojiList != null && emojiList.setRefs.isNotEmpty()) {
                relayPool.fetchEmojiSets(emojiList.setRefs)
                Log.d(TAG, "Phase2: NIP-30 emoji sets requested (${emojiList.setRefs.size} refs)")

                // Retry unresolved set refs after 30s. Hint relays may be down
                // (e.g. frens.nostr1.com observed returning HTTP 503 during
                // Phase 3a validation). Fall back to indexers + author write
                // relays only.
                delay(30_000L)
                val unresolved = emojiList.setRefs.filter { ref ->
                    memoryEventStore.getEmojiSet(ref.authorPubkey, ref.setName) == null
                }
                if (unresolved.isNotEmpty()) {
                    relayPool.fetchEmojiSets(unresolved, skipHintRelays = true)
                    Log.d(TAG, "Phase2: NIP-30 retry for ${unresolved.size} " +
                            "unresolved set ref(s) (skipping hint relays)")
                }
            } else {
                Log.d(TAG, "Phase2: NIP-30 no emoji list or empty set refs")
            }
        }

        // Own-profile pipeline: rebuild ref anchors from snapshot, then eager
        // fetch notes + refs + engagement. Non-blocking — runs concurrently
        // with Phase 3. Anchor rebuild is synchronous (pure MES scan, ~20ms).
        scope.launch {
            profilePipeline.rebuildOwnProfileAnchors(pubkeyHex)
            profilePipeline.loadProfile(
                pubkey = pubkeyHex,
                isOwn = true,
                anchorPolicy = com.unsilence.app.data.relay.AnchorPolicy.OWN,
            )
        }

        // Historical notification backfill: paginated #p fetch across all connected
        // relays. Delayed 15s so outbox relay connections stabilize first — notifications
        // come from OTHER people's write relays, not just ours.
        // Non-blocking — launched in background so it doesn't delay Phase 3.
        scope.launch {
            delay(15_000)
            relayPool.fetchHistoricalNotifications(pubkeyHex)
        }

        // Persistent notification tail: forward-looking only (since:now).
        // New reactions/reposts/zaps/replies arrive via EventProcessor → MES
        // and surface in NotificationsViewModel's MES flow.
        relayPool.subscribeOwnNotifications(pubkeyHex, System.currentTimeMillis() / 1000)

        // NIP-57 private zap decryption. Pending work is derived from MES's
        // authenticated receipt set, so a late collector receives snapshot work too.
        privateZapRepository.start()

        // ═══════════════════════════════════════════════════════════════════
        // Phase 3 (2500ms): Maintenance
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
        }.mapNotNull(::normalizeRelayUrl).toSet()
        Log.d(TAG, "Phase3: fetching relay health for ${userRelayUrls.size} configured relays")

        // Wire the integral relay set for half-open circuit breaker recovery.
        // Integral = indexer + own read/write + search — essentials that heal on
        // a short cooldown after transient DNS/network blips.
        val integralUrls = buildSet {
            addAll(relayPreferencesStore.indexerRelayUrlsSnapshot())
            addAll(memoryEventStore.getSearchRelayUrls(pubkeyHex))
            addAll(memoryEventStore.readRelaysFor(pubkeyHex))
            addAll(memoryEventStore.writeRelaysFor(pubkeyHex))
        }
        relayCapabilitiesStore.setIntegralRelays(integralUrls)
        relayPool.setIntegralRelays(integralUrls)

        // Fetch trust scores + relay monitors concurrently, targeted to user's relays only.
        // Snapshot-persisted so data is available immediately on next restart.
        val maintenanceStalenessMs = 12L * 60 * 60 * 1000 // 12 hours
        val lastTrustUrls = relayPreferencesStore.lastTrustRelayUrls()
        val trustAge = System.currentTimeMillis() - relayPreferencesStore.lastTrustFetchAt()
        val hasTrustScores = memoryEventStore.getTrustScores().isNotEmpty()
        val trustJob = if (hasTrustScores && trustAge < maintenanceStalenessMs && lastTrustUrls == userRelayUrls) {
            Log.d(TAG, "Phase3: trust scores fresh for unchanged relay set; skipping fetch")
            null
        } else {
            scope.launch {
                val ok = relayPool.fetchTrustScores(TRUST_SCORE_PROVIDER_PUBKEY, userRelayUrls.toList())
                if (ok) {
                    relayPreferencesStore.setLastTrustFetch(System.currentTimeMillis(), userRelayUrls)
                } else {
                    Log.w(TAG, "Phase3: trust fetch failed — not advancing 12h gate")
                }
            }
        }

        val wotPrefs = relayPreferencesStore.wotProviderPrefsSuspending()
        val wotTargets = ((memoryEventStore.getFollows(pubkeyHex) ?: emptySet()) + pubkeyHex)
        val wotJob = scope.launch {
            val resolvedProvider = if (wotPrefs.source == WotProviderSource.OWN_10040) {
                val fetched = relayPool.fetchOwn10040(pubkeyHex)
                val ownProvider = if (fetched) {
                    withTimeoutOrNull(2_000L) {
                        var provider = memoryEventStore.ownWotProviderFromRegistry()
                        while (provider == null) {
                            delay(100L)
                            provider = memoryEventStore.ownWotProviderFromRegistry()
                        }
                        provider
                    }
                } else {
                    null
                }
                ownProvider ?: wotProviderDescriptorFromPrefs(wotPrefs)
            } else {
                wotProviderDescriptorFromPrefs(wotPrefs)
            }

            memoryEventStore.setActiveWotProvider(resolvedProvider.providerPubkey, resolvedProvider.relayHint)
            val targetsHash = wotTargetsHash(resolvedProvider.providerPubkey, wotTargets)
            val wotAge = System.currentTimeMillis() - relayPreferencesStore.lastWotFetchAt()
            val lastWotTargetsHash = relayPreferencesStore.lastWotTargetsHash()
            if (shouldSkipBootstrapWotFetch(
                    hasWotData = memoryEventStore.hasWotData(),
                    ageMs = wotAge,
                    lastTargetsHash = lastWotTargetsHash,
                    currentTargetsHash = targetsHash,
                    stalenessMs = maintenanceStalenessMs,
                )
            ) {
                Log.d(TAG, "Phase3: WoT assertions fresh for unchanged provider/targets; skipping fetch")
            } else {
                Log.d(TAG, "Phase3: fetching WoT assertions for ${wotTargets.size} target(s)")
                val ok = relayPool.fetchWotAssertions(
                    providerPubkey = resolvedProvider.providerPubkey,
                    relayHint = resolvedProvider.relayHint,
                    subjects = wotTargets,
                    prioritySubjects = listOf(pubkeyHex),
                )
                if (ok) {
                    logWotCoverageCanary(pubkeyHex, resolvedProvider)
                    relayPreferencesStore.setLastWotFetch(System.currentTimeMillis(), targetsHash)
                } else {
                    Log.w(TAG, "Phase3: WoT fetch failed — not advancing 12h gate")
                }
            }
        }

        val monitorAge = System.currentTimeMillis() - relayPreferencesStore.lastMonitorFetchAt()
        val hasMonitors = memoryEventStore.relayMonitorCount() > 0
        val monitorJob = if (hasMonitors && monitorAge < maintenanceStalenessMs) {
            Log.d(TAG, "Phase3: relay monitors fresh (age=${monitorAge / 60_000}min, count=${memoryEventStore.relayMonitorCount()}), skipping fetch")
            null
        } else {
            Log.d(TAG, "Phase3: fetching relay monitors (age=${monitorAge / 60_000}min, hasMonitors=$hasMonitors)")
            scope.launch {
                // Advance the 12h staleness gate ONLY on a successful fetch — a failed
                // fetch that timestamps "fetched" would suppress retries for 12h on stale
                // data (H20 lesson: a gate poisoned by unverified success).
                val ok = relayPool.fetchRelayMonitors()
                if (ok) {
                    relayPreferencesStore.setLastMonitorFetchAt(System.currentTimeMillis())
                } else {
                    Log.w(TAG, "Phase3: monitor fetch failed — not advancing 12h gate, will retry next launch")
                }
            }
        }
        trustJob?.join()
        wotJob.join()
        monitorJob?.join()

        // Retire the old local pinned-relay store (one-time, idempotent) — the feed carousel
        // now sources kind-10012 favorites. No auto-publish from old pins.
        scope.launch { relayPreferencesStore.retirePinnedStore() }

        // Pre-warm feed-switcher relays (favorites + read relays). Fire-and-forget —
        // reactive flow recomputes when favorites change.
        feedRelayWarmer.start()
        scope.launch {
            if (!relayCapabilitiesStore.isNetworkDown) {
                trendingClient.refreshIfStale()
            }
        }

        cancelLegacyBackgroundSync()

        Log.d(TAG, "Bootstrap complete for $pubkeyHex")
    }

    private fun claimAccountOwner(pubkeyHex: String) {
        // Account-switch residue: a bailed teardown may have left a different user's
        // events in MES. Clear before claiming ownership. Same-pubkey relogin keeps data.
        val prevOwner = memoryEventStore.ownPubkey
        if (prevOwner != null && prevOwner != pubkeyHex) memoryEventStore.clear()
        memoryEventStore.ownPubkey = pubkeyHex
        zapPreferencesStore.selectOwner(pubkeyHex)
        blossomServersStore.selectOwner(pubkeyHex)
        settingsStore.selectOwner(pubkeyHex)
        nwcManager.resetIfOwnerChanged(pubkeyHex)
    }

    private fun logWotCoverageCanary(ownerPubkey: String, provider: WotProviderDescriptor) {
        val follows = memoryEventStore.getFollows(ownerPubkey).orEmpty()
            .mapNotNull { it.trim().lowercase().takeIf(::isHexPubkey) }
            .toSet()
        val scoredSubjects = memoryEventStore.getWotAssertions().keys
            .mapNotNull { it.trim().lowercase().takeIf(::isHexPubkey) }
            .toSet()
        val scoredFollows = follows.count { it in scoredSubjects }
        Log.i(
            TAG,
            "WoT coverage provider=${provider.providerPubkey.take(8)} relay=${provider.relayHint} scoredFollows=$scoredFollows totalFollows=${follows.size} assertions=${scoredSubjects.size}",
        )
    }

    private fun isHexPubkey(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * BackgroundSyncWorker.doWork() is an empty stub — the 30min periodic
     * work it used to run only produced pointless device wakeups. Cancel the
     * unique work so existing installs stop the legacy schedule too.
     */
    private fun cancelLegacyBackgroundSync() {
        WorkManager.getInstance(context).cancelUniqueWork(BackgroundSyncWorker.WORK_NAME)
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
        // 0. Claim a teardown generation and cancel the in-flight bootstrap. We bump
        //    BEFORE taking the mutex so a login that supervenes will out-number us.
        val jobToCancel = bootstrapJob
        val tornGen = sessionGen.incrementAndGet()
        initGate.invalidateSession()
        jobToCancel?.cancel()

        bootstrapMutex.withLock {
            // A login that supervened now owns all shared singletons (RelayPool,
            // EventProcessor, MES, KeyManager). Any teardown work here would corrupt
            // the new session. Bail — the new bootstrap is authoritative.
            if (sessionGen.get() != tornGen) {
                Log.w(TAG, "SESSION-FENCE: teardown aborted — newer session (gen=${sessionGen.get()}) supervened tornGen=$tornGen")
                return@withLock
            }
            bootstrapJob = null
            accountMetadataRecoveryJob?.cancel()
            accountMetadataRecoveryJob = null
            muteRecoveryJob?.cancel()
            muteRecoveryJob = null
            muteBootstrapSettledForPubkey = null
            muteEventVerificationGate.reset()
            muteListRepository.markPublishUnsafe("session teardown")
            muteListRepository.markSnapshotPending()

            // 1. Close persistent subscriptions + clear relay pool caches
            relayPool.closeLiveMuteSub()
            relayPool.closeLiveNotifSub()
            relayPool.clearCaches()
            privateZapRepository.stop()
            ownZapReceiptAuthorityCoordinator.stop()

            // 2. Disconnect all WebSockets
            relayPool.disconnectAll()

            // 3. Clear ALL in-memory state — eventsById, profiles, stats, follows, relays.
            //    clearUserState() preserved eventsById which leaked old user's cached events
            //    into the new user's Global feed after re-login.
            memoryEventStore.clear()

            // 3b. Delete snapshot file — prevents restoreIfPresent() from reloading
            //     old user's events into MES on next bootstrap.
            snapshotScheduler.deleteSnapshot()

            // 4. Clear credentials, cached signer, and account-scoped local preferences
            zapPreferencesStore.clearActiveOwner()
            blossomServersStore.clearActiveOwner()
            settingsStore.clearActiveOwner()
            keyManager.clear()
            signingManager.clear()
            nwcManager.clear()

            // 5. Cancel child scopes (NOT this scope — it must survive for next login)
            eventProcessor.stop()
            eventProcessor.unregisterTap(ownMuteRelayTap)

            // 6. Release shared ExoPlayer (must be on Main — ExoPlayer thread affinity)
            withContext(Dispatchers.Main) { sharedPlayerHolder.release() }

            // 7. Clear profile resolver in-flight state
            profileResolver.clear()

            // 8. Clear CardHydrator memo + own-engagement dedup sets
            cardHydrator.resetHydratedMemo()

            // In-memory state already cleared by eventProcessor.stop() (seenIds)
            // and relayPool.disconnectAll() (connections map)

            Log.d(TAG, "Teardown complete")
        }
    }

    /**
     * Determine whether the local mute-list state is safe to publish from.
     * Runs the inner settle logic, then — if the base result would be safe —
     * runs an encrypt→decrypt round-trip self-test to verify the crypto path
     * actually works before allowing any publish.
     */
    private suspend fun settleMuteList(
        pubkeyHex: String,
        fetchResult: MuteListFetchResult,
    ): MuteSettleOutcome {
        val baseResult = settleMuteListInner(pubkeyHex, fetchResult)

        // Only run round-trip if we'd otherwise mark safe
        val wouldBeSafe = baseResult.result in setOf(
            MuteSettleResult.NsecDecrypted,
            MuteSettleResult.AmberDecrypted,
            MuteSettleResult.NoPrivateContent,
            MuteSettleResult.RelayConfirmedEmpty,
        )
        if (!wouldBeSafe) return baseResult

        // Round-trip self-test: encrypt a canary, decrypt it, verify equality.
        val roundTripOk = signingManager.encryptRoundTrip()
        if (!roundTripOk) {
            return baseResult.copy(result = MuteSettleResult.EncryptRoundTripFailed)
        }
        return baseResult
    }

    /**
     * Apply one fetch/decrypt outcome. Kept in one place so initial bootstrap
     * and bounded reconnect recovery cannot disagree about which states open
     * the publish gate or prompt Amber authorization.
     */
    private fun applyMuteSettleOutcome(
        pubkeyHex: String,
        outcome: MuteSettleOutcome,
        reasonPrefix: String,
    ): Boolean = when (outcome.result) {
        MuteSettleResult.NsecDecrypted,
        MuteSettleResult.AmberDecrypted,
        MuteSettleResult.NoPrivateContent,
        MuteSettleResult.RelayConfirmedEmpty -> {
            val opened = muteListRepository.markPublishSafe(
                reason = "$reasonPrefix: ${outcome.result}",
                expectedEventId = outcome.eventId,
                expectNoCurrentEvent = outcome.result == MuteSettleResult.RelayConfirmedEmpty,
            )
            if (opened) muteEventVerificationGate.markVerified(outcome.eventId)
            if (!opened) {
                muteListRepository.markPublishUnsafe("settled event was superseded")
                memoryEventStore.getLatestMuteListEvent(pubkeyHex)?.let { latest ->
                    scope.launch { handleOwnMuteListEvent(latest) }
                }
            }
            opened
        }
        MuteSettleResult.NoEventFound,
        MuteSettleResult.DecryptFailed,
        MuteSettleResult.Timeout,
        MuteSettleResult.EncryptRoundTripFailed -> {
            val syncState = when (outcome.result) {
                MuteSettleResult.NoEventFound,
                MuteSettleResult.Timeout,
                -> MuteSyncState.WaitingForRelayList
                MuteSettleResult.DecryptFailed,
                MuteSettleResult.EncryptRoundTripFailed,
                -> MuteSyncState.EncryptionUnavailable
                MuteSettleResult.NsecDecrypted,
                MuteSettleResult.AmberDecrypted,
                MuteSettleResult.NoPrivateContent,
                MuteSettleResult.RelayConfirmedEmpty,
                -> MuteSyncState.Preparing
            }
            muteListRepository.markPublishUnsafe(
                reason = "$reasonPrefix incomplete: ${outcome.result}",
                state = syncState,
            )
            if (keyManager.isAmberMode && outcome.result in setOf(
                    MuteSettleResult.DecryptFailed,
                    MuteSettleResult.EncryptRoundTripFailed,
                )
            ) {
                _amberReauthorizeRequiredFlow.tryEmit(Unit)
            }
            false
        }
    }

    /** Retry unresolved account metadata without holding the user on the entry screen. */
    private fun startAccountMetadataRecovery(
        pubkeyHex: String,
        indexerUrls: List<String>,
        myGen: Int,
        initSession: InitSession,
    ) {
        accountMetadataRecoveryJob?.cancel()
        accountMetadataRecoveryJob = scope.launch {
            val recoveryRelays = indexerUrls.mapNotNull(::normalizeRelayUrl).toSet()
            if (recoveryRelays.isEmpty()) return@launch
            var attempt = 0
            while (true) {
                val timedDelay = ACCOUNT_METADATA_RECOVERY_DELAYS_MS.getOrNull(attempt)
                val trigger = if (timedDelay != null) {
                    withTimeoutOrNull(timedDelay) {
                        relayPool.onRelayReconnected.first { relay ->
                            normalizeRelayUrl(relay) in recoveryRelays
                        }
                    } ?: "backoff"
                } else {
                    relayPool.onRelayReconnected.first { relay ->
                        normalizeRelayUrl(relay) in recoveryRelays
                    }
                }
                if (sessionGen.get() != myGen ||
                    !initGate.isCurrent(initSession, pubkeyHex) ||
                    keyManager.getPublicKeyHex() != pubkeyHex
                ) {
                    return@launch
                }

                Log.i(
                    TAG,
                    "ACCOUNT-RECOVERY attempt=${attempt + 1} trigger=$trigger",
                )
                relayPool.connectAndAwait(recoveryRelays.toList(), timeoutMs = 5_000)
                val result = relayPool.fetchAccountMetadata(pubkeyHex, indexerUrls)
                if (sessionGen.get() != myGen ||
                    !initGate.isCurrent(initSession, pubkeyHex) ||
                    keyManager.getPublicKeyHex() != pubkeyHex
                ) {
                    return@launch
                }
                materializeConfirmedEmptyAccountLists(pubkeyHex, result)
                if (result.hasGraphResponse) {
                    val recoveredFollows = memoryEventStore.getFollows(pubkeyHex).orEmpty()
                    if (recoveredFollows.isNotEmpty()) {
                        val unresolvedProfiles = profileResolver.filterUnresolved(recoveredFollows)
                        if (unresolvedProfiles.isNotEmpty()) {
                            profileResolver.request(unresolvedProfiles.toList())
                        }
                        relayPool.fetchRelayLists(recoveredFollows.toList())
                    }
                    Log.i(TAG, "ACCOUNT-RECOVERY resolved graph metadata")
                    return@launch
                }
                attempt++
                if (attempt == ACCOUNT_METADATA_RECOVERY_DELAYS_MS.size) {
                    Log.w(
                        TAG,
                        "ACCOUNT-RECOVERY timed retries exhausted for ${pubkeyHex.take(8)}…; " +
                            "waiting for an indexer reconnect",
                    )
                }
            }
        }
    }

    /**
     * A real EOSE quorum turns network absence into explicit loaded-empty state.
     * This is what lets a fresh imported key publish its first contact and relay
     * lists; timeouts and CLOSED frames never reach this path.
     */
    private fun materializeConfirmedEmptyAccountLists(
        pubkeyHex: String,
        result: AccountMetadataFetchResult,
    ) {
        val contactListAbsent = canMaterializeEmptyContactList(
            localStateResolved = memoryEventStore.getFollows(pubkeyHex) != null,
            declaredWriteRelays = memoryEventStore.writeRelaysFor(pubkeyHex),
            result = result,
        )
        if (contactListAbsent) {
            memoryEventStore.updateFollows(pubkeyHex, emptySet(), createdAt = 0L)
            Log.i(TAG, "ACCOUNT-METADATA confirmed empty contact list")
        }
        if (result.confirmsAbsent(10002) &&
            memoryEventStore.materializeEmptyRelayListIfAbsent(pubkeyHex)
        ) {
            Log.i(TAG, "ACCOUNT-METADATA confirmed empty relay list")
        }
    }

    /**
     * A failed one-shot is not replayed automatically. Retry on a reconnect or a
     * bounded backoff. After three timed attempts, remain passively armed for a
     * real reconnect so an offline pending edit can still reach the network without
     * creating a polling or radio loop.
     */
    private fun startMuteListReconnectRecovery(
        pubkeyHex: String,
        indexerUrls: List<String>,
        myGen: Int,
    ) {
        muteRecoveryJob?.cancel()
        muteRecoveryJob = scope.launch {
            var attempt = 0
            while (true) {
                val timedDelay = MUTE_RECOVERY_DELAYS_MS.getOrNull(attempt)
                val reconnectedRelay = if (timedDelay != null) {
                    withTimeoutOrNull(timedDelay) {
                        relayPool.onRelayReconnected.first()
                    } ?: "backoff"
                } else {
                    relayPool.onRelayReconnected.first()
                }
                if (sessionGen.get() != myGen ||
                    keyManager.getPublicKeyHex() != pubkeyHex ||
                    muteBootstrapSettledForPubkey != pubkeyHex ||
                    muteListRepository.publishSafe.value
                ) {
                    return@launch
                }

                // Let a burst of staggered reconnects settle so one request can
                // reuse several warm sockets instead of racing each handshake.
                delay(1_000L)
                if (muteListRepository.publishSafe.value) return@launch

                Log.i(
                    TAG,
                    "MUTE-RECOVERY attempt=${attempt + 1} trigger=$reconnectedRelay",
                )
                val fetchResult = relayPool.fetchMuteList(pubkeyHex, indexerUrls)
                if (sessionGen.get() != myGen || keyManager.getPublicKeyHex() != pubkeyHex) {
                    return@launch
                }
                val outcome = settleMuteList(pubkeyHex, fetchResult)
                if (applyMuteSettleOutcome(
                        pubkeyHex = pubkeyHex,
                        outcome = outcome,
                        reasonPrefix = "reconnect recovery ${attempt + 1}",
                    )
                ) {
                    return@launch
                }
                if (outcome.result in setOf(
                        MuteSettleResult.DecryptFailed,
                        MuteSettleResult.EncryptRoundTripFailed,
                    )
                ) {
                    return@launch
                }
                attempt++
                if (attempt == MUTE_RECOVERY_DELAYS_MS.size) {
                    Log.w(
                        TAG,
                        "MUTE-RECOVERY timed retries exhausted for ${pubkeyHex.take(8)}…; " +
                            "pending edit retained and waiting for a relay reconnect",
                    )
                }
            }
        }
    }

    /**
     * Called after Amber grants encryption/decryption permission. A successful
     * canary round-trip proves the permission path works, but it does not by
     * itself repopulate the already-loaded encrypted mute list. Re-decrypt the
     * current kind-10000 content before allowing publishes; otherwise the next
     * mute edit can publish an empty private list.
     */
    suspend fun recoverMuteListAfterAmberAuthorization(): Boolean {
        val pubkeyHex = keyManager.getPublicKeyHex() ?: run {
            muteListRepository.markPublishUnsafe("Amber reauth: no active account")
            return false
        }
        if (muteBootstrapSettledForPubkey != pubkeyHex) {
            muteListRepository.markPublishUnsafe("Amber reauth: bootstrap not settled")
            return false
        }
        if (!signingManager.encryptRoundTrip()) {
            muteListRepository.markPublishUnsafe(
                "Amber reauth: encrypt round-trip failed",
                MuteSyncState.EncryptionUnavailable,
            )
            return false
        }

        val event = memoryEventStore.getLatestMuteListEvent(pubkeyHex)
        if (event == null) {
            muteListRepository.markPublishUnsafe(
                "Amber reauth: no loaded mute list",
                MuteSyncState.WaitingForRelayList,
            )
            return false
        }

        if (event.content.isEmpty()) {
            val opened = muteListRepository.markPublishSafe(
                reason = "Amber reauth: current mute list has no private content",
                expectedEventId = event.id,
            )
            if (opened) muteEventVerificationGate.markVerified(event.id)
            return opened
        }

        val plaintext = signingManager.decrypt(event.content, pubkeyHex)
        val parsed = plaintext?.let(::parseMuteTags)
        if (parsed == null) {
            muteListRepository.markPublishUnsafe(
                "Amber reauth: existing mute list decrypt failed",
                MuteSyncState.EncryptionUnavailable,
            )
            return false
        }

        val applied = memoryEventStore.updateMuteListPrivateTagsIfCurrent(
            eventId = event.id,
            pubkey = pubkeyHex,
            privatePubkeys = parsed.pubkeys,
            privateHashtags = parsed.hashtags,
            privateWords = parsed.words,
            privateEventIds = parsed.eventIds,
        )
        val opened = applied && muteListRepository.markPublishSafe(
            reason = "Amber reauth decrypted existing mute list",
            expectedEventId = event.id,
        )
        if (opened) muteEventVerificationGate.markVerified(event.id)
        return opened
    }

    private suspend fun settleMuteListInner(
        pubkeyHex: String,
        fetchResult: MuteListFetchResult,
    ): MuteSettleOutcome {
        // A snapshot event is useful cached state, but it is not proof that this
        // launch queried the relay-side replaceable list. Never open the publish
        // gate from disk alone: require a verified response event, or real EOSE
        // from every target in the dedicated fetch.
        if (!fetchResult.hasFreshnessEvidence) {
            return MuteSettleOutcome(MuteSettleResult.NoEventFound)
        }

        val observedEvent = fetchResult.receivedEvent
        if (observedEvent == null && fetchResult.confirmedEmptyCoverage &&
            memoryEventStore.getLatestMuteListEvent(pubkeyHex) == null
        ) {
            return MuteSettleOutcome(MuteSettleResult.RelayConfirmedEmpty)
        }
        val settled = withTimeoutOrNull(10_000L) {
            while (true) {
                val event = awaitMuteListEvent(
                    current = { memoryEventStore.getLatestMuteListEvent(pubkeyHex) },
                    updates = memoryEventStore.ownMuteListEventFlow(),
                    timeoutMs = 2_000L,
                    accept = { candidate ->
                        observedEvent == null ||
                            candidate.id == observedEvent.id ||
                            candidate.createdAt >= observedEvent.createdAt
                    },
                )
                if (event == null) {
                    return@withTimeoutOrNull if (
                        observedEvent == null && fetchResult.confirmedEmptyCoverage
                    ) {
                        MuteSettleOutcome(MuteSettleResult.RelayConfirmedEmpty)
                    } else {
                        MuteSettleOutcome(MuteSettleResult.NoEventFound)
                    }
                }

                if (event.content.isEmpty()) {
                    if (!memoryEventStore.isCurrentMuteListEvent(pubkeyHex, event.id)) continue
                    return@withTimeoutOrNull MuteSettleOutcome(
                        result = MuteSettleResult.NoPrivateContent,
                        eventId = event.id,
                    )
                }

                val plaintext = signingManager.decrypt(event.content, pubkeyHex)
                val privateTags = plaintext?.let(::parseMuteTags)
                if (!memoryEventStore.isCurrentMuteListEvent(pubkeyHex, event.id)) continue
                if (privateTags == null) {
                    return@withTimeoutOrNull MuteSettleOutcome(
                        result = MuteSettleResult.DecryptFailed,
                        eventId = event.id,
                    )
                }

                val applied = memoryEventStore.updateMuteListPrivateTagsIfCurrent(
                    eventId = event.id,
                    pubkey = pubkeyHex,
                    privatePubkeys = privateTags.pubkeys,
                    privateHashtags = privateTags.hashtags,
                    privateWords = privateTags.words,
                    privateEventIds = privateTags.eventIds,
                )
                if (!applied) continue
                return@withTimeoutOrNull MuteSettleOutcome(
                    result = if (keyManager.isAmberMode) {
                        MuteSettleResult.AmberDecrypted
                    } else {
                        MuteSettleResult.NsecDecrypted
                    },
                    eventId = event.id,
                )
            }
            @Suppress("UNREACHABLE_CODE")
            MuteSettleOutcome(MuteSettleResult.Timeout)
        }

        return settled ?: MuteSettleOutcome(MuteSettleResult.Timeout)
    }

    /**
     * Verify a newly accepted own kind-10000. The MES callback closes the gate
     * synchronously; this reopens it only if the same event remains current.
     */
    private suspend fun handleOwnMuteListEvent(
        event: com.unsilence.app.data.memory.NostrEvent,
    ) {
        // Initial bootstrap settlement verifies only the final event after fetch
        // coverage. Avoid decrypting every historical snapshot revision.
        if (muteBootstrapSettledForPubkey != event.pubkey) return
        // A stale relay copy cannot become current again without a newer MES
        // callback, so reject it before potentially invoking Amber.
        if (!memoryEventStore.isCurrentMuteListEvent(event.pubkey, event.id)) return
        if (!muteEventVerificationGate.tryBegin(event.id)) return

        var opened = false
        try {
            val verified = if (event.content.isEmpty()) {
                memoryEventStore.isCurrentMuteListEvent(event.pubkey, event.id)
            } else {
                val plaintext = signingManager.decrypt(event.content, event.pubkey)
                val parsed = plaintext?.let(::parseMuteTags)
                if (parsed == null) {
                    if (memoryEventStore.isCurrentMuteListEvent(event.pubkey, event.id)) {
                        muteListRepository.markPublishUnsafe(
                            "new own kind-10000 decrypt failed",
                            MuteSyncState.EncryptionUnavailable,
                        )
                        if (keyManager.isAmberMode) _amberReauthorizeRequiredFlow.tryEmit(Unit)
                    }
                    return
                }
                memoryEventStore.updateMuteListPrivateTagsIfCurrent(
                    eventId = event.id,
                    pubkey = event.pubkey,
                    privatePubkeys = parsed.pubkeys,
                    privateHashtags = parsed.hashtags,
                    privateWords = parsed.words,
                    privateEventIds = parsed.eventIds,
                )
            }
            if (verified) {
                opened = muteListRepository.markPublishSafe(
                    reason = "new own kind-10000 verified",
                    expectedEventId = event.id,
                )
                if (opened) muteRecoveryJob?.cancel()
            }
        } finally {
            muteEventVerificationGate.finish(event.id, verified = opened)
        }
    }

    private data class ParsedMuteTags(
        val pubkeys: Set<String>,
        val hashtags: Set<String>,
        val words: Set<String>,
        val eventIds: Set<String>,
    )

    private fun parseMuteTags(plaintext: String): ParsedMuteTags? {
        return runCatching {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(plaintext)
            if (arr !is kotlinx.serialization.json.JsonArray) return null
            val pubkeys = mutableSetOf<String>()
            val hashtags = mutableSetOf<String>()
            val words = mutableSetOf<String>()
            val eventIds = mutableSetOf<String>()
            for (tagArr in arr) {
                val tag = (tagArr as kotlinx.serialization.json.JsonArray)
                    .map { it.jsonPrimitive.content }
                if (tag.size < 2) continue
                when (tag[0]) {
                    "p" -> pubkeys.add(tag[1])
                    "t" -> hashtags.add(tag[1].lowercase())
                    "word" -> words.add(tag[1].lowercase())
                    "e" -> eventIds.add(tag[1])
                }
            }
            ParsedMuteTags(pubkeys, hashtags, words, eventIds)
        }.getOrElse { e ->
            Log.w(TAG, "parseMuteTags: ${e.message}")
            null
        }
    }
}

internal enum class MuteSettleResult {
    NsecDecrypted,
    AmberDecrypted,
    NoPrivateContent,
    RelayConfirmedEmpty,
    NoEventFound,
    DecryptFailed,
    Timeout,
    EncryptRoundTripFailed,
}

internal data class MuteSettleOutcome(
    val result: MuteSettleResult,
    val eventId: String? = null,
)

/** Waits for a real raw kind-10000, never a locally-derived MuteList overlay. */
internal suspend fun awaitMuteListEvent(
    current: () -> NostrEvent?,
    updates: Flow<NostrEvent?>,
    timeoutMs: Long,
    accept: (NostrEvent) -> Boolean = { true },
): NostrEvent? = current()?.takeIf(accept) ?: withTimeoutOrNull(timeoutMs) {
    updates.filterNotNull().first(accept)
}
