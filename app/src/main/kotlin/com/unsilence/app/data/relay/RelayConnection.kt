package com.unsilence.app.data.relay

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "RelayConnection"

/**
 * Single WebSocket connection to one Nostr relay.
 *
 * Thread model: OkHttp calls listener methods on its own threads.
 * We push raw JSON strings into [messages] for the caller to consume in a coroutine.
 */
class RelayConnection(
    val url: String,
    private val client: OkHttpClient,
) {
    private val _messages = Channel<String>(capacity = Channel.BUFFERED)
    val messages: ReceiveChannel<String> get() = _messages

    private var ws: WebSocket? = null
    private val connected = AtomicBoolean(false)

    /** True while the WebSocket handshake has completed and onClosed/onFailure has not fired. */
    var isConnected: Boolean = false
        private set

    fun connect() {
        if (connected.getAndSet(true)) return
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, Listener())
        Log.d(TAG, "Connecting to $url")
    }

    fun send(text: String): Boolean = ws?.send(text) == true

    fun close() {
        connected.set(false)
        ws?.close(1000, "Client shutdown")
        _messages.close()
        Log.d(TAG, "Closed $url")
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            Log.d(TAG, "Connected: $url")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _messages.trySend(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Failure on $url: ${t.message}")
            isConnected = false
            connected.set(false)
            _messages.close()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Closed $url: $code $reason")
            isConnected = false
            connected.set(false)
            _messages.close()
        }
    }
}
