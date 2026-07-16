package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.MemoryEventStore
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_PARALLEL_NIP11_FETCHES = 4
private const val MAX_NIP11_BODY_CHARS = 256 * 1024
private const val MAX_NIP11_NAME_CHARS = 200
private const val MAX_NIP11_TEXT_CHARS = 4 * 1024
private const val MAX_NIP11_FIELD_CHARS = 500
private const val MAX_NIP11_URL_CHARS = 2 * 1024
private const val MAX_SUPPORTED_NIPS = 256

/**
 * A NIP-11 document fetched by THIS device, directly from the relay's host
 * (`GET https://<host>` with `Accept: application/nostr+json`). Distinct from the
 * monitor-sourced NIP-11 embedded in kind-30166: the device fetch is the AUTHORITY
 * (the PERSPECTIVE RULE) — perspective-dependent relays (subnet.relays.land) serve a
 * different identity per viewer, and the censor sits between this device and the relay,
 * so what the monitor saw is only a discovery seed. All fields tolerate absence (null).
 */
data class DeviceNip11Doc(
    val name: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val software: String? = null,
    val version: String? = null,
    val supportedNips: Set<Int> = emptySet(),
    val authRequired: Boolean? = null,
    val paymentRequired: Boolean? = null,
    val restrictedWrites: Boolean? = null,
    val feeMsats: Long? = null,          // fees.admission[0], normalized to msats
    val operatorPubkey: String? = null,  // NIP-11 `pubkey` (hex) — the operator self-ID
    val contact: String? = null,         // NIP-11 `contact`
)

internal fun parseDeviceNip11(body: String): DeviceNip11Doc? = runCatching {
    val obj = NostrJson.parseToJsonElement(body).jsonObject
    fun str(k: String): String? =
        (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    val nips = (obj["supported_nips"] as? JsonArray)
        ?.asSequence()
        ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        ?.take(MAX_SUPPORTED_NIPS)
        ?.toSet()
        ?: emptySet()
    val lim = obj["limitation"] as? JsonObject
    fun limBool(k: String): Boolean? = (lim?.get(k) as? JsonPrimitive)?.booleanOrNull
    // fees.admission[0].amount, normalized to msats (unit "sats" → ×1000).
    val feeMsats = (obj["fees"] as? JsonObject)
        ?.let { it["admission"] as? JsonArray }
        ?.firstOrNull()?.let { it as? JsonObject }
        ?.let { adm ->
            val amount = (adm["amount"] as? JsonPrimitive)?.longOrNull
            val unit = (adm["unit"] as? JsonPrimitive)?.content
            when {
                amount == null -> null
                unit == "sats" || unit == "sat" -> amount * 1000
                else -> amount
            }
        }
    DeviceNip11Doc(
        name = str("name")?.take(MAX_NIP11_NAME_CHARS),
        description = str("description")?.take(MAX_NIP11_TEXT_CHARS),
        icon = str("icon")?.takeIf { it.length <= MAX_NIP11_URL_CHARS && isHttpUrl(it) },
        software = str("software")?.take(MAX_NIP11_FIELD_CHARS),
        version = str("version")?.take(MAX_NIP11_FIELD_CHARS),
        supportedNips = nips,
        authRequired = limBool("auth_required"),
        paymentRequired = limBool("payment_required"),
        restrictedWrites = limBool("restricted_writes"),
        feeMsats = feeMsats,
        operatorPubkey = str("pubkey")?.takeIf { it.length == 64 },
        contact = str("contact")?.take(MAX_NIP11_FIELD_CHARS),
    )
}.getOrNull()

private fun isHttpUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

/**
 * Device-side NIP-11 fetcher. Used only for user-visible relay identity surfaces (detail
 * and visible relay-list rows) — never a background/periodic poll.
 * Reuses the shared base OkHttp client (same one the WebSocket/OG paths use), with short
 * finite timeouts layered on for the one-shot HTTP GET. In-memory cache with a ~1h TTL,
 * keyed by normalized relay url. Malformed/missing docs resolve to null, never throw.
 */
@Singleton
class Nip11Fetcher @Inject constructor(
    baseClient: OkHttpClient,
    private val memoryEventStore: Lazy<MemoryEventStore>,
) {

    // The base client has readTimeout=0 (WebSocket). Layer finite timeouts for a plain GET.
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private data class Cached(val doc: DeviceNip11Doc?, val at: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()
    private val networkPermits = Semaphore(MAX_PARALLEL_NIP11_FETCHES)

    /**
     * Fetch (or return cached) the device NIP-11 doc for [relayUrl]. Returns null when the
     * host is unreachable from this device, returns a non-2xx, or the body is unparseable —
     * an honest "we couldn't read it from here", which the detail page renders as a
     * DNS-blocked / untested verdict rather than pretending the monitor's view is ours.
     */
    suspend fun fetch(relayUrl: String, force: Boolean = false): DeviceNip11Doc? {
        val norm = normalizeRelayUrl(relayUrl) ?: return null
        freshCache(norm, force)?.let { cached ->
            cached.doc?.let { persistIdentity(norm, it, cached.at) }
            return cached.doc
        }
        return fetchLocks.computeIfAbsent(norm) { Mutex() }.withLock {
            freshCache(norm, force)?.let { cached ->
                cached.doc?.let { persistIdentity(norm, it, cached.at) }
                return@withLock cached.doc
            }
            fetchFromNetwork(norm)
        }
    }

    private fun freshCache(norm: String, force: Boolean): Cached? {
        if (force) return null
        val cached = cache[norm] ?: return null
        if (System.currentTimeMillis() - cached.at >= TTL_MS) return null
        return cached
    }

    private suspend fun fetchFromNetwork(norm: String): DeviceNip11Doc? {
        val httpUrl = norm
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")

        val doc = networkPermits.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder()
                        .url(httpUrl)
                        .header("Accept", "application/nostr+json")
                        .get()
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val body = resp.body.readBoundedUtf8() ?: return@use null
                        parseDeviceNip11(body)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }
        val fetchedAt = System.currentTimeMillis()
        cache[norm] = Cached(doc, fetchedAt)
        doc?.let { persistIdentity(norm, it, fetchedAt) }
        Log.d(TAG, "NIP11 device-fetch $norm → ${if (doc != null) "ok name=${doc.name}" else "unreachable/empty"}")
        return doc
    }

    private fun ResponseBody.readBoundedUtf8(): String? {
        if (contentLength() > MAX_NIP11_BODY_CHARS) return null
        val reader = charStream()
        val result = StringBuilder()
        val buffer = CharArray(8 * 1024)
        while (true) {
            val remaining = MAX_NIP11_BODY_CHARS + 1 - result.length
            if (remaining <= 0) return null
            val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            result.append(buffer, 0, read)
        }
        return result.toString().takeIf(String::isNotBlank)
    }

    private fun persistIdentity(
        relayUrl: String,
        doc: DeviceNip11Doc,
        fetchedAt: Long,
    ) {
        memoryEventStore.get().putRelayIdentity(
            url = relayUrl,
            name = doc.name,
            iconUrl = doc.icon,
            fetchedAt = fetchedAt,
        )
    }

    companion object {
        private const val TAG = "RelayMgmt"
        private const val TTL_MS = 60L * 60 * 1000   // 1h
    }
}
