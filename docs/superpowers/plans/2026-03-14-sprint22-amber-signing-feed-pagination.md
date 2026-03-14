# Sprint 22: Amber Signing + Feed Pagination Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable event signing for Amber-login users via Quartz's `NostrSignerExternal`, add growing-window pagination to feeds, and default to Following feed when follows exist.

**Architecture:** `SigningManager` wraps Quartz's `NostrSigner` polymorphism — holds either `NostrSignerInternal` (nsec) or `NostrSignerExternal` (Amber). All ViewModels call `signingManager.sign(template)` instead of building their own signer. For Amber mode, `NostrSignerExternal` handles the intent flow internally — we just wire `registerForegroundLauncher` in `MainActivity` and feed results back via `newResponse()`. Feed pagination layers a `_displayLimit` StateFlow on existing `loadMore()` guards.

**Tech Stack:** Kotlin, Hilt, Quartz (NostrSignerExternal/NostrSignerInternal), Room, Compose, ActivityResultLauncher

**Spec:** `docs/superpowers/specs/2026-03-14-sprint22-amber-signing-feed-pagination-design.md`

---

## Chunk 1: SigningManager + Amber Wiring

### Task 1: Create SigningManager singleton

**Files:**
- Create: `app/src/main/kotlin/com/unsilence/app/data/auth/SigningManager.kt`

- [ ] **Step 1: Create SigningManager with dual-mode signing**

```kotlin
package com.unsilence.app.data.auth

import android.content.ContentResolver
import android.content.Intent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"

@Singleton
class SigningManager @Inject constructor(
    private val keyManager: KeyManager,
    private val contentResolver: ContentResolver,
) {
    @Volatile
    private var signer: NostrSigner? = null

    /**
     * Build the signer lazily on first use.
     * nsec mode → NostrSignerInternal (signs locally).
     * Amber mode → NostrSignerExternal (delegates to Amber app via NIP-55 intents).
     */
    @Synchronized
    private fun getOrCreateSigner(): NostrSigner? {
        signer?.let { return it }

        val pubkey = keyManager.getPublicKeyHex() ?: return null

        val newSigner = if (keyManager.isAmberMode) {
            NostrSignerExternal(pubkey, AMBER_PACKAGE, contentResolver)
        } else {
            val privKeyHex = keyManager.getPrivateKeyHex() ?: return null
            NostrSignerInternal(KeyPair(privKey = privKeyHex.hexToByteArray()))
        }

        signer = newSigner
        return newSigner
    }

    /**
     * Sign an event template. Transparent for both login modes:
     * - nsec: signs locally via Dispatchers.Default (crypto is blocking)
     * - Amber: suspends while Quartz's NostrSignerExternal handles the NIP-55
     *   intent flow (Quartz manages its own timeouts internally)
     *
     * Returns the signed event, or null on failure/timeout/cancellation.
     */
    suspend fun <T : Event> sign(template: EventTemplate<T>): T? {
        val s = getOrCreateSigner() ?: return null
        return if (s is NostrSignerExternal) {
            // NostrSignerExternal.sign() is already a suspend fun that manages
            // its own intent lifecycle and timeout. Do NOT wrap in withTimeoutOrNull
            // — external cancellation can corrupt ForegroundRequestHandler state.
            runCatching { s.sign(template) }.getOrNull()
        } else {
            withContext(Dispatchers.Default) {
                runCatching { s.sign(template) }.getOrNull()
            }
        }
    }

    /**
     * Register the Activity's intent launcher so NostrSignerExternal can fire Amber intents.
     * Call from MainActivity onCreate (before setContent).
     */
    fun registerLauncher(launcher: (Intent) -> Unit) {
        if (keyManager.isAmberMode) {
            val s = getOrCreateSigner()
            (s as? NostrSignerExternal)?.registerForegroundLauncher(launcher)
        }
    }

    /**
     * Unregister the launcher (call in onDispose or Activity onDestroy).
     */
    fun unregisterLauncher(launcher: (Intent) -> Unit) {
        (signer as? NostrSignerExternal)?.unregisterForegroundLauncher(launcher)
    }

    /**
     * Feed Amber's result intent back to the external signer.
     * Call from the ActivityResultLauncher callback.
     */
    fun onAmberResult(data: Intent) {
        (signer as? NostrSignerExternal)?.newResponse(data)
    }

    /** Clear cached signer on logout. */
    fun clear() {
        signer = null
    }
}
```

- [ ] **Step 2: Provide ContentResolver in AppModule**

Add `ContentResolver` provider to `app/src/main/kotlin/com/unsilence/app/di/AppModule.kt`:

```kotlin
import android.content.ContentResolver
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

// Add inside AppModule object, after provideOkHttpClient():
@Provides
@Singleton
fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
    context.contentResolver
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/auth/SigningManager.kt
git commit -m "feat: add SigningManager with dual-mode nsec/Amber signing"
```

---

### Task 2: Wire Amber intent launcher in MainActivity

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/MainActivity.kt`

- [ ] **Step 1: Add SigningManager injection and ActivityResultLauncher**

In `MainActivity.kt`, add:

```kotlin
@Inject lateinit var signingManager: SigningManager
```

Inside `onCreate`, before `setContent`, register the launcher:

```kotlin
val amberSignLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    result.data?.let { signingManager.onAmberResult(it) }
}
```

Inside `setContent`, after `UnsilenceTheme`, add a `LaunchedEffect` or `DisposableEffect` to register/unregister the launcher:

```kotlin
DisposableEffect(Unit) {
    val launcher: (Intent) -> Unit = { intent -> amberSignLauncher.launch(intent) }
    signingManager.registerLauncher(launcher)
    onDispose { signingManager.unregisterLauncher(launcher) }
}
```

Full modified `MainActivity.kt`:

```kotlin
package com.unsilence.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.ui.onboarding.RootScreen
import com.unsilence.app.ui.theme.UnsilenceTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var relayPool: RelayPool
    @Inject lateinit var signingManager: SigningManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    relayPool.reconnectAll()
                }
            }
        )

        val amberSignLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            result.data?.let { signingManager.onAmberResult(it) }
        }

        setContent {
            UnsilenceTheme {
                DisposableEffect(Unit) {
                    val launcher: (Intent) -> Unit = { intent ->
                        amberSignLauncher.launch(intent)
                    }
                    signingManager.registerLauncher(launcher)
                    onDispose { signingManager.unregisterLauncher(launcher) }
                }
                RootScreen()
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/MainActivity.kt
git commit -m "feat: wire Amber signing intent launcher in MainActivity"
```

---

### Task 3: Refactor ComposeViewModel to use SigningManager

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/compose/ComposeViewModel.kt`

- [ ] **Step 1: Replace KeyManager + NostrSignerInternal with SigningManager**

In `ComposeViewModel`:

1. Replace constructor param `keyManager: KeyManager` with `signingManager: SigningManager` (keep `keyManager` only for `pubkeyHex`).
   Actually — `pubkeyHex` is used for the avatar. We still need `keyManager` for that. So ADD `signingManager` and keep `keyManager` for the pubkey read only.

2. Change `publishNote()` (lines 40-71):

Before (lines 41-48):
```kotlin
val privKeyHex  = keyManager.getPrivateKeyHex() ?: return@launch
val privKeyBytes = privKeyHex.hexToByteArray()
val keyPair     = KeyPair(privKey = privKeyBytes)
val signer      = NostrSignerInternal(keyPair)
val template    = TextNoteEvent.build(note = content)

val signed = runCatching { signer.sign(template) }.getOrNull() ?: return@launch
```

After:
```kotlin
val template = TextNoteEvent.build(note = content)
val signed   = signingManager.sign(template) ?: return@launch
```

3. Remove unused imports: `hexToByteArray`, `KeyPair`, `NostrSignerInternal`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/compose/ComposeViewModel.kt
git commit -m "refactor: ComposeViewModel uses SigningManager instead of inline signing"
```

---

### Task 4: Refactor NoteActionsViewModel to use SigningManager

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteActionsViewModel.kt`

- [ ] **Step 1: Add SigningManager, refactor react() and repost()**

1. Add `signingManager: SigningManager` to constructor (line 43-49). Keep `keyManager` for `pubkeyHex` on line 51.

2. In `react()` (lines 105-134), replace line 107:
   ```kotlin
   val signer = buildSigner() ?: return@launch
   ```
   And line 119:
   ```kotlin
   val signed = runCatching { signer.sign(template) }.getOrNull() ?: return@launch
   ```
   With:
   ```kotlin
   val signed = signingManager.sign(template) ?: return@launch
   ```
   Remove the `signer` variable entirely.

3. In `repost()` (lines 136-173), same pattern — replace lines 138 and 153.

4. Delete `buildSigner()` (lines 192-196).

5. Remove unused imports: `hexToByteArray`, `KeyPair`, `NostrSignerInternal`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/NoteActionsViewModel.kt
git commit -m "refactor: NoteActionsViewModel uses SigningManager instead of inline signing"
```

---

### Task 5: Refactor ThreadViewModel to use SigningManager

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/thread/ThreadViewModel.kt`

- [ ] **Step 1: Add SigningManager, refactor publishReply()**

1. Add `signingManager: SigningManager` to constructor (line 42-47). Keep `keyManager` for `pubkeyHex`.

2. In `publishReply()` (lines 88-123), replace lines 90-93 and 101:
   ```kotlin
   val privKeyHex   = keyManager.getPrivateKeyHex() ?: return@launch
   val privKeyBytes = privKeyHex.hexToByteArray()
   val keyPair      = KeyPair(privKey = privKeyBytes)
   val signer       = NostrSignerInternal(keyPair)
   ...
   val signed = runCatching { signer.sign(template) }.getOrNull() ?: return@launch
   ```
   With:
   ```kotlin
   val signed = signingManager.sign(template) ?: return@launch
   ```

3. Remove unused imports: `hexToByteArray`, `KeyPair`, `NostrSignerInternal`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/thread/ThreadViewModel.kt
git commit -m "refactor: ThreadViewModel uses SigningManager instead of inline signing"
```

---

### Task 6: Refactor ProfileViewModel to use SigningManager

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileViewModel.kt`

- [ ] **Step 1: Add SigningManager, refactor saveProfile()**

1. Add `signingManager: SigningManager` to constructor. Keep `keyManager` for `pubkeyHex`.

2. In `saveProfile()` (around line 91), replace lines 92-95 and 115:
   ```kotlin
   val privKeyHex   = keyManager.getPrivateKeyHex() ?: return@launch
   val privKeyBytes = privKeyHex.hexToByteArray()
   val keyPair      = KeyPair(privKey = privKeyBytes)
   val signer       = NostrSignerInternal(keyPair)
   ...
   val signed = runCatching { signer.sign(template) }.getOrNull() ?: return@launch
   ```
   With:
   ```kotlin
   val signed = signingManager.sign(template) ?: return@launch
   ```

3. Remove unused imports: `hexToByteArray`, `KeyPair`, `NostrSignerInternal`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileViewModel.kt
git commit -m "refactor: ProfileViewModel uses SigningManager instead of inline signing"
```

---

### Task 7: Refactor ZapRepository to use SigningManager

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/wallet/ZapRepository.kt`

- [ ] **Step 1: Add SigningManager, refactor zap()**

1. Add `signingManager: SigningManager` to constructor (line 35-41). Remove `keyManager` — it's only used by `buildSigner()`.

2. In `zap()` (lines 73-74), replace:
   ```kotlin
   val signer = buildSigner()
       ?: return Result.failure(IllegalStateException("No signing key"))
   ```

3. Replace line 90-91:
   ```kotlin
   val zapRequest = runCatching { signer.sign(template) }
       .getOrElse { e -> return Result.failure(e) }
   ```
   With:
   ```kotlin
   val zapRequest = signingManager.sign(template)
       ?: return Result.failure(IllegalStateException("Signing failed or timed out"))
   ```

4. Delete `buildSigner()` (lines 139-142).

5. Remove unused imports: `hexToByteArray`, `KeyPair`, `NostrSignerInternal`, `KeyManager`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/wallet/ZapRepository.kt
git commit -m "refactor: ZapRepository uses SigningManager instead of inline signing"
```

---

### Task 8: Clear SigningManager on logout

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/AppBootstrapper.kt`

- [ ] **Step 1: Add signingManager.clear() to teardown()**

1. Add `signingManager: SigningManager` to `AppBootstrapper` constructor.
2. In `teardown()`, add `signingManager.clear()` after `keyManager.clear()`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/AppBootstrapper.kt
git commit -m "feat: clear SigningManager cached signer on logout"
```

---

## Chunk 2: Feed Pagination + Default Feed

### Task 9: Parameterize DAO feed queries with limit

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/dao/EventDao.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/data/repository/EventRepository.kt`

- [ ] **Step 1: Add limit parameter to feedFlow()**

In `EventDao.kt`, change line 95 from `LIMIT 300` to `LIMIT :limit`, and add `limit: Int` parameter to `feedFlow()` (line 97):

```kotlin
    fun feedFlow(
        relayUrls: List<String>,
        kinds: List<Int>,
        sinceTimestamp: Long,
        requireReposts: Int,
        requireReactions: Int,
        requireReplies: Int,
        requireZaps: Int,
        limit: Int = 300,
    ): Flow<List<FeedRow>>
```

- [ ] **Step 2: Add limit parameter to followingFeedFlow()**

In `EventDao.kt`, change line 144 from `LIMIT 300` to `LIMIT :limit`, and add parameter:

```kotlin
    fun followingFeedFlow(limit: Int = 300): Flow<List<FeedRow>>
```

- [ ] **Step 3: Update EventRepository to pass limit through**

In `EventRepository.kt`, update `feedFlow()` (line 22):

```kotlin
    fun feedFlow(relayUrls: List<String>, filter: FeedFilter, limit: Int = 300): Flow<List<FeedRow>> {
        ...
        return eventDao.feedFlow(
            ...
            limit = limit,
        )
    }
```

Update `followingFeedFlow()` (line 40):

```kotlin
    fun followingFeedFlow(limit: Int = 300): Flow<List<FeedRow>> =
        eventDao.followingFeedFlow(limit)
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/db/dao/EventDao.kt
git add app/src/main/kotlin/com/unsilence/app/data/repository/EventRepository.kt
git commit -m "feat: parameterize feed query limits for pagination"
```

---

### Task 10: Add _displayLimit to FeedViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/FeedViewModel.kt`

- [ ] **Step 1: Add _displayLimit StateFlow and wire into feed flow**

1. Add after line 65 (`_filter`):
   ```kotlin
   private val _displayLimit = MutableStateFlow(200)
   ```

2. Update `loadMore()` (lines 119-124) to bump the display limit:
   ```kotlin
   fun loadMore() {
       val oldest = _uiState.value.events.lastOrNull()?.createdAt ?: return
       if (oldest == lastOldestTimestamp) return
       lastOldestTimestamp = oldest
       _displayLimit.value += 200
       relayPool.fetchOlderEvents(currentRelayUrls, oldest)
   }
   ```

3. Update the `combine` in init (line 135) to include `_displayLimit`:
   ```kotlin
   combine(_feedType, _filter, _displayLimit) { type, filter, limit ->
       Triple(type, filter, limit)
   }
       .flatMapLatest { (type, filter, limit) ->
   ```

4. In the reset block (lines 138-146), add:
   ```kotlin
   _displayLimit.value = 200
   ```
   Wait — this would cause an infinite loop since we're inside the `flatMapLatest` that reacts to `_displayLimit` changes. Instead, reset `_displayLimit` OUTSIDE the `flatMapLatest`, in `setFeedType()` and `updateFilter()`:

   Actually, the reset on lines 138-140 runs inside `flatMapLatest`. If we include `_displayLimit` in the combine, then resetting it inside flatMapLatest causes re-entry. Instead:

   **Better approach:** Don't include `_displayLimit` in the outer combine. Instead, use a separate combine inside each branch:

   Replace the `flatMapLatest` branches (lines 147-164):

   ```kotlin
   when (type) {
       is FeedType.Global -> {
           currentRelayUrls = urls
           _displayLimit.flatMapLatest { limit ->
               eventRepository.feedFlow(urls, filter, limit)
           }
       }
       is FeedType.Following -> {
           currentRelayUrls = emptyList()
           outboxRouter.start()
           _displayLimit.flatMapLatest { limit ->
               eventRepository.followingFeedFlow(limit)
           }
       }
       is FeedType.RelaySet -> {
           val setEntity = relaySetRepository.getById(type.id)
           val setUrls   = setEntity?.let { relaySetRepository.decodeUrls(it) } ?: urls
           currentRelayUrls = setUrls
           relayPool.connect(setUrls)
           _displayLimit.flatMapLatest { limit ->
               eventRepository.feedFlow(setUrls, filter, limit)
           }
       }
   }
   ```

   And reset `_displayLimit` in the outer reset block (after line 140):
   ```kotlin
   _displayLimit.value = 200
   ```

   This works because `_displayLimit` reset happens inside `flatMapLatest` of the outer combine (which only reacts to `_feedType` + `_filter`), and then the inner `flatMapLatest` on `_displayLimit` restarts with 200. Subsequent `loadMore()` calls bump `_displayLimit` which re-triggers only the inner `flatMapLatest`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/FeedViewModel.kt
git commit -m "feat: add growing _displayLimit for feed pagination"
```

---

### Task 11: Default feed type based on follows count

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/FeedViewModel.kt`

- [ ] **Step 1: Add FollowDao dependency and set default feed type**

1. Add `FollowDao` to constructor:
   ```kotlin
   class FeedViewModel @Inject constructor(
       private val relaySetRepository: RelaySetRepository,
       private val eventRepository: EventRepository,
       private val userRepository: UserRepository,
       private val relayPool: RelayPool,
       private val outboxRouter: OutboxRouter,
       private val followDao: FollowDao,
   ) : ViewModel() {
   ```

2. Add import:
   ```kotlin
   import com.unsilence.app.data.db.dao.FollowDao
   ```

3. At the start of the existing `init` block's `viewModelScope.launch` (after line 127, before `relaySetRepository.seedDefaults()`), add:
   ```kotlin
   val hasFollows = followDao.count() > 0
   if (hasFollows) _feedType.value = FeedType.Following
   ```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/FeedViewModel.kt
git commit -m "feat: default to Following feed when follows exist in Room"
```

---

### Task 12: Verify build compiles

- [ ] **Step 1: Check all imports resolve and no compilation errors**

Do NOT run `./gradlew` (Android Studio may be open). Instead, review each modified file for:
- Missing imports
- Unused imports removed
- Constructor parameters match Hilt injection graph
- No references to deleted `buildSigner()` methods

Files to verify:
1. `SigningManager.kt` — new file, imports Quartz classes
2. `MainActivity.kt` — new imports for `SigningManager`, `ActivityResultContracts`, `DisposableEffect`, `Intent`
3. `ComposeViewModel.kt` — removed `NostrSignerInternal` imports, added `SigningManager` import
4. `NoteActionsViewModel.kt` — same cleanup
5. `ThreadViewModel.kt` — same cleanup
6. `ProfileViewModel.kt` — same cleanup
7. `ZapRepository.kt` — removed `KeyManager` dep, added `SigningManager`
8. `AppBootstrapper.kt` — added `SigningManager` dep
9. `EventDao.kt` — new `limit` param in queries
10. `EventRepository.kt` — new `limit` param passthrough
11. `FeedViewModel.kt` — new `FollowDao` dep, `_displayLimit` wiring

- [ ] **Step 2: Commit any fixups**

```bash
git add -u
git commit -m "fix: resolve import and compilation issues from SigningManager refactor"
```
