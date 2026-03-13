# Sprint 19: Profile & Feed Reactivity — Design Spec

## Goal

Fix repost author profiles (A3), add NIP-65 outbox fetching + pagination to user profiles (A4), and add profile staleness TTL.

## Scope

| ID | Summary | Status |
|----|---------|--------|
| A3 | Repost author profile pictures/names missing | In scope |
| A4 | User profiles show few posts, no outbox, no pagination | In scope |
| Profile TTL | Stale profiles never re-fetched | In scope |
| A5 | Zap UI animation | Deferred — can't test on emulator |

## Architecture Rule

Relay → EventProcessor → Room → Flow/StateFlow → Compose UI. UI never waits on WebSocket.

---

## A3: Repost Author Profile Fix

### Root Cause

NoteCard renders kind-6 reposts by parsing the embedded JSON for the original event's content, pubkey, and timestamp. However:

1. **FeedRow SQL JOIN** (`EventDao.kt`) joins `users` on `e.pubkey` — the reposter's pubkey. The original author's profile is never joined.
2. **NoteCard avatar** hardcodes `picture = null` for reposts: `if (boostedJson == null) row.authorPicture else null`.
3. **NoteCard name** always shows truncated hex for the original author: `"${effectivePubkey.take(6)}…${effectivePubkey.takeLast(4)}"`.

The profile fetch code in FeedViewModel correctly extracts both the reposter and original author pubkeys and requests missing profiles. The profiles likely exist in Room — the UI just never reads them.

### Fix

**Approach:** Compose-level profile lookup via FeedViewModel. No schema changes, no migration.

1. **FeedViewModel** exposes `fun profileFlow(pubkey: String): Flow<UserEntity?>` — delegates to `userRepository.userFlow(pubkey)` (UserRepository already wraps `userDao.userFlow()`). To prevent duplicate Room subscriptions for the same pubkey across multiple visible items, cache flows in the ViewModel:
   ```kotlin
   private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()
   fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
       profileCache.getOrPut(pubkey) {
           userRepository.userFlow(pubkey)
               .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
       }
   ```

2. **LazyColumn item** (in the feed screen composable) collects `profileFlow(effectivePubkey)` via `collectAsState()` for kind-6 rows and passes the resulting `UserEntity?` down to NoteCard as a parameter (e.g., `originalAuthorProfile: UserEntity? = null`).

3. **NoteCard** uses `originalAuthorProfile` for reposts:
   - Avatar: `if (boostedJson == null) row.authorPicture else originalAuthorProfile?.picture`
   - Name: `originalAuthorProfile?.displayName ?: originalAuthorProfile?.name ?: "${effectivePubkey.take(6)}…${effectivePubkey.takeLast(4)}"`

4. **NoteCard stays stateless** — it receives all data as parameters. The Flow collection happens one level up.

### Files Modified

- `FeedViewModel.kt` — Add `profileFlow(pubkey)` method with caching
- Feed screen composable (LazyColumn) — Collect profile flow for kind-6 items
- `NoteCard.kt` — Add `originalAuthorProfile` parameter, use it for avatar + name

---

## A4: User Profile Outbox Fetching + Pagination

### Root Cause

`UserProfileViewModel` calls `relayPool.fetchUserPosts(pubkey)` which only queries currently-connected relays with a fixed limit. Users who post from niche relays show few or no posts. No scroll-to-load-more exists.

### Fix — Three Parts

#### Part 1: Outbox Relay Lookup

When `UserProfileViewModel.loadProfile(pubkey)` is called:

1. Check Room for the user's kind-10002 relay list via a new `relayListDao.getByPubkey(pubkey)` query (**new method** — `RelayListDao` currently only has `upsert()`, `allFlow()`, and `getAll()`).
2. If not in Room, fetch kind-10002 from indexer relays (`purplepag.es`, `user.kindpag.es`, `indexer.coracle.social`). Wait for it to arrive in Room with a ~5s timeout.
3. Parse write relay URLs from the relay list entity.
4. Connect to those relays via `relayPool.connect()`, respecting the 25-connection soft cap.
5. Fetch user posts from all connected relays (including newly-added outbox ones).

**Connection cap:** `RelayPool.connect()` checks `connections.size >= 25` before adding new relays. If at cap, skip the connect and log which outbox relay URLs were skipped (debug-level). The fetch still proceeds against whatever relays are already connected — the user may see fewer posts, which is acceptable degradation.

Connections opened for profile viewing stay open for the rest of the session. `AppBootstrapper.teardown()` cleans them up on logout. This is acceptable because most Nostr users cluster around 5-10 popular relays, so connection growth is bounded in practice.

**DI for relay list access:** `UserProfileViewModel` needs access to relay list data. Add a method to `UserRepository`: `suspend fun getRelayList(pubkey: String): RelayListEntity?` that delegates to `relayListDao.getByPubkey(pubkey)`. This requires adding `RelayListDao` to `UserRepository`'s constructor injection. This keeps the existing pattern of ViewModels going through repositories, not DAOs directly.

#### Part 2: Growing Query Window

`userPostsFlow(pubkey)` currently has `LIMIT 100`. Change to accept a dynamic limit parameter:

- `fun userPostsFlow(pubkey: String, limit: Int): Flow<List<FeedRow>>`
- ViewModel tracks `displayLimit` as a `MutableStateFlow<Int>(200)`
- Each scroll-to-bottom increases `displayLimit` by 200
- DAO query uses the passed limit
- `postsFlow` re-subscribes reactively when limit changes:
  ```kotlin
  private val _displayLimit = MutableStateFlow(200)
  val postsFlow: Flow<List<FeedRow>> =
      combine(_pubkeyHex.filterNotNull(), _displayLimit) { pk, limit -> pk to limit }
          .flatMapLatest { (pk, limit) -> eventRepository.userPostsFlow(pk, limit) }
  ```

This keeps the Flow bounded while allowing growth as the user scrolls. Prevents memory issues for power users with tens of thousands of posts in Room.

#### Part 3: Scroll-to-Bottom Relay Fetch

- ViewModel tracks `oldestTimestamp` from the current result set
- When user scrolls near bottom, calls a new `relayPool.fetchOlderPosts(pubkey, untilTimestamp)` method
- This sends a one-shot REQ scoped to one author with `until = oldestTimestamp`
- Posts arrive via EventProcessor → Room → `userPostsFlow` Flow re-emits automatically
- Guard against duplicate fetches with a `fetching` boolean flag (same pattern as FeedViewModel)

### New Method

`RelayPool.fetchOlderPosts(pubkey: String, untilTimestamp: Long)` — sends a one-shot REQ with:
- `kinds: [1, 6]`
- `authors: [pubkey]`
- `until: untilTimestamp`
- `limit: 200`

Subscription ID prefix: `older-user-` (starts with `older-`, matching the existing `isOneShotSubscription` check so EOSE closes it correctly).

### Files Modified

- `RelayPool.kt` — Add connection cap in `connect()`, add `fetchOlderPosts()` method
- `UserProfileViewModel.kt` — Add outbox relay lookup, `_displayLimit` StateFlow, scroll-to-bottom pagination
- `EventDao.kt` — Add `limit` parameter to `userPostsFlow()` query
- `EventRepository.kt` — Thread `limit` parameter through to DAO
- `RelayListDao.kt` — Add `suspend fun getByPubkey(pubkey: String): RelayListEntity?`
- `UserRepository.kt` — Add `suspend fun getRelayList(pubkey: String): RelayListEntity?`

---

## Profile Staleness TTL

### Root Cause

`UserRepository.fetchMissingProfiles()` checks if a pubkey exists in Room via `allPubkeys()`. If it exists — even with `picture = null` and `name = null` — it's considered "cached" and never re-fetched. Profiles that change (avatar, display name) are never updated.

### Fix

Change `fetchMissingProfiles()` to fetch profiles that are either missing OR stale (older than 6 hours).

1. **UserDao** — Add `suspend fun stalePubkeys(olderThan: Long): List<String>` returning pubkeys where `updated_at < olderThan`.

2. **UserRepository.fetchMissingProfiles()** — Change logic:
   ```
   val cached = userDao.allPubkeys().toSet()
   val staleThreshold = System.currentTimeMillis() / 1000 - (6 * 3600)
   val stale = userDao.stalePubkeys(staleThreshold).toSet()
   val toFetch = pubkeys.filter { it !in cached || it in stale }
   ```
   Two queries is intentional for clarity — `allPubkeys()` is needed for the "missing" check and `stalePubkeys()` for the "stale" check. For a client app with <10K users in Room, this is negligible overhead.

3. When kind-0 arrives, the existing `upsert` overwrites the row and sets `updatedAt = nowSeconds`, resetting the TTL.

**Why 6 hours:** Profiles change infrequently. 1 hour wastes relay bandwidth on every feed load. 6 hours balances freshness with efficiency.

**No migration needed** — `updated_at` column already exists on `users` table.

### Files Modified

- `UserDao.kt` — Add `stalePubkeys()` query
- `UserRepository.kt` — Update `fetchMissingProfiles()` logic

---

## What This Sprint Does NOT Change

- Thread view queries (stay as-is)
- Notification queries (stay as-is)
- Search queries (stay as-is)
- Global/Following feed queries (already Flow-based, working correctly)
- Zap animations (deferred — A5)
- EventProcessor pipeline (no changes)
- Room schema / migrations (no new columns or tables)
