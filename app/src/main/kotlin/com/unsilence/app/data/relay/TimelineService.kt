package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.NostrEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TimelineService"

/** Max time we wait for the FAST tier to EOSE before kicking off the SLOW
 *  tier anyway. Picked so that on a healthy network the user already has
 *  events on screen by the time obscure outbox relays start handshaking,
 *  but a slow / partial FAST tier still gets long-tail coverage. */
private const val SLOW_TIER_WATCHDOG_MS = 2_000L

/** Poll interval while waiting for FAST EOSEs. Coarse — we just need to
 *  wake up promptly when allEosed flips. */
private const val SLOW_TIER_POLL_MS = 50L

/**
 * EOSE-aware multi-relay timeline subscription with persistent ref cache.
 *
 * Mirrors Jumble's client.subscribeTimeline (client.service.ts:328) and
 * _subscribeTimeline (client.service.ts:576) — the scheduler that wraps
 * the lower-level Subscription primitive (P1) with:
 *
 *   1. Per-subscribe-call cache of TimelineRef (id, createdAt) — second
 *      subscribe to the same {urls, filter} hits the cache and injects
 *      since=head.createdAt+1 into the new REQ.
 *
 *   2. Pre-EOSE buffering — events arriving before EOSE are collected,
 *      sorted by compareEventsDesc, sliced to filter.limit. Single
 *      onEvents(events, eosed) callback per relay-EOSE.
 *
 *   3. Threshold merge — onEvents fires once half of the SubRequests in
 *      the call have EOSE'd, so the user sees content as fast as possible
 *      even when one relay is slow.
 *
 *   4. Post-EOSE live tail — events with created_at > globalEosedAt are
 *      fired via onNew and inserted into the timeline refs at the correct
 *      sorted position. Events older than head are dropped.
 *
 * Threading: subscribeTimeline is suspend (IO). Mutations to per-call
 * state happen under per-state-object synchronized blocks. Timeline cache
 * itself is a ConcurrentHashMap; refs lists are immutable and replaced
 * atomically (copy-on-write).
 */
@Singleton
class TimelineService @Inject constructor(
    private val subscription: Subscription,
    private val eventLoader: TimelineEventLoader,
) {
    /** Dispatcher for fire-and-forget subscribe coroutines. Tests override with Unconfined. */
    internal var subscribeDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
    private data class Timeline(
        val refs: List<TimelineRef>,
        val filter: NostrFilter,
        val urls: List<String>,
    )

    private val timelines = ConcurrentHashMap<String, Timeline>()
    private val multiKeys = ConcurrentHashMap<String, List<String>>()
    private val seqCounter = AtomicLong(0)

    interface TimelineHandle {
        val timelineKey: String
        fun close()
    }

    suspend fun subscribeTimeline(
        subRequests: List<SubRequest>,
        onEvents: (events: List<NostrEvent>, eosed: Boolean) -> Unit,
        onNew: (event: NostrEvent) -> Unit = {},
        onClose: ((url: String, reason: String) -> Unit)? = null,
        needSort: Boolean = true,
    ): TimelineHandle {
        val multiKey = generateMultiKey(subRequests)
        val perSubKeys = Collections.synchronizedList(mutableListOf<String>())
        val perSubTimelines = Array<List<NostrEvent>>(subRequests.size) { emptyList() }
        val eosedCount = AtomicInteger(0)
        val multiHandles = Collections.synchronizedList(mutableListOf<Subscription.Handle>())

        // Cross-sub dedup for onNew (Jumble's newEventIdSet)
        val newEventIdSet = ConcurrentHashMap.newKeySet<String>()

        // Fire-and-forget: launch all subscribeSingle calls concurrently and
        // return immediately. Jumble doesn't block on the subscribe loop —
        // the handle is returned right away, callbacks fire as EOSEs arrive.
        // The subScope is owned by the TimelineHandle; close() cancels it.
        val subScope = CoroutineScope(SupervisorJob() + subscribeDispatcher)

        // ── Lazy outbox: defer SLOW-tier connects ────────────────────────
        // The resolver tags low-priority outbox write relays as SubTier.SLOW.
        // We launch FAST-tier subscribeSingles immediately, then either wait
        // for ALL FAST tier to EOSE or for SLOW_TIER_WATCHDOG_MS, whichever
        // comes first, before launching SLOW. If the user closes/swaps the
        // feed before then, subScope.cancel() drops the deferred coroutines
        // and those WebSockets are never opened.
        val fastIndices = subRequests.withIndex()
            .filter { it.value.tier == SubTier.FAST }
            .map { it.index }
        val slowIndices = subRequests.withIndex()
            .filter { it.value.tier == SubTier.SLOW }
            .map { it.index }
        val fastEosedCount = AtomicInteger(0)

        val launchSingle: (Int) -> Unit = { index ->
            val sr = subRequests[index]
            subScope.launch {
                subscribeSingle(
                    index = index,
                    subRequest = sr,
                    onPerSubEvents = { events, eosed ->
                        perSubTimelines[index] = events
                        if (eosed) {
                            eosedCount.incrementAndGet()
                            if (sr.tier == SubTier.FAST) fastEosedCount.incrementAndGet()
                        }
                        // Emit on every per-sub update so cached events render
                        // immediately and pre-EOSE batches stream through. The
                        // allEosed flag tells consumers when the load is final.
                        val merged = mergeTimelines(perSubTimelines.toList(), sr.filter.limit)
                        if (merged.isNotEmpty()) {
                            val allEosed = eosedCount.get() >= subRequests.size
                            try { onEvents(merged, allEosed) } catch (t: Throwable) {
                                Log.w(TAG, "onEvents threw", t)
                            }
                        }
                    },
                    onNew = { evt ->
                        if (newEventIdSet.add(evt.id)) {
                            try { onNew(evt) } catch (t: Throwable) {
                                Log.w(TAG, "onNew threw", t)
                            }
                        }
                    },
                    onClose = onClose,
                    needSort = needSort,
                    outerKeysCollector = perSubKeys,
                    outerHandleCollector = multiHandles,
                )
            }
        }

        for (index in fastIndices) launchSingle(index)

        if (slowIndices.isNotEmpty()) {
            subScope.launch {
                if (fastIndices.isEmpty()) {
                    // No FAST tier to gate on — fall back to a fixed delay so
                    // we never block the slow tier indefinitely.
                    delay(SLOW_TIER_WATCHDOG_MS)
                } else {
                    val deadline = System.currentTimeMillis() + SLOW_TIER_WATCHDOG_MS
                    while (fastEosedCount.get() < fastIndices.size &&
                        System.currentTimeMillis() < deadline) {
                        delay(SLOW_TIER_POLL_MS)
                    }
                }
                Log.d(TAG, "lazy outbox: launching ${slowIndices.size} SLOW-tier sub(s) " +
                    "(fastEosed=${fastEosedCount.get()}/${fastIndices.size})")
                for (index in slowIndices) launchSingle(index)
            }
        }

        // perSubKeys grows as subs complete — store the live reference.
        // loadMoreTimeline reads it later when the user scrolls down.
        multiKeys[multiKey] = perSubKeys

        return object : TimelineHandle {
            override val timelineKey = multiKey
            @Volatile private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                subScope.cancel()
                multiHandles.forEach { runCatching { it.close() } }
                multiKeys.remove(multiKey)
            }
        }
    }

    private suspend fun subscribeSingle(
        index: Int,
        subRequest: SubRequest,
        onPerSubEvents: (events: List<NostrEvent>, eosed: Boolean) -> Unit,
        onNew: (NostrEvent) -> Unit,
        onClose: ((String, String) -> Unit)?,
        needSort: Boolean,
        outerKeysCollector: MutableList<String>,
        outerHandleCollector: MutableList<Subscription.Handle>,
    ) {
        val key = generateTimelineKey(subRequest.urls, subRequest.filter)
        outerKeysCollector.add(key)

        // ── Cache lookup. Jumble client.service.ts:600-610 ──────────────────
        val cached: Timeline? = if (needSort) timelines[key] else null
        var since: Long? = null
        var cachedEvents: List<NostrEvent> = emptyList()
        if (cached != null && cached.refs.isNotEmpty()) {
            cachedEvents = eventLoader.getEvents(cached.refs.map { it.id })
            if (cachedEvents.isNotEmpty()) {
                onPerSubEvents(cachedEvents, false)
                since = (cachedEvents[0].createdAt + 1)
                    .coerceAtMost(System.currentTimeMillis() / 1000L)
            }
        }

        // ── Per-sub state. All mutations under stateLock. ──────────────────
        val stateLock = Any()
        val events = mutableListOf<NostrEvent>()
        var eosedAt: Long? = null
        var eosed = false

        val effectiveFilter = if (since != null) subRequest.filter.copy(since = since) else subRequest.filter

        val handle = subscription.subscribe(
            urls = subRequest.urls,
            filter = effectiveFilter,
            onevent = { evt ->
                synchronized(stateLock) {
                    if (eosedAt == null) {
                        // Pre-EOSE: buffer
                        events.add(evt)
                    } else {
                        // Post-EOSE: live tail
                        val cutoff = eosedAt!!
                        if (evt.createdAt > cutoff) {
                            onNew(evt)
                            insertIntoTimelineRefs(key, evt)
                        }
                    }
                }
            },
            oneose = { allRelaysEosed ->
                val toEmit: List<NostrEvent>
                synchronized(stateLock) {
                    if (allRelaysEosed && !eosed) {
                        eosed = true
                        if (eosedAt == null) {
                            eosedAt = System.currentTimeMillis() / 1000L
                        }
                    }
                }

                // Jumble client.service.ts:661-666
                if (!needSort) {
                    toEmit = synchronized(stateLock) { events.toList() }
                } else {
                    val snapshot = synchronized(stateLock) { events.toList() }
                    val sorted = snapshot.sortedWith(compareEventsDesc)
                    val limit = effectiveFilter.limit
                    val sliced = if (limit != null && sorted.size > limit) sorted.take(limit) else sorted
                    // Jumble line 666: events.concat(cachedEvents).slice(0, filter.limit)
                    // Append cached events not present in the new batch (older history)
                    val combined = sliced + cachedEvents.filter { c -> sliced.none { it.id == c.id } }
                    toEmit = if (limit != null && combined.size > limit) combined.take(limit) else combined
                }
                onPerSubEvents(toEmit, allRelaysEosed)

                // ── On full EOSE: merge into persistent timeline cache ─────
                // Jumble client.service.ts:675-703
                if (allRelaysEosed) {
                    val snapshot = synchronized(stateLock) { events.toList() }
                    val sortedFinal = if (needSort) snapshot.sortedWith(compareEventsDesc) else snapshot
                    val limit = effectiveFilter.limit
                    val newRefs = if (limit != null && sortedFinal.size > limit) {
                        sortedFinal.take(limit).map { TimelineRef(it.id, it.createdAt) }
                    } else {
                        sortedFinal.map { TimelineRef(it.id, it.createdAt) }
                    }
                    val existing = timelines[key]
                    if (existing == null || existing.refs.isEmpty()) {
                        // No cache yet — Jumble line 678
                        timelines[key] = Timeline(newRefs, subRequest.filter, subRequest.urls)
                    } else {
                        // Merge with existing — Jumble lines 687-703
                        val firstExistingCreatedAt = existing.refs.first().createdAt
                        val freshRefs = newRefs.filter { it.createdAt > firstExistingCreatedAt }
                        if (limit != null && freshRefs.size >= limit) {
                            // New refs fully replace old — Jumble line 694
                            timelines[key] = Timeline(freshRefs, subRequest.filter, subRequest.urls)
                        } else {
                            // Merge new + old — Jumble line 701
                            timelines[key] = Timeline(freshRefs + existing.refs, subRequest.filter, subRequest.urls)
                        }
                    }
                }
            },
            onclose = onClose ?: { _, _ -> },
        )

        outerHandleCollector.add(handle)
    }

    /**
     * Insert a live-tail event into the timeline refs at the correct sorted
     * position. Mirrors Jumble client.service.ts:639-654.
     */
    private fun insertIntoTimelineRefs(key: String, evt: NostrEvent) {
        val timeline = timelines[key] ?: return
        val refs = timeline.refs
        if (refs.isEmpty()) return

        var idx = 0
        while (idx < refs.size) {
            val ref = refs[idx]
            if (evt.createdAt > ref.createdAt) break
            if (evt.createdAt == ref.createdAt) {
                if (evt.id == ref.id) return  // already in cache
                if (evt.id < ref.id) break
            }
            idx++
        }
        if (idx >= refs.size) return  // older than tail — drop

        val newRefs = refs.toMutableList()
        newRefs.add(idx, TimelineRef(evt.id, evt.createdAt))
        timelines[key] = timeline.copy(refs = newRefs)
    }

    /**
     * Load older events from the timeline cache. Mirrors Jumble's
     * _loadMoreTimeline (client.service.ts:718).
     */
    suspend fun loadMoreTimeline(
        timelineKey: String,
        until: Long,
        limit: Int,
    ): List<NostrEvent> {
        val keys = multiKeys[timelineKey] ?: listOf(timelineKey)
        val gathered = mutableListOf<TimelineRef>()
        for (k in keys) {
            val tl = timelines[k] ?: continue
            gathered.addAll(tl.refs.filter { it.createdAt < until })
        }
        gathered.sortWith(compareTimelineRefsDesc)
        val ids = gathered.take(limit).map { it.id }
        return eventLoader.getEvents(ids)
    }

    // ── Test helpers ────────────────────────────────────────────────────────
    internal fun resetForTest() {
        timelines.clear()
        multiKeys.clear()
    }

    internal fun timelineForTest(key: String): List<TimelineRef>? = timelines[key]?.refs

    // ── Internals ──────────────────────────────────────────────────────────
    private fun generateMultiKey(subRequests: List<SubRequest>): String {
        val parts = subRequests.flatMap { it.urls + it.filter.toJsonObject().toString() }.sorted()
        return sha256(parts.joinToString("|")).take(16) + "-multi"
    }

    internal fun generateTimelineKey(urls: List<String>, filter: NostrFilter): String {
        val payload = urls.sorted().joinToString(",") + "|" + filter.toJsonObject().toString()
        return sha256(payload).take(16)
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Sort key for Nostr events. DESC by createdAt; ties broken by
         * lexically-smaller id wins (matches NIP-01 replaceable retention
         * and Jumble's compareEvents in lib/event.ts:439).
         */
        val compareEventsDesc: Comparator<NostrEvent> = Comparator { a, b ->
            when {
                a.createdAt != b.createdAt -> b.createdAt.compareTo(a.createdAt)
                a.id != b.id -> a.id.compareTo(b.id)
                else -> 0
            }
        }

        val compareTimelineRefsDesc: Comparator<TimelineRef> = Comparator { a, b ->
            when {
                a.createdAt != b.createdAt -> b.createdAt.compareTo(a.createdAt)
                a.id != b.id -> a.id.compareTo(b.id)
                else -> 0
            }
        }
    }
}

/**
 * K-way merge of multiple per-relay timelines. Mirrors Jumble's
 * mergeTimelines in lib/timeline.ts. Each input is expected to be already
 * sorted desc by [TimelineService.compareEventsDesc]. Output is sorted
 * desc, dedup'd by id, sliced to [limit].
 */
fun mergeTimelines(timelines: List<List<NostrEvent>>, limit: Int? = null): List<NostrEvent> {
    if (timelines.isEmpty()) return emptyList()
    if (timelines.size == 1) return timelines[0]
    return timelines.fold(emptyList()) { acc, current ->
        mergeTwo(acc, current, limit)
    }
}

private fun mergeTwo(a: List<NostrEvent>, b: List<NostrEvent>, limit: Int?): List<NostrEvent> {
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    val out = ArrayList<NostrEvent>(minOf(a.size + b.size, limit ?: Int.MAX_VALUE))
    var i = 0
    var j = 0
    val seen = HashSet<String>(a.size + b.size)
    while (i < a.size && j < b.size) {
        val cmp = TimelineService.compareEventsDesc.compare(a[i], b[j])
        when {
            cmp < 0 -> { if (seen.add(a[i].id)) out.add(a[i]); i++ }
            cmp > 0 -> { if (seen.add(b[j].id)) out.add(b[j]); j++ }
            else -> { if (seen.add(a[i].id)) out.add(a[i]); i++; j++ }
        }
        if (limit != null && out.size >= limit) return out
    }
    while (i < a.size && (limit == null || out.size < limit)) {
        if (seen.add(a[i].id)) out.add(a[i]); i++
    }
    while (j < b.size && (limit == null || out.size < limit)) {
        if (seen.add(b[j].id)) out.add(b[j]); j++
    }
    return out
}
