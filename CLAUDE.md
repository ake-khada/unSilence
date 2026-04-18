# unSilence — Claude Code Context

**Last updated:** April 17, 2026 (A.8 shipped — Room eliminated entirely. MES is sole data layer. No Room dependency, no DAOs, no entities, no migrations.)
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
| Nostr | Quartz library |
| AGP 8.9.1 | compileSdk/targetSdk 36, JDK 17 |

---

## Design System

### Colors
- **Background:** AMOLED pure black `Surface0` (#000000)
- **Surface depth:** `Surface1` (#0A0A0A), `Surface2` (#141414), `SurfaceVariant` (#080808)
- **Accent:** `Cyan` (#00E5FF), **Zap:** `ZapAmber` (#FFAB00)
- Disabled alpha: 0.38f (Material standard)
- No light theme

### Spacing (golden ratio: 360dp / phi^n)
`Spacing.micro=5dp`, `small=8dp`, `medium=12dp`, `large=20dp`, `xl=32dp`, `xxl=52dp`

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

**Core principle:** MES-only (in-memory ConcurrentHashMap), 0ms screen render, snapshot persistence to disk. No Room, no SQLite. A.8 complete — Room eliminated entirely. All data classes (EventEntity, UserEntity, FeedRow, SyncStateEntity, RelayTrustScoreEntity) live in `data/memory/Models.kt` as plain Kotlin data classes.

### Key Subsystems (read code for details)
- **EventProcessor** — dedup via seenIds, kind handlers, spam filter, relay provenance
- **ProfileResolver** — batched profile fetch, 6h staleness, 15s in-flight guard. Staleness filter is applied at EVERY call site BEFORE launching orchestration via `profileResolver.filterUnresolved()` (or `userRepository.fetchMissingProfiles`, which wraps it). Early-return on empty result prevents batch allocation and relay queue entry. ProfileResolver still filters internally as defense-in-depth, but the log line "Batch N → all fresh, skipping" should never fire. If it does, a new caller was added without the pre-filter
- **RelayPool** — WebSocket manager, ConnectionPurpose (PERSISTENT/BROWSE/OUTBOX), per-relay REQ queue (cap 10), token bucket rate limiter, idle eviction
- **FeedStateReducer** — MERGE at top / QUEUE when scrolled / APPEND pagination, blue dot, structural dedup
- **FeedHydrationController** — 5-state scroll machine (WARM_CATCHUP/SLOW_SCROLL/IDLE/FAST_SCROLL/REST), CardHydrator as stateless worker, velocity hysteresis, low-pass filter, per-item bitmask ledger (PHASE_PROFILE/REFS/ENGAGEMENT), REST cancellation of in-flight jobs. Sampled at 60ms (16 Hz) via Flow.sample in FeedScreen's snapshotFlow wiring — the state machine has natural transition rates of hundreds of milliseconds; per-frame evaluation at display refresh rate (120 Hz on Pixel 9 Pro XL) is wasteful and produces frame drops. Scroll edge detection (onScrollStarted/onScrollStopped) uses a separate snapshotFlow with distinctUntilChanged() to capture edges immediately without sampling delay
- **VideoPlaybackScope** — shared ExoPlayer, viewport center activation (60%/35% hysteresis), 3-layer flap protection: layout shift cooldown (500ms, stationary only), 250ms confirmation window (flatMapLatest cancellation), oscillation detection (A→B→A block within 3s). Fullscreen handoff: inline player detaches surface via `isFullscreen` flag, dialog gets exclusive surface ownership
- **MemoryEventStore** — in-memory ConcurrentHashMap store (eventsById, profilesByPubkey, statsByTarget, followsByPubkey, reactionsByActor, repostsByActor, trustScoresByUrl, relayMonitorsByUrl). Signal-driven reactive Flows: `_feedSignal`, `_profileSignal`, `_statsSignal`, `_actionSignal`, `_trustScoreSignal`, `_relayMonitorSignal` drive `.map { scan() }.distinctUntilChanged().flowOn(Dispatchers.Default)` patterns. All scan Flows MUST use `flowOn(Dispatchers.Default)` — MES has no internal IO dispatching; without flowOn, scans run on Main thread causing ANR. Kind 30385 trust scores parsed by `handleTrustScore()`, kind 30166 relay monitors parsed by `handleRelayMonitor()`. Combined via `relayHealthFlow()` which merges trust + monitor data per URL into `RelayHealthInfo`. Snapshot-persisted in `---RELAY_HEALTH---` section
- **ImageDimensionCache** — singleton ConcurrentHashMap cache of image aspect ratios (url → width/height). Resolves dimensions via `BitmapFactory.Options.inJustDecodeBounds` (reads only image header, ~1KB, no full decode). Pre-fetched by CardHydrator during Phase 2 hydration; NoteCard reads on first compose for correct container sizing from frame 1. Also populated on Coil success callback (covers non-hydrated paths like search/thread). Eliminates feed image pop (card renders at correct aspect ratio before the full image downloads)
- **CoverageTracker / SyncTracker** — in-memory replacements for CoverageDao+SyncStateDao (ephemeral session-scoped relay sync state). ConcurrentHashMap-backed, not suspend, no Room dependency
- **SearchViewModel** — NIP-50 search with 1000ms debounce, min 3-char filter, distinctUntilChanged, collectLatest cancellation. Local results from MES (`searchNotesFlow`, `searchUsersFlow`), relay results via token-correlated SharedFlow + `feedRowsByIdsFlow`. Token-based session tracking: each new query generates a token, `relayPool.closeSearch(token)` sends CLOSE frames for prior search subs before issuing new REQs. Search sub-IDs (`search-profiles-$token`, `search-notes-$token`) registered in `_activeOneShotSubs` so EOSE auto-closes work. `onCleared()` sends final CLOSE. RelayPool.searchNotes also runs a 10-second safety-net timeout that force-closes any search subscription whose EOSE never arrives — this handles relays (e.g. ditto.pub for search-notes) that treat NIP-50 search as a streaming subscription rather than a bounded query
- **RelayPreferencesStore** — DataStore-backed persistence for kind-99 indexer URLs, pinned relays, and notification lastSeen timestamps (per-user). StateFlow cache with suspending and snapshot reads. Replaces relayConfigDao for indexer URL reads across all VMs and RelayPool
- **AppBootstrapper** — 3-phase staggered init, bootstrap job cancellation (new login cancels in-progress bootstrap), MES snapshot restore, concurrent relay health fetch (kind 30385 trust scores + kind 30166 monitors), MediaPreconnect fire-and-forget, BackgroundSyncWorker (skeleton)

### Room — Eliminated (A.8)
Room was fully removed in A.8. No `data/db/` directory, no DAOs, no entities, no AppDatabase, no Migrations, no DatabaseModule, no Room dependency in build.gradle.kts. Data classes (EventEntity, UserEntity, FeedRow, SyncStateEntity, RelayTrustScoreEntity) moved to `data/memory/Models.kt` as plain Kotlin data classes. EventRepository deleted (all callers migrated to MES). OutboxRouter reads follows/relay lists from MES. ComposeVM/ThreadVM do optimistic insert via `memoryEventStore.insert(NostrEvent(...))`. UserRepository reads from MES.

---

## Features — Shipped

**Feed:** Following/Global/Popular + relay-specific feeds, Notes/Conversations tabs, filter bottom sheet (type/time/engagement), infinite scroll, immersive scrolling, FeedStateReducer blue dot

**Content:** Kind 1 notes, kind 6 reposts (including bridged), kind 30023 articles (WebView reader), inline @mentions, embedded quotes (nevent/note/naddr), OG link previews, YouTube thumbnails, multi-photo/video grids

**Video:** Inline autoplay, shared ExoPlayer, fullscreen dialog, HLS support, mute toggle

**Profiles:** Avatar/banner/bio, edit profile, tabs (Notes/Replies/Longform), follow/unfollow, followers count (NIP-45), NIP-65 outbox

**Engagement:** Reactions, reposts, zaps (NWC NIP-47), zap persistence (kind-9734 → MES), action bar with share

**Relay:** NIP-51 ecosystem (10002/10006/10007/10012/30002), relay sets, relay health (kind 30385 trust scores + kind 30166 NIP-66 monitors — colored dots, ping labels, detail sheet), blocked relays

**Navigation:** Bottom nav (Home/Search/Compose/Notifications/Profile), thread view (tree nesting, 16dp indent, 10% guide lines, depth cap 4), search (NIP-50), notifications (Following/Global filter, blue dot)

**Auth:** nsec + Amber (NIP-55), signing for all operations, logout with process restart

**Empty states:** Contextual icon + message on all empty screens (feed, notifications, search, profile tabs)

---

## TODO — Remaining Features

### High Priority — UX
1. Engagement drawer — tap count → bottom sheet with user list
2. Settings — Keys (nsec export), Safety (mute/block), Wallet (NWC management), Cache clear
3. Image pinch-zoom in fullscreen dialog

### High Priority — Privacy
4. NIP-36 content-warning blur overlay (data already in MES — `hasContentWarning`/`contentWarningReason` on NostrEvent)
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

1. **NEVER touch video** (InlineAutoPlayVideo.kt, VideoPlaybackScope.kt, NoteCard media section) without permission
2. **If video heat returns, check detector rate FIRST** — codec realloc per distinct video URL is normal; codec realloc per second is a detector flap bug. Look at `VideoScope: Active video` log frequency, not the player or codec lifecycle
3. **SurfaceView ignores parent View alpha** — `Modifier.alpha(0f)` does NOT hide a SurfaceView's hardware surface. Never use persistent SurfaceView with alpha gating; use conditional rendering (InlineVideoPlayer when active, VideoPreviewCard when inactive)
4. **Verify bugs on device** before fixing — stale bug lists caused regressions
5. **Diagnose before prescribing** — read actual code first
6. **Prefer caller-side guards** over time-based debounce (distinctUntilChanged, empty-set returns, state guards)
7. **key(feedKey) on LazyListState kills video autoplay** — never do this
8. **Two pointerInput modifiers conflict** — use single awaitEachGesture block
9. **Logout: isLoggedIn=false BEFORE teardown** — setting isLoggedIn=false triggers recomposition that destroys AppNavigation and all nested VMs. If teardown runs first (clears keyManager/MES), the Main-thread switch inside teardown lets Compose see cleared state + isLoggedIn=true, creating zombie VMs. exitProcess was removed — singletons survive, `key(sessionKey)` forces fresh VM creation on re-login
10. **Never carry stale bugs forward** — verify each bug exists on current HEAD
11. **If sustained heat returns during scroll, check controller rate FIRST** — run `grep ' PID  PID ' logcat | grep -c HydrationCtrl` on a session logcat. Per-minute rate above 50 indicates the sample throttle is broken or removed. The controller should never run at display refresh rate. Frame drops correlate with main-thread controller activity — both should be near zero during a healthy session
12. **When adding a new ProfileResolver call site, MUST use `userRepository.fetchMissingProfiles` (preferred) or explicitly pre-filter with `profileResolver.filterUnresolved()`** — grep logs for `Batch.*all fresh, skipping` — any non-zero count indicates a bypass
13. **Search subscriptions MUST send CLOSE frames when superseded** — every `relayPool.searchNotes()` call must be preceded by `relayPool.closeSearch(priorToken)`. Search sub-IDs must be registered in `_activeOneShotSubs`. Verify with `grep closeSearch logcat` — count must equal or exceed NIP-50 query count minus 1
14. **NIP-50 search subscriptions are protected by a 10-second timeout in RelayPool.searchNotes** as a safety net against relays that never send EOSE. If you see `Search EVENT received` log lines more than ~11 seconds after the query was issued, the timeout mechanism is broken. Grep for `10s timeout elapsed` to verify it's firing
15. **Never write `_state.value` directly in FeedStateReducer** — always route through `emitCoalesced` (for ID-changing emissions) or `emitCoalescedDataRefresh` (for data-only refreshes). Direct writes bypass the coalescing window and trigger immediate Compose recompositions on every hydration write, defeating the feedback-loop protection. The only exception is `flush()` which deliberately bypasses coalescing for user-initiated actions (blue dot tap, scroll-to-top)
16. **The current architecture is render-then-hydrate, not hydrate-then-render.** MES signal emissions flow directly to the reducer, which updates visible state immediately. The WARM zone catches up with hydration AFTER cards are visible. Any design that requires cards to be fully hydrated BEFORE entering visible state must first restructure this flow — by buffering MES emissions until hydration completes, or by driving display from a hydration-completion signal instead of from MES. Patching the gate at the reducer level will always fail because the hydration work hasn't started yet when the reducer runs. P_WARM_READINESS_PAGINATION attempted this patch twice and failed both times; the sprint was reverted and deferred
17. **In Kotlin coroutines, `viewModelScope.launch { }` inside a `collectLatest` block does NOT participate in collectLatest's cancellation semantics** — the inner launch is scoped to `viewModelScope`, so each re-emission spawns a new coroutine while old ones continue running. On signal-driven MES Flows this creates a feedback loop: hydration writes → MES signal re-emission → new coroutine → more writes. Use `withContext(Dispatchers.IO)` instead (participates in cancellation), and add an ID-set guard to skip data-only re-emissions. P_PROFILE_VM_LEAK measured 452 leaked coroutines from this pattern in a 7.5-minute session
18. **Engagement freshness checks use tiered thresholds based on post age** (`freshnessThreshold` in FeedHydrationController). The flat 5-minute threshold previously used was appropriate for fresh posts but produced ~71% wasted fetches on the dominant case (posts >1 hour old). If adding a new engagement dispatch path, route it through this function or replicate the tiering logic. Do NOT use the raw `ENGAGEMENT_STALE_MS` constant as the sole threshold — it's the Tier 1 value only. P_ENGAGEMENT_TIERED_FRESHNESS measured 43% batch reduction on return visits and 57% thermal rate reduction
19. **Every MES signal-driven scan Flow MUST use `flowOn(Dispatchers.Default)`** — unlike Room (which dispatches to its own IO pool), MES scans run inline on the calling coroutine's thread. Without `flowOn`, all ConcurrentHashMap iteration + filtering + sorting runs on Main, causing ANR ("close app" dialog). A.5.1 T3 discovered this when search scans stalled the UI; T6 hardened all existing Flows. Pattern: `_signal.map { scan() }.distinctUntilChanged().flowOn(Dispatchers.Default)`
20. **Kind 10012 relay set references require hint-relay resolution** — kind-10012 (favorites) events contain `["a", "30002:pubkey:dtag", "hint-relay"]` tags referencing kind-30002 relay sets that exist on specific hint relays, NOT indexers. EventProcessor.resolveRelaySetRefs() parses these tags and dispatches fetches via RelaySetRefFetcher (fun interface, same pattern as PrefetchDispatcher). RelayPool.fetchRelaySetsByCoordinate() connects to hint relays and sends `#d`-filtered REQs. Without this, relay sets from Jumble/Keychat only appear if the user also stores them on indexer relays
21. **fetchRelayEcosystem MUST send to write relays, not just indexers** — NIP-51 replaceable events (10006/10007/10012/30002) are published to write relays by Jumble, Keychat, etc. Indexers (purplepag.es) focus on kind 0/3/10002 and may not store these kinds. If relay ecosystem fetch returns empty for 10012/30002, check whether write relays are included as targets
22. **All NIP-51/NIP-65 relay control-plane events need direct-path insert** — kinds 10002, 10006, 10007, 10012, 30002 are NOT in shouldChannel (they're not feed content). Without direct `memoryEventStore.insert()` in onRelayMessage, they never reach MES handlers. Kind 3 IS in shouldChannel (for snapshot persistence) but also gets direct-path `updateFollows()` for immediate MES update
23. **NostrEvent.relaysSeen MUST be `ConcurrentHashMap.newKeySet()`** — iterated on Dispatchers.Default (feedFlow scans) while mutated on IO (addRelaySeen, insert dedup). Using `mutableSetOf()` (LinkedHashSet) causes ConcurrentModificationException. Every construction site (EventProcessor, ComposeVM, NoteActionsVM, ThreadVM, snapshot restore) must use `ConcurrentHashMap.newKeySet<String>()`
24. **Image aspect ratio resolution is pre-hydration, not render-time** — `ImageDimensionCache` resolves aspect ratios during CardHydrator Phase 2 via lightweight `BitmapFactory.inJustDecodeBounds` (header-only, no full decode). NoteCard's `MediaImage` reads the cache on first compose (priority: imeta > `ImageDimensionCache` > default 4:3). This eliminates the aspect ratio pop where cards rendered at 4:3 then jumped to the image's true ratio. `feedImageAspectRatio()` has no portrait cap — images render at their true dimensions. `ThreadParentCard` uses `SubcomposeAsyncImage` with empty error composable so failed images collapse (no gap)

---

## Performance Audit Methodology

Code reading does not measure runtime cost. Always grab a real session logcat and analyze it before proposing performance fixes. Use `grep ' PID  PID '` to filter to main-thread events, count by tag with `awk`/`sort`/`uniq -c`, measure inter-event deltas to identify hot paths firing at display refresh rate. The morning audit on Apr 11 used three parallel agents to read code and produced a confident 9-item plan based on speculation; the log analysis found the actual bug (controller at 120 Hz on main thread) in 5 minutes. Always measure first.

Rate-limit exhaustion counts per relay (`grep "token exhausted" | grep -oE "wss://[^ ]+" | sort | uniq -c | sort -rn`) are a reliable signal for "something is hammering this relay." Always check which sub-ID prefix appears before the exhaustion to identify the responsible call site.

Subscription leak detection: grep for `Search EVENT received` and compare the last event timestamp against the last closeSearch timestamp. Any events arriving after the last close indicate a leaking subscription that neither EOSE nor timeout caught.

Validation discipline: When validation criteria fail, revert first, investigate second, re-ship third. Do not attempt to reframe failed criteria as acceptable during the analysis of the same failed run. "Pre-existing" is a claim that requires evidence from a comparable baseline — if the baseline log doesn't exist or was captured under different conditions (different feed size, scroll intensity, video density), the comparison is invalid. A user-reported new symptom during validation is a hard stop regardless of root-cause analysis.

---

## Key Patterns

**MES feed query:** `feedFlow()` scans `idsByKind` + `eventEntitiesByNoteId`, filters by FeedFilter (kinds, followedPubkeys, contentFilter, relayUrls), joins profilesByPubkey + statsByTarget, returns FeedRow list. Kind 6 engagement resolves via rootId fallback

**Shared composables:** NostrRichText, AvatarImage, relativeTime, ThreadParentCard, EmptyState, ActionButton, ZapButton

**Logout:** `isLoggedIn=false` (destroys Compose tree) → `bootstrapper.teardown()` (cancel bootstrap, disconnect, MES.clear(), delete snapshot, clear credentials, release ExoPlayer on Main). No exitProcess — singletons survive, `key(sessionKey)` forces fresh VM creation on re-login

**Relay config:** 5 indexers (purplepag.es etc.) in DataStore (kind 99), 5 search (NIP-50) in MES, 6 global defaults, cap 13+3 browse. All relay config read from MES signal-driven Flows + RelayPreferencesStore

**Relay health:** Trust scores (kind 30385 from trustedrelays.xyz) + monitors (kind 30166 from relay.nostr.watch) merged in `relayHealthFlow()` via `combine(_trustScoreSignal, _relayMonitorSignal)`. Both keyed by `normalizeRelayUrl()`. UI uses `lookup()` extension for normalized fallback. Trust scores targeted via `#d` filter, monitors fetched unfiltered (paginated). Snapshot-persisted in `---RELAY_HEALTH---` section. relay.nostr.watch connection retries once after 3s

**Notifications:** MES scan-based — `getNotifications()` walks `idsByKind` for kinds 1/6/7/9735, filters by `#p` tag match, resolves actor profiles and target note content inline. No insert-time index. `notificationsFlow()` driven by `combine(_feedSignal, _statsSignal)`. Read/unread (blue dot) via DataStore per-user `notif_last_seen_{pubkey}` key in RelayPreferencesStore

---

## Source Structure

```
app/src/main/kotlin/com/unsilence/app/
├── data/
│   ├── auth/        KeyManager, SigningManager
│   ├── cache/       CoverageTracker, SyncTracker (in-memory ephemeral state)
│   ├── memory/      MemoryEventStore, Models, SnapshotScheduler
│   ├��─ relay/       RelayPool, EventProcessor, ProfileResolver, CardHydrator, OgFetcher, RelayPreferencesStore
│   ├── repository/  UserRepository, ZapRepository
│   ├── wallet/      NwcManager, LnurlResolver
│   └── AppBootstrapper.kt
├── di/              Hilt modules
├── ui/
│   ├── common/      EmptyState, ShimmerNoteCard, IdentIcon, LoadingScreen, ImageRequestHelpers
│   ├── feed/        FeedScreen, FeedViewModel, FeedStateReducer, FeedHydrationController,
│   │                NoteCard, ArticleCard, ArticleReaderScreen, FilterScreen, ZapDialogs,
│   │                ImageDimensionCache, ImageSizing, VideoThumbnailCache
│   ├��─ navigation/  AppNavigation
│   ├── compose/     ComposeScreen
│   ├── notifications/ NotificationsScreen
│   ├── profile/     ProfileScreen, UserProfileScreen, EditProfileScreen, SettingsScreen
│   ├── search/      SearchScreen
│   ├── relays/      RelayManagementScreen, CreateRelaySetScreen
│   ├── shared/      EventFeedItems, NotificationEventRow, ThreadParentCard, VideoPlaybackScope
│   ├── thread/      ThreadScreen, ThreadViewModel
│   └── theme/       Color.kt (Surface0/1/2, Cyan, ZapAmber), Theme.kt (Spacing, Sizing, AppType)
└── util/
```
