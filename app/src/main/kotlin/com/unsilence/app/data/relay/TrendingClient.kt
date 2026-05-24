package com.unsilence.app.data.relay

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
) {
    /** Staleness guard: don't fetch more than once per 30 minutes. */
    private val lastFetchMs = AtomicLong(0L)
    @Volatile private var cachedData: TrendingData? = null

    suspend fun fetch(forceRefresh: Boolean = false): TrendingData? {
        val now = System.currentTimeMillis()
        val cached = cachedData
        if (!forceRefresh && cached != null && now - lastFetchMs.get() < STALENESS_MS) {
            return cached
        }

        val result = withContext(Dispatchers.IO) {
            try {
                fetchFromAntiprimal()
            } catch (e: Exception) {
                Log.w(TAG, "Trending fetch failed: ${e.message}")
                null
            }
        }

        if (result != null) {
            cachedData = result
            lastFetchMs.set(now)
        }
        return result ?: cached
    }

    private suspend fun fetchFromAntiprimal(): TrendingData? {
        return withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                val hashtagSubId = "trending-tags-${System.nanoTime()}"
                val usersSubId = "trending-users-${System.nanoTime()}"

                val hashtagReq = buildJsonArray {
                    add(JsonPrimitive("REQ"))
                    add(JsonPrimitive(hashtagSubId))
                    add(buildJsonObject {
                        put("cache", buildJsonArray {
                            add(JsonPrimitive("trending_hashtags_4h"))
                        })
                    })
                }.toString()

                val usersReq = buildJsonArray {
                    add(JsonPrimitive("REQ"))
                    add(JsonPrimitive(usersSubId))
                    add(buildJsonObject {
                        put("cache", buildJsonArray {
                            add(JsonPrimitive("explore_people"))
                            add(buildJsonObject {
                                put("limit", JsonPrimitive(8))
                            })
                        })
                    })
                }.toString()

                val hashtags = mutableListOf<TrendingHashtag>()
                val profiles = mutableMapOf<String, MutableMap<String, String?>>()
                val followerCounts = mutableMapOf<String, Long>()
                var hashtagEose = false
                var usersEose = false

                val request = Request.Builder().url(PRIMAL_CACHE_URL).build()
                val ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d(TAG, "Connected to Primal cache for trending")
                        webSocket.send(hashtagReq)
                        webSocket.send(usersReq)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val arr = Json.parseToJsonElement(text).jsonArray
                            val type = arr[0].jsonPrimitive.content

                            when (type) {
                                "EVENT" -> {
                                    val subId = arr[1].jsonPrimitive.content
                                    val event = arr[2].jsonObject
                                    val kind = event["kind"]?.jsonPrimitive?.int ?: return
                                    val content = event["content"]?.jsonPrimitive?.content ?: return

                                    when {
                                        subId == hashtagSubId && kind == 10000116 -> {
                                            parseHashtags(content, hashtags)
                                        }
                                        subId == usersSubId && kind == 0 -> {
                                            val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return
                                            parseProfile(pubkey, content, profiles)
                                        }
                                        subId == usersSubId && kind == 10000133 -> {
                                            parseFollowerCounts(content, followerCounts)
                                        }
                                    }
                                }
                                "EOSE" -> {
                                    val subId = arr[1].jsonPrimitive.content
                                    if (subId == hashtagSubId) hashtagEose = true
                                    if (subId == usersSubId) usersEose = true

                                    if (hashtagEose && usersEose) {
                                        // Send CLOSE for both subs
                                        webSocket.send(buildJsonArray {
                                            add(JsonPrimitive("CLOSE"))
                                            add(JsonPrimitive(hashtagSubId))
                                        }.toString())
                                        webSocket.send(buildJsonArray {
                                            add(JsonPrimitive("CLOSE"))
                                            add(JsonPrimitive(usersSubId))
                                        }.toString())
                                        webSocket.close(1000, "done")

                                        val result = assembleTrendingData(
                                            hashtags, profiles, followerCounts,
                                        )
                                        if (cont.isActive) cont.resume(result)
                                    }
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

                cont.invokeOnCancellation {
                    ws.cancel()
                }
            }
        }
    }

    private fun parseHashtags(content: String, out: MutableList<TrendingHashtag>) {
        try {
            val obj = Json.parseToJsonElement(content).jsonObject
            for ((tag, scoreEl) in obj) {
                val score = scoreEl.jsonPrimitive.content.toDoubleOrNull() ?: continue
                out.add(TrendingHashtag(tag, score))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse hashtags: ${e.message}")
        }
    }

    private fun parseProfile(
        pubkey: String,
        content: String,
        out: MutableMap<String, MutableMap<String, String?>>,
    ) {
        try {
            val obj = Json.parseToJsonElement(content).jsonObject
            out[pubkey] = mutableMapOf(
                "name" to obj["name"]?.jsonPrimitive?.content,
                "display_name" to obj["display_name"]?.jsonPrimitive?.content,
                "picture" to obj["picture"]?.jsonPrimitive?.content,
                "about" to obj["about"]?.jsonPrimitive?.content,
                "nip05" to obj["nip05"]?.jsonPrimitive?.content,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse profile: ${e.message}")
        }
    }

    private fun parseFollowerCounts(
        content: String,
        out: MutableMap<String, Long>,
    ) {
        try {
            val obj = Json.parseToJsonElement(content).jsonObject
            for ((pubkey, countEl) in obj) {
                out[pubkey] = countEl.jsonPrimitive.long
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse follower counts: ${e.message}")
        }
    }

    private fun assembleTrendingData(
        hashtags: List<TrendingHashtag>,
        profiles: Map<String, Map<String, String?>>,
        followerCounts: Map<String, Long>,
    ): TrendingData {
        // Hashtags: top 8, sorted by score descending
        val topHashtags = hashtags
            .sortedByDescending { it.score }
            .take(8)

        // Profiles: merge with follower counts, top 8 by follower count
        val topProfiles = profiles.map { (pubkey, meta) ->
            TrendingProfile(
                pubkey = pubkey,
                name = meta["name"],
                displayName = meta["display_name"],
                picture = meta["picture"],
                about = meta["about"],
                nip05 = meta["nip05"],
                followerCount = followerCounts[pubkey] ?: 0L,
            )
        }
            .sortedByDescending { it.followerCount }
            .take(8)

        Log.d(TAG, "Trending: ${topHashtags.size} tags, ${topProfiles.size} profiles")
        return TrendingData(hashtags = topHashtags, profiles = topProfiles)
    }

    companion object {
        private const val STALENESS_MS = 30 * 60 * 1000L // 30 minutes
        private const val PRIMAL_CACHE_URL = "wss://cache2.primal.net/v1"
    }
}
