package com.unsilence.app.data.relay

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RelayCapStore"
private val Context.relayCapsDataStore by preferencesDataStore("relay_capabilities")
private val CAPS_KEY = stringPreferencesKey("caps_json")

/** Structural rejection prefixes that teach us something reusable about the relay. */
private val STRUCTURAL_PREFIXES = setOf(
    "restricted", "invalid", "error",
)

/** Transient prefixes — don't record these, they don't predict future behavior.
 *  "blocked" is per-REQ policy rejection (e.g. "filters must specify at least one kind"),
 *  not relay-level unhealthiness — must not strike toward shouldSkip (H19a). */
private val TRANSIENT_PREFIXES = setOf("auth-required", "rate-limited", "pow", "duplicate", "blocked")

/** Entries older than this are evicted on load — transient failures heal between sessions. */
private const val STRIKE_TTL_MS = 24 * 60 * 60 * 1000L  // 24 hours

const val MAX_CAPABILITY_STRIKES = 3

/** Transport-strike retry cooldowns. Once a relay passes MAX_CAPABILITY_STRIKES,
 *  it's skipped only within this window since the last strike — then a single
 *  retry is allowed (half-open). Replaces the old "permanent until 24h TTL".
 *  DNS failures use a longer base — the VPN/system resolver won't change in 1 min. */
private const val TRANSPORT_RETRY_BASE_MS = 60_000L            // 1 min — timeout/TLS first retry
private const val DNS_RETRY_BASE_MS       = 5 * 60_000L        // 5 min — DNS base (resolver unlikely to change sooner)
private const val TRANSPORT_RETRY_MAX_MS  = 30 * 60_000L       // 30 min cap (non-integral, dead relays)
private const val INTEGRAL_RETRY_COOLDOWN_MS = 60_000L         // 1 min base — integral relays heal fast
private const val INTEGRAL_ESCALATED_COOLDOWN_MS = 5 * 60_000L // 5 min after a failure streak (H20b)
private const val INTEGRAL_ESCALATION_THRESHOLD = 5            // consecutive fails before escalating (H20b)
private const val CAPABILITY_RETRY_BASE_MS = 60_000L
private const val CAPABILITY_RETRY_MAX_MS = 5 * 60_000L

/** DNS-degraded detection: ≥ THRESHOLD distinct relays failing DNS within WINDOW_MS
 *  means the pipe is down, not the relays. Strikes are suppressed until a relay resolves. */
internal const val NETWORK_DOWN_DNS_THRESHOLD = 4
internal const val NETWORK_DOWN_WINDOW_MS = 3_000L

/** DNS-degraded latch TTL. The degraded flag defers EVERY reconnect, so clearing it
 *  requires a successful connect — but if the gate blocks all connects, the latch
 *  could never produce its own exit evidence and would hang forever (observed: armed
 *  5+ min on a censored network). After this TTL the latch auto-expires; a fresh burst
 *  of distinct DNS failures re-arms it with a new onset. The heuristic stays useful;
 *  it just can't latch. (H20a) */
internal const val DNS_DEGRADED_TTL_MS = 90_000L  // 90s

/** Cross-session dead-relay denylist. Distinct from transient strikes (in-memory,
 *  24h TTL, half-open) — this is long-term, persisted, DataStore-backed.
 *  A relay is dead when it fails [DEAD_RELAY_THRESHOLD] consecutive DNS resolutions
 *  outside of network-down (Phase 1 gate wraps the increment — critical).
 *  CONNECT_TIMEOUT is transient and does NOT count (H18.4). */
internal const val DEAD_RELAY_THRESHOLD = 10
internal const val DEAD_RELAY_REPROBE_MS = 7L * 24 * 3600 * 1000  // weekly

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

/** REQ class matters for relays that explicitly require a NIP-50 search filter. */
enum class RelayRequestClass {
    GENERAL,
    NIP50_SEARCH,
}

internal data class LearnedClosedRejection(
    val prefix: String,
    val reason: String,
    val searchOnly: Boolean,
)

/** Pure CLOSED classifier. Transient policy responses deliberately teach nothing. */
internal fun classifyClosedRejection(reason: String): LearnedClosedRejection? {
    val prefix = reason.substringBefore(':').trim().lowercase()
    if (prefix in TRANSIENT_PREFIXES) return null
    val effectivePrefix = if (prefix in STRUCTURAL_PREFIXES) prefix else "error"
    val tail = reason.substringAfter(':', missingDelimiterValue = "").trim()
    return LearnedClosedRejection(
        prefix = effectivePrefix,
        reason = tail.ifEmpty { reason }.take(120),
        searchOnly = reason.contains("search filter is required", ignoreCase = true),
    )
}

private fun incrementSaturated(value: Int): Int =
    if (value == Int.MAX_VALUE) Int.MAX_VALUE else value + 1

/** Apply one structural rejection without Android/store dependencies. */
internal fun applyCapabilityRejection(
    existing: RelayCapabilities,
    rejection: LearnedClosedRejection,
    nowMs: Long,
): RelayCapabilities {
    val restricted = existing.restricted ||
        rejection.prefix == "restricted" ||
        rejection.reason.contains("white-list", ignoreCase = true)
    return existing.copy(
        restricted = restricted,
        consecutiveCapabilityFailures = incrementSaturated(
            if (nowMs - existing.lastCapabilityStrikeAt < STRIKE_TTL_MS) {
                existing.consecutiveCapabilityFailures
            } else {
                0
            },
        ),
        lastCapabilityStrikeAt = nowMs,
        lastCapabilityReason = rejection.reason,
        searchOnly = existing.searchOnly || rejection.searchOnly,
    )
}

/** A successful socket connection heals transport state only. */
internal fun clearTransportFailures(existing: RelayCapabilities): RelayCapabilities {
    if (existing.restricted) return existing
    return existing.copy(
        strikes = 0,
        lastStrikeAt = 0L,
        lastReason = "",
        deadFailCount = 0,
        consecutiveFailures = 0,
    )
}

/** A genuine EOSE proves that the relay accepted a REQ. Routing facts remain cached. */
internal fun clearCapabilityFailures(existing: RelayCapabilities): RelayCapabilities {
    if (existing.restricted) return existing
    return existing.copy(consecutiveCapabilityFailures = 0)
}

/** An explicit user retry clears both transient domains and learned request routing. */
internal fun clearAllRelayFailures(existing: RelayCapabilities): RelayCapabilities {
    if (existing.restricted) return existing
    return clearCapabilityFailures(clearTransportFailures(existing)).copy(
        lastCapabilityStrikeAt = 0L,
        lastCapabilityReason = "",
        searchOnly = false,
    )
}

/** Learned request compatibility, kept pure so every dispatcher shares one rule. */
internal fun isRequestClassCompatible(
    capabilities: RelayCapabilities,
    requestClass: RelayRequestClass,
): Boolean = requestClass != RelayRequestClass.GENERAL || !capabilities.searchOnly

/**
 * The structural rejection that taught us a relay is search-only must not suppress
 * the request class it explicitly asked for. Other later structural failures still
 * back off normally, even when the relay remains classified as search-only.
 */
internal fun shouldIgnoreCapabilityCooldown(
    capabilities: RelayCapabilities,
    requestClass: RelayRequestClass,
): Boolean =
    requestClass == RelayRequestClass.NIP50_SEARCH &&
        capabilities.searchOnly &&
        capabilities.lastCapabilityReason.contains("search filter is required", ignoreCase = true)

private val TRANSPORT_REASON_NAMES = setOf(
    SkipReason.DNS_RESOLUTION,
    SkipReason.CLEARTEXT_BLOCKED,
    SkipReason.HTTP_UPGRADE_4XX,
    SkipReason.HTTP_UPGRADE_5XX,
    SkipReason.SSL_ERROR,
    SkipReason.CONNECT_TIMEOUT,
    SkipReason.UNKNOWN_FAILURE,
).mapTo(mutableSetOf()) { it.name }

/**
 * Before capability/transport state was split, structural CLOSED strikes occupied
 * [RelayCapabilities.strikes]/lastReason. Migrate those persisted records once so
 * an upgrade does not need one more known-losing REQ to learn the same fact again.
 */
internal fun migrateLegacyCapabilityState(existing: RelayCapabilities): RelayCapabilities {
    val isLegacyCapability =
        existing.lastCapabilityStrikeAt == 0L &&
            existing.consecutiveCapabilityFailures == 0 &&
            existing.lastReason.isNotBlank() &&
            existing.lastReason !in TRANSPORT_REASON_NAMES
    if (!isLegacyCapability) return existing

    return existing.copy(
        strikes = 0,
        lastStrikeAt = 0L,
        lastReason = "",
        consecutiveCapabilityFailures = existing.strikes.coerceAtLeast(1),
        lastCapabilityStrikeAt = existing.lastStrikeAt,
        lastCapabilityReason = existing.lastReason,
        searchOnly = existing.searchOnly ||
            existing.lastReason.contains("search filter is required", ignoreCase = true),
    )
}

/** Search-only/restricted are routing facts; only transient failure evidence expires. */
internal fun shouldRetainPersistedCapabilities(
    capabilities: RelayCapabilities,
    nowMs: Long,
): Boolean {
    val latestEvidence = maxOf(
        capabilities.lastStrikeAt,
        capabilities.lastCapabilityStrikeAt,
    )
    return capabilities.restricted ||
        capabilities.searchOnly ||
        (nowMs - latestEvidence) < STRIKE_TTL_MS
}

/** Read-side interface for relay capability checks. Testable without Android context. */
interface RelaySkipCheck {
    fun shouldSkip(relayUrl: String): Boolean

    fun shouldSkipRequest(
        relayUrl: String,
        requestClass: RelayRequestClass,
        bypassCooldown: Boolean = false,
    ): Boolean = !bypassCooldown && shouldSkip(relayUrl)

    /** Called only for genuine relay success (EOSE), never synthesized EOSE/CLOSED. */
    fun recordRequestSuccess(relayUrl: String) = Unit
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
    private val networkMonitor: NetworkMonitor,
) : RelaySkipCheck {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), RelayCapabilities.serializer())

    // Transport callbacks can arrive in bursts across many relays. Persisting from
    // every callback creates redundant DataStore edits and unstructured GlobalScope
    // work. A conflated process-lifetime writer preserves the latest snapshot while
    // bounding pending disk work to one follow-up write.
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistRequests = Channel<Unit>(capacity = Channel.CONFLATED)

    init {
        persistenceScope.launch {
            for (ignored in persistRequests) {
                runCatching { persist() }
                    .onFailure { Log.w(TAG, "Failed to persist relay capabilities", it) }
            }
        }
    }

    private val caps = ConcurrentHashMap<String, RelayCapabilities>()

    /** URLs of integral relays (indexer / own read / own write / search). These heal on
     *  a short flat cooldown rather than exponential backoff. */
    @Volatile private var integralUrls: Set<String> = emptySet()

    // ── DNS-degraded detection ────────────────────────────────────────
    // Ring of recent DNS-failure timestamps keyed by relay URL. When
    // ≥ NETWORK_DOWN_DNS_THRESHOLD distinct relays fail within
    // NETWORK_DOWN_WINDOW_MS, the pipe is down — don't strike relays.

    /** relay URL → timestamp of most recent DNS failure. */
    private val recentDnsFailures = ConcurrentHashMap<String, Long>()

    /** True while we believe the network is DNS-degraded (multiple distinct relays
     *  failing within a short window). Cleared on first successful connection. */
    @Volatile var dnsDegraded: Boolean = false
        private set

    /** Epoch ms when dnsDegraded was set. Load-bearing for the [DNS_DEGRADED_TTL_MS]
     *  latch expiry (H20a) — was previously set-but-never-read. */
    private val dnsDegradedOnsetAt = AtomicLong(0L)

    /** Relay URLs that received a DNS failure while degraded — cleared on heal. */
    private val dnsFailedDuringDegradation: MutableSet<String> =
        ConcurrentHashMap.newKeySet()

    /** True when the network is down (ConnectivityManager OFFLINE) or DNS is
     *  degraded (multiple distinct relays failing). Used by RelayPool to gate
     *  reconnect attempts and by this class to gate strikes. */
    val isNetworkDown: Boolean
        get() = networkMonitor.state.value == NetworkState.OFFLINE || dnsDegradedActive()

    /** True while the DNS-degraded latch is armed AND within its TTL. Lazily clears an
     *  expired latch: that's a TIMEOUT-driven expiry, not a recovery — no successful
     *  connect happened — so strikes are NOT healed here (cf. [healDnsDegraded]). A
     *  fresh burst of distinct DNS failures must re-arm it. (H20a) */
    private fun dnsDegradedActive(): Boolean {
        if (!dnsDegraded) return false
        if (isDegradedActive(true, dnsDegradedOnsetAt.get(), System.currentTimeMillis())) return true
        dnsDegraded = false
        recentDnsFailures.clear()
        dnsFailedDuringDegradation.clear()
        Log.w(TAG, "DNS-degraded latch expired after ${DNS_DEGRADED_TTL_MS}ms with no successful connect — clearing (gate must not block its own exit, H20a)")
        return false
    }

    /** Record a DNS failure for degraded-detection. Called before the strike gate. */
    private fun recordDnsFailure(url: String) {
        val now = System.currentTimeMillis()
        // Evaluate the latch's TTL first: an expired latch clears here so THIS fresh
        // failure re-arms with a new onset rather than riding a stale (possibly
        // minutes-old) arm. dnsDegradedActive() also clears recentDnsFailures on expiry. (H20a)
        val active = dnsDegradedActive()

        recentDnsFailures[url] = now

        // Prune stale entries
        val cutoff = now - NETWORK_DOWN_WINDOW_MS
        recentDnsFailures.entries.removeIf { it.value < cutoff }

        if (!active && recentDnsFailures.size >= NETWORK_DOWN_DNS_THRESHOLD) {
            dnsDegraded = true
            dnsDegradedOnsetAt.set(now)
            Log.w(TAG, "DNS-degraded: ${recentDnsFailures.size} distinct relays failed DNS within ${NETWORK_DOWN_WINDOW_MS}ms")
        }

        if (dnsDegraded) {
            dnsFailedDuringDegradation.add(url)
        }
    }

    /** Called on any successful relay connection. Clears DNS-degraded state and
     *  heals strikes accrued during the degraded period. */
    private fun healDnsDegraded() {
        if (!dnsDegraded) return
        dnsDegraded = false
        recentDnsFailures.clear()
        val healed = dnsFailedDuringDegradation.toSet()
        dnsFailedDuringDegradation.clear()
        if (healed.isNotEmpty()) {
            Log.w(TAG, "DNS-degraded cleared — healing ${healed.size} relay(s) struck during outage")
            for (url in healed) {
                clearTransportStrikesInternal(url, scheduleWrite = false)
            }
            schedulePersist()
        }
    }

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
                val fresh = map.filter { (_, c) -> shouldRetainPersistedCapabilities(c, now) }
                val evicted = map.size - fresh.size
                // Clear legacy authRequired flags — auth is handled by the auth pipeline
                var migratedLegacyState = false
                val cleaned = fresh.mapValues { (_, c) ->
                    var fixed = migrateLegacyCapabilityState(c)
                    if (fixed != c) migratedLegacyState = true
                    if (fixed.authRequired) fixed = fixed.copy(authRequired = false)
                    // Dead-count policy invariant (H18.4): only DNS_RESOLUTION produces
                    // deadFailCount. Historical data may contain dead entries from
                    // CONNECT_TIMEOUT before this policy. Reset on every load — idempotent.
                    if (fixed.deadFailCount > 0 && fixed.lastReason != SkipReason.DNS_RESOLUTION.name) {
                        fixed = fixed.copy(deadFailCount = 0)
                    }
                    fixed
                }
                caps.putAll(cleaned)
                Log.w(TAG, "Loaded ${cleaned.size} relay capabilities (evicted $evicted stale)")
                // Dump skippable relays on load for diagnostics
                val skippable = cleaned.filter { (_, c) ->
                    c.restricted || c.searchOnly ||
                        c.strikes >= MAX_CAPABILITY_STRIKES ||
                        c.consecutiveCapabilityFailures >= MAX_CAPABILITY_STRIKES ||
                        c.deadFailCount >= DEAD_RELAY_THRESHOLD
                }
                if (skippable.isNotEmpty()) {
                    for ((url, c) in skippable) {
                        Log.w(
                            TAG,
                            "  WILL-SKIP: $url restricted=${c.restricted} " +
                                "transportStrikes=${c.strikes} capFails=${c.consecutiveCapabilityFailures} " +
                                "searchOnly=${c.searchOnly} dead=${c.deadFailCount} " +
                                "reason='${c.lastCapabilityReason.ifBlank { c.lastReason }}'",
                        )
                    }
                }
                // Persist cleaned data so stale/fixed entries don't re-load
                val needsPersist = evicted > 0 || migratedLegacyState ||
                    fresh.any { (k, v) -> map[k]?.authRequired == true ||
                        (v.deadFailCount > 0 && v.lastReason != SkipReason.DNS_RESOLUTION.name) }
                if (needsPersist) {
                    schedulePersist()
                }
            }
            .onFailure { Log.w(TAG, "Failed to parse relay capabilities: ${it.message}") }
    }

    /** Get capabilities for a relay; null when nothing learned yet. */
    fun get(relayUrl: String): RelayCapabilities? {
        val key = normalizeRelayUrl(relayUrl) ?: return null
        return caps[key]
    }

    /** True when transport or generic capability health suppresses every REQ class. */
    override fun shouldSkip(relayUrl: String): Boolean {
        val c = get(relayUrl) ?: return false
        if (!isRequestClassCompatible(c, RelayRequestClass.GENERAL)) return true
        return shouldSkipInternal(relayUrl, c, ignoreCapabilityCooldown = false)
    }

    override fun shouldSkipRequest(
        relayUrl: String,
        requestClass: RelayRequestClass,
        bypassCooldown: Boolean,
    ): Boolean {
        val c = get(relayUrl) ?: return false
        if (!isRequestClassCompatible(c, requestClass)) {
            Log.d(TAG, "REQ-SKIP: $relayUrl is learned search-only")
            return true
        }
        // Explicit capability bypasses are used for dedicated registry relays.
        // They bypass health cooldowns, but never a known-incompatible REQ class
        // (the search-only check above), because that request cannot succeed.
        if (bypassCooldown) return false
        // A structural rejection of a general REQ must not suppress the one
        // request class the relay explicitly accepts. Restricted/dead and
        // transport cooldowns remain authoritative.
        val ignoreCapabilityCooldown = shouldIgnoreCapabilityCooldown(c, requestClass)
        return shouldSkipInternal(relayUrl, c, ignoreCapabilityCooldown)
    }

    private fun shouldSkipInternal(
        relayUrl: String,
        c: RelayCapabilities,
        ignoreCapabilityCooldown: Boolean,
    ): Boolean {
        if (c.restricted) {
            Log.w(
                TAG,
                "shouldSkip=true: $relayUrl restricted=true " +
                    "reason='${c.lastCapabilityReason.ifBlank { c.lastReason }}'",
            )
            return true
        }
        // Dead relay: skip unless weekly reprobe window has elapsed
        if (c.deadFailCount >= DEAD_RELAY_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - c.lastProbeAt < DEAD_RELAY_REPROBE_MS) {
                return true  // still dead, not yet time to reprobe
            }
            // Reprobe window — allow one attempt
            Log.w(TAG, "shouldSkip=false (dead reprobe): $relayUrl deadFails=${c.deadFailCount}")
            return false
        }
        val now = System.currentTimeMillis()

        if (c.strikes >= MAX_CAPABILITY_STRIKES) {
            val cooldown = retryCooldownMs(relayUrl, c.strikes)
            val elapsed = now - c.lastStrikeAt
            if (elapsed < cooldown) {
                Log.w(
                    TAG,
                    "shouldSkip=true: $relayUrl transportStrikes=${c.strikes} reason='${c.lastReason}' " +
                        "(retry in ${(cooldown - elapsed) / 1000}s${if (isIntegral(relayUrl)) ", integral" else ""})",
                )
                return true
            }
            Log.w(
                TAG,
                "shouldSkip=false (transport half-open): $relayUrl strikes=${c.strikes}",
            )
        }

        if (!ignoreCapabilityCooldown &&
            c.consecutiveCapabilityFailures >= MAX_CAPABILITY_STRIKES
        ) {
            val cooldown = computeCapabilityCooldownMs(c.consecutiveCapabilityFailures)
            val elapsed = now - c.lastCapabilityStrikeAt
            if (elapsed < cooldown) {
                Log.w(
                    TAG,
                    "shouldSkip=true: $relayUrl capFails=${c.consecutiveCapabilityFailures} " +
                        "reason='${c.lastCapabilityReason}' " +
                        "(retry in ${(cooldown - elapsed) / 1000}s)",
                )
                return true
            }
            Log.w(
                TAG,
                "shouldSkip=false (capability half-open): $relayUrl " +
                    "capFails=${c.consecutiveCapabilityFailures}",
            )
        }
        return false
    }

    /** Cooldown before a struck relay becomes retry-eligible.
     *  Delegates to [computeRetryCooldownMs] — see companion for testable pure logic. */
    internal fun retryCooldownMs(url: String, strikes: Int): Long {
        val key = normalizeRelayUrl(url) ?: url
        val c = caps[key]
        return computeRetryCooldownMs(isIntegral(url), c?.lastReason, strikes, c?.consecutiveFailures ?: 0)
    }

    /**
     * Record a transport-level failure for [url]. After [MAX_CAPABILITY_STRIKES]
     * weighted strikes, the URL is marked skippable for the capability TTL.
     *
     * Called from RelayConnection's onFailure handler.
     */
    fun recordTransportFailure(url: String, reason: SkipReason) {
        val key = normalizeRelayUrl(url) ?: return

        // DNS failures feed the degraded-detection heuristic BEFORE the gate check.
        if (reason == SkipReason.DNS_RESOLUTION) {
            recordDnsFailure(key)
        }

        // Gate: don't strike relays for failures that are the network's fault.
        // A relay is only struck for failures that are ITS fault. Both DNS_RESOLUTION
        // and CONNECT_TIMEOUT are retryable transport failures that fire during outages.
        if ((reason == SkipReason.DNS_RESOLUTION || reason == SkipReason.CONNECT_TIMEOUT) && isNetworkDown) {
            Log.w(TAG, "$reason on $key ignored — network down/degraded, not striking")
            return
        }

        val weight = strikesForReason(reason)
        val now = System.currentTimeMillis()
        var previous = RelayCapabilities()
        var updated = RelayCapabilities()
        // CLOSED delivery and OkHttp onFailure can race on different threads. Merge
        // transport state atomically so it cannot erase capability learning (or vice versa).
        caps.compute(key) { _, current ->
            val existing = current ?: RelayCapabilities()
            previous = existing
            val newStrikes = (existing.strikes.toLong() + weight)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            // Dead-relay increment: DNS only. CONNECT_TIMEOUT is transient (VPN/network
            // variability) and must not contribute to the permanent denylist.
            // AFTER the network-down gate — DNS is unreachable above during outages.
            val newDeadCount = if (reason == SkipReason.DNS_RESOLUTION) {
                incrementSaturated(existing.deadFailCount)
            } else {
                existing.deadFailCount
            }
            existing.copy(
                strikes = newStrikes,
                lastStrikeAt = now,
                lastReason = reason.name.take(120),
                deadFailCount = newDeadCount,
                lastProbeAt = if (
                    newDeadCount >= DEAD_RELAY_THRESHOLD &&
                    existing.deadFailCount < DEAD_RELAY_THRESHOLD
                ) {
                    now // just crossed threshold — set initial probe time
                } else if (existing.deadFailCount >= DEAD_RELAY_THRESHOLD) {
                    now // reprobe failed — update probe time
                } else {
                    existing.lastProbeAt
                },
                // Escalation counter (H20b): counts every recorded failure (incl. CONNECT_TIMEOUT,
                // which deadFailCount excludes). Reset on success in clearTransportStrikesInternal.
                consecutiveFailures = incrementSaturated(existing.consecutiveFailures),
            ).also { updated = it }
        }
        val newStrikes = updated.strikes
        val newDeadCount = updated.deadFailCount

        if (newDeadCount >= DEAD_RELAY_THRESHOLD && previous.deadFailCount < DEAD_RELAY_THRESHOLD) {
            Log.w(TAG, "Dead relay: $key ($newDeadCount consecutive failures, reprobe in ${DEAD_RELAY_REPROBE_MS / 86_400_000}d)")
        } else if (newStrikes >= MAX_CAPABILITY_STRIKES) {
            Log.w(TAG, "Transport skip: $key ($reason, $newStrikes strikes, dead=$newDeadCount)")
        } else {
            Log.d(TAG, "Transport strike: $key ($reason, $newStrikes/$MAX_CAPABILITY_STRIKES, dead=$newDeadCount/$DEAD_RELAY_THRESHOLD)")
        }
        // Fire-and-forget persist — don't block onFailure callback thread.
        // Bursts are conflated into the latest complete snapshot.
        schedulePersist()
    }

    /**
     * Parse a CLOSED reason and record any structural learning. Call from the
     * CLOSED handler in RelayPool.
     *
     * @param relayUrl the relay that sent the CLOSED
     * @param reason the full reason string from the CLOSED message
     */
    fun learnFromClosed(relayUrl: String, reason: String) {
        val rejection = classifyClosedRejection(reason) ?: return
        val key = normalizeRelayUrl(relayUrl) ?: return
        var updated = RelayCapabilities()
        caps.compute(key) { _, current ->
            applyCapabilityRejection(
                existing = current ?: RelayCapabilities(),
                rejection = rejection,
                nowMs = System.currentTimeMillis(),
            ).also { updated = it }
        }
        schedulePersist()
        Log.w(
            TAG,
            "Learned from $key: prefix='${rejection.prefix}' " +
                "capFails=${updated.consecutiveCapabilityFailures} " +
                "searchOnly=${updated.searchOnly} restricted=${updated.restricted} " +
                "reason='${updated.lastCapabilityReason}'",
        )
    }

    private suspend fun persist() {
        val snapshot = HashMap(caps)
        val encoded = json.encodeToString(serializer, snapshot)
        context.relayCapsDataStore.edit { it[CAPS_KEY] = encoded }
    }

    private fun schedulePersist() {
        persistRequests.trySend(Unit)
    }

    /**
     * Permanently mark [url] as structurally invalid (malformed URL that will never
     * resolve). Uses `restricted = true` so [shouldSkip] returns true immediately
     * with no half-open retry — a malformed URL cannot heal.
     */
    fun markStructurallyInvalid(url: String) {
        // normalizeRelayUrl may itself reject url — use raw as key if so
        val key = normalizeRelayUrl(url) ?: url.trim().take(200)
        caps.compute(key) { _, current ->
            (current ?: RelayCapabilities()).copy(
                restricted = true,
                strikes = MAX_CAPABILITY_STRIKES,
                lastStrikeAt = System.currentTimeMillis(),
                lastReason = "structurally-invalid-url",
            )
        }
        Log.w(TAG, "Marked structurally invalid: ${key.take(80)}")
        schedulePersist()
    }

    /**
     * Clear transport strikes for [url] on successful connection.
     * Restricted relays stay restricted — only transient strike accumulation is forgiven.
     * Also triggers DNS-degraded heal: if we were degraded and a relay just resolved,
     * the network is back — clear all strikes from the degraded period.
     */
    fun clearTransportStrikes(url: String) {
        val key = normalizeRelayUrl(url) ?: return
        clearTransportStrikesInternal(key)
        // A successful connection means the network can resolve at least one host.
        // If we were DNS-degraded, this is the heal trigger.
        healDnsDegraded()
    }

    override fun recordRequestSuccess(relayUrl: String) {
        val key = normalizeRelayUrl(relayUrl) ?: return
        clearCapabilityStrikesInternal(key)
    }

    /**
     * Clear cooldown + dead-count for a relay the user explicitly re-added or edited.
     * A manual add is an explicit "try this now" signal — clears everything.
     */
    fun clearCooldownForRelay(url: String) {
        val key = normalizeRelayUrl(url) ?: return
        var changed = false
        caps.computeIfPresent(key) { _, current ->
            if (current.restricted) {
                current
            } else {
                clearAllRelayFailures(current).also { changed = it != current }
            }
        }
        if (changed) schedulePersist()
    }

    /**
     * Clear DNS-dead state for all relays that failed DNS resolution.
     * Called on network/VPN change — DNS resolvability is a property of the current
     * network, not the relay. A relay dead on network A is likely alive on network B.
     */
    fun clearDnsDeadOnNetworkChange() {
        var cleared = 0
        for (key in caps.keys) {
            caps.computeIfPresent(key) { _, current ->
                if (current.lastReason == SkipReason.DNS_RESOLUTION.name &&
                    (current.strikes > 0 || current.deadFailCount > 0)
                ) {
                    cleared++
                    clearTransportFailures(current)
                } else {
                    current
                }
            }
        }
        if (cleared > 0) {
            Log.w(TAG, "Network change: cleared DNS-dead state for $cleared relay(s)")
            schedulePersist()
        }
    }

    /** Internal strike-clear without the degraded-heal trigger (avoids recursion). */
    private fun clearTransportStrikesInternal(key: String, scheduleWrite: Boolean = true) {
        var existing: RelayCapabilities? = null
        caps.computeIfPresent(key) { _, current ->
            if (current.restricted ||
                (current.strikes == 0 && current.deadFailCount == 0 &&
                    current.consecutiveFailures == 0)
            ) {
                current
            } else {
                existing = current
                clearTransportFailures(current)
            }
        }
        val prior = existing ?: return
        if (prior.deadFailCount >= DEAD_RELAY_THRESHOLD) {
            Log.w(TAG, "Dead relay revived: $key (was dead with ${prior.deadFailCount} failures)")
        } else if (prior.strikes > 0) {
            Log.w(
                TAG,
                "Cleared transport strikes for $key " +
                    "(was ${prior.strikes}, reason='${prior.lastReason}')",
            )
        }
        if (scheduleWrite) schedulePersist()
    }

    /** Clear only protocol rejection cadence after a genuine EOSE. Socket open is insufficient. */
    private fun clearCapabilityStrikesInternal(key: String) {
        var priorFailures = 0
        caps.computeIfPresent(key) { _, current ->
            if (current.restricted || current.consecutiveCapabilityFailures == 0) {
                current
            } else {
                priorFailures = current.consecutiveCapabilityFailures
                clearCapabilityFailures(current)
            }
        }
        if (priorFailures == 0) return
        Log.w(TAG, "Cleared capability failures for $key after accepted REQ (was $priorFailures)")
        schedulePersist()
    }

    /** Current strike count for [url], or 0 if no entry. For diagnostics only. */
    fun strikesFor(url: String): Int = caps[normalizeRelayUrl(url) ?: url]?.strikes ?: 0

    fun dump(): String =
        caps.entries
            .sortedByDescending { maxOf(it.value.strikes, it.value.consecutiveCapabilityFailures) }
            .joinToString("\n") { (url, c) ->
                "$url: transportStrikes=${c.strikes} capFails=${c.consecutiveCapabilityFailures} " +
                    "searchOnly=${c.searchOnly} auth=${c.authRequired} restricted=${c.restricted} " +
                    "last='${c.lastCapabilityReason.ifBlank { c.lastReason }}'"
            }

    companion object {
        /** Cleartext is a policy violation (Android NSP) — instant skip.
         *  Everything else accumulates gradually so transient failures heal. */
        internal fun strikesForReason(reason: SkipReason): Int = when (reason) {
            SkipReason.CLEARTEXT_BLOCKED -> MAX_CAPABILITY_STRIKES
            else -> 1
        }

        /** Pure cooldown calculation — no store state needed. Testable directly.
         *  Integral relays: short flat window. Others: exponential backoff, capped.
         *  DNS failures use a longer base — the VPN/system resolver won't resolve
         *  a missing host in 1 minute. */
        internal fun computeRetryCooldownMs(
            isIntegral: Boolean,
            lastReason: String?,
            strikes: Int,
            consecutiveFailures: Int = 0,
        ): Long {
            if (isIntegral) return computeIntegralCooldownMs(consecutiveFailures)
            val overage = (strikes - MAX_CAPABILITY_STRIKES).coerceIn(0, 6)
            val base = if (lastReason == SkipReason.DNS_RESOLUTION.name) DNS_RETRY_BASE_MS
                       else TRANSPORT_RETRY_BASE_MS
            return (base shl overage).coerceAtMost(TRANSPORT_RETRY_MAX_MS)
        }

        /** Integral-relay retry cooldown with consecutive-failure escalation (H20b).
         *  Base 60s keeps healthy integrals healing fast; after
         *  [INTEGRAL_ESCALATION_THRESHOLD] consecutive failures (e.g. a TCP-blackholed
         *  relay the 60s heal loop probes forever) it backs off to 5 min — ~5× less
         *  bandwidth/battery burn with zero yield. Reset on success (consecutiveFailures→0).
         *  The heal loop stays UNGATED by design (H20a lesson: gates blocked recovery);
         *  escalation alone calms the hammer while recovery paths still multiply. Testable directly. */
        internal fun computeIntegralCooldownMs(consecutiveFailures: Int): Long =
            if (consecutiveFailures >= INTEGRAL_ESCALATION_THRESHOLD) INTEGRAL_ESCALATED_COOLDOWN_MS
            else INTEGRAL_RETRY_COOLDOWN_MS

        /** Structural CLOSED retry cadence: 60s through the threshold, then
         *  2m → 4m → 5m cap. Unlike transport policy this is relay-integral
         *  independent: the rejection describes the REQ class, not reachability. */
        internal fun computeCapabilityCooldownMs(consecutiveFailures: Int): Long {
            val overage = (consecutiveFailures - MAX_CAPABILITY_STRIKES).coerceIn(0, 3)
            return (CAPABILITY_RETRY_BASE_MS shl overage)
                .coerceAtMost(CAPABILITY_RETRY_MAX_MS)
        }

        /** Pure decision: is the DNS-degraded latch still active? Armed AND within
         *  [DNS_DEGRADED_TTL_MS] of its onset. Past the TTL the latch is stale and must
         *  not keep gating reconnects — the gate that armed it also defers the connects
         *  that would clear it, so a stale latch hangs forever (H20a). Testable directly. */
        internal fun isDegradedActive(armed: Boolean, onsetAtMs: Long, nowMs: Long): Boolean =
            armed && (nowMs - onsetAtMs) < DNS_DEGRADED_TTL_MS
    }
}
