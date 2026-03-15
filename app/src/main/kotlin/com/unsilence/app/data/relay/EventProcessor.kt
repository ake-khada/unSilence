package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.db.dao.EventDao
import com.unsilence.app.data.db.dao.EventRelayDao
import com.unsilence.app.data.db.dao.EventStatsDao
import com.unsilence.app.data.db.dao.ReactionDao
import com.unsilence.app.data.db.dao.TagDao
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.db.entity.EventRelayEntity
import com.unsilence.app.data.db.entity.EventStatsEntity
import com.unsilence.app.data.db.entity.ReactionEntity
import com.unsilence.app.data.db.entity.TagEntity
import com.unsilence.app.data.db.entity.UserEntity
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
import com.vitorpamplona.quartz.lightning.LnInvoiceUtil
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EventProcessor"

// Dedup cache limits
private const val DEDUP_MAX  = 10_000
private const val DEDUP_TRIM = 2_000

/**
 * Parsed, ready-to-persist entity. The sealed type encodes which table it targets so
 * [flushBatch] can route without another when-expression.
 */
sealed class ProcessedEvent {
    data class Event(val entity: EventEntity)       : ProcessedEvent()
    data class User(val entity: UserEntity)         : ProcessedEvent()
    data class Reaction(val entity: ReactionEntity) : ProcessedEvent()
}

/**
 * Parses raw relay wire messages and writes valid events to Room.
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
 *       HOT  (cap 50 ): kinds 1, 6, 20, 21, 30023 — feed content, flushed every 100 ms.
 *       COLD (cap 500): kinds 0, 7, 9735           — background data, flushed every 2 s.
 *
 *  4. BATCHED ROOM WRITES — drainer coroutines collect from their channel, then call
 *     a single batch-insert DAO method instead of one INSERT per event.
 *
 *  5. WRITE COALESCING — before each flush, duplicates are removed by primary key so
 *     that one event arriving from 5 relays produces exactly one INSERT.
 */
@Singleton
class EventProcessor @Inject constructor(
    private val eventDao: EventDao,
    private val userDao: UserDao,
    private val reactionDao: ReactionDao,
    private val eventStatsDao: EventStatsDao,
    private val tagDao: TagDao,
    private val eventRelayDao: EventRelayDao,
    private val outboxRouter: dagger.Lazy<OutboxRouter>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nowSeconds: Long get() = System.currentTimeMillis() / 1000L

    // ── 1. Dedup cache ────────────────────────────────────────────────────────

    /**
     * Set of recently-seen event IDs. ConcurrentHashMap<K,Unit> is the idiomatic
     * Kotlin/Java concurrent set. Trimmed when it exceeds [DEDUP_MAX].
     */
    private val seenIds = ConcurrentHashMap<String, Unit>(DEDUP_MAX + DEDUP_TRIM)

    // ── 3. Priority channels ──────────────────────────────────────────────────

    /** HOT lane: feed content (kind 1, 6, 20, 21, 30023). Flushed every 100 ms.
     *  Capacity 500: initial load from 19 relays × 500 limit = up to 9 500 kind 1 events
     *  can burst in before the first drain. trySend drops silently, so we size generously. */
    private val hotChannel  = Channel<ProcessedEvent>(capacity = 500)

    /** COLD lane: background data (kind 0, 7, 9735). Flushed every 2 s. */
    private val coldChannel = Channel<ProcessedEvent>(capacity = 500)

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
    suspend fun process(raw: String, relayUrl: String) {
        // ── Fix: early return for non-EVENT messages before ANY JSON work ──────
        if (!raw.startsWith("[\"EVENT\"")) return

        // ── Fix 1: dedup by event ID, extracted without JSON parsing ──────────
        val eventId = extractEventId(raw) ?: return
        if (seenIds.putIfAbsent(eventId, Unit) != null) return   // already seen — zero cost
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

    private fun trimDedupCacheIfNeeded() {
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
        val tagsJson  = obj["tags"]?.toString()                      ?: "[]"
        val tags      = obj["tags"]?.jsonArray ?: JsonArray(emptyList())

        // NIP-40: skip events that have already expired
        val expiration = tags.firstOrNull {
            it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "expiration"
        }?.jsonArray?.getOrNull(1)?.jsonPrimitive?.longOrNull
        if (expiration != null && expiration < nowSeconds) return

        // Skip machine-generated spam (e.g. xitchat broadcast JSON)
        if (kind == 1 && content.startsWith("xitchat-broadcast-v1-")) return

        // Build the entity and route to the appropriate priority lane
        val processed: ProcessedEvent? = when (kind) {
            0 -> buildUserEvent(pubkey, content)
            7 -> buildReactionEvent(id, pubkey, createdAt, content, tags)
            1, 6, 9734, 9735, 20, 21, 30023 -> buildContentEvent(
                id, pubkey, kind, content, createdAt, tagsJson, sig, tags, relayUrl
            )
            else -> null
        }

        // ── Fix 4: priority lanes ─────────────────────────────────────────────
        if (processed != null) {
            val isHot = kind == 1 || kind == 6 || kind == 20 || kind == 21 || kind == 30023
            // trySend is non-suspending: drops if full rather than blocking relay consumption.
            // Channels are sized so drops are extremely rare under realistic Nostr traffic.
            if (isHot) hotChannel.trySend(processed) else coldChannel.trySend(processed)
        }

        // Dispatch to kind handlers (OutboxRouter for kind 3 / 10002).
        // Launched in a new coroutine so that a slow handler (e.g. OutboxRouter doing
        // Room writes or opening relay connections) never blocks the relay message loop.
        kindHandlers[kind]?.let { handler -> scope.launch { handler(obj) } }
    }

    // ── Entity builders (no DB access — just data class construction) ─────────

    private fun buildUserEvent(pubkey: String, content: String): ProcessedEvent.User? {
        return try {
            val meta = NostrJson.parseToJsonElement(content).jsonObject
            fun str(key: String) = meta[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ProcessedEvent.User(
                UserEntity(
                    pubkey      = pubkey,
                    name        = str("name"),
                    displayName = str("display_name"),
                    about       = str("about"),
                    picture     = str("picture"),
                    nip05       = str("nip05"),
                    lud16       = str("lud16"),
                    banner      = str("banner"),
                    updatedAt   = nowSeconds,
                )
            )
        } catch (_: Exception) {
            Log.w(TAG, "Bad metadata for $pubkey")
            null
        }
    }

    private fun buildReactionEvent(
        id: String, pubkey: String, createdAt: Long, content: String, tags: JsonArray,
    ): ProcessedEvent.Reaction? {
        val targetId = tags.lastOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "e"
        }?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content ?: return null
        return ProcessedEvent.Reaction(
            ReactionEntity(
                eventId       = id,
                targetEventId = targetId,
                pubkey        = pubkey,
                content       = content.ifBlank { "+" },
                createdAt     = createdAt,
            )
        )
    }

    private fun buildContentEvent(
        id: String, pubkey: String, kind: Int, content: String, createdAt: Long,
        tagsJson: String, sig: String, tags: JsonArray, relayUrl: String,
    ): ProcessedEvent.Event {
        val (replyToId, rootId) = parseNip10Threading(tags)
        val (hasCw, cwReason)   = parseContentWarning(tags)
        return ProcessedEvent.Event(
            EventEntity(
                id                   = id,
                pubkey               = pubkey,
                kind                 = kind,
                content              = content,
                createdAt            = createdAt,
                tags                 = tagsJson,
                sig                  = sig,
                relayUrl             = relayUrl,
                replyToId            = replyToId,
                rootId               = rootId,
                hasContentWarning    = hasCw,
                contentWarningReason = cwReason,
                cachedAt             = nowSeconds,
            )
        )
    }

    // ── Fix 2 + 4: Channel drainers ───────────────────────────────────────────

    /**
     * HOT drainer: collects up to 100 feed events within a 100 ms window, then
     * flushes. The withTimeoutOrNull provides natural pacing without busy-waiting.
     */
    private suspend fun drainHot() {
        val buffer = ArrayDeque<ProcessedEvent>(100)
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
        val buffer = ArrayDeque<ProcessedEvent>(200)
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

    // ── Zap sats extraction ─────────────────────────────────────────────

    /** Extract a tag value from a JSON-serialized tags array: [["key","value"],...] */
    private fun tagValue(tagsJson: String, key: String): String? = runCatching {
        NostrJson.parseToJsonElement(tagsJson).jsonArray
            .firstOrNull { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == key }
            ?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content
    }.getOrNull()

    /**
     * Extract sats from a kind-9735 zap receipt's tags.
     * Primary: parse bolt11 invoice via Quartz's LnInvoiceUtil.
     * Fallback: read "amount" tag from embedded zap request (millisats).
     */
    private fun extractZapSats(tagsJson: String): Long {
        // Primary: parse bolt11 tag via Quartz's LnInvoiceUtil
        val bolt11 = tagValue(tagsJson, "bolt11")
        if (bolt11 != null) {
            try {
                val sats = LnInvoiceUtil.getAmountInSats(bolt11)
                if (sats > BigDecimal.ZERO) return sats.toLong()
            } catch (_: Exception) { }
        }

        // Fallback: read "amount" tag from embedded zap request (millisats)
        val descriptionJson = tagValue(tagsJson, "description") ?: return 0L
        return try {
            val zapRequest = NostrJson.parseToJsonElement(descriptionJson).jsonObject
            val tags = zapRequest["tags"]?.jsonArray
            val amountTag = tags?.firstOrNull { tag ->
                tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "amount"
            }
            val msats = amountTag?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content?.toLongOrNull()
            (msats ?: 0L) / 1_000L
        } catch (_: Exception) { 0L }
    }

    private suspend fun flushBatch(batch: List<ProcessedEvent>) {
        // LinkedHashMap preserves insertion order while deduplicating by key
        val events    = LinkedHashMap<String, EventEntity>()
        val users     = LinkedHashMap<String, UserEntity>()
        val reactions = LinkedHashMap<String, ReactionEntity>()

        for (item in batch) {
            when (item) {
                is ProcessedEvent.Event    -> events[item.entity.id]          = item.entity
                is ProcessedEvent.User     -> users[item.entity.pubkey]       = item.entity
                is ProcessedEvent.Reaction -> reactions[item.entity.eventId]  = item.entity
            }
        }

        // Single batch insert per table — one SQLite write-lock acquisition instead of N
        if (events.isNotEmpty())    eventDao.insertOrIgnoreBatch(events.values.toList())
        if (users.isNotEmpty())     userDao.upsertBatch(users.values.toList())
        if (reactions.isNotEmpty()) reactionDao.insertOrIgnoreBatch(reactions.values.toList())

        // Insert tags and event_relays for content events
        for (entity in events.values) {
            // Insert event_relay (provenance tracking)
            try {
                eventRelayDao.insertOrIgnore(
                    EventRelayEntity(
                        eventId = entity.id,
                        relayUrl = entity.relayUrl,
                        seenAt = entity.createdAt,
                    )
                )
            } catch (_: Exception) { }

            // Parse and insert tags
            try {
                val tagsArray = NostrJson.parseToJsonElement(entity.tags).jsonArray
                val tagEntities = tagsArray.mapIndexedNotNull { index, element ->
                    val tag = element.jsonArray
                    if (tag.size < 2) return@mapIndexedNotNull null
                    val tagName = tag[0].jsonPrimitive.content
                    val tagValue = tag[1].jsonPrimitive.content
                    val extra = tag.getOrNull(2)?.jsonPrimitive?.content
                    TagEntity(
                        eventId = entity.id,
                        tagName = tagName,
                        tagPos = index,
                        tagValue = tagValue,
                        extra = extra,
                    )
                }
                if (tagEntities.isNotEmpty()) {
                    tagDao.insertAll(tagEntities)
                }
            } catch (_: Exception) { }

            // Update stats for parent events
            when (entity.kind) {
                1 -> {
                    // Reply: increment reply count on parent
                    entity.replyToId?.let { eventStatsDao.incrementReplyCount(it) }
                    if (entity.rootId != null && entity.rootId != entity.replyToId) {
                        eventStatsDao.incrementReplyCount(entity.rootId)
                    }
                }
                6 -> {
                    // Repost: increment repost count on original
                    entity.rootId?.let { eventStatsDao.incrementRepostCount(it) }
                }
                9735 -> {
                    // Zap receipt: increment zap stats on target
                    if (entity.rootId != null) {
                        val sats = extractZapSats(entity.tags)
                        eventStatsDao.incrementZapStats(entity.rootId, sats)
                    }
                }
            }
        }

        // Update reaction stats
        for (entity in reactions.values) {
            eventStatsDao.incrementReactionCount(entity.targetEventId)
        }
    }

    // ── NIP-10: threading ─────────────────────────────────────────────────────

    /**
     * Returns (replyToId, rootId) parsed from `e` tags.
     *
     * Priority: explicit "root"/"reply" markers. Fallback: positional
     * (first e = root, last e = reply-to). If only one e tag, it is the root.
     */
    private fun parseNip10Threading(tags: JsonArray): Pair<String?, String?> {
        val eTags = tags.filter { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "e" }
            .map { it.jsonArray }

        if (eTags.isEmpty()) return Pair(null, null)

        // Marker-based (NIP-10 recommended)
        val rootId    = eTags.firstOrNull { it.getOrNull(3)?.jsonPrimitive?.content == "root" }
            ?.getOrNull(1)?.jsonPrimitive?.content
        val replyToId = eTags.firstOrNull { it.getOrNull(3)?.jsonPrimitive?.content == "reply" }
            ?.getOrNull(1)?.jsonPrimitive?.content

        if (rootId != null || replyToId != null) return Pair(replyToId, rootId)

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
