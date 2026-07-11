package com.unsilence.app.data.relay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import com.unsilence.app.data.WOT_REGISTRY_LOOKUP_RELAYS
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.PaginatedFetchResult
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RelayPool"

/** Profile fallback negative-cache TTL — prevents re-firing the full chain. */
private const val PROFILE_FALLBACK_NEG_TTL = 5 * 60_000L
/** Max fallback relay targets across all missing pks in one batch. */
private const val MAX_PROFILE_FALLBACK_RELAYS = 8
/** Wait for EventProcessor cold-lane flush (2s batch cycle) + margin.
 *  Must track EventProcessor.drainCold's 2s timeout — if that changes, update here. */
private const val COLD_LANE_FLUSH_MS = 2_500L

/** A search result correlated with the token of the search session that produced it. */
data class SearchResult(val token: Long, val eventId: String)

/** Why a relay connection exists — a relay can hold multiple purposes simultaneously. */
enum class ConnectionPurpose {
    /** Long-lived, bootstrap-tagged (indexers, global default relays). Never evicted by sweep. */
    PERSISTENT,
    /** User-initiated relay-browse session (RelayBrowseSession). Evicted when session ends. */
    BROWSE,
    /** Active single-relay feed source. Exempted from sweep while feed is active;
     *  removed when user switches feed type. */
    FEED_SUB,
    /** Pre-warmed feed-switcher relay. Connected socket, no subscription.
     *  Exempt from sweep; dropped when no longer a switch target. */
    FEED_WARM,
}

data class RelayConnectionDebugSnapshot(
    val url: String,
    val purposes: Set<ConnectionPurpose>,
    val oneShotCount: Int,
    val queuedReqCount: Int,
    val hasActiveSubscription: Boolean,
)

/** Source of "which relays currently have a non-paused subscription."
 *  Consulted by the pool sweep to avoid force-closing connections with
 *  live subscriptions. Implemented by [Subscription]. */
interface ActiveSubsSource {
    fun activeRelayUrls(): Set<String>
    fun activeSubIds(): Set<String>
}

/**
 * Manages multiple relay WebSocket connections for the global feed.
 *
 * Architecture rule: events flow Relay → EventProcessor → MemoryEventStore.
 * The pool itself never touches the UI.
 */
@Singleton
class RelayPool @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val processor: EventProcessor,
    private val relayPreferencesStore: dagger.Lazy<RelayPreferencesStore>,
    private val signingManager: com.unsilence.app.data.auth.SigningManager,
    private val keyManager: com.unsilence.app.data.auth.KeyManager,
    private val memoryEventStore: dagger.Lazy<com.unsilence.app.data.memory.MemoryEventStore>,
    private val relayCapabilitiesStore: RelayCapabilitiesStore,
    private val activeSubsSource: dagger.Lazy<ActiveSubsSource>,
    private val networkMonitor: NetworkMonitor,
    private val nip11Fetcher: Nip11Fetcher,
) : RelayTransport, ReconnectSource {
    // WebSocket consume loops MUST not be starved by snapshot restore or
    // other heavy IO. limitedParallelism(8) reserves dedicated threads for
    // inbound message processing.
    private val wsDispatcher = Dispatchers.IO.limitedParallelism(8)
    private val scope = CoroutineScope(SupervisorJob() + wsDispatcher)
    private val connections = ConcurrentHashMap<String, RelayConnection>()

    /** Relay URLs deferred during network-down/DNS-degraded. Drained with jitter
     *  when the network recovers (checked in the 60s sweep). */
    private val pendingReconnect: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Coverage-ranked global outbox relay allowlist. Ephemeral connections to relays
     *  NOT in this set (and not in the persistent pool) are skipped to shrink the DNS
     *  failure surface. Populated by [updateOutboxAllowlist] after kind-10002 is fetched. */
    @Volatile private var outboxAllowlist: Set<String> = emptySet()
    private val reconnecting = ConcurrentHashMap<String, AtomicBoolean>()
    /** Cached blocked relay URLs, refreshed before each connect(). */
    @Volatile private var blockedUrls: Set<String> = emptySet()

    /** Integral relay URLs (indexer/read/write/search) — re-attempted by the heal
     *  pass when disconnected and past their skip cooldown. */
    @Volatile private var integralRelayUrls: Set<String> = emptySet()

    fun setIntegralRelays(urls: Collection<String>) {
        integralRelayUrls = urls.mapNotNull { normalizeRelayUrl(it) }.toSet()
    }

    /** Update the coverage-ranked outbox allowlist. Ephemeral connections to relays
     *  outside this set (and not in the persistent pool) are skipped. */
    fun setOutboxAllowlist(urls: Set<String>) {
        outboxAllowlist = urls
        Log.w(TAG, "Outbox allowlist updated: ${urls.size} relays")
    }

    /** Read-only access to blocked relay URLs for allowlist construction. */
    fun getBlockedUrls(): Set<String> = blockedUrls

    /**
     * Set by FeedViewModel when the user is viewing a SingleRelay feed.
     * One-shot dispatch paths exclude this URL from their relay sets to prevent
     * funneling auxiliary work back to the relay already hosting the persistent
     * feed subscription.
     *
     * Null when the active feed is Following / Global / RelaySet — in those
     * cases auxiliary fanout across multiple relays is desirable and the
     * funneling concern doesn't apply.
     */
    @Volatile var activeSingleRelayFeedUrl: String? = null

    /** Snapshot of currently-connected relay URLs. Read-only, used by CardHydrator
     *  as fallback when write relays are unknown. */
    fun connectedRelayUrls(): List<String> = connections.keys.toList()

    // ── Connection purpose tracking ────────────────────────────────────────
    // A relay can serve multiple purposes simultaneously (e.g. PERSISTENT + BROWSE).
    // Persistent sub replay is only skipped when a relay is browse-only.
    private val connectionPurposes = ConcurrentHashMap<String, MutableSet<ConnectionPurpose>>()

    fun addPurpose(url: String, purpose: ConnectionPurpose) {
        connectionPurposes.computeIfAbsent(url) { ConcurrentHashMap.newKeySet() }.add(purpose)
        Log.d(TAG, "Purpose + $purpose on $url (now: ${connectionPurposes[url]})")
    }

    fun removePurpose(url: String, purpose: ConnectionPurpose) {
        connectionPurposes[url]?.remove(purpose)
        if (connectionPurposes[url]?.isEmpty() == true) {
            connectionPurposes.remove(url)
        }
        Log.d(TAG, "Purpose - $purpose on $url (now: ${connectionPurposes[url] ?: "none"})")
    }

    fun hasPurpose(url: String, purpose: ConnectionPurpose): Boolean =
        connectionPurposes[url]?.contains(purpose) == true

    fun isBrowseOnly(url: String): Boolean =
        hasPurpose(url, ConnectionPurpose.BROWSE) &&
        !hasPurpose(url, ConnectionPurpose.PERSISTENT)

    fun hasAnyPurpose(url: String): Boolean =
        connectionPurposes[url]?.isNotEmpty() == true

    fun connectionDebugSnapshot(): Map<String, RelayConnectionDebugSnapshot> {
        val activeSubUrls = runCatching { activeSubsSource.get().activeRelayUrls() }
            .getOrDefault(emptySet())
        val urls = buildSet {
            addAll(connections.keys)
            addAll(connectionPurposes.keys)
            addAll(activeSubUrls)
            addAll(relayOneShotCount.keys)
            addAll(relayReqQueue.keys)
        }
        return urls.associateWith { url ->
            RelayConnectionDebugSnapshot(
                url = url,
                purposes = connectionPurposes[url]?.toSet().orEmpty(),
                oneShotCount = relayOneShotCount[url]?.get() ?: 0,
                queuedReqCount = relayReqQueue[url]?.size ?: 0,
                hasActiveSubscription = url in activeSubUrls,
            )
        }
    }

    private val countCallbacks = ConcurrentHashMap<String, CompletableDeferred<Long?>>()
    /** One-shot REQ callbacks that return the first EVENT's raw tags JSON. */
    internal val eventTagsCallbacks = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    /** Per-subId EOSE completion signal. Callers register before dispatch, await after.
     *  handleEose completes the deferred when any relay EOSE's the sub. */
    internal val oneShotEoseCallbacks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    /** Per-subId set of relay URLs the one-shot was sent to. */
    private val oneShotSubTargets = ConcurrentHashMap<String, Set<String>>()
    /** Per-subId set of relay URLs that have EOSE'd or CLOSED. */
    private val oneShotSubEosed = ConcurrentHashMap<String, MutableSet<String>>()
    /** Per-subId set of relay URLs whose slot has already been released.
     *  Idempotency guard for [releaseOneShotForRelay] — prevents double-decrement
     *  when both handleEose and cleanupOneShotSub fire for the same (subId, url). */
    private val oneShotReleased = ConcurrentHashMap<String, MutableSet<String>>()
    /** Per-subId first-EOSE completion signal. Completed when ANY single relay
     *  EOSE's the sub. Used by engagement dispatch (first-EOSE-wins) so callers
     *  don't wait for all 4-6 outbox relays to respond. */
    internal val oneShotFirstEose = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val profileFetchAttempted = ConcurrentHashMap<String, Long>()
    /** Pubkeys that went through the full indexer+fallback chain and still missed.
     *  5-min TTL prevents scroll-back from re-firing the chain. */
    private val profileFallbackNegCache = ConcurrentHashMap<String, Long>()

    /** In-flight event fetch dedup — maps event ID to completion signal.
     *  Callers that arrive while a fetch is in-flight skip the REQ;
     *  the monitor coroutine completes the Deferred and removes the entry. */
    private val eventFetchInFlight = ConcurrentHashMap<String, CompletableDeferred<NostrEvent?>>()
    /** Peak eventFetchInFlight.size since last metrics snapshot — for instrumentation. */
    private val eventFetchInFlightPeak = AtomicInteger(0)

    /** Negative cache: event IDs that failed to resolve after all outbox phases.
     *  Values = timestamp (epoch ms). TTL 5 min. Written by CardHydrator, checked
     *  by every fetchEventById/fetchEventsByIds entry point. */
    private val missingRefCache = ConcurrentHashMap<String, Long>()
    private val MISSING_REF_TTL_MS = 5 * 60 * 1000L // 5 minutes
    private val missingRefCacheHits = AtomicLong(0)

    // ── Ephemeral WebSocket rate limiting ─────────────────────────────────
    /** Per-URL last-open timestamp (nanos) for ephemeral connections — min 50ms gap. */
    private val ephemeralLastOpenNanos = ConcurrentHashMap<String, AtomicLong>()
    private val MIN_EPHEMERAL_GAP_NS = 50_000_000L // 50ms

    /** Relays that have completed NIP-42 auth successfully. */
    private val authenticatedRelays = ConcurrentHashMap.newKeySet<String>()

    /** Relays currently waiting for an auth response (prevents duplicate auth attempts). */
    private val authInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Last challenge received per relay — needed for CLOSED auth-required flow. */
    private val pendingChallenges = ConcurrentHashMap<String, String>()

    /** Relays that sent CLOSED auth-required without a prior AUTH challenge — suppress repeated warnings. */
    private val authFailedRelays = ConcurrentHashMap.newKeySet<String>()

    /** Auth event IDs awaiting OK response — maps eventId → relay URL. */
    private val pendingAuthEventIds = ConcurrentHashMap<String, String>()

    /** Consecutive auth-required rejections per relay since last real OK.
     *  Reset on real OK. */
    private val authRejectionStreak = ConcurrentHashMap<String, Int>()

    /** Relays granted the one-time no-OK optimistic fallback on this connection.
     *  A subsequent challenge proves that optimism did not establish a stable
     *  authenticated session and must not start another replay loop. */
    private val optimisticAuthUsed = ConcurrentHashMap.newKeySet<String>()

    /** Relays whose auth repeatedly failed — excluded from fan-out.
     *  Session-scoped (in-memory). Cleared on logout via disconnectAll(). */
    private val authUnavailableRelays = ConcurrentHashMap.newKeySet<String>()

    /** Emitted when a relay is determined to require auth we can't satisfy. */
    private val _relayAuthUnavailable = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val relayAuthUnavailable: SharedFlow<String> = _relayAuthUnavailable.asSharedFlow()

    /** Publish OK callbacks — maps eventId → callback receiving (relayUrl, success, message). */
    private val publishOkCallbacks = ConcurrentHashMap<String, (String, Boolean, String) -> Unit>()

    /** Last time each relay received a message — used for idle eviction. */
    private val connectionLastActivity = ConcurrentHashMap<String, Long>()

    /** Active one-shot subscriptions in flight (tracked by unique sub-ID, not per-relay). */
    private val _activeOneShotSubs = ConcurrentHashMap.newKeySet<String>()

    // ── Persistent own-mute-list subscription ─────────────────────────
    // Keeps a live tail on own kind-10000 so cross-client mute changes
    // arrive without needing a cold-start re-fetch.
    @Volatile private var liveMuteSubReq: String? = null
    private val liveMuteSubRelays = ConcurrentHashMap.newKeySet<String>()

    // Persistent #p notification tail — forward-looking only (since:bootstrap_time).
    // Catches new reactions/reposts/zaps/replies mentioning the user after bootstrap.
    @Volatile private var liveNotifSubReq: String? = null
    @Volatile private var liveNotifSince: Long? = null
    private val liveNotifSubRelays = ConcurrentHashMap.newKeySet<String>()
    private val searchTimeoutJobs = ConcurrentHashMap<Long, Job>()

    // ── Paginated fetch state ─────────────────────────────────────────
    private data class PageState(
        val eventCount: AtomicInteger = AtomicInteger(0),
        val oldestCreatedAt: AtomicLong = AtomicLong(Long.MAX_VALUE),
        val eoseReceived: CompletableDeferred<Unit> = CompletableDeferred(),
    )
    private val _activePages = ConcurrentHashMap<String, PageState>()

    /** Number of in-flight one-shot subscriptions — used by HydrationFrontier for priority shedding. */
    fun activeOneShotCount(): Int = _activeOneShotSubs.size

    // ── Negative cache API ───────────────────────────────────────────

    /** Check if an event ID is in the negative cache (within TTL). Expired entries are evicted lazily. */
    fun isEventUnresolved(eventId: String): Boolean {
        val ts = missingRefCache[eventId] ?: return false
        if (System.currentTimeMillis() - ts < MISSING_REF_TTL_MS) {
            missingRefCacheHits.incrementAndGet()
            return true
        }
        missingRefCache.remove(eventId)
        return false
    }

    /** Mark an event ID as unresolved (negative cache, 5-min TTL). */
    fun markEventUnresolved(eventId: String) {
        missingRefCache[eventId] = System.currentTimeMillis()
    }

    // ── Relay metrics (for MES/size logger) ──────────────────────────

    data class RelayMetrics(
        val eventFetchInFlightPeak: Int,
        val missingRefCacheSize: Int,
        val missingRefCacheHits: Long,
    )

    fun snapshotRelayMetrics(): RelayMetrics = RelayMetrics(
        eventFetchInFlightPeak = eventFetchInFlightPeak.getAndSet(0),
        missingRefCacheSize = missingRefCache.size,
        missingRefCacheHits = missingRefCacheHits.getAndSet(0),
    )

    // ── In-flight fetch monitor ──────────────────────────────────────

    /** Launch a coroutine that polls MES for event arrival and completes the Deferred.
     *  Removes the entry from eventFetchInFlight on completion (success or timeout). */
    private fun launchFetchMonitor(eventId: String, deferred: CompletableDeferred<NostrEvent?>) {
        scope.launch {
            try {
                val mes = memoryEventStore.get()
                val result = withTimeoutOrNull(30_000L) {
                    while (true) {
                        mes.getNostrEvent(eventId)?.let { return@withTimeoutOrNull it }
                        delay(500)
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
                deferred.complete(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                deferred.complete(null)
                throw e
            } catch (e: Exception) {
                deferred.complete(null)
            } finally {
                eventFetchInFlight.remove(eventId)
            }
        }
    }

    /** Update the peak-size watermark for instrumentation. */
    private fun trackInFlightPeak() {
        val current = eventFetchInFlight.size
        eventFetchInFlightPeak.updateAndGet { maxOf(it, current) }
    }

    // ── Per-relay REQ queue ───────────────────────────────────────────
    // Relays typically allow 10-20 concurrent subscriptions. When a relay hits
    // the limit, new one-shot REQs are queued and sent as CLOSE events free slots.
    companion object {
        const val MAX_CONCURRENT_REQS_PER_RELAY = 10
        const val IDLE_EVICTION_THRESHOLD_MS = 60_000L
        // Safety rail against runaway bugs. Not a resource policy.
        // BROWSE is session-scoped. If this fires, something is misbehaving — investigate.
        const val POOL_SAFETY_CAP = 50
        const val POOL_SWEEP_CAP = 40
        const val RATE_LIMIT_MAX_TOKENS = 5
        const val RATE_LIMIT_REFILL_MS = 1000L
        const val RATE_LIMIT_COOLDOWN_MS = 30_000L
        const val RECONNECT_JITTER_WINDOW_MS = 8_000L
        const val SEARCH_TIMEOUT_MS = 10_000L
        const val RELAY_MONITOR_URL = "wss://relay.nostr.watch"
        const val RELAY_MONITOR_PUBKEY =
            "9bbbb845e5b6c831c29789900769843ab43bb5047abe697870cb50b6fc9bf923"
        const val MAX_AUTH_REJECTIONS = 3
        /** One optimistic attempt plus one re-challenge without an OK is enough
         *  to prove the relay cannot establish a stable authenticated session. */
        const val MAX_AUTH_NO_OK_STREAK = 2
    }

    /** Active one-shot sub count per relay URL. */
    private val relayOneShotCount = ConcurrentHashMap<String, AtomicInteger>()
    /** Queued REQs per relay — sent when slots free up. */
    private val relayReqQueue = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<String>>()

    // ── Per-relay rate limiter (token bucket + cooldown) ──────────────
    private data class RateLimitState(
        val tokens: AtomicInteger = AtomicInteger(RATE_LIMIT_MAX_TOKENS),
        val lastRefill: AtomicLong = AtomicLong(System.currentTimeMillis()),
        val cooldownUntil: AtomicLong = AtomicLong(0),
    )
    private val rateLimiters = ConcurrentHashMap<String, RateLimitState>()
    /** Log cooldown drops once per relay per cooldown window. */
    private val cooldownLogged = ConcurrentHashMap<String, Long>()

    /** True when the relay is NOT in a CLOSED-triggered cooldown window.
     *  Unlike [canSendToRelay], this does NOT consume a rate-limit token — used by
     *  [flushRelayQueue] where REQs were already rate-gated on entry. */
    private fun isRelayOutOfCooldown(url: String): Boolean {
        val state = rateLimiters[url] ?: return true
        return System.currentTimeMillis() >= state.cooldownUntil.get()
    }

    private fun canSendToRelay(url: String): Boolean {
        val state = rateLimiters.getOrPut(url) { RateLimitState() }
        val now = System.currentTimeMillis()
        // Cooldown check
        if (now < state.cooldownUntil.get()) return false
        // Refill tokens
        val elapsed = now - state.lastRefill.get()
        if (elapsed >= RATE_LIMIT_REFILL_MS) {
            val refillCount = (elapsed / RATE_LIMIT_REFILL_MS).toInt().coerceAtMost(RATE_LIMIT_MAX_TOKENS)
            state.tokens.updateAndGet { (it + refillCount).coerceAtMost(RATE_LIMIT_MAX_TOKENS) }
            state.lastRefill.set(now)
        }
        // Consume token — getAndUpdate returns the PRE-update value.
        // If pre-update > 0, we had a token to spend → allowed.
        val had = state.tokens.getAndUpdate { if (it > 0) it - 1 else 0 }
        if (had <= 0) {
            Log.d(TAG, "Rate limit: token exhausted for $url (throttling)")
        }
        return had > 0
    }

    private fun markRelayRateLimited(url: String) {
        val state = rateLimiters.getOrPut(url) { RateLimitState() }
        val until = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
        state.cooldownUntil.set(until)
        Log.w(TAG, "Relay $url marked for cooldown until ${java.util.Date(until)} (rate-limited)")
    }

    /**
     * Send a one-shot REQ to a relay, queuing if the relay is at its concurrent sub limit.
     * Drops REQs when the relay is in rate-limit cooldown or token-starved.
     * Call this instead of conn.send(req) for all one-shot subscription REQs.
     *
     * Prefetch subs (sub-ID starting with "prefetch-") are capped at 8 slots,
     * reserving 2 slots for high-priority work (profile fetches, user posts,
     * relay ecosystem). Without this reservation, prefetch floods all 10 slots
     * and starves profile page content.
     */
    private fun sendOneShotToRelay(conn: RelayConnection, req: String) {
        val count = relayOneShotCount.computeIfAbsent(conn.url) { AtomicInteger(0) }
        val isPrefetch = req.contains("\"prefetch-")
        val effectiveCap = if (isPrefetch) MAX_CONCURRENT_REQS_PER_RELAY - 2 else MAX_CONCURRENT_REQS_PER_RELAY
        // Queue when at sub cap OR rate-limited (don't drop — flush will retry later)
        if (count.get() >= effectiveCap || !canSendToRelay(conn.url)) {
            val queue = relayReqQueue.computeIfAbsent(conn.url) { java.util.concurrent.ConcurrentLinkedQueue() }
            queue.add(req)
            Log.d(TAG, "Queued REQ for ${conn.url} (${count.get()}/$MAX_CONCURRENT_REQS_PER_RELAY active)")
            return
        }
        count.incrementAndGet()
        if (!conn.send(req)) {
            count.decrementAndGet()
            Log.w(TAG, "One-shot send failed for ${conn.url}; slot released")
        }
    }

    /**
     * Dispatch a one-shot REQ to [url]: reuse the pooled connection if one exists,
     * otherwise open an ephemeral connection (no pool slot, no cap, auto-closes
     * after EOSE/timeout). NEVER connectAndAwait — transient hint/ref fetches must
     * not occupy pool slots (Slice 8: 192-relay hint fan-out exhausted the pool).
     */
    private fun sendOneShotPooledOrEphemeral(
        url: String,
        req: String,
        subId: String,
        timeoutMs: Long = 2_000,
    ) {
        // Belt-and-suspenders: callers normalize, but guard here so a malformed URL
        // never reaches openEphemeral → RelayConnection.connect → okhttp crash.
        val clean = normalizeRelayUrl(url) ?: run {
            Log.w(TAG, "sendOneShotPooledOrEphemeral: skipping invalid relay url: ${url.take(80)}")
            return
        }
        val conn = connections[clean]
        if (conn != null) {
            sendOneShotToRelay(conn, req)
        } else {
            // Outbox allowlist gate: don't open ephemeral connections to relays
            // outside the coverage-ranked set. Shrinks the DNS failure surface.
            // Allowlist empty = not yet populated (bootstrap) → allow all.
            if (outboxAllowlist.isNotEmpty() && clean !in outboxAllowlist) {
                Log.d(TAG, "Ephemeral skipped (not in outbox allowlist): ${clean.take(60)}")
                return
            }
            scope.launch { openEphemeral(clean, listOf(req), setOf(subId), timeoutMs) }
        }
    }

    /**
     * Flush queued REQs for a relay after a slot frees up.
     */
    private fun flushRelayQueue(conn: RelayConnection) {
        val count = relayOneShotCount[conn.url] ?: return
        val queue = relayReqQueue[conn.url] ?: return
        while (count.get() < MAX_CONCURRENT_REQS_PER_RELAY &&
               queue.isNotEmpty() &&
               isRelayOutOfCooldown(conn.url)) {
            val req = queue.poll() ?: break
            count.incrementAndGet()
            if (conn.send(req)) {
                Log.d(TAG, "Flushed queued REQ on ${conn.url} (${count.get()}/$MAX_CONCURRENT_REQS_PER_RELAY active)")
            } else {
                count.decrementAndGet()
                queue.add(req)
                Log.w(TAG, "Queued REQ send failed for ${conn.url}; slot released")
                break
            }
        }
    }

    /** Safety rail: reject new connections if pool is at capacity. */
    private fun canOpenNewConnection(): Boolean {
        val size = connections.size
        if (size >= POOL_SAFETY_CAP) {
            Log.w(TAG, "Pool safety cap reached ($size/$POOL_SAFETY_CAP) — this shouldn't happen, investigate")
            return false
        }
        return true
    }

    private fun logPoolState() {
        val purposeCounts = mutableMapOf<String, Int>()
        for ((url, _) in connections) {
            val purposes = connectionPurposes[url]
            if (purposes.isNullOrEmpty()) {
                purposeCounts["NONE"] = (purposeCounts["NONE"] ?: 0) + 1
            } else {
                for (p in purposes) {
                    val key = p.name
                    purposeCounts[key] = (purposeCounts[key] ?: 0) + 1
                }
            }
        }
        Log.w(TAG, "Pool: total=${connections.size} $purposeCounts")
    }

    /**
     * Try to evict one idle BROWSE connection to make room for a new one.
     * Returns true if a connection was evicted.
     */
    private fun evictIdleConnection(): Boolean {
        val now = System.currentTimeMillis()
        // BROWSE and purpose-less (NONE) connections are evictable. PERSISTENT is exempt.
        val candidate = connections.entries
            .filter { (url, _) ->
                !hasPurpose(url, ConnectionPurpose.PERSISTENT) &&
                (hasPurpose(url, ConnectionPurpose.BROWSE) || !hasAnyPurpose(url))
            }
            .filter { (url, _) ->
                val lastActive = connectionLastActivity[url] ?: 0L
                (now - lastActive) >= IDLE_EVICTION_THRESHOLD_MS
            }
            .maxByOrNull { (url, _) ->
                // Evict the most idle connection first
                now - (connectionLastActivity[url] ?: 0L)
            }
        if (candidate != null) {
            val (url, conn) = candidate
            val idleSec = (now - (connectionLastActivity[url] ?: 0L)) / 1000
            connections.remove(url)
            conn.close()
            connectionPurposes.remove(url)
            connectionLastActivity.remove(url)
            relayOneShotCount.remove(url)
            relayReqQueue.remove(url)
            Log.d(TAG, "Evicted idle connection $url (idle ${idleSec}s, at cap)")
            return true
        }
        return false
    }

    /**
     * Force-evict the most idle BROWSE connection regardless of idle threshold.
     * Falls back to the most idle non-PERSISTENT connection.
     * Used by [connectAndAwait] with forceEvict=true for critical one-shot queries
     * (e.g., NIP-45 COUNT) when the pool is at cap and normal eviction fails.
     */
    private fun forceEvictMostIdle(): Boolean {
        val now = System.currentTimeMillis()
        // Prefer BROWSE-only connections (transient, expendable)
        val candidate = connections.entries
            .filter { (url, _) ->
                hasPurpose(url, ConnectionPurpose.BROWSE) &&
                !hasPurpose(url, ConnectionPurpose.PERSISTENT)
            }
            .maxByOrNull { (url, _) -> now - (connectionLastActivity[url] ?: 0L) }
            ?: connections.entries
                .filter { (url, _) ->
                    !hasPurpose(url, ConnectionPurpose.PERSISTENT)
                }
                .maxByOrNull { (url, _) -> now - (connectionLastActivity[url] ?: 0L) }
        if (candidate != null) {
            val (url, conn) = candidate
            val idleSec = (now - (connectionLastActivity[url] ?: 0L)) / 1000
            connections.remove(url)
            conn.close()
            connectionPurposes.remove(url)
            connectionLastActivity.remove(url)
            relayOneShotCount.remove(url)
            relayReqQueue.remove(url)
            Log.d(TAG, "Force-evicted connection $url (idle ${idleSec}s) for one-shot query")
            return true
        }
        return false
    }

    // Wire EventProcessor relay set ref fetcher
    init {
        processor.relaySetRefFetcher = RelaySetRefFetcher { author, dTags, hintRelayUrls ->
            scope.launch { fetchRelaySetsByCoordinate(author, dTags, hintRelayUrls) }
        }
    }

    // Hook 2 (H18.4b): network identity change → clear DNS-dead state.
    // VPN toggle / WiFi↔cellular changes the DNS resolver. Relays dead on
    // the old network may resolve on the new one. Don't eagerly reconnect —
    // just clear the skip-gate; demand paths + 60s sweep pick them up.
    init {
        scope.launch {
            networkMonitor.networkChanged.collect {
                relayCapabilitiesStore.clearDnsDeadOnNetworkChange()
            }
        }
    }

    // Evict stale entries every 5 minutes to prevent unbounded growth.
    init {
        scope.launch {
            while (true) {
                delay(300_000)
                val cutoff = System.currentTimeMillis() - 300_000
                // eventFetchInFlight: self-cleaning via launchFetchMonitor finally blocks
                missingRefCache.entries.removeIf { it.value < cutoff } // same 5-min TTL
                profileFetchAttempted.entries.removeIf { it.value < cutoff }
                profileFallbackNegCache.entries.removeIf { it.value < cutoff }
                sourceProfileAttempted.entries.removeIf { it.value < cutoff }
            }
        }
        // Periodic pool state logging + connection sweep — every 60s.
        scope.launch {
            while (true) {
                delay(60_000)
                logPoolState()
                // Sweep unused connections (existing per-url release pass)
                for (url in connections.keys.toList()) {
                    releaseIfUnused(url)
                }
                // Hard cap: if pool > POOL_SWEEP_CAP, force-close oldest evictable.
                if (connections.size > POOL_SWEEP_CAP) {
                    val activeSubUrls = runCatching { activeSubsSource.get().activeRelayUrls() }
                        .getOrDefault(emptySet())
                    val candidates = connections.keys
                        .filter { url ->
                            !hasPurpose(url, ConnectionPurpose.PERSISTENT) &&
                            !hasPurpose(url, ConnectionPurpose.FEED_SUB) &&
                            url !in activeSubUrls
                        }
                        .sortedBy { connectionLastActivity[it] ?: 0L }

                    val toCloseTarget = connections.size - POOL_SWEEP_CAP
                    val toCloseActual = candidates.size.coerceAtMost(toCloseTarget)
                    for (url in candidates.take(toCloseActual)) {
                        // Map-before-close: remove first so listenForEvents.finally sees identity mismatch
                        val conn = connections.remove(url) ?: continue
                        connectionPurposes.remove(url)
                        connectionLastActivity.remove(url)
                        conn.close()
                        Log.w(TAG, "Pool over cap, force-closed: $url")
                    }
                    if (toCloseActual < toCloseTarget) {
                        Log.w(TAG, "Pool sweep couldn't reach cap: ${connections.size} > $POOL_SWEEP_CAP " +
                            "(closed $toCloseActual of $toCloseTarget; all remaining are exempt)")
                    }
                }
                // Drain deferred reconnects. Spread with jitter to avoid spiking the resolver.
                if (pendingReconnect.isNotEmpty()) {
                    if (!relayCapabilitiesStore.isNetworkDown) {
                        // Recovered — drain everything.
                        // DNS-dead state is cleared by the networkChanged collector (H18.4b);
                        // doing it here too caused a duplicate DataStore persist.
                        val deferred = pendingReconnect.toList()
                        pendingReconnect.clear()
                        Log.w(TAG, "Network recovered — draining ${deferred.size} deferred reconnects with jitter")
                        for (url in deferred) {
                            scope.launch {
                                delay(kotlin.random.Random.nextLong(RECONNECT_JITTER_WINDOW_MS))
                                reconnectWithBackoff(url)
                            }
                        }
                    } else {
                        // Still degraded/down — the gate defers every reconnectWithBackoff,
                        // so nothing produces the successful connect that clears the latch.
                        // Drain 1-2 entries as DIRECT probes (connectAndAwait bypasses the
                        // defer). Prefer relays whose last failure was DNS — their success is
                        // the strongest proof the resolver recovered, and onOpen →
                        // clearTransportStrikes → healDnsDegraded clears the latch at once. (H20a)
                        val probes = pendingReconnect.toList()
                            .sortedByDescending { relayCapabilitiesStore.get(it)?.lastReason == SkipReason.DNS_RESOLUTION.name }
                            .take(2)
                        Log.w(TAG, "DNS-degraded — probing ${probes.size} deferred relay(s) to test recovery (${pendingReconnect.size} pending)")
                        for (url in probes) {
                            pendingReconnect.remove(url)
                            scope.launch {
                                delay(kotlin.random.Random.nextLong(RECONNECT_JITTER_WINDOW_MS))
                                val ready = connectAndAwait(listOf(url), timeoutMs = 3_000)
                                if (ready > 0) {
                                    Log.w(TAG, "DNS-degraded probe connected: $url — latch cleared via heal path")
                                } else {
                                    pendingReconnect.add(url)  // still unreachable — requeue for next sweep
                                }
                            }
                        }
                    }
                }

                // Heal integral relays: re-attempt any configured integral relay that
                // is disconnected and past its skip cooldown. A transient DNS blip
                // strikes indexers/read/write/search past the threshold; without this
                // they stay skipped until the 24h TTL on next cold start.
                for (url in integralRelayUrls) {
                    if (connections.containsKey(url)) continue
                    if (url in blockedUrls) continue
                    if (relayCapabilitiesStore.shouldSkip(url)) continue   // still in cooldown
                    scope.launch {
                        val ready = connectAndAwait(listOf(url), timeoutMs = 3_000)
                        if (ready > 0 && connections[url] != null) {
                            addPurpose(url, ConnectionPurpose.PERSISTENT)
                            _onRelayReconnected.tryEmit(url)
                            Log.w(TAG, "Healed integral relay: $url")
                        }
                    }
                }
            }
        }
    }

    private val _connectionStates = MutableStateFlow<Map<String, RelayState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, RelayState>> get() = _connectionStates.asStateFlow()

    /** Emits (token, eventId) pairs for events arriving on search-notes-* subscriptions. */
    private val _searchResults = MutableSharedFlow<SearchResult>(extraBufferCapacity = 256)
    val searchResults: SharedFlow<SearchResult> = _searchResults.asSharedFlow()

    /**
     * Extract the subscription ID from an EVENT message without JSON parsing.
     * Format: ["EVENT","subscription-id",{...}]
     */
    private fun extractEventSubId(raw: String): String? {
        val eventEnd = raw.indexOf("\"EVENT\"")
        if (eventEnd < 0) return null
        val quoteOpen = raw.indexOf('"', eventEnd + 7)
        if (quoteOpen < 0) return null
        val subStart = quoteOpen + 1
        val quoteClose = raw.indexOf('"', subStart)
        if (quoteClose < 0) return null
        return raw.substring(subStart, quoteClose)
    }

    /**
     * Extract created_at from a raw EVENT message without full JSON parsing.
     * Scans for `"created_at":` and reads the numeric value.
     */
    private fun extractCreatedAt(raw: String): Long? {
        val key = "\"created_at\":"
        val idx = raw.indexOf(key)
        if (idx < 0) return null
        val start = idx + key.length
        val end = raw.indexOfAny(charArrayOf(',', '}', ' '), start)
        if (end < 0) return null
        return raw.substring(start, end).trim().toLongOrNull()
    }

    /**
     * Extract the Nostr event ID from a raw EVENT message without JSON parsing.
     * Scans for the `"id":"` marker and grabs the next 64 hex chars.
     */
    /** Extract the "tags" array from a raw EVENT JSON as a JSON string.
     *  Scans for `"tags":` and grabs the JSON array. */
    private fun extractTagsFromRaw(raw: String): String? {
        val marker = "\"tags\":"
        val idx = raw.indexOf(marker)
        if (idx < 0) return null
        val start = raw.indexOf('[', idx + marker.length)
        if (start < 0) return null
        var depth = 0
        for (i in start until raw.length) {
            when (raw[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** True when a relay has been marked auth-unavailable this session. */
    override fun isAuthUnavailable(url: String): Boolean =
        normalizeRelayUrl(url)?.let { it in authUnavailableRelays } ?: false

    private fun updateConnectionStates() {
        _connectionStates.value = connections.mapValues { it.value.state.value }
    }

    /** Clear transient caches. Called on logout. */
    fun clearCaches() {
        profileFetchAttempted.clear()
        profileFallbackNegCache.clear()
    }

    /**
     * Connect to [relayUrls], start listening for events, and suspend until at least
     * one connection is ready OR [timeoutMs] elapses. Does NOT send any subscriptions —
     * the caller sends requests after this returns.
     */
    override suspend fun connectAndAwait(
        relayUrls: List<String>,
        timeoutMs: Long,
        forceEvict: Boolean,
    ): Int {
        val newConns = mutableListOf<RelayConnection>()
        for (rawUrl in relayUrls) {
            val url = normalizeRelayUrl(rawUrl) ?: continue
            if (url in blockedUrls) {
                Log.d(TAG, "Blocked relay — skipping $url")
                continue
            }
            if (relayCapabilitiesStore.shouldSkip(url)) {
                val caps = relayCapabilitiesStore.get(url)
                Log.w(TAG, "connectAndAwait GATE-SKIP: $url auth=${caps?.authRequired} restricted=${caps?.restricted} strikes=${caps?.strikes} reason='${caps?.lastReason}'")
                continue
            }
            // Read-decide-write: check existing entry state before creating new
            val existing = connections[url]
            if (existing != null) {
                val s = existing.state.value
                if (s == RelayState.CONNECTED || s == RelayState.CONNECTING) {
                    Log.w(TAG, "connectAndAwait REUSE: $url state=$s")
                    continue
                }
                // Stale (DISCONNECTED/FAILED) — evict and replace
                if (!canOpenNewConnection()) {
                    // Still counts as evict: we're replacing, not adding
                }
                val replacement = RelayConnection(url, okHttpClient, relayCapabilitiesStore)
                connections[url] = replacement  // map-before-close
                existing.close()
                connectionLastActivity[url] = System.currentTimeMillis()
                replacement.connect()
                scope.launch { listenForEvents(replacement) }
                newConns.add(replacement)
                Log.w(TAG, "connectAndAwait REPLACE: $url (was $s, pool=${connections.size})")
                continue
            }
            // No existing entry — create new (subject to pool cap)
            if (!canOpenNewConnection()) {
                Log.w(TAG, "connectAndAwait GATE-CAP: $url blocked by pool cap (${connections.size}/$POOL_SAFETY_CAP)")
                continue
            }
            val candidate = RelayConnection(url, okHttpClient, relayCapabilitiesStore)
            connections[url] = candidate
            connectionLastActivity[url] = System.currentTimeMillis()
            candidate.connect()
            scope.launch { listenForEvents(candidate) }
            newConns.add(candidate)
            Log.w(TAG, "connectAndAwait NEW: $url (pool=${connections.size})")
        }
        if (newConns.isEmpty()) {
            // All URLs already in pool — wait for at least one to be connected.
            // connect() (non-suspending) creates connection entries that may still
            // be handshaking. Return early only if at least one is ready; otherwise
            // poll just like we do for newly-created connections.
            val existingConns = relayUrls.mapNotNull { normalizeRelayUrl(it) }
                .filter { it !in blockedUrls }
                .mapNotNull { connections[it] }
            if (existingConns.isEmpty()) return 0
            val alreadyReady = existingConns.count { it.isConnected }
            if (alreadyReady > 0) return alreadyReady
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val ready = existingConns.count { it.isConnected }
                if (ready > 0) {
                    Log.d(TAG, "connectAndAwait: $ready/${existingConns.size} relay(s) ready (existing)")
                    return ready
                }
                delay(50)
            }
            val ready = existingConns.count { it.isConnected }
            Log.w(TAG, "connectAndAwait: timeout — $ready/${existingConns.size} relay(s) ready (existing)")
            return ready
        }
        // Poll until at least one connection is ready
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready = newConns.count { it.isConnected }
            if (ready > 0) {
                Log.d(TAG, "connectAndAwait: $ready/${newConns.size} relay(s) ready")
                return ready
            }
            delay(50)
        }
        val ready = newConns.count { it.isConnected }
        Log.w(TAG, "connectAndAwait: timeout — $ready/${newConns.size} relay(s) ready")
        return ready
    }

    // ── Ephemeral one-shot batch ────────────────────────────────────────

    /**
     * Send one-shot REQs to specified URLs with warm-pool reuse.
     *
     * For URLs already in [connections]: reuses via [sendOneShotToRelay].
     * For URLs NOT in pool: opens ephemeral WebSocket (no cap, no reconnect),
     * sends REQs, collects events until EOSE, then closes.
     *
     * Ephemeral connections never enter [connections] map and don't count against the cap.
     */
    suspend fun sendOneShotBatch(
        urls: List<String>,
        reqs: List<String>,
        subIds: List<String>,
        timeoutMs: Long = 8_000,
        capabilityBypassRelays: Set<String> = emptySet(),
    ) {
        val excluded = activeSingleRelayFeedUrl
        val bypassRelays = capabilityBypassRelays.mapNotNull { normalizeRelayUrl(it) }.toSet()
        val logBypass = bypassRelays.isNotEmpty()
        val normalized = urls.mapNotNull { normalizeRelayUrl(it) }.distinct()
            .filter { relayUrl ->
                relayUrl !in blockedUrls &&
                    relayUrl != excluded &&
                    (relayUrl in bypassRelays || !relayCapabilitiesStore.shouldSkip(relayUrl))
            }
        if (logBypass) {
            Log.i(TAG, "sendOneShotBatch: capability bypass relays=${bypassRelays.joinToString(",")}")
        }
        if (normalized.isEmpty() || reqs.isEmpty()) {
            if (excluded != null && urls.any { normalizeRelayUrl(it) == excluded }) {
                Log.d(TAG, "one-shot skipped: only feedRelay in target set")
            }
            return
        }

        val reused = mutableListOf<String>()
        val ephemeral = mutableListOf<String>()

        for (url in normalized) {
            if (connections.containsKey(url)) {
                reused.add(url)
            } else {
                ephemeral.add(url)
            }
        }

        if (logBypass) {
            Log.i(TAG, "sendOneShotBatch: ${normalized.size} urls → ${reused.size} reused, ${ephemeral.size} ephemeral")
        } else {
            Log.d(TAG, "sendOneShotBatch: ${normalized.size} urls → ${reused.size} reused, ${ephemeral.size} ephemeral")
        }

        // Pre-filter reused URLs to those present in the pool — must match the
        // send loop's `connections[url] ?: continue` gate so every relay that
        // receives REQs is counted in targetSet.
        val liveReused = reused.filter { connections[it] != null }

        // Resolve target set BEFORE sending: live reused + ephemeral
        val targetSet = (liveReused + ephemeral).toSet()

        // Register target set for EOSE coverage tracking. Requires the caller
        // to have registered oneShotEoseCallbacks[subId] beforehand — both
        // engagement callers (dispatchOwnEngagement, dispatchEngagement) do.
        // Skipping subIds without a callback avoids leaking map entries for
        // internal callers (fetchProfiles, fetchOlderPosts, etc.).
        if (targetSet.isNotEmpty()) {
            for (subId in subIds) {
                if (oneShotEoseCallbacks.containsKey(subId)) {
                    oneShotSubTargets[subId] = targetSet
                }
            }
        }

        // Pool-reused path: wait for mid-handshake connections, then send via existing infra
        for (url in reused) {
            val conn = connections[url] ?: continue
            if (!conn.isConnected) {
                // Mid-handshake — wait up to 1s for ready, skip if it fails
                val state = withTimeoutOrNull(1_000) {
                    conn.state.first {
                        it == RelayState.CONNECTED || it == RelayState.FAILED || it == RelayState.DISCONNECTED
                    }
                }
                if (state != RelayState.CONNECTED) continue
            }
            subIds.forEach { _activeOneShotSubs.add(it) }
            reqs.forEach { sendOneShotToRelay(conn, it) }
        }

        // Ephemeral path: open temporary connections (parallel, bounded by timeout)
        if (ephemeral.isEmpty()) return

        coroutineScope {
            ephemeral.map { url ->
                async { openEphemeral(url, reqs, subIds.toSet(), timeoutMs, logInfo = logBypass) }
            }.awaitAll()
        }
    }

    /**
     * Open an ephemeral WebSocket, send REQs, collect events until all sub-IDs
     * receive EOSE or timeout, then close. Never touches [connections],
     * [connectionPurposes], or reconnect logic.
     */
    private suspend fun openEphemeral(
        url: String,
        reqs: List<String>,
        subIds: Set<String>,
        timeoutMs: Long,
        logInfo: Boolean = false,
    ) {
        // Rate limit: min 50ms gap per URL
        val lastOpen = ephemeralLastOpenNanos.computeIfAbsent(url) { AtomicLong(0) }
        val now = System.nanoTime()
        val prev = lastOpen.get()
        if (now - prev < MIN_EPHEMERAL_GAP_NS) {
            Log.d(TAG, "Ephemeral rate-limited: $url")
            return
        }
        if (!lastOpen.compareAndSet(prev, now)) return // CAS race — another caller won

        val conn = RelayConnection(url, okHttpClient, relayCapabilitiesStore)
        try {
            conn.connect()
            // Wait for WebSocket ready (max 2s)
            val state = withTimeoutOrNull(2_000) {
                conn.state.first {
                    it == RelayState.CONNECTED || it == RelayState.FAILED || it == RelayState.DISCONNECTED
                }
            }
            if (state != RelayState.CONNECTED) {
                if (logInfo) Log.i(TAG, "Ephemeral connect failed: $url (state=$state)")
                else Log.d(TAG, "Ephemeral connect failed: $url (state=$state)")
                return
            }

            // Send all REQs
            reqs.forEach { conn.send(it) }

            // Collect events until all sub-IDs EOSE'd or timeout
            val pendingSubs = subIds.toMutableSet()
            withTimeoutOrNull(timeoutMs) {
                conn.messages.consumeEach { raw ->
                    when {
                        raw.startsWith("[\"EVENT\"") -> {
                            processor.process(raw, url)
                        }
                        raw.startsWith("[\"EOSE\"") -> {
                            val eoseSubId = extractEoseSubId(raw)
                            if (eoseSubId != null && eoseSubId in pendingSubs) {
                                conn.send("""["CLOSE","$eoseSubId"]""")
                                recordOneShotRelayCoverage(eoseSubId, url)
                                pendingSubs.remove(eoseSubId)
                                if (pendingSubs.isEmpty()) return@withTimeoutOrNull
                            }
                        }
                        raw.startsWith("[\"AUTH\"") -> {
                            val challenge = raw.substringAfter("[\"AUTH\",\"", "")
                                .substringBefore("\"")
                            if (challenge.isNotEmpty()) {
                                handleAuthChallenge(conn, challenge)
                            }
                        }
                        raw.startsWith("[\"OK\"") -> {
                            handleOk(conn, raw)
                        }
                    }
                }
            }
            val eosed = subIds.size - pendingSubs.size
            if (logInfo) Log.i(TAG, "Ephemeral complete: $url ($eosed/${subIds.size} subs EOSE'd)")
            else Log.d(TAG, "Ephemeral complete: $url ($eosed/${subIds.size} subs EOSE'd)")
        } finally {
            conn.close()
        }
    }

    /**
     * Pre-load blocked relay URLs into the in-memory snapshot.
     * Must be called during bootstrap BEFORE any connect() calls.
     */
    fun refreshBlockedRelays() {
        val ownPubkey = keyManager.getPublicKeyHex() ?: return
        blockedUrls = memoryEventStore.get().getBlockedRelayUrls(ownPubkey).toSet()
        Log.d(TAG, "Blocked relay snapshot loaded: ${blockedUrls.size} URL(s)")
    }

    /**
     * Update the blocked relay snapshot and disconnect any currently-connected
     * blocked relays with a proper WebSocket close handshake.
     */
    fun onBlockedRelaysChanged(newBlockedUrls: Set<String>) {
        blockedUrls = newBlockedUrls
        for (url in newBlockedUrls) {
            connections.remove(url)?.let { conn ->
                conn.close()
                Log.d(TAG, "Disconnected newly-blocked relay: $url")
            }
        }
    }

    fun connect(relayUrls: List<String>) {
        val normalizedUrls = relayUrls.mapNotNull { normalizeRelayUrl(it) }
        for (url in normalizedUrls) {
            if (url in blockedUrls) {
                Log.d(TAG, "Blocked relay — skipping $url")
                continue
            }
            if (relayCapabilitiesStore.shouldSkip(url)) {
                val caps = relayCapabilitiesStore.get(url)
                Log.w(TAG, "connect GATE-SKIP: $url auth=${caps?.authRequired} restricted=${caps?.restricted} strikes=${caps?.strikes} reason='${caps?.lastReason}'")
                continue
            }
            // Read-decide-write: check existing entry state before creating new
            val existing = connections[url]
            if (existing != null) {
                val s = existing.state.value
                if (s == RelayState.CONNECTED || s == RelayState.CONNECTING) {
                    Log.w(TAG, "connect REUSE: $url state=$s")
                    continue
                }
                // Stale (DISCONNECTED/FAILED) — evict and replace
                val replacement = RelayConnection(url, okHttpClient, relayCapabilitiesStore)
                connections[url] = replacement  // map-before-close
                existing.close()
                connectionLastActivity[url] = System.currentTimeMillis()
                scope.launch {
                    replacement.connect()
                    listenForEvents(replacement)
                }
                Log.w(TAG, "connect REPLACE: $url (was $s, pool=${connections.size})")
                continue
            }
            // No existing entry — create new (subject to pool cap)
            if (!canOpenNewConnection()) {
                Log.w(TAG, "connect GATE-CAP: $url blocked by pool cap (${connections.size}/$POOL_SAFETY_CAP)")
                continue
            }
            val candidate = RelayConnection(url, okHttpClient, relayCapabilitiesStore)
            connections[url] = candidate
            connectionLastActivity[url] = System.currentTimeMillis()
            scope.launch {
                candidate.connect()
                listenForEvents(candidate)
            }
        }
        Log.w(TAG, "Pool has ${connections.size} connections")
    }

    private suspend fun listenForEvents(conn: RelayConnection) {
        try { try {
            conn.messages.consumeEach { raw ->
                connectionLastActivity[conn.url] = System.currentTimeMillis()
                // Fire taps for ALL message types (EVENT/EOSE/CLOSED).
                // Subscription taps demux by subId and need EOSE/CLOSED delivery.
                // For non-EVENT, EventProcessor fires taps then returns early.
                // For EVENT, it also handles dedup + MES routing.
                processor.process(raw, conn.url)
                if (raw.startsWith("[\"EOSE\"")) {
                    val eoseSubId = extractEoseSubId(raw)
                    if (eoseSubId != null && eoseSubId.startsWith("search-")) {
                        Log.d(TAG, "Search EOSE: subId=$eoseSubId relay=${conn.url}")
                    }
                    handleEose(conn, raw)
                    return@consumeEach
                }
                // NIP-45 COUNT response: ["COUNT","sub-id",{"count":N}]
                if (raw.startsWith("[\"COUNT\"")) {
                    handleCount(raw)
                    return@consumeEach
                }
                // Relay NOTICE — log for diagnostics
                if (raw.startsWith("[\"NOTICE\"")) {
                    val notice = raw.substringAfter("\"NOTICE\",\"", "").substringBefore("\"")
                    Log.w(TAG, "Relay NOTICE ${conn.url}: $notice")
                    return@consumeEach
                }
                // NIP-42 OK response — ["OK", "<event-id>", <success>, "<message>"]
                if (raw.startsWith("[\"OK\"")) {
                    handleOk(conn, raw)
                    return@consumeEach
                }
                // NIP-42 AUTH challenge — sign and respond automatically
                if (raw.startsWith("[\"AUTH\"")) {
                    val challenge = raw.substringAfter("[\"AUTH\",\"", "").substringBefore("\"")
                    if (challenge.isNotEmpty()) {
                        Log.w(TAG, "AUTH challenge from ${conn.url}: ${challenge.take(20)}…")
                        handleAuthChallenge(conn, challenge)
                    }
                    return@consumeEach
                }
                // NIP-42 CLOSED with auth-required — authenticate then replay the sub
                if (raw.startsWith("[\"CLOSED\"")) {
                    try {
                        val arr = NostrJson.parseToJsonElement(raw).jsonArray
                        val closedSubId = arr[1].jsonPrimitive.content
                        val reason = arr.getOrNull(2)?.jsonPrimitive?.content ?: ""
                        val category = when {
                            isOneShotSubscription(closedSubId) -> "one-shot"
                            closedSubId.startsWith("browse-") -> "browse"
                            else -> "live"
                        }
                        if (reason.startsWith("auth-required")) {
                            Log.w(TAG, "CLOSED auth-required $category sub '$closedSubId' on ${conn.url}: $reason " +
                                "(hasPendingChallenge=${pendingChallenges.containsKey(conn.url)} " +
                                "authenticated=${conn.url in authenticatedRelays} " +
                                "authInFlight=${conn.url in authInFlight} " +
                                "streak=${authRejectionStreak[conn.url] ?: 0} " +
                                "unavailable=${conn.url in authUnavailableRelays})")
                            when {
                                conn.url in authUnavailableRelays -> {
                                    Log.d(TAG, "auth-required on unavailable relay ${conn.url} for '$closedSubId' — ignoring")
                                }
                                conn.url in authInFlight -> {
                                    // CLOSED because our REQ raced the in-flight auth handshake — NOT a rejection.
                                    // completeAuth() will replay this sub on OK; don't pollute the streak.
                                    Log.d(TAG, "auth-required on ${conn.url} while auth in flight — awaiting OK, not penalizing")
                                }
                                else -> {
                                    val streak = authRejectionStreak.merge(conn.url, 1, Int::plus) ?: 1
                                    authenticatedRelays.remove(conn.url)
                                    optimisticAuthUsed.remove(conn.url)
                                    if (streak >= MAX_AUTH_REJECTIONS) {
                                        authUnavailableRelays.add(conn.url)
                                        authInFlight.remove(conn.url)
                                        pendingAuthEventIds.values.removeAll { it == conn.url }
                                        _relayAuthUnavailable.tryEmit(conn.url)
                                        Log.w(TAG, "AUTH: ${conn.url} rejected $streak times — marking unavailable")
                                    } else {
                                        val challenge = pendingChallenges[conn.url]
                                        if (challenge != null) {
                                            Log.w(TAG, "AUTH: rejected on ${conn.url} (streak=$streak) — re-authenticating")
                                            handleAuthChallenge(conn, challenge)
                                        } else {
                                            Log.w(TAG, "AUTH: rejected on ${conn.url} (streak=$streak) but no challenge cached")
                                        }
                                    }
                                }
                            }
                        } else if (reason.contains("rate-limit", ignoreCase = true) ||
                               reason.contains("too many", ignoreCase = true)) {
                            markRelayRateLimited(conn.url)
                            Log.w(TAG, "CLOSED rate-limited $category sub '$closedSubId' on ${conn.url}: $reason")
                        } else {
                            Log.w(TAG, "CLOSED $category sub '$closedSubId' on ${conn.url}: reason='$reason'")
                        }
                        // Mechanism S: relay closed sub without dropping WS. For live subs
                        // (still active in Subscription), emit reconnect signal so
                        // Subscription.resumeRelay re-issues the REQ. Skip one-shot and
                        // auth-required (already handled above).
                        if (!isOneShotSubscription(closedSubId) &&
                            !reason.startsWith("auth-required")) {
                            val activeIds = runCatching { activeSubsSource.get().activeSubIds() }
                                .getOrDefault(emptySet())
                            if (closedSubId in activeIds) {
                                _onRelayReconnected.tryEmit(conn.url)
                                Log.w(TAG, "RESUB: live sub '$closedSubId' closed on ${conn.url}, notified resub")
                            }
                        }
                        // A CLOSED relay won't send EOSE — release its slot and
                        // count it as done for coverage so it doesn't force a full timeout.
                        if (isOneShotSubscription(closedSubId)) {
                            releaseOneShotForRelay(closedSubId, conn.url)
                            recordOneShotRelayCoverage(closedSubId, conn.url)
                        }
                        // Layer 2: learn from structural rejections for future REQs.
                        if (reason.isNotEmpty()) {
                            scope.launch { relayCapabilitiesStore.learnFromClosed(conn.url, reason) }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse CLOSED message: ${e.message}")
                    }
                    return@consumeEach
                }
                val subId = extractEventSubId(raw)
                if (subId != null) {
                    // One-shot event-tags callback (e.g. following count)
                    eventTagsCallbacks[subId]?.let { deferred ->
                        val tagsJson = extractTagsFromRaw(raw)
                        deferred.complete(tagsJson)
                        eventTagsCallbacks.remove(subId)
                    }
                    // Emit (token, eventId) for search-notes subscriptions so SearchViewModel
                    // can correlate relay results with the correct query session.
                    if (subId.startsWith("search-notes-")) {
                        val token = subId.removePrefix("search-notes-").toLongOrNull()
                        if (token != null) {
                            val eventId = extractEventIdFromRaw(raw)
                            if (eventId != null) {
                                Log.d(TAG, "Search EVENT received: subId=$subId relay=${conn.url} eventId=${eventId.take(12)}…")
                                _searchResults.tryEmit(SearchResult(token, eventId))
                            }
                        }
                    }
                    // Also log search-profiles events
                    if (subId.startsWith("search-profiles-")) {
                        Log.d(TAG, "Search EVENT received: subId=$subId relay=${conn.url}")
                    }
                    // Paginated fetch tracking: count events and track oldest created_at
                    _activePages[subId]?.let { page ->
                        page.eventCount.incrementAndGet()
                        val ts = extractCreatedAt(raw)
                        if (ts != null) {
                            page.oldestCreatedAt.updateAndGet { minOf(it, ts) }
                        }
                    }
                }
                // NOTE: processor.process already called at top of consumeEach
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Stream closed for ${conn.url}: ${e.message}")
        }
        } finally {
            // Skip the reconnect decision if we're being cancelled (scope teardown).
            // A `return` here would swallow the propagating CancellationException, so
            // gate with `if (isActive)` and use NO `return` statements in this block.
            if (currentCoroutineContext().isActive) {
                val url = conn.url
                val current = connections[url]
                if (current !== conn) {
                    // Map already points elsewhere (replaced by reconnect/sweep) — don't race.
                    Log.w(TAG, "listenForEvents exit: $url replaced, no reconnect")
                } else {
                    val purposes = connectionPurposes[url]
                    val activeSubUrls = runCatching { activeSubsSource.get().activeRelayUrls() }
                        .getOrDefault(emptySet())
                    val stillNeeded = !purposes.isNullOrEmpty() || url in activeSubUrls
                    val notSkipped = !relayCapabilitiesStore.shouldSkip(url)

                    Log.w(TAG, "listenForEvents exit: $url state=${conn.state.value} " +
                        "purposes=$purposes inActiveSubs=${url in activeSubUrls} " +
                        "shouldSkip=${!notSkipped} → reconnect=${stillNeeded && notSkipped}")

                    if (stillNeeded && notSkipped) {
                        reconnectWithBackoff(url)
                    } else {
                        // No purpose, not in active subs — clean up the dead entry
                        connections.remove(url, conn)
                    }
                }
            }
        }
    }

    /**
     * Idempotent slot release for a single (subId, url) pair.
     *
     * Both [handleEose] and [cleanupOneShotSub] funnel through here. The
     * [oneShotReleased] guard ensures that even if both paths fire for the
     * same relay URL, the slot is decremented exactly once.
     *
     * Sends CLOSE frame, decrements [relayOneShotCount], and flushes the
     * per-relay queue so queued REQs can drain.
     */
    private fun releaseOneShotForRelay(subId: String, url: String) {
        val released = oneShotReleased.computeIfAbsent(subId) { ConcurrentHashMap.newKeySet() }
        if (!released.add(url)) return // already released — idempotent guard

        connections[url]?.let { conn ->
            conn.send("""["CLOSE","$subId"]""")
            relayOneShotCount[url]?.let { count ->
                val prev = count.getAndUpdate { if (it > 0) it - 1 else 0 }
                if (prev > 0) flushRelayQueue(conn)
            }
        }
    }

    /**
     * Fix 3: Subscription lifecycle — CLOSE after EOSE for one-shot subscriptions.
     *
     * One-shot subs (profiles, threads, search, notifications, kind 3/10002) are
     * identified by their subscription ID prefix. Once EOSE arrives the relay has
     * finished its historical query; we close immediately to free relay resources
     * and stop the stream of duplicate events from that relay for that query.
     *
     * Persistent subs (feed-, follows-) stay open to receive live events.
     */
    private fun handleEose(conn: RelayConnection, raw: String) {
        val subId = extractEoseSubId(raw) ?: return
        // Paginated fetch: signal EOSE without sending CLOSE (pagination loop decides)
        _activePages[subId]?.let { page ->
            page.eoseReceived.complete(Unit)
            return
        }
        if (isOneShotSubscription(subId)) {
            _activeOneShotSubs.remove(subId)
            // Single release path — idempotent, sends CLOSE + decrements slot + flushes queue
            releaseOneShotForRelay(subId, conn.url)
            // Record this relay as done; complete deferred when all targets covered
            recordOneShotRelayCoverage(subId, conn.url)
            Log.d(TAG, "CLOSE sent for one-shot sub '$subId' on ${conn.url}")
        }
    }

    /** Remove a one-shot sub from all tracking maps, release slots for every
     *  target relay (idempotent — skips already-released), and clean up. */
    internal fun cleanupOneShotSub(subId: String) {
        oneShotEoseCallbacks.remove(subId)
        oneShotFirstEose.remove(subId)
        val targets = oneShotSubTargets.remove(subId) ?: emptySet()
        oneShotSubEosed.remove(subId)

        // Release slot for every target relay. releaseOneShotForRelay is idempotent —
        // relays that already EOSE'd (and were released in handleEose) are skipped
        // via the oneShotReleased guard. Only un-released relays get CLOSE + decrement.
        for (url in targets) {
            releaseOneShotForRelay(subId, url)
        }

        // Final cleanup of the released tracking set
        oneShotReleased.remove(subId)
    }

    /**
     * Record a relay as done for a one-shot sub. Completes [oneShotFirstEose]
     * on the first relay response and [oneShotEoseCallbacks] when ALL target
     * relays have responded (EOSE or CLOSED).
     * Falls back to first-EOSE if no target set was registered.
     */
    private fun recordOneShotRelayCoverage(subId: String, relayUrl: String) {
        val targets = oneShotSubTargets[subId]
        if (targets == null) {
            // No target set registered — fall back to old behavior (complete on first)
            oneShotEoseCallbacks.remove(subId)?.complete(Unit)
            oneShotFirstEose.remove(subId)?.complete(Unit)
            return
        }
        val eosed = oneShotSubEosed.computeIfAbsent(subId) { ConcurrentHashMap.newKeySet() }
        eosed.add(relayUrl)
        val covered = eosed.size
        val total = targets.size
        Log.d(TAG, "one-shot '$subId' coverage $covered/$total")

        // First relay response: complete the first-EOSE deferred immediately.
        // Engagement subs await this instead of full coverage.
        if (covered == 1) {
            oneShotFirstEose.remove(subId)?.complete(Unit)
        }

        // Full coverage: complete the main deferred and clean up tracking maps.
        // oneShotReleased is NOT removed here — late duplicate EOSEs from flaky relays
        // would recreate the set and double-decrement. cleanupOneShotSub does the bulk wipe.
        if (covered >= total) {
            oneShotEoseCallbacks.remove(subId)?.complete(Unit)
            oneShotSubTargets.remove(subId)
            oneShotSubEosed.remove(subId)
        }
    }

    /**
     * Handle a NIP-45 COUNT response: ["COUNT","sub-id",{"count":N}]
     */
    private fun handleCount(raw: String) {
        try {
            val arr = NostrJson.parseToJsonElement(raw).jsonArray
            val subId = arr[1].jsonPrimitive.content
            val countObj = arr[2].jsonObject
            val count = countObj["count"]?.jsonPrimitive?.long
            countCallbacks.remove(subId)?.complete(count)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse COUNT response: ${e.message}")
        }
    }

    /**
     * NIP-45 COUNT query: send a COUNT request to a single relay and wait for the response.
     * Returns the count, or null if the relay doesn't support NIP-45 or times out.
     */
    suspend fun sendCount(
        relayUrl: String,
        filter: JsonObject,
        timeoutMs: Long = 10_000L,
    ): Long? =
        withContext(Dispatchers.IO) {
            val subId = "count-${System.nanoTime()}"
            try {
                val countRequest = buildJsonArray {
                    add(JsonPrimitive("COUNT"))
                    add(JsonPrimitive(subId))
                    add(filter)
                }.toString()

                val conn = connections[relayUrl] ?: return@withContext null

                val deferred = CompletableDeferred<Long?>()
                countCallbacks[subId] = deferred

                conn.send(countRequest)

                withTimeoutOrNull(timeoutMs) { deferred.await() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                countCallbacks.remove(subId)
            }
        }

    /**
     * Fetch the following count for a pubkey by retrieving their kind-3 event
     * and counting p-tags. Returns null on timeout or failure.
     */
    suspend fun fetchFollowingCount(pubkeyHex: String): Long? =
        withContext(Dispatchers.IO) {
            try {
                val subId = "following-count-${System.nanoTime()}"
                val req = buildJsonArray {
                    add(JsonPrimitive("REQ"))
                    add(JsonPrimitive(subId))
                    add(buildJsonObject {
                        put("kinds", buildJsonArray { add(JsonPrimitive(3)) })
                        put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                        put("limit", JsonPrimitive(1))
                    })
                }.toString()

                val deferred = CompletableDeferred<String?>()
                eventTagsCallbacks[subId] = deferred

                // Ensure indexer relays are connected before sending the kind-3 REQ —
                // they may have been evicted by idle timer or not yet connected on navigation.
                val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
                connectAndAwait(indexerUrls, timeoutMs = 3_000, forceEvict = true)
                val targets = indexerUrls.mapNotNull { connections[it] }
                    .ifEmpty { connections.values.take(3).toList() }
                targets.forEach { it.send(req) }

                // Raw send bypasses the one-shot EOSE auto-close path — without an
                // explicit CLOSE the relay streams future kind-3 updates until socket drop.
                val close = """["CLOSE","$subId"]"""

                val tagsJson = withTimeoutOrNull(10_000) { deferred.await() }
                    ?: run {
                        eventTagsCallbacks.remove(subId)
                        targets.forEach { conn -> runCatching { conn.send(close) } }
                        return@withContext null
                    }
                eventTagsCallbacks.remove(subId)
                targets.forEach { conn -> runCatching { conn.send(close) } }

                // Count p-tags in the tags array
                runCatching {
                    NostrJson.parseToJsonElement(tagsJson).jsonArray
                        .count { tag ->
                            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "p"
                        }.toLong()
                }.getOrNull()
            } catch (_: Exception) { null }
        }

    /**
     * Extract the subscription ID from an EOSE message without JSON parsing.
     * Format: ["EOSE","subscription-id"] (compact) or ["EOSE", "subscription-id"] (spaced).
     */
    private fun extractEoseSubId(raw: String): String? {
        // Find the "EOSE" token, then locate the next quoted string — that's the sub-id.
        val eoseEnd = raw.indexOf("\"EOSE\"")
        if (eoseEnd < 0) return null
        // Skip past "EOSE", then find the opening quote of the sub-id
        val quoteOpen = raw.indexOf('"', eoseEnd + 6)
        if (quoteOpen < 0) return null
        val subStart = quoteOpen + 1
        val quoteClose = raw.indexOf('"', subStart)
        if (quoteClose < 0) return null
        return raw.substring(subStart, quoteClose)
    }

    /**
     * Subscription IDs are prefixed to encode their lifecycle type.
     *
     *  ONE_SHOT  (close after EOSE): kind3-, kind10002-, profiles-, hint-profiles-,
     *                                src-profiles-, search-, older-, relay-ecosystem-,
     *                                thread-event-, thread-replies-, thread-reactions-,
     *                                thread-zaps-, user-posts-, user-longform-,
     *                                user-engagement-, hint-event-, trust-scores-,
     *                                engagement-
     *  PERSISTENT (keep open):       feed-, follows-, notifs-
     */
    private fun isOneShotSubscription(subId: String): Boolean =
        SubscriptionRules.isOneShotSubscription(subId)

    /**
     * Send a one-time REQ for the user's kind 3 (follow list) to indexer relays.
     * The response flows through EventProcessor → MemoryEventStore direct-path insert.
     */
    fun fetchFollowList(pubkeyHex: String) {
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("kind3-${System.nanoTime()}"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(3)) })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                put("limit", JsonPrimitive(1))
            })
        }.toString()
        // Kind 3 is replaceable — indexers suffice; broadcasting to every
        // connection just duplicates the same latest event.
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
        val targets = indexerUrls.mapNotNull { connections[it] }
            .ifEmpty { connections.values.take(3).toList() }
        targets.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "Fetching kind 3 for $pubkeyHex from ${targets.size} relay(s)")
    }

    /**
     * Send a one-time REQ for kind 10002 (relay list metadata) for [pubkeys].
     * Results flow through EventProcessor → MemoryEventStore direct-path insert.
     */
    fun fetchRelayLists(pubkeys: List<String>) {
        if (pubkeys.isEmpty()) return
        // Send to indexer relays only (not all connections) to avoid broadcast storm
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
        val indexerConns = indexerUrls.mapNotNull { connections[it] }
        val targets = indexerConns.ifEmpty { connections.values.take(3) }
        // Chunk to keep individual filters under 500 authors
        pubkeys.chunked(500).forEach { chunk ->
            val subId = "kind10002-${System.nanoTime()}"
            _activeOneShotSubs.add(subId)
            val req = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(subId))
                add(buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(10002)) })
                    put("authors", buildJsonArray { chunk.forEach { add(JsonPrimitive(it)) } })
                })
            }.toString()
            targets.forEach { sendOneShotToRelay(it, req) }
        }
        Log.d(TAG, "Fetching kind 10002 for ${pubkeys.size} pubkey(s) from ${targets.size} relay(s)")
    }

    /**
     * One-shot fetch for NIP-51 relay ecosystem kinds: 10006, 10007, 10012, 30002.
     * Sent to the specified indexer relays. These are replaceable/parameterized
     * replaceable events, so we only need the latest for the logged-in user.
     */
    fun fetchRelayEcosystem(pubkeyHex: String, rawIndexerRelayUrls: List<String>) {
        val indexerRelayUrls = rawIndexerRelayUrls.mapNotNull { normalizeRelayUrl(it) }.toSet()
        val subId = "relay-ecosystem-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(10006))
                    add(JsonPrimitive(10007))
                    add(JsonPrimitive(10012))
                    add(JsonPrimitive(30002))
                })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                put("limit", JsonPrimitive(50))
            })
        }.toString()
        // Send to indexers + user's connected write relays. NIP-51 replaceable events
        // are published to write relays (by Jumble, Keychat, etc.), not indexers.
        // Indexers (purplepag.es etc.) focus on kind 0/3/10002 and may not store
        // kinds 10006/10007/10012/30002.
        val writeRelayUrls = memoryEventStore.get().writeRelaysFor(pubkeyHex)
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { it !in indexerRelayUrls && connections.containsKey(it) }
        val allTargets = indexerRelayUrls + writeRelayUrls
        for (url in allTargets) {
            connections[url]?.let { sendOneShotToRelay(it, req) }
        }
        Log.w(TAG, "NIP-51 ecosystem fetch: indexers=${indexerRelayUrls.size} writeRelays=${writeRelayUrls.size} writeList=$writeRelayUrls (ownWrite=${memoryEventStore.get().writeRelaysFor(pubkeyHex).size})")
    }

    /**
     * One-shot fetch for NIP-51 mute list (kind 10000).
     * Sent to indexer + connected write relays, same pattern as fetchRelayEcosystem.
     */
    fun fetchMuteList(pubkeyHex: String, rawIndexerRelayUrls: List<String>) {
        val indexerRelayUrls = rawIndexerRelayUrls.mapNotNull { normalizeRelayUrl(it) }.toSet()
        val subId = "mute-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(10000)) })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                put("limit", JsonPrimitive(1))
            })
        }.toString()
        val writeRelayUrls = memoryEventStore.get().writeRelaysFor(pubkeyHex)
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { it !in indexerRelayUrls && connections.containsKey(it) }
        val allTargets = indexerRelayUrls + writeRelayUrls
        for (url in allTargets) {
            connections[url]?.let { sendOneShotToRelay(it, req) }
        }
        Log.d(TAG, "Fetching NIP-51 mute list for ${pubkeyHex.take(8)}… from ${indexerRelayUrls.size} indexers + ${writeRelayUrls.size} write relays")
    }

    /**
     * One-shot fetch for NIP-30 user emoji list (kind 10030).
     * Sent to indexer + connected write relays, same pattern as fetchMuteList.
     */
    fun fetchUserEmojiList(pubkeyHex: String, rawIndexerRelayUrls: List<String>) {
        val indexerRelayUrls = rawIndexerRelayUrls.mapNotNull { normalizeRelayUrl(it) }.toSet()
        val subId = "emoji-list-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(10030)) })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                put("limit", JsonPrimitive(1))
            })
        }.toString()
        val writeRelayUrls = memoryEventStore.get().writeRelaysFor(pubkeyHex)
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { it !in indexerRelayUrls && connections.containsKey(it) }
        val allTargets = indexerRelayUrls + writeRelayUrls
        for (url in allTargets) {
            connections[url]?.let { sendOneShotToRelay(it, req) }
        }
        Log.d(TAG, "Fetching NIP-30 emoji list (kind 10030) for ${pubkeyHex.take(8)}… from ${allTargets.size} relay(s)")
    }

    /**
     * One-shot batch fetch for NIP-30 emoji sets (kind 30030).
     * Groups refs by author, sends one REQ per author to their write relays + indexers.
     * Uses connectAndAwait for hint relays that aren't already connected.
     */
    suspend fun fetchEmojiSets(
        refs: List<com.unsilence.app.data.memory.EmojiSetRef>,
        skipHintRelays: Boolean = false,
    ) {
        if (refs.isEmpty()) return
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
        // Group by author so we can batch d-tags per author
        val byAuthor = refs.groupBy { it.authorPubkey }
        for ((author, authorRefs) in byAuthor) {
            val dTags = authorRefs.map { it.setName }
            // Collect hint relays from the refs + author's write relays + indexers
            val hintUrls = if (skipHintRelays) emptyList()
                else authorRefs.mapNotNull { it.hintRelay }
                    .mapNotNull { normalizeRelayUrl(it) }
                    .filter { it !in blockedUrls }
            val writeUrls = memoryEventStore.get().writeRelaysFor(author)
                .mapNotNull { normalizeRelayUrl(it) }
                .filter { it !in blockedUrls }
            val allTargets = (indexerUrls + writeUrls + hintUrls).distinct()
            if (allTargets.isEmpty()) continue

            // Connect to hint relays not yet connected
            val unconnected = allTargets.filter { !connections.containsKey(it) }
            if (unconnected.isNotEmpty()) {
                connectAndAwait(unconnected, timeoutMs = 3_000)
            }

            val subId = "emoji-set-${System.nanoTime()}"
            _activeOneShotSubs.add(subId)
            val req = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(subId))
                add(buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(30030)) })
                    put("authors", buildJsonArray { add(JsonPrimitive(author)) })
                    put("#d", buildJsonArray { dTags.forEach { add(JsonPrimitive(it)) } })
                    put("limit", JsonPrimitive(dTags.size))
                })
            }.toString()

            var sent = 0
            for (url in allTargets) {
                connections[url]?.let { conn ->
                    sendOneShotToRelay(conn, req)
                    sent++
                }
            }
            Log.d(TAG, "Fetching NIP-30 emoji sets: ${dTags.size} set(s) for ${author.take(8)}… from $sent relay(s)")
        }
    }

    /**
     * One-shot fetch for kind-30030 emoji sets.
     * Used by the discover surface on the Custom Emoji settings screen.
     * Sends to all connected relays — indexers specialize in kinds 0/3/10002
     * and don't carry kind-30030 broadly; emoji sets live on general relays.
     */
    fun fetchDiscoverEmojiSets(authorPubkeys: List<String> = emptyList()) {
        val targetUrls = connections.keys.toList()
        if (targetUrls.isEmpty()) return

        val subId = "emoji-discover-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(30030)) })
                if (authorPubkeys.isNotEmpty()) {
                    put("authors", buildJsonArray { authorPubkeys.forEach { add(JsonPrimitive(it)) } })
                }
                put("limit", JsonPrimitive(100))
            })
        }.toString()

        var sent = 0
        for (url in targetUrls) {
            connections[url]?.let { conn ->
                sendOneShotToRelay(conn, req)
                sent++
            }
        }
        Log.d(TAG, "Fetching discover emoji sets " +
            "(authors=${authorPubkeys.size.takeIf { it > 0 } ?: "any"}) " +
            "via $sent relay(s)")
    }

    /**
     * Open a persistent subscription for own kind-10000 on the user's write relays.
     * No limit, no closeOnEose — this is a live tail for the app session.
     * When another client (Amethyst, etc.) publishes an updated mute list, the
     * relay pushes it to us in real time via this subscription.
     */
    fun subscribeOwnMuteList(pubkeyHex: String) {
        val writeRelayUrls = memoryEventStore.get().writeRelaysFor(pubkeyHex)
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { connections.containsKey(it) }
        if (writeRelayUrls.isEmpty()) return
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("own-mute-live"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(10000)) })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
            })
        }.toString()
        liveMuteSubReq = req
        liveMuteSubRelays.clear()
        liveMuteSubRelays.addAll(writeRelayUrls)
        for (url in writeRelayUrls) {
            connections[url]?.send(req)
        }
    }

    /** Close the persistent own-mute-list subscription (teardown). */
    fun closeLiveMuteSub() {
        val urls = liveMuteSubRelays.toList()
        liveMuteSubReq = null
        liveMuteSubRelays.clear()
        if (urls.isNotEmpty()) {
            val close = """["CLOSE","own-mute-live"]"""
            for (url in urls) {
                connections[url]?.send(close)
            }
        }
    }

    /**
     * Paginated historical notification backfill. Fetches notification-eligible
     * events addressed to [pubkeyHex] via #p tag, plus kind-1018 responses
     * e-tagging one of the user's authored polls, from ALL connected relays,
     * paginating as deep as each relay allows. Events flow through
     * EventProcessor → MES → snapshot automatically.
     *
     * Strategy: write relays first (highest yield), then remaining connected
     * non-indexer relays in batches of 6 (concurrent within batch, sequential
     * between batches). The #p filter is highly selective — relays without
     * matching events return EOSE immediately, so the cost of asking is low.
     */
    suspend fun fetchHistoricalNotifications(pubkeyHex: String) {
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
            .mapNotNull { normalizeRelayUrl(it) }.toSet()

        // Write relays first — highest probability of carrying our notifications.
        val writeUrls = memoryEventStore.get().writeRelaysFor(pubkeyHex)
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { connections.containsKey(it) }

        // Remaining connected relays (exclude indexers + already-queried write relays).
        val otherUrls = connectedRelayUrls()
            .filter { it !in indexerUrls && it !in writeUrls && connections[it]?.isConnected == true }

        val allTargets = writeUrls + otherUrls
        if (allTargets.isEmpty()) return

        val filter = buildJsonObject {
            put("kinds", buildJsonArray {
                add(JsonPrimitive(1))    // replies
                add(JsonPrimitive(6))    // reposts
                add(JsonPrimitive(7))    // reactions
                add(JsonPrimitive(9735)) // zap receipts
            })
            put("#p", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
            put("limit", JsonPrimitive(500))
        }
        val pollIds = memoryEventStore.get().authoredPollIds(pubkeyHex)
        val pollVoteFilter = pollIds.takeIf { it.isNotEmpty() }?.let { ids ->
            buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1018)) })
                put("#e", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(500))
            }
        }

        var grandTotal = 0
        // Process in batches of 6 relays (concurrent within batch).
        for (batch in allTargets.chunked(6)) {
            Log.d(TAG, "notif-hist batch: ${batch.size} relays")
            val results = fetchPaginatedEvents(
                urls = batch,
                baseFilter = filter,
                subIdPrefix = "notif-hist",
                maxPages = 20,
                timeoutMs = 30_000,
                onPage = { page, count ->
                    Log.d(TAG, "notif-hist page $page: $count events")
                },
            )
            grandTotal += results.sumOf { it.totalEvents }
            if (pollVoteFilter != null) {
                val voteResults = fetchPaginatedEvents(
                    urls = batch,
                    baseFilter = pollVoteFilter,
                    subIdPrefix = "notif-poll-hist",
                    maxPages = 20,
                    timeoutMs = 30_000,
                    onPage = { page, count ->
                        Log.d(TAG, "notif-poll-hist page $page: $count events")
                    },
                )
                grandTotal += voteResults.sumOf { it.totalEvents }
            }
        }
        Log.d(TAG, "fetchHistoricalNotifications done: $grandTotal events across ${allTargets.size} relays")
    }

    /**
     * Open a persistent subscription for events mentioning the user (#p tag)
     * and responses to the user's authored polls (#e tag).
     * Forward-looking only: uses `since` so relays deliver only events created
     * after bootstrap. Replayed automatically on relay reconnect.
     */
    fun subscribeOwnNotifications(pubkeyHex: String, sinceEpochSeconds: Long) {
        val readRelayUrls = memoryEventStore.get().writeRelaysFor(pubkeyHex)
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { connections.containsKey(it) }
            .ifEmpty {
                connectedRelayUrls().mapNotNull { normalizeRelayUrl(it) }.take(4)
            }
        if (readRelayUrls.isEmpty()) return
        val pollIds = memoryEventStore.get().authoredPollIds(pubkeyHex)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("own-notif-live"))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))    // replies
                    add(JsonPrimitive(6))    // reposts
                    add(JsonPrimitive(7))    // reactions
                    add(JsonPrimitive(9735)) // zap receipts
                })
                put("#p", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                put("since", JsonPrimitive(sinceEpochSeconds))
            })
            if (pollIds.isNotEmpty()) {
                add(buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(1018)) })
                    put("#e", buildJsonArray { pollIds.forEach { add(JsonPrimitive(it)) } })
                    put("since", JsonPrimitive(sinceEpochSeconds))
                })
            }
        }.toString()
        liveNotifSubReq = req
        liveNotifSince = sinceEpochSeconds
        liveNotifSubRelays.clear()
        liveNotifSubRelays.addAll(readRelayUrls)
        for (url in readRelayUrls) {
            connections[url]?.send(req)
        }
        Log.d(TAG, "subscribeOwnNotifications: ${readRelayUrls.size} relays, since=$sinceEpochSeconds")
    }

    /** Rebuild the live #e filter after authoring a poll in this app session. */
    fun refreshOwnNotificationSubscription(pubkeyHex: String) {
        subscribeOwnNotifications(
            pubkeyHex = pubkeyHex,
            sinceEpochSeconds = liveNotifSince ?: (System.currentTimeMillis() / 1000L),
        )
    }

    /** Close the persistent notification subscription (teardown). */
    fun closeLiveNotifSub() {
        val urls = liveNotifSubRelays.toList()
        liveNotifSubReq = null
        liveNotifSince = null
        liveNotifSubRelays.clear()
        if (urls.isNotEmpty()) {
            val close = """["CLOSE","own-notif-live"]"""
            for (url in urls) {
                connections[url]?.send(close)
            }
        }
    }

    /**
     * Fetch kind-30002 relay sets by coordinate (author + d-tags) from hint relays.
     * Used to resolve ["a", "30002:pubkey:dtag", "hint-relay"] references in kind-10012.
     * Connects to hint relays if not already connected, sends a single REQ with
     * #d filter to fetch all referenced sets in one subscription.
     */
    suspend fun fetchRelaySetsByCoordinate(
        authorPubkey: String,
        dTags: List<String>,
        hintRelayUrls: List<String>,
    ) {
        if (dTags.isEmpty() || hintRelayUrls.isEmpty()) return
        val normalized = hintRelayUrls.mapNotNull { normalizeRelayUrl(it) }
            .filter { it !in blockedUrls }
        if (normalized.isEmpty()) return

        connectAndAwait(normalized, timeoutMs = 3_000)

        val subId = "setref-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(30002)) })
                put("authors", buildJsonArray { add(JsonPrimitive(authorPubkey)) })
                put("#d", buildJsonArray { dTags.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(dTags.size))
            })
        }.toString()

        var sent = 0
        for (url in normalized) {
            connections[url]?.let { conn ->
                sendOneShotToRelay(conn, req)
                sent++
            }
        }
        Log.d(TAG, "fetchRelaySetsByCoordinate: ${dTags.size} sets from $sent hint relay(s) for ${authorPubkey.take(8)}…")
    }

    // ── Paginated fetch primitive ────────────────────────────────────────
    //
    // Reusable building block for fetching large result sets (>500 events)
    // from a single relay. Handles: 500-event ceiling (pagination via
    // "until"), EOSE signaling, per-page cleanup, overall timeout.
    //
    // ISOLATION: uses conn.send() directly, bypasses sub cap and rate limiter.
    // Events flow through EventProcessor's direct-path insert into MES.

    /**
     * Paginated fetch from a single relay. Sends REQ, waits for EOSE,
     * paginates with "until" if the page is full, repeats until last page
     * or overall timeout.
     */
    private suspend fun paginatedFetch(
        conn: RelayConnection,
        baseFilter: JsonObject,
        subIdPrefix: String,
        timeoutMs: Long = 15_000,
        onPage: (pageNum: Int, eventCount: Int) -> Unit = { _, _ -> },
    ): PaginatedFetchResult {
        var totalEvents = 0
        var totalPages = 0
        var globalOldest = Long.MAX_VALUE
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val subId = "$subIdPrefix-${System.nanoTime()}"
            val pageState = PageState()
            _activePages[subId] = pageState

            val filter = if (globalOldest < Long.MAX_VALUE) {
                buildJsonObject {
                    baseFilter.forEach { (key, value) -> put(key, value) }
                    put("until", JsonPrimitive(globalOldest - 1))
                }
            } else {
                baseFilter
            }

            val req = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(subId))
                add(filter)
            }.toString()

            val sent = conn.send(req)
            if (!sent) {
                _activePages.remove(subId)
                Log.w(TAG, "paginatedFetch: send failed on ${conn.url}")
                break
            }

            val remaining = deadline - System.currentTimeMillis()
            val gotEose = withTimeoutOrNull(remaining) {
                pageState.eoseReceived.await()
            }

            val pageCount = pageState.eventCount.get()
            val pageOldest = pageState.oldestCreatedAt.get()

            // Send CLOSE and clean up
            conn.send(buildJsonArray {
                add(JsonPrimitive("CLOSE"))
                add(JsonPrimitive(subId))
            }.toString())
            _activePages.remove(subId)

            totalEvents += pageCount
            totalPages++
            if (pageOldest < globalOldest) globalOldest = pageOldest
            onPage(totalPages, pageCount)

            if (gotEose == null) {
                Log.w(TAG, "paginatedFetch: timeout on page $totalPages of ${conn.url}")
                break
            }
            if (pageCount < 450) break
        }

        return PaginatedFetchResult(
            totalEvents = totalEvents,
            totalPages = totalPages,
            oldestCreatedAt = if (globalOldest < Long.MAX_VALUE) globalOldest else 0L,
            relay = conn.url,
        )
    }

    // ── Public paginated fetch wrapper ──────────────────────────────────

    /**
     * Paginated fetch across multiple relays (concurrent). Events flow through
     * EventProcessor → MES as they arrive — no special routing needed.
     *
     * @param urls relay URLs to fetch from (will be connected via [connectAndAwait]).
     * @param baseFilter JSON filter object (kinds, authors, limit, etc.).
     * @param subIdPrefix subscription ID prefix for all pages.
     * @param maxPages max pages per relay before stopping.
     * @param timeoutMs overall timeout per relay.
     * @param onPage per-page callback (pageNum, eventCount) for logging.
     * @return list of [PaginatedFetchResult], one per relay that responded.
     */
    suspend fun fetchPaginatedEvents(
        urls: List<String>,
        baseFilter: JsonObject,
        subIdPrefix: String,
        maxPages: Int = 5,
        timeoutMs: Long = 30_000,
        onPage: (pageNum: Int, eventCount: Int) -> Unit = { _, _ -> },
    ): List<PaginatedFetchResult> {
        val normalized = urls.mapNotNull { normalizeRelayUrl(it) }.distinct()
            .filter { it !in blockedUrls }
        if (normalized.isEmpty()) return emptyList()

        connectAndAwait(normalized, timeoutMs = 5_000)

        return coroutineScope {
            normalized.mapNotNull { url ->
                val conn = connections[url] ?: return@mapNotNull null
                if (!conn.isConnected) return@mapNotNull null
                async {
                    val perRelayTimeout = (timeoutMs / normalized.size.coerceAtLeast(1))
                        .coerceIn(10_000, timeoutMs)
                    paginatedFetch(conn, baseFilter, subIdPrefix, perRelayTimeout, onPage)
                }
            }.awaitAll()
        }
    }

    // ── Relay health fetch orchestrators ──────────────────────────────────

    /**
     * Fetch NIP-85 kind-30382 user WoT assertions for [subjects] from the active provider relay.
     *
     * The provider relay is opened as an ephemeral connection and never inserted into
     * [connections]. Each chunk is marked queried only after that chunk's EOSE, preserving
     * the MES Pending vs Absent distinction.
     */
    suspend fun fetchWotAssertions(
        providerPubkey: String,
        relayHint: String,
        subjects: Collection<String>,
        prioritySubjects: Collection<String> = emptyList(),
    ): Boolean {
        val provider = normalizeWotPubkey(providerPubkey) ?: return false
        val relayUrl = normalizeRelayUrl(relayHint) ?: return false
        if (relayUrl in blockedUrls || relayCapabilitiesStore.shouldSkip(relayUrl)) return false

        val chunks = wotSubjectChunks(subjects, prioritySubjects = prioritySubjects)
        if (chunks.isEmpty()) return false

        var allEosed = true
        for ((index, chunk) in chunks.withIndex()) {
            val eosed = fetchWotChunk(relayUrl, provider, chunk, index + 1, chunks.size)
            if (!eosed) {
                allEosed = false
            }
        }
        return allEosed
    }

    private suspend fun fetchWotChunk(
        relayUrl: String,
        provider: String,
        chunk: List<String>,
        page: Int,
        totalPages: Int,
    ): Boolean {
        val conn = RelayConnection(relayUrl, okHttpClient, relayCapabilitiesStore)
        return try {
            conn.connect()
            val state = withTimeoutOrNull(2_000) {
                conn.state.first {
                    it == RelayState.CONNECTED || it == RelayState.FAILED || it == RelayState.DISCONNECTED
                }
            }
            if (state != RelayState.CONNECTED) {
                Log.w(TAG, "WoT fetch connect failed for chunk $page/$totalPages on $relayUrl (state=$state)")
                return false
            }
            fetchWotChunk(conn, relayUrl, provider, chunk, page, totalPages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "WoT fetch failed for chunk $page/$totalPages on $relayUrl: ${e.message}")
            false
        } finally {
            conn.close()
        }
    }

    private suspend fun fetchWotChunk(
        conn: RelayConnection,
        relayUrl: String,
        provider: String,
        chunk: List<String>,
        page: Int,
        totalPages: Int,
    ): Boolean {
        val subId = "wot-${relayUrl.hashCode().toUInt()}-${System.nanoTime()}-$page"
        val filter = wotAssertionFilter(provider, chunk) ?: return false
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(filter)
        }.toString()
        if (!conn.send(req)) return false

        val verifiedEvents = ArrayList<NostrEvent>(chunk.size)
        val eosed = withTimeoutOrNull(WOT_FETCH_TIMEOUT_MS) {
            while (true) {
                val raw = conn.messages.receive()
                when {
                    raw.startsWith("[\"EVENT\"") -> {
                        val event = processor.parseAndVerify(raw, relayUrl)
                        if (event != null && event.kind == 30382 && normalizeWotPubkey(event.pubkey) == provider) {
                            verifiedEvents.add(event)
                        }
                    }
                    raw.startsWith("[\"EOSE\"") && extractEoseSubId(raw) == subId -> {
                        return@withTimeoutOrNull true
                    }
                    raw.startsWith("[\"CLOSED\"") && raw.contains("\"$subId\"") -> {
                        Log.w(TAG, "WoT fetch CLOSED for chunk $page/$totalPages on $relayUrl")
                        return@withTimeoutOrNull false
                    }
                    raw.startsWith("[\"AUTH\"") -> {
                        val challenge = raw.substringAfter("[\"AUTH\",\"", "")
                            .substringBefore("\"")
                        if (challenge.isNotEmpty()) handleAuthChallenge(conn, challenge)
                    }
                    raw.startsWith("[\"OK\"") -> handleOk(conn, raw)
                }
            }
            false
        } == true

        conn.send(buildJsonArray {
            add(JsonPrimitive("CLOSE"))
            add(JsonPrimitive(subId))
        }.toString())

        memoryEventStore.get().insertWotAssertionChunk(
            providerPubkey = provider,
            events = verifiedEvents,
            queriedSubjects = if (eosed) chunk else emptyList(),
        )
        return eosed
    }

    /**
     * Fetch the user's public NIP-85 provider registry (kind-10040).
     *
     * Events route through EventProcessor → MES, where only own-pubkey 10040 rows
     * are accepted. The default WoT relay is included because personal providers may
     * publish the registry there even when it is not in the user's read list.
     */
    suspend fun fetchOwn10040(ownPubkey: String): Boolean {
        val owner = normalizeWotPubkey(ownPubkey) ?: return false
        val targets = wotRegistryLookupTargets(
            readRelays = memoryEventStore.get().readRelaysFor(owner),
            registryRelays = WOT_REGISTRY_LOOKUP_RELAYS,
        )
        if (targets.relayUrls.isEmpty()) return false

        val subId = "wot-10040-${System.nanoTime()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(10040)) })
                put("authors", buildJsonArray { add(JsonPrimitive(owner)) })
                put("limit", JsonPrimitive(1))
            })
        }.toString()

        val eoseDeferred = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = eoseDeferred
        return try {
            sendOneShotBatch(
                urls = targets.relayUrls,
                reqs = listOf(req),
                subIds = listOf(subId),
                timeoutMs = 8_000L,
                capabilityBypassRelays = targets.capabilityBypassRelays,
            )
            withTimeoutOrNull(8_000L) { eoseDeferred.await() } != null
        } finally {
            cleanupOneShotSub(subId)
        }
    }

    /**
     * Fetch kind 30385 (Trusted Relay Assertions) using paginated multi-relay fetch.
     *
     * 1. Resolve provider's write relays from kind-10002
     * 2. Paginated fetch on each write relay (concurrent)
     * 3. Log coverage metrics
     */
    /**
     * Fetch kind 30385 (trust scores) for specific relay URLs only.
     * Uses #d filter so the relay returns only the requested entries.
     * Each relay URL is a d-tag in kind 30385 replaceable events.
     */
    suspend fun fetchTrustScores(providerPubkeyHex: String, relayUrls: List<String>): Boolean {
        if (relayUrls.isEmpty()) return false

        // Normalize relay URLs for consistent #d matching with trust score d-tags
        val normalizedUrls = relayUrls.mapNotNull { normalizeRelayUrl(it) }.distinct()
        if (normalizedUrls.isEmpty()) return false

        // Resolve provider's write relays to know where to fetch from
        fetchRelayLists(listOf(providerPubkeyHex))
        val mes = memoryEventStore.get()
        val writeRelays = withTimeoutOrNull(5_000L) {
            var relays = mes.writeRelaysFor(providerPubkeyHex)
            while (relays.isEmpty()) {
                delay(200)
                relays = mes.writeRelaysFor(providerPubkeyHex)
            }
            relays
        } ?: emptyList()

        val sourceUrls = if (writeRelays.isNotEmpty()) {
            writeRelays.mapNotNull { normalizeRelayUrl(it) }
        } else {
            Log.w(TAG, "Trust score provider 10002 not found — using fallback relays")
            listOf("wss://nos.lol", "wss://relay.damus.io")
        }

        // #d filter: request only the relay URLs we care about (normalized)
        val filter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(30385)) })
            put("authors", buildJsonArray { add(JsonPrimitive(providerPubkeyHex)) })
            put("#d", buildJsonArray { normalizedUrls.forEach { add(JsonPrimitive(it)) } })
        }
        Log.d(TAG, "Trust score REQ: ${normalizedUrls.size} relays from ${sourceUrls.size} sources")

        // Try each source relay until we get results
        var fetched = 0
        var completed = false
        for (url in sourceUrls) {
            val conn = getOrCreateConnection(url) ?: continue
            try {
                val subId = "trust-${url.hashCode().toUInt()}-${System.nanoTime()}"
                val pageState = PageState()
                _activePages[subId] = pageState

                val req = buildJsonArray {
                    add(JsonPrimitive("REQ"))
                    add(JsonPrimitive(subId))
                    add(filter)
                }.toString()

                if (!conn.send(req)) {
                    _activePages.remove(subId)
                    continue
                }

                // With #d filter, result set is small — 15s is plenty
                val eosed = withTimeoutOrNull(15_000) { pageState.eoseReceived.await() } != null
                if (eosed) completed = true
                val count = pageState.eventCount.get()

                conn.send(buildJsonArray {
                    add(JsonPrimitive("CLOSE"))
                    add(JsonPrimitive(subId))
                }.toString())
                _activePages.remove(subId)

                fetched += count
                Log.d(TAG, "Trust scores from $url: $count events")

                // If we got a good response, no need to try more sources
                if (count > 0) break
            } catch (e: Exception) {
                Log.w(TAG, "Trust fetch failed on $url: ${e.message}")
            }
        }

        val mesCount = mes.getTrustScores().size
        Log.d(TAG, "Trust scores: $mesCount in MES ($fetched fetched for ${normalizedUrls.size} requested)")
        // A real EOSE with zero events is still a successful, authoritative
        // fetch for the requested #d set and should advance the staleness gate.
        return completed
    }

    /**
     * Fetch kind 30166 (NIP-66 relay monitors) from relay.nostr.watch.
     * Fetches ALL monitors (no #d filter) because relay.nostr.watch uses
     * d-tag formats that may not match our normalized URLs (e.g. trailing slashes).
     * With snapshot persistence this only downloads once; subsequent launches
     * restore from disk and refresh in the background.
     */
    /** @return true only if the monitor relay was reached and pagination completed
     *  without error. The caller MUST advance the 12h staleness gate only on true —
     *  a failed fetch that marked "fetched" buys 12h of silent staleness (H20 lesson:
     *  a gate poisoned by unverified success). */
    suspend fun fetchRelayMonitors(): Boolean {
        var conn = getOrCreateConnection(RELAY_MONITOR_URL)
        if (conn == null) {
            Log.w(TAG, "relay.nostr.watch unreachable — retrying in 3s")
            delay(3_000)
            conn = getOrCreateConnection(RELAY_MONITOR_URL)
        }
        if (conn == null) {
            Log.w(TAG, "relay.nostr.watch unreachable after retry — skipping relay monitors")
            return false
        }

        val baseFilter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(30166)) })
            put("authors", buildJsonArray { add(JsonPrimitive(RELAY_MONITOR_PUBKEY)) })
        }

        return try {
            val result = paginatedFetch(
                conn = conn,
                baseFilter = baseFilter,
                subIdPrefix = "relay-monitor",
                timeoutMs = 30_000,
                onPage = { page, count ->
                    Log.d(TAG, "Monitor page $page: $count events")
                },
            )
            val monitorCount = memoryEventStore.get().getRelayMonitors().size
            Log.d(TAG, "Relay monitors: $monitorCount in MES " +
                "(${result.totalPages} pages, ${result.totalEvents} events)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Monitor fetch failed: ${e.message}")
            false
        }
    }

    // ── Relay Directory firehose (Phase 1) ────────────────────────────────────
    // Two vetted monitors (dynamic registry discovery = Phase 1.5 backlog), author-pinned
    // across four redundant transports — author and transport are independent dimensions,
    // censorship-resilient by construction. 30166 events are signature-verified (A1) then
    // parsed into a bounded map; they NEVER enter MES (raw→directory→discard), so MES/size
    // stays flat by construction.
    private val DIRECTORY_MONITOR_PUBKEYS = setOf(
        RELAY_MONITOR_PUBKEY,  // 9bbbb845 — rich schema (RTT, NIPs, embedded NIP-11)
        "6d9717bc8758ddf99bc1b0e325d60bf5c41418dc122d81de6cd1a35138e51fe3",  // light/bot — liveness corroboration
    )
    private val DIRECTORY_TRANSPORT_RELAYS = listOf(
        RELAY_MONITOR_URL, "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net",
    )
    private val DIRECTORY_TTL_MS = 6L * 60 * 60 * 1000   // 6h, success-only
    private val DIRECTORY_MAX_PAGES = 8
    private val relayDirectory = ConcurrentHashMap<String, RelayDirectoryEntry>()
    private val directoryInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastDirectoryBuildAt = 0L
    private val _directoryFlow = MutableStateFlow<Map<String, RelayDirectoryEntry>>(emptyMap())
    private val _directoryBuilding = MutableStateFlow(false)

    /** Snapshot of the built directory (UI/validation). */
    fun directorySnapshot(): Map<String, RelayDirectoryEntry> = relayDirectory.toMap()

    /** Reactive directory for the §04 Discovery screen — emits the full map after each build. */
    val directoryFlow: StateFlow<Map<String, RelayDirectoryEntry>> = _directoryFlow.asStateFlow()
    /** True while a directory build is in flight (Discovery loading state). */
    val directoryBuilding: StateFlow<Boolean> = _directoryBuilding.asStateFlow()

    /** How many of [urls] (the caller's own kind-10002 relays) are CONNECTED right now.
     *  One-shot snapshot — no ticker. INTERSECTION, never connections.size: the pool idles
     *  ~30 sockets (indexers/search/monitor/warmed globals), so a "N online" badge must mean
     *  the user's own relays, not the whole pool. Reused by Settings + the relay health bar. */
    fun connectedCountOf(urls: Collection<String>): Int {
        val normalized = urls.mapNotNull { normalizeRelayUrl(it) }.toSet()
        return normalized.count { connections[it]?.isConnected == true }
    }

    /** USER-INITIATED one-shot RTT probe (the relay-screen "Test" action). Times the connect
     *  handshake on a fresh ephemeral socket. Deliberately NO background/periodic pinger and
     *  NO persisted history — lean, on-demand only. Returns connect latency in ms, or null if
     *  unreachable within the timeout. */
    suspend fun measureRtt(url: String): Int? {
        val u = normalizeRelayUrl(url) ?: return null
        if (u in blockedUrls) return null
        val conn = RelayConnection(u, okHttpClient, relayCapabilitiesStore)
        val start = System.nanoTime()
        return try {
            conn.connect()
            val state = withTimeoutOrNull(5_000) {
                conn.state.first { it == RelayState.CONNECTED || it == RelayState.FAILED || it == RelayState.DISCONNECTED }
            }
            if (state == RelayState.CONNECTED) ((System.nanoTime() - start) / 1_000_000L).toInt() else null
        } catch (_: Exception) {
            null
        } finally {
            conn.close()
        }
    }

    /**
     * Build/refresh the relay directory (Phase 1). Public, ON-DEMAND only — never cold-start
     * or background (Phase 2 wires it to the discovery screen). Single-flight; 6h success-only
     * TTL (mirrors c9dbb4e4 — a failed build never advances the TTL, so a bad network doesn't
     * buy 6h of staleness).
     */
    suspend fun ensureDirectoryFresh() {
        val sinceLast = System.currentTimeMillis() - lastDirectoryBuildAt
        if (lastDirectoryBuildAt > 0L && sinceLast < DIRECTORY_TTL_MS) {
            Log.w(TAG, "DIRECTORY: fresh (age=${sinceLast / 60_000}min) — skipping fetch")
            return
        }
        if (!directoryInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "DIRECTORY: build already in flight — skipping")
            return
        }
        _directoryBuilding.value = true
        val started = System.currentTimeMillis()
        try {
            // MES-derived enrichment inputs — computed ONCE up front (they don't depend
            // on the fetch), so the progressive re-publishes below stay cheap.
            val mes = memoryEventStore.get()
            val ownPk = keyManager.getPublicKeyHex()
            val trust = mes.getTrustScores()
            val popularity = computeDirectoryPopularity(mes)
            val followsUsing = computeDirectoryFollowsUsing(mes, ownPk)
            // The user's OWN configured relays (r/w, search, favorites, set members) —
            // always seeded so relays you ALREADY use are discoverable even if the
            // monitor never reported them (e.g. perspective relays like subnet.relays.land).
            val configured = if (ownPk != null) buildSet {
                addAll(mes.writeRelaysFor(ownPk))
                addAll(mes.readRelaysFor(ownPk))
                addAll(mes.getSearchRelayUrls(ownPk))
                mes.getFavoriteRelayConfigs(ownPk).forEach { it.url?.let(::add) }
                mes.getAllRelaySets(ownPk).forEach { addAll(it.members) }
            }.mapNotNull { normalizeRelayUrl(it) } else emptyList()
            // Bound never evicts the user's own r/w relays.
            val ownRelays = if (ownPk != null) {
                (mes.writeRelaysFor(ownPk) + mes.readRelaysFor(ownPk)).mapNotNull { normalizeRelayUrl(it) }.toSet()
            } else emptySet()

            val perUrl = HashMap<String, MutableList<RelayDirectoryEntry>>()
            var totalEvents = 0
            var verifyFailed = 0
            var capped = false

            // Build the directory from accumulated perUrl + seed own relays + bound,
            // then publish. Called PROGRESSIVELY after each transport returns so the
            // screen populates from the fast transports (~2s) and fills to full
            // coverage when the slow transport (relay.nostr.watch) completes (~15s).
            fun rebuildAndPublish() {
                val built = perUrl.mapValues { (_, entries) ->
                    // Dedup per monitor (newest createdAt), then merge across monitors (median RTT).
                    val perMonitorNewest = entries.groupBy { it.monitorPubkeys.firstOrNull() }
                        .values.map { grp -> grp.maxBy { it.monitorLastSeenAt } }
                    val merged = RelayDirectory.mergeMonitorEntries(perMonitorNewest)
                    enrichDirectoryEntry(merged, trust[merged.url]?.score, popularity[merged.url] ?: 0, followsUsing[merged.url] ?: 0)
                }.toMutableMap()
                for (u in configured) {
                    if (u !in built) built[u] = enrichDirectoryEntry(RelayDirectoryEntry(url = u), trust[u]?.score, popularity[u] ?: 0, followsUsing[u] ?: 0)
                }
                val bounded = RelayDirectory.enforceBound(built, ownRelays)
                relayDirectory.clear()
                relayDirectory.putAll(bounded)
                _directoryFlow.value = relayDirectory.toMap()
            }

            // Fetch all transports CONCURRENTLY; consume results in COMPLETION order via
            // a channel so the fast transports publish immediately (perceived load ≈ the
            // fastest transport, not the slowest). Same total bandwidth as sequential;
            // each collectDirectory30166 is failure-isolated (catches internally + closes
            // its own ephemeral conn).
            coroutineScope {
                val results = Channel<Triple<String, List<NostrEvent>, Int>>(DIRECTORY_TRANSPORT_RELAYS.size)
                for (transport in DIRECTORY_TRANSPORT_RELAYS) {
                    launch {
                        val (events, failed) = collectDirectory30166(transport)
                        results.send(Triple(transport, events, failed))
                    }
                }
                repeat(DIRECTORY_TRANSPORT_RELAYS.size) {
                    val (transport, events, failed) = results.receive()
                    verifyFailed += failed
                    // Per-transport breakdown — proves the redundancy is actually multi-sourced
                    // (the only field signal that a transport is silently rate-limited/empty).
                    Log.w(TAG, "DIRECTORY: transport $transport → ${events.size} events ($failed verify-failed)")
                    if (!capped) {
                        for (ev in events) {
                            totalEvents++
                            val entry = RelayDirectory.parseMonitorEvent(ev.tags, ev.content, ev.pubkey, ev.createdAt)
                                ?: continue
                            perUrl.getOrPut(entry.url) { mutableListOf() }.add(entry)
                            if (perUrl.size >= RelayDirectory.MAX_DIRECTORY_ENTRIES) { capped = true; break }
                        }
                    }
                    rebuildAndPublish()   // progressive emit after each transport completes
                }
                results.close()
            }

            lastDirectoryBuildAt = System.currentTimeMillis()
            val reachable = relayDirectory.values.count { it.reachability == Reachability.REACHABLE }
            val dnsBlocked = relayDirectory.values.count { it.reachability == Reachability.DNS_BLOCKED }
            Log.w(TAG, "DIRECTORY: built ${relayDirectory.size} relays from $totalEvents events " +
                "($verifyFailed verified-failed), ${System.currentTimeMillis() - started}ms, " +
                "reachable=$reachable dnsBlocked=$dnsBlocked")
            val land = relayDirectory.keys.filter { it.contains("relays.land") }
            Log.w(TAG, "DIRECTORY: relays.land entries = $land")   // acceptance signal
        } catch (e: Exception) {
            Log.w(TAG, "DIRECTORY: build failed — ${e.message} (TTL not advanced, will retry)")
        } finally {
            directoryInFlight.set(false)
            _directoryBuilding.value = false
        }
    }

    /** Per-build "N you follow" — for each url, how many of the user's follows list it in their
     *  kind-10002. Bounded (follows set × their relay lists), computed once per build; the detail
     *  page's per-open recompute (buildRelayDetail) stays the fresher override. */
    private fun computeDirectoryFollowsUsing(
        mes: com.unsilence.app.data.memory.MemoryEventStore,
        ownPubkey: String?,
    ): Map<String, Int> {
        val follows = ownPubkey?.let { mes.getFollows(it) } ?: return emptyMap()
        if (follows.isEmpty()) return emptyMap()
        val allLists = mes.allRelayListsSnapshot()
        val counts = HashMap<String, Int>()
        for (fp in follows) {
            val rl = allLists[fp] ?: continue
            (rl.read + rl.write).mapNotNull { normalizeRelayUrl(it) }.toSet().forEach { url ->
                counts[url] = (counts[url] ?: 0) + 1
            }
        }
        return counts
    }

    private fun computeDirectoryPopularity(mes: com.unsilence.app.data.memory.MemoryEventStore): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for ((_, list) in mes.allRelayListsSnapshot()) {
            (list.read + list.write).mapNotNull { normalizeRelayUrl(it) }.toSet().forEach { url ->
                counts[url] = (counts[url] ?: 0) + 1
            }
        }
        return counts
    }

    private fun enrichDirectoryEntry(merged: RelayDirectoryEntry, trustScore: Int?, popularity: Int, followsUsing: Int): RelayDirectoryEntry {
        val caps = relayCapabilitiesStore.get(merged.url)
        // Device-empirical evidence is the trump card (A5); monitor data only adds positive signal.
        val reach = RelayDirectory.computeReachability(
            hasCapsEntry = caps != null,
            deadFailCount = caps?.deadFailCount ?: 0,
            authRequired = (caps?.authRequired == true) || merged.auth,
            restricted = caps?.restricted == true,
            strikes = caps?.strikes ?: 0,
            consecutiveFailures = caps?.consecutiveFailures ?: 0,
            lastReason = caps?.lastReason,
            monitorRttMs = merged.monitorRttMs,
        )
        return merged.copy(
            trustScore = trustScore,
            popularity = popularity,
            followsUsing = followsUsing,
            ourLastReason = caps?.lastReason?.takeIf { it.isNotBlank() },
            reachability = reach,
        )
    }

    /**
     * Build the per-relay DETAIL view (§05, on detail-open). Overlays this device's NIP-11
     * fetch onto the monitor seed (PERSPECTIVE RULE — device wins), takes ONE live RTT probe,
     * and recomputes reachability from fresh caps + the probe outcome. Writes the merged entry
     * back into the directory so the device perspective persists for the session (a 6h rebuild
     * resets it to the monitor seed, re-fetched on next open). Returns null only for an
     * unnormalizable url. Never throws — an unreachable host yields a monitor-seed entry with
     * ourRttMs=null, which the detail page renders as an honest "untested/blocked" verdict.
     */
    suspend fun buildRelayDetail(url: String): RelayDirectoryEntry? {
        val u = normalizeRelayUrl(url) ?: return null
        val mes = memoryEventStore.get()
        val base = relayDirectory[u]
            ?: RelayDirectoryEntry(url = u, popularity = computeDirectoryPopularity(mes)[u] ?: 0)

        // "You follow" — of the people YOU follow (kind-3), how many list this relay in their
        // kind-10002. The honest reading of the §05 "do people I trust use it?" question;
        // distinct from `popularity` (the broad all-known-lists ranking signal).
        val ownPk = keyManager.getPublicKeyHex()
        val follows = ownPk?.let { mes.getFollows(it) } ?: emptySet()
        val allLists = mes.allRelayListsSnapshot()
        val followsUsing = follows.count { fp ->
            allLists[fp]?.let { rl -> (rl.read + rl.write).any { normalizeRelayUrl(it) == u } } == true
        }

        // Device NIP-11 — the authority. Null (unreachable/blocked) keeps the monitor seed.
        val doc = nip11Fetcher.fetch(u)
        val overlaid = if (doc != null) RelayDirectory.overlayDeviceNip11(base, doc) else base

        // One live RTT on open (the connect-handshake probe). This also refreshes caps:
        // onOpen clears strikes; a failure records the skip reason — so read caps AFTER.
        val rtt = measureRtt(u)
        val caps = relayCapabilitiesStore.get(u)
        val reach = RelayDirectory.computeReachability(
            hasCapsEntry = caps != null || rtt != null,
            deadFailCount = caps?.deadFailCount ?: 0,
            authRequired = (caps?.authRequired == true) || overlaid.auth,
            restricted = caps?.restricted == true,
            strikes = caps?.strikes ?: 0,
            consecutiveFailures = caps?.consecutiveFailures ?: 0,
            lastReason = caps?.lastReason,
            monitorRttMs = rtt ?: overlaid.monitorRttMs,
        )
        val detail = overlaid.copy(
            ourRttMs = rtt,
            ourLastReason = caps?.lastReason?.takeIf { it.isNotBlank() },
            reachability = reach,
            followsUsing = followsUsing,
        )
        relayDirectory[u] = detail
        Log.w(TAG, "DETAIL: $u src=${detail.nip11Source} rtt=${rtt}ms reach=${detail.reachability} " +
            "nips=${detail.supportedNips.size} pop=${detail.popularity} follow=${detail.followsUsing} " +
            "geo=${detail.countryCode} trust=${detail.trustScore}")
        return detail
    }

    /** Ephemeral, paginated (A6), VERIFIED (A1) collect of kind-30166 from one transport.
     *  Author-pinned to our trusted monitors; every event is id-hash + Schnorr verified and
     *  author-allowlisted (relays can return anything) before it's returned. Returns
     *  (verified events, verification-failure count). Never touches the connections map. */
    private suspend fun collectDirectory30166(transport: String): Pair<List<NostrEvent>, Int> {
        val url = normalizeRelayUrl(transport) ?: return emptyList<NostrEvent>() to 0
        val collected = mutableListOf<NostrEvent>()
        var verifyFailed = 0
        val conn = RelayConnection(url, okHttpClient, relayCapabilitiesStore)

        fun reqFor(subId: String, until: Long): String = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(30166)) })
                put("authors", buildJsonArray { DIRECTORY_MONITOR_PUBKEYS.forEach { add(JsonPrimitive(it)) } })
                if (until < Long.MAX_VALUE) put("until", JsonPrimitive(until - 1))
            })
        }.toString()

        try {
            conn.connect()
            val state = withTimeoutOrNull(2_000) {
                conn.state.first { it == RelayState.CONNECTED || it == RelayState.FAILED || it == RelayState.DISCONNECTED }
            }
            if (state != RelayState.CONNECTED) return collected to verifyFailed

            // ONE consumeEach for the whole transport — pagination is driven INSIDE the loop by
            // sending the next page's REQ on EOSE. consumeEach cancels the channel when it exits,
            // so a per-page consume (the old bug) killed the socket after page 1 ("Channel was
            // cancelled" on every clean-EOSE relay). Until-cursor windowing; 8-page / 3000-relay cap.
            var page = 0
            var until = Long.MAX_VALUE
            var subId = "dir-${System.nanoTime()}-p0"
            var pageCount = 0
            var pageOldest = Long.MAX_VALUE
            if (!conn.send(reqFor(subId, until))) return collected to verifyFailed

            // 15s per-transport ceiling — full coverage (the richest transport dumps
            // its bulk late). With the concurrent fetch + progressive publish in
            // ensureDirectoryFresh, the SCREEN populates from the fast transports in
            // ~2s; this ceiling only bounds the final fill-in, not perceived load.
            withTimeoutOrNull(15_000) {
                conn.messages.consumeEach { raw ->
                    when {
                        raw.startsWith("[\"EVENT\"") -> {
                            val ev = processor.parseAndVerify(raw, url)
                            when {
                                ev == null -> verifyFailed++   // forged/malformed — dropped + counted (A1)
                                ev.kind == 30166 && ev.pubkey in DIRECTORY_MONITOR_PUBKEYS -> {
                                    collected.add(ev)
                                    pageCount++
                                    if (ev.createdAt < pageOldest) pageOldest = ev.createdAt
                                }
                                // else: authentic but not a trusted monitor — silently ignore (A1)
                            }
                        }
                        raw.startsWith("[\"EOSE\"") && extractEoseSubId(raw) == subId -> {
                            conn.send("""["CLOSE","$subId"]""")
                            page++
                            val done = pageCount == 0 ||
                                pageOldest == Long.MAX_VALUE || pageOldest >= until ||
                                page >= DIRECTORY_MAX_PAGES ||
                                collected.size >= RelayDirectory.MAX_DIRECTORY_ENTRIES
                            if (done) return@withTimeoutOrNull
                            until = pageOldest
                            pageCount = 0
                            pageOldest = Long.MAX_VALUE
                            subId = "dir-${System.nanoTime()}-p$page"
                            conn.send(reqFor(subId, until))   // next page on the SAME channel
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DIRECTORY: collect failed on $url — ${e.message}")
        } finally {
            conn.close()
        }
        return collected to verifyFailed
    }

    /**
     * Get a live connection to [url], creating one if needed.
     * Bypasses connection cap for control-plane fetches.
     * Replaces stale/dead connections.
     */
    private suspend fun getOrCreateConnection(url: String): RelayConnection? {
        val normalized = normalizeRelayUrl(url) ?: return null
        val existing = connections[normalized]
        if (existing != null && existing.isConnected) return existing
        if (existing != null) {
            connections.remove(normalized)   // map-before-close
            existing.close()
        }
        val conn = RelayConnection(normalized, okHttpClient, relayCapabilitiesStore)
        connections[normalized] = conn
        connectionLastActivity[normalized] = System.currentTimeMillis()
        conn.connect()
        scope.launch { listenForEvents(conn) }
        return try {
            conn.awaitConnected(timeoutMs = 5_000)
            conn
        } catch (_: Exception) {
            Log.w(TAG, "getOrCreateConnection: $normalized failed to connect")
            null
        }
    }

    /** Send a kind 0 profile request for [pubkeys] to indexer relays only (deduped).
     *  [maxRelays] caps how many relays receive the REQ (1 for scroll, more for profile screen). */
    fun fetchProfiles(pubkeys: List<String>, maxRelays: Int = 5) {
        if (pubkeys.isEmpty()) return
        val now = System.currentTimeMillis()
        val novel = pubkeys.filter { pk ->
            // Negative-cache: full chain (indexer+fallback) failed for this pk recently
            val negCached = profileFallbackNegCache[pk]
            if (negCached != null && (now - negCached) < PROFILE_FALLBACK_NEG_TTL) return@filter false
            val lastAttempt = profileFetchAttempted[pk]
            lastAttempt == null || (now - lastAttempt) > 120_000 // 2 min TTL
        }
        if (novel.isEmpty()) return
        novel.forEach { profileFetchAttempted[it] = now }
        val subId = "profiles-${System.nanoTime()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(10002))
                })
                put("authors", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
            })
        }.toString()
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
        val minTargets = minOf(maxRelays, 3)
        val targetUrls = indexerUrls.take(maxRelays).let { indexers ->
            if (indexers.size >= minTargets) indexers
            else {
                val extras = connections.keys.filter { it !in indexers }.take(minTargets - indexers.size)
                indexers + extras
            }
        }.ifEmpty { connections.keys.take(minTargets).toList() }

        scope.launch {
            // Register EOSE callback so sendOneShotBatch tracks per-relay coverage
            val eoseDeferred = CompletableDeferred<Unit>()
            oneShotEoseCallbacks[subId] = eoseDeferred

            sendOneShotBatch(targetUrls, listOf(req), listOf(subId))

            // Wait for all indexer relays to EOSE (ceiling 5s past sendOneShotBatch)
            withTimeoutOrNull(5_000) { eoseDeferred.await() }
            oneShotEoseCallbacks.remove(subId)
            oneShotSubTargets.remove(subId)
            oneShotSubEosed.remove(subId)

            // Cold-lane flush for kind-0 events
            delay(COLD_LANE_FLUSH_MS)

            // ── Fallback: indexers incomplete → author's own relays (H19b) ─
            val mes = memoryEventStore.get()
            val stillIncomplete = novel.filter { profileMissingPicture(mes.getUserEntity(it)) }
            if (stillIncomplete.isEmpty()) return@launch

            val triedRelays = targetUrls.mapNotNull { normalizeRelayUrl(it) }.toSet()
            val relayToPks = mutableMapOf<String, MutableList<String>>()
            for (pk in stillIncomplete) {
                val candidates = mutableSetOf<String>()
                candidates.addAll(mes.writeRelaysFor(pk))
                candidates.addAll(mes.relaysSeenForPubkey(pk))
                candidates.removeAll(triedRelays)
                // sendOneShotBatch filters blocked+shouldSkip; pre-filter for accurate grouping
                candidates.removeAll(blockedUrls)
                for (url in candidates.take(4)) {
                    relayToPks.getOrPut(url) { mutableListOf() }.add(pk)
                }
            }
            if (relayToPks.isEmpty()) {
                val fbNow = System.currentTimeMillis()
                stillIncomplete.forEach { profileFallbackNegCache[it] = fbNow }
                Log.w(TAG, "PROFFB: ${stillIncomplete.size} pk(s) incomplete — no relay signal")
                return@launch
            }

            // Pick relays that cover the most pks, capped
            val fbRelayEntries = relayToPks.entries
                .sortedByDescending { it.value.size }
                .take(MAX_PROFILE_FALLBACK_RELAYS)
            val fbRelayUrls = fbRelayEntries.map { it.key }
            val fbPks = fbRelayEntries.flatMap { it.value }.distinct()

            Log.w(TAG, "PROFFB: ${fbPks.size} pk(s) → ${fbRelayUrls.size} relay(s): " +
                fbPks.joinToString(",") { it.take(8) } + " → " +
                fbRelayUrls.joinToString(",") { it.removePrefix("wss://").removeSuffix("/") })

            val fbSubId = "prof-fb-${System.nanoTime()}"
            val fbReq = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(fbSubId))
                add(buildJsonObject {
                    put("kinds", buildJsonArray {
                        add(JsonPrimitive(0))
                        add(JsonPrimitive(10002))
                    })
                    put("authors", buildJsonArray { fbPks.forEach { add(JsonPrimitive(it)) } })
                })
            }.toString()

            sendOneShotBatch(fbRelayUrls, listOf(fbReq), listOf(fbSubId))
            delay(COLD_LANE_FLUSH_MS)

            // Negative-cache remaining incomplete avatars; log successes for field validation
            val fbNow = System.currentTimeMillis()
            val finalIncomplete = fbPks.filter { profileMissingPicture(mes.getUserEntity(it)) }
            val resolved = fbPks.size - finalIncomplete.size
            if (resolved > 0) Log.w(TAG, "PROFFB: $resolved pk(s) resolved avatar via fallback")
            finalIncomplete.forEach { profileFallbackNegCache[it] = fbNow }
            if (finalIncomplete.isNotEmpty()) {
                Log.w(TAG, "PROFFB: ${finalIncomplete.size} pk(s) still incomplete after fallback")
            }
        }
        Log.d(TAG, "Fetching ${novel.size} profiles+relaylists → ${targetUrls.size} relay(s) (${pubkeys.size - novel.size} deduped)")
    }

    /**
     * Fetch profiles using nprofile relay hints. Connects to hinted relays (if not already)
     * and sends targeted kind-0 REQs. Follows the same pattern as [fetchEventById] with hints.
     */
    fun fetchProfilesFromHints(pubkeyHints: Map<String, List<String>>) {
        if (pubkeyHints.isEmpty()) return
        val now = System.currentTimeMillis()
        // Dedup against recent fetches
        val novel = pubkeyHints.filter { (pk, _) ->
            val last = profileFetchAttempted[pk]
            last == null || (now - last) > 300_000
        }
        if (novel.isEmpty()) return

        val allHintUrls = novel.values.flatten().distinct()
        val pubkeys = novel.keys.toList()
        pubkeys.forEach { profileFetchAttempted[it] = now }
        val subId = "hint-profiles-${System.nanoTime()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(0)) })
                put("authors", buildJsonArray { pubkeys.forEach { add(JsonPrimitive(it)) } })
            })
        }.toString()
        val targetUrls = allHintUrls.mapNotNull { normalizeRelayUrl(it) }
        scope.launch { sendOneShotBatch(targetUrls, listOf(req), listOf(subId)) }
        Log.d(TAG, "fetchProfilesFromHints: ${pubkeys.size} profiles → ${targetUrls.size} hinted relay(s)")
    }

    /**
     * Fetch profiles directly from specific relay URLs (e.g. the source relays of visible events).
     * Bypasses [profileFetchAttempted] dedup so it can run alongside [fetchProfiles].
     * Uses its own lightweight dedup to avoid hammering the same relay within 60 s.
     */
    private val sourceProfileAttempted = ConcurrentHashMap<String, Long>()

    fun fetchProfilesFromSourceRelays(pubkeys: List<String>, relayUrls: List<String>) {
        if (pubkeys.isEmpty() || relayUrls.isEmpty()) return
        val now = System.currentTimeMillis()
        val novel = pubkeys.filter { pk ->
            val last = sourceProfileAttempted[pk]
            last == null || (now - last) > 60_000
        }
        if (novel.isEmpty()) return
        novel.forEach { sourceProfileAttempted[it] = now }

        val subId = "src-profiles-${System.nanoTime()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(0)) })
                put("authors", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
            })
        }.toString()
        val targetUrls = relayUrls.mapNotNull { normalizeRelayUrl(it) }
        scope.launch { sendOneShotBatch(targetUrls, listOf(req), listOf(subId)) }
        Log.d(TAG, "fetchProfilesFromSourceRelays: ${novel.size} profiles → ${targetUrls.size} source relay(s)")
    }

    /**
     * NIP-50 search: connect to [searchRelayUrls] (if not already) and send a REQ with the
     * "search" field. Results arrive via EventProcessor → MemoryEventStore as with any other subscription.
     *
     * Two filters are sent:
     *  - kind 0 (profiles) — drives the People tab
     *  - kind 1/30023 (notes + articles) — drives the Notes tab
     */
    fun searchNotes(rawSearchRelayUrls: List<String>, query: String, token: Long) {
        if (query.isBlank()) return
        val searchRelayUrls = rawSearchRelayUrls.mapNotNull { normalizeRelayUrl(it) }

        val profileSubId = "search-profiles-$token"
        val notesSubId = "search-notes-$token"
        _activeOneShotSubs.add(profileSubId)
        _activeOneShotSubs.add(notesSubId)

        val profileReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(profileSubId))
            add(buildJsonObject {
                put("kinds",  buildJsonArray { add(JsonPrimitive(0)) })
                put("search", JsonPrimitive(query))
                put("limit",  JsonPrimitive(50))
            })
        }.toString()

        val notesReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(notesSubId))
            add(buildJsonObject {
                put("kinds",  buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(30023)) })
                put("search", JsonPrimitive(query))
                put("limit",  JsonPrimitive(50))
            })
        }.toString()

        for (url in searchRelayUrls) {
            if (relayCapabilitiesStore.shouldSkip(url)) continue
            scope.launch {
                val conn = getOrCreateConnection(url) ?: return@launch
                conn.send(profileReq)
                conn.send(notesReq)
                Log.d(TAG, "Search REQs sent to $url")
            }
        }
        // Safety-net timeout: unconditionally force-close after 10s.
        // closeSearch is idempotent — harmless if EOSE already closed all relays.
        // Must be unconditional because _activeOneShotSubs.remove fires per-relay
        // on EOSE: 4/5 relays completing removes the sub-ID, but the 5th may still
        // be streaming. The old stillActive check was dead code in that scenario.
        searchTimeoutJobs[token] = scope.launch {
            delay(SEARCH_TIMEOUT_MS)
            Log.d(TAG, "searchNotes: 10s timeout elapsed for token=$token, issuing force-close")
            closeSearch(token)
        }

        Log.d(TAG, "Queued NIP-50 search for \"$query\" to ${searchRelayUrls.size} relay(s) [token=$token]")
    }

    /**
     * Close an active search by sending CLOSE frames for both sub-IDs (search-profiles
     * and search-notes) to every connected relay. Called by SearchViewModel when a new
     * query supersedes the previous one or when the search screen is dismissed.
     */
    fun closeSearch(token: Long) {
        searchTimeoutJobs.remove(token)?.cancel()

        val profileSubId = "search-profiles-$token"
        val notesSubId = "search-notes-$token"
        _activeOneShotSubs.remove(profileSubId)
        _activeOneShotSubs.remove(notesSubId)

        val closeProfile = """["CLOSE","$profileSubId"]"""
        val closeNotes = """["CLOSE","$notesSubId"]"""

        var relayCount = 0
        for (conn in connections.values) {
            if (conn.isConnected) {
                conn.send(closeProfile)
                conn.send(closeNotes)
                relayCount++
            }
        }
        Log.d(TAG, "closeSearch: sent CLOSE for token=$token on $relayCount relay(s)")
    }

    /**
     * Search for notes matching a hashtag via NIP-12 generic tag query (`#t` filter).
     * Sends a single sub (search-notes-{token}) with `{"kinds":[1],"#t":["tag"],"limit":50}`.
     * Results flow through [searchResults] like [searchNotes].
     */
    fun searchHashtag(rawSearchRelayUrls: List<String>, tag: String, token: Long) {
        if (tag.isBlank()) return
        val searchRelayUrls = rawSearchRelayUrls.mapNotNull { normalizeRelayUrl(it) }

        val notesSubId = "search-notes-$token"
        _activeOneShotSubs.add(notesSubId)

        val notesReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(notesSubId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1)) })
                put("#t", buildJsonArray { add(JsonPrimitive(tag.lowercase())) })
                put("limit", JsonPrimitive(50))
            })
        }.toString()

        for (url in searchRelayUrls) {
            if (relayCapabilitiesStore.shouldSkip(url)) continue
            scope.launch {
                val conn = getOrCreateConnection(url) ?: return@launch
                conn.send(notesReq)
                Log.d(TAG, "Hashtag search REQ (#$tag) sent to $url")
            }
        }
        searchTimeoutJobs[token] = scope.launch {
            delay(SEARCH_TIMEOUT_MS)
            closeSearch(token)
        }
        Log.d(TAG, "Queued NIP-12 hashtag search for #$tag to ${searchRelayUrls.size} relay(s) [token=$token]")
    }

    /**
     * Fetch events older than [untilTimestamp] (Unix seconds) from the specified [relayUrls].
     * Used by pagination: caller sets `until` = oldest event's createdAt in the current list.
     */
    fun fetchOlderEvents(relayUrls: List<String>, untilTimestamp: Long) {
        val subId = "older-${System.currentTimeMillis()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(6))
                    add(JsonPrimitive(7))
                    add(JsonPrimitive(20))
                    add(JsonPrimitive(21))
                    add(JsonPrimitive(1068))
                    add(JsonPrimitive(30023))
                })
                put("until", JsonPrimitive(untilTimestamp))
                put("limit", JsonPrimitive(50))
            })
        }.toString()

        // Fallback when relayUrls is empty (e.g. Following feed): capped subset —
        // skip indexers (no general timeline content) and cap fan-out at 6.
        val targets = if (relayUrls.isEmpty()) {
            val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
                .mapNotNull { normalizeRelayUrl(it) }.toSet()
            connections.values.filter { it.url !in indexerUrls }.take(6)
        } else relayUrls.mapNotNull { connections[it] }
        targets.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "Fetching older events until $untilTimestamp from ${targets.size} relay(s)")
    }

    /**
     * Broadcast a signed event to all currently connected relays.
     *
     * [eventJson] must be the raw JSON object string for the event
     * (not the full ["EVENT", ...] array — this method wraps it).
     */
    /** Batch fetch events by ID. Deduped via in-flight Deferred map + negative cache. */
    fun fetchEventsByIds(eventIds: List<String>) {
        if (eventIds.isEmpty()) return
        // Filter: skip IDs in negative cache or already in-flight
        val novel = mutableListOf<String>()
        for (id in eventIds) {
            if (isEventUnresolved(id)) continue
            if (eventFetchInFlight.containsKey(id)) continue
            val d = CompletableDeferred<NostrEvent?>()
            if (eventFetchInFlight.putIfAbsent(id, d) == null) {
                novel.add(id)
                launchFetchMonitor(id, d)
            }
        }
        if (novel.isEmpty()) return
        trackInFlightPeak()
        val subId = "batch-events-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("ids", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
                put("kinds", buildJsonArray {
                    for (k in intArrayOf(0, 1, 6, 7, 20, 21, 1068, 30023)) add(JsonPrimitive(k))
                })
            })
        }.toString()
        // Broadened relay targeting: non-indexer relays first, then indexer relays
        // for coverage. Previously limited to 3 non-indexer relays which missed
        // events on less-replicated relays.
        // NOTE: do NOT exclude activeSingleRelayFeedUrl — targeted {"ids":[…]} fetches
        // never overlap the feed filter. The target event may live only on the feed relay
        // (bridged reposts). See fetchEventById for the same rationale.
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
            .mapNotNull { normalizeRelayUrl(it) }.toSet()
        val nonIndexer = connections.values.filter {
            it.url !in indexerUrls && !relayCapabilitiesStore.shouldSkip(it.url)
        }.shuffled()
        val indexer = connections.values.filter {
            it.url in indexerUrls && !relayCapabilitiesStore.shouldSkip(it.url)
        }
        val targets = (nonIndexer + indexer).take(6)
        if (targets.isEmpty()) {
            Log.d(TAG, "fetchEventsByIds: 0 targets (pool empty)")
            return
        }
        targets.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "fetchEventsByIds: ${novel.size} events → ${targets.size} relay(s)")
    }

    /** Single-ID overload — consults MES relay hints before broadcasting. */
    fun fetchEventById(eventId: String) {
        val hints = memoryEventStore.get().relayHintsForEvent(eventId).toList()
        if (hints.isNotEmpty()) {
            scope.launch { fetchEventById(eventId, hints) }
        } else {
            fetchEventsByIds(listOf(eventId))
        }
    }

    /** Fetch NIP-88 responses only when a poll card is visible. */
    fun fetchPollResponses(
        pollId: String,
        relayUrls: List<String>,
        until: Long? = null,
    ) {
        val targets = relayUrls.mapNotNull { normalizeRelayUrl(it) }
            .filter { it !in blockedUrls && !relayCapabilitiesStore.shouldSkip(it) }
            .distinct()
            .take(6)
        if (targets.isEmpty()) return
        val subId = "poll-responses-${System.nanoTime()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1018)) })
                put("#e", buildJsonArray { add(JsonPrimitive(pollId)) })
                until?.let { put("until", JsonPrimitive(it)) }
                put("limit", JsonPrimitive(500))
            })
        }.toString()
        _activeOneShotSubs.add(subId)

        val pooled = targets.filter { connections.containsKey(it) }
        pooled.forEach { url -> connections[url]?.let { sendOneShotToRelay(it, req) } }
        val ephemeral = targets - pooled.toSet()
        if (ephemeral.isNotEmpty()) {
            scope.launch { sendOneShotBatch(ephemeral, listOf(req), listOf(subId), timeoutMs = 8_000) }
        }
        Log.d(TAG, "fetchPollResponses: $pollId -> ${targets.size} relay(s)")
    }

    /**
     * Fetch events by ID from a SPECIFIC relay (source-relay or hint-relay targeting).
     *
     * Two modes:
     *  - default (`bypassDedup=false`): registers each id in `eventFetchInFlight`,
     *    skips ids already in flight or in the negative cache. Used by source-relay
     *    prefetch where a single fetch is sufficient.
     *  - `bypassDedup=true`: hint-relay retry path. Skips the in-flight guard so the
     *    fetch fires even when a prior broadcast already registered the ids — the
     *    point is to hit the hint relay specifically (which may not have been in the
     *    broadcast's target set). Still respects the negative cache: if a prior
     *    completed batch already determined the id is unresolvable, retrying won't
     *    help.
     *
     * Dispatches via [sendOneShotPooledOrEphemeral]: reuses a pooled connection if
     * one exists, otherwise opens an ephemeral WebSocket (no pool slot, no cap).
     * NEVER connectAndAwait — hint relays are transient and must not squat in the
     * pool (Slice 8: 192-relay hint fan-out exhausted the pool and starved feed).
     *
     * One REQ per call regardless of how many ids are passed — the wire form is
     * `{"ids":[...]}` so a list of N ids is a single subscription, not N.
     */
    suspend fun fetchEventsByIdsFromRelay(
        relayUrl: String,
        eventIds: List<String>,
        bypassDedup: Boolean = false,
    ) {
        if (eventIds.isEmpty()) return
        val normalized = normalizeRelayUrl(relayUrl) ?: return
        if (normalized in blockedUrls) return
        if (relayCapabilitiesStore.shouldSkip(normalized)) return
        // NOTE: do NOT exclude activeSingleRelayFeedUrl — targeted id-fetches
        // never overlap the feed filter (distinct subId, different filter shape).
        // Dedup BEFORE dispatch — don't open any connection (pooled or ephemeral)
        // for ids already in-flight, unresolved, or negative-cached.
        val novel = mutableListOf<String>()
        for (id in eventIds) {
            if (isEventUnresolved(id)) continue
            if (!bypassDedup) {
                if (eventFetchInFlight.containsKey(id)) continue
                val d = CompletableDeferred<NostrEvent?>()
                if (eventFetchInFlight.putIfAbsent(id, d) == null) {
                    novel.add(id)
                    launchFetchMonitor(id, d)
                }
            } else {
                novel.add(id)
            }
        }
        if (novel.isEmpty()) return
        trackInFlightPeak()
        val subId = if (bypassDedup) "hint-batch-${System.nanoTime()}"
                    else "prefetch-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("ids", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
                // Include kinds so relays that require them don't reject with
                // "filters must specify at least one kind" (purplepag.es, others).
                put("kinds", buildJsonArray {
                    for (k in intArrayOf(0, 1, 6, 7, 20, 21, 1068, 30023)) add(JsonPrimitive(k))
                })
            })
        }.toString()
        val pooled = connections.containsKey(normalized)
        sendOneShotPooledOrEphemeral(normalized, req, subId)
        if (bypassDedup) {
            Log.d(TAG, "fetchByIdsFromRelay (hint-batch): ${novel.size} events → $normalized (${if (pooled) "pooled" else "ephemeral"})")
        } else {
            Log.d(TAG, "prefetch: ${novel.size} events → $normalized (${if (pooled) "pooled" else "ephemeral"})")
        }
    }

    /**
     * Fetch a single event by ID using relay hints. Hints-first strategy:
     * when hints exist, connectAndAwait to ensure the WebSocket is open,
     * then send REQ only to hint relays — no broadcast fallback.
     * When no hints exist, sends to at most 6 random relays (non-indexer first).
     *
     * @param bypassDedup when true, skip the in-flight Deferred guard (but NOT
     *   the negative cache). Used by outbox fallback: the same event ID was already
     *   tried on the source relay, but we need to retry on the author's write relays.
     *   The negative cache still applies — if a prior completed batch already
     *   determined this event is unresolvable, retrying won't help.
     */
    suspend fun fetchEventById(eventId: String, relayHints: List<String>, bypassDedup: Boolean = false) {
        // Negative cache: always check, even for outbox retries. Within a single
        // hydrateRefs cycle the cache isn't populated yet (written after all phases).
        // Cross-cycle: prevents redundant outbox retries for known-unresolved refs.
        if (isEventUnresolved(eventId)) return

        if (!bypassDedup) {
            // In-flight dedup: another caller is already fetching this event
            if (eventFetchInFlight.containsKey(eventId)) return
            val d = CompletableDeferred<NostrEvent?>()
            if (eventFetchInFlight.putIfAbsent(eventId, d) != null) return // race lost
            launchFetchMonitor(eventId, d)
            trackInFlightPeak()
        }
        // bypassDedup callers (outbox phases) send REQs without registering in the map.
        // If a monitor is already running for this ID (from the initial broadcast),
        // the event arrival will still complete that Deferred.

        val subId = "hint-event-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("ids", buildJsonArray { add(JsonPrimitive(eventId)) })
                // Include kinds so relays that require them don't reject with
                // "filters must specify at least one kind" (purplepag.es, others).
                put("kinds", buildJsonArray {
                    for (k in intArrayOf(0, 1, 6, 7, 20, 21, 1068, 30023)) add(JsonPrimitive(k))
                })
            })
        }.toString()

        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
            .mapNotNull { normalizeRelayUrl(it) }.toSet()

        if (relayHints.isNotEmpty()) {
            // Hints-first: dispatch to hint relays via pooled reuse or ephemeral.
            // No connectAndAwait — transient hints must not occupy pool slots.
            // NOTE: do NOT exclude activeSingleRelayFeedUrl — a targeted {"ids":[id]}
            // fetch never overlaps the feed filter, and the target may live only there
            // (bridged Ditto reposts). The one-shot sub uses a distinct subId.
            val hintTargets = relayHints.mapNotNull { normalizeRelayUrl(it) }
                .filter { it !in indexerUrls && it !in blockedUrls && !relayCapabilitiesStore.shouldSkip(it) }
            if (hintTargets.isNotEmpty()) {
                hintTargets.forEach { url -> sendOneShotPooledOrEphemeral(url, req, subId) }
                Log.d(TAG, "fetchEventById: $eventId → ${hintTargets.size} hint relay(s) (pooled-or-ephemeral)")
                return
            }
        }

        // No hints (or all hints were indexer/blocked) — broadened fallback.
        val nonIndexer = connections.values.filter {
            it.url !in indexerUrls && !relayCapabilitiesStore.shouldSkip(it.url)
        }.shuffled()
        val indexer = connections.values.filter {
            it.url in indexerUrls && !relayCapabilitiesStore.shouldSkip(it.url)
        }
        val fallbackTargets = (nonIndexer + indexer).take(6)
        if (fallbackTargets.isEmpty()) {
            Log.d(TAG, "fetchEventById: $eventId → 0 targets (pool empty)")
            return
        }
        fallbackTargets.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "fetchEventById: $eventId → ${fallbackTargets.size} fallback relay(s) (no hints)")
    }

    /**
     * Targeted publish (H20c): send an event to a SPECIFIC relay set — the author's
     * own write relays — rather than broadcasting to every open socket. Two reasons:
     * the broadcast form spammed ~30 bystander relays per note (bandwidth) and leaked
     * note content to relays the user never configured (outbox-model violation + privacy).
     *
     * Pressing Post is explicit user intent, so any target that isn't already open is
     * connected here. [connectAndAwait] does NOT gate on isNetworkDown — a DNS-degraded
     * latch must never refuse a user-initiated publish (H20c: honest attempt beats
     * refused attempt; cf. H18.4b). Only the target relays receive the event; partial
     * success (≥1 relay accepts) is handled by the caller's per-relay OK tracking.
     */
    suspend fun publish(eventJson: String, targetRelays: List<String>) {
        val targets = targetRelays.mapNotNull { normalizeRelayUrl(it) }.distinct()
        if (targets.isEmpty()) {
            Log.w(TAG, "PUBLISH dispatch: no target relays — nothing sent")
            return
        }
        val parsed = NostrJson.parseToJsonElement(eventJson)
        val cmd = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(parsed)
        }.toString()
        val kind = runCatching { parsed.jsonObject["kind"]?.jsonPrimitive?.content }.getOrNull()

        // Connect any target that isn't already open — bypassing the degraded/offline
        // reconnect defer, because this is explicit user intent (H20c).
        val unopened = targets.filter { connections[it]?.isConnected != true }
        if (unopened.isNotEmpty()) {
            Log.w(TAG, "PUBLISH: connecting ${unopened.size} unopened write relay(s): $unopened")
            connectAndAwait(unopened, timeoutMs = 5_000)
        }

        // send() enqueues on OkHttp even while a socket is still handshaking (flushes on
        // open), so a slow-connecting target still receives the event within the caller's
        // OK deadline. false = no live socket → unreachable this attempt.
        var sent = 0
        val unreachable = mutableListOf<String>()
        for (url in targets) {
            val conn = connections[url]
            if (conn != null && conn.send(cmd)) sent++ else unreachable.add(url)
        }
        Log.w(TAG, "PUBLISH dispatch: kind=$kind targets=$targets sent=$sent" +
            if (unreachable.isNotEmpty()) " unreachable=$unreachable" else "")
    }

    /**
     * Register a callback for OK messages for a specific event ID.
     * Callback receives (relayUrl, success, message).
     */
    fun registerPublishCallback(eventId: String, callback: (String, Boolean, String) -> Unit) {
        publishOkCallbacks[eventId] = callback
    }

    /** Unregister a publish OK callback. */
    fun unregisterPublishCallback(eventId: String) {
        publishOkCallbacks.remove(eventId)
    }

    /**
     * Publish an event to specific relay URLs. Connects if not already connected.
     * Used for replaceable events (kind 0, 3, 10002) that target write + indexer relays.
     */
    fun publishToRelays(eventJson: String, rawRelayUrls: List<String>) {
        val relayUrls = rawRelayUrls.mapNotNull { normalizeRelayUrl(it) }
        val cmd = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(NostrJson.parseToJsonElement(eventJson))
        }.toString()

        // Send to already-connected relays immediately
        val sent = mutableSetOf<String>()
        for (url in relayUrls) {
            connections[url]?.let { it.send(cmd); sent.add(url) }
        }

        // Connect to remaining relays and send
        val remaining = relayUrls.filter { it !in sent }
        if (remaining.isNotEmpty()) {
            scope.launch {
                connect(remaining)
                delay(2_000)
                for (url in remaining) {
                    connections[url]?.send(cmd)
                }
            }
        }

        Log.d(TAG, "Published event to ${sent.size} connected + ${remaining.size} pending relay(s)")
    }

    /**
     * Fetch a thread: the event itself, replies, reactions, and zaps.
     * Separate REQs so each kind gets its own limit and they don't compete.
     */
    fun fetchThread(rawRelayUrls: List<String>, eventId: String) {
        val hintUrls = memoryEventStore.get().relayHintsForEvent(eventId)
            .mapNotNull { normalizeRelayUrl(it) }
        val relayUrls = (rawRelayUrls.mapNotNull { normalizeRelayUrl(it) }.toSet() + hintUrls).toList()
        val ts = System.currentTimeMillis()
        _activeOneShotSubs.add("thread-event-$ts")
        _activeOneShotSubs.add("thread-replies-$ts")
        _activeOneShotSubs.add("thread-reactions-$ts")
        _activeOneShotSubs.add("thread-zaps-$ts")

        // The event itself
        val eventReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("thread-event-$ts"))
            add(buildJsonObject {
                put("ids", buildJsonArray { add(JsonPrimitive(eventId)) })
            })
        }.toString()

        // Replies (kind 1 referencing this event)
        val repliesReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("thread-replies-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1)) })
                put("#e",    buildJsonArray { add(JsonPrimitive(eventId)) })
                put("limit", JsonPrimitive(200))
            })
        }.toString()

        // Reactions on this event and its replies
        val reactionsReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("thread-reactions-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(7)) })
                put("#e",    buildJsonArray { add(JsonPrimitive(eventId)) })
                put("limit", JsonPrimitive(100))
            })
        }.toString()

        // Zaps on this event and its replies
        val zapsReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("thread-zaps-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(9735)) })
                put("#e",    buildJsonArray { add(JsonPrimitive(eventId)) })
                put("limit", JsonPrimitive(100))
            })
        }.toString()

        for (url in relayUrls) {
            connections[url]?.let { conn ->
                sendOneShotToRelay(conn, eventReq)
                sendOneShotToRelay(conn, repliesReq)
                sendOneShotToRelay(conn, reactionsReq)
                sendOneShotToRelay(conn, zapsReq)
            }
        }
        Log.d(TAG, "Fetching thread + engagement for $eventId from ${relayUrls.size} relay(s)")
    }

    /**
     * Fetch comments on a long-form article by its coordinate (`30023:pk:d`).
     * ONE REQ with two OR'd filter objects: NIP-22 kind-1111 (uppercase `#A` root
     * scope) + legacy kind-1 (lowercase `#a`) — NOT `#e`. Ephemeral one-shot
     * (reaches relays even if not in the persistent pool); CLOSE after EOSE.
     */
    suspend fun fetchArticleComments(rawRelayUrls: List<String>, coord: String) {
        if (coord.isBlank()) return
        val hintUrls = memoryEventStore.get().relayHintsForEvent(coord).mapNotNull { normalizeRelayUrl(it) }
        val relayUrls = (rawRelayUrls.mapNotNull { normalizeRelayUrl(it) }.toSet() + hintUrls).toList()
        if (relayUrls.isEmpty()) return
        val subId = "article-comments-${System.currentTimeMillis()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1)) })       // legacy comments
                put("#a", buildJsonArray { add(JsonPrimitive(coord)) })
                put("limit", JsonPrimitive(200))
            })
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1111)) })    // NIP-22 comments
                put("#A", buildJsonArray { add(JsonPrimitive(coord)) })
                put("limit", JsonPrimitive(200))
            })
        }.toString()

        val eoseDeferred = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = eoseDeferred
        sendOneShotBatch(relayUrls, listOf(req), listOf(subId))
        val eosed = withTimeoutOrNull(8_000L) { eoseDeferred.await() } != null
        if (!eosed) cleanupOneShotSub(subId)
        Log.d(TAG, "Fetching comments for $coord from ${relayUrls.size} relay(s)")
    }

    /**
     * Fetch a long-form article by its addressable coordinate (author + d-tag) so a
     * quoted/embedded `naddr` reference can render the canonical article card.
     * One-shot; CLOSE after EOSE.
     */
    suspend fun fetchArticleByCoord(rawRelayUrls: List<String>, author: String, dTag: String) {
        if (author.isBlank()) return
        val relayUrls = rawRelayUrls.mapNotNull { normalizeRelayUrl(it) }.distinct()
        if (relayUrls.isEmpty()) return
        val subId = "article-addr-${System.currentTimeMillis()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(30023)) })
                put("authors", buildJsonArray { add(JsonPrimitive(author)) })
                put("#d", buildJsonArray { add(JsonPrimitive(dTag)) })
                put("limit", JsonPrimitive(2))
            })
        }.toString()
        val eoseDeferred = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = eoseDeferred
        sendOneShotBatch(relayUrls, listOf(req), listOf(subId))
        val eosed = withTimeoutOrNull(8_000L) { eoseDeferred.await() } != null
        if (!eosed) cleanupOneShotSub(subId)
    }

    /**
     * Fetch replies to a set of article comments (kind 1 or 1111 referencing the
     * comment via `#e`) — descendants that may carry no `#a`/`#A` article tag and
     * so aren't caught by fetchArticleComments. One-shot; CLOSE after EOSE.
     */
    suspend fun fetchCommentReplies(rawRelayUrls: List<String>, parentIds: List<String>) {
        if (parentIds.isEmpty()) return
        val relayUrls = rawRelayUrls.mapNotNull { normalizeRelayUrl(it) }.distinct()
        if (relayUrls.isEmpty()) return
        val subId = "comment-replies-${System.currentTimeMillis()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(1111)) })
                put("#e", buildJsonArray { parentIds.take(200).forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(200))
            })
        }.toString()
        val eoseDeferred = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = eoseDeferred
        sendOneShotBatch(relayUrls, listOf(req), listOf(subId))
        val eosed = withTimeoutOrNull(8_000L) { eoseDeferred.await() } != null
        if (!eosed) cleanupOneShotSub(subId)
    }

    /**
     * Fetch posts by a single author: kinds 1, 6, 20, 21, 30023.
     * One-shot subscription — CLOSE is sent after EOSE.
     */
    fun fetchUserPosts(pubkey: String, relayUrls: List<String> = emptyList()) {
        val ts = System.currentTimeMillis()
        val subIds = listOf("user-posts-$ts", "user-longform-$ts", "user-engagement-$ts")

        // Posts by this author
        val postsReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("user-posts-$ts"))
            add(buildJsonObject {
                // 30023 deliberately absent — user-longform below is the sole
                // kind-30023 path (own limit so kind-1 doesn't crowd articles out).
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(6))
                    add(JsonPrimitive(20))
                    add(JsonPrimitive(21))
                    add(JsonPrimitive(1068))
                })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                put("limit", JsonPrimitive(200))
            })
        }.toString()

        // Dedicated longform articles subscription (own limit so kind-1 doesn't crowd them out)
        val longformReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("user-longform-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(30023)) })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                put("limit", JsonPrimitive(50))
            })
        }.toString()

        // Reactions and zaps targeting this author (#p tag)
        val engagementReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("user-engagement-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(7)); add(JsonPrimitive(9735)) })
                put("#p", buildJsonArray { add(JsonPrimitive(pubkey)) })
                put("limit", JsonPrimitive(100))
            })
        }.toString()

        val reqs = listOf(postsReq, longformReq, engagementReq)
        val targetUrls = if (relayUrls.isNotEmpty()) {
            relayUrls.mapNotNull { normalizeRelayUrl(it) }
        } else {
            connections.keys.toList()
        }
        scope.launch { sendOneShotBatch(targetUrls, reqs, subIds) }
        Log.d(TAG, "Fetching user posts + engagement for $pubkey → ${targetUrls.size} relay(s)")
    }

    /**
     * Pagination for user profile view.
     * Fetches posts by [pubkey] older than [untilTimestamp].
     * One-shot subscription — closes on EOSE (prefix "older-" matches isOneShotSubscription).
     */
    fun fetchOlderPosts(pubkey: String, untilTimestamp: Long, relayUrls: List<String> = emptyList()) {
        val subId = "older-user-${System.currentTimeMillis()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(6))
                    add(JsonPrimitive(20))
                    add(JsonPrimitive(21))
                    add(JsonPrimitive(1068))
                    add(JsonPrimitive(30023))
                })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                put("until", JsonPrimitive(untilTimestamp))
                put("limit", JsonPrimitive(200))
            })
        }.toString()
        val targetUrls = if (relayUrls.isNotEmpty()) {
            relayUrls.mapNotNull { normalizeRelayUrl(it) }
        } else {
            connections.keys.toList()
        }
        scope.launch { sendOneShotBatch(targetUrls, listOf(req), listOf(subId)) }
        Log.d(TAG, "Fetching older posts for $pubkey until $untilTimestamp → ${targetUrls.size} relay(s)")
    }

    /**
     * Reconnect any relay that has dropped its WebSocket.
     * Called when the app returns to the foreground.
     * Creates new RelayConnection instances (Channel can't be reused after close).
     */
    fun reconnectAll() {
        val dropped = connections.entries
            .filter { it.value.state.value == RelayState.DISCONNECTED ||
                      it.value.state.value == RelayState.FAILED }
            .map { it.key }
        for (url in dropped) {
            reconnectWithBackoff(url)
        }
        if (dropped.isNotEmpty()) Log.d(TAG, "Reconnecting ${dropped.size} relay(s)")
    }

    /**
     * Reconnect a single relay with exponential backoff.
     * Guard: AtomicBoolean per URL prevents concurrent reconnect attempts.
     */
    private fun reconnectWithBackoff(url: String, attempt: Int = 0) {
        // Only block reconnect for permanent policy rejections (restricted).
        // Transport strikes heal on successful connection — let the 8-attempt
        // backoff handle transient failures without the strike system killing it.
        val caps = relayCapabilitiesStore.get(url)
        if (caps?.restricted == true) {
            Log.w(TAG, "Skipping reconnect for restricted relay $url")
            connections.remove(url)?.close()
            return
        }
        // Don't hammer a dead pipe — defer until the network recovers.
        if (relayCapabilitiesStore.isNetworkDown) {
            pendingReconnect.add(url)
            Log.w(TAG, "reconnectWithBackoff: network down, deferring $url (${pendingReconnect.size} pending)")
            return
        }
        val guard = reconnecting.getOrPut(url) { AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) return

        scope.launch {
            try {
                if (attempt > 0) {
                    val delayMs = minOf(1000L * (1L shl minOf(attempt - 1, 4)), 30_000L)
                    Log.d(TAG, "Backoff $url: attempt $attempt, delay ${delayMs}ms")
                    delay(delayMs)
                }

                // Re-check after delay — network may have gone down during backoff
                if (relayCapabilitiesStore.isNetworkDown) {
                    pendingReconnect.add(url)
                    guard.set(false)
                    Log.w(TAG, "reconnectWithBackoff: network down after delay, deferring $url")
                    return@launch
                }

                // Map-before-close: put new entry first so listenForEvents.finally
                // on the old conn sees identity mismatch and skips reconnect
                val conn = RelayConnection(url, okHttpClient, relayCapabilitiesStore)
                val old = connections.put(url, conn)
                authenticatedRelays.remove(url)
                optimisticAuthUsed.remove(url)
                pendingChallenges.remove(url)
                authFailedRelays.remove(url)
                authRejectionStreak.remove(url)
                authUnavailableRelays.remove(url)
                pendingAuthEventIds.values.removeAll { it == url }
                old?.close()
                connectionLastActivity[url] = System.currentTimeMillis()
                conn.connect()

                // Wait briefly for connection to establish
                var waited = 0
                while (conn.state.value == RelayState.CONNECTING && waited < 5000) {
                    delay(100)
                    waited += 100
                }

                if (conn.state.value == RelayState.CONNECTED) {
                    guard.set(false)
                    updateConnectionStates()
                    _onRelayReconnected.tryEmit(url)
                    // Resend persistent own-mute-live subscription if this relay carries it
                    if (url in liveMuteSubRelays) {
                        liveMuteSubReq?.let { conn.send(it) }
                    }
                    // Resend persistent notification subscription if this relay carries it
                    if (url in liveNotifSubRelays) {
                        liveNotifSubReq?.let { conn.send(it) }
                    }
                    scope.launch { listenForEvents(conn) }
                    Log.w(TAG, "Reconnected $url (attempt=$attempt)")
                } else {
                    guard.set(false)
                    if (attempt < 8) {
                        reconnectWithBackoff(url, attempt + 1)
                    } else {
                        Log.w(TAG, "Giving up reconnection to $url after $attempt attempts")
                    }
                }
            } catch (e: Exception) {
                guard.set(false)
                Log.w(TAG, "Reconnect failed for $url: ${e.message}")
                if (attempt < 8) {
                    reconnectWithBackoff(url, attempt + 1)
                }
            }
        }
    }

    /**
     * NIP-42: Sign and send an AUTH response for the given relay challenge.
     * A fresh challenge supersedes any prior auth — the relay is the authority
     * on our auth state, not our local set.
     */
    private fun handleAuthChallenge(conn: RelayConnection, challenge: String) {
        val url = conn.url
        val previousChallenge = pendingChallenges.put(url, challenge)

        if (url in authUnavailableRelays) {
            Log.d(TAG, "AUTH: $url marked unavailable, not retrying")
            return
        }

        // Some relays repeat the same connection-scoped challenge for every
        // REQ. Once that exact challenge has completed, signing it again adds
        // no authority and only burns signer/CPU/radio work.
        if (previousChallenge == challenge && url in authenticatedRelays) {
            Log.d(TAG, "AUTH: duplicate completed challenge from $url — ignoring")
            return
        }

        // A fresh challenge supersedes any prior (possibly optimistic) auth.
        if (url in authenticatedRelays) {
            if (optimisticAuthUsed.remove(url)) {
                val streak = authRejectionStreak.merge(url, 1, Int::plus) ?: 1
                Log.w(TAG, "AUTH: $url re-challenged after optimistic completion (streak=$streak)")
            }
            Log.w(TAG, "AUTH: re-challenged by $url — clearing stale auth, re-authenticating")
            authenticatedRelays.remove(url)
        }

        if (!authInFlight.add(url)) {
            Log.w(TAG, "AUTH: already in flight for $url, skipping")
            return
        }

        scope.launch {
            try {
                // Use Quartz's normalizer — it adds trailing slash for root-path
                // relays (wss://host/ not wss://host), matching how relays
                // validate the relay tag in NIP-42 auth events.
                val normalizedUrl = RelayUrlNormalizer.normalize(url)
                val template = RelayAuthEvent.build(normalizedUrl, challenge)
                val signed = signingManager.sign(template)

                if (signed == null) {
                    Log.w(TAG, "AUTH: signing failed for $url (signer returned null)")
                    authInFlight.remove(url)
                    return@launch
                }

                val authJson = """["AUTH",${signed.toJson()}]"""
                val sent = conn.send(authJson)

                if (sent) {
                    pendingAuthEventIds[signed.id] = url
                    Log.w(TAG, "AUTH: sent auth response to $url (eventId=${signed.id.take(8)}…)")

                    // Optimistic fallback ONLY for relays with a clean record.
                    // Once a relay has rejected us (streak > 0), require a real OK.
                    val streak = authRejectionStreak[url] ?: 0
                    if (streak == 0) {
                        scope.launch {
                            delay(10_000)
                            if (pendingAuthEventIds.remove(signed.id) != null) {
                                Log.w(TAG, "AUTH: OK timeout for $url — optimistic auth (clean record)")
                                optimisticAuthUsed.add(url)
                                completeAuth(conn, url, real = false)
                            }
                        }
                    } else {
                        // No optimism — require real OK. Clean up after grace period.
                        scope.launch {
                            delay(10_000)
                            if (pendingAuthEventIds.remove(signed.id) != null) {
                                authInFlight.remove(url)
                                val newStreak = authRejectionStreak.merge(url, 1, Int::plus) ?: 1
                                if (newStreak >= MAX_AUTH_NO_OK_STREAK) {
                                    authUnavailableRelays.add(url)
                                    _relayAuthUnavailable.tryEmit(url)
                                    Log.w(TAG, "AUTH: no OK for $url (streak=$newStreak) — marking unavailable")
                                } else {
                                    Log.w(TAG, "AUTH: no OK for $url (streak=$newStreak), not optimistic")
                                }
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "AUTH: failed to send auth to $url (connection closed?)")
                    authInFlight.remove(url)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AUTH: error authenticating to $url", e)
                authInFlight.remove(url)
            }
        }
    }

    /**
     * Handle ["OK", "<event-id>", <success>, "<message>"] from relay.
     * Used for NIP-42 auth confirmation.
     */
    private fun handleOk(conn: RelayConnection, raw: String) {
        try {
            val arr = NostrJson.parseToJsonElement(raw).jsonArray
            val eventId = arr[1].jsonPrimitive.content
            val success = arr[2].jsonPrimitive.boolean
            val message = arr.getOrNull(3)?.jsonPrimitive?.content ?: ""

            // Notify publish tracker if registered
            publishOkCallbacks[eventId]?.invoke(conn.url, success, message)

            // Check if this OK is for a pending auth event
            val url = pendingAuthEventIds.remove(eventId) ?: return
            if (success) {
                Log.w(TAG, "AUTH OK: relay $url accepted auth (eventId=${eventId.take(8)}…)")
                completeAuth(conn, url, real = true)
            } else {
                Log.w(TAG, "AUTH REJECTED: relay $url rejected auth: $message")
                authInFlight.remove(url)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse OK message from ${conn.url}: ${raw.take(100)}", e)
        }
    }

    /**
     * Mark relay as authenticated and replay all live subs.
     * @param real true when confirmed by relay OK; false for optimistic timeout.
     *             Real OK resets the rejection streak; optimistic does not.
     */
    private fun completeAuth(conn: RelayConnection, url: String, real: Boolean) {
        authenticatedRelays.add(url)
        authInFlight.remove(url)
        if (real) {
            authRejectionStreak.remove(url)
            optimisticAuthUsed.remove(url)
        }
        _onRelayReconnected.tryEmit(url)
        Log.w(TAG, "AUTH: completed for $url (real=$real) — notified subscribers")
    }

    // ── Reconnect signal ──────────────────────────────────────────────────

    /** Emitted when a relay reconnects or completes auth. Multiple subscribers
     *  (RelayBrowseSession, Subscription) collect this to replay their subs. */
    private val _onRelayReconnected = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val onRelayReconnected: SharedFlow<String> = _onRelayReconnected.asSharedFlow()

    /** Send a message to a specific relay by URL. Returns false if the connection doesn't exist. */
    override fun sendToRelay(url: String, msg: String): Boolean {
        val normalized = normalizeRelayUrl(url) ?: return false
        return connections[normalized]?.send(msg) == true
    }

    /**
     * Placeholder for future disconnect logic. In this PR browse CLOSE is enough;
     * connections may be reused by outbox routing or other consumers.
     */
    fun releaseIfUnused(url: String) {
        val purposes = connectionPurposes[url]
        if (purposes != null && purposes.isNotEmpty()) return  // still in use
        val lastActivity = connectionLastActivity[url] ?: 0L
        if (System.currentTimeMillis() - lastActivity < 60_000L) return  // recent
        // Map-before-close: remove first so listenForEvents.finally sees identity mismatch
        val conn = connections.remove(url) ?: return
        connectionLastActivity.remove(url)
        connectionPurposes.remove(url)
        conn.close()
        Log.d(TAG, "Released unused connection: $url")
    }

    fun disconnectAll() {
        // Map-before-close: snapshot then clear so listenForEvents.finally sees empty map
        val snapshot = ArrayList(connections.values)
        connections.clear()
        connectionPurposes.clear()
        profileFetchAttempted.clear()
        // Complete all in-flight monitors so they clean up immediately
        eventFetchInFlight.values.forEach { it.complete(null) }
        eventFetchInFlight.clear()
        missingRefCache.clear()
        authenticatedRelays.clear()
        authInFlight.clear()
        pendingChallenges.clear()
        authFailedRelays.clear()
        pendingAuthEventIds.clear()
        authRejectionStreak.clear()
        optimisticAuthUsed.clear()
        authUnavailableRelays.clear()
        relayOneShotCount.clear()
        relayReqQueue.clear()
        connectionLastActivity.clear()
        // Close after all maps are cleared
        snapshot.forEach { it.close() }
        Log.d(TAG, "disconnectAll: all connections, purposes, and auth state cleared")
    }
}
