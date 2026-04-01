package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.db.dao.EventDao
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.repository.UserRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HydrationFrontier"

// ── Step 3: HydrationNeed ────────────────────────────────────────────────────

sealed class HydrationNeed {
    /** Author profile (kind 0) missing or stale. */
    data class Profile(val pubkey: String) : HydrationNeed()

    /** Kind 6 repost target event not in Room. */
    data class RepostTarget(val eventId: String) : HydrationNeed()

    /** Quoted event (nostr:nevent/note) not in Room. */
    data class QuotedEvent(val eventId: String) : HydrationNeed()

    /** Non-media URL needs OG metadata preview. */
    data class OgPreview(val url: String) : HydrationNeed()
}

// ── Step 4: WarmWindow ───────────────────────────────────────────────────────

/**
 * Viewport-relative window of feed items that need hydration.
 * Built from [LazyListLayoutInfo] + the full feed list.
 *
 * [visible]  — items currently on screen (never shed).
 * [ahead]    — items below the viewport (scroll direction, ~2 screens).
 * [behind]   — items above the viewport (~1 screen, lower priority).
 */
data class WarmWindow(
    val visible: List<FeedRow>,
    val ahead: List<FeedRow>,
    val behind: List<FeedRow>,
    val scrollVelocity: Float = 0f,
) {
    val all: List<FeedRow> get() = behind + visible + ahead

    companion object {
        /**
         * Build a WarmWindow from layout info.
         *
         * @param visibleKeys        set of item keys currently visible on screen
         * @param firstVisibleIndex  index of first visible item in [events]
         * @param lastVisibleIndex   index of last visible item in [events]
         * @param events             full ordered feed list
         * @param avgItemHeightPx    average visible item height in pixels (from layoutInfo)
         * @param aheadBudgetPx      pixel budget for the ahead prefetch zone (density-derived)
         * @param behindBudgetPx     pixel budget for the behind prefetch zone (density-derived)
         * @param scrollVelocity     current scroll velocity in px/ms (for cadence logging)
         */
        fun from(
            visibleKeys: Set<String>,
            firstVisibleIndex: Int,
            lastVisibleIndex: Int,
            events: List<FeedRow>,
            avgItemHeightPx: Float = 0f,
            aheadBudgetPx: Float = 0f,
            behindBudgetPx: Float = 0f,
            scrollVelocity: Float = 0f,
        ): WarmWindow {
            if (events.isEmpty()) return WarmWindow(emptyList(), emptyList(), emptyList())

            val pageSize = (lastVisibleIndex - firstVisibleIndex + 1).coerceAtLeast(1)

            // Pixel-based estimation when we have real item heights and budgets,
            // fall back to row-count multiplier otherwise.
            val aheadCount: Int
            val behindCount: Int
            if (avgItemHeightPx > 0f && aheadBudgetPx > 0f) {
                aheadCount = (aheadBudgetPx / avgItemHeightPx).toInt().coerceIn(3, 30)
                behindCount = (behindBudgetPx / avgItemHeightPx).toInt().coerceIn(1, 10)
            } else {
                aheadCount = (pageSize * 2).coerceIn(3, 30)
                behindCount = pageSize.coerceIn(1, 10)
            }

            val visible = events.filter { it.id in visibleKeys }
            val aheadStart = (lastVisibleIndex + 1).coerceAtMost(events.size)
            val aheadEnd = (aheadStart + aheadCount).coerceAtMost(events.size)
            val ahead = events.subList(aheadStart, aheadEnd)

            val behindEnd = firstVisibleIndex.coerceAtMost(events.size)
            val behindStart = (behindEnd - behindCount).coerceAtLeast(0)
            val behind = events.subList(behindStart, behindEnd)

            return WarmWindow(visible, ahead, behind, scrollVelocity)
        }
    }
}

// ── Step 5: FeedRow.missingFields() ──────────────────────────────────────────

private val IMAGE_URL_PATTERN = Regex(
    """https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?|https?://(?:image\.nostr\.build|i\.nostr\.build|nostr\.build|blossom\.primal\.net)/\S+""",
    RegexOption.IGNORE_CASE,
)
private val VIDEO_URL_PATTERN = Regex(
    """https?://\S+\.(?:mp4|mov|webm|m3u8|m4v|avi)(?:\?\S*)?""",
    RegexOption.IGNORE_CASE,
)
private val YOUTUBE_URL_PATTERN = Regex(
    """https?://(?:www\.)?(?:youtube\.com/(?:watch\?v=|shorts/)|youtu\.be/)([A-Za-z0-9_-]{11})\S*""",
    RegexOption.IGNORE_CASE,
)
private val LINK_URL_PATTERN = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

/**
 * Pure function: examines a single FeedRow and returns every [HydrationNeed]
 * that can be determined without IO (Room/network).
 */
fun FeedRow.missingFields(): List<HydrationNeed> {
    val needs = mutableListOf<HydrationNeed>()

    // Profile: missing when author columns are null
    if (authorName == null && authorDisplayName == null && authorPicture == null) {
        needs.add(HydrationNeed.Profile(pubkey))
    }

    // Kind 6 repost: original event needed
    if (kind == 6) {
        extractRepostTargetId(tags)?.let { needs.add(HydrationNeed.RepostTarget(it)) }
        // Also need original author profile
        extractRepostAuthorPubkey(content, tags)?.let {
            needs.add(HydrationNeed.Profile(it))
        }
    }

    // Quoted events (nostr:nevent/note URIs in content)
    extractQuotedEventIds(content).forEach {
        needs.add(HydrationNeed.QuotedEvent(it))
    }

    // OG previews: non-media URLs in content
    val stripped = IMAGE_URL_PATTERN.replace(
        VIDEO_URL_PATTERN.replace(
            YOUTUBE_URL_PATTERN.replace(content, ""),
            "",
        ),
        "",
    )
    LINK_URL_PATTERN.findAll(stripped).forEach { match ->
        needs.add(HydrationNeed.OgPreview(match.value))
    }

    return needs
}

// ── Step 6: PlannerCadence ───────────────────────────────────────────────────

enum class PlannerCadence(val debounceMs: Long) {
    /** User idle or slow scroll — flush fast. */
    IDLE(100),
    /** Moderate scrolling — standard debounce. */
    MODERATE(500),
    /** Fast fling — batch bigger, shed low-priority. */
    FAST(1500),
}

/** Map scroll velocity (absolute px/ms) to planner cadence. */
fun scrollVelocityToCadence(absPxPerMs: Float): PlannerCadence = when {
    absPxPerMs < 0.5f  -> PlannerCadence.IDLE
    absPxPerMs < 3.0f  -> PlannerCadence.MODERATE
    else               -> PlannerCadence.FAST
}

// ── Step 7: HydrationFrontier ────────────────────────────────────────────────

/**
 * Viewport-driven hydration planner that replaces CardHydrator.
 *
 * Key properties:
 * - Mutex-serialized [plan] — only one plan executes at a time.
 * - Room subtraction before network — never re-fetch what's already cached.
 * - Priority shedding — under relay pressure, OG previews are dropped first,
 *   then ahead window is capped.
 * - TTL-based dedup — prevents re-planning the same needs within a window.
 */
@Singleton
class HydrationFrontier @Inject constructor(
    private val eventDao: EventDao,
    private val userDao: UserDao,
    private val relayPool: RelayPool,
    private val userRepository: UserRepository,
    private val ogFetcher: OgFetcher,
) {
    private val mutex = Mutex()

    /** Recently planned items — value is epoch millis of last plan. */
    private val recentlyPlanned = ConcurrentHashMap<String, Long>()
    private val PLAN_TTL_MS = 30_000L

    /** Threshold constants for priority shedding. */
    private companion object {
        const val SHED_OG_THRESHOLD = 15
        const val CAP_AHEAD_THRESHOLD = 20
    }

    /**
     * Plan and dispatch hydration for the given viewport window.
     * Mutex-serialized: concurrent calls queue behind the current plan.
     */
    suspend fun plan(window: WarmWindow) = mutex.withLock {
        val now = System.currentTimeMillis()
        evictStale(now)

        val inFlight = relayPool.activeOneShotCount()

        // 1. Collect all needs from the window, respecting priority zones
        val visibleNeeds = window.visible.flatMap { it.missingFields() }
        val aheadNeeds = if (inFlight >= CAP_AHEAD_THRESHOLD) {
            emptyList() // shed ahead entirely under extreme pressure
        } else {
            window.ahead.flatMap { it.missingFields() }
        }
        val behindNeeds = window.behind.flatMap { it.missingFields() }

        val allNeeds = visibleNeeds + aheadNeeds + behindNeeds

        // 2. Dedup against recently-planned TTL
        val novel = allNeeds.filter { need ->
            val key = needKey(need)
            val last = recentlyPlanned[key]
            last == null || (now - last) > PLAN_TTL_MS
        }.distinctBy { needKey(it) }

        if (novel.isEmpty()) return@withLock

        // Mark as planned
        novel.forEach { recentlyPlanned[needKey(it)] = now }

        // 3. Partition by type
        val profiles = novel.filterIsInstance<HydrationNeed.Profile>().map { it.pubkey }
        val repostTargets = novel.filterIsInstance<HydrationNeed.RepostTarget>().map { it.eventId }
        val quotedEvents = novel.filterIsInstance<HydrationNeed.QuotedEvent>().map { it.eventId }
        val ogUrls = if (inFlight >= SHED_OG_THRESHOLD) {
            emptyList() // shed OG under moderate pressure
        } else {
            novel.filterIsInstance<HydrationNeed.OgPreview>().map { it.url }
        }

        // 4. Room subtraction — only fetch what's truly missing
        val allEventIds = (repostTargets + quotedEvents).distinct()
        val existingEventIds = if (allEventIds.isNotEmpty()) {
            allEventIds.chunked(500).flatMap { chunk ->
                eventDao.getExistingIds(chunk)
            }.toSet()
        } else emptySet()

        val existingPubkeys = if (profiles.isNotEmpty()) {
            profiles.chunked(500).flatMap { chunk ->
                userDao.getExistingPubkeys(chunk)
            }.toSet()
        } else emptySet()

        val missingEvents = allEventIds.filter { it !in existingEventIds }
        val missingProfiles = profiles.filter { it !in existingPubkeys }
        val missingOg = ogUrls.filter { !ogFetcher.hasCached(it) }

        // Log Room subtraction impact
        val roomKnownEvents = existingEventIds.size
        val roomKnownProfiles = existingPubkeys.size
        if (roomKnownEvents > 0 || roomKnownProfiles > 0) {
            Log.d(TAG, "Room subtracted: $roomKnownEvents events, $roomKnownProfiles profiles")
        }

        // 5. Dispatch to network
        if (missingEvents.isNotEmpty()) {
            relayPool.fetchEventsByIds(missingEvents)
        }
        if (missingProfiles.isNotEmpty()) {
            userRepository.fetchMissingProfiles(missingProfiles)
        }
        // OG fetches are fire-and-forget — NoteCard's produceState will pick up results.
        // HydrationFrontier's OG tracking just prevents re-planning.

        val cadence = scrollVelocityToCadence(window.scrollVelocity)
        Log.d(
            TAG,
            "plan: visible=${window.visible.size} ahead=${window.ahead.size} behind=${window.behind.size} | " +
                "missing: events=${missingEvents.size} profiles=${missingProfiles.size} og=${missingOg.size} | " +
                "novel=${novel.size} inFlight=$inFlight cadence=$cadence shed=${inFlight >= SHED_OG_THRESHOLD}",
        )
    }

    /** Clear all tracking state (e.g., on feed switch). */
    fun clearCache() {
        recentlyPlanned.clear()
    }

    private fun evictStale(now: Long) {
        recentlyPlanned.entries.removeIf { now - it.value > PLAN_TTL_MS }
    }

    private fun needKey(need: HydrationNeed): String = when (need) {
        is HydrationNeed.Profile -> "p:${need.pubkey}"
        is HydrationNeed.RepostTarget -> "rt:${need.eventId}"
        is HydrationNeed.QuotedEvent -> "qe:${need.eventId}"
        is HydrationNeed.OgPreview -> "og:${need.url}"
    }
}
