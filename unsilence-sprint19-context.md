# unSilence Sprint 19: Profile & Feed Reactivity

> **For Claude Code:** Read this entire file before brainstorming. Always check
> ACTUAL source files before making changes. Sprint 18 just shipped — relay health,
> session teardown, DB index, FIFO pruning are already in place.

---

## Codebase Constraints (non-negotiable)

- **DI:** Hilt only. No Koin. KSP, not kapt.
- **Events:** Quartz library types, not generic `Event` data classes. Use `EventTemplate` constructor for JVM 17 compat.
- **Room migrations:** Index names MUST use `index_tablename_col1_col2` convention (with backticks in SQL). Every migration index MUST also be declared in `@Entity(indices=[...])` using vararg syntax: `Index("col1", "col2")`, NOT `Index(value=["col1"])`.
- **Versions:** Kotlin 2.3.0, KSP 2.3.0, Hilt 2.58, Room 2.7.1, AGP 8.9.1, Gradle 8.11.1, Compose BOM 2025.05.00, compileSdk/targetSdk 36.
- **Build:** Never run `./gradlew` from terminal while Android Studio is open.
- **Git:** Operations from `aivii` user terminal, not `android-dev`.
- **Architecture:** Relay → EventProcessor → Room → Flow/StateFlow → Compose UI. UI never waits on WebSocket.
- **Subscriptions:** One-shot subs CLOSE after EOSE. Persistent subs stay open. `notifs-` is persistent.
- **Relay defaults:** Indexers: `purplepag.es`, `user.kindpag.es`, `indexer.coracle.social`. Search (NIP-50): `relay.noswhere.com`, `search.nos.today`. Global: `relay.damus.io`, `nos.lol`, `nostr.mom`, `relay.nostr.net`, `relay.primal.net`, `relay.ditto.pub`.
- **Sprint 18 already delivered:** RelayConnection state tracking, RelayPool backoff reconnect + persistent sub replay, AppBootstrapper comprehensive teardown with stop()/start() on EventProcessor and OutboxRouter, MIGRATION_3_4 (root_id + created_at index), DatabaseMaintenanceJob (FIFO pruning).

---

## Bugs to Fix in Sprint 19

### A3. Repost author profile pictures missing
- **Symptom:** Kind-6 reposts sometimes show no avatar for the repost author
- **Root cause:** Profile fetching only includes the original note's author pubkey, not the reposter's pubkey. When building feed UI models, the repost event's `pubkey` (the person who reposted) isn't included in the profile fetch batch.
- **Fix:** When processing kind-6 events for display, collect BOTH the repost event's pubkey (reposter) AND the inner/original event's author pubkey. Include both in profile fetch requests.

### A4. User profiles show few posts (only ~4 days back)
- **Symptom:** Opening a user profile shows far fewer notes than expected — only recent posts
- **Root cause:** Multiple issues:
  1. Only fetches from currently connected relays, NOT the user's declared outbox relays (NIP-65 / kind-10002)
  2. Fetch limit is too low or subscription closes too early
  3. No cursor-based pagination to load older posts beyond the initial fetch
- **Fix:**
  1. When viewing a user profile, look up their kind-10002 relay list and fetch notes from their write/outbox relays specifically
  2. Increase the initial fetch limit
  3. Add `until`-based timestamp pagination — when user scrolls to bottom, fetch older notes using `until = oldestTimestamp` in the relay filter
  4. Keep the subscription open longer for slow relays, or use EOSE-based closing

### A5. No zap UI feedback
- **Symptom:** Zap count updates in Room but the feed UI doesn't reflect changes. No visual feedback when a zap is sent or received.
- **Root cause:** Feed DAO queries return `suspend fun: List<EventEntity>` — one-shot snapshots that never re-emit. When `addZap()` updates a note's zapCount/zapTotalSats in Room, nothing triggers the UI to refresh.
- **Fix:**
  1. Change feed DAO queries to return `Flow<List<EventEntity>>` so Room auto-re-emits when the events table changes
  2. ViewModel collects the Flow and maps to UI models reactively
  3. Add zap feedback animation: brief amber flash / bounce on the ⚡ icon when zap count changes
  4. When user sends a zap: show confirmation snackbar

---

## Sprint 19 Task Breakdown

### Task 1: ProfileCache — reactive profile fetching

**Problem:** Profiles are fetched once with fire-and-forget pattern. If the relay is slow or the profile isn't on connected relays, it's missed entirely.

**Design a `getOrFetch(pubkey): Flow<ProfileEntity?>` method that:**
- Emits cached profile immediately from Room if available
- If profile is missing or stale (e.g., >1 hour since `updatedAt`), triggers a relay fetch
- Emits updated profile when relay response arrives and gets saved to Room
- For feed display: ensure ALL visible pubkeys are fetched, including repost authors

**For repost avatar fix (A3):**
- When building NoteUiModel list from feed events, for kind-6 reposts: extract BOTH `event.pubkey` (reposter) AND the inner event's original author pubkey
- Include both in the profile fetch batch
- The UI model for reposts needs two profiles: one for the "reposted by X" header, one for the original note author

### Task 2: User profile — NIP-65 outbox relay fetching + pagination (A4)

**Problem:** User profile view only queries connected relays with a low limit. Users who post from different relays than what we're connected to will show few or no posts.

**Fix:**
- When opening a user profile, first check Room for their kind-10002 relay list
- If not in Room, fetch kind-10002 from indexer relays
- Parse the relay list to find their write/outbox relays
- Fetch the user's notes from their outbox relays, not just our connected relays
- Initial fetch: limit 100 (up from current)
- Pagination: when user scrolls near bottom, fetch more using `until = oldestTimestamp` in the relay filter
- Use EOSE-based subscription closing (already the pattern for one-shot subs)

### Task 3: Reactive feed queries (A5)

**Problem:** Feed queries are one-shot snapshots. Zap count updates don't propagate to UI.

**Fix:**
- Change key feed DAO queries from `suspend fun getRootNotes(): List<EventEntity>` to `fun observeRootNotes(): Flow<List<EventEntity>>`
- Room automatically re-emits Flow when the underlying table changes
- ViewModel collects the Flow and maps to UI models
- When `addZap()` updates a row, the Flow re-emits, UI updates automatically

**Zap feedback animation:**
- When a note's `zapTotalSats` changes, briefly flash the ⚡ icon amber and bounce it
- When user sends a zap, show a snackbar confirmation

**Risk:** Changing DAO returns from suspend to Flow touches every ViewModel that queries events. Change carefully — test each screen after modification.

**Scope limit:** Only change the HOME FEED queries to Flow for now. Thread queries and profile queries can stay as one-shot — don't refactor everything at once.

---

## Constraints specific to Sprint 19

- **Reactive queries are the riskiest change.** If feed queries become Flow-based, pagination logic needs to adapt. The current `loadMore()` pattern appends to a list — with Flow, the entire list re-emits on any table change. Consider using `distinctUntilChanged()` and careful state management.
- **Profile fetching must not block feed display.** Show cached profiles immediately, update when fresh data arrives. Never hold the feed waiting on a profile fetch.
- **OutboxRouter already exists** — check if it already handles NIP-65 relay routing. Don't duplicate logic.
- **Don't break existing screens.** Thread view, notifications, search — all must continue working after DAO changes.
