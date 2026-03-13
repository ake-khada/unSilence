# Sprint 18: Foundation Fixes — Design Spec

> **Scope:** Tasks 1-4 only. Resolves bugs A1, A2, A9, A10.
> Sprints 19-20 (A3-A8) are separate.

---

## Codebase Constraints

- **DI:** Hilt only (KSP, not kapt)
- **Events:** Quartz library types, `EventTemplate` constructor pattern
- **Build:** Never run `./gradlew` while Android Studio is open
- **Git:** Operations from `aivii` user terminal
- **Room migrations:** Non-destructive only
- **Versions:** Kotlin 2.3.0, KSP 2.3.0, Hilt 2.58, Room 2.7.1, AGP 8.9.1, compileSdk/targetSdk 36

---

## Task 1: RelayConnection + RelayPool Health (Resolves A9, A10)

### 1a. RelayConnection — State Tracking

**File:** `data/relay/RelayConnection.kt`

**State tracking:**
- Add `RelayState` enum: `CONNECTING`, `CONNECTED`, `DISCONNECTED`, `FAILED`
- Expose `val state: StateFlow<RelayState>`
- Update state in: `connect()` → CONNECTING, `onOpen` → CONNECTED, `onFailure` → FAILED, `onClosing`/`close()` → DISCONNECTED

**No `reconnect()` method on RelayConnection.** The `_messages` Channel is closed on failure/disconnect, and a closed Kotlin Channel cannot be reopened. Reconnection must create a new `RelayConnection` instance — this is already the pattern used by `RelayPool`. Backoff logic lives in `RelayPool` (see 1b).

**Zombie detection:**
- OkHttp already has `pingInterval(25s)` in `AppModule`. Ping failure triggers `onFailure`, which triggers backoff reconnect at the `RelayPool` level. No custom ping/pong needed.

### 1b. RelayPool — Orchestration & Backoff

**File:** `data/relay/RelayPool.kt`

**Backoff reconnection (owned by RelayPool, not RelayConnection):**
- On `RelayConnection.onFailure` (detected via state flow or callback): create a new `RelayConnection` instance for that relay URL with exponential backoff delays: 1s, 2s, 4s, 8s, ..., max 30s
- Reset backoff counter to 0 on successful connect (`CONNECTED` state)
- `AtomicBoolean` guard per relay URL — `compareAndSet(false, true)` before launching reconnect, reset on success or max retries exhausted
- Replace the old connection in the `connections` map with the new instance
- Re-launch `listenForEvents()` coroutine for the new connection

**Persistent subscription tracking:**
- Add `persistentSubs: ConcurrentHashMap<String, PersistentSub>` where `PersistentSub` holds subscription ID, filters, and `lastEventTime: Long` (Unix seconds)
- Register persistent subs when created: `feed-*`, `follows-*`, `notifs-*`
- Update `lastEventTime` when events arrive for a subscription
- Clear map on teardown

**Lifecycle reconnection (enhance existing `reconnectAll()`):**
- `MainActivity` already calls `reconnectAll()` on `ON_START` — keep that hook
- `reconnectAll()` iterates connections, creates new instances only for those with state `DISCONNECTED` or `FAILED`
- After successful reconnect: replay persistent subs with updated `since` = `max(lastEventTime - 30, 0)` (30s buffer for clock skew, Unix seconds), fallback to `now - 5 minutes` if no events received yet
- **Known limitation:** Disconnections longer than 5 minutes may miss events. Acceptable for v1.

**Fix A9 — notifications subscription:**
- `notifs-` is already absent from `isOneShotSubscription()` — notifications survive EOSE correctly
- Fix the stale comment on line 130 that lists `notifs-` as one-shot (it is not)
- Register `notifs-` subscriptions in `persistentSubs` so they're **re-subscribed on reconnect** — this is the actual A9 fix
- Ensure `fetchNotifications()` is called again after reconnect via persistent sub replay

**Aggregate state exposure:**
- Expose `val connectionStates: StateFlow<Map<String, RelayState>>` derived from individual `RelayConnection.state` flows
- No UI work this sprint — just expose for future use

**Thread safety:**
- `connections` map and `persistentSubs` map both accessed from multiple threads (reconnection, lifecycle, message processing) — use `ConcurrentHashMap` for both

---

## Task 2: AppBootstrapper Gaps (Resolves A1, A2)

**File:** `data/AppBootstrapper.kt`

### 2a. Verify Amber Login Bootstrap (may be a no-op)

- The Amber callback in `OnboardingScreen.kt` (line 61-62) calls `saveAmberLogin(pubkey)` then `onComplete()`
- `onComplete` is wired to `RootViewModel.onOnboardingComplete()` (via `RootScreen.kt` line 16)
- `onOnboardingComplete()` calls `bootstrapper.bootstrap(pubkey)` (line 24)
- **This path already works.** Verify on real device with Amber. If confirmed working, mark A1 as resolved with no code changes
- If bootstrap fails on real device despite this wiring, investigate deeper (timing, lifecycle, etc.)

### 2b. Comprehensive Teardown

Current `teardown()` does: disconnect relays, clear KeyManager, clear Room tables.

New teardown, in this order:

1. **Cancel persistent subscriptions** — clear `RelayPool.persistentSubs`, send CLOSE messages to relays
2. **Disconnect all WebSockets** — already done
3. **Clear all Room tables** — already done
4. **Clear SharedPreferences** — project uses `EncryptedSharedPreferences` (not DataStore). Any cached prefs beyond KeyManager (if any) should be cleared here. Relay set selections are in Room and already cleared in step 3
5. **Clear KeyManager** — already done
6. **Cancel child scopes** — call `OutboxRouter.stop()`, `EventProcessor.stop()` to cancel their internal coroutines. **Do NOT cancel AppBootstrapper's own scope** — it must survive for next login
7. **Reset in-memory state** — `EventProcessor.seenIds.clear()`, clear `RelayPool` connection map
8. **Navigate to onboarding screen**

`OutboxRouter` and `EventProcessor` each get `stop()` and `start()` methods:
- `stop()` cancels a child `Job` (not the injected scope itself) and clears in-memory state
- `start()` relaunches internal coroutines (drainers, routing pipeline) under a new child `Job`
- `AppBootstrapper.teardown()` calls `stop()`, `AppBootstrapper.bootstrap()` calls `start()`

---

## Task 3: Database Index (MIGRATION_3_4)

**Files:** `data/db/Migrations.kt`, `data/db/AppDatabase.kt`, `di/DatabaseModule.kt`

**One index:**
```sql
CREATE INDEX idx_events_root_id_created_at ON events(root_id, created_at)
```

- Bump database version from 3 to 4
- Add `MIGRATION_3_4` to `DatabaseModule` builder's migration list
- Non-destructive, additive only
- Supports thread queries that sort by time (existing `(root_id, kind)` index doesn't cover time ordering)

No speculative indexes. If slow queries surface during other tasks, add indexes then.

---

## Task 4: Database Maintenance Job (FIFO Pruning)

**New file:** `data/db/DatabaseMaintenanceJob.kt`

**Class:** `DatabaseMaintenanceJob` — Hilt `@Singleton`, injected with `EventDao` and `CoroutineScope`

**Single periodic coroutine, every 5 minutes:**

1. **FIFO prune (800K row cap):**
   ```kotlin
   val count = eventDao.count()
   var excess = count - 800_000
   while (excess > 0) {
       val batch = minOf(excess, 10_000)
       eventDao.deleteOldest(batch)
       excess -= batch
       delay(50) // avoid long DB lock
   }
   ```
   - New DAO method: `deleteOldest(limit)` — `DELETE FROM events WHERE id IN (SELECT id FROM events ORDER BY created_at ASC LIMIT :limit)`
   - Uses existing `(created_at)` index from MIGRATION_2_3

2. **Expiration prune (NIP-40):**
   - Call `eventDao.pruneExpired(nowSeconds)` — existing logic, moved here

**Insert path cleanup:**
- Check if `pruneExpired()` is called from the insert path — if so, remove that call

**Lifecycle:**
- `start()` launches the periodic coroutine, holds `Job` reference
- `stop()` cancels the job
- Started by `AppBootstrapper.bootstrap()`, stopped by `AppBootstrapper.teardown()`

---

## Bugs Resolved

| Bug | Fix | Task |
|-----|-----|------|
| A1 | Verify Amber callback already reaches `bootstrap()` — likely no code change needed | Task 2a |
| A2 | Comprehensive `teardown()` | Task 2b |
| A9 | Register `notifs-` as persistent sub for reconnect replay (already survives EOSE) | Task 1b |
| A10 | State tracking + backoff reconnect + lifecycle replay | Task 1a, 1b |

## Files Modified

| File | Changes |
|------|---------|
| `data/relay/RelayConnection.kt` | State enum, StateFlow (no reconnect method — Channel can't be reused) |
| `data/relay/RelayPool.kt` | Backoff reconnect (new instances), persistent sub tracking, `since` timestamps, notifs- persistent registration, fix stale comment, aggregate state, ConcurrentHashMap for thread safety |
| `data/AppBootstrapper.kt` | Comprehensive teardown with ordered steps, start/stop lifecycle for child components |
| `data/relay/EventProcessor.kt` | Add `stop()`/`start()` methods (child Job pattern, clear seenIds) |
| `data/relay/OutboxRouter.kt` | Add `stop()`/`start()` methods (child Job pattern) |
| `data/db/Migrations.kt` | Add MIGRATION_3_4 |
| `data/db/AppDatabase.kt` | Bump version to 4 |
| `di/DatabaseModule.kt` | Add MIGRATION_3_4 to builder |
| `data/db/dao/EventDao.kt` | Add `deleteOldest(limit)` query |
| `data/db/DatabaseMaintenanceJob.kt` | **New file** — periodic FIFO + expiration pruning |
| `ui/onboarding/OnboardingScreen.kt` | Verify only — Amber path already calls `onComplete()` → `bootstrap()` |
| `data/relay/RelayPool.kt` (comment) | Fix stale comment on line 130 listing `notifs-` as one-shot |
