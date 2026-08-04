package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.tagsToJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.unsilence.app.data.model.buildVideoRenderModels
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wire DTO for the inner event object inside `["EVENT", subId, {...}]`.
 *
 * Decoded via kotlinx-serialization's streaming decoder
 * ([NostrJson.decodeFromString]) directly into final-shape primitives —
 * no intermediate JsonObject / JsonArray tree, no per-field jsonPrimitive
 * allocation, no second pass to convert tags from JsonArray to
 * List<List<String>>.
 *
 * NostrJson has ignoreUnknownKeys = true so relay-specific extras (e.g.
 * NIP-19 'a' tags, custom moderation flags) don't break decode.
 */
@Serializable
internal data class EventDto(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val content: String,
    @SerialName("created_at") val createdAt: Long,
    val tags: List<List<String>> = emptyList(),
    val sig: String,
)

internal fun EventDto.toNostrEvent(relayUrl: String): NostrEvent {
    val (replyToId, rootId) = when (kind) {
        1111 -> parseNip22Threading(tags)
        1, 6, 16, 9734, 9735, 20, 21, 22, 34235, 34236, 30023 -> parseNip10Threading(tags)
        else -> Pair(null, null)
    }
    val (hasCw, cwReason) = effectiveContentWarning(kind, content, tags)
    return NostrEvent(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        createdAt = createdAt,
        tags = tags,
        tagsJson = tagsToJson(tags),
        sig = sig,
        relayUrl = relayUrl,
        replyToId = replyToId,
        rootId = rootId,
        hasContentWarning = hasCw,
        contentWarningReason = cwReason,
        firstSeenAt = System.currentTimeMillis(),
        relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add(relayUrl) },
    )
}

private fun NostrEvent.forRelay(relayUrl: String): NostrEvent {
    if (this.relayUrl == relayUrl) return this
    return copy(
        relayUrl = relayUrl,
        relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add(relayUrl) },
    )
}

private const val TAG = "EventProcessor"

/** Fetches kind-30002 relay sets by coordinate from hint relays.
 *  Called when kind-10012 arrives with ["a", "30002:pubkey:dtag", "hint-relay"] tags. */
fun interface RelaySetRefFetcher {
    fun fetch(author: String, dTags: List<String>, hintRelayUrls: List<String>)
}

// Dedup cache limits
private const val DEDUP_MAX  = 10_000
private const val DEDUP_TRIM = 2_000
// Full event objects are much heavier than IDs (especially long-form content).
// Overlapping subscription copies normally arrive close together, so a small
// bounded window captures the duplicate crypto win without retaining the feed.
private const val VERIFIED_CACHE_MAX = 1_024
private const val VERIFIED_CACHE_TRIM = 256

/**
 * Parses raw relay wire messages and writes valid events to [MemoryEventStore].
 *
 * Performance architecture (bounds CPU/allocation pressure from relay fan-out):
 *
 *  1. DEDUP FIRST — event ID extracted via substring scan BEFORE JSON parsing.
 *     A bounded verified-event cache eliminates repeat parsing/crypto while still
 *     delivering one event that matches multiple subscription IDs.
 *
 *  2. EARLY RETURN — messages that don't start with ["EVENT" are rejected in one
 *     startsWith() call. EOSE, OK, NOTICE, CLOSED never reach the JSON parser.
 *
 *  3. SINGLE VERIFIED ENVELOPE — Subscription receives the same decoded,
 *     id-checked, Schnorr-verified NostrEvent that feeds MES.
 *
 *  4. PRIORITY LANES — two channels:
 *       HOT  (cap 500): feed content, including all NIP-71 video kinds, flushed every 100 ms.
 *       COLD (cap 500): kinds 0, 7, 9735           — background data, flushed every 2 s.
 *
 *  5. BATCHED WRITES — drainer coroutines collect from their channel, then call
 *     MemoryEventStore.insert() for deduplicated batches.
 *
 *  6. WRITE COALESCING — before each flush, duplicates are removed by primary key so
 *     that one event arriving from 5 relays produces exactly one insert.
 */
@Singleton
class EventProcessor @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val signatureVerifier: com.unsilence.app.data.auth.SignatureVerifier,
) : TapRegistration {
    // CPU-bound work (JSON parse, MES insert, kind handlers).
    // Belongs on Default not IO. limitedParallelism(2) keeps this from
    // hogging Default and stalling Compose recomposition.
    private val processDispatcher = Dispatchers.Default.limitedParallelism(2)
    private var scope = CoroutineScope(SupervisorJob() + processDispatcher)
    private val nowSeconds: Long get() = System.currentTimeMillis() / 1000L

    // ── Subscription tap registry ─────────────────────────────────────────────
    // Subscriptions receive verified EVENT envelopes plus raw control messages.
    // This keeps subscription demux independent from MES dedup without decoding
    // or verifying the same EVENT twice.
    // CopyOnWriteArrayList — registrations are rare, iterations frequent.
    private val taps = java.util.concurrent.CopyOnWriteArrayList<RelayMessageTap>()

    override fun registerTap(tap: RelayMessageTap) {
        if (!taps.contains(tap)) taps.add(tap)
    }

    override fun unregisterTap(tap: RelayMessageTap) {
        taps.remove(tap)
    }

    // ── Testing support ──────────────────────────────────────────────────────

    // Stops drainers and switches scope. Tests call drainForTest() instead of
    // relying on the infinite drainer loops (which don't terminate in test dispatchers).
    internal fun setTestScope(testScope: CoroutineScope) {
        stop()
        scope = testScope
    }

    /**
     * Drain both channels and flush once. Tests call this after process()
     * to push events through the channel→flushBatch→MemoryEventStore path
     * without needing the infinite drainer loops.
     */
    internal fun drainForTest() {
        val buffer = mutableListOf<NostrEvent>()
        while (true) {
            val event = hotChannel.tryReceive().getOrNull() ?: break
            buffer.add(event)
        }
        while (true) {
            val event = coldChannel.tryReceive().getOrNull() ?: break
            buffer.add(event)
        }
        if (buffer.isNotEmpty()) {
            flushBatch(buffer)
            buffer.clear()
        }
        // Control-plane events go through a separate batch path — no media
        // sidecars and they may include kind-10012 relay set refs.
        while (true) {
            val event = controlChannel.tryReceive().getOrNull() ?: break
            buffer.add(event)
        }
        if (buffer.isNotEmpty()) {
            flushControlBatch(buffer)
        }
    }

    /**
     * Test-only: replace SignatureVerifier with a stub. Tests that exercise
     * the state machine (dedup, batching, eviction) use synthetic events
     * with fake sigs; real signature verification is covered by
     * SignatureVerifierTest separately.
     */
    @Volatile private var signatureVerifierTestOverride: com.unsilence.app.data.auth.SignatureVerifier? = null

    internal fun setTestVerifier(verifier: com.unsilence.app.data.auth.SignatureVerifier) {
        this.signatureVerifierTestOverride = verifier
    }

    private fun verifySig(event: NostrEvent): Boolean =
        (signatureVerifierTestOverride ?: signatureVerifier).verify(event)

    /** Set by RelayPool — resolves kind-30002 relay set references from kind-10012 hint tags. */
    internal var relaySetRefFetcher: RelaySetRefFetcher? = null

    /**
     * Resolve kind-30002 relay set references from a kind-10012 (favorites) event.
     * Parses ["a", "30002:pubkey:dtag", "hint-relay"] tags, groups by hint relay,
     * and dispatches fetches. The fetched kind-30002 events flow back through
     * onRelayMessage → direct-path insert → handleRelaySetMaterialized().
     */
    private fun resolveRelaySetRefs(event: NostrEvent) {
        val fetcher = relaySetRefFetcher ?: return
        // Group d-tags by hint relay URL. A tag without a hint relay is skipped —
        // the indexer fetch in fetchRelayEcosystem already covers those.
        val byHintRelay = mutableMapOf<String, MutableList<String>>()
        for (tag in event.tags) {
            if (tag.size < 3 || tag[0] != "a") continue
            val parts = tag[1].split(":")
            // Expected format: "30002:pubkey:dtag"
            if (parts.size < 3 || parts[0] != "30002") continue
            val author = parts[1]
            val dTag = parts.subList(2, parts.size).joinToString(":")
            val hintRelay = tag[2]
            if (hintRelay.isBlank() || !hintRelay.startsWith("wss://")) continue
            // Skip if we already have this relay set with members
            val existing = memoryEventStore.getSetMembers(event.pubkey, dTag)
            if (existing.isNotEmpty()) continue
            byHintRelay.getOrPut(hintRelay) { mutableListOf() }.add(dTag)
        }
        if (byHintRelay.isEmpty()) return
        // All refs in a kind-10012 share the same author (the event pubkey)
        val author = event.pubkey
        for ((hintRelay, dTags) in byHintRelay) {
            fetcher.fetch(author, dTags, listOf(hintRelay))
            Log.d(TAG, "Relay set ref resolve: ${dTags.size} sets → $hintRelay for ${author.take(8)}…")
        }
    }

    // ── 1. Dedup cache ────────────────────────────────────────────────────────

    /**
     * Set of recently-seen event IDs. ConcurrentHashMap<K,Unit> is the idiomatic
     * Kotlin/Java concurrent set. Trimmed when it exceeds [DEDUP_MAX].
     */
    internal val seenIds = ConcurrentHashMap<String, Unit>(DEDUP_MAX + DEDUP_TRIM)

    /**
     * Recently verified events shared with subscription delivery. A globally
     * deduplicated event may still be novel to a second subscription id, so its
     * verified object must remain available for that delivery.
     */
    private val verifiedEvents = ConcurrentHashMap<String, NostrEvent>(VERIFIED_CACHE_MAX + VERIFIED_CACHE_TRIM)

    // ── 3. Priority channels ──────────────────────────────────────────────────

    /** HOT lane: feed content, including all NIP-71 video kinds. Flushed every 100 ms.
     *  Capacity 500: initial load from 19 relays × 500 limit = up to 9 500 kind 1 events
     *  can burst in before the first drain. trySend drops silently, so we size generously. */
    private val hotChannel  = Channel<NostrEvent>(capacity = 500)

    /** COLD lane: background data (kind 0, 7, 9735). Flushed every 2 s. */
    private val coldChannel = Channel<NostrEvent>(capacity = 500)

    /** CONTROL lane: control-plane kinds (10002, 10006, 10007, 10012, 10040,
     *  30002, 30166, 30382, 30385). Flushed every 150 ms via [MemoryEventStore.insertBatch]
     *  so signal bumps coalesce — a 1000-event monitor burst produces ONE
     *  _relayMonitorSignal bump instead of 1000.
     *
     *  Capacity 2000 sized for the largest observed burst (1175 kind-30166
     *  monitor events from a single fetchRelayMonitors call) plus headroom
     *  for parallel kind-10002 fetches (≤300 follows). trySend drops silently
     *  if the drainer can't keep up; control-plane events are re-fetched on
     *  next bootstrap. */
    private val controlChannel = Channel<NostrEvent>(capacity = 2000)

    private var drainerJob: Job? = null

    init {
        start()
    }

    /** Launch drainer coroutines under a child Job so they can be cancelled independently. */
    fun start() {
        if (drainerJob?.isActive == true) return
        drainerJob = Job(scope.coroutineContext[Job])
        val drainerScope = CoroutineScope(scope.coroutineContext + drainerJob!!)
        drainerScope.launch { drainHot() }
        drainerScope.launch { drainCold() }
        drainerScope.launch { drainControl() }
        // Periodic verification stats — 60s interval, only logs if there's been activity.
        drainerScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                val s = signatureVerifier.stats()
                if (s.ok > 0 || s.bad > 0 || s.errors > 0) {
                    Log.d(TAG, "SigVerify: ok=${s.ok} bad=${s.bad} errors=${s.errors}")
                }
            }
        }
        Log.d(TAG, "Drainers started")
    }

    /** Cancel drainer coroutines and clear in-memory state. Called on logout. */
    fun stop() {
        drainerJob?.cancel()
        drainerJob = null
        seenIds.clear()
        verifiedEvents.clear()
        // Drain and discard any buffered events
        while (hotChannel.tryReceive().isSuccess) { /* discard */ }
        while (coldChannel.tryReceive().isSuccess) { /* discard */ }
        while (controlChannel.tryReceive().isSuccess) { /* discard */ }
        Log.d(TAG, "Stopped and cleared state")
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Called by [RelayPool] for every raw message received from a relay WebSocket.
     *
     * Fast path order (each check is cheaper than the next):
     *   1. startsWith ["EVENT"  — rejects EOSE/OK/NOTICE with one call
     *   2. extractEventId       — substring scan, no JSON allocation
     *   3. seenIds cache hit    — ConcurrentHashMap lookup, returns immediately for verified dups
     *   4. Streaming JSON decode — only for novel EVENT messages
     */
    suspend fun process(raw: String, rawRelayUrl: String) {
        val relayUrl = normalizeRelayUrl(rawRelayUrl) ?: rawRelayUrl

        // ── Early return for non-EVENT messages (existing behavior) ────────────
        if (!raw.startsWith("[\"EVENT\"")) {
            dispatchToTaps(RelayTapMessage.Control(raw, relayUrl))
            return
        }

        // ── Dedup by event ID, extracted without JSON parsing ──────────────────
        val eventId = extractEventIdFromRaw(raw) ?: return
        val subscriptionId = extractSubscriptionIdFromRaw(raw)

        // Global dedup must not suppress a second matching subscription. Reuse
        // the already-verified object and let Subscription apply its own knownIds.
        verifiedEvents[eventId]?.let { cached ->
            val relayed = cached.forRelay(relayUrl)
            if (subscriptionId != null) {
                dispatchToTaps(RelayTapMessage.VerifiedEvent(subscriptionId, relayed))
            }
            // Kind-10040 is account-scoped control state and intentionally
            // re-enters MES: it can first arrive before the owner is established.
            if (cached.kind != 10040 && seenIds.containsKey(eventId)) {
                memoryEventStore.addRelaySeen(eventId, relayUrl)
                return
            }
            handleVerifiedEvent(relayed)
            return
        }

        // Only novel EVENT messages reach here (~20 % of total messages).
        // Streaming decode bypasses the JsonElement tree: instead of allocating
        // a JsonObject + per-field JsonPrimitive + JsonArray of tags + nested
        // JsonArrays, kotlinx-serialization's streaming decoder writes straight
        // into the final EventDto fields. Saves ~5KB of allocation per event
        // — at 20 events/sec that's the dominant ~1.5MB/sec heap churn that
        // drove the 85MB GC cycles every 30-60s.
        val eventJsonStart = findEventObjectStart(raw)
        val eventJsonEnd = if (eventJsonStart >= 0) findMatchingBraceEnd(raw, eventJsonStart) else -1
        if (eventJsonStart < 0 || eventJsonEnd < 0) return
        val eventJson = raw.substring(eventJsonStart, eventJsonEnd + 1)
        val dto = try {
            NostrJson.decodeFromString<EventDto>(eventJson)
        } catch (_: Exception) {
            return  // malformed event object — skip silently
        }
        // Sanity: the precomputed eventId must match the decoded id. If a
        // relay returned a tampered or mis-aligned message, refuse it now
        // before we touch MES state.
        if (dto.id != eventId) return
        val event = dto.toNostrEvent(relayUrl)

        // Schnorr signature + id-hash verification happens before either
        // consumer sees the event. A bad copy cannot poison the cache or dedup.
        if (!verifySig(event)) return

        val canonical = verifiedEvents.putIfAbsent(event.id, event) ?: event
        trimVerifiedCacheIfNeeded()
        val relayed = canonical.forRelay(relayUrl)
        if (subscriptionId != null) {
            dispatchToTaps(RelayTapMessage.VerifiedEvent(subscriptionId, relayed))
        }
        handleVerifiedEvent(relayed)
    }

    private fun dispatchToTaps(message: RelayTapMessage) {
        if (taps.isEmpty()) return
        for (tap in taps) {
            try {
                tap.onMessage(message)
            } catch (t: Throwable) {
                Log.w(TAG, "tap.onMessage threw", t)
            }
        }
    }

    /**
     * Parse + signature-verify a raw EVENT message and RETURN the verified event without
     * inserting into MES. For the relay-directory firehose (A1): the ephemeral collector
     * bypasses [process]/[handleVerifiedEvent], so the SAME id-hash + Schnorr verification must be
     * re-applied before any directory parse — a forgeable directory is an attack surface.
     * Returns null on malformed JSON, id-mismatch, or invalid signature. Author allow-listing
     * (event.pubkey ∈ trusted monitors) is the caller's responsibility — verification proves
     * authenticity of event.pubkey, not that it is a monitor we trust.
     */
    fun parseAndVerify(raw: String, relayUrl: String): NostrEvent? {
        val eventId = extractEventIdFromRaw(raw) ?: return null
        val start = findEventObjectStart(raw)
        val end = if (start >= 0) findMatchingBraceEnd(raw, start) else -1
        if (start < 0 || end < 0) return null
        val dto = try {
            NostrJson.decodeFromString<EventDto>(raw.substring(start, end + 1))
        } catch (_: Exception) {
            return null
        }
        if (dto.id != eventId) return null
        val event = dto.toNostrEvent(relayUrl)
        return if (verifySig(event)) event else null
    }

    // ── Dedup helpers ─────────────────────────────────────────────────────────
    // Raw substring scanners live in RawEventJson.kt.

    internal fun trimDedupCacheIfNeeded() {
        if (seenIds.size <= DEDUP_MAX) return
        // ConcurrentHashMap has no defined iteration order, but removing any 2 k
        // entries is sufficient — we just need an approximate LRU effect.
        var trimmed = 0
        val iter = seenIds.keys.iterator()
        while (iter.hasNext() && trimmed < DEDUP_TRIM) {
            val id = iter.next()
            iter.remove()
            verifiedEvents.remove(id)
            trimmed++
        }
    }

    private fun trimVerifiedCacheIfNeeded() {
        if (verifiedEvents.size <= VERIFIED_CACHE_MAX) return
        var trimmed = 0
        val iter = verifiedEvents.keys.iterator()
        while (iter.hasNext() && trimmed < VERIFIED_CACHE_TRIM) {
            iter.next()
            iter.remove()
            trimmed++
        }
    }

    // ── Event routing ─────────────────────────────────────────────────────────

    private suspend fun handleVerifiedEvent(nostrEvent: NostrEvent) {
        val tags = nostrEvent.tags

        // NIP-40: skip events that have already expired.
        val expiration = tags.firstOrNull { it.size >= 2 && it[0] == "expiration" }
            ?.getOrNull(1)?.toLongOrNull()
        if (expiration != null && expiration < nowSeconds) return

        // Skip machine-generated spam: JSON payloads and broadcast protocols
        // posted as kind-1 notes. Normal notes are always plain text/markdown.
        if (nostrEvent.kind == 1 &&
            (nostrEvent.content.startsWith("{") || nostrEvent.content.startsWith("xitchat-broadcast-v1-"))
        ) return

        // 10040 intentionally bypasses seenIds; MES handles own-pubkey and
        // created_at staleness, and duplicate 10040s are rare.
        if (nostrEvent.kind != 10040) {
            if (seenIds.putIfAbsent(nostrEvent.id, Unit) != null) {
                memoryEventStore.addRelaySeen(nostrEvent.id, nostrEvent.relayUrl)
                return
            }
            trimDedupCacheIfNeeded()
        }
        // ── Kind-3 follows update (not stored in eventsById) ─────────────────
        // Updates the followsByPubkey index directly without entering MES
        // proper. Snapshot persists followsByPubkey so this is reconstructible.
        if (nostrEvent.kind == 3) {
            val followCount = memoryEventStore.updateFollows(nostrEvent)
            Log.d(TAG, "Kind-3 direct path: pubkey=${nostrEvent.pubkey.take(8)}… $followCount follows (createdAt=${nostrEvent.createdAt})")
        }
        // Control-plane events → CONTROL channel (separate lane, batched).
        // 10002 for outbox prefetch, 10006/10007/10012/10063/30002 for relay config
        // UI, 10040 for NIP-85 provider registry, 30382 for user WoT assertions,
        // 30385 for relay trust scores, 30166 for relay monitors (hundreds arrive
        // in burst — capacity-2000 channel handles the largest observed burst).
        // The drainer flushes via insertBatch so per-event signal bumps coalesce.
        // Kind-10012 relay set refs are resolved inside flushControlBatch.
        val isControlKind = when (nostrEvent.kind) {
            30382 -> memoryEventStore.isActiveWotProvider(nostrEvent.pubkey)
            10000, 10002, 10006, 10007, 10012, 10030, 10040, 10063, 30002, 30030, 30166, 30385 -> true
            else -> false
        }
        if (isControlKind) {
            controlChannel.trySend(nostrEvent)
        }

        // ── Priority lanes ───────────────────────────────────────────────────
        // Kind-3 is NOT channeled — updateFollows (above) provides the MES
        // update, and the snapshot persists followsByPubkey directly.
        // kind-1111 (NIP-22 comments) MUST be channeled: remote comments authored
        // by others reach MES only through this lane (Subscription doesn't insert;
        // local writes + legacy kind-1 had other paths). The feed filter's `kinds`
        // Feed content kinds exclude 1111, so it never leaks into Notes/replies —
        // it surfaces only in the article reader (commentIdsByCoord) + notifications.
        val shouldChannel = nostrEvent.kind in setOf(0, 1, 6, 7, 1018, 1068, 9734, 9735, 16, 20, 21, 22, 34235, 34236, 30023, 1111)
        if (shouldChannel) {
            val isHot = nostrEvent.kind == 1 || nostrEvent.kind == 6 || nostrEvent.kind == 16 || nostrEvent.kind == 20 ||
                nostrEvent.kind == 21 || nostrEvent.kind == 22 || nostrEvent.kind == 34235 || nostrEvent.kind == 34236 ||
                nostrEvent.kind == 1068 || nostrEvent.kind == 30023 || nostrEvent.kind == 1111
            // trySend is non-suspending: drops if full rather than blocking relay consumption.
            // Channels are sized so drops are extremely rare under realistic Nostr traffic.
            if (isHot) hotChannel.trySend(nostrEvent) else coldChannel.trySend(nostrEvent)
        }
    }

    // ── Channel drainers ──────────────────────────────────────────────────────

    /**
     * HOT drainer: collects up to 100 feed events within a 100 ms window, then
     * flushes. The withTimeoutOrNull provides natural pacing without busy-waiting.
     */
    private suspend fun drainHot() {
        val buffer = ArrayDeque<NostrEvent>(100)
        while (true) {
            // Block up to 100 ms waiting for the first item
            val first = withTimeoutOrNull(100L) { hotChannel.receive() }
            if (first != null) {
                buffer.add(first)
                // Drain any already-queued items without blocking (non-suspending)
                var next = hotChannel.tryReceive().getOrNull()
                while (next != null && buffer.size < 100) {
                    buffer.add(next)
                    next = hotChannel.tryReceive().getOrNull()
                }
            }
            if (buffer.isNotEmpty()) {
                flushBatch(buffer)
                buffer.clear()
            }
        }
    }

    /**
     * COLD drainer: collects up to 200 background events within a 2 s window.
     * Profiles, reactions, and zaps don't need sub-second latency.
     */
    private suspend fun drainCold() {
        val buffer = ArrayDeque<NostrEvent>(200)
        while (true) {
            val first = withTimeoutOrNull(2_000L) { coldChannel.receive() }
            if (first != null) {
                buffer.add(first)
                var next = coldChannel.tryReceive().getOrNull()
                while (next != null && buffer.size < 200) {
                    buffer.add(next)
                    next = coldChannel.tryReceive().getOrNull()
                }
            }
            if (buffer.isNotEmpty()) {
                flushBatch(buffer)
                buffer.clear()
            }
        }
    }

    /**
     * CONTROL drainer: collects up to 500 control-plane events within a
     * 150 ms window, then dispatches via [MemoryEventStore.insertBatch].
     *
     * Coalesces signal bumps for kind-10002/10006/10007/10012/10040/30002/30166/
     * 30382/30385 bursts that previously hit MES one event at a time and bumped
     * _relayConfigSignal / _wotSignal / _trustScoreSignal / _relayMonitorSignal once per
     * event. A 1175-event relay-monitor burst now produces one bump.
     *
     * Window of 150 ms is short enough that user-perceived latency for
     * kind-10002 outbox routing remains <200 ms, but long enough to let
     * a 1000-event burst coalesce into a single batch.
     */
    private suspend fun drainControl() {
        val buffer = ArrayDeque<NostrEvent>(500)
        while (true) {
            val first = withTimeoutOrNull(150L) { controlChannel.receive() }
            if (first != null) {
                buffer.add(first)
                var next = controlChannel.tryReceive().getOrNull()
                while (next != null && buffer.size < 500) {
                    buffer.add(next)
                    next = controlChannel.tryReceive().getOrNull()
                }
            }
            if (buffer.isNotEmpty()) {
                flushControlBatch(buffer)
                buffer.clear()
            }
        }
    }

    /**
     * Insert control-plane events via the MES batch path so signal bumps
     * coalesce. Unlike [flushBatch], does NOT compute imeta/video sidecars
     * (control-plane kinds carry no media).
     */
    private fun flushControlBatch(batch: List<NostrEvent>) {
        // Dedup by event ID within the batch — same kind-10002 from N relays
        val events = LinkedHashMap<String, NostrEvent>(batch.size)
        for (event in batch) {
            val existing = events[event.id]
            if (existing != null) {
                existing.relaysSeen.addAll(event.relaysSeen)
            } else {
                events[event.id] = event
            }
        }
        memoryEventStore.insertBatch(events.values.toList())

        // Kind-10012 favorites may reference relay sets via "a" tags that
        // need a follow-up fetch from a hint relay. Resolve outside the
        // insert path — fetcher dispatches its own REQ.
        for (event in events.values) {
            if (event.kind == 10012) resolveRelaySetRefs(event)
        }
    }

    // ── Batch insert ──────────────────────────────────────────────────────────

    private fun flushBatch(batch: List<NostrEvent>) {
        // Dedup by event ID within this batch (same event from N relays)
        val events = LinkedHashMap<String, NostrEvent>(batch.size)
        for (event in batch) {
            val existing = events[event.id]
            if (existing != null) {
                // Merge relay provenance
                existing.relaysSeen.addAll(event.relaysSeen)
            } else {
                events[event.id] = event
            }
        }

        // Batch insert with coalesced signal bumps: ≤5 bumps instead of N.
        val eventList = events.values.toList()
        memoryEventStore.insertBatch(eventList)

        // Pre-compute media metadata at insert time (sidecar caches).
        // ContentParser.parse is LAZY — deferred to first getOrParseEventModel() read.
        for (event in eventList) {
            if (event.kind in setOf(1, 6, 16, 20, 21, 22, 34235, 34236)) {
                val imetaMedia = ImetaParser.parseFromList(event.tags)
                val models = buildVideoRenderModels(event.kind, event.content, event.tags)
                memoryEventStore.putVideoRenderModels(event.id, models)
                val imageDims = imetaMedia
                    .filter { it.mimeType?.startsWith("image/") == true && it.width != null && it.height != null && it.height != 0 }
                    .associate { it.url to (it.width!!.toFloat() / it.height!!) }
                memoryEventStore.putImetaImageDims(event.id, imageDims)
            }
        }
    }

}

// ── NIP-10: threading (top-level — shared by EventProcessor + Subscription) ──

/**
 * Returns (replyToId, rootId) parsed from `e` tags.
 *
 * Priority: explicit "root"/"reply" markers. Fallback: positional
 * (first e = root, last e = reply-to). If only one e tag, it is the root.
 */
internal fun parseNip10Threading(tags: List<List<String>>): Pair<String?, String?> {
    val eTags = tags.filter { it.size >= 2 && it[0] == "e" }
    if (eTags.isEmpty()) return Pair(null, null)

    // If ANY e-tag carries a NIP-10 marker, use marker-based parsing
    // exclusively. "mention" markers reference events without replying —
    // they must NOT participate in positional fallback.
    val hasMarkers = eTags.any { tag ->
        val m = tag.getOrNull(3)
        m == "root" || m == "reply" || m == "mention"
    }

    if (hasMarkers) {
        val rootId    = eTags.firstOrNull { it.getOrNull(3) == "root" }?.getOrNull(1)
        val replyToId = eTags.firstOrNull { it.getOrNull(3) == "reply" }?.getOrNull(1)
        // If root but no reply marker → direct reply to root
        return Pair(replyToId ?: rootId, rootId)
    }

    // Positional fallback (no markers at all — legacy NIP-10)
    val ids = eTags.mapNotNull { it.getOrNull(1) }
    return when (ids.size) {
        0    -> Pair(null, null)
        1    -> Pair(ids[0], ids[0])   // single e = both root and reply-to
        else -> Pair(ids.last(), ids.first())
    }
}

// ── NIP-22: comments (kind-1111) parent/root threading ───────────────────────

/**
 * Returns (replyToId, rootId) parsed from NIP-22 kind-1111 tags.
 *
 * Uppercase tags describe the root scope (`A`/`K`/`P`). Lowercase tags describe
 * the direct parent. For an article comment:
 *
 * - top-level: lowercase `k=30023`, optional lowercase `e=<article event id>`
 * - reply to comment: lowercase `k=1111`, lowercase `e=<parent comment id>`
 *
 * The article root is usually addressable (`A=30023:pubkey:d`) and may not have
 * a known event id at parse time, so only top-level comments with a lowercase
 * article `e` get a rootId. Comment replies always get replyToId from lowercase
 * `e`, which is what MES needs for parent comment reply counts.
 */
internal fun parseNip22Threading(tags: List<List<String>>): Pair<String?, String?> {
    val parentKind = tags.firstOrNull { it.size >= 2 && it[0] == "k" }?.getOrNull(1)?.toIntOrNull()
    val parentId = tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.getOrNull(1)

    return when {
        parentId == null -> Pair(null, null)
        parentKind == 30023 || parentKind == 21 || parentKind == 22 ||
            parentKind == 34235 || parentKind == 34236 -> Pair(parentId, parentId)
        parentKind == 1111 -> Pair(parentId, null)
        else -> Pair(parentId, null)
    }
}

// ── NIP-36: content-warning (top-level — shared by EventProcessor + Subscription) ──

internal fun parseContentWarning(tags: List<List<String>>): Pair<Boolean, String?> {
    val cwTag = tags.firstOrNull { it.isNotEmpty() && it[0] == "content-warning" }
        ?: return Pair(false, null)
    val reason = cwTag.getOrNull(1)?.takeIf { it.isNotBlank() }
    return Pair(true, reason)
}

/**
 * Effective NIP-36 content-warning, repost-aware. For kind-6/16 reposts that
 * embed the target event as JSON in `content`, the wrapper's own tags carry no
 * `content-warning` (it lives on the inner event), so [parseContentWarning] on
 * the wrapper would miss a sensitive target. This ORs the inner event's warning
 * in so a repost of sensitive content is itself flagged sensitive — the flag the
 * feed hide-filter and card blur/hide gates consume. Wrapper reason wins, else
 * inner. Shared by EventProcessor + Subscription (same package, top-level).
 */
internal fun effectiveContentWarning(
    kind: Int,
    content: String,
    tags: List<List<String>>,
): Pair<Boolean, String?> {
    val (wrapperCw, wrapperReason) = parseContentWarning(tags)
    if ((kind != 6 && kind != 16) || content.isBlank()) return wrapperCw to wrapperReason
    val inner = runCatching {
        val obj = NostrJson.parseToJsonElement(content).jsonObject
        val innerTags = obj["tags"]?.jsonArray?.map { tagEl ->
            tagEl.jsonArray.map { it.jsonPrimitive.content }
        } ?: emptyList()
        parseContentWarning(innerTags)
    }.getOrNull() ?: (false to null)
    return (wrapperCw || inner.first) to (wrapperReason ?: inner.second)
}
