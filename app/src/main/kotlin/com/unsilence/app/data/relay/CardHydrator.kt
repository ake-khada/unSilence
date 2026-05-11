package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.model.buildVideoRenderModels
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.ui.feed.IMAGE_URL_REGEX
import com.unsilence.app.ui.feed.ImageDimensionCache
import com.unsilence.app.ui.feed.VIDEO_URL_REGEX
import com.unsilence.app.ui.feed.VideoThumbnailCache
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CardHydrator"

/** Bound on each per-phase hydrated-id memo. Sized so a tall warm-zone
 *  fan-out (~150 events on aggressive scroll) plus a feed swap fits with
 *  headroom; oldest IDs evict FIFO when the set exceeds this. */
private const val HYDRATED_CAP = 500

private val NOSTR_URI_REGEX = Regex("nostr:[a-z0-9]+", RegexOption.IGNORE_CASE)

/** Negative cache for NIP-19 bech32 URIs that fail to decode. Thread-safe. */
object Nip19FailureCache {
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private const val MAX_SIZE = 10_000 // ~400KB at 40-byte avg string length

    fun isKnownBad(uri: String): Boolean = failures.containsKey(uri)

    fun markBad(uri: String) {
        // Soft cap — remove one arbitrary entry instead of wiping the whole cache.
        // Known-bad strings persist across the session.
        if (failures.size >= MAX_SIZE) {
            val victim = failures.keys.firstOrNull()
            if (victim != null) failures.remove(victim)
        }
        failures[uri] = true
    }
}

/**
 * Unified card hydration: resolves ALL missing data for visible cards.
 *
 * Handles:
 *  - Author profiles (kind 0)
 *  - Repost original-author profiles (NIP-18 p-tag)
 *  - Referenced events for reposts (kind 6 e-tag) and quotes (nostr:nevent/note)
 *  - Referenced event author profiles
 */
@Singleton
class CardHydrator @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val userRepository: UserRepository,
    private val thumbnailCache: VideoThumbnailCache,
    private val imageDimensionCache: ImageDimensionCache,
    private val profileResolver: ProfileResolver,
) {
    // ── Per-phase hydrated-id memo ───────────────────────────────────────
    // hydrateVisibleCards re-fires on every viewport change (debounce 300ms).
    // During a slow scroll the warm zone overlaps the previous pass by 30+
    // events, so each phase repeats the same content regex scans, ref id
    // extraction, and relay fetch orchestration for events already done.
    // Track per-phase completion in bounded LRU sets and filter at entry.
    // Downstream caches (UserRepository, RelayPool eventFetchInFlight,
    // ImageDimensionCache) already dedup the actual fetches — these sets
    // skip the upstream orchestration cost only.
    private val profilesHydrated = LinkedHashSet<String>()
    private val refsHydrated = LinkedHashSet<String>()
    private val mediaHydrated = LinkedHashSet<String>()
    private val hydratedLock = Any()

    private fun filterNovel(
        events: List<FeedRow>,
        set: LinkedHashSet<String>,
    ): List<FeedRow> {
        if (events.isEmpty()) return events
        return synchronized(hydratedLock) {
            events.filter { it.id !in set }
        }
    }

    private fun markHydrated(events: List<FeedRow>, set: LinkedHashSet<String>) {
        if (events.isEmpty()) return
        synchronized(hydratedLock) {
            for (e in events) {
                if (set.add(e.id) && set.size > HYDRATED_CAP) {
                    val iter = set.iterator()
                    if (iter.hasNext()) { iter.next(); iter.remove() }
                }
            }
        }
    }

    /** Drop the per-phase memos. Called on logout / feed-switch teardown
     *  if the caller wants a clean slate. Safe to call concurrently with
     *  hydrate* — the lock guards both reads and writes. */
    fun resetHydratedMemo() {
        synchronized(hydratedLock) {
            profilesHydrated.clear()
            refsHydrated.clear()
            mediaHydrated.clear()
        }
    }

    /**
     * Profile resolution — avatar, name, identity.
     *
     * @param fanOut When false, only fetches from indexer relays (fastest path).
     *   Source relay and hint relay fetches are skipped.
     */
    suspend fun hydrateProfiles(events: List<FeedRow>, fanOut: Boolean = true, excludeSourceRelay: String? = null) {
        if (events.isEmpty()) return
        val novelEvents = filterNovel(events, profilesHydrated)
        if (novelEvents.isEmpty()) return
        // Mark up front: profile hints come from the event's content/tags
        // which are fixed at insert, so seeing the event once is enough.
        // Cancellation / fetch failure isn't fatal — per-card avatar autofetch
        // and other entry points retry on demand.
        markHydrated(novelEvents, profilesHydrated)

        val pubkeys = mutableSetOf<String>()
        val profileHints = mutableMapOf<String, MutableList<String>>()

        for (event in novelEvents) {
            pubkeys.add(event.pubkey)
            if (event.kind == 6) {
                extractRepostAuthorPubkey(event.content, event.tags)?.let { pubkeys.add(it) }
            }
            if (fanOut) {
                extractProfileHints(event.content).forEach { (pk, relays) ->
                    pubkeys.add(pk)
                    profileHints.getOrPut(pk) { mutableListOf() }.addAll(relays)
                }
            }
        }

        if (pubkeys.isEmpty()) return

        // Pre-filter: drop pubkeys already cached in Room — avoids launching
        // orchestration (relay REQs, ProfileResolver batching, logging) for pubkeys
        // that will immediately resolve to "all fresh, skipping."
        val unresolved = profileResolver.filterUnresolved(pubkeys)
        if (unresolved.isEmpty()) return

        userRepository.fetchMissingProfiles(unresolved.toList())

        if (fanOut) {
            val sourceRelays = novelEvents.map { it.relayUrl }.distinct()
                .filter { it != excludeSourceRelay }
            if (sourceRelays.isNotEmpty()) {
                relayPool.fetchProfilesFromSourceRelays(unresolved.toList(), sourceRelays)
            }
            if (profileHints.isNotEmpty()) {
                // Only fan out hints for unresolved pubkeys
                val unresolvedHints = profileHints.filterKeys { it in unresolved }
                if (unresolvedHints.isNotEmpty()) {
                    relayPool.fetchProfilesFromHints(unresolvedHints.mapValues { it.value.distinct() })
                }
            }
        }

        val skipped = events.size - novelEvents.size
        Log.d(TAG, "Phase1 profiles: ${novelEvents.size} novel cards (${skipped} skipped) → ${unresolved.size} pubkeys${if (!fanOut) " (indexer-only)" else ", ${novelEvents.map { it.relayUrl }.distinct().size} source relays"}")
    }

    /**
     * Phase 2: Referenced events + thumbnails. The slow path — ref fetches have a
     * 1500ms wait for relay responses. Also resolves ref-event author profiles.
     * Called from SLOW_SCROLL (after profiles) and IDLE.
     */
    suspend fun hydrateRefs(events: List<FeedRow>, feedRelay: String? = null) {
        if (events.isEmpty()) return
        val novelEvents = filterNovel(events, refsHydrated)
        if (novelEvents.isEmpty()) return
        // Mark up front: refs are derived from tags/content fixed at insert.
        // RelayPool.eventFetchInFlight + isEventUnresolved already dedup the
        // actual fetches; this just spares the regex / map orchestration.
        markHydrated(novelEvents, refsHydrated)

        val referencedIds = mutableSetOf<String>()
        val relayHints = mutableMapOf<String, String>()
        for (event in novelEvents) {
            if (event.kind == 6) {
                extractRepostTargetId(event.tags)?.let { id ->
                    referencedIds.add(id)
                    // Use e-tag relay hint if present; fall back to the wrapper's own relay.
                    // Bridged reposts (mostr.pub) often omit the relay hint in e-tags,
                    // but the target event usually lives on the same relay as the wrapper.
                    val eTagRelay = extractRepostTargetRelay(event.tags)
                    relayHints[id] = eTagRelay ?: event.relayUrl
                }
            }
            extractQuotedEventIds(event.content).forEach { referencedIds.add(it) }
            // Thread parents: replies reference their parent (replyToId) and root (rootId).
            // Fetching these ensures Conversations tab parent notes resolve via hydrateRefs
            // even if the initial lookupEvent times out.
            event.replyToId?.let { id ->
                referencedIds.add(id)
                relayHints.putIfAbsent(id, event.relayUrl)
            }
            event.rootId?.let { id ->
                referencedIds.add(id)
                relayHints.putIfAbsent(id, event.relayUrl)
            }
        }

        // Short-circuit: if no refs to resolve, skip the entire pipeline
        // (Room lookups, relay fetches, 1500ms delay, author resolution, thumbnails).
        if (referencedIds.isEmpty()) return

        // Skip refs in the negative cache (now in RelayPool, shared across all entry points)
        referencedIds.removeAll { relayPool.isEventUnresolved(it) }
        if (referencedIds.isEmpty()) return

        // Fetch missing referenced events (check MemoryEventStore, not Room)
        val missingRefs = referencedIds.filter { memoryEventStore.getEventEntity(it) == null }
        if (missingRefs.isNotEmpty()) {
            // Broadcast fetch for all missing refs
            relayPool.fetchEventsByIds(missingRefs.toList())

            // Hint-relay coverage. The broadcast targets only 6 connected relays —
            // events that live exclusively on the wrapper's source relay (or an
            // explicit e-tag hint relay) won't be covered. Group missing refs by
            // hint URL and send ONE batched REQ per hint relay instead of one per
            // ref id; in field logs the per-id loop fires 30+ separate one-shot
            // REQs at the same hint relay, queue-saturating it. bypassDedup:
            // the broadcast already registered these ids in eventFetchInFlight,
            // and we want the hint REQ to fire anyway.
            val hintBatches = HashMap<String, MutableList<String>>()
            for (id in missingRefs) {
                val hint = relayHints[id] ?: continue
                hintBatches.getOrPut(hint) { mutableListOf() }.add(id)
            }
            for ((hint, ids) in hintBatches) {
                if (hint == feedRelay) continue
                relayPool.fetchEventsByIdsFromRelay(hint, ids, bypassDedup = true)
            }
        }

        // Wait for missing refs to arrive from relays
        if (missingRefs.isNotEmpty()) {
            delay(1500)
        }

        // ── A.6 outbox fallback: try author's NIP-65 write relays for stragglers ─
        // Phase tracking: which fetch path resolved each originally-missing ref
        val phaseResolved = mutableMapOf<String, String>() // refId → "source"|"outbox1"|"outbox2"

        val afterSourceRelay = if (missingRefs.isNotEmpty()) {
            val resolved = missingRefs.filter { memoryEventStore.getEventEntity(it) != null }
            resolved.forEach { phaseResolved[it] = "source" }
            missingRefs.filter { memoryEventStore.getEventEntity(it) == null }
        } else emptyList()

        if (afterSourceRelay.isNotEmpty()) {
            try {
                // Extract p-tag pubkeys from referencing events to find ref authors.
                // A.6.2: for kind-6 reposts without p-tags (bridged content from mostr.pub
                // etc.), use the wrapper's own pubkey as fallback author for outbox routing.
                val refAuthorPubkeys = mutableSetOf<String>()
                for (event in novelEvents) {
                    val pTags = extractPTagPubkeys(event.tags)
                    if (pTags.isNotEmpty()) {
                        pTags.forEach { refAuthorPubkeys.add(it) }
                    } else if (event.kind == 6) {
                        refAuthorPubkeys.add(event.pubkey)
                    }
                }
                Log.d(TAG, "Outbox: ${afterSourceRelay.size} still-missing refs, ${refAuthorPubkeys.size} p-tag authors")

                // Phase 1: try write relays already cached in MemoryEventStore.
                // The per-author relay-list dump was useful during early outbox
                // debugging but produces multi-KB log lines — printing 60-relay
                // arrays once per author per hydration pass added measurable
                // Main-thread cost when this runs frequently.
                val cachedWriteRelays = refAuthorPubkeys
                    .flatMap { pk -> memoryEventStore.writeRelaysForRanked(pk) }
                    .distinct()
                    .filter { it != feedRelay }
                    .take(5)

                if (cachedWriteRelays.isNotEmpty()) {
                    // Batch: ONE REQ per write relay with all afterSourceRelay
                    // ids in `{"ids":[...]}`, instead of per-id REQs (which sent
                    // up to 5 single-id REQs per missing ref). Same shape as
                    // the hint-batch fix in ecf931e for hydrateRefs's primary
                    // hint loop. With 4 missing refs × 5 cached write relays
                    // that's 20 REQs collapsed to 5.
                    for (relay in cachedWriteRelays) {
                        relayPool.fetchEventsByIdsFromRelay(relay, afterSourceRelay, bypassDedup = true)
                    }
                    Log.d(TAG, "Outbox fallback: ${afterSourceRelay.size} refs → ${cachedWriteRelays.size} cached write relays (batched)")
                }

                // Phase 2: for authors without cached relay lists, fetch kind-10002
                val authorsWithoutRelayList = refAuthorPubkeys
                    .filter { memoryEventStore.writeRelaysFor(it).isEmpty() }
                    .take(5)
                if (authorsWithoutRelayList.isNotEmpty()) {
                    relayPool.fetchRelayLists(authorsWithoutRelayList.toList())
                    delay(2000) // Wait for kind-10002 to arrive via EventProcessor

                    // Check phase 1 resolution before phase 2 dispatch
                    afterSourceRelay.filter { memoryEventStore.getEventEntity(it) != null && it !in phaseResolved }
                        .forEach { phaseResolved[it] = "outbox1" }

                    // Now resolve newly-cached write relays
                    val newWriteRelays = authorsWithoutRelayList
                        .flatMap { memoryEventStore.writeRelaysForRanked(it) }
                        .distinct()
                        .filter { it != feedRelay }
                        .take(5)
                    if (newWriteRelays.isNotEmpty()) {
                        // Re-check which refs are still missing
                        val stillMissingAfterPhase1 = afterSourceRelay
                            .filter { memoryEventStore.getEventEntity(it) == null }
                        if (stillMissingAfterPhase1.isNotEmpty()) {
                            // Same batch-by-relay pattern as phase 1.
                            for (relay in newWriteRelays) {
                                relayPool.fetchEventsByIdsFromRelay(
                                    relay, stillMissingAfterPhase1, bypassDedup = true,
                                )
                            }
                            Log.d(TAG, "Outbox fallback phase 2: ${stillMissingAfterPhase1.size} refs → ${newWriteRelays.size} newly-resolved write relays (batched)")
                        }
                    }
                }

                // Final wait for outbox relay responses
                if (cachedWriteRelays.isNotEmpty() || authorsWithoutRelayList.isNotEmpty()) {
                    delay(2000)
                }

                // Check outbox1/outbox2 resolution
                afterSourceRelay.filter { memoryEventStore.getEventEntity(it) != null && it !in phaseResolved }
                    .forEach { phaseResolved[it] = "outbox2" }
            } finally {
                // Write negative cache even if coroutine was canceled during a delay.
                // Both getEventEntity and markEventUnresolved are non-suspending
                // ConcurrentHashMap ops — safe in a finally block without NonCancellable.
                val finallyMissing = afterSourceRelay.filter { memoryEventStore.getEventEntity(it) == null }
                for (id in finallyMissing) { relayPool.markEventUnresolved(id) }
                val resolvedViaOutbox = afterSourceRelay.size - finallyMissing.size
                if (resolvedViaOutbox > 0) {
                    Log.d(TAG, "Outbox resolved: $resolvedViaOutbox/${afterSourceRelay.size} refs via author write relays")
                }
            }
        } else if (missingRefs.isNotEmpty()) {
            // Source relay resolved everything
            missingRefs.filter { memoryEventStore.getEventEntity(it) != null }
                .forEach { phaseResolved[it] = "source" }
        }

        // ── DIAGNOSTIC: structured log per originally-missing ref ─────────────
        if (missingRefs.isNotEmpty()) {
            // Build refId → (referencedBy, referencedByKind) mapping
            val refToReferencer = mutableMapOf<String, Pair<String, Int>>()
            for (event in novelEvents) {
                if (event.kind == 6) {
                    extractRepostTargetId(event.tags)?.let { refToReferencer[it] = event.id to event.kind }
                }
                event.replyToId?.let { refToReferencer.putIfAbsent(it, event.id to event.kind) }
                event.rootId?.let { refToReferencer.putIfAbsent(it, event.id to event.kind) }
                extractQuotedEventIds(event.content).forEach { refToReferencer.putIfAbsent(it, event.id to event.kind) }
            }
            for (refId in missingRefs) {
                val entity = memoryEventStore.getEventEntity(refId)
                val (referencedBy, refByKind) = refToReferencer[refId] ?: ("unknown" to -1)
                val phase = phaseResolved[refId] ?: "unresolved"
                Log.d(TAG, "Outbox final: refId=${refId.take(12)} exists=${entity != null} " +
                    "kind=${entity?.kind} author=${entity?.pubkey?.take(12)} " +
                    "relayUrl=${entity?.relayUrl} " +
                    "contentLen=${entity?.content?.length ?: 0} " +
                    "referencedBy=${referencedBy.take(12)} referencedByKind=$refByKind " +
                    "phase=$phase")
            }
        }

        // Resolve authors for ALL referenced events (existing + newly fetched).
        // Previously only missing refs got author resolution — refs already in
        // MemoryEventStore were skipped, leaving embedded quote author profiles
        // unresolved (no name, avatar, or NIP-05).
        val allRefAuthors = referencedIds
            .mapNotNull { memoryEventStore.getEventEntity(it)?.pubkey }
        if (allRefAuthors.isNotEmpty()) {
            userRepository.fetchMissingProfiles(allRefAuthors)
        }

        val outboxResolved = afterSourceRelay.size - afterSourceRelay.count { memoryEventStore.getEventEntity(it) == null }
        val skipped = events.size - novelEvents.size
        Log.d(TAG, "Phase2 refs: ${novelEvents.size} novel cards (${skipped} skipped) → ${referencedIds.size} refs (${missingRefs.size} missing, ${afterSourceRelay.size} post-source, $outboxResolved outbox-resolved)")
    }

    /**
     * Independent media pipeline — no relay queries, no dependencies on ref resolution.
     *
     * @param mmrAllowed When true, MediaMetadataRetriever is used for video thumbnails
     *   (REST-only — 300ms/video codec work). When false, only image dimensions are
     *   resolved (IDLE-safe — BitmapFactory header-only, ~50ms each).
     */
    suspend fun hydrateMedia(events: List<FeedRow>, mmrAllowed: Boolean = false, mmrCap: Int = 3) {
        if (events.isEmpty()) return
        val novelEvents = filterNovel(events, mediaHydrated)
        if (novelEvents.isEmpty()) return
        markHydrated(novelEvents, mediaHydrated)

        // Image dimensions (always — lightweight header-only BitmapFactory decode)
        val imageUrls = mutableListOf<String>()
        for (event in novelEvents) {
            if (event.kind == 30023) continue
            val content = event.content
            val afterVideos = VIDEO_URL_REGEX.replace(content, "")
            IMAGE_URL_REGEX.findAll(afterVideos).forEach { imageUrls.add(it.value) }
        }
        val uniqueImageUrls = imageUrls.distinct().filter { imageDimensionCache.getCached(it) == null }
        if (uniqueImageUrls.isNotEmpty()) {
            imageDimensionCache.resolveAll(uniqueImageUrls)
            Log.d(TAG, "Media: resolved ${uniqueImageUrls.size} image dims")
        }

        // Video thumbnails via MediaMetadataRetriever (REST-only, capped at 3)
        if (mmrAllowed) {
            var thumbnailCount = 0
            for (event in novelEvents) {
                if (thumbnailCount >= mmrCap) break
                if (event.kind == 30023) continue
                val models = buildVideoRenderModels(event)
                for (model in models) {
                    if (thumbnailCount >= mmrCap) break
                    // Skip if poster URL exists (Coil handles it) or dims already resolved
                    if (!model.posterUrl.isNullOrBlank()) continue
                    if (model.widthPx != null && model.heightPx != null) continue
                    if (thumbnailCache.resolvedAspectRatios.containsKey(model.videoUrl)) continue
                    try {
                        withContext(Dispatchers.IO) { thumbnailCache.getThumbnail(model.videoUrl) }
                        thumbnailCount++
                        Log.d(TAG, "Media: MMR thumbnail ${model.videoUrl.take(60)}")
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Media: MMR thumbnail failed: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Full hydration: profiles + refs + media. Used by IDLE state where
     * there's no urgency to split phases.
     *
     * Coalescing: when a previous pass fired <COALESCE_COOLDOWN_MS ago AND
     * the current warm zone has only ≤COALESCE_NOVEL_THRESHOLD novel cards,
     * skip this pass entirely. Field logs showed sustained 7+ hydrator
     * firings per minute where each pass had only 1-2 novel events; each
     * tiny pass still amplified into per-event relay fetches at the source-
     * relay + hint-relay level. Holding a pass means those 1-2 events stay
     * "novel" and merge into the next pass — same coverage, fewer one-shots,
     * less radio churn. Cap is 2s so worst-case profile/ref delay is
     * bounded; per-card avatar autofetch covers visible-but-unhydrated rows
     * in the meantime.
     *
     * Bypass on big novel batches (cold start, fast scroll, feed swap):
     * fires immediately so the first paint isn't delayed.
     */
    @Volatile private var lastFullHydrationAt = 0L

    suspend fun hydrateVisibleCards(events: List<FeedRow>, feedRelay: String? = null) {
        if (events.isEmpty()) return

        // Profile + ref hydration removed — per-card self-fetch paths handle
        // these (AvatarImage 800ms autofetch for profiles, QuoteCard/EmptyRepostBody
        // produceState for refs). Warm-zone batch dispatch was the burst source
        // causing Choreographer frame skips (30-69 frames) on relay-heavy feeds.
        // hydrateMedia remains load-bearing for layout stability (image dims).
        hydrateMedia(events, mmrAllowed = false)
    }
}

/** Min interval between full hydration passes when novel count is small.
 *  Picked so worst-case profile/ref latency on a slow trickle of new events
 *  stays under ~2s — within the per-card avatar autofetch debounce window. */
private const val COALESCE_COOLDOWN_MS = 2_000L

/** Novel-event threshold below which a pass within COALESCE_COOLDOWN_MS is
 *  deferred. 3 lets tiny live-tail batches (1-2 events) coalesce while still
 *  firing immediately for fast scrolls / feed swaps where ≥4 cards are new. */
private const val COALESCE_NOVEL_THRESHOLD = 3

/** Extract the relay hint (index 2) from the first "e" tag in a repost's tags. */
fun extractRepostTargetRelay(tagsJson: String): String? {
    return try {
        val parsed = NostrJson.parseToJsonElement(tagsJson).jsonArray
        val eTag = parsed.firstOrNull { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "e" }
        eTag?.jsonArray?.getOrNull(2)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}

/** Extract the repost target event ID from the first "e" tag in a tags JSON string. */
fun extractRepostTargetId(tagsJson: String): String? {
    return try {
        val parsed = NostrJson.parseToJsonElement(tagsJson).jsonArray
        val eTag = parsed.firstOrNull { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "e" }
        val result = eTag?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content
        if (result == null) {
            Log.d("CardHydrator", "extractRepostTargetId: no e-tag found in ${parsed.size} tags, input=${tagsJson.take(200)}")
        }
        result
    } catch (e: Exception) {
        Log.w("CardHydrator", "extractRepostTargetId parse failed: ${e.message}, input=${tagsJson.take(200)}")
        null
    }
}

/** Extract quoted event IDs from nostr:nevent1.../nostr:note1... URIs in content. */
fun extractQuotedEventIds(content: String): List<String> {
    if (!content.contains("nostr:")) return emptyList()
    return NOSTR_URI_REGEX.findAll(content).mapNotNull { match ->
        if (Nip19FailureCache.isKnownBad(match.value)) return@mapNotNull null
        runCatching {
            when (val entity = Nip19Parser.uriToRoute(match.value)?.entity) {
                is NEvent -> entity.hex
                is NNote -> entity.hex
                else -> null
            }
        }.onFailure { Nip19FailureCache.markBad(match.value) }.getOrNull()
    }.toList()
}

/** Extract all p-tag pubkeys from a tags JSON string. */
fun extractPTagPubkeys(tagsJson: String): List<String> {
    return try {
        NostrJson.parseToJsonElement(tagsJson).jsonArray
            .filter { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "p" }
            .mapNotNull { it.jsonArray.getOrNull(1)?.jsonPrimitive?.content }
    } catch (_: Exception) { emptyList() }
}

/** Extract pubkey → relay hints from nostr:nprofile1... URIs in content. */
fun extractProfileHints(content: String): Map<String, List<String>> {
    if (!content.contains("nostr:")) return emptyMap()
    val hints = mutableMapOf<String, List<String>>()
    NOSTR_URI_REGEX.findAll(content).forEach { match ->
        if (Nip19FailureCache.isKnownBad(match.value)) return@forEach
        runCatching {
            val entity = Nip19Parser.uriToRoute(match.value)?.entity
            if (entity is NProfile && entity.relay.isNotEmpty()) {
                hints[entity.hex] = entity.relay.map { it.url }
            }
        }.onFailure { Nip19FailureCache.markBad(match.value) }
    }
    return hints
}
