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

    /** Subscribe to kind 0 profiles for a batch of pubkeys. */
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

        connections.values.firstOrNull()?.send(req)
    }

    fun disconnectAll() {
        connections.values.forEach { it.close() }
        connections.clear()
    }
}
