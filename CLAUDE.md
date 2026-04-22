# unSilence — Claude Code Context

**Last updated:** April 22, 2026 (Deterministic cold-start: ColdStartState enum replaces boolean splash, 10s+5s timeouts match bootstrapper, no Global flash.)
**Package:** com.unsilence.app
**Path:** /home/aivii/projects/unsilence

---

## Validation Protocol

Any runtime behavior change MUST use human-in-the-loop validation. See `VALIDATION_PROTOCOL.md` for full protocol, gesture scripts, and pass criteria. Scripted `adb shell input swipe` is NEVER valid for scroll/gesture testing.

---

## Environment

- **Main user:** `aivii` — Android Studio, git
- **Claude Code user:** `android-dev` — `./gradlew`, code edits
- **ADB:** `/home/aivii/Android/Sdk/platform-tools/adb`
- **Emulator:** Android 16.0 Baklava x86_64 (KVM)
- **Editor:** Neovim — never `nano`
- **Deploy:** `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- **NEVER** run `./gradlew` while Android Studio is open (Gradle lock conflict)

---

## Tech Stack

| Component | Version |
|-----------|---------|
| Kotlin | 2.3.0 |
| Compose | BOM 2025.12.00 (1.10, pausable composition) |
| Hilt (KSP) | 2.58 |
| Media3 | 1.5.1 (+HLS) |
| Coil | 3 (64MB memory cache cap) |
| Nostr | Quartz 1.05.1 |
| AGP 8.9.1 | compileSdk/targetSdk 36, JDK 17 |

---

## Design System

### Colors
- **Background:** AMOLED pure black `Surface0` (#000000)
- **Surface depth:** `Surface1` (#0A0A0A), `Surface2` (#141414), `SurfaceVariant` (#080808)
- **Accent:** `Cyan` (#00E5FF), **Zap:** `ZapAmber` (#FFAB00)
- Disabled alpha: 0.38f. No light theme.

### Spacing (golden ratio: 360dp / phi^n)
`micro=5dp`, `small=8dp`, `medium=12dp`, `large=20dp`, `xl=32dp`, `xxl=52dp`

### Typography (`AppType` in Theme.kt)
`caption=11sp`, `footnote=12sp`, `bodySmall=13sp`, `body=14sp`, `bodyLarge=15sp`, `subheading=16sp`, `heading=18sp`, `title=22sp`, `display=24sp`

### Sizing
`avatar=32dp`, `actionIcon=20dp`, `navIcon=20dp`, `topBarHeight=52dp`, `bottomNavHeight=52dp`, `mediaCornerRadius=8dp`

---

## Architecture

```
Relay WebSocket → EventProcessor → MemoryEventStore → Flow/StateFlow → Compose UI
                                  └→ SnapshotScheduler (disk persistence)
```

**Core principle:** MES-only (in-memory ConcurrentHashMap), 0ms screen render, snapshot persistence to disk. No Room, no SQLite. All data classes in `data/memory/Models.kt`.

### Key Subsystems (read code for details)
- **EventProcessor** — dedup via seenIds, kind handlers, spam filter, relay provenance
- **ProfileResolver** — batched profile fetch, 6h staleness, 15s in-flight guard
- **RelayPool** — WebSocket manager, ConnectionPurpose (PERSISTENT/BROWSE/OUTBOX), per-relay REQ queue (cap 10), token bucket rate limiter, idle eviction, NIP-42 auth (OK-confirmed), ephemeral one-shot path (`sendOneShotBatch` + `openEphemeral`) for cap-bypassing fetches
- **FeedStateReducer** — MERGE at top / QUEUE when scrolled / APPEND pagination, blue dot, `synchronized`-based coalescing (200ms window), `PersistentSet<String>` knownIds
- **FeedHydrationController** — 5-state scroll machine (WARM_CATCHUP/SLOW_SCROLL/IDLE/FAST_SCROLL/REST), CardHydrator as stateless worker, velocity hysteresis, per-item bitmask ledger, sampled at 16 Hz
- **VideoPlaybackScope** — shared ExoPlayer, viewport center activation (60%/35% hysteresis), 3-layer flap protection
- **MemoryEventStore** — ConcurrentHashMap store, signal-driven reactive Flows (`_feedSignal`, `_profileSignal`, `_statsSignal`, `_actionSignal`, `_trustScoreSignal`, `_relayMonitorSignal`). Pattern: `_signal.map { scan() }.distinctUntilChanged().flowOn(Dispatchers.Default)`. Bounded: per-kind content eviction (k1=5000, k6=1000, etc.) with own-pubkey + p-tag-mentioned anchors, feedRowCache LRU cap 500, profilesByPubkey LRU cap 2000 (anchored: own+followed+recent), actor indexes cap 1000 actors/500 targets
- **MesMetricsLogger** — ProcessLifecycleOwner-driven 60s foreground logger (`MES/size` tag). Reports per-collection counts, per-kind breakdown, actor indexes, external cache sizes, eviction anchor counts, relay dedup metrics. `MesMetrics.kt` data class + `MES.snapshotSize()`
- **ImageDimensionCache** — singleton ConcurrentHashMap of image aspect ratios (url → width/height), clamped to 0.2..5.0
- **VideoThumbnailCache** — first-frame thumbnails via MMR, downsampled (inSampleSize=2, ~1MB/thumb), LRU eviction at 100 entries OR 64MB bitmap total, `visibleUrls` set protects on-screen thumbnails from eviction
- **SearchViewModel** — NIP-50 search, `AtomicLong` token tracking, debounce + collectLatest, CLOSE frames on supersede
- **RelayPreferencesStore** — DataStore-backed, `Mutex`-guarded read-modify-write for indexer URLs
- **SnapshotScheduler** — periodic + onStop save (3s timeout), AtomicFile for crash safety

---

## Features — Shipped

Feed (Following/Global/Popular + relay-specific, Notes/Conversations tabs, filter sheet, infinite scroll, blue dot, deterministic cold-start) · Content (kind 1/6/30023, @mentions, quotes, OG previews, YouTube, media grids) · Video (inline autoplay, shared ExoPlayer, fullscreen, HLS, mute) · Profiles (avatar/banner/bio, edit, tabs, follow/unfollow, NIP-45 followers, NIP-65 outbox) · Engagement (reactions, reposts, zaps NWC NIP-47, action bar) · Relay (NIP-51 ecosystem, relay sets, relay health, blocked relays) · Navigation (bottom nav, thread view with tree nesting, NIP-50 search, notifications with blue dot) · Auth (nsec + Amber NIP-55, logout with session key rotation) · Branding (waveform LogoMark, adaptive icon, deterministic cold-start splash)

---

## TODO — Remaining Features

### High Priority — UX
1. Engagement drawer — tap count → bottom sheet with user list
2. Settings — Keys (nsec export), Safety (mute/block), Wallet (NWC management), Cache clear
3. Image pinch-zoom in fullscreen dialog

### High Priority — Privacy
4. NIP-36 content-warning blur overlay
5. NIP-51 mute lists (kind 10000), keyword filters
6. TOR (Phase 1: Orbot SOCKS5, Phase 2: embedded tor-android-binary)

### Core Protocol
7. WoT NIP-85 via antiprimal.net
8. NIP-96 file upload for compose
9. Language filter (UnifiedFilter + ML Kit)
10. Expanded reactions (emoji picker)

### Distribution
11. F-Droid + Zapstore + GitHub releases pipelines

---

## Critical Rules

### Video (guardrails)
1. **NEVER touch video** (InlineAutoPlayVideo.kt, VideoPlaybackScope.kt, NoteCard media section) without permission
2. **If video heat returns, check detector rate FIRST** — codec realloc per distinct URL is normal; per second is a flap bug
3. **SurfaceView ignores parent View alpha** — use conditional rendering, not `Modifier.alpha(0f)`

### Correctness
4. **Verify bugs on device** before fixing — stale bug lists caused regressions
5. **Diagnose before prescribing** — read actual code first
6. **Never carry stale bugs forward** — verify each bug exists on current HEAD
7. **Prefer caller-side guards** over time-based debounce (distinctUntilChanged, empty-set returns, state guards)
8. **key(feedKey) on LazyListState kills video autoplay** — never do this
9. **Two pointerInput modifiers conflict** — use single awaitEachGesture block
10. **Logout: isLoggedIn=false BEFORE teardown** — triggers recomposition that destroys VMs. Singletons survive via `key(sessionKey)`

### Concurrency & Threading
11. **Every MES scan Flow MUST use `flowOn(Dispatchers.Default)`** — without it, ConcurrentHashMap iteration runs on Main → ANR
12. **`viewModelScope.launch {}` inside `collectLatest` does NOT cancel** — use `withContext(IO)` instead + ID-set guard
13. **NostrEvent.relaysSeen MUST be `ConcurrentHashMap.newKeySet()`** — iterated on Default, mutated on IO
14. **produceState dispatches relay work via `withContext(IO)`** — relay methods must not block Main
15. **FeedStateReducer coalescing uses `synchronized(this)`** — no `@Volatile`, no direct `_state.value` writes except in `flush()`. Route through `emitCoalesced` or `emitCoalescedDataRefresh`
16. **SearchViewModel token is `AtomicLong`** — never regress to nullable Long, always use atomic ops
17. **RelayPreferencesStore read-modify-write uses `Mutex`** — prevents lost updates on concurrent indexer URL changes
18. **SnapshotScheduler onStop has 3s `withTimeoutOrNull`** — prevents indefinite mutex block if periodic save is in progress

### Relay & Protocol
19. **NIP-42 auth waits for OK** — `pendingAuthEventIds` tracks sent auth events. `handleOk()` confirms, `completeAuth()` marks authenticated + replays subs. 10s fallback for non-compliant relays
20. **Search subscriptions MUST send CLOSE frames when superseded** — `relayPool.closeSearch(priorToken)` before new REQs. 10s timeout safety net for missing EOSE
21. **ProfileResolver call sites MUST pre-filter** — use `userRepository.fetchMissingProfiles` or `profileResolver.filterUnresolved()`. Grep for `Batch.*all fresh, skipping` to verify
22. **fetchRelayEcosystem sends to write relays, not just indexers** — NIP-51 replaceable events live on write relays
23. **NIP-51/NIP-65 relay events need direct-path insert** — kinds 10002/10006/10007/10012/30002 not in shouldChannel
24. **One-shot fetches use `sendOneShotBatch`, NOT `connect()`** — pool-reuse for URLs already in `connections`, ephemeral WebSocket for others. Never `connect()` for one-shot REQs (it counts against the 13-connection cap). Migrated: fetchUserPosts, fetchOlderPosts, fetchProfiles, fetchProfilesFromHints, fetchProfilesFromSourceRelays
25. **Ephemeral connections never enter `connections` map** — no cap, no reconnect, no idle eviction. Lifecycle: connect → REQ → EVENT* → EOSE → CLOSE → close WebSocket. Per-URL 50ms CAS-guarded rate limit via `ephemeralLastOpenNanos`

### Feed & Hydration
26. **Render-then-hydrate architecture** — MES signals drive the reducer immediately; WARM zone catches up after. Don't gate on hydration completion at reducer level
27. **FeedStateReducer state: immutable only** — `PersistentSet<String>` for knownIds, O(delta) updates. No mutable collections in ReducerState
28. **Pagination cursor is reducer-owned** — `oldestCreatedAt` in `ReducerState`. No `minOfOrNull` outside the reducer
29. **Engagement freshness uses tiered thresholds** — route through `freshnessThreshold()`. Don't use raw `ENGAGEMENT_STALE_MS` as sole threshold
30. **State machines, not booleans** — multi-state properties must be enums. Booleans collapse intermediate states
31. **Load-aware, not time-based** — triggers use compound readiness conditions, not naive `delay()`. Integrate with HydrationCtrl state machine

### Media
32. **Media aspect ratios are layout-locked after first compose** — three-tier resolution (imeta → MMR → ImageDimensionCache). ONE update from default→resolved permitted, then locked
33. **ImageDimensionCache clamps ratios to 0.2f..5.0f** — malformed dimensions from servers must not corrupt layout
34. **Zero-height guard on imeta dims** — `&& it.height != 0` at all parse/resolve sites (EventProcessor, MES snapshot restore, NoteCard, VideoRenderModel)
35. **MES sidecar caches** — `videoRenderModelsByEventId` and `imetaImageDimsByEventId` cleared in `MES.clear()`, populated in `insertFromSnapshot()`
36. **WARM_CATCHUP runs media hydration (MMR cap 3)** — first-visible items get pre-resolved before user scrolls
37. **"Failed to call close" from MMR is framework-owned** — ~24/session, no functional effect. Re-audit if >50/session

### Shared Utilities
38. **`toEventJson(event)` lives in `NostrJson.kt`** — single shared function. Never duplicate in ViewModels
39. **`ANTIPRIMAL_RELAY_URL` in `RelayUrlUtil.kt`** — use the constant, don't hardcode `"wss://antiprimal.net"`
40. **`normalizeRelayUrl()` in `RelayUrlUtil.kt`** — top-level function, no companion object wrappers

### Thread Safety
41. **Thread DFS walk uses `visited` set** — cycle protection prevents stack overflow on circular reply chains

### Memory Bounds
42. **VideoThumbnailCache bitmaps MUST be downsampled** — `inSampleSize=2` in `downsample()`. Removing this regresses to ~3.5MB/thumb → 64MB cap hit in 2 minutes
43. **VideoThumbnailCache `visibleUrls` set** — composables register via `markVisible`/`markNotVisible` in `DisposableEffect`. Eviction skips visible URLs. Missing unregister = memory leak
44. **feedRowCache + feedRowAccessedAt paired** — every `feedRowCache.remove()` site MUST also remove from `feedRowAccessedAt`. Check: `evictOldContentEvents`, `invalidateFeedRowCache`, `trimFeedRowCacheIfNeeded`, `clear`
45. **Actor index caps** — outer map 1000 actors LRU, inner 500 targets/actor. `ownPubkey` anchored (never evicted). All three indexes (reacted/reposted/zapped) share `actorAccessedAt` and trim together
46. **Profile eviction anchors** — own pubkey + followed + top 500 recent event authors. Cascades to `profileUpdatedAt`, `profileFieldsCache`, `relayListsByPubkey`. Missing cascade = orphaned entries
47. **MES.ownPubkey** — set by AppBootstrapper at bootstrap start. Used by actor index, profile, and content eviction anchors. Must be set before events arrive
48. **Content eviction anchors** — `evictOldContentEvents()` skips events where `pubkey == ownPubkey` OR any p-tag points to ownPubkey. Mirrors profile/actor anchor patterns. Without this, own notes and notifications vanish after relay backfill triggers eviction
49. **lookupEvent `fetchingQuoteIds` is transient** — guards concurrent lookups only, cleared after completion. Permanent guards cause evicted quoted events to never re-fetch
50. **Notification `lastSeen` must be re-read per emission** — stale capture at collect start causes blue dot to reappear on tab switch when MES re-emits
51. **ColdStartState, not boolean splash** — `_coldStartState` replaces `_splashDone`. VM waits 10s for kind-3 + 5s for kind-10002 (matching bootstrapper budgets). Never regress to a boolean or shorter timeout — that reintroduces the Global→Following flash. `splashDone` is a derived backward-compat alias only

---

## Performance Audit Methodology

Always measure before proposing fixes. Grab a real session logcat, filter with `grep ' PID  PID '`, count by tag with `awk`/`sort`/`uniq -c`. Code reading does not measure runtime cost.

**Rate-limit exhaustion:** `grep "token exhausted" | grep -oE "wss://[^ ]+" | sort | uniq -c | sort -rn`
**Subscription leaks:** grep for `Search EVENT received` after last `closeSearch` timestamp
**Controller rate:** `grep -c HydrationCtrl` per minute — above 50 means throttle is broken
**Validation discipline:** failed criteria = revert first, investigate second. "Pre-existing" requires evidence from a comparable baseline
**MES memory:** `adb logcat -s "MES/size"` — 4-line emission every 60s while foreground. Tracks events (per-kind), profiles, actor indexes, feedRowCache, VideoThumbnailCache bitmap bytes, ImageDimensionCache entries. Trim events logged under `MES` tag

---

## Key Patterns

**MES feed query:** `feedFlow()` scans `idsByKind` + `eventEntitiesByNoteId`, filters by FeedFilter, joins profiles + stats → FeedRow list

**Cold-start / splash:** `ColdStartState` enum (`LOADING`, `READY_FOLLOWING`, `READY_GLOBAL`) in FeedViewModel. Waits up to 10s for kind-3 (follows), then 5s for kind-10002 (relay lists). Warm resume resolves instantly from snapshot (<300ms). No Global→Following flash. `splashDone` backward-compat alias gates AppNavigation bars

**Logout:** `isLoggedIn=false` → `bootstrapper.teardown()` → `key(sessionKey)` forces fresh VM creation

**Relay config:** 5 indexers (DataStore), 5 search (MES), 6 global defaults (RelayUrlUtil), cap 13+3 browse

**Notifications:** MES scan-based `getNotifications()` driven by `combine(_feedSignal, _statsSignal)`. Blue dot via DataStore per-user key

---

## Source Structure

```
app/src/main/kotlin/com/unsilence/app/
├── data/
│   ├── auth/        KeyManager (cached pubkey), SigningManager
│   ├── cache/       CoverageTracker, SyncTracker
│   ├── memory/      MemoryEventStore, Models, SnapshotScheduler, MesMetrics, MesMetricsLogger
│   ├── relay/       RelayPool, EventProcessor, ProfileResolver, CardHydrator,
│   │                OgFetcher, RelayPreferencesStore, NostrJson (toEventJson),
│   │                RelayUrlUtil (ANTIPRIMAL_RELAY_URL, GLOBAL_RELAY_URLS, normalizeRelayUrl)
│   ├── repository/  UserRepository, ZapRepository
│   ├── wallet/      NwcManager, LnurlResolver
│   └── AppBootstrapper.kt
├── di/              Hilt modules
├── ui/
│   ├── common/      LogoMark, LoadingScreen, EmptyState, ShimmerNoteCard, IdentIcon
│   ├── feed/        FeedScreen, FeedViewModel, FeedStateReducer, FeedHydrationController,
│   │                NoteCard, ArticleCard, ImageDimensionCache, VideoThumbnailCache
│   ├── navigation/  AppNavigation
│   ├── compose/     ComposeScreen, ComposeViewModel
│   ├── notifications/ NotificationsScreen, NotificationsViewModel
│   ├── profile/     ProfileScreen, UserProfileScreen, EditProfileScreen
│   ├── search/      SearchScreen, SearchViewModel
│   ├── relays/      RelayManagementScreen, CreateRelaySetScreen
│   ├── shared/      EventFeedItems, ThreadParentCard, VideoPlaybackScope
│   ├── thread/      ThreadScreen, ThreadViewModel (cycle-protected DFS)
│   └── theme/       Color.kt, Theme.kt (Spacing, Sizing, AppType)
└── util/
```
