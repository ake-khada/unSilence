# Stabilization Sprint: Media Sizing, Fetch Coalescing, Filters, UI Polish

**Date:** 2026-03-18
**Status:** Approved

---

## 1. Image/GIF Aspect Ratio Fix

### Problem
Images and GIFs without imeta dimensions render small and padded. Videos work correctly because they have a 3-layer aspect ratio system: `VideoRenderModel` (imeta) → `VideoThumbnailCache` (bitmap native dims) → `feedVideoAspectRatio()` (16:9 fallback + 9:16 clamp). Images have none of this — when imeta dims are missing, `MediaImage` in `NoteCard.kt:741-747` applies no `.aspectRatio()` modifier, and `ContentScale.Fit` renders the image tiny in an unconstrained container.

### Solution
Mirror the video approach for images:

1. **Add fallback aspect ratio** in `MediaImage` when imeta dims are missing — use `4:3` default (most common photo ratio). Apply `.aspectRatio()` modifier always, never leave container unconstrained.
2. **Runtime dimension update** — after Coil loads the bitmap, read its intrinsic width/height and update the container's aspect ratio via `onSuccess` callback in `SubcomposeAsyncImage`. This matches `VideoThumbnailCache.resolvedAspectRatios` pattern.
3. **Portrait clamping** — clamp portrait images to `9:16` minimum (matches video's `feedVideoAspectRatio` logic).
4. **Extract shared sizing function** `feedImageAspectRatio(rawAspectRatio: Float?, forceSquare: Boolean): Float` in a new `ImageSizing.kt` (parallel to `VideoSizing.kt`).

### Files
- `NoteCard.kt` — `MediaImage` composable
- New `ImageSizing.kt` — `feedImageAspectRatio()` function

---

## 2. Fetch Coalescing (Relay Storm Fix)

### Problem
The app sends duplicate/excessive relay REQs causing "too many concurrent REQs" errors:
- `fetchEngagementBatch()` sends 3 subs × 3 relays = 9 lanes per call
- `engagementFetchedIds` is per-ViewModel and clears on every feed type switch (`FeedViewModel.kt:185`)
- `CardHydrator` calls `fetchMissingProfiles()` twice per batch (lines 75 + 99)
- `fetchEventById()` sends to 6 relays per missing ref with zero dedup or batching

### Solution: Global InFlightTracker in RelayPool

**A. Engagement dedup:**
- Move `engagementFetchedIds` from `FeedViewModel` to `RelayPool` level as `engagementInFlight: ConcurrentHashMap<String, Long>` with 60s TTL
- Before sending engagement REQ for an event ID, check if it's already in-flight
- `fetchEngagementBatch()` filters out already-in-flight IDs before sending

**B. Profile dedup:**
- Remove the second `fetchMissingProfiles()` call in `CardHydrator` (line 99) — instead, collect ref author pubkeys into the same set as the initial pubkeys and fetch once
- The existing 5-min TTL in `profileFetchAttempted` is sufficient; the fix is removing the duplicate call path

**C. Referenced event batching:**
- Replace per-event `fetchEventById()` loop with a batched `fetchEventsByIds(ids: List<String>)` that sends a single REQ with multiple IDs
- Add `eventFetchInFlight: ConcurrentHashMap<String, Long>` with 30s TTL to prevent re-fetching the same event ID
- Cap fan-out to 3 relays (down from 6)

**D. Clean up CardHydrator:**
- Merge steps 2 and 4 (profile fetches) into one call
- Batch referenced event fetches into one REQ
- Remove excessive debug logging (keep summary line only)

### Files
- `RelayPool.kt` — add `engagementInFlight`, `eventFetchInFlight`, new `fetchEventsByIds()`
- `CardHydrator.kt` — merge profile fetch calls, batch ref event fetches
- `FeedViewModel.kt` — remove `engagementFetchedIds` (now in RelayPool)

---

## 3. Filter System Fixes

### Problem
Multiple filter gaps vs CLAUDE.md spec:
1. `followingFeedFlow()` ignores ALL filters (hardcoded kinds, no time/engagement params)
2. `FilterScreen` doesn't expose kind toggles (showKind1/6/20/21/30023)
3. `FilterScreen` constructs `FeedFilter()` without passing current kind toggle values
4. CLAUDE.md specifies `ContentType` (Notes/All/Replies), `hideSensitive`, `minReactions/Zaps/Replies` — none implemented

### Solution

**Phase 1 (this sprint):**
1. Add `filter: FeedFilter` parameter to `EventRepository.followingFeedFlow()` and `EventDao.followingFeedFlow()`
2. Mirror the SQL filter logic from `feedFlow()` into `followingFeedFlow()` (kinds, sinceTimestamp, engagement requirements)
3. In `FeedViewModel`, pass `filter` to `followingFeedFlow()` at line 215
4. Add **kind toggle chips** to `FilterScreen` (Notes, Reposts, Pictures, Videos, Articles)
5. Include kind toggle state in the `FeedFilter` constructed by `onApply`
6. Fix `Reset` button to also reset kind toggles

**Phase 2 (future sprint):**
- ContentType (Notes/All/Replies) segmented control
- `hideSensitive` toggle
- `minReactions/minZapAmount/minReplies` sliders

### Files
- `EventDao.kt` — `followingFeedFlow()` query
- `EventRepository.kt` — `followingFeedFlow()` signature
- `FeedViewModel.kt` — pass filter to following feed
- `FilterScreen.kt` — add kind toggle chips section
- `FeedFilter.kt` — ensure `isNonDefault` covers kind toggles (already does)

---

## 4. User Avatar in Nav & Compose Icons

### Problem
Home tab icon and compose button use generic Material icons. User wants signed-in user's avatar for these.

### Solution
- **Home tab**: Replace `Icons.Filled.Home` with a small circular `AvatarImage` (20dp) of the signed-in user. When no user is signed in, fall back to `Icons.Filled.Home`.
- **Compose button**: Replace `Icons.Filled.Edit` with user avatar (20dp circular). Same fallback.
- **Reply/repost/reaction/zap icons**: Keep as Material icons — these represent actions, not identity.
- Source avatar URL from `KeyManager.getPublicKeyHex()` → `UserRepository` → `UserDao.getUserByPubkey()`.
- Pass `userAvatarUrl: String?` from `AppNavigation` down to the tab/button composables.

### Files
- `AppNavigation.kt` — inject user avatar, replace home tab and compose icons
- `RootViewModel.kt` or `AppNavigation.kt` — collect user profile for avatar URL

---

## 5. Notification Avatar Fix

### Problem
`NotificationsViewModel` calls `relayPool.fetchNotifications()` but never triggers profile fetches for actor pubkeys. The `NotificationsDao` JOIN with `users` table is correct, but user rows don't exist because profiles were never fetched.

### Solution
- After notification events arrive, extract unique actor pubkeys and call `relayPool.fetchProfiles()` for them
- Add a `LaunchedEffect` or `init` block in `NotificationsViewModel` that watches `uiState.items`, extracts pubkeys where `actorPicture == null`, and triggers a profile fetch
- Profile fetch is already deduped by `profileFetchAttempted` 5-min TTL in RelayPool

### Files
- `NotificationsViewModel.kt` — add profile fetch for actor pubkeys

---

## 6. Code Cleanup

During implementation of the above, clean up:
- Excessive debug logging in `CardHydrator` (individual ref ID logs → summary only)
- Dead `engagementFetchedIds` in `FeedViewModel` after moving to RelayPool
- Any unused imports from refactored files
