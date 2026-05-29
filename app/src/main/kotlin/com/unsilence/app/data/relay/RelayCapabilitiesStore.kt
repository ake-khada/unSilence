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
    "blocked", "restricted", "invalid", "error",
)

/** Transient prefixes — don't record these, they don't predict future behavior. */
private val TRANSIENT_PREFIXES = setOf("auth-required", "rate-limited", "pow", "duplicate")

/** Entries older than this are evicted on load — transient failures heal between sessions. */
private const val STRIKE_TTL_MS = 24 * 60 * 60 * 1000L  // 24 hours

const val MAX_CAPABILITY_STRIKES = 3

/** Transport-strike retry cooldowns. Once a relay passes MAX_CAPABILITY_STRIKES,
 *  it's skipped only within this window since the last strike — then a single
 *  retry is allowed (half-open). Replaces the old "permanent until 24h TTL". */
private const val TRANSPORT_RETRY_BASE_MS = 60_000L            // 1 min — first retry after threshold
private const val TRANSPORT_RETRY_MAX_MS  = 30 * 60_000L       // 30 min cap (non-integral, dead relays)
private const val INTEGRAL_RETRY_COOLDOWN_MS = 60_000L         // 1 min flat — integral relays heal fast

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

    /** URLs of integral relays (indexer / own read / own write / search). These heal on
     *  a short flat cooldown rather than exponential backoff. */
    @Volatile private var integralUrls: Set<String> = emptySet()

    fun setIntegralRelays(urls: Collection<String>) {
        integralUrls = urls.mapNotNull { normalizeRelayUrl(it) }.toSet()
        Log.d(TAG, "Integral relay set updated: ${integralUrls.size} relays")
    }

    private fun isIntegral(url: String): Boolean {
        val key = normalizeRelayUrl(url) ?: return false
        return key in integralUrls
    }

    /** Load persisted capabilities from DataStore. Call once at bootstrap before first REQ. */
    suspend fun load() {
        val raw = context.relayCapsDataStore.data.first()[CAPS_KEY] ?: return
        runCatching { json.decodeFromString(serializer, raw) }
            .onSuccess { map ->
                // Evict stale entries — transient failures shouldn't poison across sessions
                val now = System.currentTimeMillis()
                val fresh = map.filter { (_, c) ->
                    c.restricted || (now - c.lastStrikeAt) < STRIKE_TTL_MS
                }
                val evicted = map.size - fresh.size
                // Clear legacy authRequired flags — auth is handled by the auth pipeline
                val cleaned = fresh.mapValues { (_, c) ->
                    if (c.authRequired) c.copy(authRequired = false) else c
                }
                caps.putAll(cleaned)
                Log.w(TAG, "Loaded ${cleaned.size} relay capabilities (evicted $evicted stale)")
                // Dump skippable relays on load for diagnostics
                val skippable = cleaned.filter { (_, c) -> c.restricted || c.strikes >= MAX_CAPABILITY_STRIKES }
                if (skippable.isNotEmpty()) {
                    for ((url, c) in skippable) {
                        Log.w(TAG, "  WILL-SKIP: $url restricted=${c.restricted} strikes=${c.strikes} reason='${c.lastReason}'")
                    }
                }
                // Persist cleaned data so stale entries don't re-load
                if (evicted > 0 || fresh.any { (k, _) -> map[k]?.authRequired == true }) {
                    GlobalScope.launch(Dispatchers.IO) { persist() }
                }
            }
            .onFailure { Log.w(TAG, "Failed to parse relay capabilities: ${it.message}") }
    }

    /** Get capabilities for a relay; null when nothing learned yet. */
    fun get(relayUrl: String): RelayCapabilities? {
        val key = normalizeRelayUrl(relayUrl) ?: return null
        return caps[key]
    }

    /** True when the relay should be skipped for all REQs.
     *  Half-open: past MAX_CAPABILITY_STRIKES, skip only within a retry cooldown
     *  since lastStrikeAt. After cooldown, allow one retry — success clears strikes
     *  (onOpen.clearTransportStrikes), failure re-strikes and extends the window. */
    override fun shouldSkip(relayUrl: String): Boolean {
        val c = get(relayUrl) ?: return false
        if (c.restricted) {
            Log.w(TAG, "shouldSkip=true: $relayUrl restricted=true reason='${c.lastReason}'")
            return true
        }
        if (c.strikes < MAX_CAPABILITY_STRIKES) return false

        // Half-open: struck past threshold, skip only within cooldown
        val now = System.currentTimeMillis()
        val cooldown = retryCooldownMs(relayUrl, c.strikes)
        val elapsed = now - c.lastStrikeAt
        return if (elapsed < cooldown) {
            Log.w(TAG, "shouldSkip=true: $relayUrl strikes=${c.strikes} reason='${c.lastReason}' " +
                "(retry in ${(cooldown - elapsed) / 1000}s${if (isIntegral(relayUrl)) ", integral" else ""})")
            true
        } else {
            Log.w(TAG, "shouldSkip=false (half-open): $relayUrl strikes=${c.strikes} — cooldown elapsed, allowing retry")
            false
        }
    }

    /** Cooldown before a struck relay becomes retry-eligible.
     *  Integral relays: short flat window. Others: exponential backoff, capped. */
    internal fun retryCooldownMs(url: String, strikes: Int): Long {
        if (isIntegral(url)) return INTEGRAL_RETRY_COOLDOWN_MS
        val overage = (strikes - MAX_CAPABILITY_STRIKES).coerceIn(0, 6)
        return (TRANSPORT_RETRY_BASE_MS shl overage).coerceAtMost(TRANSPORT_RETRY_MAX_MS)
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
        Log.w(TAG, "Learned from $key: prefix='$effectivePrefix' strikes=${updated.strikes} auth=${updated.authRequired} restricted=${updated.restricted} reason='${updated.lastReason}'")
    }

    private fun applyRejection(
        existing: RelayCapabilities,
        prefix: String,
        reason: String,
    ): RelayCapabilities {
        val restricted = existing.restricted ||
            prefix == "restricted" ||
            reason.contains("white-list", ignoreCase = true)
        return existing.copy(
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

    /**
     * Clear transport strikes for [url] on successful connection.
     * Restricted relays stay restricted — only transient strike accumulation is forgiven.
     */
    fun clearTransportStrikes(url: String) {
        val key = normalizeRelayUrl(url) ?: return
        val existing = caps[key] ?: return
        if (existing.restricted) return  // policy rejections are permanent
        if (existing.strikes == 0) return // nothing to clear
        val cleared = existing.copy(strikes = 0, lastReason = "")
        caps[key] = cleared
        Log.w(TAG, "Cleared transport strikes for $key (was ${existing.strikes}, reason='${existing.lastReason}')")
        GlobalScope.launch(Dispatchers.IO) { persist() }
    }

    fun dump(): String =
        caps.entries
            .sortedByDescending { it.value.strikes }
            .joinToString("\n") { (url, c) ->
                "$url: strikes=${c.strikes} auth=${c.authRequired} restricted=${c.restricted} last='${c.lastReason}'"
            }

    companion object {
        /** Cleartext is a policy violation (Android NSP) — instant skip.
         *  Everything else accumulates gradually so transient failures heal. */
        internal fun strikesForReason(reason: SkipReason): Int = when (reason) {
            SkipReason.CLEARTEXT_BLOCKED -> MAX_CAPABILITY_STRIKES
            else -> 1
        }
    }
}
