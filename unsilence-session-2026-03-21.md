# unSilence — Session Spec: March 21, 2026

## Sign-in → Relay Browsing → Feed Switching

Everything discussed in today's session, from login flow to feed transition UX. Prompts from Ake are quoted verbatim where they drove architectural decisions.

---

## 1. Logout Flow

### Ake's design trigger:

> "The user triggers: Logout, page goes to login page. Actions behind the scene while this happens: the current profile condition that triggers a REQ for all pubkey's data: all kinds including relay data, block lists, following number, followers number and lists, badges and so on. Previous pubkey data is retained in the room, its tag is changed from current user to others' stuff."

> "I want you to think in terms of the above stated paradigm where the user initiates and all necessary reaction that is subtle and fast, yet complete and meaty."

### Spec

**User taps "Logout"**

**Instant (UI thread, <100ms):**
- `keyManager.clear()` — private key gone
- Navigate to onboarding screen — user sees login page immediately

**Behind the curtain (background coroutines, user doesn't wait):**
- **Demote old pubkey:** The old pubkey's data stays in Room but loses its "owner" status. A `currentOwnerPubkey` field (SharedPreferences or `app_state` table) is set to `null`. Every query that asks "what's MY profile / MY follows / MY relays" reads this field. Old data becomes just cached data from some npub.
- **RelayPool soft reset:** `disconnectAll()`, clear `persistentSubs`, `authenticatedRelays`, `authInFlight`, `pendingChallenges`. Don't destroy the pool — it gets reused on next login.
- **EventProcessor:** Clear `seenIds` — fresh dedup for next session.
- **VideoThumbnailCache:** Clear bitmap cache + `resolvedAspectRatios`.
- **BrowseSession:** `stop()` if active.
- **OutboxRouter:** `stop()` — kill follow-based routing.
- **SharedPlayerHolder:** `releaseAll()` — kill any active video.

**Room data is NOT deleted.** Old user's events, profiles, relay configs stay as cached data.

### Ake's constraint:

> "We should not delete the room data, we should designate it as not the one needed."

---

## 2. Login Flow (both paths)

### Two entry points, same bootstrap

**Private key login:**
- `keyManager.savePrivateKey(nsec)` → derives pubkey internally
- Events signed locally via Quartz + stored nsec

**Amber login (NIP-55):**
- App sends intent to Amber → Amber returns pubkey only
- `keyManager.savePublicKey(pubkeyHex, signerMode = AMBER)`
- Events signed by sending unsigned event to Amber via intent → Amber returns signed event
- Private key never touches unSilence

**Both paths converge:** `AppBootstrapper.bootstrap(pubkeyHex)` runs identically regardless of how the key was obtained.

### Bootstrap REQ

The same bootstrap filter sent to 4 indexer relays:
```json
{"kinds": [0, 3, 10002, 10006, 10007, 10012, 30002], "authors": ["newPubkey"]}
```

One filter, sent to each indexer relay as a separate REQ. All essential user data:
- **Kind 0** — profile metadata (name, avatar, banner, NIP-05, lud16)
- **Kind 3** — contact list (follow list, drives Following feed)
- **Kind 10002** — relay list metadata (read/write preferences, drives outbox routing)
- **Kind 10006** — blocked relays
- **Kind 10007** — search relays
- **Kind 10012** — favorite relays (populate relay dropdown)
- **Kind 30002** — relay sets (custom user-created relay sets)

Plus:
- **Followers count:** NIP-45 COUNT query: `{"kinds":[3],"#p":["newPubkey"]}`
- **Following count:** Count `p` tags from kind-3
- **NIP-05 badge:** Verify if present

After EOSE → close sub → `OutboxRouter.start()` with fresh kind-3 + kind-10002.

### Critical query fix

- `ProfileScreen`/`ProfileViewModel` must query the user profile by `keyManager.getPublicKeyHex()` at render time — not cache a stale pubkey from a previous session.
- Same for notifications — `#p` filter must use the current pubkey.
- Same for OutboxRouter — must use the new pubkey for kind-3 lookup.

---

## 3. Avatar Intro Screen

### Ake's prompt:

> "Ok, if the relay data completes within 2-3 seconds after we get the pubkey, then why not create a classy avatar intro page, where it appears after a short funny part?"

### Spec

**Create `AvatarIntroScreen.kt`** — shown ONLY on fresh login/account switch, NOT on app restart.

**Design:**
- AMOLED black background
- User avatar centered — initially identicon (from pubkey), crossfades to real avatar when kind-0 arrives
- Display name below avatar — initially truncated npub, updates to real name when kind-0 arrives
- Below name: a random funny one-liner from a hardcoded list, subtle grey (`Color.White.copy(alpha = 0.4f)`), 14sp

**Example lines:**
- *"Checking if you're still based..."*
- *"Fetching your digital soul..."*
- *"Loading your bad takes..."*
- *"Connecting to the uncensorable..."*
- *"Your relays missed you..."*
- *"Warming up the zap cannon..."*
- *"Gathering your shitposts..."*

**Timing:**
- Minimum display: **1.5 seconds** (even if bootstrap completes instantly — feels intentional, not glitchy)
- Maximum wait: **5 seconds** (timeout — proceed to home feed even if bootstrap incomplete)
- Exit condition: `bootstrapComplete AND minimumHoldElapsed`
- `AppBootstrapper` exposes `bootstrapComplete: StateFlow<Boolean>`, set to `true` when kind-0 + kind-3 + kind-10002 are all received (or on timeout)

**Exit animation:** Avatar scales 1.0 → 1.05, screen fades out, home feed fades in.

---

## 4. Landing on the Feed

### UX after login:

Navigate to home feed IMMEDIATELY after intro screen — do not block further. Feed and profile populate progressively as data arrives from indexers. If kind-3 is empty (new user, no follows), fall back to global feed via `GLOBAL_RELAY_URLS`. No loading screen, no blocking modal.

User lands on **Following feed** if they have follows. Otherwise **Global**.

---

## 5. RelayBrowseSession (sealed browsing mode)

### Ake's analysis and the Perplexity research that drove this:

> "Core call: In relay-browse mode, the browse session should own feed + engagement + first-pass profiles for the selected relay set. Only fall back for missing profiles after the browse relay fails to provide them; do not immediately fan out to the usual engagement/indexer mesh."

> "Your current logs show exactly why the old pattern is bad: the app repeatedly fetches engagement 'from 3 relays 9 lanes' and profiles 'from 4 relays,' while those same relays are already returning ERROR too many concurrent REQs. That means browse mode is not just reading one relay; it is dragging the whole global sidecar circus behind it."

> "Make relay browsing a sealed mode with these rules: One browse target, max 3 relays. One live feed REQ for that target. Engagement one-shots go to the same browse target first. Profile lookup tries browse target first, then indexer fallback only for missing pubkeys. No persistentSubs, no replayPersistentSubs, no global engagement relays while browse mode is active."

> "I would make profile fallback lazy, not automatic. If the browse relay returns the event but no kind-0 after a short timeout or EOSE, then query indexers for only the missing pubkeys; otherwise you reintroduce parallel state and death by a thousand REQs."

> "Keep Following as-is, add RelayBrowseSession, and in browse mode route all event-related reads to the browse relay first. That is the hard wall you need, because the current logs show browse and general relay machinery bleeding into each other and producing exactly the overload you already counted."

### Engagement routing correction

Original plan routed engagement to separate relays during browse mode. Ake caught it:

> "Point 5 seems strange, wouldn't it create parallel states and too many reqs?"

**Corrected rule:** When in relay browse mode, all queries (feed, engagement, profiles) go to the browse relay first. Only fall back to indexer relays for missing profiles after the browse relay fails. No fan-out to 3 engagement relays.

### Spec

**`RelayBrowseSession.kt`** — `@Singleton`, Hilt-injected.

**Owns:** `generation: AtomicLong`, `activeTarget: List<String>?`, `activeSubId: String?`, `isActive: Boolean`.

**`start(relayUrls: List<String>)`:**
- Increments generation
- Calls `stop()` first
- Normalizes URLs
- Caps at 3 relays max
- Connects to target relays (reuse existing connections if open)
- Sends one live subscription per target relay: `{"kinds":[1,6,20,21,30023],"limit":300}` with subId `browse-{generation}`
- NOT registered in RelayPool's `persistentSubs`
- Events flow through existing `EventProcessor` → Room path

**`stop()`:**
- Sends `["CLOSE", activeSubId]` to each target relay
- Clears `activeTarget`, `activeSubId`
- Sets `isActive = false`
- Releases browse ownership for each target relay (see connection ownership below)

### Connection ownership and idle disconnect

ChatGPT review flagged a gap: the original spec said `stop()` does NOT disconnect relays, which leaks sockets and burns battery for browse-only relays that have no other purpose after the session ends.

**Retain/release model on `RelayConnection`:**
- Each connection has a `retainCount: Int` tracking how many consumers need it (outbox routing, browse session, global feed, engagement).
- `start()` increments `retainCount` for each browse target relay.
- `stop()` decrements `retainCount` for each browse target relay.
- When `retainCount` drops to 0: start a **60-second idle timer**.
- If re-retained within 60s (user switches back): cancel timer, connection stays warm — instant resume.
- If timer fires (no one re-claimed it): disconnect the WebSocket.

**App backgrounding (ProcessLifecycleOwner `onStop`):**
- Disconnect browse-only relays IMMEDIATELY — no 60-second grace period when the app isn't visible.
- Only keep outbox/notification connections alive in background.
- Browse mode is a foreground interaction model. Dead sockets in background are pure waste.

**"No other active consumers" means ANY owner in the app** — not just outbox/global/engagement. The retain count is generic. Any future consumer that needs a relay connection increments on acquire, decrements on release. Same pattern everywhere.

**Rules:**
- Browse session subs NEVER enter `RelayPool.persistentSubs`
- `replayPersistentSubs` never replays browse subs
- Browse session manages its own sub lifecycle independently
- On WebSocket reconnect for a browse relay: resend its active subscription
- Generation guard: stale-generation events dropped before Room insert

**FeedViewModel wiring:**
- `FeedType.RelaySet` / `FeedType.SingleRelay` → `browseSession.start(urls)`
- `FeedType.Following` / `FeedType.Global` → `browseSession.stop()` + existing machinery
- Remove `startGlobalFeed()` / `stopGlobalFeed()` from RelayPool entirely

---

## 6. Event-Relay Junction Table

### Ake's constraint:

> "We cannot afford to have user change the relay and it takes posts out, leaves some and starts filling in other posts. The process should optimize full feed loading latency and efficacy."

> "Posts are duplicated by them anyhow, this I think is very integral to creating a smooth transition without killing useful data and re-pulling it again. By doing this right, we optimize latency and delivery."

### The problem

Current schema: `EventEntity` has a single `relay_url` column. An event is tagged with whichever relay delivered it first. When switching to a different relay, `WHERE relay_url = 'wss://nostr.wine'` misses events that exist on nostr.wine but were first seen via damus. The app re-fetches them, can't insert (PK conflict), and the feed looks empty.

### The fix: junction table

**Room migration (v11):**

```sql
CREATE TABLE event_relays (
    event_id TEXT NOT NULL,
    relay_url TEXT NOT NULL,
    UNIQUE(event_id, relay_url)
);
CREATE INDEX index_event_relays_relay_url ON event_relays(relay_url);
```

Migrate existing data: `INSERT INTO event_relays SELECT id, relay_url FROM events`

**EventProcessor change:**
After inserting/ignoring the event, always:
```sql
INSERT OR IGNORE INTO event_relays(event_id, relay_url) VALUES(:id, :url)
```

**Feed queries:**
- Relay browsing: `SELECT e.* FROM events e INNER JOIN event_relays er ON e.id = er.event_id WHERE er.relay_url IN (:browseUrls)`
- Following: filter by `authors IN (:followedPubkeys)` — no relay filter needed
- Global: `WHERE er.relay_url IN (:globalUrls)` or unfiltered

### What this enables

- Events accumulate relay associations over time
- Switching to a relay you've never browsed may still show cached content (your followed users post there)
- No data is ever deleted on feed switch
- No duplicate re-fetching

---

## 7. Two-Buffer Feed Swap

### Ake's UX requirement:

> "We cannot afford to have user change the relay and it takes posts out, leaves some and starts filling in other posts."

### Spec

The visible feed and the next feed are separate buffers. The swap happens only when the new one is ready.

```
User taps nostr.wine
    │
    ├─ Pill UI updates immediately (name → nostr.wine, cyan)
    │   └─ instant visual feedback: "I switched"
    │
    ├─ VISIBLE: old feed still showing (user still sees Following)
    │
    ├─ SHADOW BUFFER: Room query fires (event_relays WHERE relay = nostr.wine)
    │   └─ results land in shadow buffer (not displayed yet)
    │   └─ browse session connects, streams live events into shadow
    │
    ├─ THRESHOLD: shadow has ≥5 events OR 2 seconds elapsed
    │
    └─ SWAP: transition animation, shadow becomes visible
```

**Threshold logic:**
```kotlin
val isReady = combine(shadowEvents, timer) { events, elapsed ->
    events.size >= 5 || elapsed >= 2000
}
```

5 events fills roughly one viewport. The user doesn't need 300 posts to perceive "loaded."

**The user NEVER sees an empty feed.**

### Per-feed scroll position

```kotlin
val scrollStates = remember { mutableMapOf<String, LazyListState>() }
val currentScrollState = scrollStates.getOrPut(currentFeedKey) { LazyListState() }
```

Switch to nostr.wine → browse → switch to Following → switch back to nostr.wine → you're where you left off.

---

## 8. Feed Selector Pill (the spinner)

### Ake's UX concern:

> "Let's first tackle how are we going to make it more obvious for the user that he needs to tap the following sign at the top of the screen. Current way is not that obvious given that youth mistook it for a drop-down signal. It needs to be a spinner signal. Something that will make it obvious for the user seeing for the first time that this thing is a spinner selector."

### Design

Replace the current inline text items (Global, Following, relay names) with a single centered pill:

```
unSilence     [● Following ⇕]     ⊞  ✎
```

**Visual design:**
- Rounded pill container: `RoundedCornerShape(20.dp)`
- Border: `1.dp` in `Color.White.copy(alpha = 0.15f)`, no fill (transparent)
- Active feed name: cyan `#00E5FF`, 16sp, semibold
- UnfoldMore icon (⇕) right of text: 16dp, `Color.White.copy(alpha = 0.5f)`
- Padding: 16dp horizontal, 8dp vertical

**Interactions:**
- **Tap the pill** → opens bottom sheet with full feed list + add/manage actions
- **Vertical swipe on the pill** → cycles through feeds (see section 9)

**First-time hint:** On very first app launch (`feedSelectorHintShown` in SharedPreferences), animate the UnfoldMore icon with a subtle vertical bounce (translateY -4dp → 0 → 4dp → 0) twice, then set the flag. Never again.

### Ake's decision on "+" and "⚙":

> "How does the + and settings tab look?"

**Kill "+" as standalone.** Move "Add relay" and "Create set" into the pill's tap overlay (bottom sheet). The pill becomes the single entry point for all feed-related actions.

**Keep filter icon (⊞).** Controls what you see within the current feed (kinds, engagement, time range). Orthogonal to which feed you're on.

**Final top bar: 3 elements only:**

| Element | Purpose |
|---|---|
| Pill `[Following ⇕]` | Feed selector — swipe to cycle, tap for full list + add/create/manage |
| `⊞` (filter) | Filter current feed |
| `✎` (compose) | Write a post |

---

## 9. Endless Circular Swipe

### Ake's requirement:

> "Needs to be endless scroll so as to have 4 relays as an example cycle through endlessly. Like this the user can reach any relay by swiping lower. As we are at the top of screen, this is important."

### Spec

Feed list cycles infinitely:
```
Following → Global → nostr.wine → primus → Following → Global → ...
```

Index is modular: `(currentIndex + direction + feeds.size) % feeds.size`

No edge detection, no bounds, no "you've reached the end." Just spins.

**Pill animation:**
- Swipe up → old name slides UP + fades out, new name slides in FROM BELOW + fades in
- Swipe down → old name slides DOWN + fades out, new name slides in FROM ABOVE + fades in
- `AnimatedContent` with `slideInVertically` + `fadeIn` / `slideOutVertically` + `fadeOut`
- Duration: 200ms

### Feed content follows the same direction

> Ake challenged "start at the top" for the feed transition:
> "I am not sure what you mean by at the top? Is the endless cycle approach not applicable here?"

**Feed transition matches pill direction:**
- Swipe up on pill → Following feed slides UP and out, nostr.wine slides in FROM BELOW
- Swipe down on pill → reverse direction
- The spatial metaphor is one continuous vertical space — feeds stacked in a circle

**Two transition modes:**
- **Pill swipe** → directional slide (continuous spatial metaphor, matches swipe direction)
- **Pill tap + select from list** → crossfade (discrete jump, no direction)

When the new feed slides in, it appears at the **preserved scroll position** (per-feed scroll state). If first visit, appears at most recent posts. Never blank.

---

## 10. Pill Tap Overlay (Bottom Sheet)

### Ake's question:

> "Move 'Add relay' and 'Create set' into the bottom of the pill's tap overlay. How does this look?"

### Design

```
┌─────────────────────────────────────┐
│                                     │
│  ──────            ← drag handle    │
│                                     │
│  Following                       ✓  │  ← cyan checkmark on active
│  Global                             │
│                                     │
│  ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄  │
│                                     │
│  ★ nostr.wine                       │
│  ★ primus.nostr1.com                │
│  ★ aggr.nostr.land                  │
│                                     │
│  ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄  │
│                                     │
│  ◆ Bitcoin Relays              2    │
│  ◆ Dev                         3    │
│                                     │
│  ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄  │
│                                     │
│  ┌─────────────────────────────────┐│
│  │  wss://                         ││  ← always visible URL field
│  └─────────────────────────────────┘│
│                                     │
│  + Create set                       │  ← grey text
│  ⚙ Manage relays & sets            │  ← grey text
│                                     │
└─────────────────────────────────────┘
```

**Key decisions:**
- **URL field always visible** — not hidden behind a "+" tap. Paste URL, hit enter → relay added to favorites, feed switches, sheet closes. Zero extra taps.
- **No section headers** — icons (✓ ★ ◆) differentiate the sections. Headers add noise.
- **"Create set" and "Manage" are grey text, not buttons** — secondary actions that don't compete visually.
- **Sheet height is dynamic** — never covers more than 60% of screen. Feed visible behind dim scrim.
- **Tap any row → instant switch + close.** No confirm button.
- **Swipe down on sheet → dismiss without switching.**

**Add relay flow:**
1. User taps → URL field already visible
2. Paste `wss://nostr.wine` → hit enter
3. Relay added as kind-10012 favorite (published to relays)
4. Feed switches instantly via `RelayBrowseSession`
5. Sheet closes
6. Next time they open the sheet, nostr.wine is there with ★

---

## 11. Blossom-Inspired Local Media Cache

### Why

**Note:** The Blossom repo (github.com/hzrd149/blossom) describes a **localhost cache server** pattern — a proxy on `127.0.0.1:24242` with HTTP blob endpoints (`GET /<sha256>`, `PUT /upload`). What we build is an **in-app cache layer** that borrows Blossom's content-addressable semantics (sha256 keying, blob resolution order) but lives inside the Android process as Room + disk, not as a separate localhost service. The naming reflects inspiration, not protocol compliance.

The current media pipeline is ad-hoc: Coil fetches images directly from URLs on every render, `MediaMetadataRetriever` extracts video first-frames via HTTP range requests, avatars reload from network on scroll. There's no unified cache, no dedup across events sharing the same image, no prefetch, and no offline capability. On media-heavy relay feeds (which is what unSilence optimizes for), this means redundant network hits, slow scroll, and battery drain.

### Where it fits

Blossom cache sits BESIDE the event pipeline, not inside it. Events still flow `Relay → EventProcessor → Room`. Media resolution becomes a separate path:

```
Event → media reference (URL or sha256 hash from imeta)
    → BlossomCacheManager
        → local file hit? return immediately
        → miss? fetch remote, verify, store, notify UI
```

This is the same architectural wall as RelayBrowseSession vs RelayPool — don't mix subscription state with blob/file state.

### Data model

**Room table: `blossom_cache` (new, separate from events)**

| Column | Type | Purpose |
|---|---|---|
| `blob_key` | TEXT PK | Hash or normalized URL — primary lookup |
| `sha256` | TEXT | Content hash for verification |
| `source_url` | TEXT | Original media URL |
| `mime_type` | TEXT | image/jpeg, video/mp4, etc. |
| `size_bytes` | LONG | File size |
| `local_path` | TEXT | Path to cached file on disk |
| `width` | INT? | Image/video width |
| `height` | INT? | Image/video height |
| `duration_ms` | LONG? | Video duration |
| `last_accessed_at` | LONG | For LRU eviction |
| `created_at` | LONG | When cached |
| `pinned` | BOOL | Exempt from eviction (own avatar, etc.) |
| `owner_event_id` | TEXT? | Which event references this blob |
| `failed_at` | LONG? | Last fetch failure timestamp |
| `failure_count` | INT | Retry backoff tracking |

### Components

```
data/blossom/
├── BlossomCacheEntity.kt      — Room entity
├── BlossomCacheDao.kt         — Room DAO
├── BlossomRemoteClient.kt     — HTTP fetch + verify
├── BlossomCacheManager.kt     — single API, injected everywhere
└── BlossomGarbageCollector.kt — LRU eviction + storage caps
```

### Resolution API

One clean interface, injected into every media consumer:

```kotlin
interface BlobResolver {
    suspend fun resolve(
        key: String,
        sourceUrl: String?,
        expectedSha256: String?,
        priority: FetchPriority  // VISIBLE, PREFETCH, LOW
    ): BlobResult
}

data class BlobResult(
    val localUri: Uri?,        // content:// or file path
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val state: CacheState      // HIT, MISS_FETCHED, FAILED
)
```

### Read path (strict lookup order)

1. Exact hash match in Room → return local file
2. Normalized Blossom URL match → return local file
3. Original media URL match → return local file
4. Remote fetch → verify → store → return
5. Fallback placeholder (dark box at known aspect ratio)

### Rules

- **Images:** decode from disk, not network, whenever cached
- **Video:** cache poster/thumbnail first, full file only on demand or Wi-Fi
- **Avatars:** special priority bucket, tiny dedicated cache or pinned
- **Never block feed rendering on blob download** — show placeholder at correct aspect ratio, fill when ready
- **Prefetch:** visible cards + next 5-10 cards in scroll direction
- **App background:** pause all prefetch immediately
- **Low storage signal:** aggressive LRU trim
- **Logout:** keep cache — it's content, not account state (consistent with Room data retention)
- **Feed switch:** never flush cache. Blobs are content-addressed, relay-independent

### LRU eviction caps

| Bucket | Cap | Contents |
|---|---|---|
| Avatars | 100 MB | Profile pictures, pinned for own + frequent contacts |
| Images | 500 MB | Feed images, OG preview images |
| Video posters | 150 MB | First-frame thumbnails |
| Full video | 200 MB | Optional, on-demand only |

### What it replaces

The current `VideoThumbnailCache` (in-memory `ConcurrentHashMap` of `Bitmap` + `resolvedAspectRatios`) becomes one strategy inside `BlossomCacheManager`. Instead of holding bitmaps in memory (which get GC'd and re-fetched), thumbnails are persisted to disk with dimensions stored in Room. The first-frame extraction via `MediaMetadataRetriever` becomes `BlossomRemoteClient`'s video poster strategy. Coil still handles decoding from disk to `ImageBitmap` — but the source is local, not network.

### Write path (future — upload support)

Separate from caching. When we add post composition with media:

1. `UploadService` uploads file to user's Blossom server
2. Response returns canonical blob reference (sha256 hash)
3. Event composer stores that reference in imeta tags
4. Cache layer treats uploaded media like any other blob — warms local storage

Upload concerns never leak into feed browsing. Same wall as browse mode vs persistent subs.

### Integration points

`BlossomCacheManager` injected into:
- NoteCard media renderer (images, video thumbnails)
- Avatar loader (Coil `SingletonImageLoader.Factory` → check cache first)
- Video thumbnail pipeline (replaces `VideoThumbnailCache`)
- OG preview image loader
- Composer upload pipeline (later)

---

## 12. Architecture Summary

| Layer | Component | Purpose |
|---|---|---|
| Data | `event_relays` junction table | Events tagged with ALL relays seen on — instant cache on switch |
| Data | `blossom_cache` table | Content-addressed media cache with LRU eviction — disk-first media loading |
| Data | Room stays across logout | Old user data + media cache = warm state, new user data overwrites |
| Network | `RelayBrowseSession` | Sealed mode: one target (1-3 relays), one live sub per relay, engagement + profiles routed to browse relay |
| Network | `RelayPool` (unchanged) | Following/Global feed via outbox routing, persistent subs, auth replay |
| Network | Connection retain/release | `retainCount` per relay — idle disconnect after 60s when no consumers, immediate on app background |
| Media | `BlossomCacheManager` | Single resolve API for all media — local disk first, remote fetch on miss, prefetch ahead |
| Media | `BlossomGarbageCollector` | LRU eviction per bucket (avatars/images/video), storage-pressure aware |
| UX | Feed selector pill | Vertical spinner with endless circular swipe, tap for full list |
| UX | Two-buffer feed swap | Old feed visible until new one ready (5 events OR 2 seconds) |
| UX | Per-feed scroll state | Switch back to a relay → same position you left |
| UX | Directional transitions | Swipe = directional slide, tap = crossfade |
| UX | Avatar intro screen | 1.5-5s branded transition on login while bootstrap completes |
| Auth | Logout cascade | Instant UI → background cleanup → Room data + media cache retained |
| Auth | Login bootstrap | One batch REQ for all user kinds → progressive UI fill |

---

## 13. Known UI Bugs (observed March 21) — *post-session addition*

From screenshots of the Following feed on device:

### 13a. Markdown link syntax not rendered

"Source: [World News](" shows as raw text in the feed. The `[text](url)` markdown syntax in note content isn't being parsed. The URL chips below (www.reuters.com, t.me) render correctly — those come from the OG/link detection path. But the inline markdown link in the post body passes through unprocessed. NoteCard's content renderer needs to handle `[text](url)` → clickable styled text, or strip the markdown and let the URL chips carry the information.

### 13b. Empty space from failed OG preview

The Barking News post has a large blank gap between the post text and the "Source: [World News](" line. This is likely a failed OG preview image container that reserved space but never loaded. When an OG image fetch fails or returns null, the container should collapse to zero height — not hold empty space.

### 13c. Engagement counts not showing on Following feed

Hearts/zaps icons visible but zero counts on most posts. Posts that clearly have reactions on other clients (e.g., captjack's NIP-50 post) show zero here. Either:
- Engagement fetch isn't triggering for Following feed cards
- The engagement relay list doesn't overlap with where reactions are stored
- The count display hides when zero (correct) but the fetch itself is failing silently

Verify: add a temporary log in the engagement fetch path to confirm REQs are sent and events are returned for Following feed posts. If engagement is fetched but counts are zero, the issue is in the Room query joining engagement to events (possibly a relay_url mismatch — the junction table fix may resolve this).

---

## 14. Build Order

**Phase 1 — Data foundations:**
1. **`event_relays` junction table** — Room migration v11, EventProcessor dual-insert, feedFlow query rewrite. Foundation for feed switching.
2. **`RelayBrowseSession`** — sealed browsing mode, replaces `startGlobalFeed`/`stopGlobalFeed`. Connection retain/release with idle disconnect.

**Phase 2 — Auth and correctness:**
3. **Logout/login cascade** — `resetAll()` methods, `currentOwnerPubkey`, bootstrap always runs fresh. Avatar intro screen.
4. **UI bug fixes** — markdown `[text](url)` rendering, OG preview empty space collapse, engagement fetch verification for Following feed.

**Phase 3 — Media pipeline:**
5. **`BlossomCacheManager`** — Room table, resolve API, disk-first media loading. Replaces `VideoThumbnailCache`.
6. **`BlossomGarbageCollector`** — LRU eviction per bucket, storage-pressure aware.
7. **Thumbnail prefetch** — visible cards + next 5-10, pause on background.

**Phase 4 — Feed UX:**
8. **Feed selector pill** — replace inline text with spinner pill, vertical swipe, endless circular cycle.
9. **Bottom sheet overlay** — full feed list + inline URL field + create set + manage.
10. **Two-buffer feed swap** — shadow buffer, 5-event/2-second threshold, crossfade/slide transitions.
11. **Per-feed scroll state** — `Map<FeedType, LazyListState>` preservation.

**Phase 5 — Future:**
12. **Blossom upload support** — `UploadService`, composer integration, canonical blob references.
