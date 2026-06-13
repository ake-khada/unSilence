package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.repository.UserRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfilePipeline"

/** How the pipeline anchors events against MES eviction. */
enum class AnchorPolicy {
    /** Own profile — events anchored via ownPubkey, refs added to profileAnchoredIds. */
    OWN,
    /** Other user's profile — events anchored via viewedPubkey (caller MUST set
     *  [MemoryEventStore.viewedPubkey] BEFORE calling [ProfilePipeline.loadProfile]). */
    VIEWED,
    /** No eviction protection. */
    NONE,
}

/**
 * Bounded eager pipeline that pre-fetches everything needed for a profile
 * in one pass: notes, referenced events (quoted notes, repost targets,
 * thread parents), engagement, and own-engagement markers.
 *
 * Replaces lazy viewport-driven hydration for profile screens.
 *
 * **Ordering contract:** For [AnchorPolicy.VIEWED], the caller MUST set
 * [MemoryEventStore.viewedPubkey] to the target pubkey BEFORE calling
 * [loadProfile]. The pipeline's eviction anchor relies on this being set
 * so that events arriving during fetch are protected from mid-fetch eviction.
 */
@Singleton
class ProfilePipeline @Inject constructor(
    private val relayPool: RelayPool,
    private val memoryEventStore: MemoryEventStore,
    private val userRepository: UserRepository,
    private val relayPreferencesStore: RelayPreferencesStore,
) {
    companion object {
        /** Kinds fetched for profile notes (notes + reposts + generic reposts + pictures + videos + articles). */
        val PROFILE_KINDS = setOf(1, 6, 16, 20, 21, 30023)
        private const val ENGAGEMENT_CHUNK_SIZE = 50
        private const val ENGAGEMENT_TIMEOUT_MS = 10_000L
        private const val REF_WAIT_MS = 1500L
        /** If MES has notes newer than this, use delta mode (since cursor). */
        private const val DELTA_THRESHOLD_DAYS = 7L
    }

    /** Owns deduped pipeline work — survives individual callers so cancelling
     *  one waiter (e.g. a ViewModel clearing) doesn't kill another's join. */
    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Per-pubkey in-flight loadProfile runs. */
    private val inFlight = ConcurrentHashMap<String, Job>()

    /** Per-pubkey in-flight follower-count fetches — concurrent callers share one result. */
    private val followerCountInFlight = ConcurrentHashMap<String, Deferred<Long?>>()

    /** Own pubkeys whose below-head gap was healed this session — heal runs once per login. */
    private val gapHealedOwnPubkeys = ConcurrentHashMap.newKeySet<String>()

    /** Reset per-session state. Called once per login from AppBootstrapper (this is a
     *  @Singleton that survives logout/login, so the heal flag must be cleared explicitly). */
    fun resetForSession() {
        gapHealedOwnPubkeys.clear()
    }

    /**
     * Load a full profile: notes, refs, engagement, own-engagement.
     * Each step is fail-soft — a network error in one step doesn't abort subsequent steps.
     *
     * In-flight dedup per pubkey: AppBootstrapper Phase 2 and the profile
     * ViewModels can race the same pubkey — the second caller joins the first
     * run instead of duplicating paginated fetches + engagement sweeps.
     *
     * @param pubkey hex pubkey of the profile to load.
     * @param isOwn true if this is the logged-in user's own profile.
     * @param anchorPolicy eviction protection strategy.
     * @param maxPages max relay pagination pages (per relay) for note backfill.
     */
    suspend fun loadProfile(
        pubkey: String,
        isOwn: Boolean,
        anchorPolicy: AnchorPolicy,
        maxPages: Int = 5,
    ) {
        val newJob = pipelineScope.launch(start = CoroutineStart.LAZY) {
            runLoadProfile(pubkey, isOwn, anchorPolicy, maxPages)
        }
        // Atomic register-or-adopt: keep an existing active run, else install ours.
        val job = inFlight.compute(pubkey) { _, existing ->
            if (existing?.isActive == true) existing else newJob
        }!!
        if (job === newJob) {
            job.invokeOnCompletion { inFlight.remove(pubkey, job) }
            job.start()
        } else {
            newJob.cancel()
            Log.d(TAG, "loadProfile: joining in-flight run for ${pubkey.take(8)}…")
        }
        job.join()
    }

    private suspend fun runLoadProfile(
        pubkey: String,
        isOwn: Boolean,
        anchorPolicy: AnchorPolicy,
        maxPages: Int,
    ) {
        val startMs = System.currentTimeMillis()
        Log.d(TAG, "loadProfile: ${pubkey.take(8)}… isOwn=$isOwn anchor=$anchorPolicy")

        // ── Step 1: Resolve relays ─────────────────────────────────────
        val writeRelays = resolveWriteRelays(pubkey)
        Log.d(TAG, "Step1: ${writeRelays.size} write relays for ${pubkey.take(8)}…")

        // ── Step 2: Paginated note fetch (delta or full backfill) ──────
        val noteEvents = try {
            fetchNotes(pubkey, writeRelays, maxPages, isOwn)
        } catch (e: Exception) {
            Log.w(TAG, "Step2 failed: ${e.message}")
            memoryEventStore.userEvents(pubkey, PROFILE_KINDS, 2500)
        }
        Log.d(TAG, "Step2: ${noteEvents.size} notes for ${pubkey.take(8)}…")

        // ── Step 3: Ref hydration ──────────────────────────────────────
        val refIds = try {
            hydrateRefs(noteEvents, anchorPolicy)
        } catch (e: Exception) {
            Log.w(TAG, "Step3 failed: ${e.message}")
            emptySet()
        }
        Log.d(TAG, "Step3: ${refIds.size} refs hydrated for ${pubkey.take(8)}…")

        // ── Step 4: Engagement batch ───────────────────────────────────
        val noteIds = noteEvents.map { it.id }
        try {
            fetchEngagement(noteIds, writeRelays)
        } catch (e: Exception) {
            Log.w(TAG, "Step4 failed: ${e.message}")
        }
        Log.d(TAG, "Step4: engagement fetched for ${noteIds.size} notes")

        // ── Step 5: Own-engagement marker ──────────────────────────────
        // Only when viewing someone else's profile. The viewer's outbox relays
        // may differ from the profile owner's write relays, so step 4 (which
        // targets the owner's relay topology) might miss the viewer's own
        // reactions/reposts/zaps. Step 5 ensures the "I reacted to this" UI
        // state is reliable by querying the viewer's write relays specifically.
        if (!isOwn) {
            try {
                fetchOwnEngagement(noteIds)
            } catch (e: Exception) {
                Log.w(TAG, "Step5 failed: ${e.message}")
            }
            Log.d(TAG, "Step5: own-engagement markers fetched")
        }

        val elapsed = System.currentTimeMillis() - startMs
        Log.d(TAG, "loadProfile: ${pubkey.take(8)}… completed in ${elapsed}ms (${noteEvents.size} notes, ${refIds.size} refs)")
    }

    /**
     * Approximate follower count via NIP-45 COUNT on the antiprimal relay,
     * cached in MES with [MemoryEventStore.FOLLOWER_COUNT_TTL_SECONDS].
     *
     * Centralized here because the antiprimal connect uses `forceEvict = true` —
     * concurrent runs from ProfileViewModel and UserProfileViewModel could evict
     * each other's connection mid-flight. Per-pubkey in-flight dedup shares one
     * result among concurrent callers.
     */
    suspend fun fetchFollowerCount(pubkey: String): Long? {
        val (cached, cachedAt) = memoryEventStore.getFollowerCount(pubkey)
        val ttlFloor = System.currentTimeMillis() / 1000 - MemoryEventStore.FOLLOWER_COUNT_TTL_SECONDS
        if (cached != null && cachedAt != null && cachedAt > ttlFloor) return cached

        val newDeferred = pipelineScope.async(start = CoroutineStart.LAZY) {
            relayPool.connectAndAwait(listOf(ANTIPRIMAL_RELAY_URL), timeoutMs = 3_000, forceEvict = true)
            val count = relayPool.sendCount(
                relayUrl = ANTIPRIMAL_RELAY_URL,
                filter = buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(3)) })
                    put("#p", buildJsonArray { add(JsonPrimitive(pubkey)) })
                },
            )
            if (count != null) memoryEventStore.cacheFollowerCount(pubkey, count)
            count
        }
        val deferred = followerCountInFlight.compute(pubkey) { _, existing ->
            if (existing?.isActive == true) existing else newDeferred
        }!!
        if (deferred === newDeferred) {
            deferred.invokeOnCompletion { followerCountInFlight.remove(pubkey, deferred) }
            deferred.start()
        } else {
            newDeferred.cancel()
        }
        return deferred.await()
    }

    /**
     * Rebuild [MemoryEventStore.profileAnchoredIds] from own-authored events
     * in MES. Pure function of own-notes content — no snapshot schema change.
     * Call after snapshot restore completes.
     */
    fun rebuildOwnProfileAnchors(ownPubkey: String) {
        val anchored = memoryEventStore.profileAnchoredIds
        anchored.clear()

        val ownEvents = memoryEventStore.userEvents(ownPubkey, PROFILE_KINDS, 5000)
        var count = 0
        for (event in ownEvents) {
            // e-tag refs (repost targets, thread parents)
            extractETagIds(event.tagsJson).forEach {
                if (anchored.add(it)) count++
            }
            // nostr:nevent/note URIs in content (quoted notes)
            extractQuotedEventIds(event.content).forEach {
                if (anchored.add(it)) count++
            }
            // Thread parent/root IDs
            event.replyToId?.let { if (anchored.add(it)) count++ }
            event.rootId?.let { if (anchored.add(it)) count++ }
        }
        Log.d(TAG, "rebuildOwnProfileAnchors: ${ownEvents.size} own events → $count refs anchored")
    }

    // ── Step 1: Resolve write relays ────────────────────────────────────

    private fun resolveWriteRelays(pubkey: String): List<String> {
        val relays = memoryEventStore.writeRelaysFor(pubkey)
        return relays.ifEmpty { GLOBAL_RELAY_URLS }
    }

    // ── Step 2: Note fetch (delta or full backfill) ─────────────────────

    private suspend fun fetchNotes(
        pubkey: String,
        writeRelays: List<String>,
        maxPages: Int,
        isOwn: Boolean,
    ): List<NostrEvent> {
        val kinds = PROFILE_KINDS.toList()
        val nowSec = System.currentTimeMillis() / 1000
        val latestKnown = memoryEventStore.latestEventTimestampForAuthor(pubkey, PROFILE_KINDS)
        val sevenDaysAgo = nowSec - DELTA_THRESHOLD_DAYS * 86400

        // First own-profile load this session AND we have a recent head: a partial
        // snapshot restore can seed a recent head while missing posts below it, and
        // plain delta mode (since=latestKnown) would never request that gap. Heal it
        // with a bounded backward walk over a flat recent window. Flat now-14d floor,
        // NOT max(latestKnown-3d, now-14d) — that tightens the window in exactly the
        // case we're healing (gap below a very-recent head). Runs ONCE per session;
        // warm loads fall through to the lean delta path. Full-backfill (latestKnown
        // null/old) is left untouched — it already walks back from newest with no hole.
        if (isOwn && gapHealedOwnPubkeys.add(pubkey) && latestKnown != null && latestKnown > sevenDaysAgo) {
            val healSince = nowSec - 14L * 86400
            Log.w(TAG, "PROFILE-GAP heal: latestKnown=$latestKnown since=$healSince window=14d")
            val filter = buildJsonObject {
                put("kinds", buildJsonArray { PROFILE_KINDS.forEach { add(JsonPrimitive(it)) } })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                put("since", JsonPrimitive(healSince))
                put("limit", JsonPrimitive(500))
            }
            val results = relayPool.fetchPaginatedEvents(
                urls = writeRelays,
                baseFilter = filter,
                subIdPrefix = "prof-gap-${pubkey.take(8)}",
                maxPages = 2,
                timeoutMs = 20_000,
                onPage = { page, count -> Log.d(TAG, "PROFILE-GAP page $page → $count") },
            )
            Log.w(TAG, "PROFILE-GAP done: ${results.sumOf { it.totalEvents }} events / ${results.size} relays")
            return memoryEventStore.userEvents(pubkey, PROFILE_KINDS, 2500)
        }

        if (latestKnown != null && latestKnown > sevenDaysAgo) {
            // Delta mode: MES has recent data, fetch only newer events
            Log.d(TAG, "Step2: delta mode, since=$latestKnown")
            val filter = buildJsonObject {
                put("kinds", buildJsonArray { kinds.forEach { add(JsonPrimitive(it)) } })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                put("since", JsonPrimitive(latestKnown))
                put("limit", JsonPrimitive(500))
            }
            relayPool.fetchPaginatedEvents(
                urls = writeRelays,
                baseFilter = filter,
                subIdPrefix = "prof-${pubkey.take(8)}",
                maxPages = 1, // delta = single page
                timeoutMs = 15_000,
            )
        } else {
            // Full backfill: paginate backwards
            Log.d(TAG, "Step2: full backfill mode (latestKnown=${latestKnown ?: "null"})")
            val filter = buildJsonObject {
                put("kinds", buildJsonArray { kinds.forEach { add(JsonPrimitive(it)) } })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                put("limit", JsonPrimitive(500))
            }
            val results = relayPool.fetchPaginatedEvents(
                urls = writeRelays,
                baseFilter = filter,
                subIdPrefix = "prof-${pubkey.take(8)}",
                maxPages = maxPages,
                timeoutMs = 30_000,
                onPage = { page, count ->
                    Log.d(TAG, "Step2: page $page → $count events")
                },
            )
            val totalFetched = results.sumOf { it.totalEvents }
            Log.d(TAG, "Step2: backfill complete, $totalFetched events from ${results.size} relays")
        }

        // After fetch, return all MES-cached events for this author
        return memoryEventStore.userEvents(pubkey, PROFILE_KINDS, 2500)
    }

    // ── Step 3: Ref hydration ───────────────────────────────────────────

    private suspend fun hydrateRefs(
        events: List<NostrEvent>,
        anchorPolicy: AnchorPolicy,
    ): Set<String> {
        if (events.isEmpty()) return emptySet()

        val referencedIds = mutableSetOf<String>()
        val relayHints = mutableMapOf<String, String>()

        for (event in events) {
            // Kind-6 / kind-16 repost targets (e-tag). a-tag-only coordinate
            // reposts resolve later (#5); our own reposts embed the original JSON.
            if (event.kind == 6 || event.kind == 16) {
                extractRepostTargetId(event.tagsJson)?.let { id ->
                    referencedIds.add(id)
                    val eTagRelay = extractRepostTargetRelay(event.tagsJson)
                    relayHints[id] = eTagRelay ?: event.relayUrl
                }
            }
            // Quoted event IDs from nostr:nevent/note URIs in content
            extractQuotedEventIds(event.content).forEach { referencedIds.add(it) }
            // Thread parent/root IDs
            event.replyToId?.let { id ->
                referencedIds.add(id)
                relayHints.putIfAbsent(id, event.relayUrl)
            }
            event.rootId?.let { id ->
                referencedIds.add(id)
                relayHints.putIfAbsent(id, event.relayUrl)
            }
        }

        if (referencedIds.isEmpty()) return emptySet()

        // Skip refs already in MES or in negative cache
        referencedIds.removeAll { relayPool.isEventUnresolved(it) }
        val missingRefs = referencedIds.filter { memoryEventStore.getEventEntity(it) == null }

        if (missingRefs.isNotEmpty()) {
            // Broadcast fetch
            relayPool.fetchEventsByIds(missingRefs)

            // Hint-relay batched fetch (same pattern as CardHydrator.hydrateRefs)
            val hintBatches = HashMap<String, MutableList<String>>()
            for (id in missingRefs) {
                val hint = relayHints[id] ?: continue
                hintBatches.getOrPut(hint) { mutableListOf() }.add(id)
            }
            val cappedHints = hintBatches.entries
                .sortedByDescending { it.value.size }
                .take(MAX_HINT_RELAYS_PER_PASS)
            if (hintBatches.size > cappedHints.size) {
                Log.d(TAG, "hint fan-out capped: ${hintBatches.size} → ${cappedHints.size} relays")
            }
            for (entry in cappedHints) {
                relayPool.fetchEventsByIdsFromRelay(entry.key, entry.value, bypassDedup = true)
            }

            // Wait for responses
            delay(REF_WAIT_MS)

            // Outbox fallback for stragglers (same 3-phase pattern as CardHydrator)
            val stillMissing = missingRefs.filter { memoryEventStore.getEventEntity(it) == null }
            if (stillMissing.isNotEmpty()) {
                outboxFallback(events, stillMissing)
            }
        }

        // Anchor resolved refs for OWN policy
        if (anchorPolicy == AnchorPolicy.OWN) {
            val resolved = referencedIds.filter { memoryEventStore.getEventEntity(it) != null }
            memoryEventStore.profileAnchoredIds.addAll(resolved)
        }

        // Resolve ref authors
        val refAuthorPubkeys = mutableSetOf<String>()
        for (id in referencedIds) {
            val refEvent = memoryEventStore.getNostrEvent(id) ?: continue
            refAuthorPubkeys.add(refEvent.pubkey)
        }
        if (refAuthorPubkeys.isNotEmpty()) {
            userRepository.fetchMissingProfiles(refAuthorPubkeys.toList())
        }

        return referencedIds
    }

    /** A.6 outbox fallback — try author's NIP-65 write relays for missing refs. */
    private suspend fun outboxFallback(
        sourceEvents: List<NostrEvent>,
        missingIds: List<String>,
    ) {
        // Extract author pubkeys from source events' p-tags
        val refAuthorPubkeys = mutableSetOf<String>()
        for (event in sourceEvents) {
            extractPTagPubkeys(event.tagsJson).forEach { refAuthorPubkeys.add(it) }
            if (event.kind == 6 && refAuthorPubkeys.isEmpty()) {
                refAuthorPubkeys.add(event.pubkey)
            }
        }
        if (refAuthorPubkeys.isEmpty()) return

        // Try cached write relays first
        val outboxBatches = HashMap<String, MutableList<String>>()
        for (id in missingIds) {
            for (pk in refAuthorPubkeys) {
                val relays = memoryEventStore.writeRelaysFor(pk)
                for (r in relays) {
                    outboxBatches.getOrPut(r) { mutableListOf() }.add(id)
                }
            }
        }
        for ((relay, ids) in outboxBatches) {
            relayPool.fetchEventsByIdsFromRelay(relay, ids.distinct(), bypassDedup = true)
        }
        if (outboxBatches.isNotEmpty()) {
            delay(REF_WAIT_MS)
        }
    }

    // ── Step 4: Engagement batch ────────────────────────────────────────

    private suspend fun fetchEngagement(
        noteIds: List<String>,
        writeRelays: List<String>,
    ) {
        if (noteIds.isEmpty()) return

        // Read relays for engagement (reactions/reposts/zaps come from readers)
        val readRelays = relayPreferencesStore.indexerRelayUrlsSnapshot() +
            writeRelays
        val targetUrls = readRelays.distinct()

        // Chunk into ENGAGEMENT_CHUNK_SIZE per batch
        val chunks = noteIds.chunked(ENGAGEMENT_CHUNK_SIZE)
        Log.d(TAG, "Step4: ${noteIds.size} notes → ${chunks.size} chunks")

        for ((index, chunk) in chunks.withIndex()) {
            val subId = "prof-eng-${System.nanoTime()}"
            val req = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(subId))
                add(buildJsonObject {
                    put("kinds", buildJsonArray {
                        add(JsonPrimitive(1))  // replies
                        add(JsonPrimitive(6))  // reposts
                        add(JsonPrimitive(7))  // reactions
                        add(JsonPrimitive(9735)) // zap receipts
                    })
                    put("#e", buildJsonArray { chunk.forEach { add(JsonPrimitive(it)) } })
                    put("limit", JsonPrimitive(500))
                })
            }.toString()

            val eoseDeferred = CompletableDeferred<Unit>()
            relayPool.oneShotEoseCallbacks[subId] = eoseDeferred
            relayPool.sendOneShotBatch(targetUrls, listOf(req), listOf(subId))

            withTimeoutOrNull(ENGAGEMENT_TIMEOUT_MS) { eoseDeferred.await() }
            relayPool.cleanupOneShotSub(subId)

            if (index < chunks.size - 1) {
                delay(100) // brief pause between chunks to avoid relay rate limiting
            }
        }
    }

    // ── Step 5: Own-engagement marker ───────────────────────────────────

    private suspend fun fetchOwnEngagement(noteIds: List<String>) {
        val ownPk = memoryEventStore.ownPubkey ?: return
        if (noteIds.isEmpty()) return

        // Use the VIEWER's write relays, not the profile owner's
        val viewerWriteRelays = memoryEventStore.writeRelaysFor(ownPk)
            .ifEmpty { relayPool.connectedRelayUrls() }
        if (viewerWriteRelays.isEmpty()) return

        val chunks = noteIds.chunked(ENGAGEMENT_CHUNK_SIZE)
        Log.d(TAG, "Step5: ${noteIds.size} notes → ${chunks.size} chunks (viewer=${ownPk.take(8)}…)")

        for ((index, chunk) in chunks.withIndex()) {
            val subId = "prof-own-eng-${System.nanoTime()}"
            val req = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive(subId))
                add(buildJsonObject {
                    put("kinds", buildJsonArray {
                        add(JsonPrimitive(7))    // reactions
                        add(JsonPrimitive(6))    // reposts
                        add(JsonPrimitive(9735)) // zap receipts
                    })
                    put("authors", buildJsonArray { add(JsonPrimitive(ownPk)) })
                    put("#e", buildJsonArray { chunk.forEach { add(JsonPrimitive(it)) } })
                })
            }.toString()

            val eoseDeferred = CompletableDeferred<Unit>()
            relayPool.oneShotEoseCallbacks[subId] = eoseDeferred
            relayPool.sendOneShotBatch(viewerWriteRelays, listOf(req), listOf(subId))

            withTimeoutOrNull(ENGAGEMENT_TIMEOUT_MS) { eoseDeferred.await() }
            relayPool.cleanupOneShotSub(subId)

            if (index < chunks.size - 1) {
                delay(100)
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Extract event IDs from e-tags in a tags JSON string. */
    private fun extractETagIds(tagsJson: String): List<String> {
        return try {
            NostrJson.parseToJsonElement(tagsJson).jsonArray
                .filter { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "e" }
                .mapNotNull { it.jsonArray.getOrNull(1)?.jsonPrimitive?.content }
        } catch (_: Exception) { emptyList() }
    }
}
