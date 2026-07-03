package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.MemoryEventStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "TrendingClient"

data class TrendingHashtag(val tag: String, val score: Double)

data class TrendingProfile(
    val pubkey: String,
    val name: String?,
    val displayName: String?,
    val picture: String?,
    val about: String?,
    val nip05: String?,
    val followerCount: Long,
)

data class TrendingData(
    val hashtags: List<TrendingHashtag>,
    val profiles: List<TrendingProfile>,
)

@Singleton
class TrendingClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
) {
    /** Staleness guard: don't fetch more than once per 30 minutes. */
    private val lastFetchMs = AtomicLong(0L)
    @Volatile private var cachedData: TrendingData? = null
    private val fetchMutex = Mutex()

    /** Ping-free client for the short-lived trending WS — the base client's
     *  25s pingInterval would schedule keepalives on a ≤5s one-shot socket.
     *  newBuilder() shares the base client's pools, so this is cheap. */
    private val wsClient by lazy {
        okHttpClient.newBuilder().pingInterval(0, TimeUnit.MILLISECONDS).build()
    }

    suspend fun fetch(forceRefresh: Boolean = false): TrendingData? {
        val now = System.currentTimeMillis()
        val cached = cachedData
        if (!forceRefresh && cached != null && now - lastFetchMs.get() < STALENESS_MS) {
            return cached
        }

        return fetchMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedCached = cachedData
            if (!forceRefresh && lockedCached != null && lockedNow - lastFetchMs.get() < STALENESS_MS) {
                return@withLock lockedCached
            }

            val result = withContext(Dispatchers.IO) {
                try {
                    fetchFromTrendingRelay()
                } catch (e: Exception) {
                    Log.w(TAG, "Trending fetch failed: ${e.message}")
                    null
                }
            }

            if (result != null) {
                cachedData = result
                lastFetchMs.set(System.currentTimeMillis())
            }
            result ?: lockedCached
        }
    }

    /**
     * Connect to trending.relays.land, collect kind-1 events, then derive
     * hashtag frequencies and top author profiles client-side.
     */
    private suspend fun fetchFromTrendingRelay(): TrendingData? {
        val events = fetchTrendingEvents() ?: return null
        if (events.isEmpty()) return null

        // Client-side aggregation: t-tag frequencies + author frequencies
        val tagCounts = mutableMapOf<String, Int>()
        val authorCounts = mutableMapOf<String, Int>()

        for (event in events) {
            val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: continue
            authorCounts[pubkey] = (authorCounts[pubkey] ?: 0) + 1

            val tags = event["tags"]?.jsonArray ?: continue
            for (tag in tags) {
                val arr = tag.jsonArray
                if (arr.size >= 2 && arr[0].jsonPrimitive.content == "t") {
                    val value = arr[1].jsonPrimitive.content.lowercase()
                    tagCounts[value] = (tagCounts[value] ?: 0) + 1
                }
            }
        }

        val topHashtags = tagCounts.entries
            .sortedByDescending { it.value }
            .take(TRENDING_CANDIDATE_LIMIT)
            .map { TrendingHashtag(it.key, it.value.toDouble()) }

        val topAuthorPubkeys = authorCounts.entries
            .sortedByDescending { it.value }
            .take(TRENDING_CANDIDATE_LIMIT)
            .map { it.key }

        val profiles = enrichProfiles(topAuthorPubkeys)

        Log.d(TAG, "Trending: ${topHashtags.size} tags, ${profiles.size} profiles from ${events.size} events")
        return TrendingData(hashtags = topHashtags, profiles = profiles)
    }

    /** Standard NIP-01 REQ for kind 1, limit 200. Collects until EOSE or 5s timeout. */
    private suspend fun fetchTrendingEvents(): List<JsonObject>? {
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                val subId = "trending-${System.nanoTime()}"
                val req = buildJsonArray {
                    add(JsonPrimitive("REQ"))
                    add(JsonPrimitive(subId))
                    add(buildJsonObject {
                        put("kinds", buildJsonArray { add(JsonPrimitive(1)) })
                        put("limit", JsonPrimitive(200))
                    })
                }.toString()

                val events = mutableListOf<JsonObject>()
                val request = Request.Builder().url(TRENDING_RELAY_URL).build()
                val ws = wsClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d(TAG, "Connected to $TRENDING_RELAY_URL")
                        webSocket.send(req)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val arr = Json.parseToJsonElement(text).jsonArray
                            when (arr[0].jsonPrimitive.content) {
                                "EVENT" -> {
                                    events.add(arr[2].jsonObject)
                                }
                                "EOSE" -> {
                                    webSocket.send(buildJsonArray {
                                        add(JsonPrimitive("CLOSE"))
                                        add(JsonPrimitive(subId))
                                    }.toString())
                                    webSocket.close(1000, "done")
                                    if (cont.isActive) cont.resume(events)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Parse error: ${e.message}")
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "WebSocket failure: ${t.message}")
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "WebSocket closed: $code")
                    }
                })

                cont.invokeOnCancellation { ws.cancel() }
            }
        }
    }

    /**
     * For each pubkey: look up profile in MES, fetch follower count via
     * antiprimal NIP-45 COUNT (parallel).
     */
    private suspend fun enrichProfiles(pubkeys: List<String>): List<TrendingProfile> =
        coroutineScope {
            relayPool.connectAndAwait(
                listOf(ANTIPRIMAL_RELAY_URL), timeoutMs = 3_000, forceEvict = true,
            )

            pubkeys.map { pubkey ->
                async {
                    val user = memoryEventStore.getUserEntity(pubkey)
                    val followerCount = try {
                        relayPool.sendCount(
                            relayUrl = ANTIPRIMAL_RELAY_URL,
                            filter = buildJsonObject {
                                put("kinds", buildJsonArray { add(JsonPrimitive(3)) })
                                put("#p", buildJsonArray { add(JsonPrimitive(pubkey)) })
                            },
                        ) ?: 0L
                    } catch (_: Exception) { 0L }

                    TrendingProfile(
                        pubkey = pubkey,
                        name = user?.name,
                        displayName = user?.displayName,
                        picture = user?.picture,
                        about = user?.about,
                        nip05 = user?.nip05,
                        followerCount = followerCount,
                    )
                }
            }.awaitAll()
        }

    companion object {
        private const val STALENESS_MS = 30 * 60 * 1000L // 30 minutes
        private const val TRENDING_CANDIDATE_LIMIT = 32
        private const val TRENDING_RELAY_URL = "wss://trending.relays.land"
    }
}
