package com.unsilence.app.data.relay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RelayPool"

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
    private val connections = mutableMapOf<String, RelayConnection>()

    /**
     * Open connections to [relayUrls] and subscribe to feed events.
     * Calling this multiple times with the same URLs is idempotent.
     */
    fun connect(relayUrls: List<String>) {
        for (url in relayUrls) {
            if (connections.containsKey(url)) continue
            val conn = RelayConnection(url, okHttpClient)
            connections[url] = conn
            conn.connect()
            scope.launch {
                subscribeAfterConnect(conn)
                listenForEvents(conn)
            }
        }
        Log.d(TAG, "Pool has ${connections.size} connections")
    }

    private suspend fun subscribeAfterConnect(conn: RelayConnection) {
        // Feed subscription: kinds 1 (notes), 6 (reposts), 7 (reactions), 20 (pictures), 21 (video)
        val feedReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("feed-${conn.url.hashCode()}"))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(6))
                    add(JsonPrimitive(7))
                    add(JsonPrimitive(20))
                    add(JsonPrimitive(21))
                })
                put("limit", JsonPrimitive(500))
            })
        }.toString()

        conn.send(feedReq)
        Log.d(TAG, "Subscribed to ${conn.url}")
    }

    private suspend fun listenForEvents(conn: RelayConnection) {
        try {
            conn.messages.consumeEach { raw ->
                processor.process(raw, conn.url)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stream closed for ${conn.url}: ${e.message}")
        }
    }

    /**
     * Send a one-time REQ for the user's kind 3 (follow list) to all connected relays.
     * The response will flow through EventProcessor → OutboxRouter's registered handler.
     */
    fun fetchFollowList(pubkeyHex: String) {
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("kind3-${System.currentTimeMillis()}"))
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
                add(JsonPrimitive("kind10002-${System.currentTimeMillis()}"))
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
        val existing = connections[relayUrl]
        if (existing != null) {
            existing.send(req)
            Log.d(TAG, "Added authors subscription on existing $relayUrl (${authorPubkeys.size} authors)")
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

    /** Subscribe to kind 0 profiles for a batch of pubkeys.
     *  Sends to all current connections AND to dedicated profile-indexer relays
     *  (purplepag.es, user.kindpag.es) which specialise in kind 0 metadata. */
    fun fetchProfiles(pubkeys: List<String>) {
        if (pubkeys.isEmpty()) return
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("profiles-${System.currentTimeMillis()}"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(0)) })
                put("authors", buildJsonArray { pubkeys.forEach { add(JsonPrimitive(it)) } })
            })
        }.toString()

        // Send to every already-connected relay
        connections.values.forEach { it.send(req) }

        // Also query dedicated profile-indexer relays
        val indexers = listOf("wss://purplepag.es", "wss://user.kindpag.es")
        for (url in indexers) {
            val existing = connections[url]
            if (existing != null) {
                existing.send(req)
            } else {
                val conn = RelayConnection(url, okHttpClient)
                connections[url] = conn
                conn.connect()
                scope.launch {
                    conn.send(req)
                    listenForEvents(conn)
                }
                Log.d(TAG, "Connected to profile indexer: $url")
            }
        }
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
                })
                put("until", JsonPrimitive(untilTimestamp))
                put("limit", JsonPrimitive(50))
            })
        }.toString()

        for (url in relayUrls) {
            connections[url]?.send(req)
        }
        Log.d(TAG, "Fetching older events until $untilTimestamp from ${relayUrls.size} relay(s)")
    }

    /**
     * Broadcast a signed event to all currently connected relays.
     *
     * [eventJson] must be the raw JSON object string for the event
     * (not the full ["EVENT", ...] array — this method wraps it).
     */
    fun publish(eventJson: String) {
        val cmd = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(NostrJson.parseToJsonElement(eventJson))
        }.toString()
        connections.values.forEach { it.send(cmd) }
        Log.d(TAG, "Published event to ${connections.size} relay(s)")
    }

    /**
     * Fetch a thread: the event itself (by ID) and all direct replies (#e tag filter).
     * Two REQs are sent so both arrive even if the relay processes them separately.
     */
    fun fetchThread(relayUrls: List<String>, eventId: String) {
        val ts = System.currentTimeMillis()

        val eventReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("thread-event-$ts"))
            add(buildJsonObject {
                put("ids", buildJsonArray { add(JsonPrimitive(eventId)) })
            })
        }.toString()

        val repliesReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("thread-replies-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1)) })
                put("#e",    buildJsonArray { add(JsonPrimitive(eventId)) })
                put("limit", JsonPrimitive(100))
            })
        }.toString()

        for (url in relayUrls) {
            connections[url]?.let { conn ->
                conn.send(eventReq)
                conn.send(repliesReq)
            }
        }
        Log.d(TAG, "Fetching thread for $eventId from ${relayUrls.size} relay(s)")
    }

    fun disconnectAll() {
        connections.values.forEach { it.close() }
        connections.clear()
    }
}
