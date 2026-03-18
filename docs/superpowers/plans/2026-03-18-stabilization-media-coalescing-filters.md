# Stabilization Sprint: Media Sizing, Fetch Coalescing, Filters, UI Polish

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix image/GIF sizing to match video quality, eliminate relay storm from duplicate fetches, wire filters to Following feed, add kind toggles to FilterScreen, show user avatar in nav, fix notification avatars.

**Architecture:** Six independent tasks touching distinct layers. Task 1 (image sizing) mirrors the proven video aspect ratio system. Task 2 (fetch coalescing) adds global in-flight tracking at RelayPool level. Task 3 (filters) wires existing FeedFilter to the Following feed DAO query. Task 4 (FilterScreen kind toggles) is pure UI. Task 5 (avatar nav) passes user profile down to AppNavigation. Task 6 (notification profiles) triggers profile fetches for notification actors.

**Tech Stack:** Kotlin, Jetpack Compose, Room SQLite, Coil 3.0.4 (`coil3.*`), Hilt DI

---

### Task 1: Image/GIF Aspect Ratio Fix

**Files:**
- Create: `app/src/main/kotlin/com/unsilence/app/ui/feed/ImageSizing.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt:718-760`

- [ ] **Step 1: Create `ImageSizing.kt`**

Create the shared image sizing function, mirroring `VideoSizing.kt`:

```kotlin
package com.unsilence.app.ui.feed

/**
 * Single source of truth for feed image container aspect ratios.
 * Mirrors [feedVideoAspectRatio] so images and videos size identically.
 */
internal fun feedImageAspectRatio(
    rawAspectRatio: Float?,
    forceSquare: Boolean = false,
): Float {
    if (forceSquare) return 1f
    val raw = rawAspectRatio?.takeIf { it > 0f } ?: (4f / 3f) // default: 4:3 (most common photo)
    return when {
        raw >= 1f -> raw              // landscape: use actual
        else -> maxOf(raw, 9f / 16f)  // portrait: cap at 9:16
    }
}
```

- [ ] **Step 2: Update `MediaImage` in `NoteCard.kt`**

Replace the current `MediaImage` composable (lines 713–760) with aspect-ratio-aware version that always constrains the container and updates on Coil load success:

Replace the entire `MediaImage` function body. The key changes:
1. Always apply `.aspectRatio()` — use `feedImageAspectRatio(imetaAspect, forceSquare)` even when imeta is null (falls back to 4:3)
2. Use `mutableStateOf` for `displayAspect` so it can update when Coil resolves the actual bitmap dimensions
3. Use `SubcomposeAsyncImage`'s `success` content slot (Coil 3 API) to read painter intrinsic size and update `displayAspect`
4. Use `ContentScale.Crop` (not Fit) so images fill the aspect-ratio-constrained container

**Important:** This project uses **Coil 3.0.4** (`coil3.compose.SubcomposeAsyncImage`). Coil 3's `SubcomposeAsyncImage` does NOT accept `onSuccess` as a parameter — use the `success` content slot instead.

```kotlin
@Composable
private fun MediaImage(
    url: String,
    imetaMedia: List<ImetaMedia>,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    forceSquare: Boolean = false,
) {
    val imetaAspect = imetaMedia
        .firstOrNull { it.url == url && it.width != null && it.height != null }
        ?.let { it.width!!.toFloat() / it.height!! }

    var displayAspect by remember(url, forceSquare) {
        mutableStateOf(feedImageAspectRatio(imetaAspect, forceSquare))
    }

    val imageModifier = modifier
        .fillMaxWidth()
        .aspectRatio(displayAspect, matchHeightConstraintsFirst = false)
        .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
        .background(MediaPlaceholder)
        .clickable { onImageClick(url) }

    SubcomposeAsyncImage(
        model              = url,
        contentDescription = null,
        loading            = { ShimmerBox(modifier = Modifier.fillMaxSize()) },
        error              = {
            Box(
                modifier         = Modifier.fillMaxSize().background(MediaPlaceholder),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.BrokenImage,
                    contentDescription = null,
                    tint               = TextSecondary,
                    modifier           = Modifier.size(Sizing.actionIcon),
                )
            }
        },
        success = {
            // Coil 3: read painter intrinsic size inside success slot
            val size = painter.intrinsicSize
            if (!forceSquare && size.width > 0f && size.height > 0f) {
                val trueAspect = feedImageAspectRatio(size.width / size.height, false)
                LaunchedEffect(trueAspect) { displayAspect = trueAspect }
            }
            Image(
                painter            = painter,
                contentDescription = null,
                contentScale       = if (forceSquare) ContentScale.Crop else ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        },
        modifier = imageModifier,
    )
}
```

Add imports at the top of `NoteCard.kt` (if not already present):
```kotlin
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Size
```
Note: `Image` may already be imported. `SubcomposeAsyncImageScope` provides `painter` implicitly.

- [ ] **Step 3: Add import for `feedImageAspectRatio`**

In `NoteCard.kt`, no new import needed — `ImageSizing.kt` is in the same package (`com.unsilence.app.ui.feed`).

Verify the `mutableStateOf` import is present (it should be already from existing `remember` usage).

- [ ] **Step 4: Build and verify**

Build via Android Studio. The image/GIF containers should now always have an aspect ratio constraint. Images without imeta get 4:3 default, then snap to actual ratio when Coil loads.

- [ ] **Step 5: Commit**

```
feat: image/GIF aspect ratio system mirroring video sizing

Images now always have an aspectRatio() constraint — 4:3 default
when imeta dims are missing, updated to true ratio on Coil load.
Eliminates small/padded rendering for GIFs and images without imeta.
```

---

### Task 2: Fetch Coalescing (Relay Storm Fix)

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt:71,716-728,906-976`
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/CardHydrator.kt:40-107`
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/FeedViewModel.kt:98-109,185`

- [ ] **Step 1: Add global in-flight tracking maps to `RelayPool`**

After line 71 (`private val profileFetchAttempted = ...`), add:

```kotlin
/** Global engagement dedup — event IDs already fetched (60s TTL). */
private val engagementFetched = ConcurrentHashMap<String, Long>()

/** Global event-by-ID dedup — prevents duplicate fetchEventById calls (30s TTL). */
private val eventFetchInFlight = ConcurrentHashMap<String, Long>()

// Evict stale entries every 5 minutes to prevent unbounded growth.
init {
    scope.launch {
        while (true) {
            delay(300_000)
            val cutoff = System.currentTimeMillis() - 300_000
            engagementFetched.entries.removeIf { it.value < cutoff }
            eventFetchInFlight.entries.removeIf { it.value < cutoff }
            profileFetchAttempted.entries.removeIf { it.value < cutoff }
        }
    }
}
```

- [ ] **Step 2: Add dedup to `fetchEngagementBatch()`**

At the top of `fetchEngagementBatch()` (line 907), after the `if (eventIds.isEmpty()) return` check, add:

```kotlin
val now = System.currentTimeMillis()
val novel = eventIds.filter { id ->
    val last = engagementFetched[id]
    last == null || (now - last) > 60_000
}
if (novel.isEmpty()) {
    Log.d(TAG, "fetchEngagementBatch: all ${eventIds.size} IDs already in-flight, skipping")
    return
}
novel.forEach { engagementFetched[it] = now }
```

Then replace all subsequent references to `eventIds` in the method body with `novel`. Specifically:
- Line ~920: `novel.forEach { add(JsonPrimitive(it)) }` (replies #e filter)
- Line ~931: `novel.forEach { add(JsonPrimitive(it)) }` (reactions #e filter)
- Line ~942: `novel.forEach { add(JsonPrimitive(it)) }` (zaps #e filter)
- Line ~956: `val scopeKeyHash = novel.sorted().joinToString(",")`
- Line ~975: log line: `"Fetching engagement for ${novel.size} events ..."`

- [ ] **Step 3: Replace `fetchEventById` with batched `fetchEventsByIds`**

Replace the existing `fetchEventById` method (lines 715-728) with a batched version:

```kotlin
/** Batch fetch events by ID. Deduped against in-flight tracker (30s TTL). */
fun fetchEventsByIds(eventIds: List<String>) {
    if (eventIds.isEmpty()) return
    val now = System.currentTimeMillis()
    val novel = eventIds.filter { id ->
        val last = eventFetchInFlight[id]
        last == null || (now - last) > 30_000
    }
    if (novel.isEmpty()) return
    novel.forEach { eventFetchInFlight[it] = now }
    val subId = "batch-events-${System.nanoTime()}"
    val req = buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(subId))
        add(buildJsonObject {
            put("ids", buildJsonArray { novel.forEach { add(JsonPrimitive(it)) } })
            put("limit", JsonPrimitive(novel.size))
        })
    }.toString()
    connections.values.take(3).forEach { it.send(req) }
    Log.d(TAG, "fetchEventsByIds: ${novel.size} events → ${minOf(connections.size, 3)} relay(s)")
}

/** Keep single-ID overload for backward compatibility, delegates to batch. */
fun fetchEventById(eventId: String) = fetchEventsByIds(listOf(eventId))
```

- [ ] **Step 4: Simplify CardHydrator — merge profile calls, batch ref fetches**

Replace the `hydrateVisibleCards` method body in `CardHydrator.kt` (lines 40-104):

```kotlin
suspend fun hydrateVisibleCards(events: List<FeedRow>) {
    val newEvents = events.filter { it.id !in hydratedIds }
    if (newEvents.isEmpty()) return
    hydratedIds.addAll(newEvents.map { it.id })

    // 1. Collect all pubkeys and referenced event IDs
    val pubkeys = mutableSetOf<String>()
    val referencedIds = mutableSetOf<String>()

    for (event in newEvents) {
        pubkeys.add(event.pubkey)
        if (event.kind == 6) {
            extractRepostAuthorPubkey(event.content, event.tags)?.let { pubkeys.add(it) }
            extractRepostTargetId(event.tags)?.let { referencedIds.add(it) }
        }
        extractQuotedEventIds(event.content).forEach { referencedIds.add(it) }
    }

    // 2. Fetch missing referenced events (batched, deduped in RelayPool)
    val missingRefs = referencedIds.filter { eventDao.getEventById(it) == null }
    if (missingRefs.isNotEmpty()) {
        relayPool.fetchEventsByIds(missingRefs.toList())
    }

    // 3. After ref events arrive, collect their authors too
    if (missingRefs.isNotEmpty()) {
        delay(1500)
        missingRefs.mapNotNull { eventDao.getEventById(it)?.pubkey }
            .forEach { pubkeys.add(it) }
    }

    // 4. Single profile fetch for ALL pubkeys (authors + repost authors + ref authors)
    if (pubkeys.isNotEmpty()) {
        userRepository.fetchMissingProfiles(pubkeys.toList())
    }

    Log.d(TAG, "Hydrated ${newEvents.size} cards: ${pubkeys.size} profiles, ${referencedIds.size} refs (${missingRefs.size} missing)")
}
```

Key changes vs old code:
- Removed duplicate `fetchMissingProfiles()` call (was at lines 75 AND 99)
- Single profile fetch AFTER ref events arrive (so ref authors are included)
- Batch `fetchEventsByIds()` instead of per-ID loop
- Removed verbose per-ref debug logging (keep summary only)

- [ ] **Step 5: Remove `engagementFetchedIds` from `FeedViewModel`**

In `FeedViewModel.kt`:
- Delete line 98: `private val engagementFetchedIds = mutableSetOf<String>()`
- Replace `fetchEngagementForVisible` method (lines 104-109) with:

```kotlin
fun fetchEngagementForVisible(visibleIds: Set<String>) {
    relayPool.fetchEngagementBatch(visibleIds.toList().take(20))
}
```

- Delete line 185: `engagementFetchedIds.clear()` (in the `flatMapLatest` block)

The dedup now lives in `RelayPool.engagementFetched` (global, survives ViewModel recreation).

- [ ] **Step 6: Build and verify**

Build. Verify no compile errors. The relay storm should be significantly reduced — engagement dedup at RelayPool level, batched event fetches, single profile fetch path.

- [ ] **Step 7: Commit**

```
fix: global fetch coalescing — dedupe engagement, batch refs, merge profiles

- engagementFetched in RelayPool with 60s TTL replaces per-VM set
- fetchEventsByIds batches multiple event lookups into single REQ
- CardHydrator: single fetchMissingProfiles call instead of two
- Reduced relay fan-out from 6 to 3 for event-by-ID fetches
```

---

### Task 3: Wire Filters to Following Feed

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/dao/EventDao.kt:105-140`
- Modify: `app/src/main/kotlin/com/unsilence/app/data/repository/EventRepository.kt:41-42`
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/FeedViewModel.kt:211-216`

- [ ] **Step 1: Add filter params to `EventDao.followingFeedFlow()`**

Replace the `followingFeedFlow` method (lines 108-140) with a filtered version. Mirror the SQL logic from `feedFlow()`:

```kotlin
@Query("""
    SELECT
        e.id,
        e.pubkey,
        e.kind,
        e.content,
        e.created_at,
        e.tags,
        e.relay_url,
        e.reply_to_id,
        e.root_id,
        e.has_content_warning,
        e.content_warning_reason,
        e.cached_at,
        COALESCE(s.zap_total_sats, 0) AS zap_total_sats,
        u.name            AS author_name,
        u.display_name    AS author_display_name,
        u.picture         AS author_picture,
        u.nip05           AS author_nip05,
        COALESCE(s.reaction_count, 0) AS reaction_count,
        COALESCE(s.reply_count, 0)    AS reply_count,
        COALESCE(s.repost_count, 0)   AS repost_count,
        COALESCE(s.zap_count, 0)      AS zap_count
    FROM events e
    INNER JOIN follows     f ON f.pubkey   = e.pubkey
    LEFT JOIN  users       u ON u.pubkey   = e.pubkey
    LEFT JOIN  event_stats s ON s.event_id = e.id
    WHERE e.kind IN (:kinds)
      AND ((e.reply_to_id IS NULL AND e.root_id IS NULL) OR e.kind = 6)
      AND (:sinceTimestamp = 0 OR e.created_at > :sinceTimestamp)
      AND ((:requireReposts = 0 AND :requireReactions = 0 AND :requireReplies = 0 AND :requireZaps = 0)
           OR (:requireReposts   = 1 AND COALESCE(s.repost_count, 0)   >= 1)
           OR (:requireReactions = 1 AND COALESCE(s.reaction_count, 0) >= 1)
           OR (:requireReplies   = 1 AND COALESCE(s.reply_count, 0)    >= 1)
           OR (:requireZaps      = 1 AND COALESCE(s.zap_count, 0)      >= 1))
    ORDER BY e.created_at DESC
    LIMIT :limit
""")
fun followingFeedFlow(
    kinds: List<Int>,
    sinceTimestamp: Long,
    requireReposts: Int,
    requireReactions: Int,
    requireReplies: Int,
    requireZaps: Int,
    limit: Int = 300,
): Flow<List<FeedRow>>
```

- [ ] **Step 2: Update `EventRepository.followingFeedFlow()`**

Replace line 41-42 in `EventRepository.kt`:

```kotlin
fun followingFeedFlow(filter: FeedFilter, limit: Int = 300): Flow<List<FeedRow>> {
    val sinceTimestamp = filter.sinceHours?.let {
        (System.currentTimeMillis() / 1000) - (it * 3600L)
    } ?: 0L
    return eventDao.followingFeedFlow(
        kinds            = filter.enabledKinds,
        sinceTimestamp   = sinceTimestamp,
        requireReposts   = if (filter.requireReposts)   1 else 0,
        requireReactions = if (filter.requireReactions) 1 else 0,
        requireReplies   = if (filter.requireReplies)   1 else 0,
        requireZaps      = if (filter.requireZaps)      1 else 0,
        limit            = limit,
    )
}
```

- [ ] **Step 3: Pass filter in FeedViewModel**

In `FeedViewModel.kt`, replace lines 211-216 (the `FeedType.Following` branch):

```kotlin
is FeedType.Following -> {
    currentRelayUrls = emptyList()
    outboxRouter.start()
    _displayLimit.flatMapLatest { limit ->
        eventRepository.followingFeedFlow(filter, limit)
    }
}
```

- [ ] **Step 4: Build and verify**

Build. The Following feed should now respect time range, engagement, and kind filters.

- [ ] **Step 5: Commit**

```
fix: Following feed now respects all filter settings

Wire FeedFilter through to followingFeedFlow — kinds, time range,
and engagement requirements now apply to the Following feed the
same way they do for Global and RelaySet feeds.
```

---

### Task 4: Add Kind Toggle Chips to FilterScreen

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/FilterScreen.kt`

- [ ] **Step 1: Add kind toggle state vars**

After line 63 (`var requireZaps ...`), add:

```kotlin
var showKind1     by remember { mutableStateOf(currentFilter.showKind1) }
var showKind6     by remember { mutableStateOf(currentFilter.showKind6) }
var showKind20    by remember { mutableStateOf(currentFilter.showKind20) }
var showKind21    by remember { mutableStateOf(currentFilter.showKind21) }
var showKind30023 by remember { mutableStateOf(currentFilter.showKind30023) }
```

- [ ] **Step 2: Add kind toggle section to the UI**

After the engagement `FlowRow` section (after line 157), before the Apply button section, add:

```kotlin
HorizontalDivider(color = Color(0xFF222222), thickness = 0.5.dp)

// ── Content types ───────────────────────────────────────────
Text(
    text     = "Content types",
    color    = TextSecondary,
    fontSize = 12.sp,
    modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
)
FlowRow(
    modifier              = Modifier
        .fillMaxWidth()
        .padding(horizontal = Spacing.medium)
        .padding(bottom = Spacing.medium),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement   = Arrangement.spacedBy(8.dp),
) {
    FilterChip("Notes",    showKind1)     { showKind1     = !showKind1 }
    FilterChip("Reposts",  showKind6)     { showKind6     = !showKind6 }
    FilterChip("Pictures", showKind20)    { showKind20    = !showKind20 }
    FilterChip("Videos",   showKind21)    { showKind21    = !showKind21 }
    FilterChip("Articles", showKind30023) { showKind30023 = !showKind30023 }
}
```

- [ ] **Step 3: Include kind toggles in `FeedFilter` construction**

Replace the `onApply` block (lines 168-176) to pass kind toggles:

```kotlin
onApply(
    FeedFilter(
        showKind1        = showKind1,
        showKind6        = showKind6,
        showKind20       = showKind20,
        showKind21       = showKind21,
        showKind30023    = showKind30023,
        sinceHours       = sinceHours,
        requireReposts   = requireReposts,
        requireReactions = requireReactions,
        requireReplies   = requireReplies,
        requireZaps      = requireZaps,
    )
)
```

- [ ] **Step 4: Update Reset to include kind toggles**

In the Reset button `onClick` (lines 98-103), add:

```kotlin
showKind1     = true
showKind6     = true
showKind20    = true
showKind21    = true
showKind30023 = true
```

- [ ] **Step 5: Build and verify**

Build. The FilterScreen should now show Content types chips. Toggling them off should hide those kinds from all feed types.

- [ ] **Step 6: Commit**

```
feat: add content type toggles to filter screen

Users can now show/hide Notes, Reposts, Pictures, Videos, and
Articles from any feed type. Kind toggles are included in the
FeedFilter passed to all feed queries.
```

---

### Task 5: User Avatar in Navigation Icons

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/navigation/AppNavigation.kt:83-88,92-115,265-272,290-314`

- [ ] **Step 1: Add `KeyManager` to `FeedViewModel` and expose `userAvatarUrl`**

The actual `FeedViewModel` constructor (lines 52-61) is:
```kotlin
class FeedViewModel @Inject constructor(
    private val relaySetRepository: RelaySetRepository,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,  // already present
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val followDao: FollowDao,
    private val coverageRepository: CoverageRepository,
    private val cardHydrator: CardHydrator,
) : ViewModel() {
```

`UserRepository` is already injected. **`KeyManager` is NOT** — add it:

```kotlin
class FeedViewModel @Inject constructor(
    private val relaySetRepository: RelaySetRepository,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val followDao: FollowDao,
    private val coverageRepository: CoverageRepository,
    private val cardHydrator: CardHydrator,
    private val keyManager: KeyManager,
) : ViewModel() {
```

Add import: `import com.unsilence.app.data.auth.KeyManager`

Then add the `userAvatarUrl` StateFlow after the class properties (around line 70):

```kotlin
/** Signed-in user's avatar URL, for nav icons. */
val userAvatarUrl: StateFlow<String?> = keyManager.getPublicKeyHex()?.let { pubkey ->
    userRepository.userFlow(pubkey)
        .map { it?.picture }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
} ?: MutableStateFlow(null)
```

Add import: `import kotlinx.coroutines.flow.map` (if not already present — check; `SharingStarted` and `stateIn` are already imported).

- [ ] **Step 3: Collect userAvatarUrl in AppNavigation**

In `AppNavigation.kt`, after line 115:

```kotlin
val userAvatarUrl by feedViewModel.userAvatarUrl.collectAsStateWithLifecycle()
```

- [ ] **Step 4: Replace Home tab icon with user avatar**

Replace the Home tab rendering in the bottom nav (lines 290-314). The current code iterates `TABS.forEachIndexed` and renders `Icon(tab.icon, ...)`. For index 0, render avatar instead:

In the `TABS.forEachIndexed` block, replace the `Icon(...)` call with:

```kotlin
if (index == 0 && userAvatarUrl != null) {
    // User avatar as Home tab icon — Cyan border ring when selected
    Box(
        modifier = Modifier
            .size(Sizing.navIcon)
            .then(
                if (index == selectedTab)
                    Modifier.border(1.5.dp, Cyan, CircleShape)
                else Modifier
            )
            .clip(CircleShape),
    ) {
        AsyncImage(
            model = userAvatarUrl,
            contentDescription = "Home",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
} else {
    Icon(
        imageVector        = tab.icon,
        contentDescription = tab.contentDescription,
        tint               = if (index == selectedTab) Cyan else NavUnselected,
        modifier           = Modifier.size(Sizing.navIcon),
    )
}
```

Add imports (Coil 3 paths):
```kotlin
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
```
Note: `CircleShape`, `clip`, `Modifier.size` should already be imported in AppNavigation.kt.

- [ ] **Step 5: Replace Compose button icon with user avatar**

Replace the compose icon (lines 265-272):

```kotlin
if (userAvatarUrl != null) {
    Box(
        modifier = Modifier
            .size(Sizing.navIcon)
            .border(1.5.dp, Cyan, CircleShape)
            .clip(CircleShape)
            .clickable { showCompose = true },
    ) {
        AsyncImage(
            model = userAvatarUrl,
            contentDescription = "New post",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
} else {
    Icon(
        imageVector        = Icons.Filled.Edit,
        contentDescription = "New post",
        tint               = Cyan,
        modifier           = Modifier
            .size(Sizing.navIcon)
            .clickable { showCompose = true },
    )
}
```

- [ ] **Step 6: Build and verify**

Build. When a user is signed in and their profile has a picture URL, the Home tab and Compose button should show a small circular avatar. When no profile picture exists, the icons fall back to Material defaults.

- [ ] **Step 7: Commit**

```
feat: show signed-in user avatar in Home tab and Compose button

Replaces generic Material icons with the user's profile picture
when available. Falls back to default icons when no avatar exists.
```

---

### Task 6: Fix Notification Avatars

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/notifications/NotificationsViewModel.kt`

- [ ] **Step 1: Add profile fetch for notification actors**

The issue: `NotificationsViewModel` fetches notification events but never fetches profiles for the actors. The DAO JOIN with `users` is correct, but user rows don't exist.

Add `UserRepository` to the constructor and trigger profile fetches. Replace the entire `NotificationsViewModel`:

```kotlin
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val notificationsDao: NotificationsDao,
    private val relayPool: RelayPool,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    init {
        keyManager.getPublicKeyHex()?.let { pubkey ->
            relayPool.fetchNotifications(pubkey)

            viewModelScope.launch {
                notificationsDao.notificationsFlow(pubkey).collect { rows ->
                    _uiState.update { it.copy(items = rows, loading = false) }

                    // Fetch profiles for actors whose picture is still null.
                    // UserRepository.fetchMissingProfiles already dedupes via
                    // cached pubkeys + stale threshold, so no local set needed.
                    val missingPubkeys = rows
                        .filter { it.actorPicture == null }
                        .map { it.actorPubkey }
                        .distinct()
                    if (missingPubkeys.isNotEmpty()) {
                        userRepository.fetchMissingProfiles(missingPubkeys)
                    }
                }
            }
        }
    }
}
```

Add import: `com.unsilence.app.data.repository.UserRepository`

- [ ] **Step 2: Build and verify**

Build. When notifications load, actors whose profiles haven't been fetched yet will trigger a profile fetch. Room will re-emit the query with the avatar URL once the profile arrives.

- [ ] **Step 3: Commit**

```
fix: fetch profiles for notification actors — avatars now load

NotificationsViewModel now triggers fetchMissingProfiles for actor
pubkeys where actorPicture is null. Room re-emits the notification
query when profiles arrive, populating avatars automatically.
```

---

### Task 7: Code Cleanup

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/CardHydrator.kt` (already cleaned in Task 2)
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/FeedViewModel.kt` (already cleaned in Task 2)

- [ ] **Step 1: Remove unused imports from all modified files**

After all tasks, scan each modified file for unused imports and remove them. Key files:
- `NoteCard.kt` — check if old `ContentScale` import pattern changed
- `FeedViewModel.kt` — `engagementFetchedIds` removal may leave dead imports
- `CardHydrator.kt` — reduced logging may leave unused `Log` patterns (keep `Log` itself)

- [ ] **Step 2: Final build**

Full build to verify everything compiles cleanly.

- [ ] **Step 3: Commit**

```
chore: remove unused imports and dead code from stabilization changes
```
