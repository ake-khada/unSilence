package com.unsilence.app.data.relay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
)

/** A search result correlated with the token of the search session that produced it. */
data class SearchResult(val token: Long, val eventId: String)

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
    private val relayConfigDao: dagger.Lazy<com.unsilence.app.data.db.dao.RelayConfigDao>,
    private val subscriptionRegistry: dagger.Lazy<SubscriptionRegistry>,
    private val coverageRepository: dagger.Lazy<com.unsilence.app.data.repository.CoverageRepository>,
    private val signingManager: com.unsilence.app.data.auth.SigningManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, RelayConnection>()
    private val persistentSubs = ConcurrentHashMap<String, PersistentSub>()
    private val reconnecting = ConcurrentHashMap<String, AtomicBoolean>()
    /** Cached blocked relay URLs, refreshed before each connect(). */
    @Volatile private var blockedUrls: Set<String> = emptySet()

    private val countCallbacks = ConcurrentHashMap<String, CompletableDeferred<Long?>>()
    private val profileFetchAttempted = ConcurrentHashMap<String, Long>()

    /** Global engagement dedup — event IDs already fetched (60s TTL). */
    private val engagementFetched = ConcurrentHashMap<String, Long>()

    /** Global event-by-ID dedup — prevents duplicate fetchEventById calls (30s TTL). */
    private val eventFetchInFlight = ConcurrentHashMap<String, Long>()

    /** Relays that have completed NIP-42 auth successfully. */
    private val authenticatedRelays = ConcurrentHashMap.newKeySet<String>()

    /** Relays currently waiting for an auth response (prevents duplicate auth attempts). */
    private val authInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Last challenge received per relay — needed for CLOSED auth-required flow. */
    private val pendingChallenges = ConcurrentHashMap<String, String>()

    // Evict stale entries every 5 minutes to prevent unbounded growth.
    init {
        scope.launch {
            while (true) {
                delay(300_000)
                val cutoff = System.currentTimeMillis() - 300_000
                engagementFetched.entries.removeIf { it.value < cutoff }
                eventFetchInFlight.entries.removeIf { it.value < cutoff }
                profileFetchAttempted.entries.removeIf { it.value < cutoff }
            }
        }
    }

    private val _connectionStates = MutableStateFlow<Map<String, RelayState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, RelayState>> get() = _connectionStates.asStateFlow()

    /** Emits (token, eventId) pairs for events arriving on search-notes-* subscriptions. */
    private val _searchResults = MutableSharedFlow<SearchResult>(extraBufferCapacity = 256)
    val searchResults: SharedFlow<SearchResult> = _searchResults.asSharedFlow()

    /** Register a subscription as persistent so it's replayed after reconnect. */
    private fun registerPersistentSub(subId: String, reqJson: String) {
        persistentSubs[subId] = PersistentSub(subId = subId, reqJson = reqJson)
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
     * Extract the Nostr event ID from a raw EVENT message without JSON parsing.
     * Scans for the `"id":"` marker and grabs the next 64 hex chars.
     */
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
    suspend fun connectAndAwait(relayUrls: List<String>, timeoutMs: Long = 5_000): Int {
        val newConns = mutableListOf<RelayConnection>()
        for (url in relayUrls) {
            if (connections.containsKey(url)) continue
            if (connections.size >= 25) {
                Log.d(TAG, "Connection cap (25) reached — skipping $url")
                continue
            }
            val conn = RelayConnection(url, okHttpClient)
            connections[url] = conn
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
    suspend fun refreshBlockedRelays() {
        blockedUrls = relayConfigDao.get().blockedRelayUrls().toSet()
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
            lanes.add(Lane("feed-posts-$hash", url))
            lanes.add(Lane("feed-media-$hash", url))
            lanes.add(Lane("feed-longform-$hash", url))
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
            if (connections.size + newUrls.size >= 25) {
                Log.d(TAG, "Connection cap (25) reached — skipping $url")
                continue
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
            scope.launch {
                conn.connect()
                subscribeAfterConnect(conn)
                listenForEvents(conn)
            }
        }
        Log.d(TAG, "Pool has ${connections.size} connections")
    }

    private suspend fun subscribeAfterConnect(conn: RelayConnection) {
        val hash = conn.url.hashCode()

        // REQ 1: posts and reposts — the main feed content
        val postsSubId = "feed-posts-$hash"
        val postsReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(postsSubId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(6)) })
                put("limit", JsonPrimitive(300))
            })
        }.toString()

        // REQ 2: pictures and videos
        val mediaSubId = "feed-media-$hash"
        val mediaReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(mediaSubId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(20)); add(JsonPrimitive(21)) })
                put("limit", JsonPrimitive(100))
            })
        }.toString()

        // REQ 3: longform articles (NIP-23)
        val longformSubId = "feed-longform-$hash"
        val longformReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(longformSubId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(30023)) })
                put("limit", JsonPrimitive(50))
            })
        }.toString()

        registerPersistentSub(postsSubId, postsReq)
        registerPersistentSub(mediaSubId, mediaReq)
        registerPersistentSub(longformSubId, longformReq)

        conn.send(postsReq)
        conn.send(mediaReq)
        conn.send(longformReq)
        Log.d(TAG, "Subscribed to ${conn.url} (3 feed subscriptions)")
    }

    private suspend fun listenForEvents(conn: RelayConnection) {
        try {
            conn.messages.consumeEach { raw ->
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
                // NIP-42 AUTH challenge — structural preparation (stub: log and ignore)
                if (raw.startsWith("[\"AUTH\"")) {
                    val challenge = raw.substringAfter("[\"AUTH\",\"", "").substringBefore("\"")
                    Log.d(TAG, "AUTH challenge from ${conn.url}: ${challenge.take(20)}… (not yet implemented)")
                    return@consumeEach
                }
                // Update lastEventTime for persistent sub tracking
                val subId = extractEventSubId(raw)
                if (subId != null) {
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
                    coverageRepository.get().markFromHandle(terminalHandle)
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
        if (isOneShotSubscription(subId)) {
            conn.send("""["CLOSE","$subId"]""")
            Log.d(TAG, "CLOSE sent for one-shot sub '$subId' on ${conn.url}")
        }
        // Notify coverage registry — returns handle only when ALL lanes resolved
        val terminalHandle = subscriptionRegistry.get().onEose(subId, conn.url)
        if (terminalHandle != null) {
            scope.launch {
                coverageRepository.get().markFromHandle(terminalHandle)
                subscriptionRegistry.get().cleanup(terminalHandle.handleId)
            }
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
     *  ONE_SHOT  (close after EOSE): kind3-, kind10002-, profiles-, search-, older-,
     *                                relay-ecosystem-,
     *                                thread-event-, thread-replies-, thread-reactions-,
     *                                thread-zaps-, user-posts-, user-engagement-,
     *                                engagement-replies-, engagement-reactions-, engagement-zaps-
     *  PERSISTENT (keep open):       feed-, follows-, notifs-
     */
    private fun isOneShotSubscription(subId: String): Boolean =
        subId.startsWith("kind3-")                  ||
        subId.startsWith("kind10002-")              ||
        subId.startsWith("profiles-")               ||
        subId.startsWith("search-")                 ||
        subId.startsWith("older-")                  ||
        subId.startsWith("relay-ecosystem-")        ||
        subId.startsWith("thread-event-")           ||
        subId.startsWith("thread-replies-")         ||
        subId.startsWith("thread-reactions-")       ||
        subId.startsWith("thread-zaps-")            ||
        subId.startsWith("user-posts-")             ||
        subId.startsWith("user-engagement-")        ||
        subId.startsWith("engagement-replies-")     ||
        subId.startsWith("engagement-reactions-")   ||
        subId.startsWith("engagement-zaps-")

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
        connections.values.forEach { it.send(req) }
        Log.d(TAG, "Fetching kind 3 for $pubkeyHex from ${connections.size} relay(s)")
    }

    /**
     * Send a one-time REQ for kind 10002 (relay list metadata) for [pubkeys].
     * Results flow through EventProcessor → OutboxRouter's registered handler.
     */
    fun fetchRelayLists(pubkeys: List<String>) {
        if (pubkeys.isEmpty()) return
        // Chunk to keep individual filters under 500 authors
        pubkeys.chunked(500).forEach { chunk ->
            val req = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive("kind10002-${System.nanoTime()}"))
                add(buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(10002)) })
                    put("authors", buildJsonArray { chunk.forEach { add(JsonPrimitive(it)) } })
                })
            }.toString()
            connections.values.forEach { it.send(req) }
        }
        Log.d(TAG, "Fetching kind 10002 for ${pubkeys.size} pubkey(s)")
    }

    /**
     * One-shot fetch for NIP-51 relay ecosystem kinds: 10006, 10007, 10012, 30002.
     * Sent to the specified indexer relays. These are replaceable/parameterized
     * replaceable events, so we only need the latest for the logged-in user.
     */
    fun fetchRelayEcosystem(pubkeyHex: String, indexerRelayUrls: List<String>) {
        val subId = "relay-ecosystem-${System.nanoTime()}"
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
        for (url in indexerRelayUrls) {
            connections[url]?.send(req)
        }
        Log.d(TAG, "Fetching NIP-51 relay ecosystem for $pubkeyHex from ${indexerRelayUrls.size} indexers")
    }

    /**
     * Open (or reuse) a connection to [relayUrl] and subscribe to kind 1/6/20/21 events
     * filtered to [authorPubkeys] only — used by the outbox routing for the Following feed.
     *
     * If the relay is already connected (e.g. it's also a global relay), we just send
     * an additional subscription; the existing listenForEvents coroutine picks it up.
     */
    fun connectForAuthors(relayUrl: String, authorPubkeys: List<String>) {
        if (authorPubkeys.isEmpty()) return
        val req = buildAuthorsReq(relayUrl, authorPubkeys)
        val subId = "follows-${relayUrl.hashCode()}"
        registerPersistentSub(subId, req)
        val existing = connections[relayUrl]
        if (existing != null) {
            existing.send(req)
            Log.d(TAG, "Added authors subscription on existing $relayUrl (${authorPubkeys.size} authors)")
            return
        }
        if (connections.size >= 25) {
            Log.d(TAG, "Connection cap (25) reached — skipping $relayUrl")
            return
        }
        val conn = RelayConnection(relayUrl, okHttpClient)
        connections[relayUrl] = conn
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

    /** Send a kind 0 profile request for [pubkeys] to indexer relays only (deduped). */
    fun fetchProfiles(pubkeys: List<String>) {
        if (pubkeys.isEmpty()) return
        val now = System.currentTimeMillis()
        val novel = pubkeys.filter { pk ->
            val lastAttempt = profileFetchAttempted[pk]
            lastAttempt == null || (now - lastAttempt) > 300_000 // 5 min TTL
        }
        if (novel.isEmpty()) return
        novel.forEach { profileFetchAttempted[it] = now }
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("profiles-${System.nanoTime()}"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(0)) })
                put("authors", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
            })
        }.toString()
        val indexerUrls = runBlocking { relayConfigDao.get().getIndexerRelayUrls() }
        val indexerConns = indexerUrls.mapNotNull { connections[it] }
        // Always aim for at least 3 targets: supplement with general relays if needed
        val targets = if (indexerConns.size >= 3) {
            indexerConns
        } else {
            val extras = connections.values.filter { it !in indexerConns }.take(3 - indexerConns.size)
            indexerConns + extras
        }.ifEmpty { connections.values.take(3) }
        targets.forEach { it.send(req) }
        Log.d(TAG, "Fetching ${novel.size} profiles from ${targets.size} relay(s) (${pubkeys.size - novel.size} deduped)")
    }

    /**
     * NIP-50 search: connect to [searchRelayUrls] (if not already) and send a REQ with the
     * "search" field. Results arrive via EventProcessor → Room as with any other subscription.
     *
     * Two filters are sent:
     *  - kind 0 (profiles) — drives the People tab
     *  - kind 1/30023 (notes + articles) — drives the Notes tab
     */
    fun searchNotes(searchRelayUrls: List<String>, query: String, token: Long) {
        if (query.isBlank()) return

        val profileReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("search-profiles-$token"))
            add(buildJsonObject {
                put("kinds",  buildJsonArray { add(JsonPrimitive(0)) })
                put("search", JsonPrimitive(query))
                put("limit",  JsonPrimitive(50))
            })
        }.toString()

        val notesReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("search-notes-$token"))
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
        Log.d(TAG, "Queued NIP-50 search for \"$query\" to ${searchRelayUrls.size} relay(s) [token=$token]")
    }

    /**
     * Fetch events older than [untilTimestamp] (Unix seconds) from the specified [relayUrls].
     * Used by pagination: caller sets `until` = oldest event's createdAt in the current list.
     */
    fun fetchOlderEvents(relayUrls: List<String>, untilTimestamp: Long) {
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("older-${System.currentTimeMillis()}"))
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
        targets.forEach { it.send(req) }
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
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("ids", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(novel.size))
            })
        }.toString()
        connections.values.take(3).forEach { it.send(req) }
        Log.d(TAG, "fetchEventsByIds: ${novel.size} events → ${minOf(connections.size, 3)} relay(s)")
    }

    /** Single-ID overload — delegates to batch. */
    fun fetchEventById(eventId: String) = fetchEventsByIds(listOf(eventId))

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
    fun publishToRelays(eventJson: String, relayUrls: List<String>) {
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
    fun fetchThread(relayUrls: List<String>, eventId: String) {
        val ts = System.currentTimeMillis()

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
                conn.send(eventReq)
                conn.send(repliesReq)
                conn.send(reactionsReq)
                conn.send(zapsReq)
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
    fun fetchUserPosts(pubkey: String) {
        val ts = System.currentTimeMillis()

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

        connections.values.forEach {
            it.send(postsReq)
            it.send(engagementReq)
        }
        Log.d(TAG, "Fetching user posts + engagement for $pubkey from ${connections.size} relay(s)")
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
            Log.d(TAG, "fetchEngagementBatch: all ${eventIds.size} IDs already in-flight, skipping")
            return
        }
        novel.forEach { engagementFetched[it] = now }
        val ts = now

        val repliesSubId = "engagement-replies-$ts"
        val reactionsSubId = "engagement-reactions-$ts"
        val zapsSubId = "engagement-zaps-$ts"

        // Replies (kind 1 referencing these events)
        val repliesReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(repliesSubId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1)) })
                put("#e", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(500))
            })
        }.toString()

        // Reactions (kind 7 referencing these events)
        val reactionsReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(reactionsSubId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(7)) })
                put("#e", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(500))
            })
        }.toString()

        // Zap receipts (kind 9735 referencing these events)
        val zapsReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(zapsSubId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(9735)) })
                put("#e", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(200))
            })
        }.toString()

        val targets = connections.values.take(3)

        // Register coverage lanes: 3 subs × N relays
        val lanes = mutableSetOf<Lane>()
        for (conn in targets) {
            lanes.add(Lane(repliesSubId, conn.url))
            lanes.add(Lane(reactionsSubId, conn.url))
            lanes.add(Lane(zapsSubId, conn.url))
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
            conn.send(repliesReq)
            conn.send(reactionsReq)
            conn.send(zapsReq)
        }
        Log.d(TAG, "Fetching engagement for ${novel.size} events from ${targets.size} relay(s) (${lanes.size} lanes, ${eventIds.size - novel.size} deduped)")
    }

    /**
     * Pagination for user profile view.
     * Fetches posts by [pubkey] older than [untilTimestamp].
     * One-shot subscription — closes on EOSE (prefix "older-" matches isOneShotSubscription).
     */
    fun fetchOlderPosts(pubkey: String, untilTimestamp: Long) {
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
        connections.values.forEach { it.send(req) }
        Log.d(TAG, "Fetching older posts for $pubkey until $untilTimestamp from ${connections.size} relay(s)")
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
                val conn = RelayConnection(url, okHttpClient)
                connections[url] = conn
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
        val nowSeconds = System.currentTimeMillis() / 1000L
        for ((_, sub) in persistentSubs) {
            val since = if (sub.lastEventTime > 0) {
                maxOf(sub.lastEventTime - 30, 0)
            } else {
                nowSeconds - 300
            }
            val updatedReq = injectSince(sub.reqJson, since)
            conn.send(updatedReq)
            Log.d(TAG, "Replayed persistent sub '${sub.subId}' on ${conn.url} (since=$since)")
        }
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
                    authInFlight.remove(url)
                    return@launch
                }

                // Send ["AUTH", {signed event JSON}]
                val authJson = """["AUTH",${signed.toJson()}]"""
                val sent = conn.send(authJson)

                if (sent) {
                    // TODO: NIP-42 specifies relay responds with ["OK",...] —
                    // for now we optimistically mark as authenticated after send.
                    authenticatedRelays.add(url)
                    Log.d(TAG, "AUTH: sent auth response to $url")
                    // Replay all persistent subs now that we're authenticated
                    replayPersistentSubs(conn)
                } else {
                    Log.w(TAG, "AUTH: failed to send auth to $url (connection closed?)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "AUTH: error authenticating to $url", e)
            } finally {
                authInFlight.remove(url)
            }
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

    fun disconnectAll() {
        connections.values.forEach { it.close() }
        connections.clear()
        profileFetchAttempted.clear()
    }
}
