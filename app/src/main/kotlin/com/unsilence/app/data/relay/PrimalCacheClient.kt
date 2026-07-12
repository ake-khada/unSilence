package com.unsilence.app.data.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val PRIMAL_CACHE_URL = "wss://cache2.primal.net/v1"
private const val PRIMAL_PROFILE_KIND = 10000105
private const val PRIMAL_CONNECT_TIMEOUT_SECONDS = 5L
private const val PRIMAL_OVERALL_TIMEOUT_MS = 8_000L

internal fun parsePrimalFollowerCountFrame(text: String): Long? = runCatching {
    val frame = NostrJson.parseToJsonElement(text).jsonArray
    if (frame.size < 3 || frame[0].jsonPrimitive.content != "EVENT") return@runCatching null
    val event = frame[2].jsonObject
    if (event["kind"]?.jsonPrimitive?.intOrNull != PRIMAL_PROFILE_KIND) return@runCatching null
    val content = event["content"]?.jsonPrimitive?.content ?: return@runCatching null
    NostrJson.parseToJsonElement(content)
        .jsonObject["followers_count"]
        ?.jsonPrimitive
        ?.longOrNull
}.getOrNull()

/** One-shot access to Primal's cache protocol. This is intentionally not a RelayPool connection. */
@Singleton
class PrimalCacheClient @Inject constructor(baseClient: OkHttpClient) {
    private val client = baseClient.newBuilder()
        .connectTimeout(PRIMAL_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun fetchFollowerCount(pubkey: String): Long? = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(PRIMAL_OVERALL_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val subId = "profile-count-${System.nanoTime()}"
                    val requestMessage = buildJsonArray {
                        add(JsonPrimitive("REQ"))
                        add(JsonPrimitive(subId))
                        add(buildJsonObject {
                            put("cache", buildJsonArray {
                                add(JsonPrimitive("user_profile"))
                                add(buildJsonObject { put("pubkey", pubkey) })
                            })
                        })
                    }.toString()
                    val closeMessage = buildJsonArray {
                        add(JsonPrimitive("CLOSE"))
                        add(JsonPrimitive(subId))
                    }.toString()
                    val completed = AtomicBoolean(false)
                    val socketRef = AtomicReference<WebSocket?>()

                    fun finish(value: Long?, webSocket: WebSocket? = socketRef.get()) {
                        if (!completed.compareAndSet(false, true)) return
                        webSocket?.send(closeMessage)
                        if (webSocket?.close(1000, "done") == false) webSocket.cancel()
                        if (continuation.isActive) continuation.resume(value)
                    }

                    val request = Request.Builder().url(PRIMAL_CACHE_URL).build()
                    val socket = client.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (completed.get()) {
                                webSocket.close(1000, "cancelled")
                                return
                            }
                            if (!webSocket.send(requestMessage)) finish(null, webSocket)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            parsePrimalFollowerCountFrame(text)?.let {
                                finish(it, webSocket)
                                return
                            }
                            val isEose = runCatching {
                                val frame = NostrJson.parseToJsonElement(text).jsonArray
                                frame.size >= 2 &&
                                    frame[0].jsonPrimitive.content == "EOSE" &&
                                    frame[1].jsonPrimitive.content == subId
                            }.getOrDefault(false)
                            if (isEose) finish(null, webSocket)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            finish(null, webSocket)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            finish(null, webSocket)
                        }
                    })
                    socketRef.set(socket)
                    if (completed.get()) socket.cancel()

                    continuation.invokeOnCancellation {
                        if (completed.compareAndSet(false, true)) {
                            val activeSocket = socketRef.get()
                            if (activeSocket?.close(1000, "cancelled") == false) activeSocket.cancel()
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
