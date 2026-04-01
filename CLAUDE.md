# unSilence — Claude Code Context

**Last updated:** April 1, 2026 (evening)
**Repository:** https://github.com/ake-khada/unSilence
**Package:** com.unsilence.app
**Path:** /home/aivii/projects/unsilence

---

## Environment

- **Main user:** `aivii` — runs Android Studio (JetBrains Toolbox), git operations
- **Claude Code user:** `android-dev` — runs `./gradlew`, code edits
- **ADB:** `/home/aivii/Android/Sdk/platform-tools/adb`
- **Emulator:** Android 16.0 Baklava x86_64 (KVM enabled)
- **Editor:** Neovim (`nvim`) — never use `nano`
- **Shell:** Zsh + Oh My Zsh on all systems

## Build Rules

- **NEVER** run `./gradlew` while Android Studio is open (Gradle lock conflict)
- Git operations from `aivii` terminal, not `android-dev`
- JAVA_HOME=/usr/lib/jvm/java-17-openjdk for CLI builds
- Compile check: `cd /home/aivii/projects/unsilence && ./gradlew :app:compileDebugKotlin 2>&1 | tail -15`
- Deploy via Android Studio Run button (not CLI APK install — signing mismatch)

---

## Tech Stack

| Component | Version |
|-----------|---------|
| Kotlin | 2.3.0 |
| Jetpack Compose | BOM 2025.05.00 |
| JDK | 17 |
| Gradle | 8.9 |
| KSP | 2.3.0 |
| Hilt | 2.58 |
| Room | 2.7.1 |
| AGP | 8.9.1 |
| Media3 / ExoPlayer | 1.5.1 (+HLS) |
| Coil | 3 |
| compileSdk / targetSdk | 36 |
| Nostr protocol | Quartz library |

### DI: Hilt only. No Koin. KSP, not kapt.

### Room Migrations
- Index names MUST use `index_tablename_col1_col2` convention (backticks in SQL)
- Every migration index MUST also be declared in `@Entity(indices=[...])` using vararg syntax: `Index("col1", "col2")`
- Test migrations thoroughly — Room can't auto-generate WITHOUT ROWID schemas

---

## Theme

- **Background:** AMOLED pure black `#000000`
- **Primary accent:** Cyan `#00E5FF`
- **Zap accent:** Amber `#FFAB00`
- **No light theme** — AMOLED only
- **Spacing:** Golden ratio system — 5/8/12/20/32/52dp

---

## Architecture

```
Relay WebSocket → EventProcessor → Room DB → Flow/StateFlow → Compose UI
```

### Core principle: Room-first, 0ms screen render, network fills gaps invisibly.

Every screen renders instantly from Room cache. Network fetches happen in the background. The user never waits for a relay response to see content.

### Room Database — v14

12 tables:

| Table | Purpose |
|-------|---------|
| events | All Nostr events (kind 1, 6, 7, 30023, etc.) |
| users | Profile metadata (kind 0) |
| follows | Contact list (kind 3 p-tags) |
| reactions | Kind 7 reactions |
| event_stats | Denormalized counters (reply/repost/reaction/zap count + zap sats) |
| tags | Normalized event tags (replaces JSON parsing) |
| event_relays | Which relay delivered each event (provenance) |
| relay_configs | NIP-51 relay kinds (10002/10006/10007/10012) |
| nostr_relay_sets | Kind 30002 categorized relay sets |
| nostr_relay_set_members | Relay URLs within each set |
| coverage | Coverage ledger — tracks what's been fetched |
| pinned_relays | User's pinned relay feeds (Room-backed) |

### Key Subsystems

**EventProcessor** — Receives events from RelayPool, deduplicates via `seenIds` set, processes by kind, stores in Room with denormalized counters. Records relay provenance in `event_relays` even for deduped events (fixes relay feed gaps). Immutable `kindHandlers` map via `dagger.Lazy`.

**ProfileResolver** — Centralized batched profile fetching. 6-hour staleness threshold (1h for no-picture profiles). In-flight guard prevents duplicate requests. Sends to 4 indexer relays (purplepag.es, user.kindpag.es, indexer.coracle.social, antiprimal.net).

**RelayPool** — WebSocket connection manager with `ConnectionPurpose` map:
- `PERSISTENT` — home feed subs (Following/Global), stay open
- `BROWSE` — relay-specific feed browsing, temporary
- `OUTBOX` — NIP-65 outbox relay connections for followed users

Auth spam suppression: `authFailedRelays` set, warning logged once then suppressed, cleared on reconnect. Persistent replay guard: requires `PERSISTENT` purpose before replaying subs (blocks OUTBOX-only relays from 31+ unwanted persistent subs).

**FeedStateReducer** — Manages feed state transitions:
- `isAtTop` true → MERGE new events directly into visible list with grey tint flash
- `isAtTop` false → QUEUE events as pending, show blue dot with count
- DOT_TAP → flush pending into visible, scroll to top
- Structural dedup: fast ID-order check prevents state update when Room re-emits same list

**VideoPlaybackScope** — Shared ExoPlayer instance at screen level. Active video detected via viewport center from `snapshotFlow` on `LazyListState`. Muted by default. 500ms buffer threshold (`DefaultLoadControl`). CDN preconnect at startup (HEAD requests to 7 common Nostr CDN hosts).

**HydrationFrontier** — Viewport-driven hydration planner (replaced CardHydrator). Builds a WarmWindow from LazyListLayoutInfo using pixel-based estimation (avgItemHeight → ahead/behind item counts), computes HydrationNeeds per FeedRow via pure `missingFields()` extension, subtracts Room-cached data (batch DAO queries), then dispatches only truly missing profiles/events to network. Velocity-aware cadence: Idle (<0.5 px/ms) → 100ms debounce, Moderate → 500ms, Fast fling → 1500ms. Mutex-serialized `plan()`, 30s TTL dedup, priority shedding under relay pressure (>15 in-flight: drop OG, >20: cap ahead window). Coexists with per-card produceState in NoteCard/ThreadedReplyItem — prefetched data lands in Room first, so produceState resolves from cache.

**FeedViewModel** — Manages feed type (Following/Global/SingleRelay), content filter (Notes/Conversations), engagement coalescing (Channel.CONFLATED + 2s minimum interval). Feed query: tri-state `combine(_feedType, _filter, _contentFilter)`.

**AppBootstrapper** — Comprehensive bootstrap (fetch kind 0/3/10002/10006/10007/10012/30002 on login) and teardown (disconnect all, clear ProfileResolver, preserve Room cache). Logout = process restart via `exitProcess(0)` with synchronous `.commit()` on SharedPrefs.

### Data Flow

```
User scrolls → snapshotFlow → ViewportSnapshot (visibleKeys, indices, avgItemHeight, velocity)
    → velocity-aware debounce (Idle 100ms / Moderate 500ms / Fast 1500ms)
    → WarmWindow.from(layoutInfo, events, avgItemHeightPx) — pixel-based ahead/behind estimation
    → HydrationFrontier.plan() (Mutex, Room subtraction, priority shedding)
    → ProfileResolver / RelayPool.fetchEventsByIds → Room update → Compose recomposition

New event arrives → EventProcessor → Room INSERT → Flow emission
    → FeedStateReducer (MERGE if at top, QUEUE if scrolled) → UI update

Conversation threading: reply visible → produceState(replyToId)
    → lookupEvent (Room check → relay REQ → 3s wait) → ThreadParentCard renders

Bridged repost: kind 6 empty content → produceState(e-tag target)
    → lookupEvent (Room check → relay REQ → 3s wait) → effectiveContent from fetched event
```

---

## Relay Configuration

**Indexers:** purplepag.es, user.kindpag.es, indexer.coracle.social, antiprimal.net
**Search (NIP-50):** relay.noswhere.com, search.nos.today, antiprimal.net
**Global defaults:** relay.damus.io, nos.lol, nostr.mom, relay.nostr.net, relay.primal.net, relay.ditto.pub
**Connection cap:** 25 relays soft limit
**Feed subs split:** posts(300), media(100), reactions(200), zaps(200)

---

## Features — Shipped

### Feed
- Global + Following feed with tab switching (default to Following)
- Relay-specific feeds via pinned relays (Room-backed, VerticalPager carousel)
- Notes/Conversations tab split (Room query contentFilter, swipeable via detectHorizontalDragGestures, centered weight(1f) layout)
- Conversation threading: parent note compact card above reply with connecting line (produceState + lookupEvent for Room + relay + 3s wait)
- FeedStateReducer: blue dot when scrolled, auto-merge at top with grey tint flash
- Immersive scrolling (top bar + bottom nav + tab row hide/show via NestedScrollConnection; topBarShown passed to FeedScreen, animated height)
- Scroll-to-bottom pagination with growing window
- Time range + engagement filters

### Content Rendering
- Kind 1 notes with images, video, links
- Kind 6 reposts with original author profile resolution (p-tag extraction); bridged reposts (mostr.pub) with empty content fetch referenced event via lookupEvent
- Kind 30023 long-form articles with WebView reader (org.jetbrains:markdown GFM→HTML)
- Article preview cards on all screens (banner, title, summary)
- ImetaParser for NIP-92 media extraction (video + image, dimension-based sizing)
- Inline @mentions via NostrRichText (AnnotatedString + LinkAnnotation.Clickable, cyan, fallback to truncated npub) — used in notes AND profile bios
- Embedded quote cards for nostr:note/nevent references (nestDepth cap at 1); unified style with ThreadParentCard (0.08 alpha white border, 12dp rounded, 24dp avatar)
- OpenGraph link preview cards (OgFetcher with ConcurrentHashMap cache)
- YouTube thumbnail cards (predictable URL, tap opens browser)
- Multi-photo/video grid (2x2 layout)

### Video
- Inline autoplay with shared ExoPlayer (single instance per screen)
- Active video detection via viewport center snapshotFlow
- Muted by default, toggle button
- Fullscreen video dialog with controls
- HLS (.m3u8) support via media3-exoplayer-hls
- ExoPlayer: 500ms buffer threshold, CDN preconnect at startup
- Portrait video with proper aspect ratio (capped at 2:3)

### Profiles
- Profile view with avatar, banner, bio (NostrRichText with @mention resolution), NIP-05 badge
- Edit profile (name, about, picture, banner, nip05, lud16, website) → kind 0 publish
- Profile tabs: Notes / Replies / Longform
- Following count (exact, from kind-3 p-tags)
- Followers count (approximate, NIP-45 COUNT via indexer relays, cached daily)
- Follow/Unfollow button → kind 3 publish via SigningManager
- NIP-65 outbox relay fetching for user posts
- Scroll pagination on user profiles
- Profile staleness TTL (6hr, 1hr for no-picture)
- IdentIcon fallback for missing avatars

### Relay Management
- NIP-51 relay ecosystem: kinds 10002/10006/10007/10012/30002
- Relay set management (create, select, delete)
- Relay sync cycle with created_at guard
- Kind 10002 read/write relay publish
- Blocked relays (kind 10006) prevent connection
- Search relays (kind 10007) user-configurable

### Authentication & Signing
- nsec login via KeyManager
- Amber login (NIP-55) via SigningManager + Quartz NostrSignerExternal
- Signing for all operations (post, reply, react, repost, zap, publish kinds)
- Logout with process restart (exitProcess + synchronous commit)

### Engagement
- Reactions, reposts, zaps (via Amber or nsec)
- Zap total sats display (extractZapSats via NIP-57 bolt11 parsing + toCompactSats formatter)
- Action bar: ↩ reply, ⚡ zap sats, 🔄 repost, ❤️ reaction

### Other
- Thread view with tree-based nesting (depth indentation)
- Notifications tab
- Search via NIP-50 relays
- Bottom nav: Home, Search, Compose, Notifications, Profile
- Post compose + reply
- Settings screen shell (Wallet, Drafts, Keys, Safety, Social Graph, Custom Emojis, Console, Logout)
- Branded loading screen with cypherpunk splash lines
- New post indicators (grey flash at top, blue dot when scrolled)

---

## Performance Optimizations — Shipped

| Fix | Impact |
|-----|--------|
| MERGE structural dedup | 80+ → ~5 spurious recompositions/min |
| Persistent replay guard | OUTBOX-only relays blocked from 31 persistent subs |
| Auth spam suppression | filter.nostr.wine auth loop eliminated |
| Empty hydration skip | 38 wasted calls eliminated |
| DOT_TAP guard | 70 flushes → fires only when dot visible |
| Engagement coalescing | Channel.CONFLATED + 2s gap (78 → ~20 calls/session) |
| Profile fetch throttle | 1s minimum between batches during scroll |
| OG fetcher leak fix | .use{} on HEAD response |
| ExoPlayer buffer | 500ms bufferForPlaybackMs (was 2500ms default) |
| CDN preconnect | HEAD requests to 7 Nostr CDN hosts at startup |
| Event relay provenance | INSERT OR IGNORE for deduped events (fixes relay feed gaps) |
| HydrationFrontier Room subtraction | Batch DAO existence checks before network — never re-fetch cached data |
| Priority shedding | >15 in-flight: drop OG previews; >20: cap ahead window entirely |
| One-shot sub tracking | ConcurrentHashSet tracks active subs for shedding decisions |
| Velocity-aware cadence | Idle 100ms / Moderate 500ms / Fast fling 1500ms debounce |
| Pixel-based warm window | avgItemHeight → ahead/behind counts instead of row-count multiplier |

---

## Sprint History

| Sprint | What shipped |
|--------|-------------|
| S1-17 | Core app: feed, profiles, threads, notifications, compose, relay management, settings shell |
| S18 | Relay health, session teardown, DB index, FIFO pruning, reconnection, lifecycle awareness |
| S19 | Repost profiles (p-tag), NIP-65 outbox, pagination, profile staleness TTL, connection cap |
| S20 | ImetaParser (NIP-92), markdown renderer, MediaExtraction optimization |
| S21 | Inline video autoplay, shared ExoPlayer, fullscreen dialog |
| S22 | Video overlay→shared player refactor, default Following feed, Amber signing |
| S23 | Zap sats display, profile tabs, following/followers count, Room v4-v6 |
| S24 | Thread nesting, follow/unfollow, kind 0/3/10002 publish |
| S25 | Coverage ledger, NIP-51 relay ecosystem, Room v9-v10 |
| March 21 | Stabilization: AMOLED theme, immersive scroll, video fixes, OG cards, media grid, mentions, quote cards, article reader, split feed subs |
| March 31 | Mega sprint (20+ commits): ProfileResolver centralization, relay purpose map, FeedStateReducer+blue dot, bug polish, inline @mentions, Notes/Conversations tabs, relay feed gaps, ExoPlayer perf, auth spam suppression, logout process restart, auto-drop grey tint, engagement coalescing, profile fetch throttle |
| April 1 | Perf: engagement coalescing (Channel.CONFLATED + 2s), profile fetch throttle (1s), OG fetcher .use{} leak fix. UI: tab row immersive scroll, conversation threading (produceState + lookupEvent), bridged content rendering, profile bio NostrRichText, unified quote/parent card style. HydrationFrontier Phase 1+1.5: viewport-driven hydration replacing CardHydrator (WarmWindow, Room subtraction, priority shedding, Mutex-serialized plan, velocity-aware cadence 100/500/1500ms, pixel-based warm window, planner logging) |

---

## TODO — Remaining Features

1. WoT NIP-85 Brainstorm integration (via antiprimal.net)
2. Expanded reactions panel (Amethyst-style)
3. NWC wallet integration (Nostr Wallet Connect, one-tap zaps)
4. Language filter (UnifiedFilter + ML Kit detection in EventProcessor)
5. Engagement drawer (avatar-only rows for zaps/reposts/reactions)
6. NIP-50 search improvements
7. NIP-96 file storage/upload (Blossom)
8. NIP-36 sensitive content (blur overlay, tap to reveal)
9. NIP-09 event deletion (long-press own note → publish kind 5)
10. Deep linking (nostr: URI handling from external apps)
11. Image fullscreen pinch-zoom viewer
12. Share sheet (share notes externally)
13. Longform article editor (kind 30023 compose screen)
14. Settings tabs functional (Wallet/NWC, Keys/nsec export minimum)

**Distribution:** F-Droid first → Zapstore → GitHub releases

---

## In Progress (Claude Code working)

- (none)

---

## ⚠️ CRITICAL RULES — Read Before Every Task

### 1. NEVER touch video rendering without explicit permission
Files: `InlineAutoPlayVideo.kt`, `VideoPlaybackScope.kt`, `NoteCard.kt` media section (poster frames, thumbnails, autoplay, active zone detection). Video was broken TWICE on March 31 by stale bug lists and speculative fixes.

### 2. Always verify bugs on device before fix prompts
Stale bugs in fix prompts caused the video rendering break. Always confirm the bug exists on current HEAD before writing a fix prompt.

### 3. Include DO NOT TOUCH list in every prompt
Every Claude Code prompt must have a `## ⚠️ DO NOT TOUCH` section listing files that must not be modified.

### 4. Diagnose before prescribing
Make Claude Code read the actual code FIRST, then propose fixes. Never send speculative fixes without reading the source.

### 5. Prefer caller-side guards over time-based debounce
Use `distinctUntilChanged()`, empty-set early returns, and state guards. Only use time-based debounce when proven necessary by log analysis.

### 6. key(feedKey) on LazyListState kills video autoplay
Adding `key(feedKey)` to LazyListState recreates the state on every feed switch, destroying the scroll observer that detects the active video. Never do this.

### 7. Two pointerInput modifiers on the same composable conflict
Use a single `awaitEachGesture` block instead of stacking multiple `pointerInput` modifiers.

### 8. .apply() loses race against exitProcess
SharedPreferences `.apply()` is async — it can lose the write if `exitProcess(0)` kills the process before flush. Always use `.commit()` for pre-kill writes.

### 9. Use reviewer feedback to tighten prompts
After each Claude Code session, review what went wrong and add guards to the next prompt.

### 10. Never carry stale bugs forward
Don't copy bug lists from old prompts without verifying each bug still exists on the current codebase.

---

## Key Patterns

**EventTemplate constructor** — Use for Quartz JVM 17 compat (not data class copy)

**Room query pattern for feeds:**
```sql
SELECT e.*, u.*, s.* FROM events e
LEFT JOIN users u ON u.pubkey = e.pubkey
LEFT JOIN event_stats s ON s.event_id = e.id
WHERE e.id IN (SELECT er.event_id FROM event_relays er WHERE er.relay_url IN (:relayUrls))
AND e.kind IN (:kinds)
AND (contentFilter logic)
ORDER BY e.created_at DESC LIMIT :limit
```

**followingFeedFlow** — Uses `e.pubkey IN (SELECT pubkey FROM follows)` subquery (not JOIN) to allow parent notes from non-followed authors in Conversations tab.

**Shared composables (internal visibility, cross-package):**
- `NostrRichText` — inline @mention rendering (NoteCard, ProfileScreen, UserProfileScreen)
- `AvatarImage` — IdentIcon + AsyncImage overlay (NoteCard, EventFeedItems)
- `relativeTime` — relative timestamp formatting (NoteCard, EventFeedItems)
- `displayName` — FeedRow extension for author name (NoteCard, ArticleCard)

**Engagement fetch pattern:**
```kotlin
Channel.CONFLATED → consumeAsFlow → collect { ids → fetchEngagementBatch(ids.take(20)); delay(2000) }
```

**HydrationFrontier plan cycle:**
```kotlin
snapshotFlow { ViewportSnapshot(keys, indices, avgHeight, velocity) }
    → debounce(scrollVelocityToCadence(velocity).debounceMs)  // 100/500/1500ms
    → distinctUntilChanged { range + cadence }
    → WarmWindow.from(keys, indices, events, avgItemHeightPx)  // pixel-based prefetch
    → HydrationFrontier.plan(window)  // Mutex, Room subtraction, priority shedding
```

**Logout pattern:**
```kotlin
bootstrapper.teardown()  // disconnect relays, clear ProfileResolver
keyManager.clear()       // .commit() not .apply()!
exitProcess(0)           // kill process to clear all singletons
```

---

## Source Structure

```
app/src/main/kotlin/com/unsilence/app/
├── data/
│   ├── auth/        KeyManager, SigningManager
│   ├── db/
│   │   ├── dao/     EventDao, UserDao, FollowDao, EventStatsDao, TagDao,
│   │   │            EventRelayDao, RelayConfigDao, NostrRelaySetDao, CoverageDao,
│   │   │            PinnedRelayDao
│   │   ├── entity/  All Room entities
│   │   └── AppDatabase.kt, DatabaseModule.kt, migrations
│   ├── relay/       RelayPool, RelayConnection, EventProcessor, ProfileResolver,
│   │                HydrationFrontier, CardHydrator (top-level extractors only),
│   │                OgFetcher, OutboxRouter, ImetaParser, MediaPreconnect
│   ├── repository/  EventRepository, UserRepository, ZapRepository
│   └── AppBootstrapper.kt
├── di/              Hilt modules
├── domain/model/    FeedRow, FeedFilter, FeedType, UserProfile, etc.
├── ui/
│   ├── feed/        FeedScreen, FeedViewModel, FeedStateReducer, NoteCard,
│   │                InlineAutoPlayVideo, VideoPlaybackScope, NostrRichText (internal)
│   ├── navigation/  AppNavigation, BottomNavBar
│   ├── onboarding/  LoginScreen, RootViewModel
│   ├── profile/     ProfileScreen, UserProfileScreen, ProfileViewModel
│   ├── search/      SearchScreen
│   ├── settings/    SettingsScreen, RelayManagementScreen
│   ├── thread/      ThreadScreen, ThreadViewModel
│   └── theme/       Colors, Typography, Sizing, Spacing
└── util/            LnInvoiceUtil, NostrRefParser, etc.
```
