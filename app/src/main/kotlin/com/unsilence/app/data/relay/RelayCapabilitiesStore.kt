package com.unsilence.app.data.relay

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RelayCapStore"
private val Context.relayCapsDataStore by preferencesDataStore("relay_capabilities")
private val CAPS_KEY = stringPreferencesKey("caps_json")

/** Structural rejection prefixes that teach us something reusable about the relay. */
private val STRUCTURAL_PREFIXES = setOf(
    "auth-required", "blocked", "restricted", "invalid", "error",
)

/** Transient prefixes — don't record these, they don't predict future behavior. */
private val TRANSIENT_PREFIXES = setOf("rate-limited", "pow", "duplicate")

const val MAX_CAPABILITY_STRIKES = 3

/** Why a relay is being skipped — covers both protocol (CLOSED) and transport (onFailure) reasons. */
enum class SkipReason {
    // Protocol-level (CLOSED structural rejections) — existing path via learnFromClosed
    AUTH_REQUIRED,
    BLOCKED,
    RESTRICTED,
    INVALID,
    ERROR,
    // Transport-level (RelayConnection.onFailure) — new path via recordTransportFailure
    DNS_RESOLUTION,     // UnknownHostException — host doesn't resolve
    CLEARTEXT_BLOCKED,  // Android NSP blocks ws:// (belt-and-suspenders after normalizeRelayUrl gate)
    HTTP_UPGRADE_4XX,   // Relay refused upgrade with 4xx — auth/path/policy
    HTTP_UPGRADE_5XX,   // Relay returned 5xx — server down or broken
    SSL_ERROR,          // TLS handshake or read error
    CONNECT_TIMEOUT,    // SocketTimeoutException / ConnectException — host unreachable
    UNKNOWN_FAILURE,    // Anything else — don't strike aggressively
}

/** Read-side interface for relay capability checks. Testable without Android context. */
interface RelaySkipCheck {
    fun shouldSkip(relayUrl: String): Boolean
}

/**
 * Per-relay learned capabilities, persisted to DataStore. Read-mostly — every REQ
 * builder can consult [shouldSkip]; writes happen only on CLOSED with structural
 * rejection reasons.
 *
 * In-memory [ConcurrentHashMap] is the source of truth during a session. DataStore
 * write is fire-and-forget after each update.
 */
@Singleton
class RelayCapabilitiesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : RelaySkipCheck {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), RelayCapabilities.serializer())

    private val caps = ConcurrentHashMap<String, RelayCapabilities>()

    /** Load persisted capabilities from DataStore. Call once at bootstrap before first REQ. */
    suspend fun load() {
        val raw = context.relayCapsDataStore.data.first()[CAPS_KEY] ?: return
        runCatching { json.decodeFromString(serializer, raw) }
            .onSuccess { map ->
                caps.putAll(map)
                Log.d(TAG, "Loaded ${map.size} relay capabilities")
            }
            .onFailure { Log.w(TAG, "Failed to parse relay capabilities: ${it.message}") }
    }

    /** Get capabilities for a relay; null when nothing learned yet. */
    fun get(relayUrl: String): RelayCapabilities? {
        val key = normalizeRelayUrl(relayUrl) ?: return null
        return caps[key]
    }

    /** True when the relay should be skipped for all REQs. */
    override fun shouldSkip(relayUrl: String): Boolean {
        val c = get(relayUrl) ?: return false
        return c.authRequired || c.restricted || c.strikes >= MAX_CAPABILITY_STRIKES
    }

    /**
     * Record a transport-level failure for [url]. After [MAX_CAPABILITY_STRIKES]
     * weighted strikes, the URL is marked skippable for the capability TTL.
     *
     * Called from RelayConnection's onFailure handler.
     */
    fun recordTransportFailure(url: String, reason: SkipReason) {
        val key = normalizeRelayUrl(url) ?: return
        val weight = strikesForReason(reason)
        val existing = caps[key] ?: RelayCapabilities()
        val newStrikes = existing.strikes + weight
        val updated = existing.copy(
            strikes = newStrikes,
            lastStrikeAt = System.currentTimeMillis(),
            lastReason = reason.name.take(120),
        )
        caps[key] = updated

        if (newStrikes >= MAX_CAPABILITY_STRIKES) {
            Log.w(TAG, "Transport skip: $key ($reason, $newStrikes strikes)")
        } else {
            Log.d(TAG, "Transport strike: $key ($reason, $newStrikes/$MAX_CAPABILITY_STRIKES)")
        }
        // Fire-and-forget persist — don't block onFailure callback thread
        GlobalScope.launch(Dispatchers.IO) { persist() }
    }

    /**
     * Parse a CLOSED reason and record any structural learning. Call from the
     * CLOSED handler in RelayPool.
     *
     * @param relayUrl the relay that sent the CLOSED
     * @param reason the full reason string from the CLOSED message
     */
    suspend fun learnFromClosed(relayUrl: String, reason: String) {
        val prefix = reason.substringBefore(':').trim().lowercase()

        // Skip transient prefixes — they don't teach us anything reusable.
        if (prefix in TRANSIENT_PREFIXES) return

        val effectivePrefix = if (prefix in STRUCTURAL_PREFIXES) prefix else "error"
        val rest = reason.substringAfter(':', missingDelimiterValue = "").trim()

        val key = normalizeRelayUrl(relayUrl) ?: return
        val existing = caps[key] ?: RelayCapabilities()
        val updated = applyRejection(existing, effectivePrefix, rest.ifEmpty { reason })
        if (updated == existing) return

        caps[key] = updated
        persist()
        Log.d(TAG, "Learned from $key: strikes=${updated.strikes} auth=${updated.authRequired} restricted=${updated.restricted} reason='${updated.lastReason}'")
    }

    private fun applyRejection(
        existing: RelayCapabilities,
        prefix: String,
        reason: String,
    ): RelayCapabilities {
        val authReq = existing.authRequired || prefix == "auth-required"
        val restricted = existing.restricted ||
            prefix == "restricted" ||
            reason.contains("white-list", ignoreCase = true)
        return existing.copy(
            authRequired = authReq,
            restricted = restricted,
            strikes = existing.strikes + 1,
            lastStrikeAt = System.currentTimeMillis(),
            lastReason = reason.take(120),
        )
    }

    private suspend fun persist() {
        val snapshot = HashMap(caps)
        val encoded = json.encodeToString(serializer, snapshot)
        context.relayCapsDataStore.edit { it[CAPS_KEY] = encoded }
    }

    fun dump(): String =
        caps.entries
            .sortedByDescending { it.value.strikes }
            .joinToString("\n") { (url, c) ->
                "$url: strikes=${c.strikes} auth=${c.authRequired} restricted=${c.restricted} last='${c.lastReason}'"
            }

    companion object {
        /** DNS and cleartext failures are structurally permanent — instant skip (weight = threshold). */
        internal fun strikesForReason(reason: SkipReason): Int = when (reason) {
            SkipReason.DNS_RESOLUTION,
            SkipReason.CLEARTEXT_BLOCKED -> MAX_CAPABILITY_STRIKES
            else -> 1
        }
    }
}
