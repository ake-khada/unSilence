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
 *      removing them. resumeAll() re-sends stored REQ payloads. Used by
 *      ProcessLifecycleOwner to stop event flow when backgrounded.
 *
 * Reconnect handling is NOT in this primitive. If a relay drops mid-sub,
 * onclose fires once and that relay is done. Higher layers (TimelineService,
 * RelayBrowseSession) handle reconnect-and-resub.
 *
 * Thread safety: subscribe() is suspend (network I/O). Callbacks fire on
 * EventProcessor's bounded Default dispatcher. Callbacks must not block.
 */
interface ReconnectSource {
    val onRelayReconnected: SharedFlow<String>
}

@Singleton
class Subscription @Inject constructor(
    private val transport: RelayTransport,
    private val tapRegistration: TapRegistration,
    private val reconnectSource: ReconnectSource,
    private val relayCapabilitiesStore: RelaySkipCheck,
) : ActiveSubsSource {
    /** Active subscription state, keyed by subId. */
    private data class SubState(
        val urls: Set<String>,
        val reqPayload: String,
        val onevent: (NostrEvent) -> Unit,
        val oneose: (allEosed: Boolean) -> Unit,
        val onclose: (url: String, reason: String) -> Unit,
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
     * incoming events / EOSEs / closes for this subscription's subId.
     *
     * Returns a [Handle] — call close() to terminate. Idempotent close.
     */
    suspend fun subscribe(
        urls: List<String>,
        filter: NostrFilter,
        onevent: (NostrEvent) -> Unit,
        oneose: (allEosed: Boolean) -> Unit = {},
        onclose: (url: String, reason: String) -> Unit = { _, _ -> },
    ): Handle {
        ensureTapRegistered()

        val urlSet = urls.mapNotNull { normalizeRelayUrl(it) }.toSet()
        val subId = generateSubId(urls)
        val req = buildReqJson(subId, filter)
        val state = SubState(
            urls = urlSet,
            reqPayload = req,
            onevent = onevent,
            oneose = oneose,
            onclose = onclose,
        )
        subs[subId] = state
        Log.d(TAG, "SUB-OPEN: $subId to ${urls.size} relays, filter=${filter.toJsonObject()}")

        try {
            // Connection establishment is the caller's responsibility for
            // PERSISTENT-purpose relays (browse / outbox / home feed). For
            // ad-hoc one-off subs we still call connectAndAwait — it's a no-op
            // for already-connected relays.
            transport.connectAndAwait(urls, timeoutMs = 5_000)

            // Send REQ to each relay, skipping those with known structural rejections.
            val failedUrls = mutableListOf<String>()
            var skippedCount = 0
            for (url in urls) {
                if (relayCapabilitiesStore.shouldSkip(url)) {
                    skippedCount++
                    Log.w(TAG, "SUB-SKIP: $subId skipping $url (cap store)")
                    handleRelayEose(subId, url) // count as done so EOSE threshold isn't blocked
                    continue
                }
                if (transport.isAuthUnavailable(url)) {
                    skippedCount++
                    Log.w(TAG, "SUB-SKIP: $subId skipping $url (auth unavailable)")
                    handleRelayEose(subId, url)
                    continue
                }
                if (!transport.sendToRelay(url, req)) {
                    failedUrls.add(url)
                }
            }
            if (skippedCount > 0) {
                Log.w(TAG, "SUB-DISPATCH: $subId skipped $skippedCount/${urls.size} relays due to capabilities")
            }

            // Retry failed sends — connections may still be establishing after
            // connectAndAwait returned (it waits for ANY 1, not all). Poll up
            // to 10s for remaining connections to come up.
            if (failedUrls.isNotEmpty()) {
                Log.w(TAG, "subscribe $subId: ${failedUrls.size}/${urls.size} sends failed, retrying up to 10s")
                val retryDeadline = System.currentTimeMillis() + 10_000L
                val remaining = failedUrls.toMutableList()
                while (remaining.isNotEmpty() && System.currentTimeMillis() < retryDeadline) {
                    delay(500)
                    val s = subs[subId]
                    if (s == null || s.isPaused) return HandleImpl(subId) // closed or paused during retry
                    val iter = remaining.iterator()
                    while (iter.hasNext()) {
                        if (transport.sendToRelay(iter.next(), req)) iter.remove()
                    }
                }
                if (remaining.isNotEmpty()) {
                    Log.w(TAG, "subscribe $subId: ${remaining.size} relays unreachable after retry")
                }
                for (url in remaining) {
                    handleRelayEose(subId, url)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            subs.remove(subId)
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
            count++
        }
        if (count > 0) Log.d(TAG, "pauseAll: paused $count subs")
    }

    /**
     * Resume all paused subscriptions by re-sending stored REQ payloads.
     * Resets EOSE/CLOSED tracking for a fresh cycle. [SubState.knownIds]
     * preserved for cross-relay dedup continuity. Called from
     * ProcessLifecycleOwner.onStart when app is foregrounded.
     */
    fun resumeAll() {
        var count = 0
        for ((subId, state) in subs) {
            if (!state.isPaused) continue
            state.isPaused = false
            state.eosedRelays.clear()
            state.closedRelays.clear()
            for (url in state.urls) {
                if (!relayCapabilitiesStore.shouldSkip(url)) {
                    transport.sendToRelay(url, state.reqPayload)
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
        if (relayCapabilitiesStore.shouldSkip(normalized)) return
        if (transport.isAuthUnavailable(normalized)) return
        if (transport.isRateLimited(normalized)) {
            if (rateLimitSkipLogged.add(normalized)) {
                Log.w(TAG, "resumeRelay $normalized: skipped during rate-limit cooldown")
            }
            return
        }
        rateLimitSkipLogged.remove(normalized)

        val matching = subs.values.filter { state ->
            !state.isPaused && normalized in state.urls
        }
        if (matching.isEmpty()) {
            Log.d(TAG, "resumeRelay $normalized: 0 subs matched (total subs=${subs.size})")
            return
        }
        if (!claimReplayWindow(normalized, nowMs)) return

        var count = 0
        for (state in matching) {
            state.eosedRelays.remove(normalized)
            state.closedRelays.remove(normalized)
            transport.sendToRelay(normalized, state.reqPayload)
            count++
        }
        Log.d(TAG, "resumeRelay $normalized: replayed $count sub(s)")
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
                handleRelayEose(subId, relayUrl)
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

        if (event.id in state.knownIds) return
        // Reject future-dated events — prevents a poisoned since cursor. Signature
        // and id-hash validity are guaranteed by RelayTapMessage.VerifiedEvent.
        val nowSec = System.currentTimeMillis() / 1000L
        if (event.createdAt > nowSec + 60L) return
        if (!state.knownIds.add(event.id)) return
        state.lastEventAt = android.os.SystemClock.elapsedRealtime()
        try {
            state.onevent(event)
        } catch (t: Throwable) {
            Log.w(TAG, "onevent threw for sub=$subId", t)
        }
    }

    private fun handleRelayEose(subId: String, relayUrl: String) {
        val state = subs[subId] ?: return
        if (state.isPaused) return  // ignore EOSE during lifecycle pause
        if (!state.eosedRelays.add(relayUrl)) return  // already EOSE'd this relay
        val allEosed = state.eosedRelays.size >= state.urls.size
        Log.d(TAG, "EOSE: $subId from $relayUrl (${state.eosedRelays.size}/${state.urls.size} allEosed=$allEosed events=${state.knownIds.size})")
        try {
            state.oneose(allEosed)
        } catch (t: Throwable) {
            Log.w(TAG, "oneose threw for sub=$subId", t)
        }
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
            handleRelayEose(subId, relayUrl)
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        watchdogScopes[subId] = scope
        for (url in urlSet) {
            scope.launch {
                val deadline = android.os.SystemClock.elapsedRealtime() + EOSE_CEILING_MS
                while (true) {
                    val s = subs[subId] ?: return@launch
                    if (s.isPaused) return@launch
                    if (s.eosedRelays.contains(url) || s.closedRelays.contains(url)) return@launch
                    val now = android.os.SystemClock.elapsedRealtime()
                    val quiesced = s.lastEventAt > 0L && (now - s.lastEventAt) >= EOSE_QUIESCENCE_MS
                    val ceilingHit = now >= deadline
                    if (quiesced || ceilingHit) {
                        Log.d(TAG, "EOSE watchdog: synthesizing sub=$subId relay=$url " +
                            "(${if (quiesced) "quiescent" else "ceiling"})")
                        handleRelayEose(subId, url)
                        return@launch
                    }
                    delay(EOSE_POLL_MS)
                }
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
