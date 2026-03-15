package com.unsilence.app.data.relay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, RelayConnection>()
    private val persistentSubs = ConcurrentHashMap<String, PersistentSub>()
    private val reconnecting = ConcurrentHashMap<String, AtomicBoolean>()

    private val countCallbacks = ConcurrentHashMap<String, CompletableDeferred<Long?>>()
    private val profileFetchAttempted = ConcurrentHashMap<String, Boolean>()

    private val _connectionStates = MutableStateFlow<Map<String, RelayState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, RelayState>> get() = _connectionStates.asStateFlow()

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
     * Open connections to [relayUrls] and subscribe to feed events.
     * Calling this multiple times with the same URLs is idempotent.
     */
    fun connect(relayUrls: List<String>) {
        for (url in relayUrls) {
            if (connections.containsKey(url)) continue
            if (connections.size >= 25) {
                Log.d(TAG, "Connection cap (25) reached — skipping $url")
                continue
            }
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

        // REQ 2: pictures, videos, longform articles
        val mediaSubId = "feed-media-$hash"
        val mediaReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(mediaSubId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(20)); add(JsonPrimitive(21)); add(JsonPrimitive(30023)) })
                put("limit", JsonPrimitive(100))
            })
        }.toString()

        registerPersistentSub(postsSubId, postsReq)
        registerPersistentSub(mediaSubId, mediaReq)
        conn.send(postsReq)
        conn.send(mediaReq)
        Log.d(TAG, "Subscribed to ${conn.url} (2 feed subscriptions)")
    }

    private suspend fun listenForEvents(conn: RelayConnection) {
        try {
            conn.messages.consumeEach { raw ->
                // Fix 3: intercept EOSE before EventProcessor so we can send CLOSE
                // for one-shot subscriptions. EventProcessor's process() would already
                // early-return for non-EVENT strings, but we need the relay URL here.
                if (raw.startsWith("[\"EOSE\"")) {
                    handleEose(conn, raw)
                    return@consumeEach
                }
                // NIP-45 COUNT response: ["COUNT","sub-id",{"count":N}]
                if (raw.startsWith("[\"COUNT\"")) {
                    handleCount(raw)
                    return@consumeEach
                }
                // Update lastEventTime for persistent sub tracking
                val subId = extractEventSubId(raw)
                if (subId != null) {
                    persistentSubs.computeIfPresent(subId) { _, sub ->
                        sub.copy(lastEventTime = System.currentTimeMillis() / 1000L)
                    }
                }
                processor.process(raw, conn.url)
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
        if (isOneShotSubscription(subId)) {
            conn.send("""["CLOSE","$subId"]""")
            Log.d(TAG, "CLOSE sent for one-shot sub '$subId' on ${conn.url}")
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
        val novel = pubkeys.filter { profileFetchAttempted.putIfAbsent(it, true) == null }
        if (novel.isEmpty()) return
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("profiles-${System.nanoTime()}"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(0)) })
                put("authors", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
            })
        }.toString()
        val indexerUrls = listOf(
            "wss://purplepag.es",
            "wss://user.kindpag.es",
            "wss://indexer.coracle.social",
            "wss://antiprimal.net",
        )
        val targets = indexerUrls.mapNotNull { connections[it] }.ifEmpty { connections.values.take(3) }
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
    fun searchNotes(searchRelayUrls: List<String>, query: String) {
        if (query.isBlank()) return
        val ts = System.currentTimeMillis()

        val profileReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("search-profiles-$ts"))
            add(buildJsonObject {
                put("kinds",  buildJsonArray { add(JsonPrimitive(0)) })
                put("search", JsonPrimitive(query))
                put("limit",  JsonPrimitive(50))
            })
        }.toString()

        val notesReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("search-notes-$ts"))
            add(buildJsonObject {
                put("kinds",  buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(30023)) })
                put("search", JsonPrimitive(query))
                put("limit",  JsonPrimitive(50))
            })
        }.toString()

        for (url in searchRelayUrls) {
            val existing = connections[url]
            if (existing != null) {
                existing.send(profileReq)
                existing.send(notesReq)
            } else {
                val conn = RelayConnection(url, okHttpClient)
                connections[url] = conn
                conn.connect()
                scope.launch {
                    conn.send(profileReq)
                    conn.send(notesReq)
                    listenForEvents(conn)
                }
                Log.d(TAG, "Connected to search relay: $url")
            }
        }
        Log.d(TAG, "Sent NIP-50 search for \"$query\" to ${searchRelayUrls.size} relay(s)")
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
    /** One-shot REQ to fetch a single event by ID from all connected relays. */
    fun fetchEventById(eventId: String) {
        val subId = "quote-$eventId"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("ids", buildJsonArray { add(JsonPrimitive(eventId)) })
                put("limit", JsonPrimitive(1))
            })
        }.toString()
        connections.values.forEach { it.send(req) }
        Log.d(TAG, "fetchEventById: $eventId → ${connections.size} relay(s)")
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
        val ts = System.currentTimeMillis()

        // Replies (kind 1 referencing these events)
        val repliesReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("engagement-replies-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1)) })
                put("#e", buildJsonArray { eventIds.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(500))
            })
        }.toString()

        // Reactions (kind 7 referencing these events)
        val reactionsReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("engagement-reactions-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(7)) })
                put("#e", buildJsonArray { eventIds.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(500))
            })
        }.toString()

        // Zap receipts (kind 9735 referencing these events)
        val zapsReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("engagement-zaps-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(9735)) })
                put("#e", buildJsonArray { eventIds.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(200))
            })
        }.toString()

        val targets = connections.values.take(6)
        targets.forEach { conn ->
            conn.send(repliesReq)
            conn.send(reactionsReq)
            conn.send(zapsReq)
        }
        Log.d(TAG, "Fetching engagement for ${eventIds.size} events from ${targets.size} relay(s)")
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
