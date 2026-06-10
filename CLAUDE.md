# unSilence — Claude Code Context

**Package:** com.unsilence.app | **Path:** /home/aivii/projects/unsilence

---

## Validation & Process

- Runtime behavior changes MUST use human-in-the-loop validation. See `VALIDATION_PROTOCOL.md`
- Scripted `adb shell input swipe` is NEVER valid for scroll/gesture testing
- Always measure before proposing perf fixes (logcat, not code reading)
- Failed validation = revert first, investigate second

---

## Environment

- **Users:** `aivii` (Android Studio, git) · `android-dev` (gradlew, code edits)
- **ADB:** `/home/aivii/Android/Sdk/platform-tools/adb`
- **Device:** Real Pixel phone (not emulator)
- **Build:** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug` (system JDK 26 breaks Kotlin)
- **Deploy:** `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- **Editor:** Neovim — never `nano`
- **NEVER** run `./gradlew` while Android Studio is open (Gradle lock conflict)
- **Release-build validation log lines must be `Log.w` or higher** — R8 strips `Log.d`/`Log.v` in release. A validation that depends on a `Log.d` line will read as a silent failure on the release builds we actually test on

---

## Tech Stack

Kotlin 2.3.0 · Compose BOM 2025.12.00 · Hilt/KSP 2.58 · Media3 1.5.1+HLS · Coil 3 (64MB cap) · Quartz 1.05.1 · AGP 8.9.1 · compileSdk 36 · JDK 17

---

## Design System

**Colors:** Background `#000000`, Surface1 `#0A0A0A`, Surface2 `#141414`, SurfaceVariant `#080808`, Accent Cyan `#00E5FF`, ZapAmber `#FFAB00`. Disabled alpha 0.38f. No light theme.
**Spacing (golden ratio):** micro=5, small=8, medium=12, large=20, xl=32, xxl=52 (dp)
**Type (`AppType` in Theme.kt):** caption=11, footnote=12, bodySmall=13, body=14, bodyLarge=15, subheading=16, heading=18, title=22, display=24 (sp)
**Sizing:** avatar=32, actionIcon=20, navIcon=20, topBarHeight=52, bottomNavHeight=52, mediaCornerRadius=8 (dp)

---

## Architecture

```
Relay WebSocket ─┬→ EventProcessor → MemoryEventStore → signal Flows → Compose UI
                 │                  └→ SnapshotScheduler (disk persistence)
                 └→ Subscription.parseEvent → TimelineService → FeedViewModel._events
```

**Core:** MES-only (in-memory ConcurrentHashMap), no Room/SQLite. V2 pipeline only (TimelineService + Subscription + flat-list outbox routing).
**Dispatchers:** WebSocket `IO.limitedParallelism(8)`, EventProcessor `Default.limitedParallelism(2)`, snapshot `IO.limitedParallelism(1)`.
**ContentParser:** lazy via `MES.getOrParseEventModel()` (`computeIfAbsent`). imeta dims + video render models remain eager.
**Feed reactivity:** `feedRows` derived from `_events × _contentFilter` only — no signal triggers. Profile/stats per-card via `profileFlow`/`statsFlow`.
**Dual-path event flow:** Both EventProcessor and Subscription parse NIP-10 threading — otherwise replies leak into Notes tab.

---

## Critical Rules — Guardrails

### Video
- **NEVER touch video** (InlineAutoPlayVideo.kt, VideoPlaybackScope.kt) without permission
- SurfaceView ignores parent View alpha — use conditional rendering, not `Modifier.alpha(0f)`

### EventCard Pipeline
- **Single rendering pipeline** — EventCard is sole composable for all surfaces
- Never wrap `getOrParseEventModel()` in `remember` — `computeIfAbsent` deduplicates, `remember` locks null
- **Repost AuthorHeader:** for kind-6, skip `row.authorPicture`/`displayName`/`nip05` fallbacks so inner author never flashes reposter
- **pointerInput scoped to content area** — EventActionBar sits OUTSIDE the long-press scope

### NostrEvent Construction
- `firstSeenAt` is epoch **milliseconds** — `System.currentTimeMillis()`, never /1000
- Single clock read — derive `nowSeconds = nowMs / 1000L` from one call
- Always populate threading for content kinds (1, 6, 9734, 9735, 20, 21, 30023)

### Correctness
- **Verify bugs on device** before fixing — stale bug lists caused regressions
- **Diagnose before prescribing** — read actual code first
- `key(feedKey)` on LazyListState kills video autoplay — never do this
- Logout: `isLoggedIn=false` BEFORE teardown
- Foreground-resume owned by single `UnsilenceApp` ProcessLifecycle observer — never register second

---

## Critical Rules — Concurrency

- **Every MES scan Flow MUST use `flowOn(Dispatchers.Default)`** — CHM iteration on Main → ANR
- **`viewModelScope.launch {}` inside `collectLatest` does NOT cancel** — use `withContext(IO)` + guard
- **NostrEvent.relaysSeen MUST be `ConcurrentHashMap.newKeySet()`** — iterated on Default, mutated on IO
- **Never read from concurrently-mutated map inside sort comparator** — TimSort throws. Snapshot first
- **Every external relay event MUST pass sig verify in `EventProcessor.handleEvent()`**
- **TimelineMerge.merge is the sole merge path** — never `(new + old).distinctBy.sortedWith`
- **RelayPreferencesStore read-modify-write uses `Mutex`** — prevents lost updates
- **SnapshotScheduler onStop: 3s `withTimeoutOrNull`** — prevents indefinite mutex block

---

## Critical Rules — Relay & Protocol

- **NIP-42 auth is relay-authoritative** — fresh challenge supersedes prior auth; never short-circuit
- **Auth give-up:** 3 consecutive `auth-required` CLOSEDs → `authUnavailableRelays`, exclude from fan-out
- **`clearTransportStrikes(url)` in `RelayConnection.onOpen`** — single site covers all connect paths
- **Half-open circuit breaker:** `shouldSkip` cooldown-gated past MAX_CAPABILITY_STRIKES (integral 60s, DNS 5m→30m, timeout/TLS 1m→30m). `restricted` permanent
- **Dead-relay denylist: DNS only** — only `DNS_RESOLUTION` increments `deadFailCount`. `CONNECT_TIMEOUT` is transient and must never contribute to the permanent denylist (H18.4)
- **`isNetworkDown` gates both strike paths** — DNS strikes suppressed when OFFLINE || dnsDegraded
- **No app-level DoH or DNS override** — the app uses the OS/VPN resolver. DNS failures handled by relay health/backoff (TTL + categorized cooldown). Bypass risks leaking DNS past the user's VPN/Tor
- **`reconnectWithBackoff` defers when `isNetworkDown`** — `pendingReconnect` set, 60s sweep drains with jitter
- **Hint/ref one-shot:** `sendOneShotPooledOrEphemeral` — NEVER `connectAndAwait` (Slice 8 pool exhaustion)
- **Hint fan-out capped:** `MAX_HINT_RELAYS_PER_PASS` (12) per hydration pass
- **Map-before-close contract:** all close paths remove/replace map entry BEFORE `conn.close()`
- **`listenForEvents` cancellation:** rethrows `CancellationException`; finally block gates on `isActive`, no `return`
- **`listenForEvents` reconnect gate:** `stillNeeded = !purposes.isNullOrEmpty() || url in activeSubUrls` — no recency check (H8). Never gate on `recentlyActive` — it creates self-perpetuating resurrection loops
- **Ids-only filters must include `kinds`** — some relays (purplepag.es) reject filters without kinds. Always add kinds to `{"ids":[...]}` fetch paths (H19a)
- **`"blocked"` CLOSED is TRANSIENT** — per-REQ policy rejection must not strike relay-level health. Sub-level rejection ≠ relay unhealthy (H19a)
- **`flushRelayQueue`:** uses `isRelayOutOfCooldown` (check only), NOT `canSendToRelay` (consumes token)
- **`normalizeRelayUrl` rejects whitespace/control chars** — chokepoint for all relay URL validation
- `sendOneShotBatch` excludes `activeSingleRelayFeedUrl`; targeted id-fetches do NOT exclude
- Ephemeral connections never enter `connections` map — no cap, no reconnect, no idle eviction
- Indexer relays are PERSISTENT — registered before `connectAndAwait`

---

## Critical Rules — Feed & Subscription

- **TimelineService is the sole feed primitive** — FeedVM, ProfileVM, UserProfileVM all delegate
- Outbox routing: N SubRequests, ONE PER RELAY. Max 10 write relays (greedy set-cover), top-3 FAST
- EOSE watchdog: ≥1 event then 2.5s silence → synthetic EOSE; 30s ceiling
- `setupSubscription(key, resetView)` — `resetView=true` for user-initiated; `false` for metaVer bumps
- `Subscription.parseEvent()` MUST populate `replyToId`/`rootId` via `parseNip10Threading()`
- `feedRows` has NO signal triggers — adding any reintroduces full-list recompute on every kind-0/7/9735
- **feedRowCache is LruCache(1000)** — retains rows across slice swaps (H7 fix). No manual eviction
- **Churny feed hydration trim (H9b):** Global/SingleRelay use warm 10/30/6 (above/below/lookahead); Following keeps 10/50/12. Don't regress churny feeds to Following's wider window
- **Global cache:** `recentEventsWithDisplayableFloor` scans MES for 100 displayable roots. `scanCap=1000`
- **`loadMore` routes through `fetchOlderTimeline`** (cache-first, relay backfill when cache exhausts)
- **Engagement REQs coalesced per-relay** via CardHydrator (chunk=5, max 25 relays)
- Warm-zone hydration: viewport-driven via CardHydrator (WARM_ZONE_ABOVE=10, BELOW=50). No per-card fetches
- **Pull-to-refresh:** 1.5s start-time debounce collapses mashing; `_isRefreshing` in-flight guard

---

## Critical Rules — Mute & Moderation

- **Optimistic mute floor guard** — `MES.muteListOptimisticFloor` rejects stale kind-10000 relay events
- **Replaceable event guard:** `newerExists` check at TOP of `handleMuteList`
- **`muteListDecryptCallback` wiring order** — BEFORE `snapshotScheduler.restoreIfPresent()`
- **No content-filter on kind-6/16 repost JSON envelope (H16)** — word-mute, spam heuristics must never run against repost content (NIP-18 JSON, not user text). Mute the reposter pubkey, reposted event ID, or hashtags; for word-mute on reposts, check the parsed target's display text. A bare `{` mute word once muted every repost in the app
- Long-press: `PointerEventPass.Initial` + `awaitFirstDown(requireUnconsumed = false)`

---

## Critical Rules — Media

- Aspect ratios layout-locked after first compose. ONE default→resolved update, then locked
- ImageDimensionCache clamps to 0.2f..5.0f; zero-height guard at all sites
- OG aspect ratio is 16:9 for all four states
- OgFetcher: NEVER set Accept-Encoding (OkHttp handles transparent decompression)

---

## Critical Rules — Memory Bounds

- **`DERIVED_ONLY_KINDS` (H9):** kind-30166 relay monitors skip `eventsById`/`idsByKind`, populate only compact `relayMonitorsByUrl`. Saved 80MB heap. Pattern: raw event → compact derived state → discard raw
- **Profile eviction anchors `idsByPubkey` (H19c):** `trimProfilesIfNeeded` skips pubkeys that have events in MES — don't evict profiles whose content is stored (evict-then-refetch is wasted work)
- VideoThumbnailCache: ½ source dims, no JPEG round-trip, 48MB/30 entries
- **feedRowCache:** `LruCache(1000)` — retains across slice swaps, bounds memory (H7)
- FeedVM `profileCache`/`statsCache`: `LruCache(500)`, synchronized. Never unbounded CHM
- Actor indexes: 1000 actors LRU, 500 targets/actor, ownPubkey anchored
- Profile eviction: own + followed + top 500 recent. Content eviction: LRU-by-touch band model
- **TimelineService cache persisted** — snapshot V12, `INITIAL_CACHE_EMIT_CAP`=60, `PERSISTED_REFS_CAP`=500
- Kind-9735 `event.pubkey` is LNURL service signer — use `parseZapDescription`, never `event.pubkey`
- Notification reverse index: `notifIdsByRecipient` per-recipient signals, no global recompute

---

## Critical Rules — Snapshot

- Kind-3 direct-path via `updateFollows` (not EventProcessor)
- Follows-first order: `---FOLLOWS---` before `---EVENTS---`
- `connectAndAwait` BEFORE snapshot restore
- Freshness = file mtime, NOT event createdAt
- `@Volatile restored` guard prevents premature saves
- `insertBatch` coalesces signal bumps (≤5 per batch)

---

## Critical Rules — Shared Utilities

- `toEventJson(event)` in NostrJson.kt — single shared function
- `normalizeRelayUrl()` in RelayUrlUtil.kt — top-level function
- `parseNip10Threading()` in EventProcessor.kt — shared by EventProcessor + Subscription
- `findHashtags()` in ContentParser.kt — structural walk, shared by parser + compose
- Thread depth cap 6; ThreadScreen has NO inline reply bar
- Engagement drawer toggle is **chevron only** — never re-add count-tap shortcut

---

## Performance Audit

Measure first. Logcat → `grep ' PID  PID '` → `awk`/`sort`/`uniq -c`.

| What | Command |
|------|---------|
| Rate-limit | `grep "token exhausted" \| grep -oE "wss://[^ ]+" \| sort \| uniq -c \| sort -rn` |
| Sub leaks | grep `Search EVENT received` after last `closeSearch` |
| Hydration | `grep "CardHydrator:"`, `grep "Phase1 profiles"` |
| Memory | `adb logcat -s "MES/size"` (60s emissions) |
