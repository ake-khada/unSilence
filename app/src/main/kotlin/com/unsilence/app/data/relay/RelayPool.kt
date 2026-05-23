package com.unsilence.app.data.relay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
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
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RelayPool"

/** A search result correlated with the token of the search session that produced it. */
data class SearchResult(val token: Long, val eventId: String)

/** Why a relay connection exists — a relay can hold multiple purposes simultaneously. */
enum class ConnectionPurpose { PERSISTENT, BROWSE }

/**
 * Manages multiple relay WebSocket connections for the global feed.
 *
 * Architecture rule: events flow Relay → EventProcessor → Room.
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
) : RelayTransport, ReconnectSource {
    // WebSocket consume loops MUST not be starved by snapshot restore or
    // other heavy IO. limitedParallelism(8) reserves dedicated threads for
    // inbound message processing.
    private val wsDispatcher = Dispatchers.IO.limitedParallelism(8)
    private val scope = CoroutineScope(SupervisorJob() + wsDispatcher)
    private val connections = ConcurrentHashMap<String, RelayConnection>()
    private val reconnecting = ConcurrentHashMap<String, AtomicBoolean>()
    /** Cached blocked relay URLs, refreshed before each connect(). */
    @Volatile private var blockedUrls: Set<String> = emptySet()

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
    private val profileFetchAttempted = ConcurrentHashMap<String, Long>()

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
        const val POOL_SWEEP_CAP = 20
        const val RATE_LIMIT_MAX_TOKENS = 5
        const val RATE_LIMIT_REFILL_MS = 1000L
        const val RATE_LIMIT_COOLDOWN_MS = 30_000L
        const val SEARCH_TIMEOUT_MS = 10_000L
        const val RELAY_MONITOR_URL = "wss://relay.nostr.watch"
        const val RELAY_MONITOR_PUBKEY =
            "9bbbb845e5b6c831c29789900769843ab43bb5047abe697870cb50b6fc9bf923"
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
        conn.send(req)
    }

    /**
     * Flush queued REQs for a relay after a slot frees up.
     */
    private fun flushRelayQueue(conn: RelayConnection) {
        val count = relayOneShotCount[conn.url] ?: return
        val queue = relayReqQueue[conn.url] ?: return
        while (count.get() < MAX_CONCURRENT_REQS_PER_RELAY) {
            val req = queue.poll() ?: break
            count.incrementAndGet()
            conn.send(req)
            Log.d(TAG, "Flushed queued REQ on ${conn.url} (${count.get()}/$MAX_CONCURRENT_REQS_PER_RELAY active)")
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
        Log.d(TAG, "Pool: total=${connections.size} $purposeCounts")
    }

    /**
     * Try to evict one idle BROWSE connection to make room for a new one.
     * Returns true if a connection was evicted.
     */
    private fun evictIdleConnection(): Boolean {
        val now = System.currentTimeMillis()
        // Only BROWSE-only connections are evictable. PERSISTENT is exempt.
        val candidate = connections.entries
            .filter { (url, _) ->
                !hasPurpose(url, ConnectionPurpose.PERSISTENT) &&
                hasPurpose(url, ConnectionPurpose.BROWSE)
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

    // Evict stale entries every 5 minutes to prevent unbounded growth.
    init {
        scope.launch {
            while (true) {
                delay(300_000)
                val cutoff = System.currentTimeMillis() - 300_000
                // eventFetchInFlight: self-cleaning via launchFetchMonitor finally blocks
                missingRefCache.entries.removeIf { it.value < cutoff } // same 5-min TTL
                profileFetchAttempted.entries.removeIf { it.value < cutoff }
            }
        }
        // Periodic pool state logging + connection sweep — every 60s.
        scope.launch {
            while (true) {
                delay(60_000)
                logPoolState()
                // Sweep unused connections
                for (url in connections.keys.toList()) {
                    releaseIfUnused(url)
                }
                // Hard cap: if pool > 20, force-close oldest by activity
                if (connections.size > POOL_SWEEP_CAP) {
                    val byActivity = connections.keys
                        .sortedBy { connectionLastActivity[it] ?: 0L }
                    val toClose = connections.size - POOL_SWEEP_CAP
                    for (url in byActivity.take(toClose)) {
                        connections[url]?.close()
                        connections.remove(url)
                        connectionPurposes.remove(url)
                        connectionLastActivity.remove(url)
                        Log.w(TAG, "Pool over cap, force-closed: $url")
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

    private fun updateConnectionStates() {
        _connectionStates.value = connections.mapValues { it.value.state.value }
    }

    /** Clear transient caches. Called on logout. */
    fun clearCaches() {
        profileFetchAttempted.clear()
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
            if (!canOpenNewConnection()) continue
            val candidate = RelayConnection(url, okHttpClient)
            val existing = connections.putIfAbsent(url, candidate)
            if (existing != null) continue
            connectionLastActivity[url] = System.currentTimeMillis()
            candidate.connect()
            scope.launch { listenForEvents(candidate) }
            newConns.add(candidate)
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
    ) {
        val excluded = activeSingleRelayFeedUrl
        val normalized = urls.mapNotNull { normalizeRelayUrl(it) }.distinct()
            .filter { it !in blockedUrls && it != excluded }
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

        Log.d(TAG, "sendOneShotBatch: ${normalized.size} urls → ${reused.size} reused, ${ephemeral.size} ephemeral")

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
                async { openEphemeral(url, reqs, subIds.toSet(), timeoutMs) }
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

        val conn = RelayConnection(url, okHttpClient)
        try {
            conn.connect()
            // Wait for WebSocket ready (max 2s)
            val state = withTimeoutOrNull(2_000) {
                conn.state.first {
                    it == RelayState.CONNECTED || it == RelayState.FAILED || it == RelayState.DISCONNECTED
                }
            }
            if (state != RelayState.CONNECTED) {
                Log.d(TAG, "Ephemeral connect failed: $url (state=$state)")
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
            Log.d(TAG, "Ephemeral complete: $url ($eosed/${subIds.size} subs EOSE'd)")
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
            if (!canOpenNewConnection()) continue
            val candidate = RelayConnection(url, okHttpClient)
            val existing = connections.putIfAbsent(url, candidate)
            if (existing != null) continue
            connectionLastActivity[url] = System.currentTimeMillis()
            scope.launch {
                candidate.connect()
                listenForEvents(candidate)
            }
        }
        Log.d(TAG, "Pool has ${connections.size} connections")
    }

    private suspend fun listenForEvents(conn: RelayConnection) {
        try {
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
                        Log.d(TAG, "AUTH challenge from ${conn.url}: ${challenge.take(20)}…")
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
                        if (reason.startsWith("auth-required")) {
                            Log.d(TAG, "CLOSED auth-required for sub '$closedSubId' on ${conn.url}: $reason")
                            val challenge = pendingChallenges[conn.url]
                            if (challenge != null && conn.url !in authenticatedRelays) {
                                handleAuthChallenge(conn, challenge)
                            } else if (conn.url in authenticatedRelays) {
                                // Already authed — notify browse session to resend.
                                if (closedSubId.startsWith("browse-")) {
                                    _onRelayReconnected.tryEmit(conn.url)
                                    Log.d(TAG, "Notified subscribers to resend closed sub '$closedSubId' on ${conn.url}")
                                }
                            } else if (conn.url !in authFailedRelays) {
                                Log.w(TAG, "CLOSED auth-required for '$closedSubId' on ${conn.url} but no challenge cached (suppressing future warnings)")
                                authFailedRelays.add(conn.url)
                            }
                        } else if (reason.contains("rate-limit", ignoreCase = true) ||
                               reason.contains("too many", ignoreCase = true)) {
                            markRelayRateLimited(conn.url)
                            Log.w(TAG, "CLOSED rate-limited sub '$closedSubId' on ${conn.url}: $reason")
                        } else {
                            Log.d(TAG, "CLOSED sub '$closedSubId' on ${conn.url}: $reason")
                        }
                        // Count this relay as done for EOSE coverage — a CLOSED relay
                        // won't send EOSE, so don't let it force a full timeout.
                        if (isOneShotSubscription(closedSubId)) {
                            recordOneShotRelayCoverage(closedSubId, conn.url)
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
        } catch (e: Exception) {
            Log.w(TAG, "Stream closed for ${conn.url}: ${e.message}")
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
            conn.send("""["CLOSE","$subId"]""")
            // Record this relay as done; complete deferred when all targets covered
            recordOneShotRelayCoverage(subId, conn.url)
            // Free per-relay slot and flush queued REQs
            relayOneShotCount[conn.url]?.let { count ->
                val prev = count.getAndUpdate { if (it > 0) it - 1 else 0 }
                if (prev > 0) flushRelayQueue(conn)
            }
            Log.d(TAG, "CLOSE sent for one-shot sub '$subId' on ${conn.url}")
        }
    }

    /** Remove a one-shot sub from all tracking maps (EOSE callbacks + coverage). */
    internal fun cleanupOneShotSub(subId: String) {
        oneShotEoseCallbacks.remove(subId)
        oneShotSubTargets.remove(subId)
        oneShotSubEosed.remove(subId)
    }

    /**
     * Record a relay as done for a one-shot sub. Completes the EOSE deferred
     * when all target relays have responded (EOSE or CLOSED).
     * Falls back to first-EOSE if no target set was registered.
     */
    private fun recordOneShotRelayCoverage(subId: String, relayUrl: String) {
        val targets = oneShotSubTargets[subId]
        if (targets == null) {
            // No target set registered — fall back to old behavior (complete on first)
            oneShotEoseCallbacks.remove(subId)?.complete(Unit)
            return
        }
        val eosed = oneShotSubEosed.computeIfAbsent(subId) { ConcurrentHashMap.newKeySet() }
        eosed.add(relayUrl)
        val covered = eosed.size
        val total = targets.size
        Log.d(TAG, "one-shot '$subId' coverage $covered/$total")
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
    suspend fun sendCount(relayUrl: String, filter: JsonObject): Long? =
        withContext(Dispatchers.IO) {
            try {
                val subId = "count-${System.nanoTime()}"
                val countRequest = buildJsonArray {
                    add(JsonPrimitive("COUNT"))
                    add(JsonPrimitive(subId))
                    add(filter)
                }.toString()

                val conn = connections[relayUrl] ?: return@withContext null

                val deferred = CompletableDeferred<Long?>()
                countCallbacks[subId] = deferred

                conn.send(countRequest)

                withTimeoutOrNull(10_000) { deferred.await() }
                    .also { countCallbacks.remove(subId) }
            } catch (_: Exception) { null }
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

                val tagsJson = withTimeoutOrNull(10_000) { deferred.await() }
                    ?: run { eventTagsCallbacks.remove(subId); return@withContext null }
                eventTagsCallbacks.remove(subId)

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
     * Send a one-time REQ for the user's kind 3 (follow list) to all connected relays.
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
        connections.values.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "Fetching kind 3 for $pubkeyHex from ${connections.size} relay(s)")
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
        Log.d(TAG, "Fetching NIP-51 relay ecosystem for ${pubkeyHex.take(8)}… from ${indexerRelayUrls.size} indexers + ${writeRelayUrls.size} write relays")
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

    // ── Relay health fetch orchestrators ──────────────────────────────────

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
    suspend fun fetchTrustScores(providerPubkeyHex: String, relayUrls: List<String>) {
        if (relayUrls.isEmpty()) return

        // Normalize relay URLs for consistent #d matching with trust score d-tags
        val normalizedUrls = relayUrls.mapNotNull { normalizeRelayUrl(it) }.distinct()
        if (normalizedUrls.isEmpty()) return

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
                withTimeoutOrNull(15_000) { pageState.eoseReceived.await() }
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
    }

    /**
     * Fetch kind 30166 (NIP-66 relay monitors) from relay.nostr.watch.
     * Fetches ALL monitors (no #d filter) because relay.nostr.watch uses
     * d-tag formats that may not match our normalized URLs (e.g. trailing slashes).
     * With snapshot persistence this only downloads once; subsequent launches
     * restore from disk and refresh in the background.
     */
    suspend fun fetchRelayMonitors() {
        var conn = getOrCreateConnection(RELAY_MONITOR_URL)
        if (conn == null) {
            Log.w(TAG, "relay.nostr.watch unreachable — retrying in 3s")
            delay(3_000)
            conn = getOrCreateConnection(RELAY_MONITOR_URL)
        }
        if (conn == null) {
            Log.w(TAG, "relay.nostr.watch unreachable after retry — skipping relay monitors")
            return
        }

        val baseFilter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(30166)) })
            put("authors", buildJsonArray { add(JsonPrimitive(RELAY_MONITOR_PUBKEY)) })
        }

        try {
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
        } catch (e: Exception) {
            Log.w(TAG, "Monitor fetch failed: ${e.message}")
        }
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
        if (existing != null) connections.remove(normalized)
        val conn = RelayConnection(normalized, okHttpClient)
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
        scope.launch { sendOneShotBatch(targetUrls, listOf(req), listOf(subId)) }
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
     * "search" field. Results arrive via EventProcessor → Room as with any other subscription.
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
            val conn = connections.getOrPut(url) {
                RelayConnection(url, okHttpClient).also { c ->
                    scope.launch { listenForEvents(c) }
                }
            }
            if (!conn.isConnected) conn.connect()

            scope.launch {
                try {
                    conn.awaitConnected()
                    Log.d(TAG, "Search relay ready: $url")
                    conn.send(profileReq)
                    conn.send(notesReq)
                    Log.d(TAG, "Search REQs sent to $url")
                } catch (e: Exception) {
                    Log.w(TAG, "Search relay $url failed: ${e.message}")
                }
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
                    add(JsonPrimitive(30023))
                })
                put("until", JsonPrimitive(untilTimestamp))
                put("limit", JsonPrimitive(50))
            })
        }.toString()

        // Fallback to all connected relays when relayUrls is empty (e.g. Following feed)
        val targets = if (relayUrls.isEmpty()) connections.values.toList()
            else relayUrls.mapNotNull { connections[it] }
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
                put("limit", JsonPrimitive(novel.size))
            })
        }.toString()
        // Broadened relay targeting: non-indexer relays first, then indexer relays
        // for coverage. Previously limited to 3 non-indexer relays which missed
        // events on less-replicated relays.
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
            .mapNotNull { normalizeRelayUrl(it) }.toSet()
        val excluded = activeSingleRelayFeedUrl
        val nonIndexer = connections.values.filter { it.url !in indexerUrls && it.url != excluded }.shuffled()
        val indexer = connections.values.filter { it.url in indexerUrls && it.url != excluded }
        val targets = (nonIndexer + indexer).take(6)
        if (targets.isEmpty()) {
            Log.d(TAG, "one-shot skipped: only feedRelay in target set")
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
     * `connectAndAwait` runs first when no connection exists (hint-relay fetches
     * frequently target obscure relays not in the persistent pool). 2s budget so
     * unreachable hints don't block the hydrator.
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
        if (normalized == activeSingleRelayFeedUrl) {
            Log.d(TAG, "one-shot skipped: only feedRelay in target set")
            return
        }
        if (connections[normalized] == null) {
            connectAndAwait(listOf(normalized), timeoutMs = 2_000)
        }
        val conn = connections[normalized] ?: return
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
                put("limit", JsonPrimitive(novel.size))
            })
        }.toString()
        sendOneShotToRelay(conn, req)
        if (bypassDedup) {
            Log.d(TAG, "fetchByIdsFromRelay (hint-batch): ${novel.size} events → $normalized")
        } else {
            Log.d(TAG, "prefetch: ${novel.size} events → $normalized")
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
                put("limit", JsonPrimitive(1))
            })
        }.toString()

        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
            .mapNotNull { normalizeRelayUrl(it) }.toSet()
        val excluded = activeSingleRelayFeedUrl

        if (relayHints.isNotEmpty()) {
            // Hints-first: connect and wait for WebSocket readiness, then send REQ
            // only to hint relays. No broadcast fallback — if all hints fail, the
            // event stays unfetched until the next hydration pass retries.
            val hintTargets = relayHints.mapNotNull { normalizeRelayUrl(it) }
                .filter { it !in indexerUrls && it !in blockedUrls && it != excluded }
            if (hintTargets.isNotEmpty()) {
                connectAndAwait(hintTargets, timeoutMs = 2_000)
                var sent = 0
                hintTargets.forEach { url ->
                    connections[url]?.let { conn ->
                        sendOneShotToRelay(conn, req)
                        sent++
                    }
                }
                Log.d(TAG, "fetchEventById: $eventId → $sent hint relay(s)")
                return
            }
        }

        // No hints (or all hints were indexer/blocked/feedRelay) — broadened fallback.
        val nonIndexer = connections.values.filter { it.url !in indexerUrls && it.url != excluded }.shuffled()
        val indexer = connections.values.filter { it.url in indexerUrls && it.url != excluded }
        val fallbackTargets = (nonIndexer + indexer).take(6)
        if (fallbackTargets.isEmpty()) {
            Log.d(TAG, "one-shot skipped: only feedRelay in target set")
            return
        }
        fallbackTargets.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "fetchEventById: $eventId → ${fallbackTargets.size} fallback relay(s) (no hints)")
    }

    fun publish(eventJson: String) {
        val cmd = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(NostrJson.parseToJsonElement(eventJson))
        }.toString()
        connections.values.forEach { it.send(cmd) }
        Log.d(TAG, "Published event to ${connections.size} relay(s)")
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
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(6))
                    add(JsonPrimitive(20))
                    add(JsonPrimitive(21))
                    add(JsonPrimitive(30023))
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
        val guard = reconnecting.getOrPut(url) { AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) return

        scope.launch {
            try {
                if (attempt > 0) {
                    val delayMs = minOf(1000L * (1L shl minOf(attempt - 1, 4)), 30_000L)
                    Log.d(TAG, "Backoff $url: attempt $attempt, delay ${delayMs}ms")
                    delay(delayMs)
                }

                connections[url]?.close()
                authenticatedRelays.remove(url)
                pendingChallenges.remove(url)
                authFailedRelays.remove(url)
                pendingAuthEventIds.values.removeAll { it == url }
                val conn = RelayConnection(url, okHttpClient)
                connections[url] = conn
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
                    scope.launch { listenForEvents(conn) }
                    Log.d(TAG, "Reconnected $url")
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
     * After successful auth, notifies browse sessions for re-subscription.
     */
    private fun handleAuthChallenge(conn: RelayConnection, challenge: String) {
        val url = conn.url
        pendingChallenges[url] = challenge

        // Skip if already authenticated or auth is in flight
        if (url in authenticatedRelays) {
            Log.d(TAG, "AUTH: already authenticated to $url, skipping")
            return
        }
        if (!authInFlight.add(url)) {
            Log.d(TAG, "AUTH: already in flight for $url, skipping")
            return
        }

        scope.launch {
            try {
                val normalizedUrl = NormalizedRelayUrl(url)
                val template = RelayAuthEvent.build(normalizedUrl, challenge)
                val signed = signingManager.sign(template)

                if (signed == null) {
                    Log.w(TAG, "AUTH: signing failed for $url (signer returned null)")
                    return@launch
                }

                // Send ["AUTH", {signed event JSON}]
                val authJson = """["AUTH",${signed.toJson()}]"""
                val sent = conn.send(authJson)

                if (sent) {
                    // Track event ID — relay will respond with ["OK", eventId, true/false, "..."]
                    pendingAuthEventIds[signed.id] = url
                    Log.d(TAG, "AUTH: sent auth response to $url (eventId=${signed.id.take(8)}…)")

                    // 10s fallback: if the relay never sends OK, optimistically mark as authenticated.
                    // Prevents indefinite auth-pending state for non-compliant relays.
                    scope.launch {
                        delay(10_000)
                        if (pendingAuthEventIds.remove(signed.id) != null) {
                            Log.w(TAG, "AUTH: OK timeout for $url — falling back to optimistic auth")
                            completeAuth(conn, url)
                        }
                    }
                } else {
                    Log.w(TAG, "AUTH: failed to send auth to $url (connection closed?)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "AUTH: error authenticating to $url", e)
                authInFlight.remove(url)
            }
            // authInFlight removed by handleOk/completeAuth/timeout, not here
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
                Log.d(TAG, "AUTH OK: relay $url accepted auth (eventId=${eventId.take(8)}…)")
                completeAuth(conn, url)
            } else {
                Log.w(TAG, "AUTH REJECTED: relay $url rejected auth: $message")
                authInFlight.remove(url)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse OK message from ${conn.url}: ${raw.take(100)}", e)
        }
    }

    /**
     * Mark relay as authenticated and notify browse sessions.
     * Shared by OK handler and timeout fallback.
     */
    private fun completeAuth(conn: RelayConnection, url: String) {
        authenticatedRelays.add(url)
        authInFlight.remove(url)
        _onRelayReconnected.tryEmit(url)
        Log.d(TAG, "AUTH: completed for $url — notified subscribers")
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
        connections[url]?.close()
        connections.remove(url)
        connectionLastActivity.remove(url)
        connectionPurposes.remove(url)
        Log.d(TAG, "Released unused connection: $url")
    }

    fun disconnectAll() {
        connections.values.forEach { it.close() }
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
        relayOneShotCount.clear()
        relayReqQueue.clear()
        connectionLastActivity.clear()
        Log.d(TAG, "disconnectAll: all connections, purposes, and auth state cleared")
    }
}
