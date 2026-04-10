# unSilence — Claude Code Context

**Last updated:** April 10, 2026 (design polish sprint complete: 8 items shipped)
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
| Room | 2.7.1 (v18, 14 tables) |
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
Relay WebSocket → EventProcessor → Room DB → Flow/StateFlow → Compose UI
```

**Core principle:** Room-first, 0ms screen render, network fills gaps invisibly.

### Key Subsystems (read code for details)
- **EventProcessor** — dedup via seenIds, kind handlers, spam filter, relay provenance
- **ProfileResolver** — batched profile fetch, 6h staleness, 15s in-flight guard
- **RelayPool** — WebSocket manager, ConnectionPurpose (PERSISTENT/BROWSE/OUTBOX), per-relay REQ queue (cap 10), token bucket rate limiter, idle eviction
- **FeedStateReducer** — MERGE at top / QUEUE when scrolled / APPEND pagination, blue dot, structural dedup
- **FeedHydrationController** — 5-state scroll machine (WARM_CATCHUP/SLOW_SCROLL/IDLE/FAST_SCROLL/REST), CardHydrator as stateless worker, velocity hysteresis, low-pass filter
- **VideoPlaybackScope** — shared ExoPlayer, viewport center activation (60%/35% hysteresis)
- **AppBootstrapper** — 3-phase staggered init, BackgroundSyncWorker (skeleton)

### Room v18 Tables
events, users, follows, reactions, event_stats, tags, event_relays, relay_configs, nostr_relay_sets, nostr_relay_set_members, coverage, pinned_relays, relay_trust_scores, sync_state

### Room Migrations
- Index names: `index_tablename_col1_col2` convention (backticks in SQL)
- Every migration index must also be declared in `@Entity(indices=[...])`

---

## Features — Shipped

**Feed:** Following/Global/Popular + relay-specific feeds, Notes/Conversations tabs, filter bottom sheet (type/time/engagement), infinite scroll, immersive scrolling, FeedStateReducer blue dot

**Content:** Kind 1 notes, kind 6 reposts (including bridged), kind 30023 articles (WebView reader), inline @mentions, embedded quotes (nevent/note/naddr), OG link previews, YouTube thumbnails, multi-photo/video grids

**Video:** Inline autoplay, shared ExoPlayer, fullscreen dialog, HLS support, mute toggle

**Profiles:** Avatar/banner/bio, edit profile, tabs (Notes/Replies/Longform), follow/unfollow, followers count (NIP-45), NIP-65 outbox

**Engagement:** Reactions, reposts, zaps (NWC NIP-47), zap persistence (kind-9734 → Room), action bar with share

**Relay:** NIP-51 ecosystem (10002/10006/10007/10012/30002), relay sets, trust scores (kind 30385), blocked relays

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
4. NIP-36 content-warning blur overlay (data already in Room)
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
2. **Verify bugs on device** before fixing — stale bug lists caused regressions
3. **Diagnose before prescribing** — read actual code first
4. **Prefer caller-side guards** over time-based debounce (distinctUntilChanged, empty-set returns, state guards)
5. **key(feedKey) on LazyListState kills video autoplay** — never do this
6. **Two pointerInput modifiers conflict** — use single awaitEachGesture block
7. **.commit() not .apply()** before exitProcess (async write loses race)
8. **Never carry stale bugs forward** — verify each bug exists on current HEAD

---

## Key Patterns

**Room feed query:** events JOIN users JOIN event_stats, kind 6 engagement resolves via `COALESCE(root_id, id)`, follows via IN-subquery (not LEFT JOIN)

**Shared composables:** NostrRichText, AvatarImage, relativeTime, ThreadParentCard, EmptyState, ActionButton, ZapButton

**Logout:** `bootstrapper.teardown() → keyManager.clear(.commit()) → exitProcess(0)`

**Relay config:** 5 indexers (purplepag.es etc.), 5 search (NIP-50), 6 global defaults, cap 13+3 browse

---

## Source Structure

```
app/src/main/kotlin/com/unsilence/app/
├── data/
│   ├── auth/        KeyManager, SigningManager
│   ├── db/          dao/, entity/, AppDatabase, migrations
│   ├��─ relay/       RelayPool, EventProcessor, ProfileResolver, CardHydrator, OgFetcher
│   ├── repository/  EventRepository, UserRepository, ZapRepository
│   ├── wallet/      ZapRepository, NwcManager, LnurlResolver
│   └── AppBootstrapper.kt
├── di/              Hilt modules
├── ui/
│   ├── common/      EmptyState, ShimmerNoteCard, IdentIcon, LoadingScreen, ImageRequestHelpers
│   ├── feed/        FeedScreen, FeedViewModel, FeedStateReducer, FeedHydrationController,
│   │                NoteCard, ArticleCard, ArticleReaderScreen, FilterScreen, ZapDialogs
│   ├��─ navigation/  AppNavigation
│   ├── compose/     ComposeScreen
│   ├── notifications/ NotificationsScreen
│   ├── profile/     ProfileScreen, UserProfileScreen, EditProfileScreen, SettingsScreen
│   ├── search/      SearchScreen
│   ├── relays/      RelayManagementScreen, CreateRelaySetScreen
│   ├── shared/      EventFeedItems, NotificationEventRow, ThreadParentCard
│   ├── thread/      ThreadScreen, ThreadViewModel
│   └── theme/       Color.kt (Surface0/1/2, Cyan, ZapAmber), Theme.kt (Spacing, Sizing, AppType)
└── util/
```
