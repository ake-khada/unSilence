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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
private const val PRIMAL_METADATA_KIND = 0
private const val PRIMAL_PAGING_KIND = 10000113
private const val PRIMAL_FOLLOWER_COUNTS_KIND = 10000133
private const val PRIMAL_CONNECT_TIMEOUT_SECONDS = 5L
private const val PRIMAL_OVERALL_TIMEOUT_MS = 8_000L

internal data class PrimalSuggestedProfile(
    val pubkey: String,
    val name: String?,
    val displayName: String?,
    val picture: String?,
    val about: String?,
    val nip05: String?,
    val followerCount: Long?,
)

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

internal fun parsePrimalSuggestedProfiles(frames: Collection<String>): List<PrimalSuggestedProfile> {
    val metadata = LinkedHashMap<String, JsonObject>()
    val encounterOrder = LinkedHashSet<String>()
    val pagingOrder = ArrayList<String>()
    val followerCounts = HashMap<String, Long>()

    frames.forEach { text ->
        val event = runCatching {
            val frame = NostrJson.parseToJsonElement(text).jsonArray
            if (frame.size < 3 || frame[0].jsonPrimitive.content != "EVENT") return@runCatching null
            frame[2].jsonObject
        }.getOrNull() ?: return@forEach

        val kind = event["kind"]?.jsonPrimitive?.intOrNull ?: return@forEach
        val content = event["content"]?.jsonPrimitive?.contentOrNull ?: return@forEach
        val contentObject = runCatching { NostrJson.parseToJsonElement(content).jsonObject }.getOrNull()
            ?: return@forEach
        when (kind) {
            PRIMAL_METADATA_KIND -> {
                val pubkey = normalizeWotPubkey(event["pubkey"]?.jsonPrimitive?.contentOrNull)
                    ?: return@forEach
                metadata[pubkey] = contentObject
                encounterOrder.add(pubkey)
            }
            PRIMAL_PAGING_KIND -> {
                contentObject["elements"]?.let { elements ->
                    runCatching { elements.jsonArray }
                        .getOrNull()
                        ?.mapNotNullTo(pagingOrder) {
                            normalizeWotPubkey(it.jsonPrimitive.contentOrNull)
                        }
                }
            }
            PRIMAL_FOLLOWER_COUNTS_KIND -> {
                contentObject.forEach { (rawPubkey, value) ->
                    val pubkey = normalizeWotPubkey(rawPubkey) ?: return@forEach
                    value.jsonPrimitive.longOrNull?.let { followerCounts[pubkey] = it }
                }
            }
            PRIMAL_PROFILE_KIND -> {
                val pubkey = normalizeWotPubkey(
                    contentObject["pubkey"]?.jsonPrimitive?.contentOrNull
                        ?: event["pubkey"]?.jsonPrimitive?.contentOrNull,
                ) ?: return@forEach
                contentObject["followers_count"]?.jsonPrimitive?.longOrNull?.let {
                    followerCounts[pubkey] = it
                }
            }
        }
    }

    val orderedPubkeys = (pagingOrder.asSequence() + encounterOrder.asSequence()).distinct()
    return orderedPubkeys.mapNotNull { pubkey ->
        val profile = metadata[pubkey] ?: return@mapNotNull null
        PrimalSuggestedProfile(
            pubkey = pubkey,
            name = profile["name"]?.jsonPrimitive?.contentOrNull,
            displayName = profile["display_name"]?.jsonPrimitive?.contentOrNull,
            picture = profile["picture"]?.jsonPrimitive?.contentOrNull,
            about = profile["about"]?.jsonPrimitive?.contentOrNull,
            nip05 = profile["nip05"]?.jsonPrimitive?.contentOrNull,
            followerCount = followerCounts[pubkey],
        )
    }.toList()
}

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

    /** Fallback only when the active WoT corpus cannot provide notable people. */
    internal suspend fun fetchTrendingProfiles(
        ownPubkey: String,
        limit: Int = NOTABLE_PEOPLE_LIMIT,
    ): List<PrimalSuggestedProfile>? = withContext(Dispatchers.IO) {
        try {
            val frames = fetchCacheFrames(
                cacheVerb = "explore_people",
                options = buildJsonObject {
                    put("user_pubkey", ownPubkey)
                    put("limit", limit)
                    put("offset", 0)
                },
            ) ?: return@withContext null
            parsePrimalSuggestedProfiles(frames).take(limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchCacheFrames(
        cacheVerb: String,
        options: JsonObject,
    ): List<String>? = withTimeoutOrNull(PRIMAL_OVERALL_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val subId = "cache-${System.nanoTime()}"
            val requestMessage = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(subId))
                add(buildJsonObject {
                    put("cache", buildJsonArray {
                        add(JsonPrimitive(cacheVerb))
                        add(options)
                    })
                })
            }.toString()
            val closeMessage = buildJsonArray {
                add(JsonPrimitive("CLOSE"))
                add(JsonPrimitive(subId))
            }.toString()
            val completed = AtomicBoolean(false)
            val socketRef = AtomicReference<WebSocket?>()
            val frames = ArrayList<String>()

            fun finish(value: List<String>?, webSocket: WebSocket? = socketRef.get()) {
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
                    val frameTypeAndSub = runCatching {
                        val frame = NostrJson.parseToJsonElement(text).jsonArray
                        frame.getOrNull(0)?.jsonPrimitive?.contentOrNull to
                            frame.getOrNull(1)?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    when {
                        frameTypeAndSub?.first == "EVENT" && frameTypeAndSub.second == subId -> {
                            frames.add(text)
                        }
                        frameTypeAndSub?.first == "EOSE" && frameTypeAndSub.second == subId -> {
                            finish(frames.toList(), webSocket)
                        }
                        frameTypeAndSub?.first == "CLOSED" && frameTypeAndSub.second == subId -> {
                            finish(null, webSocket)
                        }
                    }
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
}
