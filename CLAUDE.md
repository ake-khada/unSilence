# unSilence — Claude Code Context

**Last updated:** April 26, 2026 (Zone-aware hydration replacing eager full-300 hydration.)
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
Relay WebSocket → EventProcessor → MemoryEventStore → FeedListener → FeedWindow → Compose UI
                                  └→ SnapshotScheduler (disk persistence)
```

**Core principle:** MES-only (in-memory ConcurrentHashMap), 0ms screen render, snapshot persistence to disk. No Room, no SQLite. All data classes in `data/memory/Models.kt`.
**Feed architecture:** Batch-first — feed is a frozen list of 300 events loaded by FeedWindowLoader, NOT a reactive stream. Live-tail events arrive via MES FeedListener callbacks and are merged into the window by sorted insertion. No `feedFlow.collect`, no continuous re-scan.

### Key Subsystems (read code for details)
- **ContentParser** — single-pass tokenizer producing `EventModel` from raw event fields. Called at insert time (EventProcessor.flushBatch) and snapshot restore (MES.insertFromSnapshot). Pure function, O(n) in content length. Cached in `MES.eventModelsByEventId` sidecar
- **EventProcessor** — dedup via seenIds, kind handlers, spam filter, relay provenance, ContentParser.parse hook in flushBatch
- **ProfileResolver** — batched profile fetch, 6h staleness, 15s in-flight guard
- **RelayPool** — WebSocket manager, ConnectionPurpose (PERSISTENT/BROWSE/OUTBOX), per-relay REQ queue (cap 10), token bucket rate limiter, idle eviction, NIP-42 auth (OK-confirmed), ephemeral one-shot path (`sendOneShotBatch` + `openEphemeral`) for cap-bypassing fetches
- **FeedWindow** — per-feed-key window primitive. `Channel<WindowEvent>(UNLIMITED)` drain loop serialized via `Dispatchers.Default.limitedParallelism(1)`. WindowEvents: ContentInsert (sorted by createdAt), EngagementUpdate, ProfileUpdate, FlushPending, IsAtTop/NotAtTop, ViewportChanged. Pending buffer when scrolled down; auto-flush when at top. Zone-aware hydration: ViewportChanged → cancel-and-reschedule 300ms debounce → `runHydrationPass()` hydrates warm zone (10 above + 30 below viewport). Per-event `HydrationStatus` tracks idempotent fetches (profiles, refs, OG, video first-frame, engagement). OG and video IO dispatched on `Dispatchers.IO` to avoid blocking the state-mutation scope. `WindowSnapshot` (@Immutable data class) exposed via StateFlow. RENDERED_CAP=1500, WINDOW_BATCH=300
- **FeedWindowLoader** — bounded-window feed loader: `loadBatchFor(key, cursor, limit)` queries MES, applies post-query filters, runs imeta-only media pass (~5ms for 300 events). No eager profile/ref/OG hydration — all moved to FeedWindow's zone-aware hydration. `refreshEngagementForIds(ids)` replaces the deleted 120s engagement tick. Home branch polls MES with 5s timeout when empty. Profile branch warm-start with 60s throttle on background refresh (`lastWarmRefreshMs`). `FeedWindowConfig` holds all constants (WINDOW_SIZE=300, relay fanout, freshness TTLs)
- **VideoPlaybackScope** — shared ExoPlayer, viewport center activation (60%/35% hysteresis), 3-layer flap protection
- **MemoryEventStore** — ConcurrentHashMap store, signal-driven reactive Flows (`_feedSignal`, `_profileSignal`, `_statsSignal`, `_actionSignal`, `_trustScoreSignal`, `_relayMonitorSignal`). Pattern: `_signal.map { scan() }.distinctUntilChanged().flowOn(Dispatchers.Default)`. **FeedListener** — targeted callbacks (`onContentInsert`, `onEngagementUpdate`, `onProfileUpdate`) fired from MES insert thread, registered via `CopyOnWriteArrayList`. Used by FeedWindow for live-tail. Bounded: per-kind content eviction (k1=5000, k6=1000, etc.) with own-pubkey + p-tag-mentioned anchors, feedRowCache LRU cap 500, profilesByPubkey LRU cap 2000 (anchored: own+followed+recent), actor indexes cap 1000 actors/500 targets. Sidecar caches: `eventModelsByEventId` (EventModel), `videoRenderModelsByEventId`, `imetaImageDimsByEventId`
- **MesMetricsLogger** — ProcessLifecycleOwner-driven 60s foreground logger (`MES/size` tag). Reports per-collection counts, per-kind breakdown, actor indexes, external cache sizes, eviction anchor counts, relay dedup metrics. `MesMetrics.kt` data class + `MES.snapshotSize()`
- **ImageDimensionCache** — singleton ConcurrentHashMap of image aspect ratios (url → width/height), clamped to 0.2..5.0
- **VideoThumbnailCache** — first-frame thumbnails via MMR, downsampled (inSampleSize=2, ~1MB/thumb), LRU eviction at 100 entries OR 64MB bitmap total, `visibleUrls` set protects on-screen thumbnails from eviction
- **SearchViewModel** — NIP-50 search, `AtomicLong` token tracking, debounce + collectLatest, CLOSE frames on supersede
- **RelayPreferencesStore** — DataStore-backed, `Mutex`-guarded read-modify-write for indexer URLs, relay monitor staleness timestamp (`lastMonitorFetchAt`)
- **SnapshotScheduler** — periodic + onStop save (3s timeout), AtomicFile for crash safety. Snapshot section order: `---FOLLOWS---`, `---EVENTS---`, `---AGGREGATES---`, `---RELAY_HEALTH---`. Follows-first enables early `_followsSignal` mid-restore (before 25s event parse), eliminating cold-start Global→Following flash. Old snapshots (follows at end) still parse via implicit section 0 default

---

## Features — Shipped

Feed (Following/Global/Popular + relay-specific, Notes/Conversations tabs, filter sheet, Load More pagination, blue dot, deterministic cold-start) · Content (kind 1/6/20/21/30023, @mentions, quotes, OG previews, YouTube, media grids — unified EventCard pipeline with pre-parsed EventModel + ContentParser) · Video (inline autoplay, shared ExoPlayer, fullscreen, HLS, mute) · Profiles (avatar/banner/bio, edit, tabs, follow/unfollow, NIP-45 followers, NIP-65 outbox) · Engagement (reactions, reposts, zaps NWC NIP-47, action bar) · Relay (NIP-51 ecosystem, relay sets, relay health, blocked relays) · Navigation (bottom nav, thread view with tree nesting, NIP-50 search, notifications with blue dot) · Auth (nsec + Amber NIP-55, logout with session key rotation) · Branding (waveform LogoMark, adaptive icon, deterministic cold-start splash)

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

### EventCard Pipeline
1. **Single rendering pipeline** — EventCard is the sole card composable for all surfaces (feed, thread, profile, search, notifications). NoteCard.kt and ArticleCard.kt are deleted. Never recreate parallel card renderers
2. **EventModel is pre-parsed at insert time** — ContentParser.parse() runs in EventProcessor.flushBatch and MES.insertFromSnapshot. Composables read from `MES.eventModelsByEventId`, never invoke ContentParser during render. `preparsedImeta` param eliminates redundant ImetaParser calls — parse once in caller, pass to ContentParser
3. **ContentParser is a pure function** — no Android dependencies, no side effects, O(n) tokenization. Token precedence: nostr: URIs > YouTube > video > image > generic URL > text
4. **CardRole enum replaces RenderContext** — Feed, Thread, Reply, Profile, Article, Search, Embedded, NotificationCompact. Controls action bar visibility, avatar size, content line limits
5. **Segment sealed class** — Text, Link, Image, Video, YouTube, MentionPubkey, QuoteEvent, QuoteAddress. ContentFlow walks segments in source order — consecutive same-type segments collapse (Text/MentionPubkey → InlineText, Image → EventMediaGrid, Video → EventVideoGrid). No bucket-then-render
6. **QuoteCard uses same pipeline** — resolves EventModel from `lookupModel` or parses on-the-fly via ContentParser. Renders via ContentFlow with `CardRole.Embedded`. `nestDepth` controls recursion: depth < 1 → full ContentFlow, depth >= 1 → text-only from segments. Falls back to NostrRichText if model parse fails
7. **NoteCardHelpers.kt holds shared utilities** — AvatarImage, NostrRichText, LinkPreviewCard, ActionButton, ZapButton, FullScreenVideoDialog, regex constants, extension properties. Same package as old NoteCard (`ui.feed`)

### Pipeline Unification
8. **Recursive unification** — when unifying a rendering pipeline, follow it through every embed point. Quoted notes are notes. Reply previews are notes. Notification target previews are notes. They all get the same primitives or the unification is incomplete

### Video (guardrails)
9. **NEVER touch video** (InlineAutoPlayVideo.kt, VideoPlaybackScope.kt, EventCard media section) without permission
10. **If video heat returns, check detector rate FIRST** — codec realloc per distinct URL is normal; per second is a flap bug
11. **SurfaceView ignores parent View alpha** — use conditional rendering, not `Modifier.alpha(0f)`

### Correctness
12. **Verify bugs on device** before fixing — stale bug lists caused regressions
13. **Diagnose before prescribing** — read actual code first
14. **Never carry stale bugs forward** — verify each bug exists on current HEAD
15. **Prefer caller-side guards** over time-based debounce (distinctUntilChanged, empty-set returns, state guards)
16. **key(feedKey) on LazyListState kills video autoplay** — never do this
17. **Two pointerInput modifiers conflict** — use single awaitEachGesture block
18. **Logout: isLoggedIn=false BEFORE teardown** — triggers recomposition that destroys VMs. Singletons survive via `key(sessionKey)`

### Concurrency & Threading
19. **Every MES scan Flow MUST use `flowOn(Dispatchers.Default)`** — without it, ConcurrentHashMap iteration runs on Main → ANR
20. **`viewModelScope.launch {}` inside `collectLatest` does NOT cancel** — use `withContext(IO)` instead + ID-set guard
21. **NostrEvent.relaysSeen MUST be `ConcurrentHashMap.newKeySet()`** — iterated on Default, mutated on IO
22. **produceState dispatches relay work via `withContext(IO)`** — relay methods must not block Main
23. **SearchViewModel token is `AtomicLong`** — never regress to nullable Long, always use atomic ops
24. **RelayPreferencesStore read-modify-write uses `Mutex`** — prevents lost updates on concurrent indexer URL changes
25. **SnapshotScheduler onStop has 3s `withTimeoutOrNull`** — prevents indefinite mutex block if periodic save is in progress

### Relay & Protocol
26. **NIP-42 auth waits for OK** — `pendingAuthEventIds` tracks sent auth events. `handleOk()` confirms, `completeAuth()` marks authenticated + replays subs. 10s fallback for non-compliant relays
27. **Search subscriptions MUST send CLOSE frames when superseded** — `relayPool.closeSearch(priorToken)` before new REQs. 10s timeout safety net for missing EOSE
28. **ProfileResolver call sites MUST pre-filter** — use `userRepository.fetchMissingProfiles` or `profileResolver.filterUnresolved()`. Grep for `Batch.*all fresh, skipping` to verify
29. **fetchRelayEcosystem sends to write relays, not just indexers** — NIP-51 replaceable events live on write relays
30. **NIP-51/NIP-65 relay events need direct-path insert** — kinds 10002/10006/10007/10012/30002 not in shouldChannel
31. **One-shot fetches use `sendOneShotBatch`, NOT `connect()`** — pool-reuse for URLs already in `connections`, ephemeral WebSocket for others. Never `connect()` for one-shot REQs (it counts against the pool safety cap)
32. **Ephemeral connections never enter `connections` map** — no cap, no reconnect, no idle eviction. Lifecycle: connect → REQ → EVENT* → EOSE → CLOSE → close WebSocket
33. **Indexer relays are PERSISTENT** — registered with `ConnectionPurpose.PERSISTENT` in bootstrap before `connectAndAwait`. Prevents idle eviction
34. **Relay monitor fetch is staleness-gated** — 12h threshold via `RelayPreferencesStore.lastMonitorFetchAt()`

### Feed & Hydration (FeedWindow)
35. **Batch-first, not reactive-stream** — Feed is a frozen list of WINDOW_BATCH events from FeedWindowLoader. No `feedFlow.collect`, no continuous MES re-scan. Live-tail via MES FeedListener callbacks merged into the window
36. **FeedWindow is the sole feed primitive** — FeedViewModel, ProfileViewModel, and UserProfileViewModel all use FeedWindow instances. Each window has its own Channel drain loop serialized on `Dispatchers.Default.limitedParallelism(1)`
37. **Live-tail has cold-fill mode** — `coldFill = isLoadingInitial || rendered.size < 50`. During cold fill, events accepted with sorted insert regardless of head time. Steady state: strict live-tail, only `createdAt > head`. Both branches use sorted insert (`indexOfFirst { it.createdAt < row.createdAt }`)
38. **`passesAllFilters` shared companion** — both `loadBatchFor` and live-tail `ContentInsert` use `FeedWindow.passesAllFilters(row, filter)`. No divergence
39. **Profile tab kinds** — NOTES: `setOf(1, 6)`, REPLIES: `setOf(1, 6)`, LONGFORM: `setOf(30023)`. Both FeedWindow.resolveFilter and FeedWindowLoader.loadBatchFor must agree
40. **Tab-keyed window cache** — `mutableMapOf<ProfileTab, FeedWindow>()` in both profile VMs. Cache cleared on pubkey change. `onCleared` releases all
41. **MES polling for cold Home feeds** — `loadBatchFor(Home)` polls MES every 300ms with 5s timeout when empty
42. **Profile warm-start** — `loadBatchFor(Profile)` returns cached if available, background refresh throttled 60s per pubkey (`lastWarmRefreshMs`)
43. **Zone-aware hydration replaces eager hydration** — No blocking hydration at load time. FeedWindowLoader does imeta-only pass. Profiles, refs, OG, video first-frame, engagement hydrated by FeedWindow's `runHydrationPass()` on viewport changes. Warm zone = 10 above + 30 below
44. **Hydration IO escapes limitedParallelism(1)** — `scope.launch(Dispatchers.IO)` for OG and video MMR. Without this, IO blocks the drain loop for seconds per URL
45. **Hydration scheduling is cancel-and-reschedule** — `hydrationJob?.cancel()` before every new launch. Rapid scrolls extend 300ms debounce. `lastHydratedFirst`/`lastHydratedLast` skip redundant passes. Never `if (job.isActive) return`
46. **Engagement freshness is per-viewport, 60s staleness** — `HydrationStatus.engagementFreshAt` per event. `refreshEngagementForIds(ids)` replaces deleted 120s tick
47. **`_activeKey` StateFlow pattern** — set AFTER `activeWindow` in `swapToWindow`. `flatMapLatest` reads correct window's snapshot
48. **Viewport tracking in screens** — `snapshotFlow { layoutInfo.visibleItemsInfo }` sampled 100ms → `onViewportChanged(first, last)`. All three screens wired

### Media
49. **Media aspect ratios are layout-locked after first compose** — three-tier resolution (imeta → MMR → ImageDimensionCache). ONE update from default→resolved permitted, then locked
50. **ImageDimensionCache clamps ratios to 0.2f..5.0f** — malformed dimensions from servers must not corrupt layout
51. **Zero-height guard on imeta dims** — `&& it.height != 0` at all parse/resolve sites
52. **MES sidecar caches** — `eventModelsByEventId`, `videoRenderModelsByEventId`, `imetaImageDimsByEventId` cleared in `MES.clear()`, populated in `insertFromSnapshot()`
53. **FeedWindowLoader imeta-only** — parses imeta dims at load time (~5ms for 300). No profile/ref/OG hydration at load time
54. **OG preview aspect ratio is 16:9** — all four states (request, placeholder, loaded, error) use `aspectRatio(16f / 9f)`

### Shared Utilities
55. **`toEventJson(event)` in `NostrJson.kt`** — single shared function, never duplicate
56. **`ANTIPRIMAL_RELAY_URL` in `RelayUrlUtil.kt`** — use the constant, don't hardcode
57. **`normalizeRelayUrl()` in `RelayUrlUtil.kt`** — top-level function, no companion wrappers

### Thread Safety
58. **Thread DFS walk uses `visited` set** — cycle protection on circular reply chains

### Memory Bounds
59. **VideoThumbnailCache bitmaps MUST be downsampled** — `inSampleSize=2`. Removing → ~3.5MB/thumb → 64MB cap in 2 min
60. **VideoThumbnailCache `visibleUrls` set** — `markVisible`/`markNotVisible` in `DisposableEffect`. Missing unregister = leak
61. **feedRowCache + feedRowAccessedAt paired** — every `remove()` site MUST also remove from `feedRowAccessedAt`
62. **Actor index caps** — 1000 actors LRU, 500 targets/actor. `ownPubkey` anchored
63. **Profile eviction anchors** — own + followed + top 500 recent authors. Cascades to all profile maps
64. **MES.ownPubkey** — set at bootstrap start, before events arrive. Used by all eviction anchors
65. **Content eviction anchors** — skips `pubkey == ownPubkey` OR p-tag → ownPubkey
66. **lookupEvent `fetchingQuoteIds` is transient** — cleared after completion, not permanent
67. **Notification `lastSeen` re-read per emission** — prevents blue dot reappear on tab switch
68. **ColdStartState, not boolean splash** — 10s kind-3 + 5s kind-10002. Never regress to boolean

### Snapshot Persistence
69. **Kind-3 is NOT channeled through EventProcessor** — direct-path via `updateFollows`
70. **Snapshot follows format** — `follows|pubkey|createdAt|hex1,hex2,...` pipe-delimited
71. **Follows-first snapshot order** — `---FOLLOWS---` before `---EVENTS---`. Mid-restore signal enables <1.4s cold-start
72. **connectAndAwait BEFORE snapshot restore** — connections must be in map for `sendOneShotBatch` reuse during 25s parse

---

## Performance Audit Methodology

Always measure before proposing fixes. Grab a real session logcat, filter with `grep ' PID  PID '`, count by tag with `awk`/`sort`/`uniq -c`. Code reading does not measure runtime cost.

**Rate-limit exhaustion:** `grep "token exhausted" | grep -oE "wss://[^ ]+" | sort | uniq -c | sort -rn`
**Subscription leaks:** grep for `Search EVENT received` after last `closeSearch` timestamp
**Window loader:** `grep "FeedWindow:" | grep "events="` — verify window sizes and coverage percentages
**Validation discipline:** failed criteria = revert first, investigate second. "Pre-existing" requires evidence from a comparable baseline
**MES memory:** `adb logcat -s "MES/size"` — 4-line emission every 60s while foreground. Tracks events (per-kind), profiles, actor indexes, feedRowCache, VideoThumbnailCache bitmap bytes, ImageDimensionCache entries. Trim events logged under `MES` tag

---

## Key Patterns

**MES feed query:** `feedEvents()` / `userEvents()` scan `idsByKind` + `eventEntitiesByNoteId`, filter by kind/pubkey/relay/content-type, join profiles + stats → FeedRow list. Called by `FeedWindowLoader.loadBatchFor()` at load time, not continuously

**Cold-start / splash:** `ColdStartState` enum (`LOADING`, `READY_FOLLOWING`, `READY_GLOBAL`) in FeedViewModel. Waits up to 10s for kind-3 (follows), then 5s for kind-10002 (relay lists). Warm resume resolves instantly from snapshot (<300ms). No Global→Following flash. `splashDone` backward-compat alias gates AppNavigation bars

**Logout:** `isLoggedIn=false` → `bootstrapper.teardown()` → `key(sessionKey)` forces fresh VM creation

**Relay config:** 5 indexers (DataStore, PERSISTENT purpose), 5 search (MES), 6 global defaults (RelayUrlUtil), safety cap 50 (POOL_SAFETY_CAP). Relay monitors staleness-gated at 12h via DataStore

**Notifications:** MES scan-based `getNotifications()` driven by `combine(_feedSignal, _statsSignal)`. Blue dot via DataStore per-user key

---

## Source Structure

```
app/src/main/kotlin/com/unsilence/app/
├── data/
│   ├── auth/        KeyManager (cached pubkey), SigningManager
│   ├── cache/       CoverageTracker, SyncTracker
│   ├── memory/      MemoryEventStore, Models, SnapshotScheduler, MesMetrics, MesMetricsLogger
│   ├── model/       EventModel, ContentParser, VideoRenderModel
│   ├── relay/       RelayPool, EventProcessor, ProfileResolver, CardHydrator,
│   │                OgFetcher, RelayPreferencesStore, NostrJson (toEventJson),
│   │                RelayUrlUtil (ANTIPRIMAL_RELAY_URL, GLOBAL_RELAY_URLS, normalizeRelayUrl)
│   ├── repository/  UserRepository, ZapRepository
│   ├── wallet/      NwcManager, LnurlResolver
│   └── AppBootstrapper.kt
├── di/              Hilt modules
├── ui/
│   ├── common/      LogoMark, LoadingScreen, EmptyState, ShimmerNoteCard, IdentIcon
│   ├── feed/        FeedScreen, FeedViewModel, FeedWindow, FeedWindowLoader,
│   │                WindowKey, FeedWindowConfig, EventCard, ContentFlow,
│   │                NoteCardHelpers, ImageDimensionCache, VideoThumbnailCache
│   ├── navigation/  AppNavigation
│   ├── compose/     ComposeScreen, ComposeViewModel
│   ├── notifications/ NotificationsScreen, NotificationsViewModel
│   ├── profile/     ProfileScreen, UserProfileScreen, EditProfileScreen
│   ├── search/      SearchScreen, SearchViewModel
│   ├── relays/      RelayManagementScreen, CreateRelaySetScreen
│   ├── shared/      EventFeedItems, ThreadParentCard, CardRole, VideoPlaybackScope
│   ├── thread/      ThreadScreen, ThreadViewModel (cycle-protected DFS)
│   └── theme/       Color.kt, Theme.kt (Spacing, Sizing, AppType)
└── util/
```
