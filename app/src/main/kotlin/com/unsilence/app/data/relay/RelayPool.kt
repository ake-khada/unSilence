package com.unsilence.app.data.relay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.coroutineScope
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

/**
 * Tracks a persistent subscription for replay after reconnection.
 * [lastEventTime] is Unix seconds — updated when events arrive for this sub.
 */
data class PersistentSub(
    val subId: String,
    val reqJson: String,
    val lastEventTime: Long = 0L,
    /** When non-null, this sub is only sent to this specific relay URL. */
    val targetRelayUrl: String? = null,
)

/** A search result correlated with the token of the search session that produced it. */
data class SearchResult(val token: Long, val eventId: String)

/** Why a relay connection exists — a relay can hold multiple purposes simultaneously. */
enum class ConnectionPurpose { PERSISTENT, BROWSE, OUTBOX }

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
    private val subscriptionRegistry: dagger.Lazy<SubscriptionRegistry>,
    private val coverageTracker: dagger.Lazy<com.unsilence.app.data.cache.CoverageTracker>,
    private val signingManager: com.unsilence.app.data.auth.SigningManager,
    private val syncTracker: dagger.Lazy<com.unsilence.app.data.cache.SyncTracker>,
    private val keyManager: com.unsilence.app.data.auth.KeyManager,
    private val memoryEventStore: dagger.Lazy<com.unsilence.app.data.memory.MemoryEventStore>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, RelayConnection>()
    private val persistentSubs = ConcurrentHashMap<String, PersistentSub>()
    private val reconnecting = ConcurrentHashMap<String, AtomicBoolean>()
    /** Cached blocked relay URLs, refreshed before each connect(). */
    @Volatile private var blockedUrls: Set<String> = emptySet()

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
        !hasPurpose(url, ConnectionPurpose.PERSISTENT) &&
        !hasPurpose(url, ConnectionPurpose.OUTBOX)

    fun hasAnyPurpose(url: String): Boolean =
        connectionPurposes[url]?.isNotEmpty() == true

    private val countCallbacks = ConcurrentHashMap<String, CompletableDeferred<Long?>>()
    /** One-shot REQ callbacks that return the first EVENT's raw tags JSON. */
    internal val eventTagsCallbacks = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val profileFetchAttempted = ConcurrentHashMap<String, Long>()

    /** Global engagement dedup — event IDs already fetched (60s TTL). */
    private val engagementFetched = ConcurrentHashMap<String, Long>()

    /** Tracks event IDs per engagement subscription for post-EOSE cache invalidation. */
    private val engagementSubEventIds = ConcurrentHashMap<String, List<String>>()

    /** Global event-by-ID dedup — prevents duplicate fetchEventById calls (30s TTL). */
    private val eventFetchInFlight = ConcurrentHashMap<String, Long>()

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

    /** Last time each relay received a message — used for idle eviction. */
    private val connectionLastActivity = ConcurrentHashMap<String, Long>()

    /** Active one-shot subscriptions in flight (tracked by unique sub-ID, not per-relay). */
    private val _activeOneShotSubs = ConcurrentHashMap.newKeySet<String>()
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

    // ── Per-relay REQ queue ───────────────────────────────────────────
    // Relays typically allow 10-20 concurrent subscriptions. When a relay hits
    // the limit, new one-shot REQs are queued and sent as CLOSE events free slots.
    companion object {
        const val MAX_CONCURRENT_REQS_PER_RELAY = 10
        const val IDLE_EVICTION_THRESHOLD_MS = 60_000L
        const val OUTBOX_IDLE_EVICTION_MS = 30_000L
        const val STEADY_STATE_DELAY_MS = 30_000L
        const val STEADY_STATE_CAP = 10
        const val RATE_LIMIT_MAX_TOKENS = 5
        const val RATE_LIMIT_REFILL_MS = 1000L
        const val RATE_LIMIT_COOLDOWN_MS = 30_000L
        const val SEARCH_TIMEOUT_MS = 10_000L
        const val RELAY_MONITOR_URL = "wss://relay.nostr.watch"
        const val RELAY_MONITOR_PUBKEY =
            "9bbbb845e5b6c831c29789900769843ab43bb5047abe697870cb50b6fc9bf923"
    }

    /** After bootstrap settles (30s), tighten cap to [STEADY_STATE_CAP] for non-PERSISTENT. */
    @Volatile private var steadyStateActive = false
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

    /**
     * Try to evict one idle BROWSE or OUTBOX connection to make room for a new one.
     * Returns true if a connection was evicted.
     */
    private fun evictIdleConnection(): Boolean {
        val now = System.currentTimeMillis()
        // Find BROWSE/OUTBOX-only connections that exceed their idle threshold.
        // OUTBOX uses 30s (radios idle sooner), BROWSE uses 60s.
        val candidate = connections.entries
            .filter { (url, _) ->
                !hasPurpose(url, ConnectionPurpose.PERSISTENT) &&
                (hasPurpose(url, ConnectionPurpose.BROWSE) || hasPurpose(url, ConnectionPurpose.OUTBOX))
            }
            .filter { (url, _) ->
                val lastActive = connectionLastActivity[url] ?: 0L
                val threshold = if (hasPurpose(url, ConnectionPurpose.OUTBOX) && !hasPurpose(url, ConnectionPurpose.BROWSE))
                    OUTBOX_IDLE_EVICTION_MS else IDLE_EVICTION_THRESHOLD_MS
                (now - lastActive) >= threshold
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
     * Force-evict the most idle OUTBOX connection regardless of idle threshold.
     * Falls back to the most idle non-home-feed connection if no OUTBOX candidates exist.
     * Used by [connectAndAwait] with forceEvict=true for critical one-shot queries
     * (e.g., NIP-45 COUNT) when the pool is at cap and normal eviction fails.
     */
    private fun forceEvictMostIdle(): Boolean {
        val now = System.currentTimeMillis()
        // Prefer OUTBOX-only connections (expendable profile-specific connections)
        val candidate = connections.entries
            .filter { (url, _) ->
                hasPurpose(url, ConnectionPurpose.OUTBOX) &&
                !hasPurpose(url, ConnectionPurpose.PERSISTENT)
            }
            .maxByOrNull { (url, _) -> now - (connectionLastActivity[url] ?: 0L) }
            ?: connections.entries
                .filter { (url, _) -> !hasPurpose(url, ConnectionPurpose.PERSISTENT) }
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
                engagementFetched.entries.removeIf { it.value < cutoff }
                engagementSubEventIds.entries.removeIf { true } // clear all — subs should be completed by now
                eventFetchInFlight.entries.removeIf { it.value < cutoff }
                profileFetchAttempted.entries.removeIf { it.value < cutoff }
            }
        }
        // Steady-state cap: 30s after startup, tighten to 10 non-PERSISTENT connections
        scope.launch {
            delay(STEADY_STATE_DELAY_MS)
            steadyStateActive = true
            Log.d(TAG, "Steady-state cap active: evicting idle non-PERSISTENT connections above $STEADY_STATE_CAP")
            // Proactive sweep: evict OUTBOX/BROWSE connections above the steady-state cap
            val nonPersistent = connections.entries.filter { (url, _) ->
                !hasPurpose(url, ConnectionPurpose.PERSISTENT)
            }
            if (nonPersistent.size > STEADY_STATE_CAP) {
                val toEvict = nonPersistent.size - STEADY_STATE_CAP
                var evicted = 0
                repeat(toEvict) { if (evictIdleConnection()) evicted++ }
                Log.d(TAG, "Steady-state sweep: evicted $evicted/${toEvict} idle connections (pool has ${connections.size})")
            }
        }
    }

    private val _connectionStates = MutableStateFlow<Map<String, RelayState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, RelayState>> get() = _connectionStates.asStateFlow()

    /** Emits (token, eventId) pairs for events arriving on search-notes-* subscriptions. */
    private val _searchResults = MutableSharedFlow<SearchResult>(extraBufferCapacity = 256)
    val searchResults: SharedFlow<SearchResult> = _searchResults.asSharedFlow()

    /** Register a subscription as persistent so it's replayed after reconnect. */
    private fun registerPersistentSub(subId: String, reqJson: String, targetRelayUrl: String? = null) {
        persistentSubs[subId] = PersistentSub(subId = subId, reqJson = reqJson, targetRelayUrl = targetRelayUrl)
    }

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

    private fun extractEventIdFromRaw(raw: String): String? {
        val marker = "\"id\":\""
        val markerIdx = raw.indexOf(marker)
        if (markerIdx < 0) return null
        val idStart = markerIdx + marker.length
        if (idStart + 64 > raw.length) return null
        val id = raw.substring(idStart, idStart + 64)
        if (!id.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return id
    }

    private fun updateConnectionStates() {
        _connectionStates.value = connections.mapValues { it.value.state.value }
    }

    /** Cancel all persistent subscriptions and clear tracking. Called on logout. */
    fun clearPersistentSubs() {
        for ((subId, _) in persistentSubs) {
            connections.values.forEach { it.send("""["CLOSE","$subId"]""") }
        }
        persistentSubs.clear()
        profileFetchAttempted.clear()
    }

    /**
     * Connect to [relayUrls], start listening for events, and suspend until at least
     * one connection is ready OR [timeoutMs] elapses. Does NOT send any subscriptions —
     * the caller sends requests after this returns.
     */
    suspend fun connectAndAwait(
        relayUrls: List<String>,
        timeoutMs: Long = 5_000,
        forceEvict: Boolean = false,
    ): Int {
        val newConns = mutableListOf<RelayConnection>()
        for (rawUrl in relayUrls) {
            val url = normalizeRelayUrl(rawUrl) ?: continue
            if (url in blockedUrls) {
                Log.d(TAG, "Blocked relay — skipping $url")
                continue
            }
            if (connections.containsKey(url)) continue
            if (connections.size >= 13) {
                // Try evicting an idle connection before giving up
                if (!evictIdleConnection() && !(forceEvict && forceEvictMostIdle())) {
                    val browseCount = connections.keys.count { hasPurpose(it, ConnectionPurpose.BROWSE) && !hasPurpose(it, ConnectionPurpose.PERSISTENT) }
                    val isBrowse = hasPurpose(url, ConnectionPurpose.BROWSE)
                    if (!isBrowse || browseCount >= 3) {
                        Log.d(TAG, "Connection cap (13) reached — skipping $url")
                        continue
                    }
                }
            }
            val conn = RelayConnection(url, okHttpClient)
            connections[url] = conn
            connectionLastActivity[url] = System.currentTimeMillis()
            conn.connect()
            scope.launch { listenForEvents(conn) }
            newConns.add(conn)
        }
        if (newConns.isEmpty()) return connections.values.count { it.isConnected }
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

    /**
     * Register a single coverage handle spanning feed subs across [relayUrls].
     * Called once from connect() when new relay connections will send REQs
     * via subscribeAfterConnect(). EOSE from each relay resolves lanes.
     */
    private fun registerHomeCoverage(relayUrls: List<String>) {
        val lanes = mutableSetOf<Lane>()
        for (url in relayUrls) {
            val hash = url.hashCode()
            lanes.add(Lane("feed-$hash", url))
        }
        if (lanes.isEmpty()) return
        val handle = CoverageHandle(
            handleId = "home-${System.nanoTime()}",
            scopeType = "home", scopeKey = "home", relaySetId = "global",
            expectedLanes = lanes,
        )
        subscriptionRegistry.get().register(handle)
        Log.d(TAG, "Registered home coverage handle: ${lanes.size} lanes across ${relayUrls.size} relays")
    }

    fun connect(relayUrls: List<String>, isHomeFeed: Boolean = false) {
        val normalizedUrls = relayUrls.mapNotNull { normalizeRelayUrl(it) }
        // Collect URLs that will actually be connected
        val newUrls = mutableListOf<String>()
        for (url in normalizedUrls) {
            if (url in blockedUrls) {
                Log.d(TAG, "Blocked relay — skipping $url")
                continue
            }
            if (connections.containsKey(url)) continue
            if (connections.size + newUrls.size >= 13) {
                // Try evicting an idle connection before giving up
                if (!evictIdleConnection()) {
                    val browseCount = connections.keys.count { hasPurpose(it, ConnectionPurpose.BROWSE) && !hasPurpose(it, ConnectionPurpose.PERSISTENT) }
                    val isBrowse = hasPurpose(url, ConnectionPurpose.BROWSE)
                    if (!isBrowse || browseCount >= 3) {
                        Log.d(TAG, "Connection cap (13) reached — skipping $url")
                        continue
                    }
                }
            }
            newUrls.add(url)
        }

        // Register ONE coverage handle spanning ALL new relays' feed subs —
        // but ONLY for home feed connections (not outbox/search/profile relays).
        if (newUrls.isNotEmpty() && isHomeFeed) {
            registerHomeCoverage(newUrls)
        }

        for (url in newUrls) {
            val conn = RelayConnection(url, okHttpClient)
            connections[url] = conn
            connectionLastActivity[url] = System.currentTimeMillis()
            scope.launch {
                conn.connect()
                if (!hasPurpose(url, ConnectionPurpose.PERSISTENT)) {
                    Log.d(TAG, "Skipping subscribeAfterConnect on non-PERSISTENT $url (purposes=${connectionPurposes[url] ?: "none"})")
                } else {
                    subscribeAfterConnect(conn)
                }
                listenForEvents(conn)
            }
        }
        Log.d(TAG, "Pool has ${connections.size} connections")
    }

    private suspend fun subscribeAfterConnect(conn: RelayConnection) {
        val hash = conn.url.hashCode()

        // Clean up legacy per-type feed subs from prior versions
        persistentSubs.keys.removeIf {
            it == "feed-posts-$hash" || it == "feed-media-$hash" || it == "feed-longform-$hash"
        }

        // Single combined feed subscription: notes, reposts, pictures, videos, longform
        val feedSubId = "feed-$hash"
        val feedReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(feedSubId))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(6))
                    add(JsonPrimitive(20))
                    add(JsonPrimitive(21))
                    add(JsonPrimitive(30023))
                })
                put("limit", JsonPrimitive(300))
            })
        }.toString()

        registerPersistentSub(feedSubId, feedReq, targetRelayUrl = conn.url)
        conn.send(feedReq)
        Log.d(TAG, "Subscribed to ${conn.url} (1 combined feed subscription)")
    }

    private suspend fun listenForEvents(conn: RelayConnection) {
        try {
            conn.messages.consumeEach { raw ->
                connectionLastActivity[conn.url] = System.currentTimeMillis()
                // Fix 3: intercept EOSE before EventProcessor so we can send CLOSE
                // for one-shot subscriptions. EventProcessor's process() would already
                // early-return for non-EVENT strings, but we need the relay URL here.
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
                                // Already authed — replay the specific closed sub
                                if (hasPurpose(conn.url, ConnectionPurpose.PERSISTENT)) {
                                    persistentSubs[closedSubId]?.let { sub ->
                                        val since = if (sub.lastEventTime > 0) maxOf(sub.lastEventTime - 30, 0)
                                                    else System.currentTimeMillis() / 1000L - 300
                                        conn.send(injectSince(sub.reqJson, since))
                                        Log.d(TAG, "Replayed closed sub '$closedSubId' on ${conn.url} (since=$since)")
                                    }
                                }
                                // Browse subs aren't in persistentSubs — notify the
                                // browse session so it can resend its own REQ.
                                if (closedSubId.startsWith("browse-")) {
                                    onRelayReconnected?.invoke(conn.url)
                                    Log.d(TAG, "Notified browse session to resend closed sub '$closedSubId' on ${conn.url}")
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
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse CLOSED message: ${e.message}")
                    }
                    return@consumeEach
                }
                // Update lastEventTime for persistent sub tracking
                val subId = extractEventSubId(raw)
                if (subId != null) {
                    // One-shot event-tags callback (e.g. following count)
                    eventTagsCallbacks[subId]?.let { deferred ->
                        val tagsJson = extractTagsFromRaw(raw)
                        deferred.complete(tagsJson)
                        eventTagsCallbacks.remove(subId)
                    }
                    persistentSubs.computeIfPresent(subId) { _, sub ->
                        sub.copy(lastEventTime = System.currentTimeMillis() / 1000L)
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
                processor.process(raw, conn.url)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stream closed for ${conn.url}: ${e.message}")
        }
        // Relay disconnected — mark all pending lanes for this relay as failed
        handleRelayFailure(conn.url)
    }

    /**
     * When a relay disconnects or errors, mark all its pending subscription
     * lanes as failed so coverage handles can reach terminal state.
     */
    private fun handleRelayFailure(relayUrl: String) {
        val registry = subscriptionRegistry.get()
        for (lane in registry.subsForRelay(relayUrl)) {
            val terminalHandle = registry.onLaneFailure(lane.subId, lane.relayUrl)
            if (terminalHandle != null) {
                scope.launch {
                    coverageTracker.get().markFromHandle(terminalHandle)
                    registry.cleanup(terminalHandle.handleId)
                }
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
            conn.send("""["CLOSE","$subId"]""")
            // Invalidate FeedRow cache for engagement subs so counts update in UI
            engagementSubEventIds.remove(subId)?.let { eventIds ->
                memoryEventStore.get().invalidateFeedRowCache(eventIds)
            }
            // Free per-relay slot and flush queued REQs
            relayOneShotCount[conn.url]?.let { count ->
                val prev = count.getAndUpdate { if (it > 0) it - 1 else 0 }
                if (prev > 0) flushRelayQueue(conn)
            }
            Log.d(TAG, "CLOSE sent for one-shot sub '$subId' on ${conn.url}")
        }
        // Update sync_state for persistent subs (foundation for background sync)
        mapSubIdToSyncKey(subId)?.let { syncKey ->
            scope.launch {
                try {
                    syncTracker.get().upsert(
                        com.unsilence.app.data.memory.SyncStateEntity(
                            subscriptionKey = syncKey,
                            lastSyncAt = System.currentTimeMillis(),
                            lastEventCount = 0,
                            source = "foreground",
                        )
                    )
                } catch (_: Exception) { }
            }
        }
        // Notify coverage registry — returns handle only when ALL lanes resolved
        val terminalHandle = subscriptionRegistry.get().onEose(subId, conn.url)
        if (terminalHandle != null) {
            scope.launch {
                coverageTracker.get().markFromHandle(terminalHandle)
                subscriptionRegistry.get().cleanup(terminalHandle.handleId)
            }
        }
    }

    /** Map persistent subscription IDs to sync_state keys. */
    private fun mapSubIdToSyncKey(subId: String): String? = when {
        subId.startsWith("feed-")     -> "following-feed"
        subId.startsWith("notifs-")   -> "own-engagement"
        subId.startsWith("follows-")  -> "follow-list"
        else -> null
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
     * The response will flow through EventProcessor → OutboxRouter's registered handler.
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
     * Results flow through EventProcessor → OutboxRouter's registered handler.
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
        val writeRelayUrls = connections.keys
            .filter { hasPurpose(it, ConnectionPurpose.OUTBOX) }
            .filter { it !in indexerRelayUrls }
        val allTargets = indexerRelayUrls + writeRelayUrls
        for (url in allTargets) {
            connections[url]?.let { sendOneShotToRelay(it, req) }
        }
        Log.d(TAG, "Fetching NIP-51 relay ecosystem for ${pubkeyHex.take(8)}… from ${indexerRelayUrls.size} indexers + ${writeRelayUrls.size} write relays")
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

    /**
     * Open (or reuse) a connection to [relayUrl] and subscribe to kind 1/6/20/21 events
     * filtered to [authorPubkeys] only — used by the outbox routing for the Following feed.
     *
     * If the relay is already connected (e.g. it's also a global relay), we just send
     * an additional subscription; the existing listenForEvents coroutine picks it up.
     */
    fun connectForAuthors(rawRelayUrl: String, authorPubkeys: List<String>) {
        if (authorPubkeys.isEmpty()) return
        val relayUrl = normalizeRelayUrl(rawRelayUrl) ?: return
        val req = buildAuthorsReq(relayUrl, authorPubkeys)
        val subId = "follows-${relayUrl.hashCode()}"
        registerPersistentSub(subId, req, targetRelayUrl = relayUrl)
        val existing = connections[relayUrl]
        if (existing != null) {
            existing.send(req)
            Log.d(TAG, "Added authors subscription on existing $relayUrl (${authorPubkeys.size} authors)")
            return
        }
        if (connections.size >= 13) {
            // Try evicting an idle connection before giving up
            if (!evictIdleConnection()) {
                val browseCount = connections.keys.count { hasPurpose(it, ConnectionPurpose.BROWSE) && !hasPurpose(it, ConnectionPurpose.PERSISTENT) }
                val isBrowse = hasPurpose(relayUrl, ConnectionPurpose.BROWSE)
                if (!isBrowse || browseCount >= 3) {
                    Log.d(TAG, "Connection cap (13) reached — skipping $relayUrl")
                    return
                }
            }
        }
        val conn = RelayConnection(relayUrl, okHttpClient)
        connections[relayUrl] = conn
        connectionLastActivity[relayUrl] = System.currentTimeMillis()
        conn.connect()
        scope.launch {
            conn.send(req)
            listenForEvents(conn)
        }
        Log.d(TAG, "Connected for authors: $relayUrl (${authorPubkeys.size} authors)")
    }

    private fun buildAuthorsReq(relayUrl: String, authorPubkeys: List<String>): String =
        buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("follows-${relayUrl.hashCode()}"))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(6))
                    add(JsonPrimitive(20))
                    add(JsonPrimitive(21))
                })
                put("authors", buildJsonArray {
                    authorPubkeys.forEach { add(JsonPrimitive(it)) }
                })
                put("limit", JsonPrimitive(200))
            })
        }.toString()

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
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(0)) })
                put("authors", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
            })
        }.toString()
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
        val indexerConns = indexerUrls.mapNotNull { connections[it] }.take(maxRelays)
        // Supplement with general relays if needed (at least 1 target)
        val minTargets = minOf(maxRelays, 3)
        val targets = if (indexerConns.size >= minTargets) {
            indexerConns
        } else {
            val extras = connections.values.filter { it !in indexerConns }.take(minTargets - indexerConns.size)
            indexerConns + extras
        }.ifEmpty { connections.values.take(minTargets) }
        targets.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "Fetching ${novel.size} profiles from ${targets.size} relay(s) (${pubkeys.size - novel.size} deduped)")
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

        // Connect to all hinted relays
        val allHintUrls = novel.values.flatten().distinct()
        if (allHintUrls.isNotEmpty()) connect(allHintUrls)

        // Send one batched REQ to hinted relays
        val pubkeys = novel.keys.toList()
        pubkeys.forEach { profileFetchAttempted[it] = now }
        val subId = "hint-profiles-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(0)) })
                put("authors", buildJsonArray { pubkeys.forEach { add(JsonPrimitive(it)) } })
            })
        }.toString()
        val hintConns = allHintUrls.mapNotNull { normalizeRelayUrl(it)?.let { url -> connections[url] } }
        hintConns.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "fetchProfilesFromHints: ${pubkeys.size} profiles → ${hintConns.size} hinted relay(s)")
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
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(0)) })
                put("authors", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
            })
        }.toString()
        val conns = relayUrls.mapNotNull { normalizeRelayUrl(it)?.let { url -> connections[url] } }
        conns.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "fetchProfilesFromSourceRelays: ${novel.size} profiles → ${conns.size} source relay(s)")
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
    /** Batch fetch events by ID. Deduped against in-flight tracker (30s TTL). */
    fun fetchEventsByIds(eventIds: List<String>) {
        if (eventIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val novel = eventIds.filter { id ->
            val last = eventFetchInFlight[id]
            last == null || (now - last) > 30_000
        }
        if (novel.isEmpty()) return
        novel.forEach { eventFetchInFlight[it] = now }
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
        val nonIndexer = connections.values.filter { it.url !in indexerUrls }.shuffled()
        val indexer = connections.values.filter { it.url in indexerUrls }
        val targets = (nonIndexer + indexer).take(6)
        targets.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "fetchEventsByIds: ${novel.size} events → ${targets.size} relay(s)")
    }

    /** Single-ID overload — delegates to batch. */
    fun fetchEventById(eventId: String) = fetchEventsByIds(listOf(eventId))

    /**
     * Fetch events by ID from a SPECIFIC relay (source-relay targeting).
     * Used by EventProcessor's prefetch: when a reply arrives from relay X,
     * the parent event is almost certainly on relay X too.
     * Deduped against the same in-flight tracker (30s TTL).
     */
    fun fetchEventsByIdsFromRelay(relayUrl: String, eventIds: List<String>) {
        if (eventIds.isEmpty()) return
        val normalized = normalizeRelayUrl(relayUrl) ?: return
        if (normalized in blockedUrls) return
        val now = System.currentTimeMillis()
        val novel = eventIds.filter { id ->
            val last = eventFetchInFlight[id]
            last == null || (now - last) > 30_000
        }
        if (novel.isEmpty()) return
        val conn = connections[normalized]
        if (conn == null) return          // no connection — don't pollute eventFetchInFlight
        novel.forEach { eventFetchInFlight[it] = now }
        val subId = "prefetch-${System.nanoTime()}"
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
        Log.d(TAG, "prefetch: ${novel.size} events → $normalized")
    }

    /**
     * Fetch a single event by ID using relay hints. Hints-first strategy:
     * when hints exist, connectAndAwait to ensure the WebSocket is open,
     * then send REQ only to hint relays — no broadcast fallback.
     * When no hints exist, sends to at most 3 random non-indexer relays.
     *
     * @param bypassDedup when true, skip the eventFetchInFlight 30s guard.
     *   Used by outbox fallback: the same event ID was already tried on the
     *   source relay, but we need to retry on the author's write relays.
     */
    suspend fun fetchEventById(eventId: String, relayHints: List<String>, bypassDedup: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!bypassDedup) {
            val last = eventFetchInFlight[eventId]
            if (last != null && (now - last) <= 30_000) return
        }
        eventFetchInFlight[eventId] = now

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

        if (relayHints.isNotEmpty()) {
            // Hints-first: connect and wait for WebSocket readiness, then send REQ
            // only to hint relays. No broadcast fallback — if all hints fail, the
            // event stays unfetched until the next hydration pass retries (30s TTL).
            val hintTargets = relayHints.mapNotNull { normalizeRelayUrl(it) }
                .filter { it !in indexerUrls && it !in blockedUrls }
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

        // No hints (or all hints were indexer/blocked) — broadened fallback.
        // Non-indexer relays first (shuffled), then indexer relays for coverage.
        // eventFetchInFlight prevents retry within 30s.
        val nonIndexer = connections.values.filter { it.url !in indexerUrls }.shuffled()
        val indexer = connections.values.filter { it.url in indexerUrls }
        val fallbackTargets = (nonIndexer + indexer).take(6)
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
        val relayUrls = rawRelayUrls.mapNotNull { normalizeRelayUrl(it) }
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
     * Request notification events for [userPubkey] from all currently connected relays.
     * Sends a #p-tagged filter for kinds 1 (replies/mentions), 6 (reposts), 7 (reactions),
     * and 9735 (zap receipts). Results flow through EventProcessor → Room → NotificationsDao.
     */
    fun fetchNotifications(userPubkey: String) {
        // Remove any previous notifs persistent sub before registering a new one
        persistentSubs.keys.removeIf { it.startsWith("notifs-") }
        val subId = "notifs-${System.currentTimeMillis()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(6))
                    add(JsonPrimitive(7))
                    add(JsonPrimitive(9735))
                })
                put("#p",    buildJsonArray { add(JsonPrimitive(userPubkey)) })
                put("limit", JsonPrimitive(100))
            })
        }.toString()
        registerPersistentSub(subId, req)
        connections.values.forEach { it.send(req) }
        Log.d(TAG, "Fetching notifications for $userPubkey from ${connections.size} relay(s)")
    }

    /**
     * Fetch posts by a single author: kinds 1, 6, 20, 21, 30023.
     * One-shot subscription — CLOSE is sent after EOSE.
     */
    fun fetchUserPosts(pubkey: String, relayUrls: List<String> = emptyList()) {
        val ts = System.currentTimeMillis()
        _activeOneShotSubs.add("user-posts-$ts")
        _activeOneShotSubs.add("user-longform-$ts")
        _activeOneShotSubs.add("user-engagement-$ts")

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

        val targets = if (relayUrls.isNotEmpty()) {
            relayUrls.mapNotNull { connections[normalizeRelayUrl(it)] }
                .ifEmpty { connections.values.take(5) }
        } else {
            connections.values.toList()
        }
        targets.forEach {
            sendOneShotToRelay(it, postsReq)
            sendOneShotToRelay(it, longformReq)
            sendOneShotToRelay(it, engagementReq)
        }
        Log.d(TAG, "Fetching user posts + engagement for $pubkey from ${targets.size} relay(s)")
    }

    /**
     * Fetch engagement (replies, reactions, zaps) scoped to specific event IDs.
     * Called by ViewModels when new posts appear in the feed.
     * One-shot subscriptions — closed after EOSE.
     * Sends to at most 6 relays to avoid fan-out.
     */
    fun fetchEngagementBatch(eventIds: List<String>) {
        if (eventIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val novel = eventIds.filter { id ->
            val last = engagementFetched[id]
            last == null || (now - last) > 60_000
        }
        if (novel.isEmpty()) {
            Log.v(TAG, "fetchEngagementBatch: all ${eventIds.size} IDs already in-flight, skipping")
            return
        }
        novel.forEach { engagementFetched[it] = now }
        val ts = now

        // Single consolidated subscription for all engagement kinds
        val subId = "engagement-$ts"
        _activeOneShotSubs.add(subId)
        engagementSubEventIds[subId] = novel

        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(7))
                    add(JsonPrimitive(9735))
                })
                put("#e", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(500))
            })
        }.toString()

        // Route engagement to browse relays when active, otherwise use non-indexer
        // relays only. Indexer relays (purplepag.es, etc.) store profile metadata
        // (kind 0, 10002), NOT content events — they can't return engagement data.
        val browseTargets = browseEngagementTargets
        val targets = if (browseTargets.isNotEmpty()) {
            browseTargets.mapNotNull { connections[it] }
        } else {
            val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
                .mapNotNull { normalizeRelayUrl(it) }.toSet()
            connections.values.filter { it.url !in indexerUrls }.take(3)
        }

        // Register coverage lanes: 1 sub × N relays (was 3 subs × N relays)
        val lanes = mutableSetOf<Lane>()
        for (conn in targets) {
            lanes.add(Lane(subId, conn.url))
        }
        val scopeKeyHash = novel.sorted().joinToString(",")
            .let {
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(it.toByteArray())
                    .joinToString("") { b -> "%02x".format(b) }
                    .take(16)
            }
        val handle = CoverageHandle(
            handleId = "engagement-$ts",
            scopeType = "engagement", scopeKey = scopeKeyHash,
            relaySetId = "global", expectedLanes = lanes,
        )
        subscriptionRegistry.get().register(handle)

        targets.forEach { conn ->
            sendOneShotToRelay(conn, req)
        }
        Log.d(TAG, "Fetching engagement for ${novel.size} events from ${targets.size} relay(s) (${lanes.size} lanes, ${eventIds.size - novel.size} deduped)")
    }

    /**
     * Pagination for user profile view.
     * Fetches posts by [pubkey] older than [untilTimestamp].
     * One-shot subscription — closes on EOSE (prefix "older-" matches isOneShotSubscription).
     */
    fun fetchOlderPosts(pubkey: String, untilTimestamp: Long, relayUrls: List<String> = emptyList()) {
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("older-user-${System.currentTimeMillis()}"))
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
        val targets = if (relayUrls.isNotEmpty()) {
            relayUrls.mapNotNull { connections[normalizeRelayUrl(it)] }
                .ifEmpty { connections.values.take(5) }
        } else {
            connections.values.toList()
        }
        targets.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "Fetching older posts for $pubkey until $untilTimestamp from ${targets.size} relay(s)")
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
                    replayPersistentSubs(conn)
                    onRelayReconnected?.invoke(url)
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
     * Replay all persistent subscriptions on a reconnected relay.
     * Updates the `since` filter to avoid re-fetching old events.
     */
    private fun replayPersistentSubs(conn: RelayConnection) {
        if (!hasPurpose(conn.url, ConnectionPurpose.PERSISTENT)) {
            Log.d(TAG, "Skipping persistent replay on non-PERSISTENT ${conn.url} (purposes=${connectionPurposes[conn.url] ?: "none"})")
            return
        }
        val nowSeconds = System.currentTimeMillis() / 1000L
        var replayCount = 0
        for ((_, sub) in persistentSubs) {
            // Skip subs targeted at a different relay
            if (sub.targetRelayUrl != null && sub.targetRelayUrl != conn.url) continue
            val since = if (sub.lastEventTime > 0) {
                maxOf(sub.lastEventTime - 30, 0)
            } else {
                nowSeconds - 300
            }
            val updatedReq = injectSince(sub.reqJson, since)
            conn.send(updatedReq)
            replayCount++
            Log.d(TAG, "Replayed persistent sub '${sub.subId}' on ${conn.url} (since=$since)")
        }
        Log.d(TAG, "Replay check for ${conn.url}: purposes=${connectionPurposes[conn.url] ?: "none"}, replayed $replayCount persistent sub(s)")
    }

    /**
     * NIP-42: Sign and send an AUTH response for the given relay challenge.
     * After successful auth, replays all persistent subscriptions on this relay.
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

            // Check if this OK is for a pending auth event
            val url = pendingAuthEventIds.remove(eventId) ?: return
            if (success) {
                Log.d(TAG, "AUTH OK: relay $url accepted auth (eventId=${eventId.take(8)}…)")
                completeAuth(conn, url)
            } else {
                val message = arr.getOrNull(3)?.jsonPrimitive?.content ?: ""
                Log.w(TAG, "AUTH REJECTED: relay $url rejected auth: $message")
                authInFlight.remove(url)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse OK message from ${conn.url}: ${raw.take(100)}", e)
        }
    }

    /**
     * Mark relay as authenticated and replay subscriptions.
     * Shared by OK handler and timeout fallback.
     */
    private fun completeAuth(conn: RelayConnection, url: String) {
        authenticatedRelays.add(url)
        authInFlight.remove(url)
        if (hasPurpose(url, ConnectionPurpose.PERSISTENT)) {
            scope.launch { replayPersistentSubs(conn) }
        } else {
            Log.d(TAG, "AUTH: non-PERSISTENT $url — notify browse session")
            onRelayReconnected?.invoke(url)
        }
    }

    /**
     * Inject a "since" field into a REQ JSON filter object.
     */
    private fun injectSince(reqJson: String, since: Long): String {
        val arr = NostrJson.parseToJsonElement(reqJson).jsonArray
        return buildJsonArray {
            add(arr[0]) // "REQ"
            add(arr[1]) // sub-id
            for (i in 2 until arr.size) {
                val filter = arr[i].jsonObject
                add(buildJsonObject {
                    for ((key, value) in filter) {
                        put(key, value)
                    }
                    put("since", JsonPrimitive(since))
                })
            }
        }.toString()
    }

    // ── Browse session hooks ────────────────────────────────────────────────

    /** When browse mode is active, engagement one-shots route here instead of general connections. */
    @Volatile var browseEngagementTargets: List<String> = emptyList()

    /** Called after a relay successfully reconnects. Browse session uses this to resend its subs. */
    var onRelayReconnected: ((String) -> Unit)? = null

    /** Send a message to a specific relay by URL. Returns false if the connection doesn't exist. */
    fun sendToRelay(url: String, msg: String): Boolean =
        connections[url]?.send(msg) == true

    /**
     * Placeholder for future disconnect logic. In this PR browse CLOSE is enough;
     * connections may be reused by outbox routing or other consumers.
     */
    fun releaseIfUnused(@Suppress("UNUSED_PARAMETER") url: String) {
        // Intentionally no disconnect logic in this PR.
    }

    fun disconnectAll() {
        connections.values.forEach { it.close() }
        connections.clear()
        connectionPurposes.clear()
        profileFetchAttempted.clear()
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
