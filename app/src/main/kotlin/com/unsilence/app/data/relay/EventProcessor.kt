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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.unsilence.app.data.model.buildVideoRenderModels
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EventProcessor"

/** Fetches kind-30002 relay sets by coordinate from hint relays.
 *  Called when kind-10012 arrives with ["a", "30002:pubkey:dtag", "hint-relay"] tags. */
fun interface RelaySetRefFetcher {
    fun fetch(author: String, dTags: List<String>, hintRelayUrls: List<String>)
}

// Dedup cache limits
private const val DEDUP_MAX  = 10_000
private const val DEDUP_TRIM = 2_000

/**
 * Parses raw relay wire messages and writes valid events to [MemoryEventStore].
 *
 * Performance architecture (fixes phone overheating from 19-relay fan-out):
 *
 *  1. DEDUP FIRST — event ID extracted via substring scan BEFORE JSON parsing.
 *     ConcurrentHashMap<String, Unit> seen cache (≤10 k entries) eliminates ~80 % of
 *     processing since the same event arrives from multiple relays simultaneously.
 *
 *  2. EARLY RETURN — messages that don't start with ["EVENT" are rejected in one
 *     startsWith() call. EOSE, OK, NOTICE, CLOSED never reach the JSON parser.
 *
 *  3. PRIORITY LANES — two channels:
 *       HOT  (cap 500): kinds 1, 6, 20, 21, 30023 — feed content, flushed every 100 ms.
 *       COLD (cap 500): kinds 0, 7, 9735           — background data, flushed every 2 s.
 *
 *  4. BATCHED WRITES — drainer coroutines collect from their channel, then call
 *     MemoryEventStore.insert() for deduplicated batches.
 *
 *  5. WRITE COALESCING — before each flush, duplicates are removed by primary key so
 *     that one event arriving from 5 relays produces exactly one insert.
 */
@Singleton
class EventProcessor @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val outboxRouter: dagger.Lazy<OutboxRouter>,
) {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nowSeconds: Long get() = System.currentTimeMillis() / 1000L

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
        }
    }

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

    // ── 3. Priority channels ──────────────────────────────────────────────────

    /** HOT lane: feed content (kind 1, 6, 20, 21, 30023). Flushed every 100 ms.
     *  Capacity 500: initial load from 19 relays × 500 limit = up to 9 500 kind 1 events
     *  can burst in before the first drain. trySend drops silently, so we size generously. */
    private val hotChannel  = Channel<NostrEvent>(capacity = 500)

    /** COLD lane: background data (kind 0, 7, 9735). Flushed every 2 s. */
    private val coldChannel = Channel<NostrEvent>(capacity = 500)

    // ── Kind handlers (immutable — populated at construction, no race) ─────────

    /**
     * Handlers for specific event kinds, dispatched after entity building.
     * Immutable map: handlers exist from construction, before drainers start.
     * Uses dagger.Lazy<OutboxRouter> to break the circular DI dependency
     * (EventProcessor ↔ OutboxRouter).
     */
    private val kindHandlers: Map<Int, suspend (JsonObject) -> Unit> = mapOf(
        3     to { obj -> outboxRouter.get().handleContactList(obj) },
        10002 to { obj -> outboxRouter.get().handleRelayList(obj) },
        10006 to { obj -> outboxRouter.get().handleRelayKindList(obj, 10006) },
        10007 to { obj -> outboxRouter.get().handleRelayKindList(obj, 10007) },
        10012 to { obj -> outboxRouter.get().handleFavoriteRelays(obj) },
        30002 to { obj -> outboxRouter.get().handleRelaySet(obj) },
    )

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
        Log.d(TAG, "Drainers started")
    }

    /** Cancel drainer coroutines and clear in-memory state. Called on logout. */
    fun stop() {
        drainerJob?.cancel()
        drainerJob = null
        seenIds.clear()
        // Drain and discard any buffered events
        while (hotChannel.tryReceive().isSuccess) { /* discard */ }
        while (coldChannel.tryReceive().isSuccess) { /* discard */ }
        Log.d(TAG, "Stopped and cleared state")
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Called by [RelayPool] for every raw message received from a relay WebSocket.
     *
     * Fast path order (each check is cheaper than the next):
     *   1. startsWith ["EVENT"  — rejects EOSE/OK/NOTICE with one call
     *   2. extractEventId       — substring scan, no JSON allocation
     *   3. seenIds cache hit    — ConcurrentHashMap lookup, returns immediately for dups
     *   4. JSON parse + route   — only for novel EVENT messages
     */
    suspend fun process(raw: String, rawRelayUrl: String) {
        // ── Fix: early return for non-EVENT messages before ANY JSON work ──────
        if (!raw.startsWith("[\"EVENT\"")) return
        val relayUrl = normalizeRelayUrl(rawRelayUrl) ?: rawRelayUrl

        // ── Fix 1: dedup by event ID, extracted without JSON parsing ──────────
        val eventId = extractEventId(raw) ?: return
        if (seenIds.putIfAbsent(eventId, Unit) != null) {
            // Already processed — just record this relay as a source so
            // relay-specific feeds (browse mode) include the event.
            memoryEventStore.addRelaySeen(eventId, relayUrl)
            return
        }
        trimDedupCacheIfNeeded()

        // Only novel EVENT messages reach here (~20 % of total messages).
        try {
            val msg = NostrJson.parseToJsonElement(raw).jsonArray
            if (msg.size < 3) return
            handleEvent(eventId, msg[2].jsonObject, relayUrl)
        } catch (_: Exception) {
            // Malformed relay message — skip silently
        }
    }

    // ── Dedup helpers ─────────────────────────────────────────────────────────

    /**
     * Extract the event ID from a raw Nostr EVENT string WITHOUT JSON parsing.
     *
     * Nostr event IDs are always 64-char lowercase hex. The format of an EVENT
     * message is: ["EVENT","sub-id",{"id":"<64-hex>","pubkey":...}]
     * We scan for the literal `"id":"` marker and grab the next 64 bytes.
     */
    private fun extractEventId(raw: String): String? {
        val marker = "\"id\":\""
        val markerIdx = raw.indexOf(marker)
        if (markerIdx < 0) return null
        val idStart = markerIdx + marker.length
        if (idStart + 64 > raw.length) return null
        val id = raw.substring(idStart, idStart + 64)
        // Validate: must be 64 lowercase hex chars (Nostr spec)
        if (!id.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return id
    }

    internal fun trimDedupCacheIfNeeded() {
        if (seenIds.size <= DEDUP_MAX) return
        // ConcurrentHashMap has no defined iteration order, but removing any 2 k
        // entries is sufficient — we just need an approximate LRU effect.
        var trimmed = 0
        val iter = seenIds.keys.iterator()
        while (iter.hasNext() && trimmed < DEDUP_TRIM) {
            iter.next()
            iter.remove()
            trimmed++
        }
    }

    // ── Event routing ─────────────────────────────────────────────────────────

    private suspend fun handleEvent(id: String, obj: JsonObject, relayUrl: String) {
        val pubkey    = obj["pubkey"]?.jsonPrimitive?.content        ?: return
        val kind      = obj["kind"]?.jsonPrimitive?.intOrNull        ?: return
        val content   = obj["content"]?.jsonPrimitive?.content       ?: return
        val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: return
        val sig       = obj["sig"]?.jsonPrimitive?.content           ?: return
        val tags      = obj["tags"]?.jsonArray ?: JsonArray(emptyList())

        // NIP-40: skip events that have already expired
        val expiration = tags.firstOrNull {
            it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "expiration"
        }?.jsonArray?.getOrNull(1)?.jsonPrimitive?.longOrNull
        if (expiration != null && expiration < nowSeconds) return

        // Skip machine-generated spam: JSON payloads and broadcast protocols
        // posted as kind-1 notes. Normal notes are always plain text/markdown.
        if (kind == 1 && (content.startsWith("{") || content.startsWith("xitchat-broadcast-v1-"))) return

        // Parse NIP-10 threading for content event kinds
        val (replyToId, rootId) = when (kind) {
            1, 6, 9734, 9735, 20, 21, 30023 -> parseNip10Threading(tags)
            else -> Pair(null, null)
        }

        val (hasCw, cwReason) = parseContentWarning(tags)

        // Convert JsonArray tags to List<List<String>>
        val tagsList = tags.map { tag ->
            tag.jsonArray.map { it.jsonPrimitive.content }
        }

        val nostrEvent = NostrEvent(
            id = id,
            pubkey = pubkey,
            kind = kind,
            content = content,
            createdAt = createdAt,
            tags = tagsList,
            tagsJson = tagsToJson(tagsList),
            sig = sig,
            relayUrl = relayUrl,
            replyToId = replyToId,
            rootId = rootId,
            hasContentWarning = hasCw,
            contentWarningReason = cwReason,
            firstSeenAt = System.currentTimeMillis(),
            relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add(relayUrl) },
        )

        // ── Direct-path control-plane updates (not channeled) ────────────────
        // Control-plane kinds (3, 10002) update MemoryEventStore state directly
        // without entering the feed-content channels. They are NOT feed items.
        if (kind == 3) {
            val follows = nostrEvent.tags
                .filter { it.size >= 2 && it[0] == "p" }
                .map { it[1] }
                .toSet()
            memoryEventStore.updateFollows(pubkey, follows, createdAt)
            Log.d(TAG, "Kind-3 direct path: pubkey=${pubkey.take(8)}… ${follows.size} follows (createdAt=$createdAt)")
        }
        // Control-plane events → direct insert into MES (bypass cold channel).
        // These are NOT feed content and need immediate processing:
        // 10002 for outbox prefetch, 10006/10007/10012/30002 for relay config UI,
        // 30385 for trust scores, 30166 for relay monitors (hundreds arrive in
        // burst — cold channel drops via trySend; direct insert ensures none are
        // lost). Both are ephemeral (not snapshot-persisted, re-fetched every bootstrap).
        if (kind in setOf(10002, 10006, 10007, 10012, 30002, 30166, 30385)) {
            memoryEventStore.insert(nostrEvent)
        }
        // Kind-10012 (favorites) may contain ["a", "30002:pubkey:dtag", "hint-relay"]
        // tags referencing relay sets that exist on specific hint relays (not indexers).
        // Resolve these references so the actual relay sets appear in the UI.
        if (kind == 10012) {
            resolveRelaySetRefs(nostrEvent)
        }

        // ── Priority lanes ───────────────────────────────────────────────────
        // Kind-3 is NOT channeled — updateFollows (above) provides the MES
        // update, and the snapshot persists followsByPubkey directly.
        val shouldChannel = kind in setOf(0, 1, 6, 7, 9734, 9735, 20, 21, 30023)
        if (shouldChannel) {
            val isHot = kind == 1 || kind == 6 || kind == 20 || kind == 21 || kind == 30023
            // trySend is non-suspending: drops if full rather than blocking relay consumption.
            // Channels are sized so drops are extremely rare under realistic Nostr traffic.
            if (isHot) hotChannel.trySend(nostrEvent) else coldChannel.trySend(nostrEvent)
        }

        // Dispatch to kind handlers (OutboxRouter for kind 3 / 10002).
        // Launched in a new coroutine so that a slow handler (e.g. OutboxRouter doing
        // Room writes or opening relay connections) never blocks the relay message loop.
        kindHandlers[kind]?.let { handler -> scope.launch { handler(obj) } }
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

        for (event in events.values) {
            memoryEventStore.insert(event)
            // Pre-compute media metadata + EventModel at insert time (sidecar caches).
            // Parse imeta once for all downstream consumers.
            if (event.kind in setOf(1, 6, 20, 21, 30023)) {
                val imetaMedia = ImetaParser.parseFromList(event.tags)

                if (event.kind in setOf(1, 6, 20, 21)) {
                    val models = buildVideoRenderModels(event.kind, event.content, event.tags)
                    memoryEventStore.putVideoRenderModels(event.id, models)
                    val imageDims = imetaMedia
                        .filter { it.mimeType?.startsWith("image/") == true && it.width != null && it.height != null && it.height != 0 }
                        .associate { it.url to (it.width!!.toFloat() / it.height!!) }
                    memoryEventStore.putImetaImageDims(event.id, imageDims)
                }

                val model = com.unsilence.app.data.model.ContentParser.parse(
                    id = event.id,
                    pubkey = event.pubkey,
                    kind = event.kind,
                    content = event.content,
                    tagsJson = event.tagsJson,
                    createdAt = event.createdAt,
                    relayUrl = event.relayUrl,
                    replyToId = event.replyToId,
                    rootId = event.rootId,
                    hasContentWarning = event.hasContentWarning,
                    contentWarningReason = event.contentWarningReason,
                    preparsedImeta = imetaMedia,
                )
                memoryEventStore.putEventModel(event.id, model)
            }
        }
    }

    // ── NIP-10: threading ─────────────────────────────────────────────────────

    /**
     * Returns (replyToId, rootId) parsed from `e` tags.
     *
     * Priority: explicit "root"/"reply" markers. Fallback: positional
     * (first e = root, last e = reply-to). If only one e tag, it is the root.
     */
    internal fun parseNip10Threading(tags: JsonArray): Pair<String?, String?> {
        val eTags = tags.filter { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "e" }
            .map { it.jsonArray }

        if (eTags.isEmpty()) return Pair(null, null)

        // Marker-based (NIP-10 recommended)
        val rootId    = eTags.firstOrNull { it.getOrNull(3)?.jsonPrimitive?.content == "root" }
            ?.getOrNull(1)?.jsonPrimitive?.content
        val replyToId = eTags.firstOrNull { it.getOrNull(3)?.jsonPrimitive?.content == "reply" }
            ?.getOrNull(1)?.jsonPrimitive?.content

        if (rootId != null || replyToId != null) {
            // If root marker exists but no reply marker, the reply target IS the root
            return Pair(replyToId ?: rootId, rootId)
        }

        // Positional fallback
        val ids = eTags.mapNotNull { it.getOrNull(1)?.jsonPrimitive?.content }
        return when (ids.size) {
            0    -> Pair(null, null)
            1    -> Pair(ids[0], ids[0])   // single e = both root and reply-to
            else -> Pair(ids.last(), ids.first())
        }
    }

    // ── NIP-36: content-warning ───────────────────────────────────────────────

    private fun parseContentWarning(tags: JsonArray): Pair<Boolean, String?> {
        val cwTag = tags.firstOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "content-warning"
        }?.jsonArray ?: return Pair(false, null)

        val reason = cwTag.getOrNull(1)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        return Pair(true, reason)
    }
}
