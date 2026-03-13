# unSilence Sprint 18-20: Bug Fix Context

> **For Claude Code:** Read this entire file before brainstorming. It contains the
> architecture assessment, bug list, fix plan, and codebase constraints. Do NOT
> blindly copy code from the spec file (`unsilence-spec_final_final.md`) — it uses
> Koin DI and generic Event types that don't match this codebase.
> Always check the ACTUAL source files before making changes.

---

## Codebase Constraints (non-negotiable)

- **DI:** Hilt only. No Koin. KSP, not kapt.
- **Events:** Quartz library types (`com.vitorpamplona.quartz.events.*`), not generic `Event` data classes. Use `EventTemplate` constructor pattern for JVM 17 compat.
- **Gradle config:** `compilerOptions` in top-level `kotlin {}` block, not `android {}`. Hilt+KSP use `id()` with hardcoded versions in root `build.gradle.kts` (not `alias()`).
- **Versions:** Kotlin 2.3.0, KSP 2.3.0, Hilt 2.58, Room 2.7.1, AGP 8.9.1, Gradle 8.11.1, Compose BOM 2025.05.00, compileSdk/targetSdk 36.
- **Build:** Never run `./gradlew` from terminal while Android Studio is open.
- **Git:** Operations from `aivii` user terminal, not `android-dev`.
- **Architecture:** Relay → EventProcessor → Room → Flow/StateFlow → Compose UI. UI never waits on WebSocket.
- **Subscriptions:** One-shot subs (profiles, threads, search) CLOSE after EOSE. Persistent subs (feed, notifications) stay open.
- **Dedup:** `seenIds` cache hashes raw messages before JSON parsing (~80% duplicate elimination).
- **Pruning:** ~750MB target, 800K row FIFO. Currently has composite DB indexes on events table.
- **Relay defaults:** Indexers: `purplepag.es`, `user.kindpag.es`, `indexer.coracle.social`. Search (NIP-50): `relay.noswhere.com`, `search.nos.today`. Global: `relay.damus.io`, `nos.lol`, `nostr.mom`, `relay.nostr.net`, `relay.primal.net`, `relay.ditto.pub`.
- **Room migrations must be non-destructive.** No `fallbackToDestructiveMigration()`.
- **Test on Android 16.0 Baklava emulator.**

---

## Architecture Assessment

### What's solid
- Dedup-before-parse (`seenIds`) — excellent optimization
- Composite DB indexes on events table — already fixed "Long db operation" issues
- One-shot vs persistent subscription model — clean separation
- `engagementId` pattern (`rootId ?: id`) — correct repost reaction handling
- FIFO pruning at 800K rows — reasonable strategy
- Clean-room build (not forked from Wisp) — no legacy relay machinery to fight

### Systemic issues found (bugs cluster around these)

**1. No WebSocket health management → causes bugs A10, A9, partially A1**
- `onFailure` just logs, no reconnection
- No ping/pong heartbeat for zombie connection detection
- No relay state tracking (connecting/connected/disconnected/failed)
- No lifecycle hook for foreground resume

**2. No login/logout orchestrator → causes bugs A1, A2**
- Logout is a multi-step teardown touching every layer, but no single coordinator
- Login bootstrap (profile + follows + relay list fetch) isn't triggered after Amber

**3. Fire-and-forget profile fetching → causes bugs A3, A4**
- Profiles fetched once with arbitrary timeout, not reactively observed
- Repost author profiles not included in fetch requests
- User profile views only query connected relays, not user's outbox relays

**4. One-shot feed queries → causes bug A5**
- Feed DAO returns `suspend fun: List<>` (snapshot), not `Flow<List<>>` (reactive)
- Room updates (zap count changes) don't trigger UI re-emission

### DB concerns
- Missing indexes: `rootId+createdAt`, `pubkey+kind+createdAt`, standalone `createdAt`, `contacts.ownerPubkey`
- `pruneIfNeeded()` runs `SELECT COUNT(*)` on every insert — should be periodic
- `rawJson` column uses ~800MB alone at 800K rows — exceeds 750MB target by itself (flag for future, don't fix now)

---

## Bug List — All 10 Active Bugs

### A1. Amber Login — AppBootstrapper not wired
- **Symptom:** Amber login loads pubkey but doesn't bootstrap profile/follows/relay list on real device
- **Root cause:** Amber callback stores pubkey but never triggers the bootstrap sequence (kind-0, kind-3, kind-10002 fetch + relay connect + persistent subscriptions)
- **Fix:** After Amber returns pubkey, call the same bootstrap path that nsec login uses

### A2. Logout doesn't fully clear Room/relay connections
- **Symptom:** Stale data persists after logout. Re-login shows previous user's feed
- **Root cause:** No coordinated teardown — missed steps across relay, Room, DataStore, coroutine layers
- **Fix:** Single `teardown()` function: cancel subs → disconnect WebSockets → clear Room tables → clear DataStore → cancel scopes → navigate to onboarding

### A3. Repost author profile pictures missing
- **Symptom:** Kind-6 reposts sometimes show no avatar for the repost author
- **Root cause:** Profile fetch only includes original note's author, not the reposter's pubkey
- **Fix:** When displaying reposts, fetch profiles for BOTH `event.pubkey` (reposter) AND inner event's author

### A4. User profiles show few posts
- **Symptom:** Opening a profile shows far fewer notes than expected
- **Root cause:** Only fetches from connected relays (not user's outbox relays), limit too low, subscription closes too early
- **Fix:** Fetch from user's NIP-65 outbox relays, increase limit, use cursor-based pagination with `until`

### A5. No zap UI feedback
- **Symptom:** Zap count updates in Room but UI doesn't reflect changes, no send confirmation
- **Root cause:** Feed queries are one-shot snapshots, not reactive Flows. `addZap()` updates Room but nothing re-emits to UI
- **Fix:** Switch feed DAO to `Flow<List<EventEntity>>`, add zap animation, add send confirmation snackbar

### A6. CreateRelaySet obscured behind status bar
- **Symptom:** Content hidden behind system status bar
- **Root cause:** Missing WindowInsets handling
- **Fix:** Add `Modifier.statusBarsPadding()` or proper Scaffold inset handling

### A7. Article image renders below action bar + raw markdown
- **Symptom:** NIP-23 articles show raw markdown syntax, images in wrong position
- **Root cause:** No markdown renderer, no NIP-23 header image parsing
- **Fix:** Add markdown rendering library (e.g., `multiplatform-markdown-renderer-m3`), parse `image` tag for header, render content through markdown composable

### A8. Videos from nostr.build show black — imeta parsing needed
- **Symptom:** Video URLs in imeta tags (NIP-92) not extracted, black rectangles shown
- **Root cause:** Media extraction only scans `content` for URLs, misses structured `imeta` tags
- **Fix:** Parse `imeta` tags: extract `url`, `m` (mime type), `dim`, `blurhash`. Feed video URLs to ExoPlayer. Also fix FeedScreen video detection (currently maps ALL notes as video candidates)

### A9. Notifications subscription closes after EOSE
- **Symptom:** Notifications load once but don't update in real-time
- **Root cause:** `notifs-` prefix treated as one-shot, CLOSE sent after EOSE
- **Fix:** Exclude `notifs-` from `isOneShotSubscription()`. Must stay open like feed subscriptions

### A10. Background→foreground: dead WebSockets not reconnected
- **Symptom:** Feed stops updating after backgrounding the app
- **Root cause:** OkHttp doesn't auto-reconnect, app doesn't detect dead connections on resume
- **Fix:** Lifecycle observer on `ON_START` → check connection state → reconnect dead sockets → re-subscribe persistent subs

---

## Sprint Plan

### Sprint 18: Foundation Fixes (systemic issues)

**Task 1: RelayConnectionManager**
- New component layered on top of existing relay infrastructure
- Per-relay state tracking enum: CONNECTING, CONNECTED, DISCONNECTED, FAILED
- Exponential backoff reconnection on `onFailure` (1s, 2s, 4s, 8s, max 30s)
- `ProcessLifecycleOwner` hook: on `ON_START`, check all relays, reconnect dead ones
- After reconnect: re-subscribe all persistent (non-one-shot) subscriptions
- Expose `connectionState: StateFlow<Map<String, RelayState>>`
- Do NOT replace existing subscription lifecycle — add on top
- **Also fix A9:** Verify `notifs-` prefix excluded from `isOneShotSubscription()`
- **Resolves:** A10, A9

**Task 2: SessionManager (bootstrap + teardown)**
- New component injected via Hilt
- `bootstrap(pubkey)`: store pubkey → fetch kind-0/3/10002 → connect user relays → open persistent feed + notification subs
- `teardown()`: cancel subs → disconnect WebSockets → clear Room → clear DataStore → cancel scopes → navigate to onboarding
- Wire `bootstrap()` into Amber login callback (same path nsec uses)
- Wire `teardown()` into existing logout button
- **Resolves:** A1, A2

**Task 3: Database Indexes**
- Room migration (bump version, non-destructive)
- Add indexes:
  - `idx_events_rootId_createdAt ON events(rootId, createdAt)`
  - `idx_events_pubkey_kind_createdAt ON events(pubkey, kind, createdAt)`
  - `idx_events_createdAt ON events(createdAt)`
  - `idx_contacts_ownerPubkey ON contacts(ownerPubkey)`
- Verify with `EXPLAIN QUERY PLAN` in debug

**Task 4: Fix Pruning Schedule**
- Remove `pruneIfNeeded()` from insert path
- Add in-memory counter, trigger prune every 1000 inserts (or periodic coroutine every 5 min)
- Verify insert throughput improves, DB doesn't grow unbounded

### Sprint 19: Profile & Feed Reactivity

**Task 5: ProfileCache Refactor**
- `getOrFetch(pubkey): Flow<ProfileEntity?>` — emit cached immediately, fetch if missing/stale
- For reposts: include both reposter pubkey AND original author pubkey in fetch
- For user profiles: fetch from user's NIP-65 outbox relays, increase limit, cursor-based pagination
- **Resolves:** A3, A4

**Task 6: Reactive Feed Queries**
- Change feed DAO returns from `suspend fun: List<>` to `Flow<List<>>`
- Room auto-re-emits when events table changes (inserts, zap count updates)
- ViewModel collects Flow, maps to UI models
- Add zap animation (amber flash/bounce on ⚡)
- Add zap send confirmation snackbar
- **Resolves:** A5
- **Risk:** Touches every ViewModel that queries events. Change carefully.

### Sprint 20: Media & Content

**Task 7: imeta Tag Parser (NIP-92)**
- Parse `["imeta", "url ...", "m video/mp4", "dim ...", "blurhash ..."]`
- Extract url, mime type, dimensions, blurhash
- Feed video URLs to ExoPlayer alongside content-extracted URLs
- Fix FeedScreen video detection: add `hasVideo(note)` check (currently maps all notes)
- **Resolves:** A8

**Task 8: Markdown Renderer for NIP-23**
- Add `com.mikepenz:multiplatform-markdown-renderer-m3` or similar
- Parse `image` tag for header image, render at top
- Render `content` through markdown composable instead of `Text()`
- **Resolves:** A7

**Task 9: CreateRelaySet WindowInsets**
- `Modifier.statusBarsPadding()` or proper Scaffold inset handling
- **Resolves:** A6

---

## After Sprints 18-20 → Language Filter (Sprint 21)

Separate context file will be provided. Uses ML Kit for detection, `language` column on events, detection in EventProcessor pipeline before Room insert, `LanguageFilterRow` chips below feed top bar, DataStore preference for selected languages.
