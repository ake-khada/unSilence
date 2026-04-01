# HydrationFrontier — Feed Prefetch Architecture

**Status:** Design spec — ready for Phase 1 implementation
**Replaces:** CardHydrator + per-card produceState fetches
**Does NOT change:** Room schema, RelayPool protocol, FeedStateReducer, VideoPlaybackScope

---

## Problem

The current architecture triggers network work per-card as items compose:
- CardHydrator fires on every snapshotFlow emission (125 calls/session)
- Each hydration cycle triggers profile fetches (180 batches × 4 relays = 720 round-trips)
- Engagement fetches fire per-visible-set (78 calls × 3 lanes = 234 subs)
- Parent notes and repost targets use produceState per-card (N+1 pattern)
- OG metadata fetches per-card via produceState
- Total: 479 one-shot subs in one browsing session, 94-frame skips, 1162ms Davey

## Core Principle

**One planner pass over a bounded frontier. Batch by entity type. Room mediates everything.**

The UI never triggers network work directly. The planner scans the warm window,
computes what's missing from Room, emits typed batch sets, and the executor
fires minimal relay REQs. Results flow into Room → reactive Flows → UI updates.

---

## Warm Window

Computed from `LazyListState.layoutInfo`, NOT from item count:

```kotlin
data class WarmWindow(
    val visibleItems: List<FeedRow>,  // items currently on screen
    val aheadItems: List<FeedRow>,    // items within ~800dp ahead of viewport
    val behindItems: List<FeedRow>,   // items within ~200dp behind viewport
    val visibleRange: IntRange,       // visible indices
    val scrollVelocity: Float,        // current scroll speed (px/ms)
    val scrollDirection: Direction,   // UP or DOWN
    val isConversationsTab: Boolean,  // parent note fetches only when true
) {
    val items: List<FeedRow> get() = visibleItems + aheadItems + behindItems
}
```

The ahead distance is viewport-distance-based (dp/px), not item-count-based,
because feed items have wildly different heights (text-only: ~80dp, image: ~400dp,
video: ~300dp). "2 screens ahead" means ~800dp on a typical phone, which might
be 3 image posts or 10 text posts.

Compute from layoutInfo:
```kotlin
// layoutInfo only exposes VISIBLE items — it does NOT know off-screen item offsets.
// So the frontier is: visible items + estimated N items ahead based on average item height.
val visible = layoutInfo.visibleItemsInfo
val avgItemHeight = if (visible.isNotEmpty()) {
    visible.sumOf { it.size } / visible.size
} else 300  // reasonable default px

val aheadBudgetPx = 800.dp.toPx()  // ~2 screens
val estimatedAheadCount = (aheadBudgetPx / avgItemHeight).toInt().coerceIn(3, 30)

val lastVisibleIndex = visible.lastOrNull()?.index ?: 0
val aheadEndIndex = (lastVisibleIndex + estimatedAheadCount).coerceAtMost(feedEvents.lastIndex)
val aheadItems = feedEvents.subList(lastVisibleIndex + 1, aheadEndIndex + 1)
val visibleItems = visible.mapNotNull { info -> feedEvents.getOrNull(info.index) }
```

This is intentionally imprecise — estimated-index budget, not real offset math.
The frontier doesn't need pixel-perfect boundaries. It needs "roughly 2 screens
worth of rows" to batch their needs. Off by 2-3 items is fine.

---

## Conditional Completeness

NOT a flat bitmask. Completeness depends on what the row type requires:

```kotlin
fun FeedRow.missingFields(isConversationsTab: Boolean = false): Set<HydrationNeed> {
    val needs = mutableSetOf<HydrationNeed>()
    
    // Profile: flag only when completely absent from the JOIN result.
    // Partial profiles (name but no picture) are still profiles — staleness
    // is handled by ProfileResolver's 6h/1h TTL internally.
    if (authorPicture == null && authorName == null && authorDisplayName == null) {
        needs += HydrationNeed.Profile(pubkey)
    }
    
    // Kind-6 reposts with empty content need the inner event (bridged content)
    if (kind == 6 && content.isBlank()) {
        extractRepostTargetId(tags)?.let { needs += HydrationNeed.Event(it) }
    }
    
    // Parent note fetch ONLY on Conversations tab — not every reply everywhere
    if (isConversationsTab && replyToId != null) {
        needs += HydrationNeed.Event(replyToId)
    }
    
    // OG candidate — planner checks ogFetcher.hasCached(), not the row.
    // missingFields() must be PURE (no service dependencies).
    val urls = extractNonMediaUrls(content)
    if (urls.isNotEmpty()) {
        needs += HydrationNeed.OgMetadata(urls.first())
    }
    
    return needs
}

sealed class HydrationNeed {
    data class Profile(val pubkey: String) : HydrationNeed()
    data class Event(val eventId: String) : HydrationNeed()
    data class OgMetadata(val url: String) : HydrationNeed()
    data class Engagement(val eventId: String) : HydrationNeed()
}
```

A text-only kind-1 root note with a cached profile and no URLs? Already complete.
A kind-6 repost from mostr.pub with empty content? Needs Event + Profile.
A reply in Conversations tab? Needs parent Event + parent Profile.

---

## Planner

One pass per tick. Collects all needs from the warm window into typed sets:

```kotlin
class HydrationFrontier @Inject constructor(
    private val profileResolver: ProfileResolver,
    private val relayPool: RelayPool,
    private val ogFetcher: OgFetcher,
    private val eventDao: EventDao,
    private val userDao: UserDao,
) {
    // In-memory tracking — different retention policies per type
    private val requestedProfiles = ConcurrentHashMap<String, Long>()  // pubkey → timestamp (TTL-based, reusable)
    private val requestedEvents = ConcurrentHashMap<String, Int>()     // eventId → frontier generation (short-lived)
    private val requestedOg = ConcurrentHashMap<String, Long>()        // url → timestamp (TTL-based)
    
    private val currentGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    
    // PHASE 1: Serialize plan() — overlapping calls rebuild churn in a fancier shape.
    private val planMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun plan(window: WarmWindow) = planMutex.withLock {
        val gen = currentGeneration.incrementAndGet()
        val now = System.currentTimeMillis()
        
        val cadence = scrollVelocityToCadence(window.scrollVelocity)
        val skipOg = cadence == PlannerCadence.Fast
        
        // 1. Partition needs by priority zone: visible > ahead > behind
        val visibleNeeds = window.visibleItems.flatMap { it.missingFields(window.isConversationsTab) }
        val aheadNeeds = window.aheadItems.flatMap { it.missingFields(window.isConversationsTab) }
        val behindNeeds = window.behindItems.flatMap { it.missingFields(window.isConversationsTab) }
        
        // 2. Deduplicate across zones and subtract already-requested (TTL/generation)
        val profileTtlMs = 300_000L
        val ogTtlMs = 600_000L
        
        fun filterProfiles(needs: List<HydrationNeed>): List<String> =
            needs.filterIsInstance<HydrationNeed.Profile>()
                .map { it.pubkey }.distinct()
                .filter { pk -> val ts = requestedProfiles[pk]; ts == null || (now - ts > profileTtlMs) }
        
        fun filterEvents(needs: List<HydrationNeed>): List<String> =
            needs.filterIsInstance<HydrationNeed.Event>()
                .map { it.eventId }.distinct()
                .filter { id -> requestedEvents[id] == null }
        
        val visibleEvents = filterEvents(visibleNeeds)
        val visibleProfiles = filterProfiles(visibleNeeds)
        val aheadEvents = filterEvents(aheadNeeds) - visibleEvents.toSet()
        val aheadProfiles = filterProfiles(aheadNeeds) - visibleProfiles.toSet()
        
        val candidateOg = if (skipOg) emptyList() else {
            (visibleNeeds + aheadNeeds).filterIsInstance<HydrationNeed.OgMetadata>()
                .map { it.url }.distinct()
                .filter { url -> !ogFetcher.hasCached(url) }  // cache check lives HERE, not in missingFields()
                .filter { url -> val ts = requestedOg[url]; ts == null || (now - ts > ogTtlMs) }
                .take(5)
        }
        
        // 3. SUBTRACT what Room already has — one batched query per type.
        //    These DAO calls MUST be suspend/IO-safe and batched (IN clause),
        //    NOT per-item probes. The point is to kill overproduction, not
        //    swap network churn for chatty DB probing.
        //    Also: chunk to ≤ 500 args per query to stay under SQLite limits.
        //    NOT per-item probes. The point is to kill overproduction, not
        //    swap network churn for chatty DB probing.
        val allCandidateEvents = visibleEvents + aheadEvents
        val missingEvents = if (allCandidateEvents.isNotEmpty()) {
            val knownInRoom = eventDao.getExistingIds(allCandidateEvents)  // one query
            allCandidateEvents - knownInRoom.toSet()
        } else emptyList()
        
        val allCandidateProfiles = visibleProfiles + aheadProfiles
        val missingProfiles = if (allCandidateProfiles.isNotEmpty()) {
            val knownInRoom = userDao.getExistingPubkeys(allCandidateProfiles)  // one query
            allCandidateProfiles - knownInRoom.toSet()
        } else emptyList()
        
        // 4. Priority shedding under relay pressure.
        //    Visible ALWAYS fetched. Shed from behind first, then ahead OG.
        val inFlight = relayPool.activeOneShotCount()
        val visibleMissingEvents = missingEvents.filter { it in visibleEvents.toSet() }
        val aheadMissingEvents = missingEvents - visibleMissingEvents.toSet()
        val visibleMissingProfiles = missingProfiles.filter { it in visibleProfiles.toSet() }
        val aheadMissingProfiles = missingProfiles - visibleMissingProfiles.toSet()
        
        // Under pressure: always fetch visible, cap ahead work
        val aheadEventBudget = if (inFlight > 20) 3 else aheadMissingEvents.size
        val aheadProfileBudget = if (inFlight > 20) 3 else aheadMissingProfiles.size
        val ogUrls = if (inFlight > 15) emptyList() else candidateOg
        
        // 5. Execute in priority order:
        //    visible events → visible profiles → ahead events → ahead profiles → OG
        val eventsToFetch = visibleMissingEvents + aheadMissingEvents.take(aheadEventBudget)
        if (eventsToFetch.isNotEmpty()) {
            eventsToFetch.forEach { requestedEvents[it] = gen }
            relayPool.fetchEventsByIds(eventsToFetch)  // ONE batch REQ
        }
        
        val profilesToFetch = visibleMissingProfiles + aheadMissingProfiles.take(aheadProfileBudget)
        if (profilesToFetch.isNotEmpty()) {
            profilesToFetch.forEach { requestedProfiles[it] = now }
            profileResolver.fetchBatch(profilesToFetch)  // indexer relays
        }
        
        ogUrls.forEach { url ->
            requestedOg[url] = now
            ogFetcher.prefetch(url)
        }
        
        // Engagement: UNTOUCHED in Phase 1. Existing coalesced channel stays as-is.
        
        // 6. Expire stale event requests
        val staleGen = gen - 3
        requestedEvents.entries.removeIf { it.value < staleGen }
    }
    
    fun clear() {
        requestedProfiles.clear()
        requestedEvents.clear()
        requestedOg.clear()
        currentGeneration.set(0)
    }
}
```

---

## Dynamic Budget

NOT a fixed 2-second timer. Adaptive to scroll state:

```kotlin
sealed class PlannerCadence {
    // User stopped scrolling or scrolling very slowly
    // → flush immediately, small batch likely
    object Idle : PlannerCadence()
    
    // User scrolling at moderate speed
    // → batch every 500ms, medium batches
    object Moderate : PlannerCadence()
    
    // User flinging fast
    // → batch every 1500ms, large batches, skip OG entirely
    object Fast : PlannerCadence()
}

fun scrollVelocityToCadence(velocityPxPerMs: Float): PlannerCadence = when {
    abs(velocityPxPerMs) < 0.5f  -> PlannerCadence.Idle
    abs(velocityPxPerMs) < 3.0f  -> PlannerCadence.Moderate
    else                          -> PlannerCadence.Fast
}
```

During fast fling: skip OG entirely. Only fetch events + profiles.
During idle: flush everything including OG.
**Engagement is NOT touched by the planner. It stays on its existing coalesced channel.**

---

## Compose Integration

### LazyLayoutCacheWindow (free perf)

```kotlin
// Requires Compose BOM 2025.08.00+ (we're on 2025.05.00 — needs BOM upgrade)
@OptIn(ExperimentalFoundationApi::class)
val cacheWindow = LazyLayoutCacheWindow(ahead = 300.dp, behind = 100.dp)
val listState = rememberLazyListState(cacheWindow = cacheWindow)
```

This pre-composes ~3 screens of items during idle frames via pausable composition.
Items in the cache window have their LaunchedEffects fire early — but we do NOT
use LaunchedEffect for fetch triggers. The planner drives all fetching.

### snapshotFlow → Planner

```kotlin
// In FeedScreen, replace current hydration snapshotFlow with:
LaunchedEffect(listState) {
    snapshotFlow { 
        WarmWindow.from(listState.layoutInfo, feedEvents, contentFilter)
    }
    // Custom frontier key — NOT full WarmWindow equality (rebuilds churn).
    // Key on: visible index range + coarse ahead bound + velocity bucket.
    .distinctUntilChangedBy { w ->
        Triple(w.visibleRange, w.aheadItems.size, scrollVelocityToCadence(w.scrollVelocity))
    }
    .debounce { window ->
        when (scrollVelocityToCadence(window.scrollVelocity)) {
            PlannerCadence.Idle -> 100L
            PlannerCadence.Moderate -> 500L
            PlannerCadence.Fast -> 1500L
        }
    }
    .collect { window ->
        hydrationFrontier.plan(window)
    }
}
```

---

## What This Replaces

| Current | New |
|---------|-----|
| CardHydrator.hydrateVisibleCards() | HydrationFrontier.plan() |
| Per-card produceState for parent notes | Batched fetchEventsByIds for all parents |
| Per-card produceState for repost targets | Same batch as parents |
| Per-card OG fetch via produceState | Planner-driven ogFetcher.prefetch() |
| ProfileResolver called per-cycle | One batch per planner tick |
| snapshotFlow → hydrate visible only | snapshotFlow → plan visible + 2 screens ahead |

## What This Does NOT Change

- Room schema (no migrations)
- RelayPool WebSocket protocol
- FeedStateReducer (blue dot, merge, structural dedup)
- VideoPlaybackScope (active zone detection, shared ExoPlayer)
- EventProcessor (kind handlers, dedup, Room insert)
- Notes/Conversations tab split
- **Engagement channel (still CONFLATED, still coalesced — Phase 1 does NOT touch engagement)**

---

## Implementation Phases

### Phase 1 — Planner + Typed Batches (one Claude Code session)
1. Create HydrationFrontier class with Mutex-serialized plan() method
2. Create WarmWindow data class with from(layoutInfo, feedEvents) factory
3. Add `eventDao.getExistingIds()` and `userDao.getExistingPubkeys()` batch queries
4. Replace CardHydrator usage in FeedViewModel with HydrationFrontier
5. Wire snapshotFlow in FeedScreen to planner with velocity-based debounce
6. Remove per-card produceState for parent notes (conversations)
7. Remove per-card produceState for repost targets (bridged content)
8. Add batched fetchEventsByIds call for all missing events
9. Priority shedding under relay pressure (not hard return)
10. Verify: device log shows typed batch fetches, not per-card one-shots

### Phase 2 — Compose Upgrade + Cache Window
1. Upgrade Compose BOM to 2025.08.00+ for LazyLayoutCacheWindow
2. Add cacheWindow to LazyListState in FeedScreen
3. Verify: pausable composition active, prefetch trace visible in systrace

### Phase 3 — Measurement + Tuning
1. Measure: compare relay round-trips before/after with device logs
2. Tune velocity thresholds based on real device scroll data
3. Tune pressure shedding thresholds based on relay response times
4. Tune TTLs based on observed re-request patterns

---

## Success Criteria (Phase 1)

- One-shot relay subs per session: significantly reduced (was 479)
- Frame skips > 30 frames: reduced (was 16 instances, worst 94)
- Davey frames: reduced (was 4, worst 1162ms)
- Profile fetch batches: significantly reduced (was 180)
- fetchEventsByIds calls: batched, not per-item (was repeated "1 events 3 relays")
- Phone temperature: noticeably cooler during 60s browse
- **Engagement is NOT measured here** — Phase 1 does not touch engagement
