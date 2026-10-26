package com.unsilence.app.data.relay

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "RelayConnection"
private const val MAX_RELAY_MESSAGE_CHARS = 512 * 1024

enum class RelayState { CONNECTING, CONNECTED, DISCONNECTED, FAILED }

/**
 * Categorize an OkHttp WebSocket failure into a [SkipReason] for
 * [RelayCapabilitiesStore]. Returns null only for truly benign failures
 * (e.g. normal close during shutdown).
 */
internal fun categorizeTransportFailure(t: Throwable, response: Response?): SkipReason? {
    // Cleartext: Android wraps the exception class inconsistently — message is most reliable
    if (t.message?.contains("CLEARTEXT communication", ignoreCase = true) == true) {
        return SkipReason.CLEARTEXT_BLOCKED
    }

    // DNS: UnknownHostException is direct
    if (t is java.net.UnknownHostException) {
        return SkipReason.DNS_RESOLUTION
    }

    // SSL/TLS: javax.net.ssl.SSLException and subclasses
    if (t is javax.net.ssl.SSLException) {
        return SkipReason.SSL_ERROR
    }

    // HTTP upgrade failure: OkHttp may throw ProtocolException or attach the response
    if (t is java.net.ProtocolException || response != null) {
        val code = response?.code ?: parseHttpCodeFromMessage(t.message)
        return when {
            code != null && code in 400..499 -> SkipReason.HTTP_UPGRADE_4XX
            code != null && code in 500..599 -> SkipReason.HTTP_UPGRADE_5XX
            else -> SkipReason.UNKNOWN_FAILURE
        }
    }

    // Connect timeout or general connect failure
    if (t is java.net.SocketTimeoutException || t is java.net.ConnectException) {
        return SkipReason.CONNECT_TIMEOUT
    }

    return SkipReason.UNKNOWN_FAILURE
}

/** Parse HTTP status code from OkHttp's "Expected HTTP 101 response but was '502 Bad Gateway'" message. */
private fun parseHttpCodeFromMessage(msg: String?): Int? {
    if (msg == null) return null
    val match = """'(\d{3})\b""".toRegex().find(msg) ?: return null
    return match.groupValues[1].toIntOrNull()
}

/**
 * Single WebSocket connection to one Nostr relay.
 *
 * Thread model: OkHttp calls listener methods on its own threads.
 * We push raw JSON strings into [messages] for the caller to consume in a coroutine.
 * A full inbox holds this socket's reader until capacity or termination. Incoming
 * WebSocket control frames also wait behind it; an independent ping timeout can
 * fail the socket and release the reader. This is bounded backpressure, not a
 * guarantee that a stalled consumer can keep a connection alive indefinitely.
 */
class RelayConnection(
    val url: String,
    private val webSocketFactory: WebSocket.Factory,
    private val capabilitiesStore: RelayCapabilitiesStore? = null,
) {
    private val _messages = Channel<String>(capacity = Channel.BUFFERED)
    val messages: ReceiveChannel<String> get() = _messages

    /** Channel.close alone does not release an already suspended sender. */
    private val terminated = CompletableDeferred<Unit>()

    private val _state = MutableStateFlow(RelayState.DISCONNECTED)
    val state: StateFlow<RelayState> get() = _state.asStateFlow()

    private var ws: WebSocket? = null
    private val connected = AtomicBoolean(false)

    /** True while the WebSocket handshake has completed and onClosed/onFailure has not fired. */
    val isConnected: Boolean get() = _state.value == RelayState.CONNECTED

    /** Suspend until the WebSocket reaches CONNECTED state, or throw on timeout/failure. */
    suspend fun awaitConnected(timeoutMs: Long = 5000) {
        if (_state.value == RelayState.CONNECTED) return
        withTimeout(timeoutMs) {
            val result = _state.first {
                it == RelayState.CONNECTED || it == RelayState.DISCONNECTED || it == RelayState.FAILED
            }
            if (result != RelayState.CONNECTED) {
                error("Relay connection failed: $url")
            }
        }
    }

    fun connect() {
        if (connected.getAndSet(true)) return
        _state.value = RelayState.CONNECTING
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            // Malformed URL — must never crash the app. Mark permanently invalid
            // so shouldSkip prevents all future attempts.
            Log.w(TAG, "connect: rejected invalid relay url ${url.take(80)} — ${e.message}")
            capabilitiesStore?.markStructurallyInvalid(url)
            _state.value = RelayState.FAILED
            connected.set(false)
            terminateInbox()
            return
        }
        ws = webSocketFactory.newWebSocket(request, Listener())
        Log.d(TAG, "Connecting to $url")
    }

    fun send(text: String): Boolean = ws?.send(text) == true

    fun close() {
        val wasConnecting = _state.value == RelayState.CONNECTING
        _state.value = RelayState.DISCONNECTED
        connected.set(false)
        terminateInbox()
        // A graceful close only queues a frame while an upgrade is still pending.
        // Cancel the handshake so a bounded owner cannot leave its socket running.
        if (wasConnecting) ws?.cancel() else ws?.close(1000, "Client shutdown")
        Log.d(TAG, "Closed $url")
    }

    /** Idempotent release for every terminal path, even when nobody drains the inbox. */
    private fun terminateInbox() {
        terminated.complete(Unit)
        _messages.close()
    }

    /** Called only on the OkHttp reader's full-inbox slow path, never the UI thread. */
    private fun awaitInboxCapacity(text: String) {
        try {
            runBlocking {
                select<Unit> {
                    // Prefer termination when both clauses are ready.
                    terminated.onAwait {}
                    _messages.onSend(text) {}
                }
            }
        } catch (_: ClosedSendChannelException) {
            // Inbox terminated while entering the slow path.
        } catch (_: CancellationException) {
            // ReceiveChannel.cancel (including consumeEach cancellation) releases onSend.
            // This callback has no caller coroutine Job whose cancellation could be swallowed.
        } catch (interrupted: InterruptedException) {
            // Do not silently lose a frame and continue an otherwise live connection.
            close()
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.value = RelayState.CONNECTED
            capabilitiesStore?.clearTransportStrikes(url)
            Log.d(TAG, "Connected: $url")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (terminated.isCompleted) return
            if (text.length > MAX_RELAY_MESSAGE_CHARS) {
                Log.w(TAG, "Dropping oversized relay frame from $url (${text.length} chars)")
                webSocket.close(1009, "Message too large")
                // The inbox consumer may reconnect immediately when it ends. Publish
                // terminal state first; onClosed can arrive after that listener exits.
                _state.value = RelayState.DISCONNECTED
                connected.set(false)
                terminateInbox()
                return
            }
            val offered = _messages.trySend(text)
            if (offered.isSuccess || offered.isClosed) return
            awaitInboxCapacity(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Failure on $url: ${t.message}")
            _state.value = RelayState.FAILED
            connected.set(false)
            terminateInbox()

            // Record transport failure for capability tracking
            val reason = categorizeTransportFailure(t, response)
            if (reason != null) {
                capabilitiesStore?.recordTransportFailure(url, reason)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WS closed by remote: url=$url code=$code reason='$reason' (was state=${_state.value})")
            _state.value = RelayState.DISCONNECTED
            connected.set(false)
            terminateInbox()
        }
    }
}
