# unSilence — Claude Code Context

**Package:** com.unsilence.app | **Path:** /home/aivii/projects/unsilence

---

## Process & Environment

- Runtime behavior changes MUST use human-in-the-loop validation (`VALIDATION_PROTOCOL.md`). Failed validation = revert first
- Scripted `adb shell input swipe` is NEVER valid. Measure before perf fixes (logcat, not code reading)
- Validation log lines: `Log.w`+ — R8 strips `Log.d`/`Log.v` in release
- **Users:** `aivii` (Android Studio, git) · `android-dev` (gradlew, code edits) · **Editor:** Neovim, never `nano`
- **ADB:** `/home/aivii/Android/Sdk/platform-tools/adb` · **Device:** Real Pixel (not emulator)
- **Build:** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug` · **Deploy:** `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- **NEVER** run `./gradlew` while Android Studio is open (Gradle lock conflict)

---

## Tech Stack

**Last verified against build files: 2026-06-13.**

Kotlin 2.3.0 · Compose BOM 2025.12.00 · Hilt 2.58 · KSP 2.3.0 · Media3 1.5.1+HLS · Coil 3.3.0 (15% RAM image cache) · Quartz 1.05.1 · AGP 8.9.1 · compileSdk 36 · JDK 17

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
**Feed reactivity:** `feedRows` from `_events × _contentFilter` only — no signal triggers. Per-card `profileFlow`/`statsFlow`.
**Dual-path:** Both EventProcessor and Subscription parse NIP-10 threading — otherwise replies leak into Notes tab.
**Shared:** `toEventJson` (NostrJson), `normalizeRelayUrl` (RelayUrlUtil), `parseNip10Threading` (EventProcessor), `findHashtags` (ContentParser).

---

## Critical Rules — Guardrails

- **NEVER touch video** (InlineAutoPlayVideo.kt, VideoPlaybackScope.kt) without permission. SurfaceView ignores alpha — conditional rendering, not `Modifier.alpha(0f)`
- **Single rendering pipeline** — EventCard sole composable. Never `remember` around `getOrParseEventModel()` (`computeIfAbsent` dedups, `remember` locks null)
- **Repost AuthorHeader:** skip `row.authorPicture`/`displayName`/`nip05` fallbacks for kind-6 so inner author never flashes reposter
- **pointerInput scoped to content area** — EventActionBar OUTSIDE long-press scope. Thread depth cap 6, no inline reply bar
- Engagement drawer toggle is **chevron only** — never re-add count-tap shortcut
- `firstSeenAt` is epoch **ms** (`System.currentTimeMillis()`, never /1000). Single clock read; derive `nowSeconds = nowMs / 1000L`
- Always populate threading for content kinds (1, 6, 9734, 9735, 20, 21, 30023)
- **Verify bugs on device** before fixing. **Diagnose before prescribing** — read actual code first
- `key(feedKey)` on LazyListState kills video autoplay. Logout: `isLoggedIn=false` BEFORE teardown
- Foreground-resume owned by single `UnsilenceApp` ProcessLifecycle observer — never register second

---

## Critical Rules — Concurrency

- **Every MES scan Flow MUST `flowOn(Dispatchers.Default)`** — CHM iteration on Main → ANR
- **`viewModelScope.launch {}` inside `collectLatest` does NOT cancel** — use `withContext(IO)` + guard
- **NostrEvent.relaysSeen MUST be `ConcurrentHashMap.newKeySet()`** — iterated on Default, mutated on IO
- **Never read concurrently-mutated map inside sort comparator** — TimSort throws. Snapshot first
- **Every external relay event MUST pass sig verify in `EventProcessor.handleEvent()`**
- **TimelineMerge.merge is sole merge path** — never `(new + old).distinctBy.sortedWith`
- **RelayPreferencesStore: `Mutex` read-modify-write**; **SnapshotScheduler onStop: 3s `withTimeoutOrNull`**

---

## Critical Rules — Relay & Protocol

- **NIP-42 auth relay-authoritative** — fresh challenge supersedes prior. **Give-up:** 3 `auth-required` CLOSEDs → `authUnavailableRelays`
- **`clearTransportStrikes(url)` in `RelayConnection.onOpen`** — single site covers all connect paths
- **Half-open breaker:** `shouldSkip` cooldown-gated past MAX_CAPABILITY_STRIKES (integral 60s→5m after 5 consec fails (H20b), DNS 5m→30m, timeout/TLS 1m→30m). `restricted` permanent
- **Network heuristics must never gate their own exit evidence** — degraded/down states TTL out (`dnsDegraded` 90s) and probe-drain `pendingReconnect` while still degraded; clearing requires a successful connect, so the gate can't block the connect that clears it (H20a)
- **User-initiated actions (publish, manual refresh) bypass network-state gates** — explicit intent ⇒ try NOW; `connectAndAwait` ignores `isNetworkDown`; honest failure beats refused attempt (H20c, cf. H18.4b)
- **Dead-relay denylist: DNS only** — `CONNECT_TIMEOUT` never increments `deadFailCount` (H18.4). `isNetworkDown` gates both strike paths. `consecutiveFailures` (any reason, reset on success) drives integral escalation (H20b)
- **No app-level DoH/DNS override** — OS/VPN resolver only; leak risk past VPN/Tor
- **`reconnectWithBackoff` defers when `isNetworkDown`** — `pendingReconnect` set, 60s sweep drains with jitter; while degraded, sweep still probe-drains 1-2 (DNS-failed preferred) so a probe-success clears the latch (H20a)
- **Publish is outbox-targeted** — `publish(eventJson, targetRelays)` sends to own write relays ONLY, not `connections.values`. Broadcast `publish(eventJson)` (reactions/reposts/profile/zap) still over-broadcasts — tracked follow-up (H20c)
- **One-shot:** `sendOneShotPooledOrEphemeral`, NEVER `connectAndAwait` (pool exhaustion). `MAX_HINT_RELAYS_PER_PASS` = 12
- **Map-before-close:** all close paths remove/replace map entry BEFORE `conn.close()`
- **`listenForEvents`:** rethrows `CancellationException`; finally gates `isActive`. Reconnect: `stillNeeded = !purposes.isNullOrEmpty() || url in activeSubUrls` — never `recentlyActive` (H8)
- **Ids-only filters include `kinds`** (purplepag.es rejects without); `"blocked"` CLOSED is transient — never strikes health (H19a)
- **`flushRelayQueue`:** `isRelayOutOfCooldown` (check), NOT `canSendToRelay` (consumes token). `normalizeRelayUrl` rejects whitespace/control chars
- `sendOneShotBatch` excludes `activeSingleRelayFeedUrl`; targeted id-fetches do NOT exclude
- Ephemeral connections never enter `connections` map. Indexer relays are PERSISTENT (before `connectAndAwait`)

---

## Critical Rules — Feed & Subscription

- **TimelineService sole feed primitive** — FeedVM, ProfileVM, UserProfileVM delegate. Outbox: N SubRequests ONE PER RELAY, max 10 write relays, top-3 FAST
- EOSE watchdog: ≥1 event then 2.5s silence → synthetic EOSE; 30s ceiling
- `setupSubscription(key, resetView)` — `resetView=true` user-initiated; `false` metaVer bumps
- `Subscription.parseEvent()` MUST populate `replyToId`/`rootId` via `parseNip10Threading()`
- `feedRows` NO signal triggers — adding any → full-list recompute on every kind-0/7/9735
- **Warm-zone hydration:** viewport-driven (Following 10/50/12, churny 10/30/6 — don't widen churny). No per-card fetches
- **Global cache:** `recentEventsWithDisplayableFloor` 100 displayable roots, `scanCap=1000`
- **`loadMore`→`fetchOlderTimeline`** (cache-first, relay backfill). **Engagement coalesced per-relay** (chunk=5, max 25)
- **Pull-to-refresh:** 1.5s debounce; `_isRefreshing` in-flight guard

---

## Critical Rules — Mute & Moderation

- **Optimistic mute floor** rejects stale kind-10000. **Replaceable guard:** `newerExists` at TOP of `handleMuteList`
- **`muteListDecryptCallback` wiring** — BEFORE `snapshotScheduler.restoreIfPresent()`
- **Word-mute/spam NEVER run on kind-6/16 content** (NIP-18 JSON, not user text) — mute reposter/event-id/hashtags instead (H16)
- Long-press: `PointerEventPass.Initial` + `awaitFirstDown(requireUnconsumed = false)`

---

## Critical Rules — Media

- Aspect ratios layout-locked after first compose. ONE default→resolved update, then locked
- ImageDimensionCache clamps 0.2f..5.0f; zero-height guard. OG aspect 16:9 all states
- OgFetcher: NEVER set Accept-Encoding (OkHttp handles transparent decompression)

---

## Critical Rules — Memory Bounds

- **`DERIVED_ONLY_KINDS` (H9):** kind-30166 skip `eventsById`/`idsByKind`, populate compact `relayMonitorsByUrl`. Pattern: raw→derived→discard
- **Profile eviction anchors `idsByPubkey` (H19c):** skip pubkeys with events in MES. Own + followed + top 500 recent; content: LRU-by-touch bands
- VideoThumbnailCache: ½ dims, no JPEG round-trip, 48MB/30 entries
- **feedRowCache `LruCache(1000)`** retains across slice swaps (H7). FeedVM `profileCache`/`statsCache` `LruCache(500)` synchronized. Never unbounded CHM
- Actor indexes: 1000 actors LRU, 500 targets/actor, ownPubkey anchored
- **TimelineService cache persisted** — snapshot V13 (events carry tagsJson; ≤V12 reader reconstructs), `INITIAL_CACHE_EMIT_CAP`=60, `PERSISTED_REFS_CAP`=500
- **Maintenance trims are gated** — actor/feedRow trims every 64th call; profile trim backs off 60s when a pass evicts 0 (anchored-over-cap livelock: 7.5min restore). Never call a scan-trim per insert
- Kind-9735 `event.pubkey` is LNURL signer — use `parseZapDescription`. Notif index: `notifIdsByRecipient` per-recipient signals
- **ContentParser DoS bound (H-spam):** INPUT `take(20k`, kind-30023 `200k)` BEFORE tokenize (regex pass is O(content)); SEGMENT `take(150)`, tail→one `"… [content truncated]"` text node; `EventModel.truncated`→chip, NO tap-to-expand (re-creates the freeze). `PARSE-HEAVY` probe permanent. Rotating-npub sybil defeats per-pubkey mute — the bound is the durable defense

---

## Critical Rules — Snapshot

- Kind-3 direct-path via `updateFollows` (not EventProcessor). Follows-first: `---FOLLOWS---` before `---EVENTS---`
- `connectAndAwait` BEFORE snapshot restore. Freshness = file mtime, NOT event createdAt
- `@Volatile restored` prevents premature saves. `insertBatch` coalesces signals (≤5/batch)

---

## Performance Audit

Measure first. Logcat → `grep ' PID  PID '` → `awk`/`sort`/`uniq -c`. Key greps: `"token exhausted"` (rate-limit), `"CardHydrator:"` (hydration), `"MES/size"` (memory 60s), `Search EVENT received` after `closeSearch` (sub leaks).
