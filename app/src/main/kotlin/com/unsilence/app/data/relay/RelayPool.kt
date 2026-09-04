package com.unsilence.app.data.relay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import com.unsilence.app.data.WOT_REGISTRY_LOOKUP_RELAYS
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.PaginatedFetchResult
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RelayPool"
private val ACCOUNT_METADATA_KINDS = listOf(0, 3, 10002)

/** Profile fallback negative-cache TTL — prevents re-firing the full chain. */
private const val PROFILE_FALLBACK_NEG_TTL = 5 * 60_000L
/** Max fallback relay targets across all missing pks in one batch. */
private const val MAX_PROFILE_FALLBACK_RELAYS = 8
/** Wait for EventProcessor cold-lane flush (2s batch cycle) + margin.
 *  Must track EventProcessor.drainCold's 2s timeout — if that changes, update here. */
private const val COLD_LANE_FLUSH_MS = 2_500L
private const val RECONNECT_HEALTHY_WINDOW_MS = 30_000L
private const val FOLLOW_PACK_RELAY_LIMIT = 6
private const val FOLLOW_PACK_RELAY_TIMEOUT_MS = 8_000L
private const val PROFILE_RELAY_FACTS_INDEXER_LIMIT = 4
private const val PROFILE_RELAY_FACTS_AUTHOR_RELAY_LIMIT = 3
private const val PROFILE_RELAY_FACTS_TIMEOUT_MS = 8_000L
private const val EVENT_REFERENCE_PHASE_TIMEOUT_MS = 4_000L
private const val EVENT_PROCESSOR_SETTLE_MS = 200L
private const val BRIDGE_EVENT_FETCH_TTL_MS = 5 * 60_000L
internal const val MAX_EPHEMERAL_CONNECTIONS = 4
private const val FOREGROUND_RECONNECT_STAGGER_MS = 200L
private const val MAX_FOLLOW_REFRESH_RELAYS = 8
private const val MAX_PROFILE_METADATA_REFRESH_RELAYS = 4
private const val PROFILE_METADATA_REFRESH_TIMEOUT_MS = 12_000L
private const val ACCOUNT_METADATA_FETCH_TIMEOUT_MS = 5_000L
private const val ACCOUNT_METADATA_EMPTY_EVIDENCE_RELAYS = 2
private const val MUTE_LIST_FETCH_TIMEOUT_MS = 8_000L
private const val MUTE_EMPTY_EVIDENCE_RELAYS_PER_CLASS = 2
internal const val FOLLOW_REFRESH_FRESH_MS = 60_000L
internal const val FOLLOW_REFRESH_RETRY_MS = 15_000L

internal fun reconnectPriority(purposes: Set<ConnectionPurpose>): Int = when {
    ConnectionPurpose.FEED_SUB in purposes -> 0
    ConnectionPurpose.PERSISTENT in purposes -> 1
    ConnectionPurpose.BROWSE in purposes -> 2
    ConnectionPurpose.FEED_WARM in purposes -> 3
    else -> 4
}

internal fun shouldReleaseRelayConnection(
    purposes: Set<ConnectionPurpose>,
    hasActiveSubscription: Boolean,
    lastActivityMs: Long,
    nowMs: Long,
    idleThresholdMs: Long = RelayPool.IDLE_EVICTION_THRESHOLD_MS,
): Boolean = purposes.isEmpty() &&
    !hasActiveSubscription &&
    nowMs - lastActivityMs >= idleThresholdMs

internal data class RelayEvictionCandidate(
    val url: String,
    val purposes: Set<ConnectionPurpose>,
    val hasActiveSubscription: Boolean,
    val lastActivityMs: Long,
)

/** Prefer expendable browse channels, then the oldest other non-persistent channel. */
internal fun selectForceEvictionCandidate(
    candidates: Collection<RelayEvictionCandidate>,
): RelayEvictionCandidate? {
    val eligible = candidates.filter {
        ConnectionPurpose.PERSISTENT !in it.purposes &&
            ConnectionPurpose.FEED_SUB !in it.purposes &&
            !it.hasActiveSubscription
    }
    return eligible
        .filter { ConnectionPurpose.BROWSE in it.purposes }
        .minByOrNull { it.lastActivityMs }
        ?: eligible.minByOrNull { it.lastActivityMs }
}

internal fun ensureRelayPoolCapacity(
    currentSize: () -> Int,
    cap: Int,
    forceEvict: Boolean,
    evictMostIdle: () -> Boolean,
): Boolean {
    if (currentSize() < cap) return true
    return forceEvict && evictMostIdle() && currentSize() < cap
}

internal fun <T> resetRelayOneShotBookkeeping(
    url: String,
    counts: ConcurrentHashMap<String, AtomicInteger>,
    queues: ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<T>>,
) {
    counts.remove(url)
    queues.remove(url)
}

internal data class RelayOneShotOwnerKey(val subId: String, val url: String)

internal fun <T : Any> resetRelayOneShotOwners(
    url: String,
    owners: ConcurrentHashMap<RelayOneShotOwnerKey, T>,
) {
    owners.entries.removeIf { it.key.url == url }
}

internal fun <T : Any> takeRelayOneShotOwner(
    subId: String,
    url: String,
    sourceOwner: T?,
    owners: ConcurrentHashMap<RelayOneShotOwnerKey, T>,
): T? {
    val key = RelayOneShotOwnerKey(subId, url)
    val owner = owners[key] ?: return null
    if (sourceOwner != null && owner !== sourceOwner) return null
    return if (owners.remove(key, owner)) owner else null
}

/**
 * A follow-list refresh must sample both the author's declared write relays and
 * independent indexes. Keeping the two sources explicit prevents a stale fast
 * responder in either group from becoming the sole authority.
 */
internal fun followRefreshRelayTargets(
    writeRelayUrls: Collection<String>,
    indexRelayUrls: Collection<String>,
    limit: Int,
): List<String> {
    if (limit <= 0) return emptyList()
    val writes = writeRelayUrls.mapNotNull(::normalizeRelayUrl).distinct()
    val indexes = indexRelayUrls.mapNotNull(::normalizeRelayUrl).distinct()
    val selected = linkedSetOf<String>()

    writes.take(minOf(2, limit)).forEach(selected::add)
    indexes.forEach { if (selected.size < limit) selected.add(it) }
    writes.drop(2).forEach { if (selected.size < limit) selected.add(it) }
    return selected.take(limit)
}

internal fun shouldRunFollowRefresh(
    forceRefresh: Boolean,
    nowMs: Long,
    lastSuccessMs: Long?,
    lastAttemptMs: Long?,
): Boolean {
    if (forceRefresh) return true
    if (lastSuccessMs != null && nowMs - lastSuccessMs < FOLLOW_REFRESH_FRESH_MS) return false
    if (lastAttemptMs != null && nowMs - lastAttemptMs < FOLLOW_REFRESH_RETRY_MS) return false
    return true
}

/**
 * A profile refresh is usable only after one of the verified relay events at
 * the newest observed timestamp has reached MES (or MES already has a newer
 * event). EOSE alone is only a socket boundary and must not unlock a merge
 * against an older snapshot.
 */
internal fun profileMetadataRefreshSettled(
    currentEventId: String?,
    currentCreatedAt: Long?,
    receivedCreatedAtById: Map<String, Long>,
): Boolean {
    val newestReceivedAt = receivedCreatedAtById.values.maxOrNull() ?: return false
    val retainedAt = currentCreatedAt ?: return false
    if (retainedAt > newestReceivedAt) return true
    return retainedAt == newestReceivedAt &&
        currentEventId != null &&
        receivedCreatedAtById[currentEventId] == newestReceivedAt
}

/** Wire-level result of the destructive-save kind-0 preflight. */
internal enum class ProfileMetadataRefreshResult {
    /** At least one verified kind-0 reached MES at the newest observed timestamp. */
    SETTLED,
    /** No event arrived, but at least one relay completed the query with a real EOSE. */
    CONFIRMED_ABSENT,
    /** No verified event and no real EOSE, or a received event failed to settle. */
    UNAVAILABLE,
}

internal fun profileMetadataRefreshResult(
    receivedEventCount: Int,
    realEoseCount: Int,
    settled: Boolean,
): ProfileMetadataRefreshResult = when {
    receivedEventCount > 0 && settled -> ProfileMetadataRefreshResult.SETTLED
    receivedEventCount > 0 -> ProfileMetadataRefreshResult.UNAVAILABLE
    realEoseCount > 0 -> ProfileMetadataRefreshResult.CONFIRMED_ABSENT
    else -> ProfileMetadataRefreshResult.UNAVAILABLE
}

/** Kinds accepted by every ID-based reference fetch. Empty repost hydration depends on this. */
internal val EVENT_REFERENCE_FETCH_KINDS =
    listOf(0, 1, 6, 7, 16, 20, 21, 22, 34235, 34236, 1068, 1111, 30023)

private fun buildReplaceableMetadataReq(
    subId: String,
    pubkey: String,
    kinds: Collection<Int>,
): String =
    buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(subId))
        for (kind in kinds) {
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(kind)) })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                put("limit", JsonPrimitive(1))
            })
        }
    }.toString()

internal fun buildAccountMetadataReq(subId: String, pubkey: String): String =
    buildReplaceableMetadataReq(subId, pubkey, ACCOUNT_METADATA_KINDS)

internal fun buildCommentParentsReq(subId: String, parentIds: List<String>): String =
    buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(subId))
        add(buildJsonObject {
            put("ids", buildJsonArray {
                parentIds.distinct().take(200).forEach { add(JsonPrimitive(it)) }
            })
            put("kinds", buildJsonArray {
                add(JsonPrimitive(1))
                add(JsonPrimitive(1111))
            })
            put("limit", JsonPrimitive(200))
        })
    }.toString()

internal fun isRateLimitedClosedReason(reason: String): Boolean =
    reason.contains("rate-limit", ignoreCase = true) ||
        reason.contains("too many", ignoreCase = true)

internal fun shouldResubAfterClosed(reason: String, isOneShot: Boolean): Boolean =
    !isOneShot &&
        !reason.trimStart().startsWith("auth-required", ignoreCase = true) &&
        !isRateLimitedClosedReason(reason)

internal data class ParsedNip45Count(
    val subId: String,
    val result: Nip45CountResult,
)

internal fun parseNip45CountFrame(raw: String): ParsedNip45Count? = runCatching {
    val frame = NostrJson.parseToJsonElement(raw).jsonArray
    if (frame.size < 3 || frame[0].jsonPrimitive.content != "COUNT") return@runCatching null
    val body = frame[2].jsonObject
    val count = body["count"]?.jsonPrimitive?.longOrNull ?: return@runCatching null
    ParsedNip45Count(
        subId = frame[1].jsonPrimitive.content,
        result = Nip45CountResult(
            count = count,
            limited = body["limited"]?.jsonPrimitive?.booleanOrNull == true,
        ),
    )
}.getOrNull()

/** A search result correlated with the token of the search session that produced it. */
data class SearchResult(val token: Long, val eventId: String)

/** Why a relay connection exists — a relay can hold multiple purposes simultaneously. */
enum class ConnectionPurpose {
    /** Long-lived, bootstrap-tagged (indexers, global default relays). Never evicted by sweep. */
    PERSISTENT,
    /** User-initiated relay-browse session (RelayBrowseSession). Evicted when session ends. */
    BROWSE,
    /** Active single-relay feed source. Exempted from sweep while feed is active;
     *  removed when user switches feed type. */
    FEED_SUB,
    /** Pre-warmed feed-switcher relay. Connected socket, no subscription.
     *  Exempt from sweep; dropped when no longer a switch target. */
    FEED_WARM,
}

data class RelayConnectionDebugSnapshot(
    val url: String,
    val purposes: Set<ConnectionPurpose>,
    val oneShotCount: Int,
    val queuedReqCount: Int,
    val hasActiveSubscription: Boolean,
)

/**
 * Freshness evidence collected by the dedicated kind-10000 request.
 *
 * [receivedEvent] is signature-verified by EventProcessor. [confirmedEmptyCoverage]
 * is deliberately based on real EOSE frames, not the one-shot lifecycle signal:
 * failed/closed ephemeral sockets are released as lifecycle-complete so they do
 * not leak slots, but they are not proof that the relay-side list was queried.
 */
internal data class MuteListFetchResult(
    val receivedEvent: NostrEvent? = null,
    val eoseRelays: Set<String> = emptySet(),
    val expectedRelays: Set<String> = emptySet(),
    val writeRelays: Set<String> = emptySet(),
    val fallbackRelays: Set<String> = emptySet(),
    val indexerRelays: Set<String> = emptySet(),
) {
    val confirmedEmptyCoverage: Boolean
        get() = when {
            // A declared outbox is authoritative: never overwrite until every
            // declared write relay confirms the replaceable event is absent.
            writeRelays.isNotEmpty() -> eoseRelays.containsAll(writeRelays)
            // With no kind-10002, corroborate absence across both the relays we
            // would publish to and independent indexers. Two of each tolerates
            // one unhealthy endpoint without reducing this to a single-relay guess.
            fallbackRelays.isNotEmpty() || indexerRelays.isNotEmpty() ->
                hasMuteEmptyQuorum(eoseRelays, fallbackRelays) &&
                    hasMuteEmptyQuorum(eoseRelays, indexerRelays)
            else -> expectedRelays.isNotEmpty() && eoseRelays.containsAll(expectedRelays)
        }

    val hasFreshnessEvidence: Boolean
        get() = receivedEvent != null || confirmedEmptyCoverage
}

private fun hasMuteEmptyQuorum(eoseRelays: Set<String>, candidates: Set<String>): Boolean {
    if (candidates.size < MUTE_EMPTY_EVIDENCE_RELAYS_PER_CLASS) return false
    return candidates.count { it in eoseRelays } >= MUTE_EMPTY_EVIDENCE_RELAYS_PER_CLASS
}

/** Real relay coverage from the combined own-profile/contact/relay-list lookup. */
internal data class AccountMetadataFetchResult(
    val queriedRelays: Set<String> = emptySet(),
    val eoseRelays: Set<String> = emptySet(),
    val receivedKinds: Set<Int> = emptySet(),
) {
    /**
     * A verified event proves presence. Absence needs every declared outbox, or
     * independent agreement when there is no outbox. A timeout/CLOSED frame is
     * never evidence.
     */
    fun resolves(kind: Int): Boolean =
        kind in receivedKinds || confirmsAbsent(kind)

    fun confirmsAbsent(
        kind: Int,
        authoritativeRelays: Collection<String> = emptyList(),
    ): Boolean {
        if (kind in receivedKinds) return false
        val admittedEose = eoseRelays.intersect(queriedRelays)
        val authoritative = authoritativeRelays.mapNotNull(::normalizeRelayUrl).toSet()
        return if (authoritative.isNotEmpty()) {
            admittedEose.containsAll(authoritative)
        } else {
            admittedEose.size >= ACCOUNT_METADATA_EMPTY_EVIDENCE_RELAYS
        }
    }

    /** Profile/relay-list events alone do not resolve the contact graph. */
    val hasGraphResponse: Boolean get() = resolves(3)
}

internal fun canMaterializeEmptyContactList(
    localStateResolved: Boolean,
    declaredWriteRelays: Collection<String>,
    result: AccountMetadataFetchResult,
): Boolean =
    !localStateResolved &&
        result.confirmsAbsent(3, authoritativeRelays = declaredWriteRelays)

/** Source of "which relays currently have a non-paused subscription."
 *  Consulted by the pool sweep to avoid force-closing connections with
 *  live subscriptions. Implemented by [Subscription]. */
interface ActiveSubsSource {
    fun activeRelayUrls(): Set<String>
    fun activeSubIds(): Set<String>
}

/**
 * Manages multiple relay WebSocket connections for the global feed.
 *
 * Architecture rule: events flow Relay → EventProcessor → MemoryEventStore.
 * The pool itself never touches the UI.
 */
@Singleton
class RelayPool @Inject constructor(
    private val relayConnectionFactory: RelayConnectionFactory,
    private val processor: EventProcessor,
    private val relayPreferencesStore: dagger.Lazy<RelayPreferencesStore>,
    private val signingManager: com.unsilence.app.data.auth.SigningManager,
    private val keyManager: com.unsilence.app.data.auth.KeyManager,
    private val memoryEventStore: dagger.Lazy<com.unsilence.app.data.memory.MemoryEventStore>,
    private val relayCapabilitiesStore: RelayCapabilitiesStore,
    private val activeSubsSource: dagger.Lazy<ActiveSubsSource>,
    private val networkMonitor: NetworkMonitor,
    private val nip11Fetcher: Nip11Fetcher,
) : RelayTransport, ReconnectSource {
    // WebSocket consume loops MUST not be starved by snapshot restore or
    // other heavy IO. limitedParallelism(8) reserves dedicated threads for
    // inbound message processing.
    private val wsDispatcher = Dispatchers.IO.limitedParallelism(8)
    private val scope = CoroutineScope(SupervisorJob() + wsDispatcher)
    private val connections = ConcurrentHashMap<String, RelayConnection>()
    private val socketLifecycleLock = Any()
    private val connectionRegistry = RelayConnectionRegistry(
        connections = connections,
        lifecycleLock = socketLifecycleLock,
        createConnection = relayConnectionFactory::create,
    )
    private val socketTransportSuspended = AtomicBoolean(false)

    /** Relay URLs deferred during network-down/DNS-degraded. Drained with jitter
     *  when the network recovers (checked in the 60s sweep). */
    private val pendingReconnect: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Coverage-ranked global outbox relay allowlist. Ephemeral connections to relays
     *  NOT in this set (and not in the persistent pool) are skipped to shrink the DNS
     *  failure surface. Populated by [updateOutboxAllowlist] after kind-10002 is fetched. */
    @Volatile private var outboxAllowlist: Set<String> = emptySet()
    private val reconnecting = ConcurrentHashMap<String, AtomicBoolean>()
    /** Retained across short-lived successful sockets; cleared only after a healthy window. */
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    /** Cached blocked relay URLs, refreshed before each connect(). */
    @Volatile private var blockedUrls: Set<String> = emptySet()

    /** Integral relay URLs (indexer/read/write/search) — re-attempted by the heal
     *  pass when disconnected and past their skip cooldown. */
    @Volatile private var integralRelayUrls: Set<String> = emptySet()

    fun setIntegralRelays(urls: Collection<String>) {
        integralRelayUrls = urls.mapNotNull { normalizeRelayUrl(it) }.toSet()
    }

    /** Update the coverage-ranked outbox allowlist. Ephemeral connections to relays
     *  outside this set (and not in the persistent pool) are skipped. */
    fun setOutboxAllowlist(urls: Set<String>) {
        outboxAllowlist = urls
        Log.d(TAG, "Outbox allowlist updated: ${urls.size} relays")
    }

    /** Read-only access to blocked relay URLs for allowlist construction. */
    fun getBlockedUrls(): Set<String> = blockedUrls

    /**
     * Set by FeedViewModel when the user is viewing a SingleRelay feed.
     * One-shot dispatch paths exclude this URL from their relay sets to prevent
     * funneling auxiliary work back to the relay already hosting the persistent
     * feed subscription.
     *
     * Null when the active feed is Following / Global / RelaySet — in those
     * cases auxiliary fanout across multiple relays is desirable and the
     * funneling concern doesn't apply.
     */
    @Volatile var activeSingleRelayFeedUrl: String? = null

    @Volatile private var activeFeedRelayHintsSnapshot: List<String> = emptyList()

    /** Bounded SingleRelay/RelaySet locality hints for row-backed hydration. */
    internal fun setActiveFeedRelayHints(relayUrls: Collection<String>) {
        activeFeedRelayHintsSnapshot = boundedSeenRelayHints(
            seenRelays = emptyList(),
            browseRelays = relayUrls,
        )
    }

    internal fun activeFeedRelayHints(): List<String> = activeFeedRelayHintsSnapshot

    /** Snapshot of currently-connected relay URLs. Read-only, used by CardHydrator
     *  as fallback when write relays are unknown. */
    fun connectedRelayUrls(): List<String> = connections.values
        .filter { it.isConnected }
        .map { it.url }

    /**
     * Open [connection] only while foreground transport is allowed. The lock
     * closes the race where ProcessLifecycle.onStop lands between a caller's
     * background check and RelayConnection.connect().
     */
    private fun connectIfTransportActive(connection: RelayConnection): Boolean =
        synchronized(socketLifecycleLock) {
            if (socketTransportSuspended.get()) {
                false
            } else {
                connection.connect()
                true
            }
        }

    private suspend fun connectTrackedEphemeral(connection: RelayConnection): Boolean {
        ephemeralSemaphore.acquire()
        activeEphemeralConnections.add(connection)
        if (connectIfTransportActive(connection)) return true
        activeEphemeralConnections.remove(connection)
        ephemeralSemaphore.release()
        return false
    }

    private fun closeTrackedEphemeral(connection: RelayConnection) {
        if (activeEphemeralConnections.remove(connection)) {
            ephemeralSemaphore.release()
        }
        connection.close()
    }

    /**
     * Close every pooled and ephemeral WebSocket when the app loses its
     * foreground lifecycle. Connection-purpose, auth-policy, and subscription
     * state are retained so foreground recovery can recreate the channels and
     * replay REQs without a cold bootstrap.
     */
    fun suspendSocketsForBackground() {
        val pooled: List<RelayConnection>
        val ephemeral: List<RelayConnection>
        synchronized(socketLifecycleLock) {
            if (!socketTransportSuspended.compareAndSet(false, true)) return
            pooled = connections.values.toList()
            ephemeral = activeEphemeralConnections.toList()
            pooled.forEach { it.close() }
            ephemeral.forEach { it.close() }
        }
        updateConnectionStates()
        Log.d(TAG, "Background socket suspend: pooled=${pooled.size} ephemeral=${ephemeral.size}")
    }

    /** Allow transport again and recreate suspended pooled channels in priority order. */
    fun resumeSocketsForForeground() {
        if (!socketTransportSuspended.compareAndSet(true, false)) return
        reconnectAll()
    }

    internal fun socketsSuspendedForTest(): Boolean = socketTransportSuspended.get()

    // ── Connection purpose tracking ────────────────────────────────────────
    // A relay can serve multiple purposes simultaneously (e.g. PERSISTENT + BROWSE).
    // Persistent sub replay is only skipped when a relay is browse-only.
    private val connectionPurposes = ConcurrentHashMap<String, MutableSet<ConnectionPurpose>>()

    fun addPurpose(url: String, purpose: ConnectionPurpose) {
        connectionPurposes.computeIfAbsent(url) { ConcurrentHashMap.newKeySet() }.add(purpose)
        Log.d(TAG, "Purpose + $purpose on $url (now: ${connectionPurposes[url]})")
    }

    fun removePurpose(url: String, purpose: ConnectionPurpose) {
        connectionPurposes[url]?.remove(purpose)
        if (connectionPurposes[url]?.isEmpty() == true) {
            connectionPurposes.remove(url)
        }
        Log.d(TAG, "Purpose - $purpose on $url (now: ${connectionPurposes[url] ?: "none"})")
    }

    fun hasPurpose(url: String, purpose: ConnectionPurpose): Boolean =
        connectionPurposes[url]?.contains(purpose) == true

    fun isBrowseOnly(url: String): Boolean =
        hasPurpose(url, ConnectionPurpose.BROWSE) &&
        !hasPurpose(url, ConnectionPurpose.PERSISTENT)

    fun hasAnyPurpose(url: String): Boolean =
        connectionPurposes[url]?.isNotEmpty() == true

    /** Connection cleanup is fail-closed: unknown subscription state means retain. */
    private fun activeSubUrlsForCleanup(): Set<String>? = runCatching {
        activeSubsSource.get().activeRelayUrls()
    }.onFailure {
        Log.w(TAG, "Unable to snapshot active subscriptions; skipping connection cleanup", it)
    }.getOrNull()

    fun connectionDebugSnapshot(): Map<String, RelayConnectionDebugSnapshot> {
        val activeSubUrls = runCatching { activeSubsSource.get().activeRelayUrls() }
            .getOrDefault(emptySet())
        val urls = buildSet {
            addAll(connections.keys)
            addAll(connectionPurposes.keys)
            addAll(activeSubUrls)
            addAll(relayOneShotCount.keys)
            addAll(relayReqQueue.keys)
        }
        return urls.associateWith { url ->
            RelayConnectionDebugSnapshot(
                url = url,
                purposes = connectionPurposes[url]?.toSet().orEmpty(),
                oneShotCount = relayOneShotCount[url]?.get() ?: 0,
                queuedReqCount = relayReqQueue[url]?.size ?: 0,
                hasActiveSubscription = url in activeSubUrls,
            )
        }
    }

    private val countCallbacks = ConcurrentHashMap<String, CompletableDeferred<Nip45CountResult?>>()
    /** Per-subId EOSE completion signal. Callers register before dispatch, await after.
     *  handleEose completes the deferred when any relay EOSE's the sub. */
    internal val oneShotEoseCallbacks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    /** Per-subId set of relay URLs the one-shot was sent to. */
    private val oneShotSubTargets = ConcurrentHashMap<String, Set<String>>()
    /** Per-subId set of relay URLs that have EOSE'd or CLOSED. */
    private val oneShotSubEosed = ConcurrentHashMap<String, MutableSet<String>>()
    /** Per-subId set of relay URLs whose slot has already been released.
     *  Idempotency guard for [releaseOneShotForRelay] — prevents double-decrement
     *  when both handleEose and cleanupOneShotSub fire for the same (subId, url). */
    private val oneShotReleased = ConcurrentHashMap<String, MutableSet<String>>()
    private val profileFetchAttempted = ConcurrentHashMap<String, Long>()
    private val hintedProfileFetchAttempted = ConcurrentHashMap<String, Long>()
    /** Pubkeys that went through the full indexer+fallback chain and still missed.
     *  5-min TTL prevents scroll-back from re-firing the chain. */
    private val profileFallbackNegCache = ConcurrentHashMap<String, Long>()
    private val followRefreshLastAttemptMs = ConcurrentHashMap<String, Long>()
    private val followRefreshLastSuccessMs = ConcurrentHashMap<String, Long>()

    /** In-flight event fetch dedup — maps event ID to completion signal.
     *  Callers that arrive while a fetch is in-flight skip the REQ;
     *  the monitor coroutine completes the Deferred and removes the entry. */
    private val eventFetchInFlight = ConcurrentHashMap<String, CompletableDeferred<NostrEvent?>>()
    /** Peak eventFetchInFlight.size since last metrics snapshot — for instrumentation. */
    private val eventFetchInFlightPeak = AtomicInteger(0)

    /** Negative cache: event IDs that failed to resolve after all outbox phases.
     *  Values = timestamp (epoch ms). TTL 5 min. Written by CardHydrator, checked
     *  by every fetchEventById/fetchEventsByIds entry point. */
    private val missingRefCache = ConcurrentHashMap<String, Long>()
    /** Per-ID guard for the single miss-tier bridge attempt. */
    private val bridgeEventFetchAttempted = ConcurrentHashMap<String, Long>()
    private val MISSING_REF_TTL_MS = 5 * 60 * 1000L // 5 minutes
    private val missingRefCacheHits = AtomicLong(0)
    private val eventIdFetchCoalescer = EventIdFetchCoalescer(scope) { batch ->
        if (batch.relayHints.isEmpty()) {
            fetchEventsByIds(batch.eventIds)
        } else {
            fetchEventsByIdsWithHints(batch.eventIds, batch.relayHints, bypassDedup = false)
        }
    }

    // ── Ephemeral WebSocket rate limiting ─────────────────────────────────
    /** Per-URL last-open timestamp (nanos) for ephemeral connections — min 50ms gap. */
    private val ephemeralLastOpenNanos = ConcurrentHashMap<String, AtomicLong>()
    private val MIN_EPHEMERAL_GAP_NS = 50_000_000L // 50ms
    /** Global handshake/connection cap: weak links must not fan out dozens in parallel. */
    private val ephemeralSemaphore = Semaphore(MAX_EPHEMERAL_CONNECTIONS)
    private val activeEphemeralConnections = ConcurrentHashMap.newKeySet<RelayConnection>()

    /** Relays that have completed NIP-42 auth successfully. */
    private val authenticatedRelays = ConcurrentHashMap.newKeySet<String>()

    /** Relays currently waiting for an auth response (prevents duplicate auth attempts). */
    private val authInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Last challenge received per relay — needed for CLOSED auth-required flow. */
    private val pendingChallenges = ConcurrentHashMap<String, String>()

    /** Relays that sent CLOSED auth-required without a prior AUTH challenge — suppress repeated warnings. */
    private val authFailedRelays = ConcurrentHashMap.newKeySet<String>()

    /** Auth event IDs awaiting OK response — maps eventId → relay URL. */
    private val pendingAuthEventIds = ConcurrentHashMap<String, String>()

    /** Consecutive auth-required rejections per relay since last real OK.
     *  Reset on real OK. */
    private val authRejectionStreak = ConcurrentHashMap<String, Int>()

    /** Relays granted the one-time no-OK optimistic fallback on this connection.
     *  A subsequent challenge proves that optimism did not establish a stable
     *  authenticated session and must not start another replay loop. */
    private val optimisticAuthUsed = ConcurrentHashMap.newKeySet<String>()

    /** Relays whose auth repeatedly failed — excluded from fan-out.
     *  Session-scoped (in-memory). Cleared on logout via disconnectAll(). */
    private val authUnavailableRelays = ConcurrentHashMap.newKeySet<String>()

    /** Eligibility quarantine + distinct-challenge signing budget for this login session. */
    private val authSessionPolicy = RelayAuthSessionPolicy()

    /** Emitted when a relay is determined to require auth we can't satisfy. */
    private val _relayAuthUnavailable = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val relayAuthUnavailable: SharedFlow<String> = _relayAuthUnavailable.asSharedFlow()

    /** Publish OK callbacks — maps eventId → callback receiving (relayUrl, success, message). */
    private val publishOkCallbacks = ConcurrentHashMap<String, (String, Boolean, String) -> Unit>()

    /** Last time each relay received a message — used for idle eviction. */
    private val connectionLastActivity = ConcurrentHashMap<String, Long>()

    /** Active one-shot subscriptions in flight (tracked by unique sub-ID, not per-relay). */
    private val _activeOneShotSubs = ConcurrentHashMap.newKeySet<String>()

    // ── Persistent own-mute-list subscription ─────────────────────────
    // Keeps a live tail on own kind-10000 so cross-client mute changes
    // arrive without needing a cold-start re-fetch.
    @Volatile private var liveMuteSubReq: String? = null
    private val liveMuteSubRelays = ConcurrentHashMap.newKeySet<String>()

    // Persistent #p notification tail — forward-looking only (since:bootstrap_time).
    // Catches new reactions/reposts/zaps/replies mentioning the user after bootstrap.
    @Volatile private var liveNotifSubReq: String? = null
    @Volatile private var liveNotifSince: Long? = null
    private val liveNotifSubRelays = ConcurrentHashMap.newKeySet<String>()
    private val searchTimeoutJobs = ConcurrentHashMap<Long, Job>()

    // ── Paginated fetch state ─────────────────────────────────────────
    private data class PageState(
        val eventCount: AtomicInteger = AtomicInteger(0),
        val oldestCreatedAt: AtomicLong = AtomicLong(Long.MAX_VALUE),
        val eoseReceived: CompletableDeferred<Unit> = CompletableDeferred(),
    )
    private val _activePages = ConcurrentHashMap<String, PageState>()

    /** Number of in-flight one-shot subscriptions — used by HydrationFrontier for priority shedding. */
    fun activeOneShotCount(): Int = _activeOneShotSubs.size

    // ── Negative cache API ───────────────────────────────────────────

    /** Check if an event ID is in the negative cache (within TTL). Expired entries are evicted lazily. */
    fun isEventUnresolved(eventId: String): Boolean {
        val ts = missingRefCache[eventId] ?: return false
        if (System.currentTimeMillis() - ts < MISSING_REF_TTL_MS) {
            missingRefCacheHits.incrementAndGet()
            return true
        }
        missingRefCache.remove(eventId)
        return false
    }

    /** Mark an event ID as unresolved (negative cache, 5-min TTL). */
    fun markEventUnresolved(eventId: String) {
        missingRefCache[eventId] = System.currentTimeMillis()
    }

    // ── Relay metrics (for MES/size logger) ──────────────────────────

    data class RelayMetrics(
        val eventFetchInFlightPeak: Int,
        val missingRefCacheSize: Int,
        val missingRefCacheHits: Long,
    )

    fun snapshotRelayMetrics(): RelayMetrics = RelayMetrics(
        eventFetchInFlightPeak = eventFetchInFlightPeak.getAndSet(0),
        missingRefCacheSize = missingRefCache.size,
        missingRefCacheHits = missingRefCacheHits.getAndSet(0),
    )

    // ── In-flight fetch monitor ──────────────────────────────────────

    /** Launch a coroutine that polls MES for event arrival and completes the Deferred.
     *  Removes the entry from eventFetchInFlight on completion (success or timeout). */
    private fun launchFetchMonitor(eventId: String, deferred: CompletableDeferred<NostrEvent?>) {
        scope.launch {
            try {
                val mes = memoryEventStore.get()
                val result = withTimeoutOrNull(30_000L) {
                    while (true) {
                        mes.getNostrEvent(eventId)?.let { return@withTimeoutOrNull it }
                        delay(500)
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
                deferred.complete(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                deferred.complete(null)
                throw e
            } catch (e: Exception) {
                deferred.complete(null)
            } finally {
                eventFetchInFlight.remove(eventId)
            }
        }
    }

    /** Update the peak-size watermark for instrumentation. */
    private fun trackInFlightPeak() {
        val current = eventFetchInFlight.size
        eventFetchInFlightPeak.updateAndGet { maxOf(it, current) }
    }

    // ── Per-relay REQ queue ───────────────────────────────────────────
    // Relays typically allow 10-20 concurrent subscriptions. When a relay hits
    // the limit, new one-shot REQs are queued and sent as CLOSE events free slots.
    companion object {
        const val MAX_CONCURRENT_REQS_PER_RELAY = 10
        const val IDLE_EVICTION_THRESHOLD_MS = 60_000L
        // Safety rail against runaway bugs. Not a resource policy.
        // BROWSE is session-scoped. If this fires, something is misbehaving — investigate.
        const val POOL_SAFETY_CAP = 50
        const val POOL_SWEEP_CAP = 40
        const val RATE_LIMIT_MAX_TOKENS = 5
        const val RATE_LIMIT_REFILL_MS = 1000L
        const val RATE_LIMIT_COOLDOWN_MS = 30_000L
        const val RECONNECT_JITTER_WINDOW_MS = 8_000L
        const val SEARCH_TIMEOUT_MS = 10_000L
        const val RELAY_MONITOR_URL = "wss://relay.nostr.watch"
        const val RELAY_MONITOR_PUBKEY =
            "9bbbb845e5b6c831c29789900769843ab43bb5047abe697870cb50b6fc9bf923"
        const val MAX_AUTH_REJECTIONS = 3
        /** One optimistic attempt plus one re-challenge without an OK is enough
         *  to prove the relay cannot establish a stable authenticated session. */
        const val MAX_AUTH_NO_OK_STREAK = 2
    }

    /** Active one-shot sub count per relay URL. */
    private val relayOneShotCount = ConcurrentHashMap<String, AtomicInteger>()
    /** Queued REQs per relay — sent when slots free up. */
    private data class QueuedRelayReq(
        val payload: String,
        val requestClass: RelayRequestClass,
        val bypassCooldown: Boolean,
    )

    private val relayReqQueue =
        ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<QueuedRelayReq>>()
    /** Exact socket that owns each counted one-shot. A URL replacement must not
     *  let cleanup from the old socket decrement the new socket's counter. */
    private val relayOneShotOwners =
        ConcurrentHashMap<RelayOneShotOwnerKey, RelayConnection>()

    /** One-shot state is owned by the current WebSocket instance, never by a URL forever. */
    private fun resetConnectionScopedState(url: String) {
        resetRelayOneShotOwners(url, relayOneShotOwners)
        resetRelayOneShotBookkeeping(url, relayOneShotCount, relayReqQueue)
    }

    private fun resetRelayAuthState(url: String) {
        authenticatedRelays.remove(url)
        authInFlight.remove(url)
        optimisticAuthUsed.remove(url)
        pendingChallenges.remove(url)
        authFailedRelays.remove(url)
        authRejectionStreak.remove(url)
        authUnavailableRelays.remove(url)
        pendingAuthEventIds.values.removeAll { it == url }
    }

    private fun acquirePooledConnection(
        url: String,
        forceEvict: Boolean = false,
        bypassPoolCap: Boolean = false,
        resetAuth: Boolean = false,
    ): RelayConnectionClaim? = connectionRegistry.acquire(
        url = url,
        transportAllowed = { !socketTransportSuspended.get() },
        canCreateNew = {
            bypassPoolCap || ensurePoolSlot(forceEvict)
        },
        beforeInstall = { installedUrl, _ ->
            // Applies to both first install and replacement. If any stale
            // URL-scoped state survived an older removal path, it cannot leak
            // into this socket's one-shot ownership.
            resetConnectionScopedState(installedUrl)
            if (resetAuth) resetRelayAuthState(installedUrl)
            connectionLastActivity[installedUrl] = System.currentTimeMillis()
        },
    )

    private fun removePooledConnection(
        url: String,
        clearPurposes: Boolean = false,
    ): RelayConnection? = synchronized(socketLifecycleLock) {
        val removed = connections.remove(url) ?: return@synchronized null
        resetConnectionScopedState(url)
        connectionLastActivity.remove(url)
        if (clearPurposes) connectionPurposes.remove(url)
        removed
    }

    private fun removePooledConnection(
        url: String,
        expected: RelayConnection,
        clearPurposes: Boolean = false,
    ): RelayConnection? = synchronized(socketLifecycleLock) {
        if (!connections.remove(url, expected)) return@synchronized null
        resetConnectionScopedState(url)
        connectionLastActivity.remove(url)
        if (clearPurposes) connectionPurposes.remove(url)
        expected
    }

    // ── Per-relay rate limiter (token bucket + cooldown) ──────────────
    private data class RateLimitState(
        val tokens: AtomicInteger = AtomicInteger(RATE_LIMIT_MAX_TOKENS),
        val lastRefill: AtomicLong = AtomicLong(System.currentTimeMillis()),
        val cooldownUntil: AtomicLong = AtomicLong(0),
    )
    private val rateLimiters = ConcurrentHashMap<String, RateLimitState>()
    /** Log cooldown drops once per relay per cooldown window. */
    private val cooldownLogged = ConcurrentHashMap<String, Long>()

    /** True when the relay is NOT in a CLOSED-triggered cooldown window.
     *  Unlike [canSendToRelay], this does NOT consume a rate-limit token — used by
     *  [flushRelayQueue] where REQs were already rate-gated on entry. */
    private fun isRelayOutOfCooldown(url: String): Boolean {
        val state = rateLimiters[url] ?: return true
        return System.currentTimeMillis() >= state.cooldownUntil.get()
    }

    override fun isRateLimited(url: String): Boolean {
        val normalized = normalizeRelayUrl(url) ?: return false
        return !isRelayOutOfCooldown(normalized)
    }

    private fun canSendToRelay(url: String): Boolean {
        val state = rateLimiters.getOrPut(url) { RateLimitState() }
        val now = System.currentTimeMillis()
        // Cooldown check
        if (now < state.cooldownUntil.get()) return false
        // Refill tokens
        val elapsed = now - state.lastRefill.get()
        if (elapsed >= RATE_LIMIT_REFILL_MS) {
            val refillCount = (elapsed / RATE_LIMIT_REFILL_MS).toInt().coerceAtMost(RATE_LIMIT_MAX_TOKENS)
            state.tokens.updateAndGet { (it + refillCount).coerceAtMost(RATE_LIMIT_MAX_TOKENS) }
            state.lastRefill.set(now)
        }
        // Consume token — getAndUpdate returns the PRE-update value.
        // If pre-update > 0, we had a token to spend → allowed.
        val had = state.tokens.getAndUpdate { if (it > 0) it - 1 else 0 }
        if (had <= 0) {
            Log.d(TAG, "Rate limit: token exhausted for $url (throttling)")
        }
        return had > 0
    }

    private fun markRelayRateLimited(url: String) {
        val state = rateLimiters.getOrPut(url) { RateLimitState() }
        val now = System.currentTimeMillis()
        while (true) {
            val existingUntil = state.cooldownUntil.get()
            if (now < existingUntil) return
            val until = now + RATE_LIMIT_COOLDOWN_MS
            if (state.cooldownUntil.compareAndSet(existingUntil, until)) {
                Log.w(TAG, "Relay $url marked for cooldown until ${java.util.Date(until)} (rate-limited)")
                return
            }
        }
    }

    /**
     * Send a one-shot REQ to a relay, queuing if the relay is at its concurrent sub limit.
     * Drops REQs when the relay is in rate-limit cooldown or token-starved.
     * Call this instead of conn.send(req) for all one-shot subscription REQs.
     *
     * Prefetch subs (sub-ID starting with "prefetch-") are capped at 8 slots,
     * reserving 2 slots for high-priority work (profile fetches, user posts,
     * relay ecosystem). Without this reservation, prefetch floods all 10 slots
     * and starves profile page content.
     */
    private fun sendOneShotToRelay(
        conn: RelayConnection,
        req: String,
        requestClass: RelayRequestClass = RelayRequestClass.GENERAL,
        bypassCooldown: Boolean = false,
    ) {
        synchronized(socketLifecycleLock) {
            // A replacement can win after the caller reads the map. Retarget to
            // the current owner while holding the same lock used by install/remove.
            val current = connections[conn.url]
            if (current == null) {
                extractReqSubId(req)?.let { subId -> recordOneShotRelayCoverage(subId, conn.url) }
                return@synchronized
            }
            if (relayCapabilitiesStore.shouldSkipRequest(current.url, requestClass, bypassCooldown)) {
                extractReqSubId(req)?.let { subId -> recordOneShotRelayCoverage(subId, current.url) }
                Log.d(TAG, "One-shot REQ skipped for ${current.url} ($requestClass)")
                return@synchronized
            }
            val count = relayOneShotCount.computeIfAbsent(current.url) { AtomicInteger(0) }
            val isPrefetch = req.contains("\"prefetch-")
            val effectiveCap = if (isPrefetch) MAX_CONCURRENT_REQS_PER_RELAY - 2 else MAX_CONCURRENT_REQS_PER_RELAY
            // Queue when at sub cap OR rate-limited (don't drop — flush will retry later)
            if (count.get() >= effectiveCap || !canSendToRelay(current.url)) {
                val queue = relayReqQueue.computeIfAbsent(current.url) { java.util.concurrent.ConcurrentLinkedQueue() }
                queue.add(QueuedRelayReq(req, requestClass, bypassCooldown))
                Log.d(TAG, "Queued REQ for ${current.url} (${count.get()}/$MAX_CONCURRENT_REQS_PER_RELAY active)")
                return@synchronized
            }
            count.incrementAndGet()
            val ownerKey = extractReqSubId(req)?.let { RelayOneShotOwnerKey(it, current.url) }
            if (ownerKey != null) relayOneShotOwners[ownerKey] = current
            if (!current.send(req)) {
                count.decrementAndGet()
                if (ownerKey != null) relayOneShotOwners.remove(ownerKey, current)
                Log.w(TAG, "One-shot send failed for ${current.url}; slot released")
            }
        }
    }

    /**
     * Dispatch a one-shot REQ to [url]: reuse the pooled connection if one exists,
     * otherwise open an ephemeral connection (no pool slot, globally capped, auto-closes
     * after EOSE/timeout). NEVER connectAndAwait — transient hint/ref fetches must
     * not occupy pool slots (Slice 8: 192-relay hint fan-out exhausted the pool).
     */
    private fun sendOneShotPooledOrEphemeral(
        url: String,
        req: String,
        subId: String,
        timeoutMs: Long = 2_000,
        requestClass: RelayRequestClass = RelayRequestClass.GENERAL,
    ) {
        // Belt-and-suspenders: callers normalize, but guard here so a malformed URL
        // never reaches openEphemeral → RelayConnection.connect → okhttp crash.
        val clean = normalizeRelayUrl(url) ?: run {
            Log.w(TAG, "sendOneShotPooledOrEphemeral: skipping invalid relay url: ${url.take(80)}")
            return
        }
        if (relayCapabilitiesStore.shouldSkipRequest(clean, requestClass)) {
            recordOneShotRelayCoverage(subId, clean)
            return
        }
        val conn = connections[clean]
        if (conn != null) {
            sendOneShotToRelay(conn, req, requestClass)
        } else {
            // Outbox allowlist gate: don't open ephemeral connections to relays
            // outside the coverage-ranked set. Shrinks the DNS failure surface.
            // Allowlist empty = not yet populated (bootstrap) → allow all.
            if (outboxAllowlist.isNotEmpty() && clean !in outboxAllowlist) {
                Log.d(TAG, "Ephemeral skipped (not in outbox allowlist): ${clean.take(60)}")
                return
            }
            scope.launch {
                openEphemeral(
                    clean,
                    listOf(req),
                    setOf(subId),
                    timeoutMs,
                    requestClass = requestClass,
                )
            }
        }
    }

    /**
     * Flush queued REQs for a relay after a slot frees up.
     */
    private fun flushRelayQueue(conn: RelayConnection) {
        synchronized(socketLifecycleLock) {
            if (connections[conn.url] !== conn) return@synchronized
            val count = relayOneShotCount[conn.url] ?: return@synchronized
            val queue = relayReqQueue[conn.url] ?: return@synchronized
            while (count.get() < MAX_CONCURRENT_REQS_PER_RELAY &&
                   queue.isNotEmpty() &&
                   isRelayOutOfCooldown(conn.url)) {
                val queued = queue.poll() ?: break
                if (relayCapabilitiesStore.shouldSkipRequest(
                        conn.url,
                        queued.requestClass,
                        queued.bypassCooldown,
                    )
                ) {
                    extractReqSubId(queued.payload)?.let { subId ->
                        recordOneShotRelayCoverage(subId, conn.url)
                    }
                    continue
                }
                count.incrementAndGet()
                val ownerKey = extractReqSubId(queued.payload)?.let { RelayOneShotOwnerKey(it, conn.url) }
                if (ownerKey != null) relayOneShotOwners[ownerKey] = conn
                if (conn.send(queued.payload)) {
                    Log.d(TAG, "Flushed queued REQ on ${conn.url} (${count.get()}/$MAX_CONCURRENT_REQS_PER_RELAY active)")
                } else {
                    count.decrementAndGet()
                    if (ownerKey != null) relayOneShotOwners.remove(ownerKey, conn)
                    queue.add(queued)
                    Log.w(TAG, "Queued REQ send failed for ${conn.url}; slot released")
                    break
                }
            }
        }
    }

    /** Safety rail, with an explicit priority escape hatch for critical callers. */
    private fun ensurePoolSlot(forceEvict: Boolean): Boolean {
        val size = connections.size
        if (ensureRelayPoolCapacity(
                currentSize = connections::size,
                cap = POOL_SAFETY_CAP,
                forceEvict = forceEvict,
                evictMostIdle = ::forceEvictMostIdle,
            )
        ) return true
        Log.w(TAG, "Pool safety cap reached ($size/$POOL_SAFETY_CAP) — " +
            if (forceEvict) "no safe eviction candidate" else "connection refused")
        return false
    }

    private fun logPoolState() {
        val purposeCounts = mutableMapOf<String, Int>()
        for ((url, _) in connections) {
            val purposes = connectionPurposes[url]
            if (purposes.isNullOrEmpty()) {
                purposeCounts["NONE"] = (purposeCounts["NONE"] ?: 0) + 1
            } else {
                for (p in purposes) {
                    val key = p.name
                    purposeCounts[key] = (purposeCounts[key] ?: 0) + 1
                }
            }
        }
        Log.d(TAG, "Pool: total=${connections.size} $purposeCounts")
    }

    /**
     * Try to evict one idle BROWSE connection to make room for a new one.
     * Returns true if a connection was evicted.
     */
    private fun evictIdleConnection(): Boolean {
        val now = System.currentTimeMillis()
        val activeSubUrls = activeSubUrlsForCleanup() ?: return false
        // BROWSE and purpose-less (NONE) connections are evictable. PERSISTENT is exempt.
        val candidate = connections.entries
            .filter { (url, _) ->
                !hasPurpose(url, ConnectionPurpose.PERSISTENT) &&
                (hasPurpose(url, ConnectionPurpose.BROWSE) || !hasAnyPurpose(url)) &&
                url !in activeSubUrls
            }
            .filter { (url, _) ->
                val lastActive = connectionLastActivity[url] ?: 0L
                (now - lastActive) >= IDLE_EVICTION_THRESHOLD_MS
            }
            .maxByOrNull { (url, _) ->
                // Evict the most idle connection first
                now - (connectionLastActivity[url] ?: 0L)
            }
        if (candidate != null) {
            val (url, conn) = candidate
            val idleSec = (now - (connectionLastActivity[url] ?: 0L)) / 1000
            val removed = removePooledConnection(url, conn, clearPurposes = true)
                ?: return false
            removed.close()
            Log.d(TAG, "Evicted idle connection $url (idle ${idleSec}s, at cap)")
            return true
        }
        return false
    }

    /**
     * Force-evict the most idle BROWSE connection regardless of idle threshold.
     * Falls back to the most idle non-PERSISTENT connection.
     * Used by [connectAndAwait] with forceEvict=true for critical one-shot queries
     * (e.g., NIP-45 COUNT) when the pool is at cap and normal eviction fails.
     */
    private fun forceEvictMostIdle(): Boolean {
        val now = System.currentTimeMillis()
        val activeSubUrls = activeSubUrlsForCleanup() ?: return false
        val candidate = selectForceEvictionCandidate(
            connections.keys.map { url ->
                RelayEvictionCandidate(
                    url = url,
                    purposes = connectionPurposes[url]?.toSet().orEmpty(),
                    hasActiveSubscription = url in activeSubUrls,
                    lastActivityMs = connectionLastActivity[url] ?: 0L,
                )
            },
        ) ?: return false
        val conn = connections[candidate.url] ?: return false
        val removed = removePooledConnection(candidate.url, conn, clearPurposes = true)
            ?: return false
        removed.close()
        val idleSec = (now - candidate.lastActivityMs) / 1000
        Log.d(TAG, "Force-evicted connection ${candidate.url} (idle ${idleSec}s) for one-shot query")
        return true
    }

    // Wire EventProcessor relay set ref fetcher
    init {
        processor.relaySetRefFetcher = RelaySetRefFetcher { author, dTags, hintRelayUrls ->
            scope.launch { fetchRelaySetsByCoordinate(author, dTags, hintRelayUrls) }
        }
    }

    // Hook 2 (H18.4b): network identity change → clear DNS-dead state.
    // VPN toggle / WiFi↔cellular changes the DNS resolver. Relays dead on
    // the old network may resolve on the new one. Don't eagerly reconnect —
    // just clear the skip-gate; demand paths + 60s sweep pick them up.
    init {
        scope.launch {
            networkMonitor.networkChanged.collect {
                relayCapabilitiesStore.clearDnsDeadOnNetworkChange()
            }
        }
    }

    // Evict stale entries every 5 minutes to prevent unbounded growth.
    init {
        scope.launch {
            while (true) {
                delay(300_000)
                val cutoff = System.currentTimeMillis() - 300_000
                // eventFetchInFlight: self-cleaning via launchFetchMonitor finally blocks
                missingRefCache.entries.removeIf { it.value < cutoff } // same 5-min TTL
                bridgeEventFetchAttempted.entries.removeIf { it.value < cutoff }
                profileFetchAttempted.entries.removeIf { it.value < cutoff }
                profileFallbackNegCache.entries.removeIf { it.value < cutoff }
                sourceProfileAttempted.entries.removeIf { it.value < cutoff }
            }
        }
        // Periodic pool state logging + connection sweep — every 60s.
        scope.launch {
            while (true) {
                delay(60_000)
                if (socketTransportSuspended.get()) continue
                logPoolState()
                // Sweep unused connections (existing per-url release pass)
                for (url in connections.keys.toList()) {
                    releaseIfUnused(url)
                }
                // Hard cap: if pool > POOL_SWEEP_CAP, force-close oldest evictable.
                if (connections.size > POOL_SWEEP_CAP) {
                    val activeSubUrls = runCatching { activeSubsSource.get().activeRelayUrls() }
                        .getOrDefault(emptySet())
                    val candidates = connections.keys
                        .filter { url ->
                            !hasPurpose(url, ConnectionPurpose.PERSISTENT) &&
                            !hasPurpose(url, ConnectionPurpose.FEED_SUB) &&
                            url !in activeSubUrls
                        }
                        .sortedBy { connectionLastActivity[it] ?: 0L }

                    val toCloseTarget = connections.size - POOL_SWEEP_CAP
                    val toCloseActual = candidates.size.coerceAtMost(toCloseTarget)
                    for (url in candidates.take(toCloseActual)) {
                        // Map-before-close: remove first so listenForEvents.finally sees identity mismatch
                        val conn = removePooledConnection(url, clearPurposes = true) ?: continue
                        conn.close()
                        Log.w(TAG, "Pool over cap, force-closed: $url")
                    }
                    if (toCloseActual < toCloseTarget) {
                        Log.w(TAG, "Pool sweep couldn't reach cap: ${connections.size} > $POOL_SWEEP_CAP " +
                            "(closed $toCloseActual of $toCloseTarget; all remaining are exempt)")
                    }
                }
                // Drain deferred reconnects. Spread with jitter to avoid spiking the resolver.
                if (pendingReconnect.isNotEmpty()) {
                    if (!relayCapabilitiesStore.isNetworkDown) {
                        // Recovered — drain everything.
                        // DNS-dead state is cleared by the networkChanged collector (H18.4b);
                        // doing it here too caused a duplicate DataStore persist.
                        val deferred = pendingReconnect.toList()
                        pendingReconnect.clear()
                        Log.w(TAG, "Network recovered — draining ${deferred.size} deferred reconnects with jitter")
                        for (url in deferred) {
                            scope.launch {
                                delay(kotlin.random.Random.nextLong(RECONNECT_JITTER_WINDOW_MS))
                                reconnectWithBackoff(url)
                            }
                        }
                    } else {
                        // Still degraded/down — the gate defers every reconnectWithBackoff,
                        // so nothing produces the successful connect that clears the latch.
                        // Drain 1-2 entries as DIRECT probes (connectAndAwait bypasses the
                        // defer). Prefer relays whose last failure was DNS — their success is
                        // the strongest proof the resolver recovered, and onOpen →
                        // clearTransportStrikes → healDnsDegraded clears the latch at once. (H20a)
                        val probes = pendingReconnect.toList()
                            .sortedByDescending { relayCapabilitiesStore.get(it)?.lastReason == SkipReason.DNS_RESOLUTION.name }
                            .take(2)
                        Log.w(TAG, "DNS-degraded — probing ${probes.size} deferred relay(s) to test recovery (${pendingReconnect.size} pending)")
                        for (url in probes) {
                            pendingReconnect.remove(url)
                            scope.launch {
                                delay(kotlin.random.Random.nextLong(RECONNECT_JITTER_WINDOW_MS))
                                val ready = connectAndAwait(listOf(url), timeoutMs = 3_000)
                                if (ready > 0) {
                                    Log.w(TAG, "DNS-degraded probe connected: $url — latch cleared via heal path")
                                } else {
                                    pendingReconnect.add(url)  // still unreachable — requeue for next sweep
                                }
                            }
                        }
                    }
                }

                // Heal integral relays: re-attempt any configured integral relay that
                // is disconnected and past its skip cooldown. A transient DNS blip
                // strikes indexers/read/write/search past the threshold; without this
                // they stay skipped until the 24h TTL on next cold start.
                for (url in integralRelayUrls) {
                    if (connections.containsKey(url)) continue
                    if (url in blockedUrls) continue
                    if (relayCapabilitiesStore.shouldSkip(url)) continue   // still in cooldown
                    scope.launch {
                        val ready = connectAndAwait(listOf(url), timeoutMs = 3_000)
                        if (ready > 0 && connections[url] != null) {
                            addPurpose(url, ConnectionPurpose.PERSISTENT)
                            _onRelayReconnected.tryEmit(url)
                            Log.w(TAG, "Healed integral relay: $url")
                        }
                    }
                }
            }
        }
    }

    private val _connectionStates = MutableStateFlow<Map<String, RelayState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, RelayState>> get() = _connectionStates.asStateFlow()

    /** Emits (token, eventId) pairs for events arriving on search-notes-* subscriptions. */
    private val _searchResults = MutableSharedFlow<SearchResult>(extraBufferCapacity = 256)
    val searchResults: SharedFlow<SearchResult> = _searchResults.asSharedFlow()

    /**
     * Extract the subscription ID from an EVENT message without JSON parsing.
     * Format: ["EVENT","subscription-id",{...}]
     */
    private fun extractEventSubId(raw: String): String? {
        val eventEnd = raw.indexOf("\"EVENT\"")
        if (eventEnd < 0) return null
        val quoteOpen = raw.indexOf('"', eventEnd + 7)
        if (quoteOpen < 0) return null
        val subStart = quoteOpen + 1
        val quoteClose = raw.indexOf('"', subStart)
        if (quoteClose < 0) return null
        return raw.substring(subStart, quoteClose)
    }

    /**
     * Extract created_at from a raw EVENT message without full JSON parsing.
     * Scans for `"created_at":` and reads the numeric value.
     */
    private fun extractCreatedAt(raw: String): Long? {
        val key = "\"created_at\":"
        val idx = raw.indexOf(key)
        if (idx < 0) return null
        val start = idx + key.length
        val end = raw.indexOfAny(charArrayOf(',', '}', ' '), start)
        if (end < 0) return null
        return raw.substring(start, end).trim().toLongOrNull()
    }

    /**
     * Extract the Nostr event ID from a raw EVENT message without JSON parsing.
     * Scans for the `"id":"` marker and grabs the next 64 hex chars.
     */
    /** True when a relay has been marked auth-unavailable this session. */
    override fun isAuthUnavailable(url: String): Boolean =
        normalizeRelayUrl(url)?.let { it in authUnavailableRelays } ?: false

    private fun updateConnectionStates() {
        _connectionStates.value = connections.mapValues { it.value.state.value }
    }

    /** Clear transient caches. Called on logout. */
    fun clearCaches() {
        profileFetchAttempted.clear()
        hintedProfileFetchAttempted.clear()
        profileFallbackNegCache.clear()
        bridgeEventFetchAttempted.clear()
    }

    /**
     * Connect to [relayUrls], start listening for events, and suspend until at least
     * one connection is ready OR [timeoutMs] elapses. Does NOT send any subscriptions —
     * the caller sends requests after this returns.
     */
    override suspend fun connectAndAwait(
        relayUrls: List<String>,
        timeoutMs: Long,
        forceEvict: Boolean,
        requestClass: RelayRequestClass,
    ): Int {
        if (socketTransportSuspended.get()) return 0
        val newConns = mutableListOf<RelayConnection>()
        for (rawUrl in relayUrls) {
            val url = normalizeRelayUrl(rawUrl) ?: continue
            if (url in blockedUrls) {
                Log.d(TAG, "Blocked relay — skipping $url")
                continue
            }
            if (relayCapabilitiesStore.shouldSkipRequest(url, requestClass)) {
                val caps = relayCapabilitiesStore.get(url)
                Log.w(TAG, "connectAndAwait GATE-SKIP: $url auth=${caps?.authRequired} restricted=${caps?.restricted} strikes=${caps?.strikes} reason='${caps?.lastReason}'")
                continue
            }
            val claim = acquirePooledConnection(url, forceEvict = forceEvict)
            if (claim == null) {
                Log.w(TAG, "connectAndAwait GATE-CAP: $url blocked by pool cap (${connections.size}/$POOL_SAFETY_CAP)")
                continue
            }
            if (!claim.installed) {
                Log.d(TAG, "connectAndAwait REUSE: $url state=${claim.connection.state.value}")
                continue
            }
            scope.launch { listenForEvents(claim.connection) }
            newConns.add(claim.connection)
            val action = if (claim.replaced == null) "NEW" else "REPLACE"
            Log.d(TAG, "connectAndAwait $action: $url (pool=${connections.size})")
        }
        if (newConns.isEmpty()) {
            // All URLs already in pool — wait for at least one to be connected.
            // connect() (non-suspending) creates connection entries that may still
            // be handshaking. Return early only if at least one is ready; otherwise
            // poll just like we do for newly-created connections.
            val existingConns = relayUrls.mapNotNull { normalizeRelayUrl(it) }
                .filter { it !in blockedUrls }
                .mapNotNull { connections[it] }
            if (existingConns.isEmpty()) return 0
            val alreadyReady = existingConns.count { it.isConnected }
            if (alreadyReady > 0) return alreadyReady
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val ready = existingConns.count { it.isConnected }
                if (ready > 0) {
                    Log.d(TAG, "connectAndAwait: $ready/${existingConns.size} relay(s) ready (existing)")
                    return ready
                }
                delay(50)
            }
            val ready = existingConns.count { it.isConnected }
            val timedOut = existingConns.filterNot { it.isConnected }.map { it.url }
            Log.w(
                TAG,
                "connectAndAwait: timeout — $ready/${existingConns.size} relay(s) ready " +
                    "(existing); timedOut=$timedOut",
            )
            return ready
        }
        // Poll until at least one connection is ready
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready = newConns.count { it.isConnected }
            if (ready > 0) {
                Log.d(TAG, "connectAndAwait: $ready/${newConns.size} relay(s) ready")
                return ready
            }
            delay(50)
        }
        val ready = newConns.count { it.isConnected }
        val timedOut = newConns.filterNot { it.isConnected }.map { it.url }
        Log.w(
            TAG,
            "connectAndAwait: timeout — $ready/${newConns.size} relay(s) ready; timedOut=$timedOut",
        )
        return ready
    }

    // ── Ephemeral one-shot batch ────────────────────────────────────────

    /**
     * Send one-shot REQs to specified URLs with warm-pool reuse.
     *
     * For URLs already in [connections]: reuses via [sendOneShotToRelay]. Lookup-only
     * bridge relays are admitted to the same unpurposed warm pool before classification,
     * so concurrent profile/reference/article miss tiers share one handshake; the normal
     * sweep evicts that socket because it has no persistent [ConnectionPurpose].
     * For URLs NOT in pool: opens ephemeral WebSocket (no cap, no reconnect),
     * sends REQs, collects events until EOSE, then closes.
     *
     * Ephemeral connections never enter [connections] and are globally limited by
     * [MAX_EPHEMERAL_CONNECTIONS], independently of the persistent-pool cap.
     * Returns the normalized targets admitted by block/capability policy.
     */
    suspend fun sendOneShotBatch(
        urls: List<String>,
        reqs: List<String>,
        subIds: List<String>,
        timeoutMs: Long = 8_000,
        capabilityBypassRelays: Set<String> = emptySet(),
        includeActiveFeedRelay: Boolean = false,
        requestClass: RelayRequestClass = RelayRequestClass.GENERAL,
    ): Set<String> {
        if (socketTransportSuspended.get()) {
            subIds.forEach { subId ->
                oneShotEoseCallbacks[subId]?.complete(Unit)
            }
            return emptySet()
        }
        val activeFeedRelay = activeSingleRelayFeedUrl?.let(::normalizeRelayUrl)
        val bypassRelays = capabilityBypassRelays.mapNotNull { normalizeRelayUrl(it) }.toSet()
        val logBypass = bypassRelays.isNotEmpty()
        val normalized = oneShotRelayTargets(
            relayUrls = urls,
            activeSingleRelayFeedUrl = activeFeedRelay,
            includeActiveFeedRelay = includeActiveFeedRelay,
        )
            .filter { relayUrl ->
                relayUrl !in blockedUrls &&
                    !relayCapabilitiesStore.shouldSkipRequest(
                        relayUrl,
                        requestClass,
                        bypassCooldown = relayUrl in bypassRelays,
                    )
            }
        if (logBypass) {
            Log.i(TAG, "sendOneShotBatch: capability bypass relays=${bypassRelays.joinToString(",")}")
        }
        if (normalized.isEmpty() || reqs.isEmpty()) {
            subIds.forEach { subId -> oneShotEoseCallbacks[subId]?.complete(Unit) }
            if (!includeActiveFeedRelay &&
                activeFeedRelay != null &&
                urls.any { normalizeRelayUrl(it) == activeFeedRelay }
            ) {
                Log.d(TAG, "one-shot skipped: only feedRelay in target set")
            }
            return emptySet()
        }

        // The bridge is a common final tier across profiles, event references and articles.
        // Keeping it ephemeral per call produced a handshake storm during one scroll pass.
        // An unpurposed pooled connection is still lookup-only, but lets the existing
        // one-shot demultiplexer reuse it until the regular sweep removes it.
        for (url in normalized) {
            if (isBridgeFallbackRelay(url)) {
                getOrCreateConnection(url, requestClass)
            }
        }

        val reused = mutableListOf<String>()
        val ephemeral = mutableListOf<String>()

        for (url in normalized) {
            if (connections.containsKey(url)) {
                reused.add(url)
            } else {
                ephemeral.add(url)
            }
        }

        if (logBypass) {
            Log.i(TAG, "sendOneShotBatch: ${normalized.size} urls → ${reused.size} reused, ${ephemeral.size} ephemeral")
        } else {
            Log.d(TAG, "sendOneShotBatch: ${normalized.size} urls → ${reused.size} reused, ${ephemeral.size} ephemeral")
        }

        // Pre-filter reused URLs to those present in the pool — must match the
        // send loop's `connections[url] ?: continue` gate so every relay that
        // receives REQs is counted in targetSet.
        val liveReused = reused.filter { connections[it] != null }

        // Resolve target set BEFORE sending: live reused + ephemeral
        val targetSet = (liveReused + ephemeral).toSet()

        // Register target set for EOSE coverage tracking. Requires the caller
        // to have registered oneShotEoseCallbacks[subId] beforehand — both
        // engagement callers (dispatchOwnEngagement, dispatchEngagement) do.
        // Skipping subIds without a callback avoids leaking map entries for
        // internal callers (fetchProfiles, fetchOlderPosts, etc.).
        if (targetSet.isNotEmpty()) {
            for (subId in subIds) {
                if (oneShotEoseCallbacks.containsKey(subId)) {
                    oneShotSubTargets[subId] = targetSet
                }
            }
        }

        // Pool-reused path: wait for mid-handshake connections, then send via existing infra
        for (url in reused) {
            val conn = connections[url] ?: continue
            if (!conn.isConnected) {
                // Mid-handshake — wait up to 1s for ready, skip if it fails
                val state = withTimeoutOrNull(1_000) {
                    conn.state.first {
                        it == RelayState.CONNECTED || it == RelayState.FAILED || it == RelayState.DISCONNECTED
                    }
                }
                if (state != RelayState.CONNECTED) {
                    subIds.forEach { recordOneShotRelayCoverage(it, url) }
                    continue
                }
            }
            subIds.forEach { _activeOneShotSubs.add(it) }
            reqs.forEach { req ->
                sendOneShotToRelay(
                    conn,
                    req,
                    requestClass,
                    bypassCooldown = url in bypassRelays,
                )
            }
        }

        // Ephemeral path: open temporary connections (parallel, bounded by timeout)
        if (ephemeral.isEmpty()) return targetSet

        coroutineScope {
            ephemeral.map { url ->
                async {
                    openEphemeral(
                        url,
                        reqs,
                        subIds.toSet(),
                        timeoutMs,
                        logInfo = logBypass,
                        requestClass = requestClass,
                        bypassCooldown = url in bypassRelays,
                    )
                }
            }.awaitAll()
        }
        return targetSet
    }

    /**
     * Open an ephemeral WebSocket, send REQs, collect events until all sub-IDs
     * receive EOSE or timeout, then close. Never touches [connections],
     * [connectionPurposes], or reconnect logic.
     */
    private suspend fun openEphemeral(
        url: String,
        reqs: List<String>,
        subIds: Set<String>,
        timeoutMs: Long,
        logInfo: Boolean = false,
        requestClass: RelayRequestClass = RelayRequestClass.GENERAL,
        bypassCooldown: Boolean = false,
    ) {
        if (relayCapabilitiesStore.shouldSkipRequest(url, requestClass, bypassCooldown)) {
            subIds.forEach { recordOneShotRelayCoverage(it, url) }
            return
        }
        // Rate limit: min 50ms gap per URL
        val lastOpen = ephemeralLastOpenNanos.computeIfAbsent(url) { AtomicLong(0) }
        val now = System.nanoTime()
        val prev = lastOpen.get()
        if (now - prev < MIN_EPHEMERAL_GAP_NS) {
            Log.d(TAG, "Ephemeral rate-limited: $url")
            subIds.forEach { recordOneShotRelayCoverage(it, url) }
            return
        }
        if (!lastOpen.compareAndSet(prev, now)) {
            subIds.forEach { recordOneShotRelayCoverage(it, url) }
            return // CAS race — another caller won
        }

        ephemeralSemaphore.acquire()
        try {
            if (socketTransportSuspended.get()) {
                subIds.forEach { recordOneShotRelayCoverage(it, url) }
                return
            }
            val conn = relayConnectionFactory.create(url)
            activeEphemeralConnections.add(conn)
            try {
                if (!connectIfTransportActive(conn)) return
                // Wait for WebSocket ready (max 2s)
                val state = withTimeoutOrNull(2_000) {
                    conn.state.first {
                        it == RelayState.CONNECTED || it == RelayState.FAILED || it == RelayState.DISCONNECTED
                    }
                }
                if (state != RelayState.CONNECTED) {
                    if (logInfo) Log.i(TAG, "Ephemeral connect failed: $url (state=$state)")
                    else Log.d(TAG, "Ephemeral connect failed: $url (state=$state)")
                    return
                }

                // Send all REQs
                reqs.forEach { conn.send(it) }

                // Collect events until all sub-IDs EOSE'd or timeout
                val pendingSubs = subIds.toMutableSet()
                withTimeoutOrNull(timeoutMs) {
                    conn.messages.consumeEach { raw ->
                        when {
                            raw.startsWith("[\"EVENT\"") -> {
                                processor.process(raw, url)
                            }
                            raw.startsWith("[\"EOSE\"") -> {
                                // Keep ephemeral and pooled relay delivery equivalent:
                                // registered taps need the real EOSE to distinguish a
                                // completed query from a failed/closed socket.
                                processor.process(raw, url)
                                val eoseSubId = extractEoseSubId(raw)
                                if (eoseSubId != null && eoseSubId in pendingSubs) {
                                    relayCapabilitiesStore.recordRequestSuccess(url)
                                    conn.send("""["CLOSE","$eoseSubId"]""")
                                    recordOneShotRelayCoverage(eoseSubId, url)
                                    pendingSubs.remove(eoseSubId)
                                    if (pendingSubs.isEmpty()) return@withTimeoutOrNull
                                }
                            }
                            raw.startsWith("[\"CLOSED\"") -> {
                                processor.process(raw, url)
                                val closed = runCatching {
                                    val arr = NostrJson.parseToJsonElement(raw).jsonArray
                                    val subId = arr.getOrNull(1)?.jsonPrimitive?.content
                                    val reason = arr.getOrNull(2)?.jsonPrimitive?.content.orEmpty()
                                    subId to reason
                                }.getOrNull()
                                val closedSubId = closed?.first
                                val reason = closed?.second.orEmpty()
                                if (reason.isNotEmpty()) {
                                    relayCapabilitiesStore.learnFromClosed(url, reason)
                                }
                                if (closedSubId != null && closedSubId in pendingSubs) {
                                    recordOneShotRelayCoverage(closedSubId, url)
                                    pendingSubs.remove(closedSubId)
                                    if (pendingSubs.isEmpty()) return@withTimeoutOrNull
                                }
                            }
                            raw.startsWith("[\"AUTH\"") -> {
                                val challenge = raw.substringAfter("[\"AUTH\",\"", "")
                                    .substringBefore("\"")
                                if (challenge.isNotEmpty()) {
                                    handleAuthChallenge(conn, challenge)
                                }
                            }
                            raw.startsWith("[\"OK\"") -> {
                                handleOk(conn, raw)
                            }
                        }
                    }
                }
                val eosed = subIds.size - pendingSubs.size
                if (logInfo) Log.i(TAG, "Ephemeral complete: $url ($eosed/${subIds.size} subs EOSE'd)")
                else Log.d(TAG, "Ephemeral complete: $url ($eosed/${subIds.size} subs EOSE'd)")
            } finally {
                // A failed connection or a relay that omits EOSE is still complete from
                // the caller's perspective. Coverage is idempotent for real EOSEs.
                subIds.forEach { recordOneShotRelayCoverage(it, url) }
                activeEphemeralConnections.remove(conn)
                conn.close()
            }
        } finally {
            ephemeralSemaphore.release()
        }
    }

    /**
     * Pre-load blocked relay URLs into the in-memory snapshot.
     * Must be called during bootstrap BEFORE any connect() calls.
     */
    fun refreshBlockedRelays() {
        val ownPubkey = keyManager.getPublicKeyHex() ?: return
        blockedUrls = memoryEventStore.get().getBlockedRelayUrls(ownPubkey).toSet()
        Log.d(TAG, "Blocked relay snapshot loaded: ${blockedUrls.size} URL(s)")
    }

    /**
     * Update the blocked relay snapshot and disconnect any currently-connected
     * blocked relays with a proper WebSocket close handshake.
     */
    fun onBlockedRelaysChanged(newBlockedUrls: Set<String>) {
        blockedUrls = newBlockedUrls
        for (url in newBlockedUrls) {
            removePooledConnection(url, clearPurposes = true)?.let { conn ->
                conn.close()
                Log.d(TAG, "Disconnected newly-blocked relay: $url")
            }
        }
    }

    fun connect(relayUrls: List<String>) {
        if (socketTransportSuspended.get()) return
        val normalizedUrls = relayUrls.mapNotNull { normalizeRelayUrl(it) }
        for (url in normalizedUrls) {
            if (url in blockedUrls) {
                Log.d(TAG, "Blocked relay — skipping $url")
                continue
            }
            if (relayCapabilitiesStore.shouldSkip(url)) {
                val caps = relayCapabilitiesStore.get(url)
                Log.w(TAG, "connect GATE-SKIP: $url auth=${caps?.authRequired} restricted=${caps?.restricted} strikes=${caps?.strikes} reason='${caps?.lastReason}'")
                continue
            }
            val claim = acquirePooledConnection(url)
            if (claim == null) {
                Log.w(TAG, "connect GATE-CAP: $url blocked by pool cap (${connections.size}/$POOL_SAFETY_CAP)")
                continue
            }
            if (!claim.installed) {
                Log.d(TAG, "connect REUSE: $url state=${claim.connection.state.value}")
                continue
            }
            scope.launch { listenForEvents(claim.connection) }
            val action = if (claim.replaced == null) "NEW" else "REPLACE"
            Log.d(TAG, "connect $action: $url (pool=${connections.size})")
        }
        Log.d(TAG, "Pool has ${connections.size} connections")
    }

    private suspend fun listenForEvents(conn: RelayConnection) {
        try { try {
            conn.messages.consumeEach { raw ->
                connectionLastActivity[conn.url] = System.currentTimeMillis()
                // Fire taps for ALL message types (EVENT/EOSE/CLOSED).
                // Subscription taps demux by subId and need EOSE/CLOSED delivery.
                // For non-EVENT, EventProcessor fires taps then returns early.
                // For EVENT, it also handles dedup + MES routing.
                processor.process(raw, conn.url)
                if (raw.startsWith("[\"EOSE\"")) {
                    val eoseSubId = extractEoseSubId(raw)
                    if (eoseSubId != null && eoseSubId.startsWith("search-")) {
                        Log.d(TAG, "Search EOSE: subId=$eoseSubId relay=${conn.url}")
                    }
                    handleEose(conn, raw)
                    return@consumeEach
                }
                // NIP-45 COUNT response: ["COUNT","sub-id",{"count":N}]
                if (raw.startsWith("[\"COUNT\"")) {
                    handleCount(raw)
                    return@consumeEach
                }
                // Relay NOTICE — log for diagnostics
                if (raw.startsWith("[\"NOTICE\"")) {
                    val notice = raw.substringAfter("\"NOTICE\",\"", "").substringBefore("\"")
                    Log.w(TAG, "Relay NOTICE ${conn.url}: $notice")
                    return@consumeEach
                }
                // NIP-42 OK response — ["OK", "<event-id>", <success>, "<message>"]
                if (raw.startsWith("[\"OK\"")) {
                    handleOk(conn, raw)
                    return@consumeEach
                }
                // NIP-42 AUTH challenge — sign and respond automatically
                if (raw.startsWith("[\"AUTH\"")) {
                    val challenge = raw.substringAfter("[\"AUTH\",\"", "").substringBefore("\"")
                    if (challenge.isNotEmpty()) {
                        Log.w(TAG, "AUTH challenge from ${conn.url}: ${challenge.take(20)}…")
                        handleAuthChallenge(conn, challenge)
                    }
                    return@consumeEach
                }
                // NIP-42 CLOSED with auth-required — authenticate then replay the sub
                if (raw.startsWith("[\"CLOSED\"")) {
                    try {
                        val arr = NostrJson.parseToJsonElement(raw).jsonArray
                        val closedSubId = arr[1].jsonPrimitive.content
                        val reason = arr.getOrNull(2)?.jsonPrimitive?.content ?: ""
                        val category = when {
                            isOneShotSubscription(closedSubId) -> "one-shot"
                            closedSubId.startsWith("browse-") -> "browse"
                            else -> "live"
                        }
                        if (reason.startsWith("auth-required")) {
                            Log.w(TAG, "CLOSED auth-required $category sub '$closedSubId' on ${conn.url}: $reason " +
                                "(hasPendingChallenge=${pendingChallenges.containsKey(conn.url)} " +
                                "authenticated=${conn.url in authenticatedRelays} " +
                                "authInFlight=${conn.url in authInFlight} " +
                                "streak=${authRejectionStreak[conn.url] ?: 0} " +
                                "unavailable=${conn.url in authUnavailableRelays})")
                            when {
                                conn.url in authUnavailableRelays -> {
                                    Log.d(TAG, "auth-required on unavailable relay ${conn.url} for '$closedSubId' — ignoring")
                                }
                                conn.url in authInFlight -> {
                                    // CLOSED because our REQ raced the in-flight auth handshake — NOT a rejection.
                                    // completeAuth() will replay this sub on OK; don't pollute the streak.
                                    Log.d(TAG, "auth-required on ${conn.url} while auth in flight — awaiting OK, not penalizing")
                                }
                                else -> {
                                    val streak = authRejectionStreak.merge(conn.url, 1, Int::plus) ?: 1
                                    authenticatedRelays.remove(conn.url)
                                    optimisticAuthUsed.remove(conn.url)
                                    if (streak >= MAX_AUTH_REJECTIONS) {
                                        authUnavailableRelays.add(conn.url)
                                        authInFlight.remove(conn.url)
                                        pendingAuthEventIds.values.removeAll { it == conn.url }
                                        _relayAuthUnavailable.tryEmit(conn.url)
                                        Log.w(TAG, "AUTH: ${conn.url} rejected $streak times — marking unavailable")
                                    } else {
                                        val challenge = pendingChallenges[conn.url]
                                        if (challenge != null) {
                                            Log.w(TAG, "AUTH: rejected on ${conn.url} (streak=$streak) — re-authenticating")
                                            handleAuthChallenge(conn, challenge)
                                        } else {
                                            Log.w(TAG, "AUTH: rejected on ${conn.url} (streak=$streak) but no challenge cached")
                                        }
                                    }
                                }
                            }
                        } else if (isRateLimitedClosedReason(reason)) {
                            markRelayRateLimited(conn.url)
                            Log.w(TAG, "CLOSED rate-limited $category sub '$closedSubId' on ${conn.url}: $reason")
                        } else {
                            Log.w(TAG, "CLOSED $category sub '$closedSubId' on ${conn.url}: reason='$reason'")
                        }
                        // Learn synchronously in memory before any resubscribe signal.
                        // Persistence is conflated off-thread by RelayCapabilitiesStore.
                        if (reason.isNotEmpty()) {
                            relayCapabilitiesStore.learnFromClosed(conn.url, reason)
                        }
                        // Mechanism S: relay closed sub without dropping WS. For live subs
                        // (still active in Subscription), emit reconnect signal so
                        // Subscription.resumeRelay re-issues the REQ. Skip one-shot and
                        // auth-required (already handled above).
                        val isOneShot = isOneShotSubscription(closedSubId)
                        if (shouldResubAfterClosed(reason, isOneShot)) {
                            val activeIds = runCatching { activeSubsSource.get().activeSubIds() }
                                .getOrDefault(emptySet())
                            if (closedSubId in activeIds) {
                                _onRelayReconnected.tryEmit(conn.url)
                                Log.w(TAG, "RESUB: live sub '$closedSubId' closed on ${conn.url}, notified resub")
                            }
                        }
                        // A CLOSED relay won't send EOSE — release its slot and
                        // count it as done for coverage so it doesn't force a full timeout.
                        if (isOneShot) {
                            releaseOneShotForRelay(closedSubId, conn.url, conn)
                            recordOneShotRelayCoverage(closedSubId, conn.url)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse CLOSED message: ${e.message}")
                    }
                    return@consumeEach
                }
                val subId = extractEventSubId(raw)
                if (subId != null) {
                    // Emit (token, eventId) for search-notes subscriptions so SearchViewModel
                    // can correlate relay results with the correct query session.
                    if (subId.startsWith("search-notes-")) {
                        val token = subId.removePrefix("search-notes-").toLongOrNull()
                        if (token != null) {
                            val eventId = extractEventIdFromRaw(raw)
                            if (eventId != null) {
                                Log.d(TAG, "Search EVENT received: subId=$subId relay=${conn.url} eventId=${eventId.take(12)}…")
                                _searchResults.tryEmit(SearchResult(token, eventId))
                            }
                        }
                    }
                    // Also log search-profiles events
                    if (subId.startsWith("search-profiles-")) {
                        Log.d(TAG, "Search EVENT received: subId=$subId relay=${conn.url}")
                    }
                    // Paginated fetch tracking: count events and track oldest created_at
                    _activePages[subId]?.let { page ->
                        page.eventCount.incrementAndGet()
                        val ts = extractCreatedAt(raw)
                        if (ts != null) {
                            page.oldestCreatedAt.updateAndGet { minOf(it, ts) }
                        }
                    }
                }
                // NOTE: processor.process already called at top of consumeEach
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Stream closed for ${conn.url}: ${e.message}")
        }
        } finally {
            // Skip the reconnect decision if we're being cancelled (scope teardown).
            // A `return` here would swallow the propagating CancellationException, so
            // gate with `if (isActive)` and use NO `return` statements in this block.
            if (currentCoroutineContext().isActive && !socketTransportSuspended.get()) {
                val url = conn.url
                val current = connections[url]
                if (current !== conn) {
                    // Map already points elsewhere (replaced by reconnect/sweep) — don't race.
                    Log.w(TAG, "listenForEvents exit: $url replaced, no reconnect")
                } else {
                    val purposes = connectionPurposes[url]
                    val activeSubUrls = runCatching { activeSubsSource.get().activeRelayUrls() }
                        .getOrDefault(emptySet())
                    val stillNeeded = !purposes.isNullOrEmpty() || url in activeSubUrls
                    val notSkipped = !relayCapabilitiesStore.shouldSkip(url)

                    Log.w(TAG, "listenForEvents exit: $url state=${conn.state.value} " +
                        "purposes=$purposes inActiveSubs=${url in activeSubUrls} " +
                        "shouldSkip=${!notSkipped} → reconnect=${stillNeeded && notSkipped}")

                    if (stillNeeded && notSkipped) {
                        reconnectWithBackoff(url)
                    } else {
                        // No purpose, not in active subs — clean up the dead entry
                        removePooledConnection(url, conn, clearPurposes = true)
                    }
                }
            }
        }
    }

    /**
     * Idempotent slot release for a single (subId, url) pair.
     *
     * Both [handleEose] and [cleanupOneShotSub] funnel through here. The
     * [oneShotReleased] guard ensures that even if both paths fire for the
     * same relay URL, the slot is decremented exactly once.
     *
     * Sends CLOSE frame, decrements [relayOneShotCount], and flushes the
     * per-relay queue so queued REQs can drain.
     */
    private fun releaseOneShotForRelay(
        subId: String,
        url: String,
        sourceConnection: RelayConnection? = null,
    ) {
        synchronized(socketLifecycleLock) {
            val ownerKey = RelayOneShotOwnerKey(subId, url)
            val currentOwner = relayOneShotOwners[ownerKey]
            // A late EOSE from a replaced socket must not claim ownership of a
            // same-id request that belongs to the new socket.
            if (sourceConnection != null && currentOwner != null && currentOwner !== sourceConnection) {
                return@synchronized
            }
            val released = oneShotReleased.computeIfAbsent(subId) { ConcurrentHashMap.newKeySet() }
            if (!released.add(url)) return@synchronized

            val owner = takeRelayOneShotOwner(
                subId = subId,
                url = url,
                sourceOwner = sourceConnection,
                owners = relayOneShotOwners,
            ) ?: return@synchronized
            val current = connections[url]
            if (current !== owner) return@synchronized

            current.send("""["CLOSE","$subId"]""")
            relayOneShotCount[url]?.let { count ->
                val prev = count.getAndUpdate { if (it > 0) it - 1 else 0 }
                if (prev > 0) flushRelayQueue(current)
            }
        }
    }

    /**
     * Fix 3: Subscription lifecycle — CLOSE after EOSE for one-shot subscriptions.
     *
     * One-shot subs (profiles, threads, search, notifications, kind 3/10002) are
     * identified by their subscription ID prefix. Once EOSE arrives the relay has
     * finished its historical query; we close immediately to free relay resources
     * and stop the stream of duplicate events from that relay for that query.
     *
     * Persistent subs (feed-, follows-) stay open to receive live events.
     */
    private fun handleEose(conn: RelayConnection, raw: String) {
        val subId = extractEoseSubId(raw) ?: return
        val isTrackedRequest = _activePages.containsKey(subId) || subId in _activeOneShotSubs
        if (isTrackedRequest) relayCapabilitiesStore.recordRequestSuccess(conn.url)
        // Paginated fetch: signal EOSE without sending CLOSE (pagination loop decides)
        _activePages[subId]?.let { page ->
            page.eoseReceived.complete(Unit)
            return
        }
        if (isOneShotSubscription(subId)) {
            _activeOneShotSubs.remove(subId)
            // Single release path — idempotent, sends CLOSE + decrements slot + flushes queue
            releaseOneShotForRelay(subId, conn.url, conn)
            // Record this relay as done; complete deferred when all targets covered
            recordOneShotRelayCoverage(subId, conn.url)
            Log.d(TAG, "CLOSE sent for one-shot sub '$subId' on ${conn.url}")
        }
    }

    /** Remove a one-shot sub from all tracking maps, release slots for every
     *  target relay (idempotent — skips already-released), and clean up. */
    internal fun cleanupOneShotSub(subId: String) {
        _activeOneShotSubs.remove(subId)
        oneShotEoseCallbacks.remove(subId)
        val targets = oneShotSubTargets.remove(subId) ?: emptySet()
        oneShotSubEosed.remove(subId)

        // Release slot for every target relay. releaseOneShotForRelay is idempotent —
        // relays that already EOSE'd (and were released in handleEose) are skipped
        // via the oneShotReleased guard. Only un-released relays get CLOSE + decrement.
        for (url in targets) {
            releaseOneShotForRelay(subId, url)
        }

        // Final cleanup of the released tracking set
        oneShotReleased.remove(subId)
    }

    /**
     * Record a relay as done for a one-shot sub. Completes
     * [oneShotEoseCallbacks] when ALL target relays have responded (EOSE or CLOSED).
     * Falls back to first-EOSE if no target set was registered.
     */
    private fun recordOneShotRelayCoverage(subId: String, relayUrl: String) {
        val targets = oneShotSubTargets[subId]
        if (targets == null) {
            // No target set registered — fall back to old behavior (complete on first)
            oneShotEoseCallbacks.remove(subId)?.complete(Unit)
            return
        }
        val eosed = oneShotSubEosed.computeIfAbsent(subId) { ConcurrentHashMap.newKeySet() }
        eosed.add(relayUrl)
        val covered = eosed.size
        val total = targets.size
        Log.d(TAG, "one-shot '$subId' coverage $covered/$total")

        // Full coverage: complete the main deferred and clean up tracking maps.
        // oneShotReleased is NOT removed here — late duplicate EOSEs from flaky relays
        // would recreate the set and double-decrement. cleanupOneShotSub does the bulk wipe.
        if (covered >= total) {
            oneShotEoseCallbacks.remove(subId)?.complete(Unit)
            oneShotSubTargets.remove(subId)
            oneShotSubEosed.remove(subId)
        }
    }

    /** Handle a NIP-45 COUNT response, retaining its integrity-significant limited flag. */
    private fun handleCount(raw: String) {
        val parsed = parseNip45CountFrame(raw) ?: return
        countCallbacks.remove(parsed.subId)?.complete(parsed.result)
    }

    /**
     * NIP-45 COUNT query: send a COUNT request to a single relay and wait for the response.
     * Returns the count and NIP-45 limited flag, or null if unsupported or timed out.
     */
    internal suspend fun sendCount(
        relayUrl: String,
        filter: JsonObject,
        timeoutMs: Long = 10_000L,
    ): Nip45CountResult? =
        withContext(Dispatchers.IO) {
            if (relayCapabilitiesStore.shouldSkipRequest(relayUrl, RelayRequestClass.GENERAL)) {
                return@withContext null
            }
            val subId = "count-${System.nanoTime()}"
            try {
                val countRequest = buildJsonArray {
                    add(JsonPrimitive("COUNT"))
                    add(JsonPrimitive(subId))
                    add(filter)
                }.toString()

                val conn = connections[relayUrl] ?: return@withContext null

                val deferred = CompletableDeferred<Nip45CountResult?>()
                countCallbacks[subId] = deferred

                conn.send(countRequest)

                withTimeoutOrNull(timeoutMs) { deferred.await() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                countCallbacks.remove(subId)
            }
        }

    /**
     * Refresh a user's replaceable kind-3 from their write relays and
     * independent broad indexes. Every selected relay gets a chance to respond;
     * EventProcessor/MES created-at ordering then chooses the newest event.
     * This deliberately avoids the old first-responder race, where one stale
     * relay won and all newer in-flight responses were immediately closed.
     */
    suspend fun refreshFollowList(
        pubkeyHex: String,
        forceRefresh: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val nowMs = android.os.SystemClock.elapsedRealtime()
            val lastSuccessMs = followRefreshLastSuccessMs[pubkeyHex]
            val lastAttemptMs = followRefreshLastAttemptMs[pubkeyHex]
            if (!shouldRunFollowRefresh(forceRefresh, nowMs, lastSuccessMs, lastAttemptMs)) {
                return@withContext lastSuccessMs != null &&
                    nowMs - lastSuccessMs < FOLLOW_REFRESH_FRESH_MS
            }
            followRefreshLastAttemptMs[pubkeyHex] = nowMs

            val mes = memoryEventStore.get()
            val declaredWriteRelays = mes.writeRelaysFor(pubkeyHex)
            val targets = followRefreshRelayTargets(
                writeRelayUrls = declaredWriteRelays,
                indexRelayUrls = FOLLOWER_INDEX_RELAY_URLS,
                limit = MAX_FOLLOW_REFRESH_RELAYS,
            )
            if (targets.isEmpty()) return@withContext false

            val result = fetchReplaceableMetadata(
                pubkeyHex = pubkeyHex,
                rawRelayUrls = targets,
                kinds = listOf(3),
                subIdPrefix = "kind3-refresh",
            )
            val received = 3 in result.receivedKinds
            var resolved = received && mes.getPublishableFollowsSnapshot(pubkeyHex) != null
            val confirmedNewAccountEmpty = !resolved && canMaterializeEmptyContactList(
                localStateResolved = mes.getFollowsSnapshot(pubkeyHex) != null,
                declaredWriteRelays = declaredWriteRelays,
                result = result,
            )
            if (confirmedNewAccountEmpty) {
                // A zero timestamp is a synthetic baseline, not a published
                // revision. Any real kind-3 arriving later supersedes it.
                mes.updateFollows(pubkeyHex, emptySet(), createdAt = 0L)
                resolved = true
            }
            Log.i(
                TAG,
                "FOLLOW-REFRESH author=${pubkeyHex.take(8)} targets=${targets.size} " +
                    "eose=${result.eoseRelays.size} received=$received " +
                    "confirmedEmpty=$confirmedNewAccountEmpty resolved=$resolved",
            )
            resolved.also { refreshed ->
                if (refreshed) {
                    followRefreshLastSuccessMs[pubkeyHex] = android.os.SystemClock.elapsedRealtime()
                }
            }
        }

    /**
     * Fetch the latest own kind-0 immediately before a destructive profile
     * merge. Unlike ordinary profile hydration this intentionally has no
     * freshness cache: explicit Save must not trust a snapshot that another
     * client may have replaced since this process started.
     *
     * A verified EVENT is freshness evidence for an existing profile. A real
     * EOSE with no EVENT is separately retained as confirmed absence so a new
     * key can publish its first profile; lifecycle completion caused by a
     * timeout or CLOSED frame is not absence evidence.
     */
    internal suspend fun refreshProfileMetadata(pubkeyHex: String): ProfileMetadataRefreshResult =
        withContext(Dispatchers.IO) {
            val writeRelays = memoryEventStore.get().writeRelaysFor(pubkeyHex)
                .ifEmpty { GLOBAL_RELAY_URLS }
            val targets = followRefreshRelayTargets(
                writeRelayUrls = writeRelays,
                indexRelayUrls = FOLLOWER_INDEX_RELAY_URLS,
                limit = MAX_PROFILE_METADATA_REFRESH_RELAYS,
            )
            if (targets.isEmpty()) return@withContext ProfileMetadataRefreshResult.UNAVAILABLE

            // Keep the canonical "profiles-" prefix so RelayPool's one-shot
            // EOSE/CLOSE lifecycle applies on both pooled and ephemeral sockets.
            val subId = "profiles-save-refresh-${System.nanoTime()}"
            val req = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(subId))
                add(buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(0)) })
                    put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                    put("limit", JsonPrimitive(1))
                })
            }.toString()

            val receivedCreatedAtById = ConcurrentHashMap<String, Long>()
            val eoseRelays = ConcurrentHashMap.newKeySet<String>()
            val evidenceTap = RelayMessageTap { message ->
                when (message) {
                    is RelayTapMessage.VerifiedEvent -> {
                        val event = message.event
                        if (message.subscriptionId == subId &&
                            event.kind == 0 && event.pubkey == pubkeyHex
                        ) {
                            receivedCreatedAtById[event.id] = event.createdAt
                        }
                    }
                    is RelayTapMessage.Control -> {
                        if (message.raw.startsWith("[\"EOSE\"") &&
                            extractEoseSubId(message.raw) == subId
                        ) {
                            eoseRelays.add(message.relayUrl)
                        }
                    }
                }
            }
            val eose = CompletableDeferred<Unit>()
            oneShotEoseCallbacks[subId] = eose
            processor.registerTap(evidenceTap)
            val lifecycleComplete = try {
                try {
                    sendOneShotBatch(
                        urls = targets,
                        reqs = listOf(req),
                        subIds = listOf(subId),
                        timeoutMs = PROFILE_METADATA_REFRESH_TIMEOUT_MS,
                        includeActiveFeedRelay = true,
                    )
                    withTimeoutOrNull(PROFILE_METADATA_REFRESH_TIMEOUT_MS) { eose.await() } != null
                } finally {
                    cleanupOneShotSub(subId)
                }
            } finally {
                processor.unregisterTap(evidenceTap)
            }

            val settled = if (receivedCreatedAtById.isEmpty()) {
                false
            } else {
                withTimeoutOrNull(COLD_LANE_FLUSH_MS) {
                    while (true) {
                        val current = memoryEventStore.get().getProfile(pubkeyHex)
                        if (profileMetadataRefreshSettled(
                                currentEventId = current?.id,
                                currentCreatedAt = current?.createdAt,
                                receivedCreatedAtById = receivedCreatedAtById,
                            )
                        ) {
                            return@withTimeoutOrNull true
                        }
                        delay(50L)
                    }
                } == true
            }

            val result = profileMetadataRefreshResult(
                receivedEventCount = receivedCreatedAtById.size,
                realEoseCount = eoseRelays.size,
                settled = settled,
            )
            val logMessage =
                "PROFILE-REFRESH author=${pubkeyHex.take(8)} targets=${targets.size} " +
                    "verified=${receivedCreatedAtById.size} eose=${eoseRelays.size} " +
                    "lifecycle=$lifecycleComplete settled=$settled result=$result"
            if (result != ProfileMetadataRefreshResult.UNAVAILABLE) {
                Log.i(TAG, logMessage)
            } else {
                Log.w(TAG, logMessage)
            }
            result
        }

    /** Best-effort refresh; offline callers still receive the MES-known count. */
    suspend fun fetchFollowingCount(pubkeyHex: String): Long? {
        refreshFollowList(pubkeyHex)
        return memoryEventStore.get().getFollows(pubkeyHex)?.size?.toLong()
    }

    /**
     * Extract the subscription ID from an EOSE message without JSON parsing.
     * Format: ["EOSE","subscription-id"] (compact) or ["EOSE", "subscription-id"] (spaced).
     */
    private fun extractEoseSubId(raw: String): String? {
        // Find the "EOSE" token, then locate the next quoted string — that's the sub-id.
        val eoseEnd = raw.indexOf("\"EOSE\"")
        if (eoseEnd < 0) return null
        // Skip past "EOSE", then find the opening quote of the sub-id
        val quoteOpen = raw.indexOf('"', eoseEnd + 6)
        if (quoteOpen < 0) return null
        val subStart = quoteOpen + 1
        val quoteClose = raw.indexOf('"', subStart)
        if (quoteClose < 0) return null
        return raw.substring(subStart, quoteClose)
    }

    /** Extract the second element from a compact REQ frame for queue cleanup. */
    private fun extractReqSubId(raw: String): String? = runCatching {
        val arr = NostrJson.parseToJsonElement(raw).jsonArray
        if (arr.getOrNull(0)?.jsonPrimitive?.content != "REQ") return@runCatching null
        arr.getOrNull(1)?.jsonPrimitive?.content
    }.getOrNull()

    /**
     * Subscription IDs are prefixed to encode their lifecycle type.
     *
     *  ONE_SHOT  (close after EOSE): account-metadata-, kind3-, kind10002-, profiles-, hint-profiles-,
     *                                src-profiles-, search-, older-, relay-ecosystem-,
     *                                thread-event-, thread-replies-, thread-reactions-,
     *                                thread-zaps-, user-posts-, user-longform-,
     *                                user-engagement-, hint-event-, trust-scores-,
     *                                engagement-
     *  PERSISTENT (keep open):       feed-, follows-, notifs-
     */
    private fun isOneShotSubscription(subId: String): Boolean =
        SubscriptionRules.isOneShotSubscription(subId)

    /**
     * Resolve the three pieces of metadata needed to enter the app in one bounded
     * request. A real EOSE is retained as negative-result evidence; events still flow
     * through EventProcessor into MES exactly like every other relay fetch.
     */
    internal suspend fun fetchAccountMetadata(
        pubkeyHex: String,
        rawRelayUrls: List<String>,
    ): AccountMetadataFetchResult = fetchReplaceableMetadata(
        pubkeyHex = pubkeyHex,
        rawRelayUrls = rawRelayUrls,
        kinds = ACCOUNT_METADATA_KINDS,
        subIdPrefix = "account-metadata",
    )

    private suspend fun fetchReplaceableMetadata(
        pubkeyHex: String,
        rawRelayUrls: List<String>,
        kinds: Collection<Int>,
        subIdPrefix: String,
    ): AccountMetadataFetchResult {
        val relayUrls = rawRelayUrls.mapNotNull(::normalizeRelayUrl).distinct()
        if (relayUrls.isEmpty() || kinds.isEmpty()) return AccountMetadataFetchResult()

        val subId = "$subIdPrefix-${System.nanoTime()}"
        val acceptedKinds = kinds.toSet()
        val req = buildReplaceableMetadataReq(subId, pubkeyHex, acceptedKinds)
        val realEoseRelays = ConcurrentHashMap.newKeySet<String>()
        val receivedKinds = ConcurrentHashMap.newKeySet<Int>()
        val evidenceTap = RelayMessageTap { message ->
            when (message) {
                is RelayTapMessage.VerifiedEvent -> {
                    if (message.subscriptionId == subId &&
                        message.event.pubkey == pubkeyHex &&
                        message.event.kind in acceptedKinds
                    ) {
                        receivedKinds.add(message.event.kind)
                    }
                }
                is RelayTapMessage.Control -> {
                    if (message.raw.startsWith("[\"EOSE\"") &&
                        extractEoseSubId(message.raw) == subId
                    ) {
                        realEoseRelays.add(message.relayUrl)
                    }
                }
            }
        }
        val lifecycleComplete = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = lifecycleComplete
        processor.registerTap(evidenceTap)
        val queriedRelays = try {
            try {
                val admitted = sendOneShotBatch(
                    urls = relayUrls,
                    reqs = listOf(req),
                    subIds = listOf(subId),
                    timeoutMs = ACCOUNT_METADATA_FETCH_TIMEOUT_MS,
                )
                withTimeoutOrNull(ACCOUNT_METADATA_FETCH_TIMEOUT_MS) {
                    lifecycleComplete.await()
                }
                admitted
            } finally {
                cleanupOneShotSub(subId)
            }
        } finally {
            processor.unregisterTap(evidenceTap)
        }
        delay(EVENT_PROCESSOR_SETTLE_MS)
        return AccountMetadataFetchResult(
            queriedRelays = queriedRelays,
            eoseRelays = realEoseRelays.toSet(),
            receivedKinds = receivedKinds.toSet(),
        ).also { result ->
            Log.d(
                TAG,
                "Replaceable metadata: realEose=${result.eoseRelays.size}/" +
                    "${result.queriedRelays.size} kinds=${result.receivedKinds}",
            )
        }
    }

    /**
     * Send a one-time REQ for the user's kind 3 (follow list) to indexer relays.
     * The response flows through EventProcessor → MemoryEventStore direct-path insert.
     */
    fun fetchFollowList(pubkeyHex: String) {
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("kind3-${System.nanoTime()}"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(3)) })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                put("limit", JsonPrimitive(1))
            })
        }.toString()
        // Kind 3 is replaceable — indexers suffice; broadcasting to every
        // connection just duplicates the same latest event.
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
        val targets = indexerUrls.mapNotNull { connections[it] }
            .ifEmpty { connections.values.take(3).toList() }
        targets.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "Fetching kind 3 for $pubkeyHex from ${targets.size} relay(s)")
    }

    /**
     * Send a one-time REQ for kind 10002 (relay list metadata) for [pubkeys].
     * Results flow through EventProcessor → MemoryEventStore direct-path insert.
     */
    fun fetchRelayLists(pubkeys: List<String>) {
        if (pubkeys.isEmpty()) return
        // Send to indexer relays only (not all connections) to avoid broadcast storm
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
        val indexerConns = indexerUrls.mapNotNull { connections[it] }
        val targets = indexerConns.ifEmpty { connections.values.take(3) }
        // Chunk to keep individual filters under 500 authors
        pubkeys.chunked(500).forEach { chunk ->
            val subId = "kind10002-${System.nanoTime()}"
            _activeOneShotSubs.add(subId)
            val req = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(subId))
                add(buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(10002)) })
                    put("authors", buildJsonArray { chunk.forEach { add(JsonPrimitive(it)) } })
                })
            }.toString()
            targets.forEach { sendOneShotToRelay(it, req) }
        }
        Log.d(TAG, "Fetching kind 10002 for ${pubkeys.size} pubkey(s) from ${targets.size} relay(s)")
    }

    /** Fetch one profile's published relay facts without opening a live subscription. */
    suspend fun fetchProfileRelayFacts(pubkey: String): Boolean {
        val indexers = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
            .mapNotNull(::normalizeRelayUrl)
            .distinct()
            .take(PROFILE_RELAY_FACTS_INDEXER_LIMIT)
        // NIP-51 lists are frequently published only to the author's outbox. Keep this
        // fallback narrow; indexers remain the primary lookup path requested by the UI.
        val mes = memoryEventStore.get()
        val authorWriteRelays = mes.lookupWriteRelaysFor(pubkey)
            .mapNotNull(::normalizeRelayUrl)
            .filter { it !in indexers }
            .distinct()
            .take(PROFILE_RELAY_FACTS_AUTHOR_RELAY_LIMIT)
        val targets = indexers + authorWriteRelays

        suspend fun fetchPhase(
            urls: List<String>,
            subIdPrefix: String,
            includeActiveFeedRelay: Boolean = false,
        ): Boolean {
            if (urls.isEmpty()) return false
            val subId = "$subIdPrefix-${System.nanoTime()}"
            val request = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(subId))
                add(buildJsonObject {
                    put("kinds", buildJsonArray {
                        add(JsonPrimitive(10002))
                        add(JsonPrimitive(10006))
                        add(JsonPrimitive(10007))
                    })
                    put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                    put("limit", JsonPrimitive(3))
                })
            }.toString()
            val eose = CompletableDeferred<Unit>()
            oneShotEoseCallbacks[subId] = eose
            return try {
                sendOneShotBatch(
                    urls = urls,
                    reqs = listOf(request),
                    subIds = listOf(subId),
                    timeoutMs = PROFILE_RELAY_FACTS_TIMEOUT_MS,
                    includeActiveFeedRelay = includeActiveFeedRelay,
                )
                withTimeoutOrNull(PROFILE_RELAY_FACTS_TIMEOUT_MS) { eose.await() } != null
            } finally {
                cleanupOneShotSub(subId)
            }
        }

        val standardCovered = fetchPhase(targets, "profile-relays")
        if (targets.isNotEmpty()) delay(EVENT_PROCESSOR_SETTLE_MS)
        if (mes.getRelayList(pubkey) != null) return standardCovered

        val bridgeTargets = bridgeFallbackRelayTargets(targets)
        if (bridgeTargets.isEmpty()) return standardCovered
        val bridgeCovered = fetchPhase(
            urls = bridgeTargets,
            subIdPrefix = "bridge-profile-relays",
            includeActiveFeedRelay = true,
        )
        return standardCovered || bridgeCovered
    }

    /**
     * One-shot fetch for NIP-51 relay ecosystem kinds: 10006, 10007, 10012, 30002.
     * Sent to the specified indexer relays. These are replaceable/parameterized
     * replaceable events, so we only need the latest for the logged-in user.
     */
    fun fetchRelayEcosystem(pubkeyHex: String, rawIndexerRelayUrls: List<String>) {
        val indexerRelayUrls = rawIndexerRelayUrls.mapNotNull { normalizeRelayUrl(it) }.toSet()
        val subId = "relay-ecosystem-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(10006))
                    add(JsonPrimitive(10007))
                    add(JsonPrimitive(10012))
                    add(JsonPrimitive(30002))
                })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                put("limit", JsonPrimitive(50))
            })
        }.toString()
        // Send to indexers + user's connected write relays. NIP-51 replaceable events
        // are published to write relays (by Jumble, Keychat, etc.), not indexers.
        // Indexers (purplepag.es etc.) focus on kind 0/3/10002 and may not store
        // kinds 10006/10007/10012/30002.
        val writeRelayUrls = memoryEventStore.get().writeRelaysFor(pubkeyHex)
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { it !in indexerRelayUrls && connections.containsKey(it) }
        val allTargets = indexerRelayUrls + writeRelayUrls
        for (url in allTargets) {
            connections[url]?.let { sendOneShotToRelay(it, req) }
        }
        Log.d(TAG, "NIP-51 ecosystem fetch: indexers=${indexerRelayUrls.size} writeRelays=${writeRelayUrls.size} writeList=$writeRelayUrls (ownWrite=${memoryEventStore.get().writeRelaysFor(pubkeyHex).size})")
    }

    /**
     * One-shot fetch for NIP-51 mute list (kind 10000).
     * Sent to indexers plus the owner's write relays, or Global fallbacks when the
     * owner has no relay list. Failed pooled entries are healed before dispatch.
     */
    internal suspend fun fetchMuteList(
        pubkeyHex: String,
        rawIndexerRelayUrls: List<String>,
    ): MuteListFetchResult {
        val indexerRelayUrls = rawIndexerRelayUrls.mapNotNull { normalizeRelayUrl(it) }.toSet()
        val subId = "mute-${System.nanoTime()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(10000)) })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                put("limit", JsonPrimitive(1))
            })
        }.toString()
        val writeRelayUrls = memoryEventStore.get().writeRelaysFor(pubkeyHex)
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { it !in indexerRelayUrls }
            .toSet()
        val fallbackRelayUrls = if (writeRelayUrls.isEmpty()) {
            GLOBAL_RELAY_URLS.mapNotNull(::normalizeRelayUrl).toSet()
        } else {
            emptySet()
        }
        val allTargets = (writeRelayUrls + fallbackRelayUrls + indexerRelayUrls).toList()
        if (allTargets.isEmpty()) {
            Log.w(TAG, "NIP-51 mute-list fetch skipped: no relay targets")
            return MuteListFetchResult()
        }

        // A timed retry may encounter FAILED/DISCONNECTED pooled entries. Heal those
        // entries before choosing negative-result witnesses; sendOneShotBatch otherwise
        // correctly refuses to treat a dead pooled socket as relay evidence.
        connectAndAwait(allTargets, timeoutMs = 5_000)

        // Empty is safe only with two already-live witnesses in each independent
        // relay class. Selecting them before dispatch turns the rule into complete
        // coverage of a small explicit set, rather than "some fraction responded".
        val fallbackEvidenceRelays = fallbackRelayUrls
            .filter { connections[it]?.isConnected == true }
            .take(MUTE_EMPTY_EVIDENCE_RELAYS_PER_CLASS)
            .toSet()
            .takeIf { it.size == MUTE_EMPTY_EVIDENCE_RELAYS_PER_CLASS }
            .orEmpty()
        val indexerEvidenceRelays = indexerRelayUrls
            .filter { connections[it]?.isConnected == true }
            .take(MUTE_EMPTY_EVIDENCE_RELAYS_PER_CLASS)
            .toSet()
            .takeIf { it.size == MUTE_EMPTY_EVIDENCE_RELAYS_PER_CLASS }
            .orEmpty()

        val receivedEvent = AtomicReference<NostrEvent?>(null)
        val eoseRelays = ConcurrentHashMap.newKeySet<String>()
        val evidenceTap = RelayMessageTap { message ->
            when (message) {
                is RelayTapMessage.VerifiedEvent -> {
                    val event = message.event
                    if (message.subscriptionId == subId &&
                        event.kind == 10000 && event.pubkey == pubkeyHex
                    ) {
                        receivedEvent.set(event)
                    }
                }
                is RelayTapMessage.Control -> {
                    if (message.raw.startsWith("[\"EOSE\"") &&
                        extractEoseSubId(message.raw) == subId
                    ) {
                        eoseRelays.add(message.relayUrl)
                    }
                }
            }
        }
        val eose = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = eose
        processor.registerTap(evidenceTap)
        var admittedRelays: Set<String> = emptySet()
        val batchCompleted = try {
            try {
                admittedRelays = sendOneShotBatch(
                    urls = allTargets,
                    reqs = listOf(req),
                    subIds = listOf(subId),
                    timeoutMs = MUTE_LIST_FETCH_TIMEOUT_MS,
                    includeActiveFeedRelay = true,
                )
                withTimeoutOrNull(MUTE_LIST_FETCH_TIMEOUT_MS) { eose.await() } != null
            } finally {
                cleanupOneShotSub(subId)
            }
        } finally {
            processor.unregisterTap(evidenceTap)
        }
        // EOSE is a socket boundary, not an EventProcessor/MES boundary.
        delay(EVENT_PROCESSOR_SETTLE_MS)
        val result = MuteListFetchResult(
            receivedEvent = receivedEvent.get(),
            eoseRelays = eoseRelays.toSet(),
            expectedRelays = admittedRelays,
            writeRelays = writeRelayUrls,
            fallbackRelays = fallbackEvidenceRelays,
            indexerRelays = indexerEvidenceRelays,
        )
        Log.d(
            TAG,
            "NIP-51 mute-list fetch lifecycleComplete=$batchCompleted " +
                "event=${result.receivedEvent != null} " +
                "realEose=${result.eoseRelays.size}/${result.expectedRelays.size} " +
                "(${result.indexerRelays.size} indexers, ${result.writeRelays.size} write, " +
                "${result.fallbackRelays.size} fallback relays)",
        )
        return result
    }

    /**
     * One-shot fetch for NIP-30 user emoji list (kind 10030).
     * Sent to indexer + connected write relays, same pattern as fetchMuteList.
     */
    fun fetchUserEmojiList(pubkeyHex: String, rawIndexerRelayUrls: List<String>) {
        val indexerRelayUrls = rawIndexerRelayUrls.mapNotNull { normalizeRelayUrl(it) }.toSet()
        val subId = "emoji-list-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(10030)) })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                put("limit", JsonPrimitive(1))
            })
        }.toString()
        val writeRelayUrls = memoryEventStore.get().writeRelaysFor(pubkeyHex)
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { it !in indexerRelayUrls && connections.containsKey(it) }
        val allTargets = indexerRelayUrls + writeRelayUrls
        for (url in allTargets) {
            connections[url]?.let { sendOneShotToRelay(it, req) }
        }
        Log.d(TAG, "Fetching NIP-30 emoji list (kind 10030) for ${pubkeyHex.take(8)}… from ${allTargets.size} relay(s)")
    }

    /**
     * One-shot batch fetch for NIP-30 emoji sets (kind 30030).
     * Groups refs by author, sends one REQ per author to their write relays + indexers.
     * Uses connectAndAwait for hint relays that aren't already connected.
     */
    suspend fun fetchEmojiSets(
        refs: List<com.unsilence.app.data.memory.EmojiSetRef>,
        skipHintRelays: Boolean = false,
    ) {
        if (refs.isEmpty()) return
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
        // Group by author so we can batch d-tags per author
        val byAuthor = refs.groupBy { it.authorPubkey }
        for ((author, authorRefs) in byAuthor) {
            val dTags = authorRefs.map { it.setName }
            // Collect hint relays from the refs + author's write relays + indexers
            val hintUrls = if (skipHintRelays) emptyList()
                else authorRefs.mapNotNull { it.hintRelay }
                    .mapNotNull { normalizeRelayUrl(it) }
                    .filter { it !in blockedUrls }
            val writeUrls = memoryEventStore.get().writeRelaysFor(author)
                .mapNotNull { normalizeRelayUrl(it) }
                .filter { it !in blockedUrls }
            val allTargets = (indexerUrls + writeUrls + hintUrls).distinct()
            if (allTargets.isEmpty()) continue

            // Connect to hint relays not yet connected
            val unconnected = allTargets.filter { !connections.containsKey(it) }
            if (unconnected.isNotEmpty()) {
                connectAndAwait(unconnected, timeoutMs = 3_000)
            }

            val subId = "emoji-set-${System.nanoTime()}"
            _activeOneShotSubs.add(subId)
            val req = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(subId))
                add(buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(30030)) })
                    put("authors", buildJsonArray { add(JsonPrimitive(author)) })
                    put("#d", buildJsonArray { dTags.forEach { add(JsonPrimitive(it)) } })
                    put("limit", JsonPrimitive(dTags.size))
                })
            }.toString()

            var sent = 0
            for (url in allTargets) {
                connections[url]?.let { conn ->
                    sendOneShotToRelay(conn, req)
                    sent++
                }
            }
            Log.d(TAG, "Fetching NIP-30 emoji sets: ${dTags.size} set(s) for ${author.take(8)}… from $sent relay(s)")
        }
    }

    /**
     * One-shot fetch for kind-30030 emoji sets.
     * Used by the discover surface on the Custom Emoji settings screen.
     * Sends to all connected relays — indexers specialize in kinds 0/3/10002
     * and don't carry kind-30030 broadly; emoji sets live on general relays.
     */
    fun fetchDiscoverEmojiSets(authorPubkeys: List<String> = emptyList()) {
        val targetUrls = connections.keys.toList()
        if (targetUrls.isEmpty()) return

        val subId = "emoji-discover-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(30030)) })
                if (authorPubkeys.isNotEmpty()) {
                    put("authors", buildJsonArray { authorPubkeys.forEach { add(JsonPrimitive(it)) } })
                }
                put("limit", JsonPrimitive(100))
            })
        }.toString()

        var sent = 0
        for (url in targetUrls) {
            connections[url]?.let { conn ->
                sendOneShotToRelay(conn, req)
                sent++
            }
        }
        Log.d(TAG, "Fetching discover emoji sets " +
            "(authors=${authorPubkeys.size.takeIf { it > 0 } ?: "any"}) " +
            "via $sent relay(s)")
    }

    /**
     * Open a persistent subscription for own kind-10000 on the user's write relays.
     * No limit, no closeOnEose — this is a live tail for the app session.
     * When another client (Amethyst, etc.) publishes an updated mute list, the
     * relay pushes it to us in real time via this subscription.
     */
    fun subscribeOwnMuteList(pubkeyHex: String) {
        val writeRelayUrls = memoryEventStore.get().writeRelaysFor(pubkeyHex)
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { connections.containsKey(it) }
        if (writeRelayUrls.isEmpty()) return
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("own-mute-live"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(10000)) })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
            })
        }.toString()
        liveMuteSubReq = req
        liveMuteSubRelays.clear()
        liveMuteSubRelays.addAll(writeRelayUrls)
        for (url in writeRelayUrls) {
            connections[url]?.send(req)
        }
    }

    /** Close the persistent own-mute-list subscription (teardown). */
    fun closeLiveMuteSub() {
        val urls = liveMuteSubRelays.toList()
        liveMuteSubReq = null
        liveMuteSubRelays.clear()
        if (urls.isNotEmpty()) {
            val close = """["CLOSE","own-mute-live"]"""
            for (url in urls) {
                connections[url]?.send(close)
            }
        }
    }

    /**
     * Paginated historical notification backfill. Fetches notification-eligible
     * events addressed to [pubkeyHex] via #p tag, plus kind-1018 responses
     * e-tagging one of the user's authored polls, from ALL connected relays,
     * paginating as deep as each relay allows. Events flow through
     * EventProcessor → MES → snapshot automatically.
     *
     * Strategy: write relays first (highest yield), then remaining connected
     * non-indexer relays in batches of 6 (concurrent within batch, sequential
     * between batches). The #p filter is highly selective — relays without
     * matching events return EOSE immediately, so the cost of asking is low.
     */
    suspend fun fetchHistoricalNotifications(pubkeyHex: String) {
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
            .mapNotNull { normalizeRelayUrl(it) }.toSet()

        // Write relays first — highest probability of carrying our notifications.
        val writeUrls = memoryEventStore.get().writeRelaysFor(pubkeyHex)
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { connections.containsKey(it) }

        // Remaining connected relays (exclude indexers + already-queried write relays).
        val otherUrls = connectedRelayUrls()
            .filter { it !in indexerUrls && it !in writeUrls && connections[it]?.isConnected == true }

        val allTargets = writeUrls + otherUrls
        if (allTargets.isEmpty()) return

        val filter = buildJsonObject {
            put("kinds", buildJsonArray {
                add(JsonPrimitive(1))    // replies
                add(JsonPrimitive(6))    // reposts
                add(JsonPrimitive(7))    // reactions
                add(JsonPrimitive(9735)) // zap receipts
            })
            put("#p", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
            put("limit", JsonPrimitive(500))
        }
        val pollIds = memoryEventStore.get().authoredPollIds(pubkeyHex)
        val pollVoteFilter = pollIds.takeIf { it.isNotEmpty() }?.let { ids ->
            buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1018)) })
                put("#e", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(500))
            }
        }

        var grandTotal = 0
        // Process in batches of 6 relays (concurrent within batch).
        for (batch in allTargets.chunked(6)) {
            Log.d(TAG, "notif-hist batch: ${batch.size} relays")
            val results = fetchPaginatedEvents(
                urls = batch,
                baseFilter = filter,
                subIdPrefix = "notif-hist",
                maxPages = 20,
                timeoutMs = 30_000,
                onPage = { page, count ->
                    Log.d(TAG, "notif-hist page $page: $count events")
                },
            )
            grandTotal += results.sumOf { it.totalEvents }
            if (pollVoteFilter != null) {
                val voteResults = fetchPaginatedEvents(
                    urls = batch,
                    baseFilter = pollVoteFilter,
                    subIdPrefix = "notif-poll-hist",
                    maxPages = 20,
                    timeoutMs = 30_000,
                    onPage = { page, count ->
                        Log.d(TAG, "notif-poll-hist page $page: $count events")
                    },
                )
                grandTotal += voteResults.sumOf { it.totalEvents }
            }
        }
        Log.d(TAG, "fetchHistoricalNotifications done: $grandTotal events across ${allTargets.size} relays")
    }

    /**
     * Open a persistent subscription for events mentioning the user (#p tag)
     * and responses to the user's authored polls (#e tag).
     * Forward-looking only: uses `since` so relays deliver only events created
     * after bootstrap. Replayed automatically on relay reconnect.
     */
    fun subscribeOwnNotifications(pubkeyHex: String, sinceEpochSeconds: Long) {
        val readRelayUrls = memoryEventStore.get().writeRelaysFor(pubkeyHex)
            .mapNotNull { normalizeRelayUrl(it) }
            .filter { connections.containsKey(it) }
            .ifEmpty {
                connectedRelayUrls().mapNotNull { normalizeRelayUrl(it) }.take(4)
            }
        if (readRelayUrls.isEmpty()) return
        val pollIds = memoryEventStore.get().authoredPollIds(pubkeyHex)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("own-notif-live"))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))    // replies
                    add(JsonPrimitive(6))    // reposts
                    add(JsonPrimitive(7))    // reactions
                    add(JsonPrimitive(9735)) // zap receipts
                })
                put("#p", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                put("since", JsonPrimitive(sinceEpochSeconds))
            })
            if (pollIds.isNotEmpty()) {
                add(buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(1018)) })
                    put("#e", buildJsonArray { pollIds.forEach { add(JsonPrimitive(it)) } })
                    put("since", JsonPrimitive(sinceEpochSeconds))
                })
            }
        }.toString()
        liveNotifSubReq = req
        liveNotifSince = sinceEpochSeconds
        liveNotifSubRelays.clear()
        liveNotifSubRelays.addAll(readRelayUrls)
        for (url in readRelayUrls) {
            connections[url]?.send(req)
        }
        Log.d(TAG, "subscribeOwnNotifications: ${readRelayUrls.size} relays, since=$sinceEpochSeconds")
    }

    /** Rebuild the live #e filter after authoring a poll in this app session. */
    fun refreshOwnNotificationSubscription(pubkeyHex: String) {
        subscribeOwnNotifications(
            pubkeyHex = pubkeyHex,
            sinceEpochSeconds = liveNotifSince ?: (System.currentTimeMillis() / 1000L),
        )
    }

    /** Close the persistent notification subscription (teardown). */
    fun closeLiveNotifSub() {
        val urls = liveNotifSubRelays.toList()
        liveNotifSubReq = null
        liveNotifSince = null
        liveNotifSubRelays.clear()
        if (urls.isNotEmpty()) {
            val close = """["CLOSE","own-notif-live"]"""
            for (url in urls) {
                connections[url]?.send(close)
            }
        }
    }

    /**
     * Fetch kind-30002 relay sets by coordinate (author + d-tags) from hint relays.
     * Used to resolve ["a", "30002:pubkey:dtag", "hint-relay"] references in kind-10012.
     * Connects to hint relays if not already connected, sends a single REQ with
     * #d filter to fetch all referenced sets in one subscription.
     */
    suspend fun fetchRelaySetsByCoordinate(
        authorPubkey: String,
        dTags: List<String>,
        hintRelayUrls: List<String>,
    ) {
        if (dTags.isEmpty() || hintRelayUrls.isEmpty()) return
        val normalized = hintRelayUrls.mapNotNull { normalizeRelayUrl(it) }
            .filter { it !in blockedUrls }
        if (normalized.isEmpty()) return

        connectAndAwait(normalized, timeoutMs = 3_000)

        val subId = "setref-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(30002)) })
                put("authors", buildJsonArray { add(JsonPrimitive(authorPubkey)) })
                put("#d", buildJsonArray { dTags.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(dTags.size))
            })
        }.toString()

        var sent = 0
        for (url in normalized) {
            connections[url]?.let { conn ->
                sendOneShotToRelay(conn, req)
                sent++
            }
        }
        Log.d(TAG, "fetchRelaySetsByCoordinate: ${dTags.size} sets from $sent hint relay(s) for ${authorPubkey.take(8)}…")
    }

    // ── Paginated fetch primitive ────────────────────────────────────────
    //
    // Reusable building block for fetching large result sets (>500 events)
    // from a single relay. Handles: 500-event ceiling (pagination via
    // "until"), EOSE signaling, per-page cleanup, overall timeout.
    //
    // ISOLATION: uses conn.send() directly, bypasses sub cap and rate limiter.
    // Events flow through EventProcessor's direct-path insert into MES.

    /**
     * Paginated fetch from a single relay. Sends REQ, waits for EOSE,
     * paginates with "until" if the page is full, repeats until last page
     * or overall timeout.
     */
    private suspend fun paginatedFetch(
        conn: RelayConnection,
        baseFilter: JsonObject,
        subIdPrefix: String,
        timeoutMs: Long = 15_000,
        maxPages: Int = Int.MAX_VALUE,
        onPage: (pageNum: Int, eventCount: Int) -> Unit = { _, _ -> },
    ): PaginatedFetchResult {
        if (relayCapabilitiesStore.shouldSkipRequest(conn.url, RelayRequestClass.GENERAL)) {
            return PaginatedFetchResult(0, 0, 0L, conn.url)
        }
        var totalEvents = 0
        var totalPages = 0
        var globalOldest = Long.MAX_VALUE
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline && totalPages < maxPages) {
            val subId = "$subIdPrefix-${System.nanoTime()}"
            val pageState = PageState()
            _activePages[subId] = pageState

            val filter = if (globalOldest < Long.MAX_VALUE) {
                buildJsonObject {
                    baseFilter.forEach { (key, value) -> put(key, value) }
                    put("until", JsonPrimitive(globalOldest - 1))
                }
            } else {
                baseFilter
            }

            val req = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(subId))
                add(filter)
            }.toString()

            val sent = conn.send(req)
            if (!sent) {
                _activePages.remove(subId)
                Log.w(TAG, "paginatedFetch: send failed on ${conn.url}")
                break
            }

            val remaining = deadline - System.currentTimeMillis()
            val gotEose = withTimeoutOrNull(remaining) {
                pageState.eoseReceived.await()
            }

            val pageCount = pageState.eventCount.get()
            val pageOldest = pageState.oldestCreatedAt.get()

            // Send CLOSE and clean up
            conn.send(buildJsonArray {
                add(JsonPrimitive("CLOSE"))
                add(JsonPrimitive(subId))
            }.toString())
            _activePages.remove(subId)

            totalEvents += pageCount
            totalPages++
            if (pageOldest < globalOldest) globalOldest = pageOldest
            onPage(totalPages, pageCount)

            if (gotEose == null) {
                Log.w(TAG, "paginatedFetch: timeout on page $totalPages of ${conn.url}")
                break
            }
            if (pageCount < 450) break
        }

        return PaginatedFetchResult(
            totalEvents = totalEvents,
            totalPages = totalPages,
            oldestCreatedAt = if (globalOldest < Long.MAX_VALUE) globalOldest else 0L,
            relay = conn.url,
        )
    }

    // ── Public paginated fetch wrapper ──────────────────────────────────

    /**
     * Paginated fetch across multiple relays (concurrent). Events flow through
     * EventProcessor → MES as they arrive — no special routing needed.
     *
     * @param urls relay URLs to fetch from (will be connected via [connectAndAwait]).
     * @param baseFilter JSON filter object (kinds, authors, limit, etc.).
     * @param subIdPrefix subscription ID prefix for all pages.
     * @param maxPages max pages per relay before stopping.
     * @param timeoutMs overall timeout per relay.
     * @param onPage per-page callback (pageNum, eventCount) for logging.
     * @return list of [PaginatedFetchResult], one per relay that responded.
     */
    suspend fun fetchPaginatedEvents(
        urls: List<String>,
        baseFilter: JsonObject,
        subIdPrefix: String,
        maxPages: Int = 5,
        timeoutMs: Long = 30_000,
        onPage: (pageNum: Int, eventCount: Int) -> Unit = { _, _ -> },
    ): List<PaginatedFetchResult> {
        val normalized = urls.mapNotNull { normalizeRelayUrl(it) }.distinct()
            .filter { it !in blockedUrls }
        if (normalized.isEmpty()) return emptyList()

        connectAndAwait(normalized, timeoutMs = 5_000)

        return coroutineScope {
            normalized.mapNotNull { url ->
                val conn = connections[url] ?: return@mapNotNull null
                if (!conn.isConnected) return@mapNotNull null
                async {
                    val perRelayTimeout = (timeoutMs / normalized.size.coerceAtLeast(1))
                        .coerceIn(10_000, timeoutMs)
                    paginatedFetch(
                        conn = conn,
                        baseFilter = baseFilter,
                        subIdPrefix = subIdPrefix,
                        timeoutMs = perRelayTimeout,
                        maxPages = maxPages,
                        onPage = onPage,
                    )
                }
            }.awaitAll()
        }
    }

    suspend fun fetchFollowerPage(
        subjectPubkey: String,
        until: Long?,
        onPage: (pageNum: Int, eventCount: Int) -> Unit = { _, _ -> },
    ): List<PaginatedFetchResult> {
        val filter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(3)) })
            put("#p", buildJsonArray { add(JsonPrimitive(subjectPubkey)) })
            put("limit", JsonPrimitive(FOLLOWERS_PAGE_SIZE))
            until?.let { put("until", JsonPrimitive(it)) }
        }
        return fetchPaginatedEvents(
            urls = FOLLOWER_INDEX_RELAY_URLS,
            baseFilter = filter,
            subIdPrefix = "connections-followers",
            maxPages = 1,
            timeoutMs = 24_000,
            onPage = onPage,
        )
    }

    /** Fetch each candidate author's latest replaceable contact list. */
    suspend fun fetchLatestFollowLists(pubkeys: Collection<String>): Boolean {
        if (pubkeys.isEmpty()) return true
        var allChunksResponded = true
        pubkeys.distinct().chunked(FOLLOWERS_PAGE_SIZE).forEach { authors ->
            val filter = buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(3)) })
                put("authors", buildJsonArray { authors.forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(authors.size))
            }
            val results = fetchPaginatedEvents(
                urls = FOLLOWER_INDEX_RELAY_URLS,
                baseFilter = filter,
                subIdPrefix = "connections-verify",
                maxPages = 1,
                timeoutMs = 18_000,
            )
            if (results.none { it.totalPages > 0 }) allChunksResponded = false
        }
        return allChunksResponded
    }

    /**
     * Fetch verified NIP-51 starter packs without admitting kind 39089 to feed lanes.
     * Each endpoint gets a small share of the global budget; callers apply the
     * addressable latest-per-(author,d) projection before rendering.
     */
    suspend fun fetchFollowPackEvents(
        relayUrls: Collection<String>,
        limit: Int = FOLLOW_PACK_FETCH_LIMIT,
    ): List<NostrEvent> {
        val targets = relayUrls.asSequence()
            .mapNotNull(::normalizeRelayUrl)
            .distinct()
            .filter { it !in blockedUrls && !relayCapabilitiesStore.shouldSkip(it) }
            .take(FOLLOW_PACK_RELAY_LIMIT)
            .toList()
        if (targets.isEmpty() || limit <= 0) return emptyList()

        val perRelayLimit = ((limit + targets.size - 1) / targets.size + 3)
            .coerceIn(4, limit)
        val fetched = coroutineScope {
            targets.map { relayUrl ->
                async { fetchFollowPacksFromRelay(relayUrl, perRelayLimit) }
            }.awaitAll().flatten()
        }

        val byId = LinkedHashMap<String, NostrEvent>(fetched.size)
        fetched.sortedByDescending(NostrEvent::createdAt).forEach { event ->
            val existing = byId[event.id]
            if (existing == null) byId[event.id] = event
            else existing.relaysSeen.addAll(event.relaysSeen)
        }
        return byId.values.take(limit)
    }

    private suspend fun fetchFollowPacksFromRelay(
        relayUrl: String,
        limit: Int,
    ): List<NostrEvent> {
        val conn = relayConnectionFactory.create(relayUrl)
        return try {
            if (!connectTrackedEphemeral(conn)) return emptyList()
            val state = withTimeoutOrNull(3_000L) {
                conn.state.first {
                    it == RelayState.CONNECTED || it == RelayState.FAILED || it == RelayState.DISCONNECTED
                }
            }
            if (state != RelayState.CONNECTED) return emptyList()

            val subId = "follow-packs-${System.nanoTime()}"
            val request = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(subId))
                add(buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(FOLLOW_PACK_KIND)) })
                    put("limit", JsonPrimitive(limit))
                })
            }.toString()
            if (!conn.send(request)) return emptyList()

            val events = ArrayList<NostrEvent>(limit)
            withTimeoutOrNull(FOLLOW_PACK_RELAY_TIMEOUT_MS) {
                while (events.size < limit) {
                    val raw = conn.messages.receive()
                    when {
                        raw.startsWith("[\"EVENT\"") -> {
                            processor.parseAndVerify(raw, relayUrl)
                                ?.takeIf { it.kind == FOLLOW_PACK_KIND }
                                ?.let(events::add)
                        }
                        raw.startsWith("[\"EOSE\"") && extractEoseSubId(raw) == subId -> break
                        raw.startsWith("[\"CLOSED\"") && raw.contains("\"$subId\"") -> break
                    }
                }
            }
            conn.send(buildJsonArray {
                add(JsonPrimitive("CLOSE"))
                add(JsonPrimitive(subId))
            }.toString())
            events
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Follow-pack fetch failed on $relayUrl: ${e.message}")
            emptyList()
        } finally {
            closeTrackedEphemeral(conn)
        }
    }

    // ── Relay health fetch orchestrators ──────────────────────────────────

    /**
     * Fetch NIP-85 kind-30382 user WoT assertions for [subjects] from the active provider relay.
     *
     * Chunks run sequentially through the normal one-shot demultiplexer. The provider is kept as
     * an unpurposed pooled connection, so viewport hydration batches share one handshake until the
     * regular idle sweep (or background teardown) removes it. Each chunk is marked queried only
     * after its own real EOSE, preserving the MES Pending vs Absent distinction.
     */
    suspend fun fetchWotAssertions(
        providerPubkey: String,
        relayHint: String,
        subjects: Collection<String>,
        prioritySubjects: Collection<String> = emptyList(),
    ): Boolean {
        val provider = normalizeWotPubkey(providerPubkey) ?: return false
        val relayUrl = normalizeRelayUrl(relayHint) ?: return false
        if (relayUrl in blockedUrls || relayCapabilitiesStore.shouldSkip(relayUrl)) return false

        val chunks = wotSubjectChunks(subjects, prioritySubjects = prioritySubjects)
        if (chunks.isEmpty()) return false

        return try {
            runWotChunkBatch(chunks) { chunk, page, totalPages ->
                try {
                    fetchWotChunk(relayUrl, provider, chunk, page, totalPages)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "WoT fetch failed for chunk $page/$totalPages on $relayUrl: ${e.message}")
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "WoT fetch failed on $relayUrl: ${e.message}")
            false
        }
    }

    private suspend fun fetchWotChunk(
        relayUrl: String,
        provider: String,
        chunk: List<String>,
        page: Int,
        totalPages: Int,
    ): Boolean {
        // Admit the provider to the unpurposed warm pool. Repeated viewport batches reuse it,
        // while the ordinary sweep/background lifecycle still bounds its lifetime.
        if (getOrCreateConnection(relayUrl, RelayRequestClass.GENERAL) == null) {
            Log.w(TAG, "WoT fetch connect failed on $relayUrl")
            return false
        }

        val subId = "wot-30382-${relayUrl.hashCode().toUInt()}-${System.nanoTime()}-$page"
        val filter = wotAssertionFilter(provider, chunk) ?: return false
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(filter)
        }.toString()

        val verifiedEvents = ConcurrentHashMap<String, NostrEvent>()
        val sawRealEose = AtomicBoolean(false)
        val evidenceTap = RelayMessageTap { message ->
            when (message) {
                is RelayTapMessage.VerifiedEvent -> {
                    val event = message.event
                    if (message.subscriptionId == subId &&
                        event.kind == 30382 &&
                        normalizeWotPubkey(event.pubkey) == provider
                    ) {
                        verifiedEvents.putIfAbsent(event.id, event)
                    }
                }
                is RelayTapMessage.Control -> {
                    if (message.raw.startsWith("[\"EOSE\"") &&
                        extractEoseSubId(message.raw) == subId
                    ) {
                        sawRealEose.set(true)
                    }
                }
            }
        }
        val completion = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = completion
        processor.registerTap(evidenceTap)
        val eosed = try {
            sendOneShotBatch(
                urls = listOf(relayUrl),
                reqs = listOf(req),
                subIds = listOf(subId),
                timeoutMs = WOT_FETCH_TIMEOUT_MS,
                requestClass = RelayRequestClass.GENERAL,
            )
            withTimeoutOrNull(WOT_FETCH_TIMEOUT_MS) { completion.await() } != null &&
                sawRealEose.get()
        } finally {
            cleanupOneShotSub(subId)
            processor.unregisterTap(evidenceTap)
        }

        memoryEventStore.get().insertWotAssertionChunk(
            providerPubkey = provider,
            events = verifiedEvents.values.toList(),
            queriedSubjects = if (eosed) chunk else emptyList(),
        )
        return eosed
    }

    /**
     * Fetch the user's public NIP-85 provider registry (kind-10040).
     *
     * Events route through EventProcessor → MES, where only own-pubkey 10040 rows
     * are accepted. The default WoT relay is included because personal providers may
     * publish the registry there even when it is not in the user's read list.
     */
    suspend fun fetchOwn10040(ownPubkey: String): Boolean {
        val owner = normalizeWotPubkey(ownPubkey) ?: return false
        val targets = wotRegistryLookupTargets(
            readRelays = memoryEventStore.get().readRelaysFor(owner),
            registryRelays = WOT_REGISTRY_LOOKUP_RELAYS,
        )
        if (targets.relayUrls.isEmpty()) return false

        val subId = "wot-10040-${System.nanoTime()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(10040)) })
                put("authors", buildJsonArray { add(JsonPrimitive(owner)) })
                put("limit", JsonPrimitive(1))
            })
        }.toString()

        val eoseDeferred = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = eoseDeferred
        return try {
            sendOneShotBatch(
                urls = targets.relayUrls,
                reqs = listOf(req),
                subIds = listOf(subId),
                timeoutMs = 8_000L,
                capabilityBypassRelays = targets.capabilityBypassRelays,
            )
            withTimeoutOrNull(8_000L) { eoseDeferred.await() } != null
        } finally {
            cleanupOneShotSub(subId)
        }
    }

    /**
     * Fetch kind 30385 (Trusted Relay Assertions) using paginated multi-relay fetch.
     *
     * 1. Resolve provider's write relays from kind-10002
     * 2. Paginated fetch on each write relay (concurrent)
     * 3. Log coverage metrics
     */
    /**
     * Fetch kind 30385 (trust scores) for specific relay URLs only.
     * Uses #d filter so the relay returns only the requested entries.
     * Each relay URL is a d-tag in kind 30385 replaceable events.
     */
    suspend fun fetchTrustScores(providerPubkeyHex: String, relayUrls: List<String>): Boolean {
        if (relayUrls.isEmpty()) return false

        // Normalize relay URLs for consistent #d matching with trust score d-tags
        val normalizedUrls = relayUrls.mapNotNull { normalizeRelayUrl(it) }.distinct()
        if (normalizedUrls.isEmpty()) return false

        // Resolve provider's write relays to know where to fetch from
        fetchRelayLists(listOf(providerPubkeyHex))
        val mes = memoryEventStore.get()
        val writeRelays = withTimeoutOrNull(5_000L) {
            var relays = mes.writeRelaysFor(providerPubkeyHex)
            while (relays.isEmpty()) {
                delay(200)
                relays = mes.writeRelaysFor(providerPubkeyHex)
            }
            relays
        } ?: emptyList()

        val sourceUrls = if (writeRelays.isNotEmpty()) {
            writeRelays.mapNotNull { normalizeRelayUrl(it) }
        } else {
            Log.w(TAG, "Trust score provider 10002 not found — using fallback relays")
            listOf("wss://nos.lol", "wss://relay.damus.io")
        }

        // #d filter: request only the relay URLs we care about (normalized)
        val filter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(30385)) })
            put("authors", buildJsonArray { add(JsonPrimitive(providerPubkeyHex)) })
            put("#d", buildJsonArray { normalizedUrls.forEach { add(JsonPrimitive(it)) } })
        }
        Log.d(TAG, "Trust score REQ: ${normalizedUrls.size} relays from ${sourceUrls.size} sources")

        // Try each source relay until we get results
        var fetched = 0
        var completed = false
        for (url in sourceUrls) {
            val conn = getOrCreateConnection(url) ?: continue
            try {
                val subId = "trust-${url.hashCode().toUInt()}-${System.nanoTime()}"
                val pageState = PageState()
                _activePages[subId] = pageState

                val req = buildJsonArray {
                    add(JsonPrimitive("REQ"))
                    add(JsonPrimitive(subId))
                    add(filter)
                }.toString()

                if (!conn.send(req)) {
                    _activePages.remove(subId)
                    continue
                }

                // With #d filter, result set is small — 15s is plenty
                val eosed = withTimeoutOrNull(15_000) { pageState.eoseReceived.await() } != null
                if (eosed) completed = true
                val count = pageState.eventCount.get()

                conn.send(buildJsonArray {
                    add(JsonPrimitive("CLOSE"))
                    add(JsonPrimitive(subId))
                }.toString())
                _activePages.remove(subId)

                fetched += count
                Log.d(TAG, "Trust scores from $url: $count events")

                // If we got a good response, no need to try more sources
                if (count > 0) break
            } catch (e: Exception) {
                Log.w(TAG, "Trust fetch failed on $url: ${e.message}")
            }
        }

        val mesCount = mes.getTrustScores().size
        Log.d(TAG, "Trust scores: $mesCount in MES ($fetched fetched for ${normalizedUrls.size} requested)")
        // A real EOSE with zero events is still a successful, authoritative
        // fetch for the requested #d set and should advance the staleness gate.
        return completed
    }

    /**
     * Fetch kind 30166 (NIP-66 relay monitors) from relay.nostr.watch.
     * Fetches ALL monitors (no #d filter) because relay.nostr.watch uses
     * d-tag formats that may not match our normalized URLs (e.g. trailing slashes).
     * With snapshot persistence this only downloads once; subsequent launches
     * restore from disk and refresh in the background.
     */
    /** @return true only if the monitor relay was reached and pagination completed
     *  without error. The caller MUST advance the 12h staleness gate only on true —
     *  a failed fetch that marked "fetched" buys 12h of silent staleness (H20 lesson:
     *  a gate poisoned by unverified success). */
    suspend fun fetchRelayMonitors(): Boolean {
        var conn = getOrCreateConnection(RELAY_MONITOR_URL)
        if (conn == null) {
            Log.w(TAG, "relay.nostr.watch unreachable — retrying in 3s")
            delay(3_000)
            conn = getOrCreateConnection(RELAY_MONITOR_URL)
        }
        if (conn == null) {
            Log.w(TAG, "relay.nostr.watch unreachable after retry — skipping relay monitors")
            return false
        }

        val baseFilter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(30166)) })
            put("authors", buildJsonArray { add(JsonPrimitive(RELAY_MONITOR_PUBKEY)) })
        }

        return try {
            val result = paginatedFetch(
                conn = conn,
                baseFilter = baseFilter,
                subIdPrefix = "relay-monitor",
                timeoutMs = 30_000,
                onPage = { page, count ->
                    Log.d(TAG, "Monitor page $page: $count events")
                },
            )
            val monitorCount = memoryEventStore.get().getRelayMonitors().size
            Log.d(TAG, "Relay monitors: $monitorCount in MES " +
                "(${result.totalPages} pages, ${result.totalEvents} events)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Monitor fetch failed: ${e.message}")
            false
        }
    }

    // ── Relay Directory firehose (Phase 1) ────────────────────────────────────
    // Two vetted monitors (dynamic registry discovery = Phase 1.5 backlog), author-pinned
    // across four redundant transports — author and transport are independent dimensions,
    // censorship-resilient by construction. 30166 events are signature-verified (A1) then
    // parsed into a bounded map; they NEVER enter MES (raw→directory→discard), so MES/size
    // stays flat by construction.
    private val DIRECTORY_MONITOR_PUBKEYS = setOf(
        RELAY_MONITOR_PUBKEY,  // 9bbbb845 — rich schema (RTT, NIPs, embedded NIP-11)
        "6d9717bc8758ddf99bc1b0e325d60bf5c41418dc122d81de6cd1a35138e51fe3",  // light/bot — liveness corroboration
    )
    private val DIRECTORY_TRANSPORT_RELAYS = listOf(
        RELAY_MONITOR_URL, "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net",
    )
    private val DIRECTORY_TTL_MS = 6L * 60 * 60 * 1000   // 6h, success-only
    private val DIRECTORY_MAX_PAGES = 8
    private val relayDirectory = ConcurrentHashMap<String, RelayDirectoryEntry>()
    private val directoryInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastDirectoryBuildAt = 0L
    private val _directoryFlow = MutableStateFlow<Map<String, RelayDirectoryEntry>>(emptyMap())
    private val _directoryBuilding = MutableStateFlow(false)

    /** Snapshot of the built directory (UI/validation). */
    fun directorySnapshot(): Map<String, RelayDirectoryEntry> = relayDirectory.toMap()

    /** Reactive directory for the §04 Discovery screen — emits the full map after each build. */
    val directoryFlow: StateFlow<Map<String, RelayDirectoryEntry>> = _directoryFlow.asStateFlow()
    /** True while a directory build is in flight (Discovery loading state). */
    val directoryBuilding: StateFlow<Boolean> = _directoryBuilding.asStateFlow()

    /** How many of [urls] (the caller's own kind-10002 relays) are CONNECTED right now.
     *  One-shot snapshot — no ticker. INTERSECTION, never connections.size: the pool idles
     *  ~30 sockets (indexers/search/monitor/warmed globals), so a "N online" badge must mean
     *  the user's own relays, not the whole pool. Reused by Settings + the relay health bar. */
    fun connectedCountOf(urls: Collection<String>): Int {
        val normalized = urls.mapNotNull { normalizeRelayUrl(it) }.toSet()
        return normalized.count { connections[it]?.isConnected == true }
    }

    /** USER-INITIATED one-shot RTT probe (the relay-screen "Test" action). Times the connect
     *  handshake on a fresh ephemeral socket. Deliberately NO background/periodic pinger and
     *  NO persisted history — lean, on-demand only. Returns connect latency in ms, or null if
     *  unreachable within the timeout. */
    suspend fun measureRtt(url: String): Int? {
        val u = normalizeRelayUrl(url) ?: return null
        if (u in blockedUrls) return null
        val conn = relayConnectionFactory.create(u)
        val start = System.nanoTime()
        return try {
            if (!connectTrackedEphemeral(conn)) return null
            val state = withTimeoutOrNull(5_000) {
                conn.state.first { it == RelayState.CONNECTED || it == RelayState.FAILED || it == RelayState.DISCONNECTED }
            }
            if (state == RelayState.CONNECTED) ((System.nanoTime() - start) / 1_000_000L).toInt() else null
        } catch (_: Exception) {
            null
        } finally {
            closeTrackedEphemeral(conn)
        }
    }

    /**
     * Build/refresh the relay directory (Phase 1). Public, ON-DEMAND only — never cold-start
     * or background (Phase 2 wires it to the discovery screen). Single-flight; 6h success-only
     * TTL (mirrors c9dbb4e4 — a failed build never advances the TTL, so a bad network doesn't
     * buy 6h of staleness).
     */
    suspend fun ensureDirectoryFresh() {
        val sinceLast = System.currentTimeMillis() - lastDirectoryBuildAt
        if (lastDirectoryBuildAt > 0L && sinceLast < DIRECTORY_TTL_MS) {
            Log.w(TAG, "DIRECTORY: fresh (age=${sinceLast / 60_000}min) — skipping fetch")
            return
        }
        if (!directoryInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "DIRECTORY: build already in flight — skipping")
            return
        }
        _directoryBuilding.value = true
        val started = System.currentTimeMillis()
        try {
            // MES-derived enrichment inputs — computed ONCE up front (they don't depend
            // on the fetch), so the progressive re-publishes below stay cheap.
            val mes = memoryEventStore.get()
            val ownPk = keyManager.getPublicKeyHex()
            val trust = mes.getTrustScores()
            val popularity = computeDirectoryPopularity(mes)
            val followsUsing = computeDirectoryFollowsUsing(mes, ownPk)
            // The user's OWN configured relays (r/w, search, favorites, set members) —
            // always seeded so relays you ALREADY use are discoverable even if the
            // monitor never reported them (e.g. perspective relays like subnet.relays.land).
            val configured = if (ownPk != null) buildSet {
                addAll(mes.writeRelaysFor(ownPk))
                addAll(mes.readRelaysFor(ownPk))
                addAll(mes.getSearchRelayUrls(ownPk))
                mes.getFavoriteRelayConfigs(ownPk).forEach { it.url?.let(::add) }
                mes.getAllRelaySets(ownPk).forEach { addAll(it.members) }
            }.mapNotNull { normalizeRelayUrl(it) } else emptyList()
            // Bound never evicts the user's own r/w relays.
            val ownRelays = if (ownPk != null) {
                (mes.writeRelaysFor(ownPk) + mes.readRelaysFor(ownPk)).mapNotNull { normalizeRelayUrl(it) }.toSet()
            } else emptySet()

            val perUrl = HashMap<String, MutableList<RelayDirectoryEntry>>()
            var totalEvents = 0
            var verifyFailed = 0
            var capped = false

            // Build the directory from accumulated perUrl + seed own relays + bound,
            // then publish. Called PROGRESSIVELY after each transport returns so the
            // screen populates from the fast transports (~2s) and fills to full
            // coverage when the slow transport (relay.nostr.watch) completes (~15s).
            fun rebuildAndPublish() {
                val built = perUrl.mapValues { (_, entries) ->
                    // Dedup per monitor (newest createdAt), then merge across monitors (median RTT).
                    val perMonitorNewest = entries.groupBy { it.monitorPubkeys.firstOrNull() }
                        .values.map { grp -> grp.maxBy { it.monitorLastSeenAt } }
                    val merged = RelayDirectory.mergeMonitorEntries(perMonitorNewest)
                    enrichDirectoryEntry(merged, trust[merged.url]?.score, popularity[merged.url] ?: 0, followsUsing[merged.url] ?: 0)
                }.toMutableMap()
                for (u in configured) {
                    if (u !in built) built[u] = enrichDirectoryEntry(RelayDirectoryEntry(url = u), trust[u]?.score, popularity[u] ?: 0, followsUsing[u] ?: 0)
                }
                val bounded = RelayDirectory.enforceBound(built, ownRelays)
                relayDirectory.clear()
                relayDirectory.putAll(bounded)
                _directoryFlow.value = relayDirectory.toMap()
            }

            // Fetch all transports CONCURRENTLY; consume results in COMPLETION order via
            // a channel so the fast transports publish immediately (perceived load ≈ the
            // fastest transport, not the slowest). Same total bandwidth as sequential;
            // each collectDirectory30166 is failure-isolated (catches internally + closes
            // its own ephemeral conn).
            coroutineScope {
                val results = Channel<Triple<String, List<NostrEvent>, Int>>(DIRECTORY_TRANSPORT_RELAYS.size)
                for (transport in DIRECTORY_TRANSPORT_RELAYS) {
                    launch {
                        val (events, failed) = collectDirectory30166(transport)
                        results.send(Triple(transport, events, failed))
                    }
                }
                repeat(DIRECTORY_TRANSPORT_RELAYS.size) {
                    val (transport, events, failed) = results.receive()
                    verifyFailed += failed
                    // Per-transport breakdown — proves the redundancy is actually multi-sourced
                    // (the only field signal that a transport is silently rate-limited/empty).
                    Log.w(TAG, "DIRECTORY: transport $transport → ${events.size} events ($failed verify-failed)")
                    if (!capped) {
                        for (ev in events) {
                            totalEvents++
                            val entry = RelayDirectory.parseMonitorEvent(ev.tags, ev.content, ev.pubkey, ev.createdAt)
                                ?: continue
                            perUrl.getOrPut(entry.url) { mutableListOf() }.add(entry)
                            if (perUrl.size >= RelayDirectory.MAX_DIRECTORY_ENTRIES) { capped = true; break }
                        }
                    }
                    rebuildAndPublish()   // progressive emit after each transport completes
                }
                results.close()
            }

            lastDirectoryBuildAt = System.currentTimeMillis()
            val reachable = relayDirectory.values.count { it.reachability == Reachability.REACHABLE }
            val dnsBlocked = relayDirectory.values.count { it.reachability == Reachability.DNS_BLOCKED }
            Log.w(TAG, "DIRECTORY: built ${relayDirectory.size} relays from $totalEvents events " +
                "($verifyFailed verified-failed), ${System.currentTimeMillis() - started}ms, " +
                "reachable=$reachable dnsBlocked=$dnsBlocked")
            val land = relayDirectory.keys.filter { it.contains("relays.land") }
            Log.w(TAG, "DIRECTORY: relays.land entries = $land")   // acceptance signal
        } catch (e: Exception) {
            Log.w(TAG, "DIRECTORY: build failed — ${e.message} (TTL not advanced, will retry)")
        } finally {
            directoryInFlight.set(false)
            _directoryBuilding.value = false
        }
    }

    /** Per-build "N you follow" — for each url, how many of the user's follows list it in their
     *  kind-10002. Bounded (follows set × their relay lists), computed once per build; the detail
     *  page's per-open recompute (buildRelayDetail) stays the fresher override. */
    private fun computeDirectoryFollowsUsing(
        mes: com.unsilence.app.data.memory.MemoryEventStore,
        ownPubkey: String?,
    ): Map<String, Int> {
        val follows = ownPubkey?.let { mes.getFollows(it) } ?: return emptyMap()
        if (follows.isEmpty()) return emptyMap()
        val allLists = mes.allRelayListsSnapshot()
        val counts = HashMap<String, Int>()
        for (fp in follows) {
            val rl = allLists[fp] ?: continue
            (rl.read + rl.write).mapNotNull { normalizeRelayUrl(it) }.toSet().forEach { url ->
                counts[url] = (counts[url] ?: 0) + 1
            }
        }
        return counts
    }

    private fun computeDirectoryPopularity(mes: com.unsilence.app.data.memory.MemoryEventStore): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for ((_, list) in mes.allRelayListsSnapshot()) {
            (list.read + list.write).mapNotNull { normalizeRelayUrl(it) }.toSet().forEach { url ->
                counts[url] = (counts[url] ?: 0) + 1
            }
        }
        return counts
    }

    private fun enrichDirectoryEntry(merged: RelayDirectoryEntry, trustScore: Int?, popularity: Int, followsUsing: Int): RelayDirectoryEntry {
        val caps = relayCapabilitiesStore.get(merged.url)
        // Device-empirical evidence is the trump card (A5); monitor data only adds positive signal.
        val reach = RelayDirectory.computeReachability(
            hasCapsEntry = caps != null,
            deadFailCount = caps?.deadFailCount ?: 0,
            authRequired = (caps?.authRequired == true) || merged.auth,
            restricted = caps?.restricted == true,
            strikes = caps?.strikes ?: 0,
            consecutiveFailures = caps?.consecutiveFailures ?: 0,
            lastReason = caps?.lastReason,
            monitorRttMs = merged.monitorRttMs,
        )
        return merged.copy(
            trustScore = trustScore,
            popularity = popularity,
            followsUsing = followsUsing,
            ourLastReason = caps?.lastReason?.takeIf { it.isNotBlank() },
            reachability = reach,
        )
    }

    /**
     * Build the per-relay DETAIL view (§05, on detail-open). Overlays this device's NIP-11
     * fetch onto the monitor seed (PERSPECTIVE RULE — device wins), takes ONE live RTT probe,
     * and recomputes reachability from fresh caps + the probe outcome. Writes the merged entry
     * back into the directory so the device perspective persists for the session (a 6h rebuild
     * resets it to the monitor seed, re-fetched on next open). Returns null only for an
     * unnormalizable url. Never throws — an unreachable host yields a monitor-seed entry with
     * ourRttMs=null, which the detail page renders as an honest "untested/blocked" verdict.
     */
    suspend fun buildRelayDetail(url: String): RelayDirectoryEntry? {
        val u = normalizeRelayUrl(url) ?: return null
        val mes = memoryEventStore.get()
        val base = relayDirectory[u]
            ?: RelayDirectoryEntry(url = u, popularity = computeDirectoryPopularity(mes)[u] ?: 0)

        // "You follow" — of the people YOU follow (kind-3), how many list this relay in their
        // kind-10002. The honest reading of the §05 "do people I trust use it?" question;
        // distinct from `popularity` (the broad all-known-lists ranking signal).
        val ownPk = keyManager.getPublicKeyHex()
        val follows = ownPk?.let { mes.getFollows(it) } ?: emptySet()
        val allLists = mes.allRelayListsSnapshot()
        val followsUsing = follows.count { fp ->
            allLists[fp]?.let { rl -> (rl.read + rl.write).any { normalizeRelayUrl(it) == u } } == true
        }

        // Device NIP-11 — the authority. Null (unreachable/blocked) keeps the monitor seed.
        val doc = nip11Fetcher.fetch(u)
        val overlaid = if (doc != null) RelayDirectory.overlayDeviceNip11(base, doc) else base

        // One live RTT on open (the connect-handshake probe). This also refreshes caps:
        // onOpen clears strikes; a failure records the skip reason — so read caps AFTER.
        val rtt = measureRtt(u)
        val caps = relayCapabilitiesStore.get(u)
        val reach = RelayDirectory.computeReachability(
            hasCapsEntry = caps != null || rtt != null,
            deadFailCount = caps?.deadFailCount ?: 0,
            authRequired = (caps?.authRequired == true) || overlaid.auth,
            restricted = caps?.restricted == true,
            strikes = caps?.strikes ?: 0,
            consecutiveFailures = caps?.consecutiveFailures ?: 0,
            lastReason = caps?.lastReason,
            monitorRttMs = rtt ?: overlaid.monitorRttMs,
        )
        val detail = overlaid.copy(
            ourRttMs = rtt,
            ourLastReason = caps?.lastReason?.takeIf { it.isNotBlank() },
            reachability = reach,
            followsUsing = followsUsing,
        )
        relayDirectory[u] = detail
        Log.w(TAG, "DETAIL: $u src=${detail.nip11Source} rtt=${rtt}ms reach=${detail.reachability} " +
            "nips=${detail.supportedNips.size} pop=${detail.popularity} follow=${detail.followsUsing} " +
            "geo=${detail.countryCode} trust=${detail.trustScore}")
        return detail
    }

    /** Ephemeral, paginated (A6), VERIFIED (A1) collect of kind-30166 from one transport.
     *  Author-pinned to our trusted monitors; every event is id-hash + Schnorr verified and
     *  author-allowlisted (relays can return anything) before it's returned. Returns
     *  (verified events, verification-failure count). Never touches the connections map. */
    private suspend fun collectDirectory30166(transport: String): Pair<List<NostrEvent>, Int> {
        val url = normalizeRelayUrl(transport) ?: return emptyList<NostrEvent>() to 0
        val collected = mutableListOf<NostrEvent>()
        var verifyFailed = 0
        val conn = relayConnectionFactory.create(url)

        fun reqFor(subId: String, until: Long): String = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(30166)) })
                put("authors", buildJsonArray { DIRECTORY_MONITOR_PUBKEYS.forEach { add(JsonPrimitive(it)) } })
                if (until < Long.MAX_VALUE) put("until", JsonPrimitive(until - 1))
            })
        }.toString()

        try {
            if (!connectTrackedEphemeral(conn)) return collected to verifyFailed
            val state = withTimeoutOrNull(2_000) {
                conn.state.first { it == RelayState.CONNECTED || it == RelayState.FAILED || it == RelayState.DISCONNECTED }
            }
            if (state != RelayState.CONNECTED) return collected to verifyFailed

            // ONE consumeEach for the whole transport — pagination is driven INSIDE the loop by
            // sending the next page's REQ on EOSE. consumeEach cancels the channel when it exits,
            // so a per-page consume (the old bug) killed the socket after page 1 ("Channel was
            // cancelled" on every clean-EOSE relay). Until-cursor windowing; 8-page / 3000-relay cap.
            var page = 0
            var until = Long.MAX_VALUE
            var subId = "dir-${System.nanoTime()}-p0"
            var pageCount = 0
            var pageOldest = Long.MAX_VALUE
            if (!conn.send(reqFor(subId, until))) return collected to verifyFailed

            // 15s per-transport ceiling — full coverage (the richest transport dumps
            // its bulk late). With the concurrent fetch + progressive publish in
            // ensureDirectoryFresh, the SCREEN populates from the fast transports in
            // ~2s; this ceiling only bounds the final fill-in, not perceived load.
            withTimeoutOrNull(15_000) {
                conn.messages.consumeEach { raw ->
                    when {
                        raw.startsWith("[\"EVENT\"") -> {
                            val ev = processor.parseAndVerify(raw, url)
                            when {
                                ev == null -> verifyFailed++   // forged/malformed — dropped + counted (A1)
                                ev.kind == 30166 && ev.pubkey in DIRECTORY_MONITOR_PUBKEYS -> {
                                    collected.add(ev)
                                    pageCount++
                                    if (ev.createdAt < pageOldest) pageOldest = ev.createdAt
                                }
                                // else: authentic but not a trusted monitor — silently ignore (A1)
                            }
                        }
                        raw.startsWith("[\"EOSE\"") && extractEoseSubId(raw) == subId -> {
                            conn.send("""["CLOSE","$subId"]""")
                            page++
                            val done = pageCount == 0 ||
                                pageOldest == Long.MAX_VALUE || pageOldest >= until ||
                                page >= DIRECTORY_MAX_PAGES ||
                                collected.size >= RelayDirectory.MAX_DIRECTORY_ENTRIES
                            if (done) return@withTimeoutOrNull
                            until = pageOldest
                            pageCount = 0
                            pageOldest = Long.MAX_VALUE
                            subId = "dir-${System.nanoTime()}-p$page"
                            conn.send(reqFor(subId, until))   // next page on the SAME channel
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DIRECTORY: collect failed on $url — ${e.message}")
        } finally {
            closeTrackedEphemeral(conn)
        }
        return collected to verifyFailed
    }

    /**
     * Get a live connection to [url], creating one if needed.
     * Bypasses connection cap for control-plane fetches.
     * Replaces stale/dead connections.
     */
    private suspend fun getOrCreateConnection(
        url: String,
        requestClass: RelayRequestClass = RelayRequestClass.GENERAL,
    ): RelayConnection? {
        val normalized = normalizeRelayUrl(url) ?: return null
        if (normalized in blockedUrls ||
            relayCapabilitiesStore.shouldSkipRequest(normalized, requestClass)
        ) {
            return null
        }
        val claim = acquirePooledConnection(normalized, bypassPoolCap = true) ?: return null
        val conn = claim.connection
        if (claim.installed) scope.launch { listenForEvents(conn) }
        return try {
            conn.awaitConnected(timeoutMs = 5_000)
            conn
        } catch (_: Exception) {
            Log.w(TAG, "getOrCreateConnection: $normalized failed to connect")
            null
        }
    }

    /**
     * Fetch kind-0 metadata with row locality first, followed by the existing
     * indexer and author-relay ladder. Empty hints preserve the old ordering.
     */
    fun fetchProfiles(
        pubkeys: List<String>,
        maxRelays: Int = 5,
        relayHintsByPubkey: Map<String, List<String>> = emptyMap(),
    ) {
        if (pubkeys.isEmpty()) return
        val now = System.currentTimeMillis()
        val novel = pubkeys.filter { pk ->
            // Negative-cache: full chain (indexer+fallback) failed for this pk recently
            val negCached = profileFallbackNegCache[pk]
            if (negCached != null && (now - negCached) < PROFILE_FALLBACK_NEG_TTL) return@filter false
            val lastAttempt = profileFetchAttempted[pk]
            lastAttempt == null || (now - lastAttempt) > 120_000 // 2 min TTL
        }
        if (novel.isEmpty()) return
        novel.forEach { profileFetchAttempted[it] = now }

        val hintGroups = groupProfileHintFetches(novel, relayHintsByPubkey)
        val hintedUrls = hintGroups.keys.flatten().toSet()
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
            .mapNotNull(::normalizeRelayUrl)
            .filterNot(hintedUrls::contains)
        val minTargets = minOf(maxRelays, 3)
        val targetUrls = indexerUrls.take(maxRelays).let { indexers ->
            if (indexers.size >= minTargets) indexers
            else {
                val extras = connections.keys
                    .filter { it !in indexers && it !in hintedUrls }
                    .take(minTargets - indexers.size)
                indexers + extras
            }
        }.ifEmpty {
            connections.keys.filterNot(hintedUrls::contains).take(minTargets).toList()
        }

        scope.launch {
            // Phase 1: the relays where the feed rows were actually seen. Groups
            // retain per-pubkey locality instead of broadcasting every author to
            // the union of every row's hints.
            coroutineScope {
                hintGroups.map { (hintUrls, hintedPubkeys) ->
                    async {
                        fetchProfilePhase(
                            pubkeys = hintedPubkeys,
                            targetUrls = hintUrls,
                            subIdPrefix = "hint-profiles",
                            includeActiveFeedRelay = true,
                        )
                    }
                }.awaitAll()
            }
            if (hintGroups.isNotEmpty()) delay(COLD_LANE_FLUSH_MS)

            val mes = memoryEventStore.get()

            // Phase 2: ordinary indexer/global profile fan-out, excluding relays
            // already used by the locality phase.
            val indexerPubkeys = if (hintGroups.isEmpty()) {
                novel
            } else {
                novel.filter { profileMissingPicture(mes.getUserEntity(it)) }
            }
            if (indexerPubkeys.isNotEmpty() && targetUrls.isNotEmpty()) {
                fetchProfilePhase(
                    pubkeys = indexerPubkeys,
                    targetUrls = targetUrls,
                    subIdPrefix = "profiles",
                )
                delay(COLD_LANE_FLUSH_MS)
            }

            // ── Fallback: indexers incomplete → author's own relays (H19b) ─
            val stillIncomplete = novel.filter { profileMissingPicture(mes.getUserEntity(it)) }
            if (stillIncomplete.isEmpty()) return@launch

            val triedRelays = hintedUrls + normalizedRelayTargets(targetUrls)
            val relayToPks = mutableMapOf<String, MutableList<String>>()
            for (pk in stillIncomplete) {
                val candidates = mutableSetOf<String>()
                candidates.addAll(mes.lookupWriteRelaysFor(pk))
                candidates.addAll(mes.relaysSeenForPubkey(pk))
                candidates.removeAll(triedRelays)
                // sendOneShotBatch filters blocked+shouldSkip; pre-filter for accurate grouping
                candidates.removeAll(blockedUrls)
                for (url in candidates.take(4)) {
                    relayToPks.getOrPut(url) { mutableListOf() }.add(pk)
                }
            }
            // Pick relays that cover the most pks, capped
            val fbRelayEntries = relayToPks.entries
                .sortedByDescending { it.value.size }
                .take(MAX_PROFILE_FALLBACK_RELAYS)
            val fbRelayUrls = fbRelayEntries.map { it.key }
            val fbPks = fbRelayEntries.flatMap { it.value }.distinct()

            if (fbPks.isNotEmpty()) {
                Log.d(TAG, "PROFFB: ${fbPks.size} pk(s) → ${fbRelayUrls.size} relay(s): " +
                    fbPks.joinToString(",") { it.take(8) } + " → " +
                    fbRelayUrls.joinToString(",") { it.removePrefix("wss://").removeSuffix("/") })
                fetchProfilePhase(
                    pubkeys = fbPks,
                    targetUrls = fbRelayUrls,
                    subIdPrefix = "prof-fb",
                )
                delay(COLD_LANE_FLUSH_MS)
            } else {
                Log.w(TAG, "PROFFB: ${stillIncomplete.size} pk(s) incomplete — no author relay signal")
            }

            // Final miss tier: one lookup-only bridge relay, deduped against every
            // locality, indexer/global, and author relay already attempted.
            val bridgePks = novel.filter { profileMissingPicture(mes.getUserEntity(it)) }
            val bridgeTargets = bridgeFallbackRelayTargets(triedRelays + fbRelayUrls)
            if (bridgePks.isNotEmpty() && bridgeTargets.isNotEmpty()) {
                fetchProfilePhase(
                    pubkeys = bridgePks,
                    targetUrls = bridgeTargets,
                    subIdPrefix = "bridge-profiles",
                    includeActiveFeedRelay = true,
                )
                delay(COLD_LANE_FLUSH_MS)
            }

            // Negative-cache only after every standard phase and the bridge miss tier.
            val fbNow = System.currentTimeMillis()
            val finalIncomplete = novel.filter { profileMissingPicture(mes.getUserEntity(it)) }
            val resolved = stillIncomplete.size - finalIncomplete.size
            if (resolved > 0) Log.d(TAG, "PROFFB: $resolved pk(s) resolved avatar via fallback")
            finalIncomplete.forEach { profileFallbackNegCache[it] = fbNow }
            if (finalIncomplete.isNotEmpty()) {
                Log.w(TAG, "PROFFB: ${finalIncomplete.size} pk(s) still incomplete after bridge fallback")
            }
        }
        Log.d(
            TAG,
            "Fetching ${novel.size} profiles+relaylists → ${hintedUrls.size} hint + " +
                "${targetUrls.size} indexer relay(s) (${pubkeys.size - novel.size} deduped)",
        )
    }

    private suspend fun fetchProfilePhase(
        pubkeys: List<String>,
        targetUrls: List<String>,
        subIdPrefix: String,
        includeActiveFeedRelay: Boolean = false,
    ) {
        if (pubkeys.isEmpty() || targetUrls.isEmpty()) return
        val subId = "$subIdPrefix-${System.nanoTime()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(10002))
                })
                put("authors", buildJsonArray { pubkeys.forEach { add(JsonPrimitive(it)) } })
            })
        }.toString()
        val eoseDeferred = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = eoseDeferred
        try {
            sendOneShotBatch(
                targetUrls,
                listOf(req),
                listOf(subId),
                includeActiveFeedRelay = includeActiveFeedRelay,
            )
            withTimeoutOrNull(5_000L) { eoseDeferred.await() }
        } finally {
            cleanupOneShotSub(subId)
        }
    }

    /**
     * Fetch profiles using nprofile relay hints. Connects to hinted relays (if not already)
     * and sends targeted kind-0 REQs. Follows the same pattern as [fetchEventById] with hints.
     */
    fun fetchProfilesFromHints(pubkeyHints: Map<String, List<String>>) {
        if (pubkeyHints.isEmpty()) return
        val now = System.currentTimeMillis()
        // Hint attempts use their own guard: querying locality must not suppress
        // the normal indexer/author fallback chain for the same pubkey.
        val novel = pubkeyHints.filter { (pk, _) ->
            val last = hintedProfileFetchAttempted[pk]
            last == null || (now - last) > 300_000
        }
        if (novel.isEmpty()) return

        val pubkeys = novel.keys.toList()
        pubkeys.forEach { hintedProfileFetchAttempted[it] = now }
        val groups = groupProfileHintFetches(pubkeys, novel)
        for ((targetUrls, groupedPubkeys) in groups) {
            val subId = "hint-profiles-${System.nanoTime()}"
            val req = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(subId))
                add(buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(0)) })
                    put("authors", buildJsonArray {
                        groupedPubkeys.forEach { add(JsonPrimitive(it)) }
                    })
                })
            }.toString()
            scope.launch {
                sendOneShotBatch(
                    targetUrls,
                    listOf(req),
                    listOf(subId),
                    includeActiveFeedRelay = true,
                )
            }
        }
        Log.d(TAG, "fetchProfilesFromHints: ${pubkeys.size} profiles → ${groups.size} hint group(s)")
    }

    /**
     * Fetch profiles directly from specific relay URLs (e.g. the source relays of visible events).
     * Bypasses [profileFetchAttempted] dedup so it can run alongside [fetchProfiles].
     * Uses its own lightweight dedup to avoid hammering the same relay within 60 s.
     */
    private val sourceProfileAttempted = ConcurrentHashMap<String, Long>()

    fun fetchProfilesFromSourceRelays(pubkeys: List<String>, relayUrls: List<String>) {
        if (pubkeys.isEmpty() || relayUrls.isEmpty()) return
        val now = System.currentTimeMillis()
        val novel = pubkeys.filter { pk ->
            val last = sourceProfileAttempted[pk]
            last == null || (now - last) > 60_000
        }
        if (novel.isEmpty()) return
        novel.forEach { sourceProfileAttempted[it] = now }

        val subId = "src-profiles-${System.nanoTime()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(0)) })
                put("authors", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
            })
        }.toString()
        val targetUrls = relayUrls.mapNotNull { normalizeRelayUrl(it) }
        scope.launch {
            sendOneShotBatch(
                targetUrls,
                listOf(req),
                listOf(subId),
                includeActiveFeedRelay = true,
            )
        }
        Log.d(TAG, "fetchProfilesFromSourceRelays: ${novel.size} profiles → ${targetUrls.size} source relay(s)")
    }

    /**
     * NIP-50 search: connect to [searchRelayUrls] (if not already) and send a REQ with the
     * "search" field. Results arrive via EventProcessor → MemoryEventStore as with any other subscription.
     *
     * Two filters are sent:
     *  - kind 0 (profiles) — drives the People tab
     *  - kind 1/30023 (notes + articles) — drives the Notes tab
     */
    fun searchNotes(rawSearchRelayUrls: List<String>, query: String, token: Long) {
        if (query.isBlank()) return
        val searchRelayUrls = rawSearchRelayUrls.mapNotNull { normalizeRelayUrl(it) }

        val profileSubId = "search-profiles-$token"
        val notesSubId = "search-notes-$token"
        _activeOneShotSubs.add(profileSubId)
        _activeOneShotSubs.add(notesSubId)

        val profileReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(profileSubId))
            add(buildJsonObject {
                put("kinds",  buildJsonArray { add(JsonPrimitive(0)) })
                put("search", JsonPrimitive(query))
                put("limit",  JsonPrimitive(50))
            })
        }.toString()

        val notesReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(notesSubId))
            add(buildJsonObject {
                put("kinds",  buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(30023)) })
                put("search", JsonPrimitive(query))
                put("limit",  JsonPrimitive(50))
            })
        }.toString()

        for (url in searchRelayUrls) {
            if (relayCapabilitiesStore.shouldSkipRequest(url, RelayRequestClass.NIP50_SEARCH)) continue
            scope.launch {
                val conn = getOrCreateConnection(url, RelayRequestClass.NIP50_SEARCH) ?: return@launch
                conn.send(profileReq)
                conn.send(notesReq)
                Log.d(TAG, "Search REQs sent to $url")
            }
        }
        // Safety-net timeout: unconditionally force-close after 10s.
        // closeSearch is idempotent — harmless if EOSE already closed all relays.
        // Must be unconditional because _activeOneShotSubs.remove fires per-relay
        // on EOSE: 4/5 relays completing removes the sub-ID, but the 5th may still
        // be streaming. The old stillActive check was dead code in that scenario.
        searchTimeoutJobs[token] = scope.launch {
            delay(SEARCH_TIMEOUT_MS)
            Log.d(TAG, "searchNotes: 10s timeout elapsed for token=$token, issuing force-close")
            closeSearch(token)
        }

        Log.d(TAG, "Queued NIP-50 search for \"$query\" to ${searchRelayUrls.size} relay(s) [token=$token]")
    }

    /**
     * Close an active search by sending CLOSE frames for both sub-IDs (search-profiles
     * and search-notes) to every connected relay. Called by SearchViewModel when a new
     * query supersedes the previous one or when the search screen is dismissed.
     */
    fun closeSearch(token: Long) {
        searchTimeoutJobs.remove(token)?.cancel()

        val profileSubId = "search-profiles-$token"
        val notesSubId = "search-notes-$token"
        _activeOneShotSubs.remove(profileSubId)
        _activeOneShotSubs.remove(notesSubId)

        val closeProfile = """["CLOSE","$profileSubId"]"""
        val closeNotes = """["CLOSE","$notesSubId"]"""

        var relayCount = 0
        for (conn in connections.values) {
            if (conn.isConnected) {
                conn.send(closeProfile)
                conn.send(closeNotes)
                relayCount++
            }
        }
        Log.d(TAG, "closeSearch: sent CLOSE for token=$token on $relayCount relay(s)")
    }

    /**
     * Search for notes matching a hashtag via NIP-12 generic tag query (`#t` filter).
     * Sends a single sub (search-notes-{token}) with `{"kinds":[1],"#t":["tag"],"limit":50}`.
     * Results flow through [searchResults] like [searchNotes].
     */
    fun searchHashtag(rawSearchRelayUrls: List<String>, tag: String, token: Long) {
        if (tag.isBlank()) return
        val searchRelayUrls = rawSearchRelayUrls.mapNotNull { normalizeRelayUrl(it) }

        val notesSubId = "search-notes-$token"
        _activeOneShotSubs.add(notesSubId)

        val notesReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(notesSubId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1)) })
                put("#t", buildJsonArray { add(JsonPrimitive(tag.lowercase())) })
                put("limit", JsonPrimitive(50))
            })
        }.toString()

        for (url in searchRelayUrls) {
            if (relayCapabilitiesStore.shouldSkipRequest(url, RelayRequestClass.GENERAL)) continue
            scope.launch {
                val conn = getOrCreateConnection(url, RelayRequestClass.GENERAL) ?: return@launch
                conn.send(notesReq)
                Log.d(TAG, "Hashtag search REQ (#$tag) sent to $url")
            }
        }
        searchTimeoutJobs[token] = scope.launch {
            delay(SEARCH_TIMEOUT_MS)
            closeSearch(token)
        }
        Log.d(TAG, "Queued NIP-12 hashtag search for #$tag to ${searchRelayUrls.size} relay(s) [token=$token]")
    }

    /**
     * Fetch events older than [untilTimestamp] (Unix seconds) from the specified [relayUrls].
     * Used by pagination: caller sets `until` = oldest event's createdAt in the current list.
     */
    fun fetchOlderEvents(relayUrls: List<String>, untilTimestamp: Long) {
        val subId = "older-${System.currentTimeMillis()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(6))
                    add(JsonPrimitive(7))
                    add(JsonPrimitive(20))
                    add(JsonPrimitive(21))
                    add(JsonPrimitive(22))
                    add(JsonPrimitive(1111))
                    add(JsonPrimitive(34235))
                    add(JsonPrimitive(34236))
                    add(JsonPrimitive(1068))
                    add(JsonPrimitive(30023))
                })
                put("until", JsonPrimitive(untilTimestamp))
                put("limit", JsonPrimitive(50))
            })
        }.toString()

        // Fallback when relayUrls is empty (e.g. Following feed): capped subset —
        // skip indexers (no general timeline content) and cap fan-out at 6.
        val targets = if (relayUrls.isEmpty()) {
            val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
                .mapNotNull { normalizeRelayUrl(it) }.toSet()
            connections.values.filter { it.url !in indexerUrls }.take(6)
        } else relayUrls.mapNotNull { connections[it] }
        targets.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "Fetching older events until $untilTimestamp from ${targets.size} relay(s)")
    }

    /**
     * Broadcast a signed event to all currently connected relays.
     *
     * [eventJson] must be the raw JSON object string for the event
     * (not the full ["EVENT", ...] array — this method wraps it).
     */
    /** Batch fetch events by ID. Deduped via in-flight Deferred map + negative cache. */
    fun fetchEventsByIds(eventIds: List<String>) {
        eventIds.distinct().chunked(MAX_EVENT_IDS_PER_REQ).forEach(::fetchEventsByIdsBatch)
    }

    private fun fetchEventsByIdsBatch(eventIds: List<String>) {
        if (eventIds.isEmpty()) return
        // Filter: skip IDs in negative cache or already in-flight
        val novel = mutableListOf<String>()
        for (id in eventIds) {
            if (isEventUnresolved(id)) continue
            if (eventFetchInFlight.containsKey(id)) continue
            val d = CompletableDeferred<NostrEvent?>()
            if (eventFetchInFlight.putIfAbsent(id, d) == null) {
                novel.add(id)
                launchFetchMonitor(id, d)
            }
        }
        if (novel.isEmpty()) return
        trackInFlightPeak()
        val subId = "batch-events-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("ids", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
                put("kinds", buildJsonArray {
                    EVENT_REFERENCE_FETCH_KINDS.forEach { add(JsonPrimitive(it)) }
                })
            })
        }.toString()
        // Broadened relay targeting: non-indexer relays first, then indexer relays
        // for coverage. Previously limited to 3 non-indexer relays which missed
        // events on less-replicated relays.
        // NOTE: do NOT exclude activeSingleRelayFeedUrl — targeted {"ids":[…]} fetches
        // never overlap the feed filter. The target event may live only on the feed relay
        // (bridged reposts). See fetchEventById for the same rationale.
        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
            .mapNotNull { normalizeRelayUrl(it) }.toSet()
        val nonIndexer = connections.values.filter {
            it.url !in indexerUrls && !relayCapabilitiesStore.shouldSkip(it.url)
        }.shuffled()
        val indexer = connections.values.filter {
            it.url in indexerUrls && !relayCapabilitiesStore.shouldSkip(it.url)
        }
        val targets = (nonIndexer + indexer).take(6)
        scheduleBridgeEventFallback(
            eventIds = novel,
            alreadyTriedRelayUrls = targets.map { it.url },
        )
        if (targets.isEmpty()) {
            Log.d(TAG, "fetchEventsByIds: 0 targets (pool empty)")
            return
        }
        targets.forEach { sendOneShotToRelay(it, req) }
        Log.d(TAG, "fetchEventsByIds: ${novel.size} events → ${targets.size} relay(s)")
    }

    /** Single-ID overload — consults MES relay hints before broadcasting. */
    fun fetchEventById(eventId: String) {
        val hints = boundedSeenRelayHints(
            seenRelays = memoryEventStore.get().relayHintsForEvent(eventId),
            browseRelays = activeFeedRelayHintsSnapshot,
        )
        eventIdFetchCoalescer.enqueue(eventId, hints)
    }

    /** Fetch NIP-88 responses only when a poll card is visible. */
    fun fetchPollResponses(
        pollId: String,
        relayUrls: List<String>,
        until: Long? = null,
    ) {
        val targets = relayUrls.mapNotNull { normalizeRelayUrl(it) }
            .filter { it !in blockedUrls && !relayCapabilitiesStore.shouldSkip(it) }
            .distinct()
            .take(6)
        if (targets.isEmpty()) return
        val subId = "poll-responses-${System.nanoTime()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1018)) })
                put("#e", buildJsonArray { add(JsonPrimitive(pollId)) })
                until?.let { put("until", JsonPrimitive(it)) }
                put("limit", JsonPrimitive(500))
            })
        }.toString()
        _activeOneShotSubs.add(subId)

        val pooled = targets.filter { connections.containsKey(it) }
        pooled.forEach { url -> connections[url]?.let { sendOneShotToRelay(it, req) } }
        val ephemeral = targets - pooled.toSet()
        if (ephemeral.isNotEmpty()) {
            scope.launch { sendOneShotBatch(ephemeral, listOf(req), listOf(subId), timeoutMs = 8_000) }
        }
        Log.d(TAG, "fetchPollResponses: $pollId -> ${targets.size} relay(s)")
    }

    /**
     * Fetch events by ID from a SPECIFIC relay (source-relay or hint-relay targeting).
     *
     * Two modes:
     *  - default (`bypassDedup=false`): registers each id in `eventFetchInFlight`,
     *    skips ids already in flight or in the negative cache. Used by source-relay
     *    prefetch where a single fetch is sufficient.
     *  - `bypassDedup=true`: hint-relay retry path. Skips the in-flight guard so the
     *    fetch fires even when a prior broadcast already registered the ids — the
     *    point is to hit the hint relay specifically (which may not have been in the
     *    broadcast's target set). Still respects the negative cache: if a prior
     *    completed batch already determined the id is unresolvable, retrying won't
     *    help.
     *
     * Dispatches via [sendOneShotPooledOrEphemeral]: reuses a pooled connection if
     * one exists, otherwise opens an ephemeral WebSocket (no pool slot, no cap).
     * NEVER connectAndAwait — hint relays are transient and must not squat in the
     * pool (Slice 8: 192-relay hint fan-out exhausted the pool and starved feed).
     *
     * One REQ per call regardless of how many ids are passed — the wire form is
     * `{"ids":[...]}` so a list of N ids is a single subscription, not N.
     */
    suspend fun fetchEventsByIdsFromRelay(
        relayUrl: String,
        eventIds: List<String>,
        bypassDedup: Boolean = false,
    ) {
        if (eventIds.isEmpty()) return
        val normalized = normalizeRelayUrl(relayUrl) ?: return
        if (normalized in blockedUrls) return
        if (relayCapabilitiesStore.shouldSkip(normalized)) return
        // NOTE: do NOT exclude activeSingleRelayFeedUrl — targeted id-fetches
        // never overlap the feed filter (distinct subId, different filter shape).
        // Dedup BEFORE dispatch — don't open any connection (pooled or ephemeral)
        // for ids already in-flight, unresolved, or negative-cached.
        val novel = mutableListOf<String>()
        for (id in eventIds) {
            if (isEventUnresolved(id)) continue
            if (!bypassDedup) {
                if (eventFetchInFlight.containsKey(id)) continue
                val d = CompletableDeferred<NostrEvent?>()
                if (eventFetchInFlight.putIfAbsent(id, d) == null) {
                    novel.add(id)
                    launchFetchMonitor(id, d)
                }
            } else {
                novel.add(id)
            }
        }
        if (novel.isEmpty()) return
        trackInFlightPeak()
        val subId = if (bypassDedup) "hint-batch-${System.nanoTime()}"
                    else "prefetch-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("ids", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
                // Include kinds so relays that require them don't reject with
                // "filters must specify at least one kind" (purplepag.es, others).
                put("kinds", buildJsonArray {
                    EVENT_REFERENCE_FETCH_KINDS.forEach { add(JsonPrimitive(it)) }
                })
            })
        }.toString()
        val pooled = connections.containsKey(normalized)
        sendOneShotPooledOrEphemeral(normalized, req, subId)
        if (bypassDedup) {
            Log.d(TAG, "fetchByIdsFromRelay (hint-batch): ${novel.size} events → $normalized (${if (pooled) "pooled" else "ephemeral"})")
        } else {
            Log.d(TAG, "prefetch: ${novel.size} events → $normalized (${if (pooled) "pooled" else "ephemeral"})")
        }
    }

    /** Queue an ordinary lookup into the 150ms batch window. Explicit retry phases bypass it. */
    suspend fun fetchEventById(eventId: String, relayHints: List<String>, bypassDedup: Boolean = false) {
        if (!bypassDedup) {
            eventIdFetchCoalescer.enqueue(eventId, relayHints).await()
            return
        }
        fetchEventsByIdsWithHints(listOf(eventId), relayHints, bypassDedup = true)
    }

    /** Hints-first wire dispatch for one coalesced group or an explicit bypass retry. */
    internal suspend fun fetchEventsByIdsWithHints(
        eventIds: List<String>,
        relayHints: List<String>,
        bypassDedup: Boolean,
        excludedRelayUrls: Collection<String> = emptyList(),
    ) {
        eventIds.distinct().chunked(MAX_EVENT_IDS_PER_REQ).forEach { chunk ->
            fetchEventsByIdsWithHintsBatch(
                eventIds = chunk,
                relayHints = relayHints,
                bypassDedup = bypassDedup,
                excludedRelayUrls = excludedRelayUrls,
                capToHintBudget = true,
                includeBridgeMissTier = relayHints.isEmpty(),
            )
        }
    }

    /**
     * Repair persisted timeline refs by ID. Persisted refs are stronger evidence
     * than the short-lived negative cache, so this narrow path bypasses that cache
     * while retaining the ordinary in-flight dedup and 50-ID wire bound.
     */
    internal suspend fun fetchTimelineEventsByIds(
        eventIds: List<String>,
        relayHints: List<String>,
    ) {
        val ids = eventIds.distinct().take(MAX_EVENT_IDS_PER_REQ)
        if (ids.isEmpty()) return
        fetchEventsByIdsWithHintsBatch(
            eventIds = ids,
            relayHints = relayHints,
            bypassDedup = false,
            excludedRelayUrls = emptyList(),
            capToHintBudget = true,
            includeBridgeMissTier = relayHints.isEmpty(),
            ignoreNegativeCache = true,
        )
        // Capture after dispatch: the batch registers monitors for novel IDs.
        // An absent deferred now means either the event landed and its monitor
        // removed itself, or there is nothing left for this phase to await.
        val pending = ids.mapNotNull(eventFetchInFlight::get)
        if (pending.isNotEmpty()) {
            withTimeoutOrNull(EVENT_REFERENCE_PHASE_TIMEOUT_MS + EVENT_PROCESSOR_SETTLE_MS) {
                pending.forEach { it.await() }
            }
        }
        val mes = memoryEventStore.get()
        ids.forEach { id ->
            if (mes.getNostrEvent(id) != null) missingRefCache.remove(id)
        }
    }

    /** Explicit own/global ladder phase; unlike seen hints, its established fan-out is not capped to three. */
    internal suspend fun fetchEventsByIdsFromTargets(
        eventIds: List<String>,
        targetRelayUrls: List<String>,
        bypassDedup: Boolean,
        excludedRelayUrls: Collection<String> = emptyList(),
    ) {
        eventIds.distinct().chunked(MAX_EVENT_IDS_PER_REQ).forEach { chunk ->
            fetchEventsByIdsWithHintsBatch(
                eventIds = chunk,
                relayHints = targetRelayUrls,
                bypassDedup = bypassDedup,
                excludedRelayUrls = excludedRelayUrls,
                capToHintBudget = false,
                includeBridgeMissTier = false,
            )
        }
    }

    private suspend fun fetchEventsByIdsWithHintsBatch(
        eventIds: List<String>,
        relayHints: List<String>,
        bypassDedup: Boolean,
        excludedRelayUrls: Collection<String>,
        capToHintBudget: Boolean,
        includeBridgeMissTier: Boolean,
        ignoreNegativeCache: Boolean = false,
    ) {
        val novel = mutableListOf<String>()
        for (eventId in eventIds) {
            if (!ignoreNegativeCache && isEventUnresolved(eventId)) continue
            if (bypassDedup) {
                novel += eventId
                continue
            }
            if (eventFetchInFlight.containsKey(eventId)) continue
            val deferred = CompletableDeferred<NostrEvent?>()
            if (eventFetchInFlight.putIfAbsent(eventId, deferred) == null) {
                novel += eventId
                launchFetchMonitor(eventId, deferred)
            }
        }
        if (novel.isEmpty()) return
        trackInFlightPeak()
        // bypassDedup callers (outbox phases) send REQs without registering in the map.
        // If a monitor is already running for an ID (from the initial broadcast),
        // the event arrival will still complete that Deferred.

        val subId = "hint-batch-${System.nanoTime()}"
        _activeOneShotSubs.add(subId)
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("ids", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
                // Include kinds so relays that require them don't reject with
                // "filters must specify at least one kind" (purplepag.es, others).
                put("kinds", buildJsonArray {
                    EVENT_REFERENCE_FETCH_KINDS.forEach { add(JsonPrimitive(it)) }
                })
            })
        }.toString()

        val indexerUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot()
            .mapNotNull { normalizeRelayUrl(it) }.toSet()
        val excluded = excludedRelayUrls.mapNotNull(::normalizeRelayUrl).toSet()

        val targetUrls = if (relayHints.isNotEmpty()) {
            // Hints-first: dispatch to hint relays via pooled reuse or ephemeral.
            // No connectAndAwait — transient hints must not occupy pool slots.
            // NOTE: do NOT exclude activeSingleRelayFeedUrl — a targeted ids fetch
            // never overlaps the feed filter, and the target may live only there
            // (bridged Ditto reposts). The one-shot sub uses a distinct subId.
            val hintTargets = (if (capToHintBudget) {
                canonicalRelayHints(relayHints)
            } else {
                normalizedRelayTargets(relayHints)
            })
                .filter { it !in excluded && it !in blockedUrls && !relayCapabilitiesStore.shouldSkip(it) }
            if (hintTargets.isNotEmpty()) {
                hintTargets
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        // No usable hints — broadened fallback.
        val resolvedTargets = targetUrls.ifEmpty {
            val nonIndexer = connections.values.filter {
                it.url !in indexerUrls && it.url !in excluded && !relayCapabilitiesStore.shouldSkip(it.url)
            }.shuffled()
            val indexer = connections.values.filter {
                it.url in indexerUrls && it.url !in excluded && !relayCapabilitiesStore.shouldSkip(it.url)
            }
            (nonIndexer + indexer).take(6).map { it.url }
        }
        if (resolvedTargets.isEmpty()) {
            Log.d(TAG, "fetchEventsByIds: ${novel.size} events → 0 targets (pool empty)")
            if (includeBridgeMissTier) {
                fetchEventsByIdsFromBridgeOnMiss(
                    eventIds = novel,
                    alreadyTriedRelayUrls = excluded,
                )
            }
            return
        }
        // A suspending lookup must complete on relay EOSE/timeout, not when its
        // coalesced REQ merely leaves the process. This lets the caller advance
        // from an empty hint phase to outbox/coordinate fallback deterministically.
        val eose = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = eose
        try {
            sendOneShotBatch(
                urls = resolvedTargets,
                reqs = listOf(req),
                subIds = listOf(subId),
                timeoutMs = EVENT_REFERENCE_PHASE_TIMEOUT_MS,
                includeActiveFeedRelay = targetUrls.isNotEmpty(),
            )
            withTimeoutOrNull(EVENT_REFERENCE_PHASE_TIMEOUT_MS) { eose.await() }
            // EVENT messages precede EOSE on the wire but feed/control handlers
            // publish into MES on short batching lanes. Let that insert land before
            // deciding that the established phase missed and querying the bridge.
            delay(EVENT_PROCESSOR_SETTLE_MS)
        } finally {
            cleanupOneShotSub(subId)
        }
        if (includeBridgeMissTier) {
            fetchEventsByIdsFromBridgeOnMiss(
                eventIds = novel,
                alreadyTriedRelayUrls = excluded + resolvedTargets,
            )
        }
        val targetKind = when {
            targetUrls.isEmpty() -> "fallback"
            capToHintBudget -> "hint"
            else -> "targeted"
        }
        Log.d(TAG, "fetchEventsByIds: ${novel.size} events → ${resolvedTargets.size} $targetKind relay(s)")
    }

    private fun scheduleBridgeEventFallback(
        eventIds: List<String>,
        alreadyTriedRelayUrls: Collection<String>,
    ) {
        scope.launch {
            delay(EVENT_REFERENCE_PHASE_TIMEOUT_MS)
            fetchEventsByIdsFromBridgeOnMiss(eventIds, alreadyTriedRelayUrls)
        }
    }

    /**
     * Final lookup-only ID tier. Successful standard lookups make this a no-op;
     * unresolved IDs share one guarded request to the single configured bridge.
     */
    internal suspend fun fetchEventsByIdsFromBridgeOnMiss(
        eventIds: List<String>,
        alreadyTriedRelayUrls: Collection<String> = emptyList(),
    ) {
        val targetUrls = bridgeFallbackRelayTargets(alreadyTriedRelayUrls)
        if (targetUrls.isEmpty()) return
        val mes = memoryEventStore.get()
        val now = System.currentTimeMillis()
        val missing = eventIds.distinct().filter { eventId ->
            if (mes.getEventEntity(eventId) != null) return@filter false
            var admitted = false
            bridgeEventFetchAttempted.compute(eventId) { _, previous ->
                if (previous == null || now - previous >= BRIDGE_EVENT_FETCH_TTL_MS) {
                    admitted = true
                    now
                } else {
                    previous
                }
            }
            admitted
        }
        if (missing.isEmpty()) return
        missing.chunked(MAX_EVENT_IDS_PER_REQ).forEach { chunk ->
            fetchEventsByIdsWithHintsBatch(
                eventIds = chunk,
                relayHints = targetUrls,
                bypassDedup = true,
                excludedRelayUrls = alreadyTriedRelayUrls,
                capToHintBudget = false,
                includeBridgeMissTier = false,
                ignoreNegativeCache = true,
            )
        }
    }

    /**
     * Targeted publish (H20c): send an event to a SPECIFIC relay set — the author's
     * own write relays — rather than broadcasting to every open socket. Two reasons:
     * the broadcast form spammed ~30 bystander relays per note (bandwidth) and leaked
     * note content to relays the user never configured (outbox-model violation + privacy).
     *
     * Pressing Post is explicit user intent, so any target that isn't already open is
     * connected here. [connectAndAwait] does NOT gate on isNetworkDown — a DNS-degraded
     * latch must never refuse a user-initiated publish (H20c: honest attempt beats
     * refused attempt; cf. H18.4b). Only the target relays receive the event; partial
     * success (≥1 relay accepts) is handled by the caller's per-relay OK tracking.
     */
    suspend fun publish(eventJson: String, targetRelays: List<String>) {
        val targets = targetRelays.mapNotNull { normalizeRelayUrl(it) }.distinct()
        if (targets.isEmpty()) {
            Log.w(TAG, "PUBLISH dispatch: no target relays — nothing sent")
            return
        }
        val parsed = NostrJson.parseToJsonElement(eventJson)
        val cmd = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(parsed)
        }.toString()
        val kind = runCatching { parsed.jsonObject["kind"]?.jsonPrimitive?.content }.getOrNull()

        // Connect any target that isn't already open — bypassing the degraded/offline
        // reconnect defer, because this is explicit user intent (H20c).
        val unopened = targets.filter { connections[it]?.isConnected != true }
        if (unopened.isNotEmpty()) {
            Log.w(TAG, "PUBLISH: connecting ${unopened.size} unopened write relay(s): $unopened")
            connectAndAwait(unopened, timeoutMs = 5_000)
        }

        // send() enqueues on OkHttp even while a socket is still handshaking (flushes on
        // open), so a slow-connecting target still receives the event within the caller's
        // OK deadline. false = no live socket → unreachable this attempt.
        var sent = 0
        val unreachable = mutableListOf<String>()
        for (url in targets) {
            val conn = connections[url]
            if (conn != null && conn.send(cmd)) sent++ else unreachable.add(url)
        }
        Log.w(TAG, "PUBLISH dispatch: kind=$kind targets=$targets sent=$sent" +
            if (unreachable.isNotEmpty()) " unreachable=$unreachable" else "")
    }

    /**
     * Register a callback for OK messages for a specific event ID.
     * Callback receives (relayUrl, success, message).
     */
    fun registerPublishCallback(eventId: String, callback: (String, Boolean, String) -> Unit) {
        publishOkCallbacks[eventId] = callback
    }

    /** Unregister a publish OK callback. */
    fun unregisterPublishCallback(eventId: String) {
        publishOkCallbacks.remove(eventId)
    }

    /**
     * Publish an event to specific relay URLs. Connects if not already connected.
     * Used for replaceable events (kind 0, 3, 10002) that target write + indexer relays.
     */
    fun publishToRelays(eventJson: String, rawRelayUrls: List<String>) {
        val relayUrls = rawRelayUrls.mapNotNull { normalizeRelayUrl(it) }
        val cmd = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(NostrJson.parseToJsonElement(eventJson))
        }.toString()

        // Send to already-connected relays immediately
        val sent = mutableSetOf<String>()
        for (url in relayUrls) {
            connections[url]?.let { it.send(cmd); sent.add(url) }
        }

        // Connect to remaining relays and send
        val remaining = relayUrls.filter { it !in sent }
        if (remaining.isNotEmpty()) {
            scope.launch {
                connect(remaining)
                delay(2_000)
                for (url in remaining) {
                    connections[url]?.send(cmd)
                }
            }
        }

        Log.d(TAG, "Published event to ${sent.size} connected + ${remaining.size} pending relay(s)")
    }

    /**
     * Fetch a thread: the event itself, replies, reactions, and zaps.
     * Separate REQs so each kind gets its own limit and they don't compete.
     */
    fun fetchThread(rawRelayUrls: List<String>, eventId: String) {
        val hintUrls = memoryEventStore.get().relayHintsForEvent(eventId)
            .mapNotNull { normalizeRelayUrl(it) }
        val relayUrls = (rawRelayUrls.mapNotNull { normalizeRelayUrl(it) }.toSet() + hintUrls).toList()
        val ts = System.currentTimeMillis()
        _activeOneShotSubs.add("thread-event-$ts")
        _activeOneShotSubs.add("thread-replies-$ts")
        _activeOneShotSubs.add("thread-reactions-$ts")
        _activeOneShotSubs.add("thread-zaps-$ts")

        // The event itself
        val eventReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("thread-event-$ts"))
            add(buildJsonObject {
                put("ids", buildJsonArray { add(JsonPrimitive(eventId)) })
            })
        }.toString()

        // Replies: legacy kind 1 plus NIP-22 comments on event-addressed videos.
        val repliesReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("thread-replies-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(1111))
                })
                put("#e",    buildJsonArray { add(JsonPrimitive(eventId)) })
                put("limit", JsonPrimitive(200))
            })
        }.toString()

        // Reactions on this event and its replies
        val reactionsReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("thread-reactions-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(7)) })
                put("#e",    buildJsonArray { add(JsonPrimitive(eventId)) })
                put("limit", JsonPrimitive(100))
            })
        }.toString()

        // Zaps on this event and its replies
        val zapsReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("thread-zaps-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(9735)) })
                put("#e",    buildJsonArray { add(JsonPrimitive(eventId)) })
                put("limit", JsonPrimitive(100))
            })
        }.toString()

        for (url in relayUrls) {
            connections[url]?.let { conn ->
                sendOneShotToRelay(conn, eventReq)
                sendOneShotToRelay(conn, repliesReq)
                sendOneShotToRelay(conn, reactionsReq)
                sendOneShotToRelay(conn, zapsReq)
            }
        }
        Log.d(TAG, "Fetching thread + engagement for $eventId from ${relayUrls.size} relay(s)")
    }

    /**
     * Fetch comments on a long-form article by its coordinate (`30023:pk:d`).
     * ONE REQ with two OR'd filter objects: NIP-22 kind-1111 (uppercase `#A` root
     * scope) + legacy kind-1 (lowercase `#a`) — NOT `#e`. Ephemeral one-shot
     * (reaches relays even if not in the persistent pool); CLOSE after EOSE.
     */
    suspend fun fetchArticleComments(rawRelayUrls: List<String>, coord: String) {
        if (coord.isBlank()) return
        val hintUrls = memoryEventStore.get().relayHintsForEvent(coord).mapNotNull { normalizeRelayUrl(it) }
        val relayUrls = (rawRelayUrls.mapNotNull { normalizeRelayUrl(it) }.toSet() + hintUrls).toList()
        if (relayUrls.isEmpty()) return
        val subId = "article-comments-${System.currentTimeMillis()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1)) })       // legacy comments
                put("#a", buildJsonArray { add(JsonPrimitive(coord)) })
                put("limit", JsonPrimitive(200))
            })
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1111)) })    // NIP-22 comments
                put("#A", buildJsonArray { add(JsonPrimitive(coord)) })
                put("limit", JsonPrimitive(200))
            })
        }.toString()

        val eoseDeferred = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = eoseDeferred
        sendOneShotBatch(relayUrls, listOf(req), listOf(subId))
        val eosed = withTimeoutOrNull(8_000L) { eoseDeferred.await() } != null
        if (!eosed) cleanupOneShotSub(subId)
        Log.d(TAG, "Fetching comments for $coord from ${relayUrls.size} relay(s)")
    }

    /**
     * Fetch a long-form article by its addressable coordinate (author + d-tag) so a
     * quoted/embedded `naddr` reference can render the canonical article card.
     * One-shot; CLOSE after EOSE.
     */
    suspend fun fetchArticleByCoord(rawRelayUrls: List<String>, author: String, dTag: String) {
        fetchAddressByCoord(rawRelayUrls, kind = 30023, author = author, dTag = dTag)
    }

    /** Fetch one addressable event revision by its NIP-33 coordinate. */
    suspend fun fetchAddressByCoord(
        rawRelayUrls: List<String>,
        kind: Int,
        author: String,
        dTag: String,
    ) {
        if (author.isBlank()) return
        val relayUrls = rawRelayUrls.mapNotNull { normalizeRelayUrl(it) }.distinct()
        if (relayUrls.isEmpty()) return
        val subId = "address-${System.currentTimeMillis()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(kind)) })
                put("authors", buildJsonArray { add(JsonPrimitive(author)) })
                put("#d", buildJsonArray { add(JsonPrimitive(dTag)) })
                put("limit", JsonPrimitive(2))
            })
        }.toString()
        val eoseDeferred = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = eoseDeferred
        try {
            sendOneShotBatch(
                relayUrls,
                listOf(req),
                listOf(subId),
                includeActiveFeedRelay = true,
            )
            withTimeoutOrNull(8_000L) { eoseDeferred.await() }
            delay(EVENT_PROCESSOR_SETTLE_MS)
        } finally {
            cleanupOneShotSub(subId)
        }
    }

    /**
     * Fetch replies to a set of article comments (kind 1 or 1111 referencing the
     * comment via `#e`) — descendants that may carry no `#a`/`#A` article tag and
     * so aren't caught by fetchArticleComments. One-shot; CLOSE after EOSE.
     */
    suspend fun fetchCommentReplies(rawRelayUrls: List<String>, parentIds: List<String>) {
        if (parentIds.isEmpty()) return
        val relayUrls = rawRelayUrls.mapNotNull { normalizeRelayUrl(it) }.distinct()
        if (relayUrls.isEmpty()) return
        val subId = "comment-replies-${System.currentTimeMillis()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(1111)) })
                put("#e", buildJsonArray { parentIds.take(200).forEach { add(JsonPrimitive(it)) } })
                put("limit", JsonPrimitive(200))
            })
        }.toString()
        val eoseDeferred = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = eoseDeferred
        sendOneShotBatch(relayUrls, listOf(req), listOf(subId))
        val eosed = withTimeoutOrNull(8_000L) { eoseDeferred.await() } != null
        if (!eosed) cleanupOneShotSub(subId)
    }

    /**
     * Hydrate direct parents missing from a coordinate-scoped NIP-22 result. Relay
     * indexes can return a child from `#A` while its parent lives only on the source
     * relay. Until this exact-id fetch lands, the UI keeps the orphan visible at root.
     */
    suspend fun fetchCommentParents(rawRelayUrls: List<String>, parentIds: List<String>) {
        if (parentIds.isEmpty()) return
        val relayUrls = rawRelayUrls.mapNotNull { normalizeRelayUrl(it) }.distinct()
        if (relayUrls.isEmpty()) return
        val subId = "comment-parents-${System.currentTimeMillis()}"
        val req = buildCommentParentsReq(subId, parentIds)
        val eoseDeferred = CompletableDeferred<Unit>()
        oneShotEoseCallbacks[subId] = eoseDeferred
        sendOneShotBatch(relayUrls, listOf(req), listOf(subId))
        val eosed = withTimeoutOrNull(8_000L) { eoseDeferred.await() } != null
        if (!eosed) cleanupOneShotSub(subId)
    }

    /**
     * Fetch posts by a single author, including regular and addressable NIP-71 video kinds.
     * One-shot subscription — CLOSE is sent after EOSE.
     */
    fun fetchUserPosts(pubkey: String, relayUrls: List<String> = emptyList()) {
        val ts = System.currentTimeMillis()
        val subIds = listOf("user-posts-$ts", "user-longform-$ts", "user-engagement-$ts")

        // Posts by this author
        val postsReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("user-posts-$ts"))
            add(buildJsonObject {
                // 30023 deliberately absent — user-longform below is the sole
                // kind-30023 path (own limit so kind-1 doesn't crowd articles out).
                put("kinds", buildJsonArray {
                    PROFILE_NOTE_REPLY_EVENT_KINDS.forEach { add(JsonPrimitive(it)) }
                })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                put("limit", JsonPrimitive(200))
            })
        }.toString()

        // Dedicated longform articles subscription (own limit so kind-1 doesn't crowd them out)
        val longformReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("user-longform-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(30023)) })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                put("limit", JsonPrimitive(50))
            })
        }.toString()

        // Reactions and zaps targeting this author (#p tag)
        val engagementReq = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("user-engagement-$ts"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(7)); add(JsonPrimitive(9735)) })
                put("#p", buildJsonArray { add(JsonPrimitive(pubkey)) })
                put("limit", JsonPrimitive(100))
            })
        }.toString()

        val reqs = listOf(postsReq, longformReq, engagementReq)
        val targetUrls = if (relayUrls.isNotEmpty()) {
            relayUrls.mapNotNull { normalizeRelayUrl(it) }
        } else {
            connections.keys.toList()
        }
        scope.launch { sendOneShotBatch(targetUrls, reqs, subIds) }
        Log.d(TAG, "Fetching user posts + engagement for $pubkey → ${targetUrls.size} relay(s)")
    }

    /**
     * Pagination for user profile view.
     * Fetches posts by [pubkey] older than [untilTimestamp].
     * One-shot subscription — closes on EOSE (prefix "older-" matches isOneShotSubscription).
     */
    fun fetchOlderPosts(pubkey: String, untilTimestamp: Long, relayUrls: List<String> = emptyList()) {
        val subId = "older-user-${System.currentTimeMillis()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    PROFILE_NOTE_REPLY_EVENT_KINDS.forEach { add(JsonPrimitive(it)) }
                    add(JsonPrimitive(30023))
                })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                put("until", JsonPrimitive(untilTimestamp))
                put("limit", JsonPrimitive(200))
            })
        }.toString()
        val targetUrls = if (relayUrls.isNotEmpty()) {
            relayUrls.mapNotNull { normalizeRelayUrl(it) }
        } else {
            connections.keys.toList()
        }
        scope.launch { sendOneShotBatch(targetUrls, listOf(req), listOf(subId)) }
        Log.d(TAG, "Fetching older posts for $pubkey until $untilTimestamp → ${targetUrls.size} relay(s)")
    }

    /**
     * Reconnect any relay that has dropped its WebSocket.
     * Called when the app returns to the foreground.
     * Creates new RelayConnection instances (Channel can't be reused after close).
     */
    fun reconnectAll() {
        if (socketTransportSuspended.get()) return
        val dropped = connections.entries
            .filter { it.value.state.value == RelayState.DISCONNECTED ||
                      it.value.state.value == RelayState.FAILED }
            .map { it.key }
            .sortedBy { url -> reconnectPriority(connectionPurposes[url].orEmpty()) }
        for ((index, url) in dropped.withIndex()) {
            scope.launch {
                // Keep a true trickle even for very large relay sets. Capping the
                // delay made every relay after index 25 wake together at 5 s.
                val staggerMs = index * FOREGROUND_RECONNECT_STAGGER_MS
                if (staggerMs > 0L) delay(staggerMs)
                if (!socketTransportSuspended.get()) reconnectWithBackoff(url)
            }
        }
        if (dropped.isNotEmpty()) Log.d(TAG, "Reconnecting ${dropped.size} relay(s) with foreground stagger")
    }

    /**
     * Reconnect a single relay with exponential backoff.
     * Guard: AtomicBoolean per URL prevents concurrent reconnect attempts.
     */
    private fun reconnectWithBackoff(url: String, attempt: Int = reconnectAttempts[url] ?: 0) {
        if (socketTransportSuspended.get()) return
        // Only block reconnect for permanent policy rejections (restricted).
        // Transport strikes heal on successful connection — let the 8-attempt
        // backoff handle transient failures without the strike system killing it.
        val caps = relayCapabilitiesStore.get(url)
        if (caps?.restricted == true) {
            Log.w(TAG, "Skipping reconnect for restricted relay $url")
            reconnectAttempts.remove(url)
            removePooledConnection(url)?.close()
            return
        }
        // Don't hammer a dead pipe — defer until the network recovers.
        if (relayCapabilitiesStore.isNetworkDown) {
            pendingReconnect.add(url)
            Log.w(TAG, "reconnectWithBackoff: network down, deferring $url (${pendingReconnect.size} pending)")
            return
        }
        val guard = reconnecting.getOrPut(url) { AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) return
        reconnectAttempts[url] = attempt

        scope.launch {
            try {
                if (attempt > 0) {
                    val delayMs = minOf(1000L * (1L shl minOf(attempt - 1, 4)), 30_000L)
                    Log.d(TAG, "Backoff $url: attempt $attempt, delay ${delayMs}ms")
                    delay(delayMs)
                }

                if (socketTransportSuspended.get()) {
                    guard.set(false)
                    return@launch
                }

                // Re-check after delay — network may have gone down during backoff
                if (relayCapabilitiesStore.isNetworkDown) {
                    pendingReconnect.add(url)
                    guard.set(false)
                    Log.w(TAG, "reconnectWithBackoff: network down after delay, deferring $url")
                    return@launch
                }

                // Another foreground path (for example the warm-relay recheck)
                // may already have restored this URL while our stagger/backoff
                // elapsed. Never replace a healthy or in-flight handshake.
                val current = connections[url]
                if (current?.state?.value == RelayState.CONNECTED ||
                    current?.state?.value == RelayState.CONNECTING
                ) {
                    guard.set(false)
                    return@launch
                }

                val claim = acquirePooledConnection(
                    url = url,
                    bypassPoolCap = true,
                    resetAuth = true,
                )
                if (claim == null) {
                    guard.set(false)
                    return@launch
                }
                if (!claim.installed) {
                    // Another foreground path won ownership while this backoff
                    // was sleeping. It owns the listener and replay signal.
                    guard.set(false)
                    return@launch
                }
                val conn = claim.connection

                // Wait briefly for connection to establish
                var waited = 0
                while (conn.state.value == RelayState.CONNECTING && waited < 5000) {
                    delay(100)
                    waited += 100
                }

                if (conn.state.value == RelayState.CONNECTED) {
                    guard.set(false)
                    // A WebSocket open is not proof of recovery. Preserve/increase the
                    // backoff if it flaps, and reset only after a healthy connection window.
                    reconnectAttempts[url] = (attempt + 1).coerceAtMost(8)
                    updateConnectionStates()
                    _onRelayReconnected.tryEmit(url)
                    // Resend persistent own-mute-live subscription if this relay carries it
                    if (url in liveMuteSubRelays) {
                        liveMuteSubReq?.let { conn.send(it) }
                    }
                    // Resend persistent notification subscription if this relay carries it
                    if (url in liveNotifSubRelays) {
                        liveNotifSubReq?.let { conn.send(it) }
                    }
                    scope.launch { listenForEvents(conn) }
                    scope.launch {
                        delay(RECONNECT_HEALTHY_WINDOW_MS)
                        if (connections[url] === conn && conn.state.value == RelayState.CONNECTED) {
                            reconnectAttempts.remove(url)
                            Log.d(TAG, "Reconnect backoff reset after healthy window: $url")
                        }
                    }
                    Log.d(TAG, "Reconnected $url (attempt=$attempt)")
                } else {
                    guard.set(false)
                    if (attempt < 8) {
                        reconnectWithBackoff(url, attempt + 1)
                    } else {
                        Log.w(TAG, "Giving up reconnection to $url after $attempt attempts")
                    }
                }
            } catch (e: Exception) {
                guard.set(false)
                Log.w(TAG, "Reconnect failed for $url: ${e.message}")
                if (attempt < 8) {
                    reconnectWithBackoff(url, attempt + 1)
                }
            }
        }
    }

    /**
     * NIP-42: Sign and send an AUTH response for the given relay challenge.
     * A fresh challenge supersedes any prior auth — the relay is the authority
     * on our auth state, not our local set.
     */
    private fun handleAuthChallenge(conn: RelayConnection, challenge: String) {
        val url = conn.url
        when (
            authSessionPolicy.evaluateEligibility(
                url = url,
                configuredUrls = configuredAuthRelayUrlsSnapshot(),
                unavailableRelays = authUnavailableRelays,
                rejectionStreak = authRejectionStreak[url] ?: 0,
            )
        ) {
            RelayAuthAdmission.INELIGIBLE -> {
                authenticatedRelays.remove(url)
                optimisticAuthUsed.remove(url)
                authInFlight.remove(url)
                pendingAuthEventIds.values.removeAll { it == url }
                _relayAuthUnavailable.tryEmit(url)
                Log.w(TAG, "AUTH: auth-ineligible relay $url — refusing identity signature")
                return
            }
            RelayAuthAdmission.UNAVAILABLE -> {
                Log.d(TAG, "AUTH: $url marked unavailable, not retrying")
                return
            }
            RelayAuthAdmission.READY -> Unit
            RelayAuthAdmission.ATTEMPT_LIMIT -> error("Attempt budget is checked after dedup")
        }

        val previousChallenge = pendingChallenges.put(url, challenge)

        // Some relays repeat the same connection-scoped challenge for every
        // REQ. Once that exact challenge has completed, signing it again adds
        // no authority and only burns signer/CPU/radio work.
        if (previousChallenge == challenge && url in authenticatedRelays) {
            Log.d(TAG, "AUTH: duplicate completed challenge from $url — ignoring")
            return
        }

        // A fresh challenge supersedes any prior (possibly optimistic) auth.
        if (url in authenticatedRelays) {
            if (optimisticAuthUsed.remove(url)) {
                val streak = authRejectionStreak.merge(url, 1, Int::plus) ?: 1
                Log.w(TAG, "AUTH: $url re-challenged after optimistic completion (streak=$streak)")
            }
            Log.w(TAG, "AUTH: re-challenged by $url — clearing stale auth, re-authenticating")
            authenticatedRelays.remove(url)
        }

        if (!authInFlight.add(url)) {
            Log.w(TAG, "AUTH: already in flight for $url, skipping")
            return
        }

        if (authSessionPolicy.reserveAttempt(url, authUnavailableRelays) == RelayAuthAdmission.ATTEMPT_LIMIT) {
            authInFlight.remove(url)
            pendingAuthEventIds.values.removeAll { it == url }
            _relayAuthUnavailable.tryEmit(url)
            Log.w(
                TAG,
                "AUTH: per-session attempt cap reached for $url " +
                    "($MAX_RELAY_AUTH_ATTEMPTS_PER_SESSION) — refusing identity signature",
            )
            return
        }

        scope.launch {
            try {
                // Use Quartz's normalizer — it adds trailing slash for root-path
                // relays (wss://host/ not wss://host), matching how relays
                // validate the relay tag in NIP-42 auth events.
                val normalizedUrl = RelayUrlNormalizer.normalize(url)
                val template = RelayAuthEvent.build(normalizedUrl, challenge)
                val signed = signingManager.sign(template)

                if (signed == null) {
                    Log.w(TAG, "AUTH: signing failed for $url (signer returned null)")
                    authInFlight.remove(url)
                    return@launch
                }

                val authJson = """["AUTH",${signed.toJson()}]"""
                val sent = conn.send(authJson)

                if (sent) {
                    pendingAuthEventIds[signed.id] = url
                    Log.w(TAG, "AUTH: sent auth response to $url (eventId=${signed.id.take(8)}…)")

                    // Optimistic fallback ONLY for relays with a clean record.
                    // Once a relay has rejected us (streak > 0), require a real OK.
                    val streak = authRejectionStreak[url] ?: 0
                    if (streak == 0) {
                        scope.launch {
                            delay(10_000)
                            if (pendingAuthEventIds.remove(signed.id) != null) {
                                Log.w(TAG, "AUTH: OK timeout for $url — optimistic auth (clean record)")
                                optimisticAuthUsed.add(url)
                                completeAuth(conn, url, real = false)
                            }
                        }
                    } else {
                        // No optimism — require real OK. Clean up after grace period.
                        scope.launch {
                            delay(10_000)
                            if (pendingAuthEventIds.remove(signed.id) != null) {
                                authInFlight.remove(url)
                                val newStreak = authRejectionStreak.merge(url, 1, Int::plus) ?: 1
                                if (newStreak >= MAX_AUTH_NO_OK_STREAK) {
                                    authUnavailableRelays.add(url)
                                    _relayAuthUnavailable.tryEmit(url)
                                    Log.w(TAG, "AUTH: no OK for $url (streak=$newStreak) — marking unavailable")
                                } else {
                                    Log.w(TAG, "AUTH: no OK for $url (streak=$newStreak), not optimistic")
                                }
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "AUTH: failed to send auth to $url (connection closed?)")
                    authInFlight.remove(url)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AUTH: error authenticating to $url", e)
                authInFlight.remove(url)
            }
        }
    }

    /**
     * Challenge-time snapshot: configuration is intentionally read live so a
     * relay added during the session can become eligible without recreating the
     * pool. Hint/follow-pack/WoT relays never enter this set implicitly.
     */
    private fun configuredAuthRelayUrlsSnapshot(): Set<String> {
        val ownPubkey = keyManager.getPublicKeyHex()
        val store = ownPubkey?.let { memoryEventStore.get() }
        return configuredAuthRelayUrls(
            integralRelayUrls = integralRelayUrls,
            indexerRelayUrls = relayPreferencesStore.get().indexerRelayUrlsSnapshot(),
            ownReadRelayUrls = ownPubkey?.let { store?.readRelaysFor(it) }.orEmpty(),
            ownWriteRelayUrls = ownPubkey?.let { store?.writeRelaysFor(it) }.orEmpty(),
            ownSearchRelayUrls = ownPubkey?.let { store?.getSearchRelayUrls(it) }.orEmpty(),
        )
    }

    /**
     * Handle ["OK", "<event-id>", <success>, "<message>"] from relay.
     * Used for NIP-42 auth confirmation.
     */
    private fun handleOk(conn: RelayConnection, raw: String) {
        try {
            val arr = NostrJson.parseToJsonElement(raw).jsonArray
            val eventId = arr[1].jsonPrimitive.content
            val success = arr[2].jsonPrimitive.boolean
            val message = arr.getOrNull(3)?.jsonPrimitive?.content ?: ""

            // Notify publish tracker if registered
            publishOkCallbacks[eventId]?.invoke(conn.url, success, message)

            // Check if this OK is for a pending auth event
            val url = pendingAuthEventIds.remove(eventId) ?: return
            if (success) {
                Log.w(TAG, "AUTH OK: relay $url accepted auth (eventId=${eventId.take(8)}…)")
                completeAuth(conn, url, real = true)
            } else {
                Log.w(TAG, "AUTH REJECTED: relay $url rejected auth: $message")
                authInFlight.remove(url)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse OK message from ${conn.url}: ${raw.take(100)}", e)
        }
    }

    /**
     * Mark relay as authenticated and replay all live subs.
     * @param real true when confirmed by relay OK; false for optimistic timeout.
     *             Real OK resets the rejection streak; optimistic does not.
     */
    private fun completeAuth(conn: RelayConnection, url: String, real: Boolean) {
        authenticatedRelays.add(url)
        authInFlight.remove(url)
        if (real) {
            authRejectionStreak.remove(url)
            optimisticAuthUsed.remove(url)
        }
        _onRelayReconnected.tryEmit(url)
        Log.w(TAG, "AUTH: completed for $url (real=$real) — notified subscribers")
    }

    // ── Reconnect signal ──────────────────────────────────────────────────

    /** Emitted when a relay reconnects or completes auth. Multiple subscribers
     *  (RelayBrowseSession, Subscription) collect this to replay their subs. */
    private val _onRelayReconnected = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val onRelayReconnected: SharedFlow<String> = _onRelayReconnected.asSharedFlow()

    /** Send a message to a specific relay by URL. Returns false if the connection doesn't exist. */
    override fun sendToRelay(url: String, msg: String): Boolean {
        if (socketTransportSuspended.get()) return false
        val normalized = normalizeRelayUrl(url) ?: return false
        return connections[normalized]?.send(msg) == true
    }

    /**
     * Placeholder for future disconnect logic. In this PR browse CLOSE is enough;
     * connections may be reused by outbox routing or other consumers.
     */
    fun releaseIfUnused(url: String) {
        val purposes = connectionPurposes[url]?.toSet().orEmpty()
        val activeSubUrls = activeSubUrlsForCleanup() ?: return
        val hasActiveSubscription = url in activeSubUrls
        val lastActivity = connectionLastActivity[url] ?: 0L
        if (!shouldReleaseRelayConnection(
                purposes = purposes,
                hasActiveSubscription = hasActiveSubscription,
                lastActivityMs = lastActivity,
                nowMs = System.currentTimeMillis(),
            )
        ) return
        // Map-before-close: remove first so listenForEvents.finally sees identity mismatch
        val conn = removePooledConnection(url, clearPurposes = true) ?: return
        conn.close()
        Log.d(TAG, "Released unused connection: $url")
    }

    fun disconnectAll() {
        // Map-before-close: snapshot then clear under the same lock used by
        // send/install/remove so no one-shot can attach during teardown.
        val (snapshot, ephemeralSnapshot) = synchronized(socketLifecycleLock) {
            val pooled = ArrayList(connections.values)
            val ephemeral = ArrayList(activeEphemeralConnections)
            connections.clear()
            connectionPurposes.clear()
            profileFetchAttempted.clear()
            hintedProfileFetchAttempted.clear()
            bridgeEventFetchAttempted.clear()
            // Complete all in-flight monitors so they clean up immediately
            eventFetchInFlight.values.forEach { it.complete(null) }
            eventFetchInFlight.clear()
            missingRefCache.clear()
            authenticatedRelays.clear()
            authInFlight.clear()
            pendingChallenges.clear()
            authFailedRelays.clear()
            pendingAuthEventIds.clear()
            authRejectionStreak.clear()
            optimisticAuthUsed.clear()
            authUnavailableRelays.clear()
            authSessionPolicy.clear()
            integralRelayUrls = emptySet()
            relayOneShotOwners.clear()
            relayOneShotCount.clear()
            relayReqQueue.clear()
            connectionLastActivity.clear()
            pooled to ephemeral
        }
        // Close after all maps are cleared
        snapshot.forEach { it.close() }
        ephemeralSnapshot.forEach { it.close() }
        Log.d(TAG, "disconnectAll: all pooled/ephemeral connections, purposes, and auth state cleared")
    }
}
