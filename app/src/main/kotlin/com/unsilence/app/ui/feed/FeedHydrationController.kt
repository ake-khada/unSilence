package com.unsilence.app.ui.feed

import android.util.Log
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "HydrationCtrl"

enum class ScrollState {
    WARM_CATCHUP,
    SLOW_SCROLL,
    IDLE,
    FAST_SCROLL,
}

class FeedHydrationController(
    private val scope: CoroutineScope,
    private val cardHydrator: CardHydrator,
    private val relayPool: RelayPool,
    private val userDao: UserDao,
) {
    companion object {
        const val FAST_SCROLL_ENTER_PX_S = 2500f   // must exceed to enter FAST
        const val FAST_SCROLL_EXIT_PX_S  = 1200f   // must drop below to leave FAST
        const val VELOCITY_WINDOW_FRAMES = 6
        const val IDLE_TIMEOUT_MS = 500L
        const val WARM_ZONE_SIZE = 15
        const val WARM_CATCHUP_TIMEOUT_MS = 3000L
        const val ENGAGEMENT_REFRESH_INTERVAL_MS = 30_000L
        const val REF_DEBOUNCE_MS = 500L               // IDLE
        const val REF_DEBOUNCE_SLOW_SCROLL_MS = 2000L  // SLOW_SCROLL — refs can wait while browsing
    }

    // ── State machine ─────────────────────────────────────────────────
    private var state = ScrollState.WARM_CATCHUP
    private var stateEnteredAt = System.currentTimeMillis()
    private var lastTransitionTime = 0L

    // ── Velocity tracking ─────────────────────────────────────────────
    private val scrollSamples = ArrayDeque<Pair<Long, Int>>(VELOCITY_WINDOW_FRAMES + 2)
    private var velocityPxPerSec = 0f

    // ── Hydration tracking ────────────────────────────────────────────
    private val profileHydratedIds = mutableSetOf<String>()   // Phase 1 done
    private val refHydratedIds = mutableSetOf<String>()       // Phase 2 done
    private val engagementFetchedIds = mutableSetOf<String>()
    private var lastEngagementRefreshTime = 0L
    private var lastRefStartTime = 0L           // Phase 2 debounce

    // ── Jobs ──────────────────────────────────────────────────────────
    private var idleTimerJob: Job? = null
    private var catchupTimeoutJob: Job? = null
    private var profileJob: Job? = null     // Phase 1: profiles
    private var refJob: Job? = null         // Phase 2: refs + thumbnails
    private var engagementJob: Job? = null

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
        // Update velocity
        val now = System.currentTimeMillis()
        scrollSamples.addLast(now to scrollPixelOffset)
        if (scrollSamples.size > VELOCITY_WINDOW_FRAMES + 1) scrollSamples.removeFirst()
        velocityPxPerSec = computeVelocity()

        // Store latest data
        lastVisibleItems = visibleItems
        lastAllEvents = allEvents
        lastVisibleIds = visibleItems.map { it.id }.toSet()

        // State transitions
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
        }
    }

    /**
     * Reset when feed type changes. Clears all tracking, re-enters WARM_CATCHUP.
     */
    fun reset() {
        idleTimerJob?.cancel()
        catchupTimeoutJob?.cancel()
        profileJob?.cancel()
        refJob?.cancel()
        engagementJob?.cancel()
        profileHydratedIds.clear()
        refHydratedIds.clear()
        engagementFetchedIds.clear()
        scrollSamples.clear()
        velocityPxPerSec = 0f
        lastVisibleItems = emptyList()
        lastAllEvents = emptyList()
        lastVisibleIds = emptySet()
        lastEngagementRefreshTime = 0L
        lastRefStartTime = 0L
        lastTransitionTime = 0L
        state = ScrollState.WARM_CATCHUP
        stateEnteredAt = System.currentTimeMillis()
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
        return when (state) {
            ScrollState.WARM_CATCHUP -> {
                if (velocityPxPerSec > FAST_SCROLL_ENTER_PX_S) {
                    ScrollState.FAST_SCROLL
                } else if (catchupGateMet()) {
                    ScrollState.SLOW_SCROLL
                } else {
                    ScrollState.WARM_CATCHUP
                }
            }
            ScrollState.SLOW_SCROLL -> {
                if (velocityPxPerSec > FAST_SCROLL_ENTER_PX_S) {
                    ScrollState.FAST_SCROLL
                } else {
                    ScrollState.SLOW_SCROLL
                }
            }
            ScrollState.IDLE -> {
                if (velocityPxPerSec > FAST_SCROLL_ENTER_PX_S) {
                    ScrollState.FAST_SCROLL
                } else if (isScrollInProgress) {
                    ScrollState.SLOW_SCROLL
                } else {
                    ScrollState.IDLE
                }
            }
            ScrollState.FAST_SCROLL -> {
                if (velocityPxPerSec < FAST_SCROLL_EXIT_PX_S) {
                    ScrollState.WARM_CATCHUP
                } else {
                    ScrollState.FAST_SCROLL
                }
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
        return lastVisibleItems.all { it.id in profileHydratedIds }
    }

    private fun transitionTo(newState: ScrollState) {
        val now = System.currentTimeMillis()
        if (now - lastTransitionTime < 200 && newState != ScrollState.IDLE) return
        lastTransitionTime = now
        val previousState = state
        state = newState
        onStateTransition(previousState, newState)
    }

    private fun onStateTransition(from: ScrollState, to: ScrollState) {
        Log.d(TAG, "Transition: $from → $to (v=${velocityPxPerSec.toInt()}px/s)")
        stateEnteredAt = System.currentTimeMillis()

        // Cancel state-specific jobs
        when (from) {
            ScrollState.IDLE -> {
                idleTimerJob?.cancel()
                engagementJob?.cancel()
            }
            ScrollState.WARM_CATCHUP -> {
                catchupTimeoutJob?.cancel()
            }
            ScrollState.FAST_SCROLL -> {
                // Entering from FAST_SCROLL → cancel any stale Phase 2 work
                refJob?.cancel()
            }
            else -> {}
        }

        // Set up new state
        when (to) {
            ScrollState.IDLE -> startIdleEngagement()
            ScrollState.WARM_CATCHUP -> startCatchupTimeout()
            ScrollState.FAST_SCROLL -> {
                // Cancel all hydration work immediately — total blackout
                profileJob?.cancel()
                refJob?.cancel()
            }
            else -> {}
        }
    }

    // ── State handlers ────────────────────────────────────────────────

    /**
     * WARM_CATCHUP: Phase 1 ONLY — profiles for visible items.
     * No refs, no thumbnails, no engagement. Avatars appear first.
     */
    private fun handleWarmCatchup() {
        val toProfile = lastVisibleItems.filter { it.id !in profileHydratedIds }
        if (toProfile.isEmpty()) return

        if (profileJob?.isActive == true) return
        profileJob = scope.launch(Dispatchers.IO) {
            cardHydrator.hydrateProfiles(toProfile)
            profileHydratedIds.addAll(toProfile.map { it.id })
            Log.d(TAG, "WARM_CATCHUP: profiles for ${toProfile.size} visible items")
        }
    }

    /**
     * SLOW_SCROLL: Phase 1 first for warm zone, then Phase 2 for items
     * that already have profiles resolved.
     */
    private fun handleSlowScroll() {
        val warmZone = computeWarmZone()
        val combined = lastVisibleItems + warmZone

        // Phase 1: profiles for anything not yet profile-hydrated
        val toProfile = combined.filter { it.id !in profileHydratedIds }
        if (toProfile.isNotEmpty() && profileJob?.isActive != true) {
            profileJob = scope.launch(Dispatchers.IO) {
                cardHydrator.hydrateProfiles(toProfile)
                profileHydratedIds.addAll(toProfile.map { it.id })
                Log.d(TAG, "SLOW_SCROLL: profiles for ${toProfile.size} items")
            }
        }

        // Phase 2: refs + thumbnails only for items already profile-hydrated
        // 2000ms debounce during SLOW_SCROLL — user is browsing, refs can wait
        val now = System.currentTimeMillis()
        val toRef = combined.filter { it.id in profileHydratedIds && it.id !in refHydratedIds }
        if (toRef.isNotEmpty() && refJob?.isActive != true && now - lastRefStartTime >= REF_DEBOUNCE_SLOW_SCROLL_MS) {
            lastRefStartTime = now
            refJob = scope.launch(Dispatchers.IO) {
                if (state == ScrollState.FAST_SCROLL) return@launch  // yield if scrolling resumed
                cardHydrator.hydrateRefs(toRef)
                refHydratedIds.addAll(toRef.map { it.id })
                Log.d(TAG, "SLOW_SCROLL: refs for ${toRef.size} items")
            }
        }
    }

    /**
     * IDLE: Full hydration (profiles + refs) for warm zone + engagement.
     */
    private fun handleIdle() {
        val warmZone = computeWarmZone()
        val combined = lastVisibleItems + warmZone

        // Phase 1: profiles for anything not yet done
        val toProfile = combined.filter { it.id !in profileHydratedIds }
        if (toProfile.isNotEmpty() && profileJob?.isActive != true) {
            profileJob = scope.launch(Dispatchers.IO) {
                cardHydrator.hydrateProfiles(toProfile)
                profileHydratedIds.addAll(toProfile.map { it.id })
                Log.d(TAG, "IDLE: profiles for ${toProfile.size} items")
            }
        }

        // Phase 2: refs + thumbnails
        // Debounced — minimum 500ms between Phase 2 runs
        val now = System.currentTimeMillis()
        val toRef = combined.filter { it.id !in refHydratedIds }
        if (toRef.isNotEmpty() && refJob?.isActive != true && now - lastRefStartTime >= REF_DEBOUNCE_MS) {
            lastRefStartTime = now
            refJob = scope.launch(Dispatchers.IO) {
                if (state == ScrollState.FAST_SCROLL) return@launch
                cardHydrator.hydrateRefs(toRef)
                refHydratedIds.addAll(toRef.map { it.id })
                Log.d(TAG, "IDLE: refs for ${toRef.size} items")
            }
        }
    }

    private fun startIdleEngagement() {
        engagementJob = scope.launch(Dispatchers.IO) {
            // Initial engagement fetch for visible items
            fetchEngagementForVisible()

            // Stale refresh loop every 30s while IDLE
            while (true) {
                delay(ENGAGEMENT_REFRESH_INTERVAL_MS)
                if (state != ScrollState.IDLE) break
                fetchEngagementForVisible()
                Log.d(TAG, "IDLE: stale engagement refresh")
            }
        }
    }

    private fun fetchEngagementForVisible() {
        val novelIds = lastVisibleItems
            .map { it.id }
            .filter { it !in engagementFetchedIds }
            .take(20)
        if (novelIds.isNotEmpty()) {
            engagementFetchedIds.addAll(novelIds)
            relayPool.fetchEngagementBatch(novelIds)
            lastEngagementRefreshTime = System.currentTimeMillis()
            Log.d(TAG, "Engagement fetch: ${novelIds.size} items")
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
    }

    // ── Zone computation ──────────────────────────────────────────────

    private fun computeWarmZone(): List<FeedRow> {
        if (lastAllEvents.isEmpty() || lastVisibleIds.isEmpty()) return emptyList()
        val lastVisibleIdx = lastAllEvents.indexOfLast { it.id in lastVisibleIds }
        if (lastVisibleIdx < 0) return emptyList()
        return lastAllEvents.drop(lastVisibleIdx + 1).take(WARM_ZONE_SIZE)
    }
}
