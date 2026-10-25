package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.UserEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TRENDING_RELAY_URL = "wss://trending.relays.land"
internal const val TRENDING_CANDIDATE_LIMIT = 32

data class TrendingHashtag(val tag: String, val score: Double)

data class TrendingProfile(
    val pubkey: String,
    val name: String?,
    val displayName: String?,
    val picture: String?,
    val about: String?,
    val nip05: String?,
    /** Null means unresolved; zero is retained only when a COUNT relay answered zero. */
    val followerCount: Long?,
)

data class TrendingData(
    val hashtags: List<TrendingHashtag>,
    val profiles: List<TrendingProfile>,
)

enum class TrendingOutcome { Idle, Success, Empty, Unavailable }

/** One shared progressive snapshot, including terminal outcomes when no data exists. */
data class TrendingState(
    val data: TrendingData? = null,
    val refreshing: Boolean = false,
    val outcome: TrendingOutcome = TrendingOutcome.Idle,
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
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
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

    private val refreshLock = Any()
    // All attempt/freshness fields are guarded by refreshLock. A completed job is
    // retained until the next attempt; waiters never enqueue another forced fetch.
    private var inFlight: Deferred<Unit>? = null
    private var lastFetchMs: Long? = null
    private var freshnessWindowMs = TRENDING_FRESH_MS
    private var failureRetryAtMs: Long? = null
    private var lastManualRetryMs: Long? = null

    private val _state = MutableStateFlow(TrendingState())
    val state: StateFlow<TrendingState> = _state.asStateFlow()

    fun refreshIfStale(forceRefresh: Boolean = false) {
        attempt(forceRefresh)?.start()
    }

    /** Explicit intent bypasses automatic cooldowns, but not single-flight/tap debounce. */
    fun retry() { attempt(forceRefresh = true, manual = true)?.start() }

    suspend fun fetch(forceRefresh: Boolean = false): TrendingData? {
        refresh(forceRefresh)
        return state.value.data
    }

    internal suspend fun refresh(forceRefresh: Boolean = false) {
        attempt(forceRefresh)?.await()
    }

    private fun attempt(forceRefresh: Boolean, manual: Boolean = false): Deferred<Unit>? =
        synchronized(refreshLock) {
            inFlight?.takeUnless { it.isCompleted }?.let { return@synchronized it }
            val now = nowMs()
            if (manual && lastManualRetryMs?.let { now - it < MANUAL_RETRY_MS } == true) {
                return@synchronized null
            }
            if (!forceRefresh && (
                failureRetryAtMs?.let { now < it } == true ||
                    lastFetchMs?.let { now - it < freshnessWindowMs } == true
                )) return@synchronized null
            if (manual) lastManualRetryMs = now
            _state.update { it.copy(refreshing = true) }
            val job = scope.async(start = CoroutineStart.LAZY) {
                try {
                    doRefresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    finishWithoutData(TrendingOutcome.Unavailable)
                }
            }
            inFlight = job
            job.invokeOnCompletion { cause ->
                // Also covers cancellation before a lazy job starts. Cancelling a
                // waiter leaves the shared request running for its other consumers.
                if (cause != null) synchronized(refreshLock) {
                    if (inFlight === job) _state.update {
                        it.copy(refreshing = false, outcome = TrendingOutcome.Unavailable)
                    }
                }
            }
            job
        }

    private fun finishWithoutData(outcome: TrendingOutcome) = synchronized(refreshLock) {
        failureRetryAtMs = nowMs() + TRENDING_RETRY_MS
        _state.update { it.copy(refreshing = false, outcome = outcome) }
    }

    private suspend fun doRefresh(): Unit = coroutineScope {
        val warm = launch {
            try {
                withTimeoutOrNull(3_000L) { transport.warmCountRelay() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        val events = withTimeoutOrNull(5_000L) { transport.fetchTrendingEvents() }
        if (events.isNullOrEmpty()) {
            warm.cancel()
            finishWithoutData(if (events == null) TrendingOutcome.Unavailable else TrendingOutcome.Empty)
            return@coroutineScope
        }
        val (topHashtags, topAuthorPubkeys) = aggregate(events)
        if (topHashtags.isEmpty() && topAuthorPubkeys.isEmpty()) {
            warm.cancel()
            finishWithoutData(TrendingOutcome.Empty)
            return@coroutineScope
        }

        val previousCounts = _state.value.data?.profiles
            ?.associate { it.pubkey to it.followerCount }
            .orEmpty()
        val firstData = TrendingData(
            hashtags = topHashtags,
            profiles = topAuthorPubkeys.map { pubkey ->
                toProfile(pubkey, previousCounts[pubkey])
            },
        )
        _state.value = TrendingState(firstData, refreshing = true, outcome = TrendingOutcome.Success)

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
                toProfile(pubkey, count ?: previousCounts[pubkey]) to (count != null)
            }
        }.awaitAll()

        val enrichedData = TrendingData(
            hashtags = topHashtags,
            profiles = enriched.map { it.first },
        )
        synchronized(refreshLock) {
            freshnessWindowMs = if (enriched.any { it.second }) TRENDING_FRESH_MS else TRENDING_RETRY_MS
            lastFetchMs = nowMs()
            failureRetryAtMs = null
            _state.value = TrendingState(enrichedData, outcome = TrendingOutcome.Success)
        }
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

    private fun toProfile(pubkey: String, followerCount: Long?): TrendingProfile {
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
        private const val TRENDING_FRESH_MS = 10 * 60 * 1000L
        private const val TRENDING_RETRY_MS = 60_000L
        private const val COUNT_TIMEOUT_MS = 2_500L
        private const val MANUAL_RETRY_MS = 1_500L
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
                        } catch (_: Exception) {}
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = Unit
                })

                cont.invokeOnCancellation { ws.cancel() }
            }
        }
    }

    override suspend fun warmCountRelay() {
        coroutineScope {
            FOLLOWER_COUNT_RELAY_URLS.map { relayUrl ->
                async {
                    relayPool.connectAndAwait(
                        listOf(relayUrl),
                        timeoutMs = 3_000,
                        forceEvict = true,
                    )
                }
            }.awaitAll()
        }
    }

    override suspend fun fetchFollowerCount(pubkey: String): Long? {
        val filter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(3)) })
            put("#p", buildJsonArray { add(JsonPrimitive(pubkey)) })
        }
        return coroutineScope {
            FOLLOWER_COUNT_RELAY_URLS.map { relayUrl ->
                async {
                    relayPool.sendCount(
                        relayUrl = relayUrl,
                        filter = filter,
                        timeoutMs = 2_500L,
                    )
                }
            }.awaitAll()
        }.let(::maxFollowerCount)
    }
}
