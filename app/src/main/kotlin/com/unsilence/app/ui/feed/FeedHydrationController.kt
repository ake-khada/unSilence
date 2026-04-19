package com.unsilence.app.ui.feed

import android.os.Trace
import android.util.Log
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap


private const val TAG = "HydrationCtrl"

enum class ScrollState {
    WARM_CATCHUP,
    SLOW_SCROLL,
    IDLE,
    FAST_SCROLL,
    REST,
}

class FeedHydrationController(
    private val scope: CoroutineScope,
    private val cardHydrator: CardHydrator,
    private val relayPool: RelayPool,
    private val memoryEventStore: MemoryEventStore,
) {
    /** When non-null, source-relay fan-out skips this URL (single-relay feed optimization). */
    var feedRelayUrl: String? = null
    companion object {
        const val FAST_SCROLL_ENTER_PX_S = 2500f   // must exceed to enter FAST
        const val FAST_SCROLL_EXIT_PX_S  = 1200f   // must drop below to leave FAST
        const val VELOCITY_WINDOW_FRAMES = 6
        const val IDLE_TIMEOUT_MS = 500L
        const val WARM_ZONE_SIZE = 15
        const val WARM_CATCHUP_TIMEOUT_MS = 3000L
        const val ENGAGEMENT_REFRESH_INTERVAL_MS = 30_000L
        const val REF_DEBOUNCE_MS = 500L               // IDLE
        const val ENGAGEMENT_STALE_MS = 5 * 60 * 1000L // 5 minutes — warm zone freshness threshold
        const val WARM_ZONE_ENGAGEMENT_CAP = 5         // max warm zone engagement fetches per pass
        const val ENGAGEMENT_COALESCE_MS = 750L          // coalesce window for engagement batches
        const val STATE_DWELL_MS = 200L                    // hard lock: ignore velocity transitions for this duration
        const val IDLE_TO_REST_DWELL_MS = 1000L            // how long in IDLE before transitioning to REST
        const val REST_MINIMUM_DWELL_MS = 3000L            // minimum time to stay in REST before non-user exit

        // Hydration ledger bitmask phases
        const val PHASE_PROFILE    = 1
        const val PHASE_REFS       = 2
        const val PHASE_ENGAGEMENT = 4
        const val PHASE_FULL       = 7
    }

    // ── State machine ─────────────────────────────────────────────────
    private var state = ScrollState.WARM_CATCHUP
    private var stateEnteredAt = System.currentTimeMillis()
    private var lastTransitionTime = 0L

    // ── Velocity tracking ─────────────────────────────────────────────
    private val scrollSamples = ArrayDeque<Pair<Long, Int>>(VELOCITY_WINDOW_FRAMES + 2)
    private var velocityPxPerSec = 0f
    private var smoothedVelocity = 0f   // low-pass filtered — used for state decisions

    // ── Hydration ledger (bitmask per event ID) ─────────────────────────
    private val hydrated = ConcurrentHashMap<String, Int>()

    private fun isHydrated(id: String, what: Int): Boolean =
        ((hydrated[id] ?: 0) and what) == what

    private fun markHydrated(id: String, what: Int) {
        hydrated.merge(id, what) { old, new -> old or new }
    }

    private fun retainOnly(activeIds: Set<String>) {
        hydrated.keys.retainAll(activeIds)
    }

    private fun clearPhase(what: Int) {
        val mask = what.inv()
        for (key in hydrated.keys) {
            hydrated.computeIfPresent(key) { _, v ->
                val result = v and mask
                if (result == 0) null else result
            }
        }
    }

    // ── Hydration tracking (non-bitmask) ─────────────────────────────────
    private val profileHydratedPubkeys = mutableSetOf<String>() // Phase 1 done (author pubkeys — cross-event dedup)
    private val engagementFetchedIds = mutableSetOf<String>()
    private val fanOutPendingIds = mutableSetOf<String>()     // indexer-only, needs fan-out in IDLE
    private var lastEngagementRefreshTime = 0L
    private var lastRefStartTime = 0L           // Phase 2 debounce

    // ── Jobs ──────────────────────────────────────────────────────────
    private var idleTimerJob: Job? = null
    private var idleToRestJob: Job? = null
    private var catchupTimeoutJob: Job? = null
    private var profileJob: Job? = null     // Phase 1: profiles
    private var refJob: Job? = null         // Phase 2: refs (relay queries)
    private var mediaJob: Job? = null      // Independent: image dims + video thumbnails
    private var engagementJob: Job? = null
    private var warmEngagementJob: Job? = null // Warm zone engagement pre-check
    private var engagementCoalesceJob: Job? = null // Coalescing window for engagement batches

    // ── Engagement coalescing ────────────────────────────────────────
    private val pendingEngagementIds = mutableSetOf<String>()

    // ── Queue gate ─────────────────────────────────────────────────
    private var pendingQueueSize: Int = 0

    // ── Window dedup ────────────────────────────────────────────────
    private var lastProcessedWindowHash: Int = 0
    private var lastProcessedWindowSize: Int = 0

    // ── Latest data from FeedScreen ──────────────────────────────────
    private var lastVisibleItems: List<FeedRow> = emptyList()
    private var lastAllEvents: List<FeedRow> = emptyList()
    private var lastVisibleIds: Set<String> = emptySet()

    /**
     * Called every frame from FeedScreen's snapshotFlow.
     * This is the single entry point — the controller decides what to do.
     */
    fun onScrollFrame(
        visibleItems: List<FeedRow>,
        allEvents: List<FeedRow>,
        scrollPixelOffset: Int,
        isScrollInProgress: Boolean,
    ) {
        Trace.beginSection("FeedHydrationController.enrich")
        try { onScrollFrameInner(visibleItems, allEvents, scrollPixelOffset, isScrollInProgress) }
        finally { Trace.endSection() }
    }

    private fun onScrollFrameInner(
        visibleItems: List<FeedRow>,
        allEvents: List<FeedRow>,
        scrollPixelOffset: Int,
        isScrollInProgress: Boolean,
    ) {
        // Update velocity
        val now = System.currentTimeMillis()
        scrollSamples.addLast(now to scrollPixelOffset)
        if (scrollSamples.size > VELOCITY_WINDOW_FRAMES + 1) scrollSamples.removeFirst()
        velocityPxPerSec = computeVelocity()
        smoothedVelocity = 0.3f * velocityPxPerSec + 0.7f * smoothedVelocity

        // Store latest data
        lastVisibleItems = visibleItems
        lastAllEvents = allEvents
        lastVisibleIds = visibleItems.map { it.id }.toSet()

        // REST: user scroll exits immediately — user intent wins
        if (state == ScrollState.REST && isScrollInProgress) {
            transitionTo(ScrollState.SLOW_SCROLL, isUserGesture = true)
        }

        // REST: no discretionary work
        if (state == ScrollState.REST) return

        // State transitions (velocity-based — gated by hard dwell lock)
        val candidate = nextState(isScrollInProgress)
        if (candidate != state) {
            transitionTo(candidate)
        }

        // Per-frame actions based on current state
        when (state) {
            ScrollState.WARM_CATCHUP -> handleWarmCatchup()
            ScrollState.SLOW_SCROLL -> handleSlowScroll()
            ScrollState.IDLE -> handleIdle()
            ScrollState.FAST_SCROLL -> { /* blackout — do nothing */ }
            ScrollState.REST -> { /* no work */ }
        }
    }

    /**
     * Reset when feed type changes. Clears all tracking, re-enters WARM_CATCHUP.
     */
    fun reset() {
        idleTimerJob?.cancel()
        idleToRestJob?.cancel()
        catchupTimeoutJob?.cancel()
        profileJob?.cancel()
        refJob?.cancel()
        mediaJob?.cancel()
        engagementJob?.cancel()
        warmEngagementJob?.cancel()
        engagementCoalesceJob?.cancel()
        pendingEngagementIds.clear()
        hydrated.clear()
        profileHydratedPubkeys.clear()
        engagementFetchedIds.clear()
        fanOutPendingIds.clear()
        scrollSamples.clear()
        velocityPxPerSec = 0f
        smoothedVelocity = 0f
        lastVisibleItems = emptyList()
        lastAllEvents = emptyList()
        lastVisibleIds = emptySet()
        lastEngagementRefreshTime = 0L
        lastRefStartTime = 0L
        lastTransitionTime = 0L
        pendingQueueSize = 0
        lastProcessedWindowHash = 0
        lastProcessedWindowSize = 0
        state = ScrollState.WARM_CATCHUP
        stateEnteredAt = System.currentTimeMillis()
        startCatchupTimeout()
        Log.d(TAG, "Reset → WARM_CATCHUP")
    }

    // ── Velocity computation ──────────────────────────────────────────

    private fun computeVelocity(): Float {
        if (scrollSamples.size < 2) return 0f
        val oldest = scrollSamples.first()
        val newest = scrollSamples.last()
        val dtMs = newest.first - oldest.first
        if (dtMs <= 0) return 0f
        val dpx = kotlin.math.abs(newest.second - oldest.second).toFloat()
        return dpx / dtMs * 1000f
    }

    // ── State machine transitions ─────────────────────────────────────

    private fun nextState(isScrollInProgress: Boolean): ScrollState {
        // Velocity-based state decisions use smoothedVelocity (low-pass filtered)
        // to prevent noisy per-frame spikes from causing rapid state oscillation.
        // The dwell lock is enforced inside transitionTo() as a second layer.
        return when (state) {
            ScrollState.WARM_CATCHUP -> {
                if (smoothedVelocity > FAST_SCROLL_ENTER_PX_S) {
                    ScrollState.FAST_SCROLL
                } else if (catchupGateMet()) {
                    ScrollState.SLOW_SCROLL
                } else {
                    ScrollState.WARM_CATCHUP
                }
            }
            ScrollState.SLOW_SCROLL -> {
                if (smoothedVelocity > FAST_SCROLL_ENTER_PX_S) {
                    ScrollState.FAST_SCROLL
                } else {
                    ScrollState.SLOW_SCROLL
                }
            }
            ScrollState.IDLE -> {
                if (smoothedVelocity > FAST_SCROLL_ENTER_PX_S) {
                    ScrollState.FAST_SCROLL
                } else if (isScrollInProgress) {
                    ScrollState.SLOW_SCROLL
                } else {
                    ScrollState.IDLE
                }
            }
            ScrollState.FAST_SCROLL -> {
                if (smoothedVelocity < FAST_SCROLL_EXIT_PX_S) {
                    ScrollState.WARM_CATCHUP
                } else {
                    ScrollState.FAST_SCROLL
                }
            }
            ScrollState.REST -> {
                // REST exits only via user gesture (handled in onScrollFrame) or reset()
                ScrollState.REST
            }
        }
    }

    /**
     * WARM_CATCHUP -> SLOW_SCROLL gate:
     * All visible items have been submitted to hydration, OR 3s timeout elapsed.
     * We don't query Room every frame — instead we track what's been handed to
     * CardHydrator. ProfileResolver's 200ms batching handles the actual fetch.
     */
    private fun catchupGateMet(): Boolean {
        val elapsed = System.currentTimeMillis() - stateEnteredAt
        if (elapsed >= WARM_CATCHUP_TIMEOUT_MS) return true
        if (lastVisibleItems.isEmpty()) return false
        return lastVisibleItems.all { isHydrated(it.id, PHASE_PROFILE) || it.pubkey in profileHydratedPubkeys }
    }

    private fun transitionTo(newState: ScrollState, isUserGesture: Boolean = false) {
        if (newState == state) return

        val now = System.currentTimeMillis()

        // Hard dwell lock — BLOCKS all transitions within STATE_DWELL_MS of the last one.
        // Only user gestures and reset() bypass this. Timer-based transitions (IDLE→REST,
        // SLOW_SCROLL→IDLE, catchup timeout) are gated just like velocity-based ones.
        if (!isUserGesture) {
            val sinceLastTransition = now - lastTransitionTime
            if (sinceLastTransition < STATE_DWELL_MS) return
        }

        // REST minimum dwell: non-user exits must wait REST_MINIMUM_DWELL_MS
        if (state == ScrollState.REST && !isUserGesture) {
            val restElapsed = now - stateEnteredAt
            if (restElapsed < REST_MINIMUM_DWELL_MS) return
        }

        lastTransitionTime = now
        val previousState = state
        state = newState
        onStateTransition(previousState, newState)
    }

    private fun onStateTransition(from: ScrollState, to: ScrollState) {
        Log.d(TAG, "Transition: $from → $to (v=${smoothedVelocity.toInt()}px/s, raw=${velocityPxPerSec.toInt()}px/s)")
        stateEnteredAt = System.currentTimeMillis()

        // Cancel state-specific jobs
        when (from) {
            ScrollState.IDLE -> {
                idleTimerJob?.cancel()
                idleToRestJob?.cancel()
                engagementJob?.cancel()
            }
            ScrollState.WARM_CATCHUP -> {
                catchupTimeoutJob?.cancel()
            }
            ScrollState.FAST_SCROLL -> {
                // Entering from FAST_SCROLL → cancel any stale Phase 2 work
                refJob?.cancel()
            }
            ScrollState.REST -> {
                // Exiting REST — nothing to cancel (REST does no work)
            }
            else -> {}
        }

        // Set up new state
        when (to) {
            ScrollState.IDLE -> {
                startIdleEngagement()
                // Start IDLE → REST timer
                idleToRestJob?.cancel()
                idleToRestJob = scope.launch {
                    delay(IDLE_TO_REST_DWELL_MS)
                    if (state == ScrollState.IDLE) {
                        transitionTo(ScrollState.REST)
                    }
                }
            }
            ScrollState.WARM_CATCHUP -> startCatchupTimeout()
            ScrollState.SLOW_SCROLL -> {
                // Auto-start idle timer so engagement fetches even without user scrolling.
                // If user starts scrolling, onScrollStarted() cancels this.
                idleTimerJob?.cancel()
                idleTimerJob = scope.launch {
                    delay(IDLE_TIMEOUT_MS)
                    if (state == ScrollState.SLOW_SCROLL) {
                        transitionTo(ScrollState.IDLE)
                    }
                }
            }
            ScrollState.FAST_SCROLL -> {
                // Cancel all hydration work immediately — total blackout
                profileJob?.cancel()
                refJob?.cancel()
                mediaJob?.cancel()
                // Reset window hash so we reprocess when exiting FAST_SCROLL
                lastProcessedWindowHash = 0
                lastProcessedWindowSize = 0
            }
            ScrollState.REST -> {
                // Absolute rest — cancel all in-flight discretionary work,
                // then launch REST-specific media (MMR allowed)
                profileJob?.cancel()
                refJob?.cancel()
                mediaJob?.cancel()
                engagementJob?.cancel()
                warmEngagementJob?.cancel()
                engagementCoalesceJob?.cancel()
                startRestMedia()
                Log.d(TAG, "Entering REST — cancelled scroll work, started REST media")
            }
        }
    }

    // ── State handlers ────────────────────────────────────────────────

    /**
     * WARM_CATCHUP: Phase 1 ONLY — profiles for visible + warm zone items.
     * No refs, no thumbnails, no engagement. Avatars appear first.
     */
    private fun handleWarmCatchup() {
        val warmZone = computeWarmZone()
        val combined = (lastVisibleItems + warmZone).distinctBy { it.id }
        val toProfile = combined.filter { !isHydrated(it.id, PHASE_PROFILE) && it.pubkey !in profileHydratedPubkeys }
        if (toProfile.isNotEmpty() && profileJob?.isActive != true) {
            toProfile.forEach { markHydrated(it.id, PHASE_PROFILE) }
            profileHydratedPubkeys.addAll(toProfile.map { it.pubkey })
            fanOutPendingIds.addAll(toProfile.map { it.id })
            profileJob = launchProfileHydration(toProfile, fanOut = false, tag = "WARM_CATCHUP")
        }

        // Pre-resolve video thumbnails early so first-visible items have correct
        // aspect ratios before the first IDLE fires (~3.5s after load).
        if (mediaJob?.isActive != true) {
            val mediaItems = combined.filter { !isHydrated(it.id, PHASE_REFS) }
            if (mediaItems.isNotEmpty()) {
                mediaJob = scope.launch(Dispatchers.IO) {
                    cardHydrator.hydrateMedia(mediaItems, mmrAllowed = true, mmrCap = 3)
                    Log.d(TAG, "WARM_CATCHUP: media (MMR cap 3) for ${mediaItems.size} items")
                }
            }
        }

        // Evict ledger entries for items no longer in visible + warm zone
        retainOnly(combined.map { it.id }.toSet())
    }

    /**
     * SLOW_SCROLL: cache-only — zero relay work during active scroll.
     * All relay dispatches (profiles, refs, engagement) happen in IDLE.
     * The SLOW_SCROLL↔IDLE bounce (~500ms) provides frequent hydration windows.
     */
    private fun handleSlowScroll() {
        // No-op: all relay work moved to IDLE
    }

    /**
     * IDLE: Full hydration (profiles + refs) for warm zone + engagement.
     * New items get full fan-out.
     */
    private fun handleIdle() {
        val warmZone = computeWarmZone()
        if (isWindowUnchanged(lastVisibleItems, warmZone)) return
        val combined = lastVisibleItems + warmZone

        // Phase 1: profiles for anything not yet done (full fan-out)
        val toProfile = combined.filter { !isHydrated(it.id, PHASE_PROFILE) && it.pubkey !in profileHydratedPubkeys }
        if (toProfile.isNotEmpty() && profileJob?.isActive != true) {
            toProfile.forEach { markHydrated(it.id, PHASE_PROFILE) }
            profileHydratedPubkeys.addAll(toProfile.map { it.pubkey })
            profileJob = launchProfileHydration(toProfile, fanOut = true, tag = "IDLE")
        }

        // Phase 2: refs + thumbnails (gated by queue — discretionary)
        // Debounced — minimum 500ms between Phase 2 runs
        if (pendingQueueSize == 0) {
            val now = System.currentTimeMillis()
            val toRef = combined.filter { !isHydrated(it.id, PHASE_REFS) }
            if (toRef.isNotEmpty() && refJob?.isActive != true && now - lastRefStartTime >= REF_DEBOUNCE_MS) {
                lastRefStartTime = now
                toRef.forEach { markHydrated(it.id, PHASE_REFS) }
                refJob = launchRefHydration(toRef, tag = "IDLE")
            }
        }

        // Independent media pipeline: image dims (IDLE-safe, no codec work)
        if (mediaJob?.isActive != true) {
            val mediaItems = combined.filter { !isHydrated(it.id, PHASE_REFS) }
            if (mediaItems.isNotEmpty()) {
                mediaJob = scope.launch(Dispatchers.IO) {
                    if (state == ScrollState.FAST_SCROLL || state == ScrollState.REST) return@launch
                    cardHydrator.hydrateMedia(mediaItems, mmrAllowed = true, mmrCap = 3)
                    Log.d(TAG, "IDLE: media (MMR cap 3) for ${mediaItems.size} items")
                }
            }
        }

        // Warm zone engagement pre-check (visible + warm zone — unified)
        checkEngagementFreshness(lastVisibleItems + warmZone)
    }

    /**
     * Engagement freshness check for visible + warm zone items.
     * Checks if engagement data is fresh (updated_at < 5 min). Stale or missing
     * items get a targeted engagement fetch, max 5 per pass.
     * Runs in SLOW_SCROLL and IDLE — NOT FAST_SCROLL or WARM_CATCHUP.
     * Replaces the old IDLE-only fetchEngagementForVisible() — no more dedicated
     * hot zone network calls. Room-cached engagement displays freely.
     */
    private fun checkEngagementFreshness(items: List<FeedRow>) {
        if (items.isEmpty() || warmEngagementJob?.isActive == true) return
        // Pre-filter: skip items already marked in the hydration ledger
        val unflagged = items.filter { !isHydrated(it.id, PHASE_ENGAGEMENT) }
        if (unflagged.isEmpty()) return
        val candidateTargetIds = unflagged.map { engagementTargetId(it) }
            .distinct()
            .filter { it !in engagementFetchedIds }
        if (candidateTargetIds.isEmpty()) {
            // All target IDs already covered — mark the event IDs so we skip faster next time
            unflagged.forEach { markHydrated(it.id, PHASE_ENGAGEMENT) }
            return
        }

        // Build targetId → createdAt map for tier computation.
        // For kind-6 reposts, engagementTargetId returns rootId but we use the
        // repost's createdAt (conservative: repost is newer → shorter threshold).
        val candidateSet = candidateTargetIds.toSet()
        val targetCreatedAt = mutableMapOf<String, Long>()
        for (row in unflagged) {
            val targetId = engagementTargetId(row)
            if (targetId in candidateSet) {
                targetCreatedAt.putIfAbsent(targetId, row.createdAt)
            }
        }

        // Pre-mark candidates to prevent re-submission from concurrent frames.
        unflagged.forEach { markHydrated(it.id, PHASE_ENGAGEMENT) }
        engagementFetchedIds.addAll(candidateTargetIds)
        warmEngagementJob = scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()

            // Group candidates by tier threshold based on post age.
            // Older posts have settled engagement — no need for 5-minute refresh.
            val tierGroups = candidateTargetIds.groupBy { targetId ->
                freshnessThreshold(targetCreatedAt[targetId] ?: 0L, now)
            }

            // Check each tier: IDs whose stats were updated after the threshold are fresh
            val freshIds = mutableSetOf<String>()
            for ((threshold, idsInTier) in tierGroups) {
                freshIds.addAll(idsInTier.filter { memoryEventStore.statsLastUpdated(it) > threshold })
            }

            val staleIds = candidateTargetIds.filter { it !in freshIds }.take(WARM_ZONE_ENGAGEMENT_CAP)
            if (staleIds.isNotEmpty()) {
                queueEngagementFetch(staleIds)
                Log.d(TAG, "Engagement freshness: ${staleIds.size} stale (${freshIds.size} fresh, ${candidateTargetIds.size - freshIds.size - staleIds.size} deferred, tiers: ${tierGroups.size})")
            }
        }
    }

    /**
     * Returns the epoch-ms cutoff for engagement freshness based on post age.
     * Older posts have settled engagement counts and don't need 5-minute refresh cycles.
     * P_ENGAGEMENT_TIERED_FRESHNESS: predicted 71% reduction in engagement batches.
     */
    private fun freshnessThreshold(createdAtSec: Long, nowMs: Long): Long {
        val ageMin = (nowMs / 1000 - createdAtSec) / 60
        val thresholdMs = when {
            ageMin < 10   -> ENGAGEMENT_STALE_MS  // Tier 1: <10 min old → 5 min (current behavior)
            ageMin < 60   -> 15 * 60_000L         // Tier 2: 10-60 min  → 15 min
            ageMin < 360  -> 60 * 60_000L         // Tier 3: 1-6 hours  → 1 hour
            ageMin < 1440 -> 4 * 60 * 60_000L     // Tier 4: 6-24 hours → 4 hours
            else          -> 24 * 60 * 60_000L    // Tier 5: >24 hours  → 24 hours
        }
        return nowMs - thresholdMs
    }

    private fun startIdleEngagement() {
        if (pendingQueueSize > 0) {
            Log.d(TAG, "Skipping IDLE engagement — queue=$pendingQueueSize")
            return
        }

        // Deferred fan-out: batch source + hint relay fetches for items that got
        // indexer-only profile resolution during scroll. Fires once on IDLE entry.
        if (fanOutPendingIds.isNotEmpty()) {
            val combined = lastVisibleItems + computeWarmZone()
            val pendingItems = combined.filter { it.id in fanOutPendingIds }
            if (pendingItems.isNotEmpty()) {
                fanOutPendingIds.removeAll(pendingItems.map { it.id }.toSet())
                val excludeRelay = feedRelayUrl
                scope.launch(Dispatchers.IO) {
                    if (state == ScrollState.REST) return@launch
                    cardHydrator.fanOutProfiles(pendingItems, excludeSourceRelay = excludeRelay)
                    Log.d(TAG, "IDLE: deferred fan-out for ${pendingItems.size} items")
                }
            }
        }

        // 30s stale cycle: clears in-memory dedup so freshness check re-evaluates.
        // checkEngagementFreshness() in handleIdle() covers visible + warm zone items.
        engagementJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(ENGAGEMENT_REFRESH_INTERVAL_MS)
                if (state != ScrollState.IDLE) break
                engagementFetchedIds.clear()
                clearPhase(PHASE_ENGAGEMENT)
                // Reset window hash so handleIdle re-evaluates engagement freshness
                lastProcessedWindowHash = 0
                lastProcessedWindowSize = 0
                Log.d(TAG, "IDLE: stale refresh — cleared engagement dedup for re-check")
            }
        }
    }

    /**
     * REST media: launch MediaMetadataRetriever for videos without imeta poster URL.
     * REST means the user has been stationary for seconds — safe for heavyweight work.
     */
    private fun startRestMedia() {
        val warmZone = computeWarmZone()
        val combined = (lastVisibleItems + warmZone).distinctBy { it.id }
        if (combined.isEmpty()) return
        mediaJob = scope.launch(Dispatchers.IO) {
            cardHydrator.hydrateMedia(combined, mmrAllowed = true)
            Log.d(TAG, "REST: media (MMR allowed) for ${combined.size} items")
        }
    }

    private fun startCatchupTimeout() {
        catchupTimeoutJob = scope.launch {
            delay(WARM_CATCHUP_TIMEOUT_MS)
            if (state == ScrollState.WARM_CATCHUP) {
                Log.d(TAG, "WARM_CATCHUP timeout — forcing transition to SLOW_SCROLL")
                transitionTo(ScrollState.SLOW_SCROLL)
            }
        }
    }

    // ── Idle timer ────────────────────────────────────────────────────

    /**
     * Called when scroll stops. After 500ms without scroll resume, transitions to IDLE.
     */
    fun onScrollStopped() {
        if (state != ScrollState.SLOW_SCROLL && state != ScrollState.WARM_CATCHUP) return
        idleTimerJob?.cancel()
        idleTimerJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (state == ScrollState.SLOW_SCROLL || state == ScrollState.WARM_CATCHUP) {
                transitionTo(ScrollState.IDLE)
            }
        }
    }

    fun onScrollStarted() {
        idleTimerJob?.cancel()
        idleToRestJob?.cancel()
        // Exit REST immediately on user gesture — user intent wins
        if (state == ScrollState.REST) {
            transitionTo(ScrollState.SLOW_SCROLL, isUserGesture = true)
        }
    }

    /** Called from FeedScreen when ReducerState.unreadCount changes. */
    fun onPendingCountChanged(count: Int) {
        pendingQueueSize = count
    }

    // ── Proximity sorting ────────────────────────────────────────────

    /**
     * Sort items by their proximity to the viewport center in the combined list.
     * Items closest to center are prioritized — they're what the user sees first.
     */
    private fun List<FeedRow>.sortedByProximity(
        combined: List<FeedRow>,
        centerIndex: Int,
    ): List<FeedRow> {
        if (size <= 1) return this
        val positionMap = combined.withIndex().associate { (i, row) -> row.id to i }
        return sortedBy { item ->
            val pos = positionMap[item.id] ?: Int.MAX_VALUE
            kotlin.math.abs(pos - centerIndex)
        }
    }

    // ── Engagement coalescing ───────────────────────────────────────

    /**
     * Coalesces engagement fetches into fat batches. checkEngagementFreshness
     * dumps IDs here; a 750ms timer flushes them in one relay round-trip.
     */
    private fun queueEngagementFetch(ids: List<String>) {
        pendingEngagementIds.addAll(ids)
        if (engagementCoalesceJob?.isActive == true) return
        engagementCoalesceJob = scope.launch(Dispatchers.IO) {
            delay(ENGAGEMENT_COALESCE_MS)
            val batch = pendingEngagementIds.toList()
            pendingEngagementIds.clear()
            if (batch.isNotEmpty()) {
                relayPool.fetchEngagementBatch(batch)
                Log.d(TAG, "Engagement batch (coalesced): ${batch.size} items")
            }
        }
    }

    // ── Engagement target resolution ─────────────────────────────────

    /** For kind 6 reposts, engagement targets the original event (root_id), not the wrapper. */
    private fun engagementTargetId(row: FeedRow): String =
        if (row.kind == 6 && row.rootId != null) row.rootId else row.id

    // ── Hydration launchers ───────────────────────────────────────────
    // Dedup is via profileJob?.isActive / refJob?.isActive guards at call sites.
    // Each handler checks the active-job guard before calling these methods,
    // so overlapping launches are prevented without a separate registry.

    private fun launchProfileHydration(items: List<FeedRow>, fanOut: Boolean, tag: String): Job? {
        if (items.isEmpty()) return null
        val excludeRelay = feedRelayUrl
        return scope.launch(Dispatchers.IO) {
            if (state == ScrollState.FAST_SCROLL || state == ScrollState.REST) return@launch
            cardHydrator.hydrateProfiles(items, fanOut = fanOut, excludeSourceRelay = excludeRelay)
            Log.d(TAG, "$tag: profiles for ${items.size} items (fanOut=$fanOut)")
        }
    }

    private fun launchRefHydration(items: List<FeedRow>, tag: String): Job? {
        if (items.isEmpty()) return null
        return scope.launch(Dispatchers.IO) {
            if (state == ScrollState.FAST_SCROLL || state == ScrollState.REST) return@launch
            cardHydrator.hydrateRefs(items)
            Log.d(TAG, "$tag: refs for ${items.size} items")
        }
    }

    // ── Window dedup ──────────────────────────────────────────────────

    /**
     * Returns true if the visible + warm zone window is structurally identical
     * to the last processed tick (same IDs in same order). When true, skip the
     * entire handler body — no filtering, sorting, or job launch needed.
     */
    private fun isWindowUnchanged(visible: List<FeedRow>, warmZone: List<FeedRow>): Boolean {
        var h = 0
        for (row in visible) h = h * 31 + row.id.hashCode()
        for (row in warmZone) h = h * 31 + row.id.hashCode()
        val size = visible.size + warmZone.size
        if (h == lastProcessedWindowHash && size == lastProcessedWindowSize) return true
        lastProcessedWindowHash = h
        lastProcessedWindowSize = size
        return false
    }

    // ── Zone computation ──────────────────────────────────────────────

    private fun computeWarmZone(): List<FeedRow> {
        if (lastAllEvents.isEmpty() || lastVisibleIds.isEmpty()) return emptyList()
        val lastVisibleIdx = lastAllEvents.indexOfLast { it.id in lastVisibleIds }
        if (lastVisibleIdx < 0) return emptyList()
        return lastAllEvents.drop(lastVisibleIdx + 1).take(WARM_ZONE_SIZE)
    }
}
