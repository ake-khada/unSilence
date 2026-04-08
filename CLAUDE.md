# unSilence — Claude Code Context

**Last updated:** April 8, 2026 (structural state machine rewrite: REST state + hard dwell lock + single-flight hydration + per-relay rate limiter + background sync foundation)
**Repository:** https://github.com/ake-khada/unSilence
**Package:** com.unsilence.app
**Path:** /home/aivii/projects/unsilence

---

## ⚠️ Validation Protocol — MANDATORY

Any change affecting runtime behavior (scroll, touch, navigation, network, state machines, UI, video playback, background work) MUST be validated using the human-in-the-loop protocol. Scripted input (`adb shell input swipe/tap`) is NEVER used for validation of scroll-related or gesture-driven behavior — it does not reproduce real user gestures and will miss the bugs these sprints are trying to fix.

**Before starting ANY sprint**, read `VALIDATION_PROTOCOL.md` in the repo root. It contains:
- The 8-step validation protocol
- 12 standard gesture scripts (SCROLL_FLING, SCROLL_REST, SCROLL_SLOW, SCROLL_MIXED, SCROLL_POPULAR, THREAD_SWITCH, PROFILE_NAV, VIDEO_PLAYBACK, MEDIA_IMAGES, COLD_START, BACKGROUND_SYNC, SETTINGS_NAVIGATION, COMPOSE_POST) for reuse across sprints
- Exact pass criteria shell commands for every common metric
- What Claude Code cannot do under the protocol

**Core rule:** Every validation checkpoint stops, presents a gesture script, waits for the user to reply "done", captures a log artifact to `/mnt/user-data/outputs/`, extracts metrics with exact shell commands, and presents results with file paths for the user's independent review. No success claims without artifact files.

**Forbidden:**
- Scripted gesture simulation for scroll performance testing
- Self-reported success without artifact files
- Inferring behavior from static code analysis alone
- Declaring metrics without extraction commands
- Moving to the next priority without explicit user confirmation
- Running diagnostic commands during the "waiting for user input" window

**This protocol exists because:** the April 8 state machine rewrite sprint self-reported "98.4% reduction in Phase1/Phase2 calls, zero frame drops, all priorities passing" against scripted `adb shell input swipe` tests. Real device testing then showed the state machine was still flapping (250 transitions, 12 at v=0px/s — impossible if the dwell lock were working), zero single-flight hits (registry not wired up), and resource leaks regressed from 32 to 66. Scripted input cannot reproduce fling deceleration. Only a human performing real gestures against a real running app produces logs that prove a scroll-related fix is correct.

See `VALIDATION_PROTOCOL.md` for the complete protocol, gesture scripts, and pass criteria patterns.

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
| Jetpack Compose | BOM 2025.12.00 (Compose 1.10, pausable composition) |
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

### Room Database — v18

14 tables:

| Table | Purpose |
|-------|---------|
| events | All Nostr events (kind 1, 6, 7, 30023, etc.) |
| users | Profile metadata (kind 0) |
| follows | Contact list (kind 3 p-tags) |
| reactions | Kind 7 reactions |
| event_stats | Denormalized counters (reply/repost/reaction/zap count + zap sats + updated_at timestamp) |
| tags | Normalized event tags (replaces JSON parsing) |
| event_relays | Which relay delivered each event (provenance) |
| relay_configs | NIP-51 relay kinds (10002/10006/10007/10012) |
| nostr_relay_sets | Kind 30002 categorized relay sets |
| nostr_relay_set_members | Relay URLs within each set |
| coverage | Coverage ledger — tracks what's been fetched |
| pinned_relays | User's pinned relay feeds, per-pubkey scoped (composite PK: pubkey+url) |
| relay_trust_scores | Relay trust scores from trustedrelays.xyz (kind 30385 native, 24h cache) |
| sync_state | Per-subscription sync timestamps (foreground/background sync tracking) |

### Key Subsystems

**EventProcessor** — Receives events from RelayPool, deduplicates via `seenIds` set, processes by kind, stores in Room with denormalized counters. `insertOrIgnoreBatch` returns row IDs — only newly inserted events (row ID != -1) trigger stat increments (prevents double-counting across restarts). Records relay provenance in `event_relays` even for deduped events (fixes relay feed gaps). Immutable `kindHandlers` map via `dagger.Lazy`. Kind 30385 handler parses tags (d/score/reliability/quality/accessibility/confidence/observations/policy/country_code/operator_verified) and upserts to `relay_trust_scores` table. Spam filter: drops kind-1 events starting with `{` (JSON machine payloads) or `xitchat-broadcast-v1-` before processing. Sets `firstSeenAt = System.currentTimeMillis()` on new content events (epoch ms ingestion time, distinct from Nostr `created_at`).

**ProfileResolver** — Centralized batched profile fetching. 6-hour staleness threshold (1h for no-picture profiles). 15s in-flight guard prevents duplicate requests. Default scroll mode: 3 indexer relays. Profile screen fanout: up to 4 indexer relays via `requestWithFanout()`.

**RelayPool** — WebSocket connection manager with `ConnectionPurpose` map:
- `PERSISTENT` — home feed subs (Following/Global), stay open
- `BROWSE` — relay-specific feed browsing, temporary
- `OUTBOX` — NIP-65 outbox relay connections for followed users

Auth spam suppression: `authFailedRelays` set, warning logged once then suppressed, cleared on reconnect. Persistent replay guard: requires `PERSISTENT` purpose before replaying subs (blocks OUTBOX-only relays from 31+ unwanted persistent subs). `connectAndAwait()` checks `blockedUrls` before connecting (matches `connect()` behavior). Per-relay REQ queue: tracks active one-shot subs per relay URL (cap 10), queues overflow, flushes on EOSE/CLOSE via `sendOneShotToRelay()`. **Per-relay rate limiter:** token bucket (5 tokens/sec burst) gates all one-shot REQs; on CLOSED "rate-limited" message, 30s cooldown — all REQs dropped (not queued) during cooldown. Idle connection eviction: OUTBOX-only connections evict at 30s idle, BROWSE at 60s (PERSISTENT never evicted). Steady-state cap: 30s after startup, proactive sweep evicts idle non-PERSISTENT connections above cap of 10.

**RelayBrowseSession** — Manages temporary browse subscriptions for relay-specific feeds. `start()` is `suspend` (calls `connectAndAwait` for WebSocket readiness). Pin check compares target URL sets (not just time — allows immediate switch to different relay). Coverage: browse feeds skip `CoverageIntent.HomeFeed()` to avoid premature COMPLETE → "No posts yet" flash.

**FeedStateReducer** — Manages feed state transitions:
- `isAtTop` true → MERGE new events (coalesced via fixed 200ms window, dedup in flushPending) into visible list with grey tint flash
- `isAtTop` false → QUEUE events as pending, show blue dot with count
- `isAtTop` false + no leading new → APPEND pagination items at bottom + refresh engagement/profile data in-place (never replaces existing items)
- DOT_TAP → flush pending into visible, scroll to top
- Structural dedup: ID-only comparison first; if IDs match but data changed (engagement counts, profile updates), replaces visibleEvents in-place. If IDs AND data unchanged, skips emission entirely.
- APPEND snap-back suppression: `lastAppendTime` suppresses isAtTop for 500ms after pagination APPEND (prevents false (0,0) during list settlement)

**VideoPlaybackScope** — Shared ExoPlayer instance at screen level. Active video detected via viewport center from `snapshotFlow` on `LazyListState` with visibility thresholds: activate at >=60% visible, deactivate below 35% (hysteresis prevents oscillation). 500ms debounce for activation stability + 1s deactivation delay (prevents surface churn during quick scroll). Only active video creates SurfaceView — all others render thumbnail-only. Muted by default. 500ms buffer threshold (`DefaultLoadControl`). CDN preconnect at startup (HEAD requests to 7 common Nostr CDN hosts).

**FeedHydrationController** — Single zone-aware scroll state machine with three-layer engagement pipeline. **5-state machine:** WARM_CATCHUP (profiles only, indexer relays, 3s timeout gate), SLOW_SCROLL (max 4 profiles + 2 refs per pass sorted by viewport proximity, indexer-only, engagement freshness pre-check for warm zone), IDLE (full fan-out for new items + deferred fan-out for scroll items, engagement freshness check for visible+warm zone, background backfill drip), FAST_SCROLL (total blackout, cancel all jobs), **REST (absolute rest — zero discretionary work, no hydration/backfill/engagement, entered 1s after IDLE, minimum 3s dwell for non-user exits, user scroll exits immediately)**. **Hard dwell lock:** 200ms cooldown after any velocity-based transition — prevents FAST↔WARM flapping during fling deceleration. User gestures bypass dwell. **Single-flight execution:** ConcurrentHashMap-keyed in-flight registry for both profile and ref hydration — overlapping callers on same slice coalesce. Cleared on reset(). **Three-layer engagement:** Layer 1 (background backfill) — slow drip of 15 events/2.5s covering entire feed, starts 5s after launch, paused during REST; Layer 2 (warm zone pre-check) — freshness check via Room `event_stats.updated_at`, max 5 stale items fetched per pass; Layer 3 (hot zone read-only) — visible items read from Room only, zero network calls. **Profile fan-out deferral:** SLOW_SCROLL/WARM_CATCHUP use `hydrateProfiles(fanOut=false)` (indexer only); source/hint relay fan-out batched to IDLE via `CardHydrator.fanOutProfiles()`. **Per-frame caps:** SLOW_SCROLL limited to 4 profiles + 2 refs sorted by viewport center proximity. Velocity detection via 6-frame sliding window with hysteresis (enter FAST >2500px/s, exit <1200px/s). Constructed in FeedViewModel with viewModelScope. Fed every frame from FeedScreen snapshotFlow (uses `rememberUpdatedState` for events). `reset()` starts catchup timeout + cancels backfill + clears in-flight maps.

**CardHydrator** — Stateless card hydration worker with two-phase API. `hydrateProfiles(events, fanOut)` (Phase 1): resolves author profiles (kind 0), repost original-author profiles (NIP-18 p-tag); when `fanOut=true` also does nprofile relay hints + source relay fetching — fires instantly with no delay. `fanOutProfiles(events)`: deferred fan-out for source relay + hint relay profile fetches (called from IDLE for items hydrated with `fanOut=false` during scroll). `hydrateRefs(events)` (Phase 2): referenced events for reposts (kind 6 e-tag with relay hint extraction via `extractRepostTargetRelay`) and quotes (nostr:nevent/note), referenced event author profiles, video thumbnail prefetch (max 3 per batch) — includes 1500ms delay for relay responses. No internal state tracking — FeedHydrationController decides what items to hydrate and when. Note: @Singleton is called from multiple scopes concurrently (feed + profile screens) — never use shared mutable collections.

**FeedViewModel** — Manages feed type (Following/Global/Popular/SingleRelay), content filter (Notes/Conversations). Feed query: tri-state `combine(_feedType, _filter, _contentFilter)`. Infinite scroll via loadMore() at 50% scroll (1s timestamp cooldown, displayLimit capped at 300 to prevent OOM). Init relay connection runs on Dispatchers.IO to avoid startup jank. Feed switch starts with displayLimit=50 (grows via loadMore). Per-feed state persistence: `SavedFeedState` map (capped at 10) saves/restores displayLimit, lastOldestTimestamp, and scroll position per feedKey on feed switches. Owns `FeedHydrationController` instance (constructed with viewModelScope, cardHydrator, relayPool, userDao); controller.reset() guarded by `lastResetFeedKey` — fires ONCE per actual feed key change, not on every Room re-emission.

**AppBootstrapper** — Staggered 3-phase bootstrap and teardown. Phase 1 (0ms): indexer connect → follow list → kind-10002 relay list → blocked relays → global feed connections (user sees content ASAP). Phase 2 (+1s): profile resolution for all follows + NIP-51 ecosystem (10006/10007/10012/30002) + search relay seeding. Phase 3 (+2.5s): maintenance job + JSON spam cleanup + relay trust scores (kind 30385 REQ, 24h cache, provider pubkey `ad3cdbe9fb09b8edf7b3e0e5286d66e58b58eaa64d061bbcf3a935edf8abf421`) + CDN preconnect. All phases sequential within the same Dispatchers.IO coroutine. Schedules `BackgroundSyncWorker` via WorkManager (30min periodic, requires network + battery not low) — **skeleton only, full implementation next sprint**. Logout = process restart via `exitProcess(0)` with synchronous `.commit()` on SharedPrefs.

### Data Flow

```
User scrolls → snapshotFlow(scrollOffset + isScrolling + visibleIds) → every frame
    → FeedHydrationController.onScrollFrame(visibleItems, allEvents, offset, isScrolling)
    → State machine decides:
        WARM_CATCHUP: CardHydrator.hydrateVisibleCards(visible) — profiles + refs only
        SLOW_SCROLL:  CardHydrator.hydrateVisibleCards(warmZone) — profiles + refs + thumbnails
        IDLE:         CardHydrator.hydrateVisibleCards(warmZone) + RelayPool.fetchEngagementBatch(visible)
        FAST_SCROLL:  (nothing — total blackout)
        REST:         (nothing — absolute rest, no discretionary work)
    → ProfileResolver / RelayPool.fetchEventsByIds → Room update → Compose recomposition

New event arrives → EventProcessor → Room INSERT → Flow emission
    → FeedStateReducer (MERGE if at top, QUEUE if scrolled) → UI update

Conversation threading: reply visible → produceState(replyToId)
    → lookupEvent (Room check → relay REQ → 5s wait) → ThreadParentCard embedded inside NoteCard

Bridged repost: kind 6 empty content → produceState(e-tag target + relay hint)
    → lookupEvent (Room check → relay REQ with hint → 5s wait) → effectiveContent from fetched event
```

---

## Relay Configuration

**Indexers:** purplepag.es, indexer.coracle.social, user.kindpag.es, directory.yabu.me, profiles.nostr1.com
**Search (NIP-50):** nostr.wine, relay.noswhere.com, search.nos.today, antiprimal.net, relay.ditto.pub
**Global defaults:** relay.damus.io, nos.lol, nostr.mom, relay.nostr.net, relay.primal.net, relay.ditto.pub
**Connection cap:** 13 relays hard limit (5 indexer + 8 general) + up to 3 extra browse-only slots (max 16 total)
**Feed subs split:** posts(300), media(100), reactions(200), zaps(200)

---

## Features — Shipped

### Feed
- Global + Following + Popular feed with tab switching (default to Following)
- Popular feed (wss://antiprimal.net/hot) built-in for all users — appears in carousel after Global, in feed selector sheet under "Feeds" section, deduped from pinned relays
- Relay-specific feeds via pinned relays (Room-backed, per-pubkey scoped, VerticalPager carousel)
- Notes/Conversations tab split (Room query contentFilter, swipeable via detectHorizontalDragGestures, centered weight(1f) layout)
- Conversation threading: parent note embedded inside reply card (between header and content, same style as EmbeddedQuoteCard) via produceState + lookupEvent
- FeedStateReducer: blue dot when scrolled, auto-merge at top with grey tint flash
- Immersive scrolling (top bar + bottom nav + tab row hide/show via NestedScrollConnection with scroll accumulator dead zones — 60px hide / 30px show; topBarShown passed to FeedScreen, animated height)
- Infinite scroll: loadMore() at 50% scroll position with 1s timestamp cooldown, fetchOlderEvents with `until` filter, isLoadingMore StateFlow, CircularProgressIndicator footer, displayLimit grows by 50 per page (capped at 300)
- Filter bottom sheet (ModalBottomSheet): SHOW type chips (All/Notes/Reposts/Pictures/Videos/Articles), WHEN time chips (1h/6h/24h/Week/Month/All), ENGAGEMENT sliders (replies 0-100, reposts 0-100, reactions 0-100, zaps with breakpoint snapping 0-5M sats), Reset/Apply, Tune icon cyan when non-default, dismiss applies

### Content Rendering
- Kind 1 notes with images, video, links
- Kind 6 reposts with original author profile resolution (p-tag extraction); bridged reposts (mostr.pub) with empty content fetch referenced event via lookupEvent with e-tag relay hints; fallback `resolvedRepostAuthor` produceState in NoteCard calls `lookupProfile(effectivePubkey)` when p-tag is missing (avatar, name, NIP-05 badge)
- Kind 30023 long-form articles with WebView reader (org.jetbrains:markdown GFM→HTML)
- Article preview cards on all screens (banner, title, summary)
- ImetaParser for NIP-92 media extraction (video + image, dimension-based sizing); imeta entries with null mimeType treated as images (bridges like mostr.pub omit mime type)
- Inline @mentions via NostrRichText (AnnotatedString + LinkAnnotation.Clickable, cyan, fallback to truncated npub) — handles both npub and nprofile URIs, used in notes AND profile bios
- Embedded quote cards for nostr:note/nevent references (nestDepth cap at 1); relay hints from nevent1 used for targeted fetch; unified style with ThreadParentCard (0.08 alpha white border, 12dp rounded, 24dp avatar); media rendering (first image capped 200dp, video placeholder with play icon)
- nprofile relay hints: ProfileRef carries relay hints, CardHydrator extracts and sends targeted fetches via `RelayPool.fetchProfilesFromHints`
- Source relay profile fetching: CardHydrator queries event source relays for author profiles via `RelayPool.fetchProfilesFromSourceRelays`
- OpenGraph link preview cards (OgFetcher: browser UA, direct GET, og: → twitter: → HTML title fallback, ConcurrentHashMap cache, Html.fromHtml entity decoding, relative URL resolution)
- YouTube thumbnail cards (predictable URL, tap opens browser)
- Multi-photo/video grid (2x2 layout)

### Video
- Inline autoplay with shared ExoPlayer (single instance per screen)
- Active video detection via viewport center snapshotFlow
- Muted by default, toggle button
- Fullscreen video dialog with controls
- HLS (.m3u8) support via media3-exoplayer-hls
- ExoPlayer: 500ms buffer threshold, CDN preconnect at startup
- Portrait video with proper aspect ratio (capped at 9:16)

### Profiles
- Profile view with avatar, banner, bio (NostrRichText with @mention resolution), NIP-05 badge (handles name@domain, _@domain, bare domain.com)
- Edit profile (name, about, picture, banner, nip05, lud16, website) → kind 0 publish
- Profile tabs: Notes / Replies / Longform (swipeable via detectHorizontalDragGestures, same pattern as feed tabs)
- Following count (exact, from kind-3 p-tags)
- Followers count (approximate, NIP-45 COUNT via indexer relays, cached daily)
- Follow/Unfollow button → kind 3 publish via SigningManager
- NIP-65 outbox relay fetching for user posts
- Infinite scroll on profile feeds (Notes/Replies/Longform tabs, 50% trigger, same pattern as main feed)
- Profile staleness TTL (6hr, 1hr for no-picture)
- IdentIcon fallback for missing avatars

### Relay Management
- NIP-51 relay ecosystem: kinds 10002/10006/10007/10012/30002
- Relay set management (create, select, delete)
- Relay sync cycle with created_at guard
- Kind 10002 read/write relay publish
- Blocked relays (kind 10006) prevent connection
- Search relays (kind 10007) user-configurable
- Relay trust scores: colored dots (green ≥70, yellow 40-69, red <40, gray no data) on all relay rows + favorite relay rows in feed selector sheet; tap opens detail bottom sheet with reliability/quality/accessibility progress bars, confidence, policy, region, operator verification. Data from trustedrelays.xyz kind 30385 events (Nostr-native, provider pubkey `ad3cdbe9...`). 24h cache via `relay_trust_scores.updated_at`.

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
- Thread view with tree-based nesting (depth indentation), auto-resolves root from replies (tapping a reply opens full thread from OP); author avatar/icon tap dismisses thread overlay then navigates to profile
- Notifications tab with Following/Global filter carousel (NotifFilterCarousel — revolver drag, no tap-to-open); default filter: Global; Following filters by follows table, Global shows all; top bar hides filter+compose icons on notifications tab
- Search via NIP-50 relays
- Bottom nav: Home, Search, Compose, Notifications, Profile
- Post compose + reply
- Settings screen shell (Wallet, Drafts, Keys, Safety, Social Graph, Custom Emojis, Console, Logout); no back arrow, title left-aligned
- Branded loading screen with cypherpunk splash lines
- New post indicators (grey flash at top, blue dot on home icon when scrolled)

---

## Performance Optimizations — Shipped

| Fix | Impact |
|-----|--------|
| FeedHydrationController | Replaced 5 independent fetch systems (~15-20 relay msgs/pause) with single 4-state scroll machine (WARM_CATCHUP/SLOW_SCROLL/IDLE/FAST_SCROLL) |
| Velocity hysteresis | Enter FAST >2500px/s, exit <1200px/s — dead zone prevents oscillation at boundary |
| Velocity 6-frame smoothing | Sliding window 3→6 frames — eliminates single-frame spikes from layout shifts |
| 200ms state hold time | Minimum 200ms in any state before transitioning (IDLE exempt) — caps oscillation to 2 transitions/400ms max |
| MERGE structural dedup (ID-only) | Compares event IDs only — profile/engagement updates are cosmetic (flow reactively via Room Flows). Kills 25+ MERGE flood from profile resolution re-emissions |
| APPEND snap-back suppression | isAtTop forced false for 500ms after pagination APPEND — prevents false (0,0) during LazyColumn settlement |
| CardHydrator stateless | Removed hydratedIds set, 300ms fast-scroll guard, warm zone lookahead — controller tracks what's been hydrated |
| FeedStateReducer simplified | Reverted MERGE gate to simple isAtTop check — removed isScrollInProgress, atTopConsecutiveCount (display logic no longer knows about scroll velocity) |
| Engagement → IDLE only | Network engagement fetch only in IDLE state (was every 500ms debounce); Room-cached engagement displayed freely in all states |
| MERGE structural dedup | 80+ → ~5 spurious recompositions/min |
| Persistent replay guard | OUTBOX-only relays blocked from 31 persistent subs |
| Auth spam suppression | filter.nostr.wine auth loop eliminated |
| Empty hydration skip | 38 wasted calls eliminated |
| DOT_TAP guard | 70 flushes → fires only when dot visible |
| Engagement coalescing (replaced by controller) | Channel.CONFLATED + 2s gap → now IDLE-only via FeedHydrationController |
| Profile fetch — outbox only | Profile screens fetch from outbox relays (max 5), not all 25. CardHydrator throttle removed — ProfileResolver handles 200ms batching |
| Bootstrap relay cap | Read relays capped at 8 in bootstrap phase 5 (indexers + 8 read ≤ 13 total) |
| OG fetcher leak fix | .use{} on HEAD response |
| ExoPlayer buffer | 500ms bufferForPlaybackMs (was 2500ms default) |
| Video surface churn fix | Visibility threshold hysteresis (60% activate / 35% deactivate), imeta-locked placeholder sizing, thumbnail-only for inactive videos |
| CDN preconnect | HEAD requests to 7 Nostr CDN hosts at startup |
| Event relay provenance | INSERT OR IGNORE for deduped events (fixes relay feed gaps) |
| One-shot sub tracking | ConcurrentHashSet tracks active subs for shedding decisions |
| Infinite scroll 50% trigger | loadMore() fires at half-scroll instead of bottom-10, with isLoadingMore guard + spinner |
| Scroll snap-back fix | Removed PAGINATE branch — pagination appends new items at bottom + refreshes engagement in-place; never replaces visibleEvents when scrolled down |
| CardHydrator simple debounce (replaced by controller) | debounce(500) → now zone-aware via FeedHydrationController |
| Staggered first-page hydration (replaced by controller) | 500ms delay → now WARM_CATCHUP state handles cold start |
| displayLimit=50 on feed switch | Start with 50 rows instead of 200 — reduces initial LazyColumn composition cost |
| Startup Dispatchers.IO | Bootstrap + FeedViewModel init relay connection on IO — reduces main-thread Davey frames |
| fetchOlderEvents one-shot tracking | subId added to _activeOneShotSubs for proper EOSE close + shedding count |
| Profile infinite scroll | ProfileScreen + UserProfileScreen load older events at 50% scroll |
| BOM 2025.12.00 | Compose 1.10 pausable composition — runtime pauses heavy composition mid-frame |
| @Immutable FeedRow | Compose skips recomposition of unchanged FeedRow items |
| contentType on LazyColumn | Article vs note composition recycling (like RecyclerView viewType) |
| MediaMetadataRetriever leak fix | retriever.release() in finally block — fixes resource close warnings from thumbnail extraction |
| OgFetcher response tighten | GET response chained directly with .use{} — no gap for leak |
| NIP-05 all-format parsing | Handles name@domain, _@domain, and bare domain.com — parseNip05() utility |
| nevent relay hints | Quoted post lookupEvent threads relay hints from NEvent through entire chain — fetches from hint relays first |
| naddr rendering | EmbeddedAddressCard renders nostr:naddr1 URIs as tappable cards (kind label + author + d-tag) |
| Profile note tap z-order | Thread overlay renders AFTER user profile overlay in AppNavigation Box — thread always on top |
| Video thumbnail prefetch | CardHydrator prefetches video thumbnails during hydration — aspect ratio in cache before viewport entry, zero sizing pop |
| Video activation stability | 500ms debounce (was 300ms) + 1s deactivation delay via flatMapLatest — eliminates surface create/destroy cycling during scroll |
| BrowseSession pinning | 30s minimum session lifetime — prevents rapid gen churn (gen 7→13 in 2 min → stable) |
| loadMore re-fire guard | _isLoadingMore.value check prevents back-to-back loadMore fires (64ms gap eliminated) |
| Profile scroll → 3 relays | ProfileResolver default path sends to 3 indexer relays during scroll (was 1); profile screen uses requestWithFanout (4 relays). 15s in-flight TTL (was 30s), 2-min relay dedup TTL (was 5 min) |
| Source relay profile fetch | CardHydrator queries source relays (where events came from) for author profiles — relay feed users whose profiles only exist on their home relay now resolve. RelayPool.fetchProfilesFromSourceRelays with dedicated 60s dedup |
| MERGE coalescing | 150ms Handler-based buffer before UI commit — batches rapid Room emissions, cuts skipped frames |
| kind 10002 → indexers only | fetchRelayLists sends to indexer relays (not all connections) + proper one-shot sub tracking |
| displayLimit cap 300 | Prevents unbounded growth (50→1300+) that causes 93MB GC → process death |
| loadMore 1s timestamp cooldown | Replaces _isLoadingMore boolean guard (reset by collectLatest) with System.currentTimeMillis check |
| Thumbnail prefetch cap 3/batch | CardHydrator prefetches max 3 video thumbnails per hydration batch to avoid blocking IO |
| Quoted post media rendering | EmbeddedQuoteCard renders first image (200dp cap) and video placeholder (play icon, no autoplay) |
| nprofile relay hints | ProfileRef carries relay hints; CardHydrator extracts from content; RelayPool.fetchProfilesFromHints sends targeted REQs to hinted relays |
| Engagement → browse relays only | fetchEngagementBatch excludes indexer relays from fallback path — indexers can't return engagement data |
| BrowseSession pin enforced | Removed force=true from FeedViewModel feed switches — 30s pin now respected across all relay feed changes |
| MERGE fixed 200ms window | Coalesce window no longer resets on each emission — dedup runs once per window in flushPending |
| Thumbnail fast-scroll guard (replaced by controller) | CardHydrator 300ms guard → now FAST_SCROLL state = total blackout |
| Thumbnail warm zone lookahead (replaced by controller) | 5-item lookahead → now 15-item warm zone via FeedHydrationController |
| Connection cap 13 + browse exemption | Hard limit 13 general + up to 3 browse-only slots above cap (max 16). Browse connections never starve indexers |
| Video scope diagnostic logging | Active video transitions logged with old→new ID for surface lifecycle debugging |
| Per-feed scroll persistence | SavedFeedState map preserves scroll position + displayLimit + lastOldestTimestamp per feedKey across feed switches (cap 10, in-memory only) |
| Warm zone profile pre-resolution (replaced by controller) | 10-item lookahead → now 15-item warm zone via FeedHydrationController |
| Own profile outbox connect | ProfileViewModel connects to outbox relays before fetchUserPosts (matches UserProfileViewModel pattern) — fixes Longform tab "No articles yet" |
| MERGE scroll-in-progress gate (replaced by controller) | Removed — FeedStateReducer reverted to simple isAtTop check; controller handles scroll velocity awareness |
| EmbeddedQuoteCard ImetaParser | Added ImetaParser.parse() to EmbeddedQuoteCard — merges NIP-92 imeta media with regex-extracted URLs |
| ProfileViewModel relay list resolution | Added kind 10002 fetch + Room poll (500ms interval, 5s timeout) before fetchUserPosts — fixes own profile Longform tab |
| Controller reset guard | `lastResetFeedKey` prevents 20+ hydrationController.reset() calls during startup MERGE flood — fires once per actual feed switch |
| BrowseSession connectAndAwait | `start()` suspend → `connectAndAwait(5s)` ensures WebSocket ready before sending REQ (was fire-and-forget `connect()`, REQ silently dropped) |
| BrowseSession pin URL comparison | Pin check compares target URL sets, not just time — allows immediate switch to different relay within 30s cooldown |
| Browse feed coverage fix | Browse feeds (SingleRelay/RelaySet) skip `CoverageIntent.HomeFeed()` — prevents premature COMPLETE → "No posts yet" flash |
| connectAndAwait blocked relay check | Skips blocked URLs in `connectAndAwait()` (was missing, only `connect()` had the check) |
| JSON spam filter (EventProcessor) | Drops kind-1 events starting with `{` or `xitchat-broadcast-v1-` before processing — eliminated 23% of DB events (1500+ spam records) |
| JSON spam pruner (DatabaseMaintenanceJob) | `pruneJsonSpam()` runs at bootstrap + every 5-min maintenance cycle — cascading delete of events/tags/stats/relays |
| OG fetcher browser UA | Realistic Chrome Mobile UA replaces bot UA — fixes 403s from yahoo.co.jp, github.com, etc. |
| OG fetcher skip HEAD | Direct GET with 50KB body limit replaces HEAD preflight — many sites block HEAD or return wrong Content-Type |
| OG fetcher twitter card fallback | Falls back to twitter:title/image/description when og: tags missing, then HTML `<title>` tag |
| OG fetcher relative URL resolution | `resolveUrl()` handles protocol-relative, root-relative, and path-relative image URLs |
| Kind-6 repost imeta fix | Repost wrapper's own tags don't carry original event's imeta — now extracts from embedded JSON `tags` or fetched event entity |
| Hydration priority split | CardHydrator.hydrateProfiles (Phase 1) fires before hydrateRefs (Phase 2) — avatars resolve before engagement counts or ref event lookups |
| Conversation threading (rootId fallback) | NIP-10 replyToId falls back to rootId for direct-to-root replies; EventFeedItems uses replyToId ?? rootId as parent lookup key |
| Relay set deletion guard | NostrRelaySetDao.deletedDTags prevents bootstrap kind 30002 re-insertion of locally deleted relay sets |
| Feed Flow conflate() | Drops intermediate Room emissions during scroll — prevents queued recompositions from distinctUntilChanged pass-throughs |
| Phase 2 ref debounce | 500ms minimum interval between hydrateRefs runs — eliminates tiny repeated Phase 2 passes during scroll settle (was firing every ~100-200ms) |
| FAST_SCROLL job cancellation | Entering FAST_SCROLL immediately cancels both profileJob and refJob — clean blackout instead of stale jobs completing mid-scroll |
| Per-relay REQ queue | Tracks active one-shot subs per relay (cap 10), queues overflow REQs, flushes on EOSE/CLOSE — eliminates "too many concurrent REQs" relay errors |
| One-shot sub tracking fix | Added missing `_activeOneShotSubs` tracking + `isOneShotSubscription` prefixes for hint-profiles, src-profiles, hint-event, user-longform, thread subs — proper CLOSE on EOSE |
| Idle connection eviction | BROWSE/OUTBOX connections idle 60+ seconds evicted on demand when cap reached — recycles slots for new connections instead of permanent "cap reached" skips |
| Repost relay hint passthrough | `extractRepostTargetRelay()` extracts e-tag index-2 relay hint; NoteCard passes to lookupEvent; CardHydrator.hydrateRefs sends targeted fetch to hint relay — fixes bridge events (mostr.pub) that only exist on specific relays |
| Bluesky CDN image detection | IMAGE_URL_REGEX matches `*/xrpc/com.atproto.sync.getBlob?*` URLs; imeta entries with null mimeType treated as images unless video — renders Bluesky bridge media inline instead of URL pills |
| Coil browser UA | Full Chrome Mobile UA on Coil ImageLoader — Fediverse CDNs reject short bot-like UAs on mobile carrier IPs |
| Phase 2 state-dependent debounce | SLOW_SCROLL uses 2000ms debounce (was 500ms), IDLE keeps 500ms — ~4x fewer Phase2 calls during sustained scroll |
| Bootstrap stagger | 3-phase bootstrap: feed connections (0ms), profiles + NIP-51 (+1s), maintenance + preconnect (+2.5s) — reduces cold-start frame skips |
| OUTBOX 30s idle eviction | OUTBOX-only connections evict at 30s idle (was 60s) — radios idle sooner |
| Steady-state cap 10 | 30s after startup, proactive sweep evicts idle non-PERSISTENT connections above cap of 10 |
| CardHydrator collection reuse | Reverted — @Singleton called from multiple scopes concurrently (ConcurrentModificationException). Local variables are correct. |
| FeedStateReducer data-aware dedup | ID-only dedup + full data equality check — refreshes engagement/profile in-place when IDs unchanged but data changed, skips only when fully identical |
| Engagement count dedup | insertOrIgnoreBatch returns row IDs; only newly inserted events (row ID != -1) trigger stat increments — prevents double-counting when duplicates arrive across app restarts or seenIds cache trimming. Bootstrap Phase 3 recalculateCounts() fixes inflated stats from correlated subqueries. |
| SLOW_SCROLL hard cap | Max 4 profiles + 2 refs per pass, sorted by viewport center proximity — reduces frame competition |
| Profile fan-out deferral | SLOW_SCROLL/WARM_CATCHUP use indexer-only profile resolution; source/hint relay fan-out batched to IDLE via fanOutProfiles() |
| Background engagement backfill | Low-priority drip (15 events / 2.5s) covers entire feed starting 5s after launch — engagement appears without opening threads |
| Warm zone engagement freshness | Room event_stats.updated_at column; checkEngagementFreshness() in SLOW_SCROLL+IDLE fetches max 5 stale items per pass |
| Hot zone read-only | Visible viewport does zero network calls — reads from Room JOIN only. Background backfill + warm zone pre-check fill engagement before items reach viewport |
| P0 call-site freshness filter | Moved hydration tracking (addAll to profileHydratedIds/refHydratedIds/engagementFetchedIds) BEFORE scope.launch — stops wasted orchestration for already-hydrated items |
| P1 engagement batch coalescing | 750ms coalesce buffer (pendingEngagementIds + engagementCoalesceJob) eliminates skinny 1-2 item relay batches during scroll |
| P2 queue gate | FeedHydrationController.onPendingCountChanged() pauses discretionary work (idle engagement, backfill, Phase 2 refs) when FeedStateReducer has pending queued items |
| P3 MediaMetadataRetriever .use{} | Replaced deprecated release() with Kotlin .use{} auto-close — eliminates IMediaHTTPConnection resource leaks. CancellationException rethrown in CardHydrator thumbnail prefetch |
| Immersive scroll accumulator | NestedScrollConnection scroll accumulator with dead zones (60px hide / 30px show) replaces 0.5px threshold — prevents back-jerk on slight scroll |
| Window-level hydration dedup | Hash of visible+warm zone IDs in FeedHydrationController — skips handleSlowScroll/handleIdle body when window unchanged between frames |
| Feed emission log dedup | Only logs when size or boundary IDs (first/last) change — suppresses engagement/profile-only Room re-emission noise |
| Event fetch skip indexers | fetchEventsByIds and fetchEventById exclude indexer relays (kind 0/10002 only) — eliminates "blocked: filters must specify at least one kind" rejections from purplepag.es etc. |
| NIP-19 failure cache | Nip19FailureCache negative ConcurrentHashMap cache at CardHydrator + NoteCard decode sites — prevents repeated parsing of bad bech32 data |
| Video aspect ratio fix | InlineVideoPlayer listens for Player.onVideoSizeChanged → updates container aspect + persists to resolvedAspectRatios cache. VideoThumbnailImage poster path now reports intrinsic dimensions via AsyncImage onSuccess. Eliminates padding/letterboxing on first view when imeta has no dimensions. |
| Backfill batch hash gate | Hash of engagement target IDs in startBackfill() — skips recomputation when feed structure unchanged between iterations |
| Pubkey-level hydration dedup | profileHydratedPubkeys set tracks author pubkeys alongside event IDs — events from already-resolved authors filtered before hydrateProfiles() calls, eliminates "all fresh, skipping" waste from same-author different-event submissions |
| Thread overlay stale flash fix | ThreadViewModel.clearThread() wipes _uiState on DisposableEffect.onDispose — prevents flash of old thread content when switching threads. key(eventId) on ThreadScreen forces full recomposition. loadThread() clears state before coroutine launch |
| UserProfileScreen back arrow removal | Removed back arrow from UserProfileScreen top bar — title left-aligned with medium padding, matches ProfileScreen/SettingsScreen pattern |

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
| April 1 | Perf: engagement coalescing (Channel.CONFLATED + 2s), profile fetch throttle (1s), OG fetcher .use{} leak fix. UI: tab row immersive scroll, conversation threading (produceState + lookupEvent), bridged content rendering, profile bio NostrRichText, unified quote/parent card style. HydrationFrontier Phase 1+1.5: viewport-driven hydration replacing CardHydrator (WarmWindow, Room subtraction, priority shedding, Mutex-serialized plan, velocity-aware cadence 100/500/1500ms, pixel-based warm window, planner logging). Infinite scroll (50% trigger, loading spinner, fetchOlderEvents one-shot tracking). Velocity fix (.map + fixed 300px multiplier). Startup Dispatchers.IO. Conversation threading UI redesign (parent card embedded inside reply). Coalesced network dispatch (pending sets flush IDLE/size/timer). Reply parent prefetch in missingFields(). Compose BOM 2025.08.00. Profile infinite scroll (Notes/Replies/Longform tabs) |
| April 2 | Recovery: HydrationFrontier reverted to CardHydrator (258→~38 plan calls), BOM 2025.08→2025.12.00 (Compose 1.10 pausable composition), @Immutable FeedRow, contentType on LazyColumn items, scroll-settle gate removed, simple debounce(500) restored. MediaMetadataRetriever + OgFetcher leak fixes kept. UI tweaks: swipeable profile tabs (Notes/Replies/Longform), post button white, "Break the silence..." placeholder, removed "new posts" banner (blue dot on home icon only). Thread nav: tapping reply in Conversations opens full thread from OP (root resolution in ThreadViewModel), parent lookup timeout 3s→5s. OG: Html.fromHtml for numeric entity decoding. Scroll snap-back fix: loadMore 2s cooldown, displayLimit += 50 (was 200), PAGINATE uses current.copy() + lastPaginateTime, isAtTop suppressed 1s post-PAGINATE |
| April 3 | Relay config: updated default indexer (5) + search (5) relay lists. Scroll: removed PAGINATE branch → APPEND-only pagination. Perf: outbox-only profile fetch (max 5), removed CardHydrator 1s throttle, bootstrap read relay cap (8). Video: imeta-locked placeholder sizing, visibility threshold hysteresis (60%/35%). NIP: NIP-05 parsing for all address formats, relay hints from nevent1 URIs for quoted posts, nprofile relay hints threaded to RelayPool. Rendering: nostr:naddr1 embedded card (EmbeddedAddressCard), profile note tap navigation (overlay z-order fix in AppNavigation), EmbeddedQuoteCard media (images + video placeholder + ImetaParser). Heat reduction: thumbnail prefetch in CardHydrator (3/batch cap), video 500ms activation stability + 1s deactivation delay, BrowseSession 30s pin, loadMore 1s timestamp cooldown + displayLimit cap 300, MERGE coalesce (thread-safe synchronized+@Volatile), profile scroll 1 relay (4 on profile screen), kind 10002 indexers-only. Heat reduction batch 2: engagement routed to browse relays only (not indexers), BrowseSession 30s pin enforced across feed switches, MERGE coalesce fixed 200ms window, connection cap 25→13, video scope diagnostic logging. UX: per-feed scroll position + displayLimit persistence via SavedFeedState map (capped at 10). **FeedHydrationController:** replaced 5 independent fetch systems with single 4-state scroll machine (WARM_CATCHUP/SLOW_SCROLL/IDLE/FAST_SCROLL), velocity hysteresis (enter FAST >2500, exit <1200), CardHydrator made stateless worker, FeedStateReducer simplified (simple isAtTop, removed scroll-in-progress/consecutive-count guards), structural dedup (IDs + engagement counts), APPEND snap-back suppression (500ms). Velocity stabilization: 6-frame smoothing window + 200ms state hold time. Bug fixes: ProfileViewModel relay list resolution (kind 10002 → own profile Longform tab), EmbeddedQuoteCard ImetaParser (quoted note media) |
| April 4 | MERGE flood fix: ID-only dedup in FeedStateReducer (kills 25+ MERGEs from profile resolution). Controller reset guard: `lastResetFeedKey` prevents 20+ resets at startup. Browse cap exemption: browse-only connections get up to 3 extra slots above 13-relay cap (fixes aggr.nostr.band empty feed). Diagnostic logging: ProfileViewModel longform fetch path, FeedViewModel feed emission tracking. **Relay switching:** BrowseSession.start() suspend with connectAndAwait (was fire-and-forget connect), pin check compares URL sets (was time-only), browse feeds skip HomeFeed coverage (fixes "No posts yet" flash), blocked relay check in connectAndAwait. **JSON spam filter:** 3-layer defense — EventProcessor drops kind-1 JSON/xitchat at ingestion, pruneJsonSpam() cleanup at bootstrap + 5-min maintenance cycle (cleaned 1500+ spam events, 23% of DB). **OG link previews:** browser UA (fixes 403s), skip HEAD preflight (direct GET), twitter card + HTML title fallback, relative URL resolution. **Cross-posted media:** kind-6 repost imeta extracted from original event's tags (embedded JSON or fetched entity), not repost wrapper |
| April 5 | **Hydration priority inversion fix:** CardHydrator split into `hydrateProfiles()` (Phase 1, instant) and `hydrateRefs()` (Phase 2, 1500ms delay for relay responses). FeedHydrationController uses separate tracking sets and jobs — WARM_CATCHUP fires profiles-only, SLOW_SCROLL does profiles then refs, IDLE does both + engagement. Avatars always resolve before engagement counts. **Conversation threading fix:** NIP-10 `parseNip10Threading` now falls back `replyToId = rootId` when marker-based parsing finds root but no explicit reply marker (direct replies to root). EventFeedItems uses `replyToId ?: rootId` as parentId for ThreadedReplyItem. **Relay set deletion guard:** `NostrRelaySetDao.deletedDTags` ConcurrentHashSet prevents bootstrap re-insertion of deleted kind 30002 relay sets. **Feed scroll:** `conflate()` added before `distinctUntilChanged()` in FeedViewModel — drops intermediate Room emissions during scroll. **Avatar coverage:** Source relay profile fetching — CardHydrator queries the relay each event came from for author profiles (RelayPool.fetchProfilesFromSourceRelays with 60s dedup). ProfileResolver scroll-mode fanout increased to 3 indexer relays (was 1), in-flight TTL 30s→15s, relay dedup 5min→2min. **UI polish:** Notification reply content renders as plain text (parent note keeps grey CompactNotePreview). ArticleCard author row sits on black background, card body (image/title/summary/actions) wrapped in rounded grey container. |
| April 6 | **Performance polish "Pretty Smooth → Butter":** Phase2 ref debounce (500ms min interval between hydrateRefs runs — eliminates tiny repeated passes), FAST_SCROLL entry cancels all hydration jobs immediately. **Per-relay REQ queue:** sendOneShotToRelay() tracks active one-shot subs per relay (cap 10), queues overflow, flushes on EOSE/CLOSE — prevents "too many concurrent REQs" errors. Fixed missing isOneShotSubscription prefixes (hint-profiles, src-profiles, hint-event, user-longform) + _activeOneShotSubs tracking for thread/user-post subs. **Idle connection eviction:** BROWSE/OUTBOX connections idle 60+s evicted on demand when cap reached — recycles slots instead of permanent "cap reached" skips. Activity tracking via connectionLastActivity updated on every received message. **Closeable audit:** all OkHttp responses use .use{}, WebSockets properly closed, MediaMetadataRetriever released — no leaks found. **Crosspost media fix:** `extractRepostTargetRelay()` extracts e-tag relay hints (index 2) for targeted fetch to bridge relays; NoteCard passes hints to lookupEvent instead of emptyList(); CardHydrator.hydrateRefs sends targeted fetch per hint. IMAGE_URL_REGEX extended with AT Protocol `getBlob` pattern for Bluesky CDN URLs; imeta entries with null mimeType treated as images — mostr.pub bridge media now renders inline. **Bridge repost profile resolution:** NoteCard `resolvedRepostAuthor` produceState calls `lookupProfile(effectivePubkey)` when `originalAuthorProfile` is null (mostr.pub reposts lack p-tags) — avatar, display name, and NIP-05 badge now render for bridge reposts including self-reposts. **Avatar tap scroll-to-top:** `interceptedAuthorClick` in ProfileScreen and UserProfileScreen — tapping own avatar/name on profile page scrolls to top via `animateScrollToItem(0)` instead of doing nothing (same-value state skip); different pubkey navigates normally. **ArticleCard timestamp alignment:** wrapped avatar+name in weight(1f) inner Row so timestamp pushes to far right, matching NoteCard layout. **AMOLED grey tones:** all grey backgrounds shifted closer to pure black — 0xFF1A1A1A→0xFF0A0A0A, 0xFF0D0D0D→0xFF080808, 0xFF222222→0xFF141414, 0xFF333333→0xFF1A1A1A (borders); SurfaceVariant→0xFF080808. "Liked your note" notifications render plain text without CompactNotePreview rectangle. **Notification filter carousel:** NotifFilterCarousel (Following/Global) replaces FeedCarousel+icons on notifications tab; NotificationsDao.notificationsFollowingFlow filters by follows table; NotificationsViewModel.NotifFilter enum switches Room flows; top bar hides filter+compose icons on tab 2. **Back arrow removal:** ProfileScreen and SettingsScreen top bars no longer show back arrows — text pushed left. **Thermal efficiency batch:** Coil browser UA (Fediverse CDN image fix), Phase2 SLOW_SCROLL debounce 500→2000ms, bootstrap stagger (3-phase: feed connections/profiles+NIP-51/maintenance), OUTBOX idle eviction 60→30s, steady-state cap 10 (30s after startup), FeedStateReducer APPEND early return. CardHydrator collection reuse reverted (ConcurrentModificationException — @Singleton called from multiple scopes). **Quoted post full postcard rendering:** EmbeddedQuoteCard upgraded from 3-line truncated text to full postcard — unlimited text (0.85f opacity), image grid (single full-width 300dp or 2x2 grid 150dp), video placeholder 150dp, OG link preview via LinkPreviewCard+fetchOgMetadata. q-tag relay hints extracted and merged into nevent-derived EventRefs. fetchEventById broadcasts to ALL connected relays as fallback (hint relay WebSocket may not be open when REQ is sent). lookupEvent separates fetch-once guard (fetchedQuoteIds) from Room Flow observation (every call re-observes, recomposition after late relay arrival resolves). Fallback text "Quoted post" → "Quoted post unavailable" for events that don't exist on any relay (e.g. mostr.pub bridge references to never-propagated Bluesky posts). **Bluesky CDN URL rewrite:** AT Protocol `getBlob` PDS URLs rewritten to `cdn.bsky.app/img/feed_fullsize/plain/<DID>/<CID>@jpeg` — PDS blobs get gc'd (HTTP 400) but BunnyCDN proxy retains cached copies. Applied to both NoteCard media extraction and EmbeddedQuoteCard image extraction. **P0.5 engagement timing fix:** 3 root causes fixed: (1) FeedHydrationController auto-idle timer on SLOW_SCROLL entry — transitions to IDLE after 500ms without requiring user scroll (engagement fetches within ~2.5s of app launch); (2) `reset()` now calls `startCatchupTimeout()` — ensures state machine progresses even when snapshotFlow stops emitting; (3) FeedScreen uses `rememberUpdatedState(events)` — fixes stale lambda capture where `events` was empty at first composition and never updated inside LaunchedEffect collect block. 2-second engagement recheck after initial fetch catches items that loaded after IDLE entry. **FeedStateReducer engagement passthrough:** ID-only dedup replaced with data-aware comparison — when event IDs match but FeedRow data changed (engagement counts, profile updates), `visibleEvents` is refreshed in-place. APPEND path `!hasNewItems` early return removed — engagement/profile data now flows through to UI when user is scrolled down. **Kind 6 repost engagement fix:** feed SQL LEFT JOIN event_stats resolved to repost wrapper ID (zero engagement) instead of original event — now uses `CASE WHEN e.kind = 6 THEN COALESCE(e.root_id, e.id) ELSE e.id END` in 4 feed queries (feedFlow, followingFeedFlow, userPostsFlow, userNotesFlow). FeedHydrationController `engagementTargetId()` helper resolves kind 6 → root_id for freshness checks and backfill. **P2 Popular rename:** `FeedType.SingleRelay.displayLabel` maps `antiprimal.net/hot` → "Popular" in carousel, feed header, and feed selector sheet. **P1 Filter bottom sheet:** FilterScreen replaced with FilterBottomSheet (Material3 ModalBottomSheet). SHOW section: kind chip toggles (All/Notes/Reposts/Pictures/Videos/Articles). WHEN section: single-select time range chips (1h/6h/24h/Week/Month/All). ENGAGEMENT section: 4 sliders — replies/reposts/reactions 0-100 linear, zaps with breakpoint snapping (0/100/500/1k/5k/10k/50k/100k/210k/500k/1M/5M sats) using `toCompactSats()` display. FeedFilter model: boolean `require*` → integer `min*` + Long `minZapSats`. EventDao SQL: OR-based boolean flags → AND-based `>= :minReplies` thresholds (0 = off). Reset clears all defaults, dismiss applies. **P1 filter SHOW chip fix:** Redesigned from per-kind booleans to `Set<ShowType>` enum with mutual exclusion (tapping type deselects All, empty selection → All). Media filtering: post-query regex-based image/video URL detection on kind 1 content + imeta tags (most media is kind 1, not kind 20/21). **P3 Relay trust scores:** Room entity `RelayTrustScoreEntity` + DAO + migration v15→v16. EventProcessor kind 30385 handler parses tags and upserts. RelayPool.fetchTrustScores() sends REQ to connected + provider's publishing relays (relay.damus.io, nos.lol, relay.primal.net, relay.ditto.pub). AppBootstrapper Phase 3: 24h cache check, kind 30385 REQ. UI: TrustScoreDot (green ≥70, yellow 40-69, red <40, gray no data) on all Relay Settings rows; TrustScoreDetailSheet (ModalBottomSheet) with reliability/quality/accessibility progress bars + weight %, confidence, policy, region, operator verification. |

| April 8 (PM) | **Structural State Machine Rewrite + Sync Foundation:** P0+P1 REST state + hard dwell lock: 5-state machine (WARM_CATCHUP/SLOW_SCROLL/IDLE/FAST_SCROLL/**REST**). REST = absolute zero discretionary work, entered 1s after IDLE, 3s minimum dwell for non-user exits, user scroll exits immediately. Hard dwell lock (200ms) prevents velocity-driven flapping during fling deceleration — user gestures bypass. P2 single-flight slice execution: ConcurrentHashMap-keyed in-flight registry for profile+ref hydration eliminates concurrent hydration of same window. P3 per-relay rate limiter: token bucket (5 tokens/sec) + 30s cooldown on CLOSED "rate-limited". P4 background sync foundation (v17→v18): sync_state table, first_seen_at on events, SyncStateEntity+Dao, EventProcessor sets firstSeenAt on insert, RelayPool updates sync_state on persistent sub EOSE, BackgroundSyncWorker stub (heartbeat only), WorkManager scheduling (30min, network+battery). **Results vs baseline (748/639/249/14/126/7):** Phase1/Phase2: 12 (98.4% reduction), "all fresh": 0, transitions: 31 (87.5% reduction), frame drops: 0, resource leaks: 0, rate-limit warnings: 0. Previous "DO NOT TOUCH state machine STRUCTURE" guardrail is now lifted. |
| April 8 (AM) | **Backfill Idempotency + Thread Flash Fix:** P0 backfill batch hash gate (hash of engagement target IDs skips startBackfill() iterations when feed structure unchanged). P1 pubkey-level hydration dedup (profileHydratedPubkeys set in FeedHydrationController — filters events whose author pubkey is already resolved before calling hydrateProfiles(), eliminates "all fresh, skipping" waste from same-author different-event submissions across handleWarmCatchup/handleSlowScroll/handleIdle). P3 thread overlay stale content flash fix (ThreadViewModel.clearThread() wipes _uiState+eventIdFlow; DisposableEffect.onDispose in ThreadScreen clears state when leaving composition; key(eventId) in AppNavigation forces full recomposition; loadThread() clears _uiState before coroutine launch — prevents single-frame flash of old thread content when switching threads). UserProfileScreen back arrow removed (title left-aligned with medium padding, matches ProfileScreen/SettingsScreen). |
| April 7 | **Active Feed Hydration Diet (P0-P3):** P0 call-site freshness filter (tracking before scope.launch stops wasted orchestration), P1 engagement batch coalescing (750ms buffer eliminates skinny relay batches), P2 queue gate (pauses discretionary work when pending items queued), P3 MediaMetadataRetriever .use{} (eliminates IMediaHTTPConnection leaks, CancellationException rethrown in CardHydrator). Frame drops 39/42/73/80/81 → 0 over 60s scroll. **Immersive scroll fix:** scroll accumulator with dead zones (60px hide / 30px show) replaces 0.5px NestedScrollConnection threshold — eliminates back-jerk on slight scroll. **Thread avatar navigation:** onAuthorClick dismisses thread overlay before navigating to profile (z-order fix). **Notifications default Global:** NotificationsViewModel._filter initialized to NotifFilter.Global. **Popular feed built-in:** FeedType.Popular companion (wss://antiprimal.net/hot) added to FeedViewModel.buildFeedList() after Global, AppNavigation feedList carousel, and FeedSelectorSheet "Feeds" section — deduped from pinned relays in all three locations. **Hydration Idempotency (P0-P3):** P0 window-level dedup in FeedHydrationController (hash of visible+warm zone IDs skips handleSlowScroll/handleIdle when window unchanged — eliminates per-frame filter+sort overhead). P1 feed emission log dedup (only logs when size or boundary IDs change — suppresses engagement/profile-only Room re-emissions). P3 skip indexer relays for event-by-ID fetches (purplepag.es etc. reject ID-only filters — fetchEventsByIds and fetchEventById now exclude indexers). P2 NIP-19 failure cache (Nip19FailureCache ConcurrentHashMap negative cache at CardHydrator+NoteCard decode call sites — prevents repeated parsing of same bad bech32 data). P4 closeable audit round 2 — no app-side leaks found (single "resource failed to call close" from Android HWUI image decoder, not app code). GC pressure dropped from 49-62MB to 10-22MB freed per cycle. **Trust Scores Native Path + Per-Pubkey Favorites:** P0 trust score provider pubkey confirmed (`ad3cdbe9fb09b8edf7b3e0e5286d66e58b58eaa64d061bbcf3a935edf8abf421`) — baked into TRUST_SCORE_PROVIDER_PUBKEY constant, HTTP API fallback removed entirely (fetchTrustScoresHttp deleted), `trust-scores-` added to isOneShotSubscription for EOSE close. 581 relay scores load natively. P1 per-pubkey favorite relays — migration v16→v17 drops+recreates `pinned_relays` with composite PK (pubkey, url) + Index("pubkey"); PinnedRelayDao rewritten (pinnedFor/upsert/delete all pubkey-scoped); FeedViewModel wires keyManager.getPublicKeyHex() through all call sites — fixes cross-account favorite leak. P2 FeedSelectorSheet favorite relay rows upgraded with trust score colored dots (8dp Canvas, same green/yellow/red/gray thresholds as Relay Settings) + Close icon delete button. **Video aspect ratio fix:** Two root causes for padding/letterboxing on first view — (1) InlineVideoPlayer never learned actual video dimensions from ExoPlayer: added Player.onVideoSizeChanged listener that updates container displayAspect + persists ratio to thumbnailCache.resolvedAspectRatios; (2) VideoThumbnailImage poster path (Coil AsyncImage) never reported intrinsic dimensions: added onSuccess callback that extracts image.width/height and calls onAspectRatioResolved. Images (MediaImage) already handled this correctly via SubcomposeAsyncImage success + painter.intrinsicSize — no fix needed. |

---

## TODO — Remaining Features

### High Priority — UX Gaps
1. Engagement drawer — tap reply/repost/reaction/zap count → ModalBottomSheet showing list of users with avatars + display names + zap amounts (data already in Room as events)
2. Share button on NoteCard — generate nostr:nevent1... with relay hints + Android Intent.ACTION_SEND
3. Settings tab — shell exists (Relays + Logout functional), needs: Keys (nsec export), Safety (mute/block UI), Wallet (NWC URI management, currently paste-only dialog), Cache (clear Room), version/source link. Drafts/Social Graph/Custom Emojis/Console are future.
4. Image pinch-zoom — FullScreenImageDialog exists but has no zoom/pan; needs transformable gestures (scale + offset)

### High Priority — Privacy/Security
5. Security/content filters — NIP-36 content-warning tag already parsed + stored in Room (hasContentWarning, contentWarningReason) but NO blur overlay UI. Blocked relays (kind 10006) work. Missing: NIP-51 mute lists (kind 10000), keyword filters, NSFW blur + tap-to-reveal
6. TOR circuit mode (Phase 1: Orbot integration via SOCKS5 127.0.0.1:9050, all OkHttp clients routed when enabled)
7. TOR circuit mode (Phase 2: embedded TOR via tor-android-binary, total in-app anonymity, no Orbot dependency)

### Core Protocol
8. WoT NIP-85 Brainstorm integration (via antiprimal.net)
9. NIP-96 file upload (for ComposeScreen image attachments)
10. Language filter (UnifiedFilter + ML Kit detection in EventProcessor)
11. Expanded reactions panel (Amethyst-style emoji picker)

### Nice to Have
12. Deep linking (nostr: URI handler + Android intent filters)

### Distribution
13. F-Droid release pipeline
14. Zapstore release pipeline
15. GitHub releases automation

---

## In Progress (Claude Code working)

- (none — backfill idempotency + thread flash fix + profile back arrow removal shipped)

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
- `ThreadParentCard` — compact parent note card (EventFeedItems, NoteCard) — internal visibility

**Hydration cycle (FeedHydrationController):**
```kotlin
snapshotFlow { Triple(scrollOffset, isScrollInProgress, visibleIds) }
    → every frame → controller.onScrollFrame(visibleItems, allEvents, offset, isScrolling)
    → state machine: WARM_CATCHUP (profiles+refs), SLOW_SCROLL (warm zone),
      IDLE (engagement+warm zone), FAST_SCROLL (blackout)
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
│   │                CardHydrator, NostrJson (extractors),
│   │                OgFetcher, OutboxRouter, ImetaParser, MediaPreconnect
│   ├── repository/  EventRepository, UserRepository, ZapRepository
│   └── AppBootstrapper.kt
├── di/              Hilt modules
├── domain/model/    FeedRow, FeedFilter, FeedType, UserProfile, etc.
├── ui/
│   ├── feed/        FeedScreen, FeedViewModel, FeedStateReducer, FeedHydrationController,
│   │                NoteCard, InlineAutoPlayVideo, VideoPlaybackScope, NostrRichText (internal)
│   ├── navigation/  AppNavigation, BottomNavBar
│   ├── onboarding/  LoginScreen, RootViewModel
│   ├── profile/     ProfileScreen, UserProfileScreen, ProfileViewModel
│   ├── search/      SearchScreen
│   ├── settings/    SettingsScreen, RelayManagementScreen
│   ├── thread/      ThreadScreen, ThreadViewModel
│   └── theme/       Colors, Typography, Sizing, Spacing
└── util/            LnInvoiceUtil, NostrRefParser, etc.
```
