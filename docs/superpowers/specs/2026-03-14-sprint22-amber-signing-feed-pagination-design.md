# Sprint 22: Amber Signing, Follow List Feed, Feed Pagination

**Date:** 2026-03-14
**Status:** Approved

---

## Overview

Three interconnected improvements sharing the signing → follow list → feed pipeline:

1. **SigningManager** — centralized signing abstraction for nsec and Amber login modes
2. **Feed pagination** — growing SQL window (`_displayLimit`) layered on existing `loadMore()` guards
3. **Default feed selection** — Following feed when follows exist in Room, Global otherwise

Pre-sprint hotfix: downgrade `markdown-renderer` from `0.39.2` to `0.27.0` (Compose BOM 2025.05.00 compatibility).

---

## 1. SigningManager

### Problem

Six signing locations call `keyManager.getPrivateKeyHex()` directly. When logged in via Amber, private key is null → all signing silently fails.

### Design

**New file:** `data/auth/SigningManager.kt` — Hilt `@Singleton`

**Public API:**

```kotlin
@Singleton
class SigningManager @Inject constructor(
    private val keyManager: KeyManager
) {
    private val _pendingRequests = MutableSharedFlow<SignRequest>(extraBufferCapacity = 1)

    suspend fun sign(template: EventTemplate<*>): Event?
    fun pendingSignRequest(): SharedFlow<SignRequest>
    fun completeAmberSign(signature: String)
    fun cancelAmberSign()
}
```

**SignRequest:**

```kotlin
data class SignRequest(
    val unsignedEventJson: String,
    val pubkeyHex: String,
    val deferred: CompletableDeferred<String?>
)
```

**`sign()` flow:**

- If `!keyManager.isAmberMode`: build `NostrSignerInternal(KeyPair(privKey))`, sign locally via `withContext(Dispatchers.Default)` (crypto is blocking), return result.
- If `keyManager.isAmberMode`:
  1. Serialize unsigned event to JSON
  2. Create `CompletableDeferred<String?>`
  3. Wrap in `SignRequest`, emit on `_pendingRequests`
  4. `withTimeoutOrNull(30_000) { deferred.await() }` — 30-second timeout, returns null on expiry or user dismissal
  5. If signature received, attach to event and return signed event
  6. If null (timeout/cancel), return null

**Activity-side collector** (top-level composable in `MainActivity`):

- Collect `signingManager.pendingSignRequest()`
- On emission: build Amber intent (`type=sign_event`, `event=json`, `current_user=pubkey`)
- Launch via `ActivityResultLauncher`
- On result: call `completeAmberSign(signature)` or `cancelAmberSign()`

### Refactor Touchpoints

Replace `buildSigner()` / inline signing in:

| Location | Current | After |
|----------|---------|-------|
| `ComposeViewModel.publishNote()` | `NostrSignerInternal` + `keyManager.getPrivateKeyHex()` | `signingManager.sign(template)` |
| `NoteActionsViewModel.react()` | `buildSigner()` helper | `signingManager.sign(template)` |
| `NoteActionsViewModel.repost()` | `buildSigner()` helper | `signingManager.sign(template)` |
| `ZapRepository.zap()` | `buildSigner()` inline | `signingManager.sign(template)` |
| `ThreadViewModel.publishReply()` | `NostrSignerInternal` inline | `signingManager.sign(template)` |
| `ProfileViewModel.saveProfile()` | `NostrSignerInternal` inline | `signingManager.sign(template)` |

Each location drops ~5 lines of key-fetching boilerplate and gains Amber support. The `buildSigner()` helpers are deleted.

**Not in scope:** `NwcManager.payInvoice()` signs with the NWC connection's own secret key, not the user's key — left unchanged.

### Key Files

- **New:** `app/.../data/auth/SigningManager.kt`
- **Modified:** `AmberSigner.kt` — add `createSigningIntent(unsignedEventJson, pubkeyHex): Intent` and `parseSigningResult(data: Intent?): String?`
- **Modified:** `ComposeViewModel.kt`, `NoteActionsViewModel.kt`, `ZapRepository.kt`, `ThreadViewModel.kt`, `ProfileViewModel.kt`
- **Modified:** `RootScreen` composable (top-level in `MainActivity`) — Amber signing intent collector via `rememberLauncherForActivityResult`
- **Unchanged:** `KeyManager.kt`

### Concurrency Note

Signing requests serialize naturally: each `sign()` call suspends on its own `CompletableDeferred`. The Activity collector processes one Amber intent at a time. If multiple signing requests queue (e.g., rapid tap on react + repost), they execute sequentially through the `SharedFlow` with `extraBufferCapacity = 1`.

---

## 2. Feed Pagination

### Problem

Both `feedFlow()` and `followingFeedFlow()` use a hard `LIMIT 300` in SQL. The `loadMore()` method fetches older events from relays but the query window never grows — events beyond 300 are fetched but invisible.

### Design

**Layer `_displayLimit` on top of existing guards.** Do not rewrite `loadMore()`.

**FeedViewModel changes:**

```kotlin
private val _displayLimit = MutableStateFlow(200)
```

- Wire into feed flow: `combine(_feedType, _displayLimit, ...).flatMapLatest { ... }`
- In `loadMore()`: add `_displayLimit.value += 200` before the relay fetch call
- On feed type switch or filter change: reset `_displayLimit.value = 200`
- Keep existing `lastOldestTimestamp` guard and 2-second cooldown unchanged

**EventDao changes:**

- `feedFlow(limit: Int)` — parameterize `LIMIT :limit` (was `LIMIT 300`)
- `followingFeedFlow(limit: Int)` — same

**FeedScreen:** No changes. Existing scroll detection at 50% triggers `loadMore()` which now also grows the window.

**Following feed pagination note:** When on the Following feed, `currentRelayUrls` is empty (outbox routing handles relay connections separately). `_displayLimit` growth still reveals already-cached events beyond the initial 200. Relay-side pagination for Following uses the outbox relay connections managed by `OutboxRouter`.

### Net Change

~15 lines in `FeedViewModel`, ~2 lines per DAO query, `FollowDao` added as new `FeedViewModel` dependency. No UI changes.

---

## 3. Default Feed Selection

### Problem

Need to default to Following feed when the user has follows, Global when they don't. Must handle cold start (follows not yet fetched from relays) without timing games.

### Design

**FeedViewModel init block:**

```kotlin
init {
    viewModelScope.launch {
        val hasFollows = followDao.count() > 0
        _feedType.value = if (hasFollows) FeedType.Following else FeedType.Global
    }
}
```

Check Room synchronously at launch. If follows exist from a previous session, show Following. If empty (fresh install or first Amber login), show Global. After bootstrap fetches kind-3 and populates the follows table, next app launch defaults to Following.

No timers, no auto-switching, no loading states. One check, one assignment, ~5 lines.

**Note:** `FollowDao` is a new dependency for `FeedViewModel`. The `_feedType` starts as `Global` and may briefly emit before the coroutine switches to `Following` — this causes one redundant `flatMapLatest` re-emission on cold start, which is acceptable (sub-frame).

---

## Execution Order

1. ~~**Hotfix:** markdown-renderer downgrade (done)~~
2. **SigningManager** — new singleton + refactor 6 signing locations + Activity collector
3. **Feed pagination** — `_displayLimit` in FeedViewModel + parameterize DAO queries
4. **Default feed selection** — init block check in FeedViewModel

---

## Constraints

- Hilt DI only, KSP not kapt
- Quartz `EventTemplate` constructor for JVM 17 compat
- Room migration index naming: `index_tablename_col1_col2` (no new migrations needed for this sprint)
- Git operations from `aivii` user terminal
- Never run `./gradlew` while Android Studio is open
