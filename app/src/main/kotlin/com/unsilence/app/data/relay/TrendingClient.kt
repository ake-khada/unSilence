package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.UserEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
private const val TRENDING_RELAY_URL = "wss://trending.relays.land"

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

internal interface TrendingTransport {
    suspend fun fetchTrendingEvents(): List<JsonObject>?
    suspend fun warmCountRelay()
    suspend fun fetchFollowerCount(pubkey: String): Long?
}

@Singleton
class TrendingClient internal constructor(
    private val transport: TrendingTransport,
    private val profileLookup: (String) -> UserEntity?,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        okHttpClient: OkHttpClient,
        memoryEventStore: MemoryEventStore,
        relayPool: RelayPool,
    ) : this(
        transport = NetworkTrendingTransport(okHttpClient, relayPool),
        profileLookup = memoryEventStore::getUserEntity,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val lastFetchMs = AtomicLong(0L)
    private val refreshMutex = Mutex()

    private val _data = MutableStateFlow<TrendingData?>(null)
    val data: StateFlow<TrendingData?> = _data.asStateFlow()

    fun refreshIfStale(forceRefresh: Boolean = false) {
        if (!forceRefresh && isFresh()) return
        scope.launch { refresh(forceRefresh) }
    }

    suspend fun fetch(forceRefresh: Boolean = false): TrendingData? {
        refresh(forceRefresh)
        return data.value
    }

    internal suspend fun refresh(forceRefresh: Boolean = false) {
        if (!forceRefresh && isFresh()) return
        refreshMutex.withLock {
            if (!forceRefresh && isFresh()) return
            try {
                doRefresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Trending refresh failed: ${e.message}")
            }
        }
    }

    private fun isFresh(): Boolean =
        _data.value != null && System.currentTimeMillis() - lastFetchMs.get() < STALENESS_MS

    private suspend fun doRefresh(): Unit = coroutineScope {
        val startMs = System.currentTimeMillis()
        val warm = launch {
            try {
                transport.warmCountRelay()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "COUNT relay warm failed: ${e.message}")
            }
        }

        val events = transport.fetchTrendingEvents()
        if (events.isNullOrEmpty()) {
            warm.cancel()
            return@coroutineScope
        }
        val (topHashtags, topAuthorPubkeys) = aggregate(events)
        if (topHashtags.isEmpty() && topAuthorPubkeys.isEmpty()) {
            warm.cancel()
            return@coroutineScope
        }

        val previousCounts = _data.value?.profiles
            ?.associate { it.pubkey to it.followerCount }
            .orEmpty()
        _data.value = TrendingData(
            hashtags = topHashtags,
            profiles = topAuthorPubkeys.map { pubkey ->
                toProfile(pubkey, previousCounts[pubkey] ?: 0L)
            },
        )
        lastFetchMs.set(System.currentTimeMillis())
        val phase1Ms = System.currentTimeMillis() - startMs

        warm.join()
        val enriched = topAuthorPubkeys.map { pubkey ->
            async {
                val count = try {
                    withTimeoutOrNull(COUNT_TIMEOUT_MS) {
                        transport.fetchFollowerCount(pubkey)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                toProfile(pubkey, count ?: previousCounts[pubkey] ?: 0L)
            }
        }.awaitAll()

        _data.value = TrendingData(hashtags = topHashtags, profiles = enriched)
        Log.w(
            TAG,
            "Trending: ${topHashtags.size} tags, ${enriched.size} profiles from ${events.size} " +
                "events (phase1=${phase1Ms}ms total=${System.currentTimeMillis() - startMs}ms)",
        )
    }

    private fun aggregate(events: List<JsonObject>): Pair<List<TrendingHashtag>, List<String>> {
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

        return topHashtags to topAuthorPubkeys
    }

    private fun toProfile(pubkey: String, followerCount: Long): TrendingProfile {
        val user = profileLookup(pubkey)
        return TrendingProfile(
            pubkey = pubkey,
            name = user?.name,
            displayName = user?.displayName,
            picture = user?.picture,
            about = user?.about,
            nip05 = user?.nip05,
            followerCount = followerCount,
        )
    }

    companion object {
        private const val STALENESS_MS = 10 * 60 * 1000L
        private const val TRENDING_CANDIDATE_LIMIT = 32
        private const val COUNT_TIMEOUT_MS = 2_500L
    }
}

private class NetworkTrendingTransport(
    okHttpClient: OkHttpClient,
    private val relayPool: RelayPool,
) : TrendingTransport {
    private val wsClient by lazy {
        okHttpClient.newBuilder().pingInterval(0, TimeUnit.MILLISECONDS).build()
    }

    override suspend fun fetchTrendingEvents(): List<JsonObject>? {
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
                                "EVENT" -> events.add(arr[2].jsonObject)
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

    override suspend fun warmCountRelay() {
        relayPool.connectAndAwait(
            listOf(ANTIPRIMAL_RELAY_URL),
            timeoutMs = 3_000,
            forceEvict = true,
        )
    }

    override suspend fun fetchFollowerCount(pubkey: String): Long? =
        relayPool.sendCount(
            relayUrl = ANTIPRIMAL_RELAY_URL,
            filter = buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(3)) })
                put("#p", buildJsonArray { add(JsonPrimitive(pubkey)) })
            },
            timeoutMs = 2_500L,
        )
}
