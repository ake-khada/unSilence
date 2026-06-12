package com.unsilence.app.data.relay

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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

/**
 * Device-side NIP-11 fetcher. ON DETAIL-OPEN ONLY — never a background/periodic poll.
 * Reuses the shared base OkHttp client (same one the WebSocket/OG paths use), with short
 * finite timeouts layered on for the one-shot HTTP GET. In-memory cache with a ~1h TTL,
 * keyed by normalized relay url. Malformed/missing docs resolve to null, never throw.
 */
@Singleton
class Nip11Fetcher @Inject constructor(baseClient: OkHttpClient) {

    // The base client has readTimeout=0 (WebSocket). Layer finite timeouts for a plain GET.
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private data class Cached(val doc: DeviceNip11Doc?, val at: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * Fetch (or return cached) the device NIP-11 doc for [relayUrl]. Returns null when the
     * host is unreachable from this device, returns a non-2xx, or the body is unparseable —
     * an honest "we couldn't read it from here", which the detail page renders as a
     * DNS-blocked / untested verdict rather than pretending the monitor's view is ours.
     */
    suspend fun fetch(relayUrl: String, force: Boolean = false): DeviceNip11Doc? {
        val norm = normalizeRelayUrl(relayUrl) ?: return null
        val now = System.currentTimeMillis()
        if (!force) cache[norm]?.let { if (now - it.at < TTL_MS) return it.doc }

        val httpUrl = norm
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")

        val doc = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(httpUrl)
                    .header("Accept", "application/nostr+json")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body.string().takeIf { it.isNotBlank() } ?: return@use null
                    parse(body)
                }
            }.getOrNull()
        }
        cache[norm] = Cached(doc, now)
        Log.w(TAG, "NIP11 device-fetch $norm → ${if (doc != null) "ok name=${doc.name}" else "unreachable/empty"}")
        return doc
    }

    private fun parse(body: String): DeviceNip11Doc? = runCatching {
        val obj = NostrJson.parseToJsonElement(body).jsonObject
        fun str(k: String): String? =
            (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val nips = (obj["supported_nips"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }?.toSet()
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
                    else -> amount   // msats (NIP-11 default) or unknown unit
                }
            }
        DeviceNip11Doc(
            name = str("name"),
            description = str("description"),
            icon = str("icon"),
            software = str("software"),
            version = str("version"),
            supportedNips = nips,
            authRequired = limBool("auth_required"),
            paymentRequired = limBool("payment_required"),
            restrictedWrites = limBool("restricted_writes"),
            feeMsats = feeMsats,
            operatorPubkey = str("pubkey")?.takeIf { it.length == 64 },
            contact = str("contact"),
        )
    }.getOrNull()

    companion object {
        private const val TAG = "RelayMgmt"
        private const val TTL_MS = 60L * 60 * 1000   // 1h
    }
}
