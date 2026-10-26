package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.NostrEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Subscription"
private const val EOSE_QUIESCENCE_MS = 2_500L   // silence after ≥1 event ⇒ backfill done ⇒ flip live
private const val EOSE_CEILING_MS    = 30_000L  // relay accepted REQ but sent nothing at all
private const val EOSE_POLL_MS       = 1_250L  // coarse — quiescence needs 2.5s of silence; finer polling is wasted wakeups
private const val RELAY_REPLAY_DEBOUNCE_MS = 1_000L

/**
 * Low-level Nostr REQ subscription primitive.
 *
 * Wraps [RelayTransport] (for sending) and [TapRegistration] (for receiving)
 * to provide a callback-oriented subscribe API. Mirrors Jumble's
 * client.subscribe() in client.service.ts:422.
 *
 * Lifecycle:
 *   1. subscribe(urls, filter, callbacks) — generates subId, ensures
 *      connections, sends REQ to each relay, returns Handle.
 *   2. Per-relay events arrive via the registered tap. Demuxed by subId.
 *      Cross-relay dedup via knownIds. Forwarded to onevent.
 *   3. Per-relay EOSE arrives via the tap. eosedCount incremented.
 *      oneose(allEosed) fires each time a relay completes; the boolean
 *      indicates whether ALL relays have EOSE'd.
 *   4. Per-relay CLOSED arrives via the tap. onclose fires with the reason.
 *      auth-required reasons are passed through unmodified — caller decides
 *      what to do (NIP-42 auth handling lives in callers, not here).
 *   5. Handle.close() sends CLOSE to each relay and unregisters the
 *      subscription. Subsequent messages for this subId are dropped.
 *   6. pauseAll() sends CLOSE to every relay for all active subs without
 *      removing them. resumeAll() replays with optional per-relay filters. Used by
 *      ProcessLifecycleOwner to stop event flow when backgrounded.
 *
 * On reconnect, the stored REQ or an optional provider filter is replayed for that relay.
 * Consumers with EOSE-sensitive state receive [onReplayCycleStarted] before the new cycle.
 *
 * Thread safety: subscribe() is suspend (network I/O). Callbacks fire on
 * EventProcessor's bounded Default dispatcher. Callbacks must not block.
 */
interface ReconnectSource {
    val onRelayReconnected: SharedFlow<String>
}

enum class RelayCompletionReason { Genuine, Closed, Watchdog, Skipped }

/** Wire-page statistics, before cross-relay/lifetime deduplication. */
data class RelayCompletion(
    val url: String,
    val reason: RelayCompletionReason,
    val count: Int,
    val oldest: Long?,
    val newest: Long?,
)

@Singleton
class Subscription @Inject constructor(
    private val transport: RelayTransport,
    private val tapRegistration: TapRegistration,
    private val reconnectSource: ReconnectSource,
    private val relayCapabilitiesStore: RelaySkipCheck,
    private val trafficMetrics: RelayTrafficMetrics = RelayTrafficMetrics(),
) : ActiveSubsSource {
    internal var elapsedRealtime: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    internal var watchdogDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default

    private class RelayPage {
        var count = 0
        var oldest: Long? = null
        var newest: Long? = null
        @Volatile var reason: RelayCompletionReason? = null
    }

    /** Active subscription state, keyed by subId. */
    private data class SubState(
        val urls: Set<String>,
        val reqPayload: String,
        val replayFilterProvider: ((String) -> NostrFilter)?,
        val requestClass: RelayRequestClass,
        val onevent: (NostrEvent) -> Unit,
        val oneose: (allEosed: Boolean) -> Unit,
        val onReplayCycleStarted: (Set<String>) -> Unit,
        val onclose: (url: String, reason: String) -> Unit,
        val onRelayCompletion: (RelayCompletion) -> Unit,
        val replayable: Boolean,
        val pages: ConcurrentHashMap<String, RelayPage> = ConcurrentHashMap(),
        val knownIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val eosedRelays: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val closedRelays: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        @Volatile var isPaused: Boolean = false,
        @Volatile var lastEventAt: Long = 0L,   // SystemClock.elapsedRealtime() of most recent event
    )

    private val subs = ConcurrentHashMap<String, SubState>()

    /**
     * URLs of relays with at least one non-paused active subscription.
     * Consulted by RelayPool's sweep to avoid force-closing connections
     * that subscriptions still need.
     */
    override fun activeRelayUrls(): Set<String> =
        subs.values
            .filterNot { it.isPaused }
            .flatMap { it.urls }
            .toSet()

    override fun activeSubIds(): Set<String> =
        subs.entries
            .filterNot { (_, s) -> s.isPaused }
            .map { it.key }
            .toSet()

    private val seqCounter = AtomicLong(0)
    private val watchdogScopes = ConcurrentHashMap<String, CoroutineScope>()
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastReplayAt = ConcurrentHashMap<String, Long>()
    private val rateLimitSkipLogged: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val replayEpochs = ConcurrentHashMap<String, AtomicLong>()
    private val catchUpRequestCount = AtomicLong()

    /** Accepted catch-up REQs per metrics interval, not event deliveries. */
    internal fun snapshotCatchUpRequestCount(): Long = catchUpRequestCount.getAndSet(0)

    internal fun replayEpoch(url: String): Long = replayEpochs[url]?.get() ?: 0L

    internal fun canCatchUp(url: String): Boolean =
        subs.values.any { it.replayable && !it.isPaused && url in it.urls } &&
            canRequestHistory(url)

    internal fun canRequestHistory(url: String): Boolean =
        !transport.isRateLimited(url) && !transport.isAuthUnavailable(url) &&
            !relayCapabilitiesStore.shouldSkipRequest(url, RelayRequestClass.GENERAL)

    init {
        // Replay persistent subs when a relay reconnects.
        reconnectScope.launch {
            reconnectSource.onRelayReconnected.collect { url -> resumeRelay(url) }
        }
    }

    /**
     * The single tap registered with [TapRegistration]. Demuxes by subId,
     * dispatches to per-sub callbacks. Registered lazily on first subscribe.
     */
    private val tap = RelayMessageTap { message ->
        dispatchMessage(message)
    }
    private var tapRegistered = false
    private val tapLock = Any()

    /**
     * Subscribe to [urls] with [filter]. Caller-provided callbacks fire on
     * incoming events / EOSEs / replay-cycle starts / closes for this subId.
     *
     * Returns a [Handle] — call close() to terminate. Idempotent close.
     */
    suspend fun subscribe(
        urls: List<String>,
        filter: NostrFilter,
        onevent: (NostrEvent) -> Unit,
        oneose: (allEosed: Boolean) -> Unit = {},
        onReplayCycleStarted: (Set<String>) -> Unit = {},
        onclose: (url: String, reason: String) -> Unit = { _, _ -> },
        onRelayCompletion: (RelayCompletion) -> Unit = {},
        replayable: Boolean = true,
        retryFailedSends: Boolean = true,
        replayFilterProvider: ((url: String) -> NostrFilter)? = null,
    ): Handle {
        ensureTapRegistered()

        val urlSet = urls.mapNotNull { normalizeRelayUrl(it) }.toSet()
        val subId = generateSubId(urls)
        val req = buildReqJson(subId, filter)
        val requestClass = if (filter.search.isNullOrBlank()) {
            RelayRequestClass.GENERAL
        } else {
            RelayRequestClass.NIP50_SEARCH
        }
        val state = SubState(
            urls = urlSet,
            reqPayload = req,
            replayFilterProvider = replayFilterProvider,
            requestClass = requestClass,
            onevent = onevent,
            oneose = oneose,
            onReplayCycleStarted = onReplayCycleStarted,
            onclose = onclose,
            onRelayCompletion = onRelayCompletion,
            replayable = replayable,
        )
        subs[subId] = state
        Log.d(TAG, "SUB-OPEN: $subId to ${urls.size} relays, filter=${filter.toJsonObject()}")

        try {
            // Connection establishment is the caller's responsibility for
            // PERSISTENT-purpose relays (browse / outbox / home feed). For
            // ad-hoc one-off subs we still call connectAndAwait — it's a no-op
            // for already-connected relays.
            val dispatchUrls = urlSet.filterNot { url ->
                relayCapabilitiesStore.shouldSkipRequest(url, requestClass)
            }
            transport.connectAndAwait(
                dispatchUrls,
                timeoutMs = 5_000,
                requestClass = requestClass,
            )

            // Send REQ to each relay, skipping those with known structural rejections.
            val failedUrls = mutableListOf<String>()
            var skippedCount = 0
            for (url in urlSet) {
                if (relayCapabilitiesStore.shouldSkipRequest(url, requestClass)) {
                    skippedCount++
                    Log.w(TAG, "SUB-SKIP: $subId skipping $url (cap store)")
                    handleRelayEose(subId, url, RelayCompletionReason.Skipped)
                    continue
                }
                if (transport.isAuthUnavailable(url)) {
                    skippedCount++
                    Log.w(TAG, "SUB-SKIP: $subId skipping $url (auth unavailable)")
                    handleRelayEose(subId, url, RelayCompletionReason.Skipped)
                    continue
                }
                if (!transport.sendToRelay(url, req)) {
                    failedUrls.add(url)
                } else if (!replayable) {
                    catchUpRequestCount.incrementAndGet()
                }
            }
            if (skippedCount > 0) {
                Log.w(TAG, "SUB-DISPATCH: $subId skipped $skippedCount/${urls.size} relays due to capabilities")
            }

            // Retry failed sends — connections may still be establishing after
            // connectAndAwait returned (it waits for ANY 1, not all). Poll up
            // to 10s for remaining connections to come up.
            if (failedUrls.isNotEmpty() && retryFailedSends) {
                Log.w(TAG, "subscribe $subId: ${failedUrls.size}/${urls.size} sends failed, retrying up to 10s")
                val retryDeadline = System.currentTimeMillis() + 10_000L
                val remaining = failedUrls.toMutableList()
                while (remaining.isNotEmpty() && System.currentTimeMillis() < retryDeadline) {
                    delay(500)
                    val s = subs[subId]
                    if (s == null || s.isPaused) return HandleImpl(subId) // closed or paused during retry
                    val iter = remaining.iterator()
                    while (iter.hasNext()) {
                        val url = iter.next()
                        if (relayCapabilitiesStore.shouldSkipRequest(url, requestClass)) {
                            iter.remove()
                            handleRelayEose(subId, url, RelayCompletionReason.Skipped)
                        } else if (transport.sendToRelay(url, req)) {
                            iter.remove()
                        }
                    }
                }
                if (remaining.isNotEmpty()) {
                    Log.w(TAG, "subscribe $subId: ${remaining.size} relays unreachable after retry")
                }
                for (url in remaining) {
                    handleRelayEose(subId, url, RelayCompletionReason.Skipped)
                }
            } else if (!retryFailedSends) {
                for (url in failedUrls) handleRelayEose(subId, url, RelayCompletionReason.Skipped)
            }
        } catch (e: Exception) {
            subs.remove(subId)
            for (url in urlSet) transport.sendToRelay(url, buildCloseJson(subId))
            throw e
        }

        // Per-relay EOSE watchdog: if a relay accepts REQ but never sends
        // EOSE within 30s, synthesize one so callers don't hang.
        // MUST iterate urlSet (normalized), NOT raw urls — eosedRelays uses
        // normalized URLs from dispatchMessage. Raw URLs with trailing slashes
        // never match, causing the watchdog to always fire as a false alarm.
        startEoseWatchdog(subId, urlSet)

        return HandleImpl(subId)
    }

    // ── Lifecycle pause / resume ────────────────────────────────────────────

    /**
     * Pause all active subscriptions by sending CLOSE to every relay.
     * Subscriptions stay in the [subs] map with [SubState.isPaused] = true;
     * in-flight events are dropped by the dispatch guard. Called from
     * ProcessLifecycleOwner.onStop when app is backgrounded.
     */
    fun pauseAll() {
        var count = 0
        for ((subId, state) in subs) {
            if (state.isPaused) continue
            state.isPaused = true
            val closeMsg = buildCloseJson(subId)
            for (url in state.urls) {
                transport.sendToRelay(url, closeMsg)
            }
            if (!state.replayable) {
                subs.remove(subId, state)
                watchdogScopes.remove(subId)?.cancel()
            }
            count++
        }
        if (count > 0) Log.d(TAG, "pauseAll: paused $count subs")
    }

    /**
     * Resume paused subscriptions with provider filters or their original REQ payloads.
     * Resets EOSE/CLOSED tracking for a fresh cycle. [SubState.knownIds]
     * preserved for cross-relay dedup continuity. Called from
     * ProcessLifecycleOwner.onStart when app is foregrounded.
     */
    fun resumeAll() {
        var count = 0
        subs.values.filter { it.isPaused }.flatMap { it.urls }.toSet().forEach {
            replayEpochs.computeIfAbsent(it) { AtomicLong() }.incrementAndGet()
        }
        for ((subId, state) in subs) {
            if (!state.isPaused) continue
            try {
                state.onReplayCycleStarted(state.urls)
            } catch (t: Throwable) {
                Log.w(TAG, "onReplayCycleStarted threw", t)
            }
            state.eosedRelays.clear()
            state.closedRelays.clear()
            state.pages.clear()
            state.lastEventAt = 0L
            state.isPaused = false
            for (url in state.urls) {
                if (!relayCapabilitiesStore.shouldSkipRequest(url, state.requestClass)) {
                    if (transport.sendToRelay(url, replayPayload(subId, state, url))) trafficMetrics.replayReqAccepted()
                } else {
                    handleRelayEose(subId, url, RelayCompletionReason.Skipped)
                }
            }
            startEoseWatchdog(subId, state.urls)
            count++
        }
        if (count > 0) Log.d(TAG, "resumeAll: resumed $count subs")
    }

    /**
     * Replay active (non-paused) subscriptions to a single relay that just
     * reconnected. Resets that relay's EOSE/CLOSED tracking so the fresh
     * connection gets a clean cycle. Called from the [relayPool] reconnect flow.
     */
    fun resumeRelay(url: String, nowMs: Long = System.currentTimeMillis()) {
        val normalized = normalizeRelayUrl(url) ?: return
        if (transport.isAuthUnavailable(normalized)) return
        if (transport.isRateLimited(normalized)) {
            if (rateLimitSkipLogged.add(normalized)) {
                Log.w(TAG, "resumeRelay $normalized: skipped during rate-limit cooldown")
            }
            return
        }
        rateLimitSkipLogged.remove(normalized)

        val matching = subs.entries.filter { (_, state) ->
            !state.isPaused &&
                state.replayable &&
                normalized in state.urls &&
                !relayCapabilitiesStore.shouldSkipRequest(normalized, state.requestClass)
        }
        if (matching.isEmpty()) {
            Log.d(TAG, "resumeRelay $normalized: 0 subs matched (total subs=${subs.size})")
            return
        }
        if (!claimReplayWindow(normalized, nowMs)) return
        if (matching.any { (_, state) -> state.eosedRelays.size >= state.urls.size }) {
            replayEpochs.computeIfAbsent(normalized) { AtomicLong() }.incrementAndGet()
        }

        var count = 0
        for ((subId, state) in matching) {
            val startsNewCycle = state.eosedRelays.size >= state.urls.size
            if (startsNewCycle) {
                state.lastEventAt = 0L
                try {
                    state.onReplayCycleStarted(setOf(normalized))
                } catch (t: Throwable) {
                    Log.w(TAG, "onReplayCycleStarted threw for $normalized", t)
                }
            }
            state.eosedRelays.remove(normalized)
            state.closedRelays.remove(normalized)
            state.pages.remove(normalized)
            if (transport.sendToRelay(normalized, replayPayload(subId, state, normalized))) {
                trafficMetrics.replayReqAccepted()
                startRelayEoseWatchdog(subId, normalized)
                count++
            } else {
                // Restore the completed state if the socket disappeared between
                // the reconnect signal and replay dispatch.
                handleRelayEose(subId, normalized, RelayCompletionReason.Skipped)
            }
        }
        Log.d(TAG, "resumeRelay $normalized: replayed $count sub(s)")
    }

    /** Resolve only an actual replay attempt; initial dispatch and retries keep reqPayload. */
    private fun replayPayload(subId: String, state: SubState, url: String): String {
        val provider = state.replayFilterProvider ?: return state.reqPayload
        return try {
            buildReqJson(subId, provider(url))
        } catch (_: Exception) {
            // No attempted cursor is retained, and no provider data enters the log.
            Log.w(TAG, "Replay filter unavailable; using initial request")
            state.reqPayload
        }
    }

    private fun claimReplayWindow(url: String, nowMs: Long): Boolean {
        var claimed = false
        lastReplayAt.compute(url) { _, previous ->
            if (previous == null || nowMs - previous >= RELAY_REPLAY_DEBOUNCE_MS) {
                claimed = true
                nowMs
            } else {
                previous
            }
        }
        return claimed
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun ensureTapRegistered() {
        synchronized(tapLock) {
            if (tapRegistered) return
            tapRegistration.registerTap(tap)
            tapRegistered = true
        }
    }

    /**
     * Demux an incoming raw relay message. Cheap parse: reads only the
     * type tag and subId without full JSON deserialization for the common
     * EVENT case.
     */
    private fun dispatchMessage(message: RelayTapMessage) {
        when (message) {
            is RelayTapMessage.VerifiedEvent ->
                dispatchEvent(message.subscriptionId, message.event)
            is RelayTapMessage.Control ->
                dispatchControlMessage(message.raw, message.relayUrl)
        }
    }

    private fun dispatchControlMessage(raw: String, relayUrl: String) {
        when {
            raw.startsWith("[\"EOSE\"") -> {
                val subId = extractSubId(raw) ?: return
                if (handleRelayEose(subId, relayUrl, RelayCompletionReason.Genuine)) {
                    relayCapabilitiesStore.recordRequestSuccess(relayUrl)
                }
            }
            raw.startsWith("[\"CLOSED\"") -> {
                val subId = extractSubId(raw) ?: return
                val reason = extractClosedReason(raw) ?: ""
                handleRelayClosed(subId, relayUrl, reason)
            }
            // OK / NOTICE / AUTH messages aren't routed here — they're handled
            // upstream (RelayPool for OK, EventProcessor for NOTICE).
        }
    }

    private fun dispatchEvent(subId: String, event: NostrEvent) {
        val state = subs[subId] ?: return  // not our sub, or already closed
        if (state.isPaused) return  // drop in-flight events during lifecycle pause

        // Reject future-dated events — prevents a poisoned since cursor. Signature
        // and id-hash validity are guaranteed by RelayTapMessage.VerifiedEvent.
        val nowSec = System.currentTimeMillis() / 1000L
        if (event.createdAt > nowSec + 60L) return
        val url = event.relayUrl
        if (url !in state.urls) return
        val page = state.pages.computeIfAbsent(url) { RelayPage() }
        if (page.reason != RelayCompletionReason.Genuine) synchronized(page) {
            if (page.reason != RelayCompletionReason.Genuine) {
                if (page.count < Int.MAX_VALUE) page.count++
                page.oldest = minOf(page.oldest ?: event.createdAt, event.createdAt)
                page.newest = maxOf(page.newest ?: event.createdAt, event.createdAt)
            }
        }
        if (!state.knownIds.add(event.id)) {
            trafficMetrics.subscriptionDuplicate()
            return
        }
        state.lastEventAt = elapsedRealtime()
        try {
            state.onevent(event)
        } catch (t: Throwable) {
            Log.w(TAG, "onevent threw for sub=$subId", t)
        }
    }

    private fun handleRelayEose(subId: String, relayUrl: String, reason: RelayCompletionReason): Boolean {
        val state = subs[subId] ?: return false
        if (state.isPaused || relayUrl !in state.urls) return false
        val page = state.pages.computeIfAbsent(relayUrl) { RelayPage() }
        val completion = synchronized(page) {
            // A late real EOSE may certify a watchdog-ended page, without
            // firing the display/allEosed callback twice.
            if (page.reason == RelayCompletionReason.Genuine ||
                (page.reason != null && reason != RelayCompletionReason.Genuine)) return false
            page.reason = reason
            RelayCompletion(relayUrl, reason, page.count, page.oldest, page.newest)
        }
        try {
            state.onRelayCompletion(completion)
        } catch (t: Throwable) {
            Log.w(TAG, "onRelayCompletion threw for sub=$subId", t)
        }
        if (!state.eosedRelays.add(relayUrl)) return reason == RelayCompletionReason.Genuine
        val allEosed = state.eosedRelays.size >= state.urls.size
        Log.d(TAG, "EOSE: $subId from $relayUrl (${state.eosedRelays.size}/${state.urls.size} allEosed=$allEosed events=${state.knownIds.size})")
        try {
            state.oneose(allEosed)
        } catch (t: Throwable) {
            Log.w(TAG, "oneose threw for sub=$subId", t)
        }
        return true
    }

    private fun handleRelayClosed(subId: String, relayUrl: String, reason: String) {
        val state = subs[subId] ?: return
        if (state.isPaused) return  // ignore CLOSED during lifecycle pause
        if (!state.closedRelays.add(relayUrl)) return
        try {
            state.onclose(relayUrl, reason)
        } catch (t: Throwable) {
            Log.w(TAG, "onclose threw for sub=$subId", t)
        }
        // Treat as EOSE-equivalent so callers waiting on allEosed don't hang.
        if (!state.eosedRelays.contains(relayUrl)) {
            handleRelayEose(subId, relayUrl, RelayCompletionReason.Closed)
        }
    }

    private fun generateSubId(urls: List<String>): String {
        val seq = seqCounter.incrementAndGet()
        val urlHash = urls.joinToString(",").hashCode().toString(16)
        return "sub-$seq-$urlHash"
    }

    private fun buildReqJson(subId: String, filter: NostrFilter): String =
        buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(filter.toWireJsonObject())
        }.toString()

    private fun buildCloseJson(subId: String): String =
        buildJsonArray {
            add(JsonPrimitive("CLOSE"))
            add(JsonPrimitive(subId))
        }.toString()

    /**
     * Start per-relay EOSE watchdog timers. If a relay accepts REQ but never
     * sends EOSE within 30s, synthesize one so callers don't hang. Cancels
     * any prior watchdog for the same subId. Self-terminates if sub is paused.
     */
    private fun startEoseWatchdog(subId: String, urlSet: Set<String>) {
        watchdogScopes.remove(subId)?.cancel()
        val scope = CoroutineScope(SupervisorJob() + watchdogDispatcher)
        watchdogScopes[subId] = scope
        for (url in urlSet) {
            startRelayEoseWatchdog(subId, url, scope)
        }
    }

    private fun startRelayEoseWatchdog(
        subId: String,
        url: String,
        scope: CoroutineScope? = watchdogScopes[subId],
    ) {
        val watchdogScope = scope ?: return
        watchdogScope.launch {
            val deadline = elapsedRealtime() + EOSE_CEILING_MS
            while (true) {
                val state = subs[subId] ?: return@launch
                if (state.isPaused) return@launch
                if (url in state.eosedRelays || url in state.closedRelays) return@launch
                val now = elapsedRealtime()
                val quiesced = state.lastEventAt > 0L &&
                    now - state.lastEventAt >= EOSE_QUIESCENCE_MS
                val ceilingHit = now >= deadline
                if (quiesced || ceilingHit) {
                    Log.d(
                        TAG,
                        "EOSE watchdog: synthesizing sub=$subId relay=$url " +
                            "(${if (quiesced) "quiescent" else "ceiling"})",
                    )
                    handleRelayEose(subId, url, RelayCompletionReason.Watchdog)
                    return@launch
                }
                delay(EOSE_POLL_MS)
            }
        }
    }

    private fun extractSubId(raw: String): String? {
        // ["EOSE","sub-id"] or ["CLOSED","sub-id","reason"]
        val firstComma = raw.indexOf(',')
        if (firstComma < 0) return null
        val firstQuote = raw.indexOf('"', firstComma + 1)
        if (firstQuote < 0) return null
        val secondQuote = raw.indexOf('"', firstQuote + 1)
        if (secondQuote < 0) return null
        return raw.substring(firstQuote + 1, secondQuote)
    }

    private fun extractClosedReason(raw: String): String? {
        val msg = try {
            NostrJson.parseToJsonElement(raw).jsonArray
        } catch (_: Exception) {
            return null
        }
        if (msg.size < 3) return null
        return (msg[2] as? JsonPrimitive)?.content
    }

    /** Test-only: synchronous direct dispatch. Avoid in production paths. */
    internal fun dispatchForTest(raw: String, relayUrl: String) =
        dispatchControlMessage(raw, relayUrl)

    /** Test-only: drop all subs and unregister tap. */
    internal fun resetForTest() {
        subs.clear()
        replayEpochs.clear()
        watchdogScopes.values.forEach { it.cancel() }
        watchdogScopes.clear()
        synchronized(tapLock) {
            if (tapRegistered) {
                tapRegistration.unregisterTap(tap)
                tapRegistered = false
            }
        }
    }

    interface Handle {
        fun close()
    }

    private inner class HandleImpl(private val subId: String) : Handle {
        @Volatile private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            watchdogScopes.remove(subId)?.cancel()
            val state = subs.remove(subId) ?: return
            val closeMsg = buildCloseJson(subId)
            for (url in state.urls) {
                transport.sendToRelay(url, closeMsg)
            }
        }
    }
}
