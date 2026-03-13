# Sprint 19: Profile & Feed Reactivity Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix repost author profiles (A3), add NIP-65 outbox fetching + pagination to user profiles (A4), and add profile staleness TTL.

**Architecture:** All fixes follow Relay → EventProcessor → Room → Flow/StateFlow → Compose UI. No schema migrations needed — all changes are code-only.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Hilt, OkHttp WebSockets, Coroutines/Flow

**Spec:** `docs/superpowers/specs/2026-03-13-sprint19-profile-feed-reactivity-design.md`

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `app/src/main/kotlin/com/unsilence/app/data/db/dao/UserDao.kt` | Modify | Add `stalePubkeys()` query |
| `app/src/main/kotlin/com/unsilence/app/data/db/dao/RelayListDao.kt` | Modify | Add `getByPubkey()` query |
| `app/src/main/kotlin/com/unsilence/app/data/db/dao/EventDao.kt` | Modify | Add `limit` param to `userPostsFlow()` |
| `app/src/main/kotlin/com/unsilence/app/data/repository/UserRepository.kt` | Modify | Add TTL logic, relay list access |
| `app/src/main/kotlin/com/unsilence/app/data/repository/EventRepository.kt` | Modify | Thread `limit` param through |
| `app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt` | Modify | Connection cap, `fetchOlderPosts()` |
| `app/src/main/kotlin/com/unsilence/app/ui/feed/FeedViewModel.kt` | Modify | Add `profileFlow()` with caching |
| `app/src/main/kotlin/com/unsilence/app/ui/feed/FeedScreen.kt` | Modify | Collect profile flow for kind-6 items |
| `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt` | Modify | Add `originalAuthorProfile` param |
| `app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileViewModel.kt` | Modify | Outbox lookup, pagination, growing window, profileFlow |
| `app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileScreen.kt` | Modify | Scroll-to-bottom trigger, pass profile to NoteCard |
| `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileViewModel.kt` | Modify | Add `profileFlow()` for own-profile reposts |
| `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileScreen.kt` | Modify | Pass profile to NoteCard for own-profile reposts |

---

## Chunk 1: Foundation (DAO + Repository Layer)

### Task 1: Profile Staleness TTL — DAO and Repository

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/dao/UserDao.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/data/repository/UserRepository.kt`

- [ ] **Step 1: Add `stalePubkeys()` query to UserDao**

In `UserDao.kt`, add after the `allPubkeys()` method (after line 27):

```kotlin
/** Pubkeys with profiles older than [olderThan] epoch seconds. */
@Query("SELECT pubkey FROM users WHERE updated_at < :olderThan")
suspend fun stalePubkeys(olderThan: Long): List<String>
```

- [ ] **Step 2: Update `fetchMissingProfiles()` in UserRepository**

In `UserRepository.kt`, add the `RelayListDao` import and constructor parameter, then rewrite `fetchMissingProfiles()`. The file becomes:

```kotlin
package com.unsilence.app.data.repository

import com.unsilence.app.data.db.dao.RelayListDao
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.db.entity.RelayListEntity
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.RelayPool
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val userDao: UserDao,
    private val relayListDao: RelayListDao,
    private val relayPool: RelayPool,
) {
    fun userFlow(pubkey: String): Flow<UserEntity?> = userDao.userFlow(pubkey)

    /** Returns the cached lightning address (lud16) for [pubkey], or null if not yet loaded. */
    suspend fun getUserLud16(pubkey: String): String? = userDao.getUser(pubkey)?.lud16

    /** NIP-50 profile search — re-emits as search results arrive from the relay. */
    fun searchUsers(query: String): Flow<List<UserEntity>> = userDao.searchUsers(query)

    /** Look up a user's NIP-65 relay list from Room cache. */
    suspend fun getRelayList(pubkey: String): RelayListEntity? =
        relayListDao.getByPubkey(pubkey)

    /**
     * Requests profiles for pubkeys not yet cached OR stale (>6 hours).
     * The fetched kind-0 events will arrive via EventProcessor → Room.
     */
    suspend fun fetchMissingProfiles(pubkeys: List<String>) {
        val cached = userDao.allPubkeys().toSet()
        val staleThreshold = System.currentTimeMillis() / 1000 - (6 * 3600)
        val stale = userDao.stalePubkeys(staleThreshold).toSet()
        val toFetch = pubkeys.filter { it !in cached || it in stale }
        if (toFetch.isNotEmpty()) {
            relayPool.fetchProfiles(toFetch)
        }
    }
}
```

Key changes:
- Added `RelayListDao` to constructor (Hilt auto-injects — already provided in `DatabaseModule`)
- Added `getRelayList()` method
- `fetchMissingProfiles()` now unions "missing" and "stale" pubkeys

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/db/dao/UserDao.kt \
       app/src/main/kotlin/com/unsilence/app/data/repository/UserRepository.kt
git commit -m "feat: profile staleness TTL — re-fetch profiles older than 6 hours"
```

---

### Task 2: RelayListDao — Add `getByPubkey()` Query

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/dao/RelayListDao.kt`

- [ ] **Step 1: Add `getByPubkey()` to RelayListDao**

In `RelayListDao.kt`, add after `getAll()` (after line 21):

```kotlin
/** Single relay list lookup by pubkey. Returns null if not cached. */
@Query("SELECT * FROM relay_list_metadata WHERE pubkey = :pubkey")
suspend fun getByPubkey(pubkey: String): RelayListEntity?
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/db/dao/RelayListDao.kt
git commit -m "feat: add RelayListDao.getByPubkey() for NIP-65 outbox lookup"
```

---

### Task 3: EventDao + EventRepository — Add `limit` Parameter to `userPostsFlow()`

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/dao/EventDao.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/data/repository/EventRepository.kt`

- [ ] **Step 1: Add `limit` parameter to `userPostsFlow()` in EventDao**

In `EventDao.kt`, change the `userPostsFlow` query (lines 149-184). Replace `LIMIT 100` with `:limit` and add the parameter:

Change:
```kotlin
    LIMIT 100
""")
fun userPostsFlow(pubkey: String): Flow<List<FeedRow>>
```

To:
```kotlin
    LIMIT :limit
""")
fun userPostsFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>>
```

The default `limit = 200` preserves backward compatibility for any other callsite.

- [ ] **Step 2: Thread `limit` parameter through EventRepository**

In `EventRepository.kt`, change `userPostsFlow()` (line 43-44):

Change:
```kotlin
fun userPostsFlow(pubkey: String): Flow<List<FeedRow>> =
    eventDao.userPostsFlow(pubkey)
```

To:
```kotlin
fun userPostsFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>> =
    eventDao.userPostsFlow(pubkey, limit)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/db/dao/EventDao.kt \
       app/src/main/kotlin/com/unsilence/app/data/repository/EventRepository.kt
git commit -m "feat: add limit parameter to userPostsFlow() for pagination"
```

---

## Chunk 2: RelayPool Changes

### Task 4: RelayPool — Connection Cap + `fetchOlderPosts()`

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt`

- [ ] **Step 1: Add connection cap to `connect()`**

In `RelayPool.kt`, modify the `connect()` method (lines 92-104). Add a size check inside the for loop:

Change:
```kotlin
fun connect(relayUrls: List<String>) {
    for (url in relayUrls) {
        if (connections.containsKey(url)) continue
        val conn = RelayConnection(url, okHttpClient)
        connections[url] = conn
        scope.launch {
            conn.connect()
            subscribeAfterConnect(conn)
            listenForEvents(conn)
        }
    }
    Log.d(TAG, "Pool has ${connections.size} connections")
}
```

To:
```kotlin
fun connect(relayUrls: List<String>) {
    for (url in relayUrls) {
        if (connections.containsKey(url)) continue
        if (connections.size >= 25) {
            Log.d(TAG, "Connection cap (25) reached — skipping $url")
            continue
        }
        val conn = RelayConnection(url, okHttpClient)
        connections[url] = conn
        scope.launch {
            conn.connect()
            subscribeAfterConnect(conn)
            listenForEvents(conn)
        }
    }
    Log.d(TAG, "Pool has ${connections.size} connections")
}
```

- [ ] **Step 2: Add `fetchOlderPosts()` method**

In `RelayPool.kt`, add after the existing `fetchUserPosts()` method (after line 507):

```kotlin
/**
 * Pagination for user profile view.
 * Fetches posts by [pubkey] older than [untilTimestamp].
 * One-shot subscription — closes on EOSE (prefix "older-" matches isOneShotSubscription).
 */
fun fetchOlderPosts(pubkey: String, untilTimestamp: Long) {
    val req = buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive("older-user-${System.currentTimeMillis()}"))
        add(buildJsonObject {
            put("kinds", buildJsonArray {
                add(JsonPrimitive(1))
                add(JsonPrimitive(6))
                add(JsonPrimitive(20))
                add(JsonPrimitive(21))
                add(JsonPrimitive(30023))
            })
            put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
            put("until", JsonPrimitive(untilTimestamp))
            put("limit", JsonPrimitive(200))
        })
    }.toString()
    connections.values.forEach { it.send(req) }
    Log.d(TAG, "Fetching older posts for $pubkey until $untilTimestamp from ${connections.size} relay(s)")
}
```

Note: the subscription ID starts with `"older-"` which already matches `isOneShotSubscription()` (line 201), so EOSE will close it automatically. Kinds match `fetchUserPosts()` (1, 6, 20, 21, 30023) for consistency.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt
git commit -m "feat: RelayPool connection cap (25) and fetchOlderPosts() for profile pagination"
```

---

## Chunk 3: A3 Fix (Repost Author Profile)

### Task 5: FeedViewModel — Add `profileFlow()` with Caching

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/FeedViewModel.kt`

- [ ] **Step 1: Add imports and `profileFlow()` method**

In `FeedViewModel.kt`, add these imports (after the existing imports, around line 31):

```kotlin
import com.unsilence.app.data.db.entity.UserEntity
import java.util.concurrent.ConcurrentHashMap
```

Then add the `profileFlow()` method inside the `FeedViewModel` class body, after the `feedTypeLabel` property (after line 88):

```kotlin
// ── Profile lookup for repost original authors ──────────────────────
private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

/**
 * Returns a cached StateFlow for the given pubkey's profile.
 * Used by LazyColumn items to resolve original author info on kind-6 reposts.
 * WhileSubscribed(5000) keeps the flow alive briefly when items scroll off-screen.
 */
fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
    profileCache.getOrPut(pubkey) {
        userRepository.userFlow(pubkey)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/FeedViewModel.kt
git commit -m "feat: FeedViewModel.profileFlow() with ConcurrentHashMap caching"
```

---

### Task 6: NoteCard — Add `originalAuthorProfile` Parameter

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt`

- [ ] **Step 1: Add parameter to NoteCard signature**

In `NoteCard.kt`, add the import at the top of the file:

```kotlin
import com.unsilence.app.data.db.entity.UserEntity
```

Then add `originalAuthorProfile` parameter to the function signature (after line 150, after `isNwcConfigured`):

```kotlin
    isNwcConfigured: Boolean = false,
    originalAuthorProfile: UserEntity? = null,
)
```

- [ ] **Step 2: Fix avatar for reposts**

In `NoteCard.kt`, find the avatar section (around line 243-247). Change:

```kotlin
AvatarImage(
    pubkey   = effectivePubkey,
    picture  = if (boostedJson == null) row.authorPicture else null,
    modifier = Modifier.size(Sizing.avatar),
)
```

To:

```kotlin
AvatarImage(
    pubkey   = effectivePubkey,
    picture  = if (boostedJson == null) row.authorPicture else originalAuthorProfile?.picture,
    modifier = Modifier.size(Sizing.avatar),
)
```

- [ ] **Step 3: Fix name display for reposts**

In `NoteCard.kt` line 253-254, the name `Text` composable currently shows truncated hex for reposts:

```kotlin
Text(
    text       = if (boostedJson != null) "${effectivePubkey.take(6)}…${effectivePubkey.takeLast(4)}" else (row.displayName ?: "${row.pubkey.take(6)}…${row.pubkey.takeLast(4)}"),
```

`row.displayName` is a **private extension property** defined at line 740 of NoteCard.kt:
```kotlin
private val FeedRow.displayName: String?
    get() = authorDisplayName?.takeIf { it.isNotBlank() }
         ?: authorName?.takeIf { it.isNotBlank() }
```

Change the `text` parameter to:

```kotlin
Text(
    text       = if (boostedJson != null) {
        originalAuthorProfile?.displayName?.takeIf { it.isNotBlank() }
            ?: originalAuthorProfile?.name?.takeIf { it.isNotBlank() }
            ?: "${effectivePubkey.take(6)}…${effectivePubkey.takeLast(4)}"
    } else {
        row.displayName ?: "${row.pubkey.take(6)}…${row.pubkey.takeLast(4)}"
    },
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt
git commit -m "fix(A3): NoteCard shows original author avatar + name on reposts"
```

---

### Task 7: FeedScreen — Collect Profile Flow for Kind-6 Items

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/FeedScreen.kt`

- [ ] **Step 1: Add imports**

In `FeedScreen.kt`, add at the top (only add what's missing):

```kotlin
import androidx.compose.runtime.collectAsState
import com.unsilence.app.data.relay.NostrJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
```

- [ ] **Step 2: Collect profile and pass to NoteCard**

In `FeedScreen.kt`, find the `items()` lambda in the LazyColumn (lines 85-110). The current callsite uses the item-based overload:

```kotlin
items(
    items = state.events,
    key   = { it.id },
) { row ->
    if (row.kind == 30023) {
        ArticleCard(...)
    } else {
        NoteCard(
            row             = row,
            ...
        )
    }
}
```

Add the profile resolution INSIDE the item block, before the `if` check:

```kotlin
items(
    items = state.events,
    key   = { it.id },
) { row ->
    // Resolve original author profile for kind-6 reposts
    val originalAuthorProfile = if (row.kind == 6 && row.content.isNotBlank()) {
        val embeddedPubkey = runCatching {
            NostrJson.parseToJsonElement(row.content).jsonObject["pubkey"]?.jsonPrimitive?.content
        }.getOrNull()
        embeddedPubkey?.let { viewModel.profileFlow(it).collectAsState().value }
    } else null

    if (row.kind == 30023) {
        ArticleCard(
            row     = row,
            onClick = { articleRow = row },
        )
    } else {
        NoteCard(
            row                    = row,
            originalAuthorProfile  = originalAuthorProfile,
            onNoteClick            = onNoteClick,
            onAuthorClick          = onAuthorClick,
            hasReacted             = row.engagementId in reactedIds,
            hasReposted            = row.engagementId in repostedIds,
            hasZapped              = row.engagementId in zappedIds,
            isNwcConfigured        = isNwcConfigured,
            onReact                = { actionsViewModel.react(row.id, row.pubkey) },
            onRepost               = { actionsViewModel.repost(row.id, row.pubkey, row.relayUrl) },
            onQuote                = onQuote,
            onZap                  = { amt -> actionsViewModel.zap(row.id, row.pubkey, row.relayUrl, amt) },
            onSaveNwcUri           = { uri -> actionsViewModel.saveNwcUri(uri) },
        )
    }
}
```

The `profileFlow()` returns a `StateFlow` (already cached in ViewModel), and `collectAsState()` subscribes to it within the composable scope. When the profile arrives in Room, the StateFlow updates and NoteCard recomposes with the avatar/name.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/FeedScreen.kt
git commit -m "fix(A3): FeedScreen resolves original author profile for kind-6 reposts"
```

---

## Chunk 4: A4 Fix (User Profile Outbox + Pagination)

### Task 8: UserProfileViewModel — Outbox Lookup + Growing Window + Pagination

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileViewModel.kt`

- [ ] **Step 1: Rewrite UserProfileViewModel**

Replace the entire file with:

```kotlin
package com.unsilence.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.UserRepository
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

private const val TAG = "UserProfileVM"

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository,
    private val relayPool: RelayPool,
) : ViewModel() {

    private val _pubkeyHex = MutableStateFlow<String?>(null)
    val pubkeyHex: StateFlow<String?> = _pubkeyHex.asStateFlow()

    val npub: String?
        get() = _pubkeyHex.value?.let { hex ->
            runCatching { hex.hexToByteArray().toNpub() }.getOrNull()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val userFlow: Flow<UserEntity?> = _pubkeyHex
        .filterNotNull()
        .flatMapLatest { userRepository.userFlow(it) }

    // ── Growing query window for pagination ────────────────────────────
    private val _displayLimit = MutableStateFlow(200)

    @OptIn(ExperimentalCoroutinesApi::class)
    val postsFlow: Flow<List<FeedRow>> =
        combine(_pubkeyHex.filterNotNull(), _displayLimit) { pk, limit -> pk to limit }
            .flatMapLatest { (pk, limit) -> eventRepository.userPostsFlow(pk, limit) }

    // ── Pagination state ───────────────────────────────────────────────
    private var oldestTimestamp = Long.MAX_VALUE
    private var fetching = false

    // ── Profile lookup for repost original authors ─────────────────────
    private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        profileCache.getOrPut(pubkey) {
            userRepository.userFlow(pubkey)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    init {
        // Fetch missing profiles for repost original authors as posts arrive
        viewModelScope.launch {
            postsFlow.collectLatest { rows ->
                val pubkeys = rows.flatMap { row ->
                    val embedded = if (row.kind == 6 && row.content.isNotBlank()) {
                        runCatching {
                            NostrJson.parseToJsonElement(row.content)
                                .jsonObject["pubkey"]?.jsonPrimitive?.content
                        }.getOrNull()
                    } else null
                    listOfNotNull(row.pubkey, embedded)
                }.distinct()
                userRepository.fetchMissingProfiles(pubkeys)
            }
        }
    }

    fun loadProfile(pubkey: String) {
        if (_pubkeyHex.value == pubkey) return
        _pubkeyHex.value = pubkey
        // Reset pagination state for new profile
        _displayLimit.value = 200
        oldestTimestamp = Long.MAX_VALUE
        fetching = false

        viewModelScope.launch {
            userRepository.fetchMissingProfiles(listOf(pubkey))
            connectOutboxRelays(pubkey)
            relayPool.fetchUserPosts(pubkey)
        }
    }

    /**
     * Called when user scrolls near bottom of post list.
     * 1. Increases the Room query limit (growing window)
     * 2. Fetches older posts from relays
     */
    fun loadMore(currentOldest: Long) {
        if (fetching || currentOldest >= oldestTimestamp) return
        fetching = true
        oldestTimestamp = currentOldest
        _displayLimit.value += 200

        val pubkey = _pubkeyHex.value ?: return
        relayPool.fetchOlderPosts(pubkey, currentOldest)

        // Allow next fetch after relay responses have had time to arrive
        viewModelScope.launch {
            delay(2_000)
            fetching = false
        }
    }

    /**
     * NIP-65 outbox routing: connect to the user's declared write relays
     * so we can fetch their posts from where they actually publish.
     */
    private suspend fun connectOutboxRelays(pubkey: String) {
        // Step 1: check Room cache
        var relayList = userRepository.getRelayList(pubkey)

        // Step 2: if not cached, fetch from indexer relays and wait
        if (relayList == null) {
            relayPool.fetchRelayLists(listOf(pubkey))
            relayList = withTimeoutOrNull(5_000) {
                var result: com.unsilence.app.data.db.entity.RelayListEntity? = null
                while (result == null) {
                    delay(500)
                    result = userRepository.getRelayList(pubkey)
                }
                result
            }
        }

        if (relayList == null) {
            Log.d(TAG, "No relay list found for $pubkey — fetching from connected relays only")
            return
        }

        // Step 3: parse write relay URLs and connect
        val writeUrls = runCatching {
            Json.decodeFromString<List<String>>(relayList.writeRelays)
        }.getOrDefault(emptyList())

        if (writeUrls.isNotEmpty()) {
            Log.d(TAG, "Connecting to ${writeUrls.size} outbox relays for $pubkey")
            relayPool.connect(writeUrls)
        }
    }
}
```

Key changes from original:
- Added `_displayLimit` MutableStateFlow for growing query window
- `postsFlow` uses `combine(_pubkeyHex, _displayLimit).flatMapLatest { ... }`
- Added `loadMore()` with fetching guard, limit bump, and relay fetch
- Added `connectOutboxRelays()` for NIP-65 outbox lookup
- Added `profileFlow()` for repost author resolution (same pattern as FeedViewModel)
- Added `init` block that collects `postsFlow` and calls `fetchMissingProfiles()` for all pubkeys including embedded repost authors
- `loadProfile()` resets pagination state

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileViewModel.kt
git commit -m "feat(A4): outbox relay fetching, growing query window, scroll pagination"
```

---

### Task 9: UserProfileScreen — Scroll-to-Bottom + Pass Profile to NoteCard

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileScreen.kt`

- [ ] **Step 1: Add imports**

In `UserProfileScreen.kt`, add these imports (only add what's missing):

```kotlin
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.unsilence.app.data.relay.NostrJson
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
```

- [ ] **Step 2: Add LazyListState and scroll detection**

Inside the `UserProfileScreen` composable, after the state collection block (after line 79), add:

```kotlin
val listState = rememberLazyListState()

// Trigger loadMore() when scrolled near bottom
val shouldLoadMore = remember {
    derivedStateOf {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val totalItems = listState.layoutInfo.totalItemsCount
        totalItems > 0 && lastVisible >= totalItems - 5
    }
}

LaunchedEffect(Unit) {
    snapshotFlow { shouldLoadMore.value }
        .distinctUntilChanged()
        .collect { shouldLoad ->
            if (shouldLoad && posts.isNotEmpty()) {
                val oldest = posts.last().createdAt
                viewModel.loadMore(oldest)
            }
        }
}
```

- [ ] **Step 3: Pass `listState` to LazyColumn**

Find the LazyColumn (around line 93) and add `state = listState`:

```kotlin
LazyColumn(
    state               = listState,
    modifier            = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
```

- [ ] **Step 4: Resolve original author profile for reposts and pass to NoteCard**

Find the `items()` block that renders posts with NoteCard (lines 254-267). The current code uses the item-based overload:

```kotlin
items(items = posts, key = { it.id }) { row ->
    NoteCard(
        row             = row,
        ...
    )
}
```

Add profile resolution and pass to NoteCard:

```kotlin
items(items = posts, key = { it.id }) { row ->
    // Resolve original author profile for kind-6 reposts
    val originalAuthorProfile = if (row.kind == 6 && row.content.isNotBlank()) {
        val embeddedPubkey = runCatching {
            NostrJson.parseToJsonElement(row.content).jsonObject["pubkey"]?.jsonPrimitive?.content
        }.getOrNull()
        embeddedPubkey?.let { viewModel.profileFlow(it).collectAsState().value }
    } else null

    NoteCard(
        row                    = row,
        originalAuthorProfile  = originalAuthorProfile,
        onAuthorClick          = onAuthorClick,
        hasReacted             = row.engagementId in reactedIds,
        hasReposted            = row.engagementId in repostedIds,
        hasZapped              = row.engagementId in zappedIds,
        isNwcConfigured        = isNwcConfigured,
        onReact                = { actionsViewModel.react(row.id, row.pubkey) },
        onRepost               = { actionsViewModel.repost(row.id, row.pubkey, row.relayUrl) },
        onZap                  = { amt -> actionsViewModel.zap(row.id, row.pubkey, row.relayUrl, amt) },
        onSaveNwcUri           = { uri -> actionsViewModel.saveNwcUri(uri) },
    )
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileScreen.kt
git commit -m "feat(A4): scroll-to-bottom pagination + repost author profile on user profile screen"
```

---

## Chunk 5: Own-Profile Repost Fix

### Task 10: ProfileViewModel + ProfileScreen — Repost Author Profiles on Own Profile

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileViewModel.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileScreen.kt`

The logged-in user's own profile screen (`ProfileScreen.kt`) uses `ProfileViewModel`, which does not have `profileFlow()`. Without this, reposts on the user's own profile will still show truncated hex.

- [ ] **Step 1: Add `profileFlow()` to ProfileViewModel**

In `ProfileViewModel.kt`, add these imports:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap
```

Then add the `profileFlow()` method inside the class body (after `postsFlow`, around line 48):

```kotlin
// ── Profile lookup for repost original authors ──────────────────────
private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
    profileCache.getOrPut(pubkey) {
        userRepository.userFlow(pubkey)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }
```

- [ ] **Step 2: Add profile resolution to ProfileScreen NoteCard callsite**

In `ProfileScreen.kt`, add imports (only add what's missing):

```kotlin
import androidx.compose.runtime.collectAsState
import com.unsilence.app.data.relay.NostrJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
```

Find the `items()` block (line 269-282) and add profile resolution:

```kotlin
items(items = posts, key = { it.id }) { row ->
    // Resolve original author profile for kind-6 reposts
    val originalAuthorProfile = if (row.kind == 6 && row.content.isNotBlank()) {
        val embeddedPubkey = runCatching {
            NostrJson.parseToJsonElement(row.content).jsonObject["pubkey"]?.jsonPrimitive?.content
        }.getOrNull()
        embeddedPubkey?.let { viewModel.profileFlow(it).collectAsState().value }
    } else null

    NoteCard(
        row                    = row,
        originalAuthorProfile  = originalAuthorProfile,
        onAuthorClick          = onAuthorClick,
        hasReacted             = row.engagementId in reactedIds,
        hasReposted            = row.engagementId in repostedIds,
        hasZapped              = row.engagementId in zappedIds,
        isNwcConfigured        = isNwcConfigured,
        onReact                = { actionsViewModel.react(row.id, row.pubkey) },
        onRepost               = { actionsViewModel.repost(row.id, row.pubkey, row.relayUrl) },
        onZap                  = { amt -> actionsViewModel.zap(row.id, row.pubkey, row.relayUrl, amt) },
        onSaveNwcUri           = { uri -> actionsViewModel.saveNwcUri(uri) },
    )
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileViewModel.kt \
       app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileScreen.kt
git commit -m "fix(A3): own-profile reposts show original author avatar + name"
```

**Note:** ThreadScreen.kt and SearchScreen.kt also call NoteCard but are lower priority — kind-6 reposts rarely appear as thread OPs or search results. The `originalAuthorProfile` parameter defaults to `null`, so these screens will show truncated hex for reposts (existing behavior). Can be fixed in a future sprint if needed.

---

## Manual Verification

After all tasks are committed, verify on the emulator:

1. **A3 — Repost avatars on Global feed:** Open Global feed, find kind-6 reposts. Verify:
   - Original author shows avatar (not identicon fallback)
   - Original author shows display name (not truncated hex)
   - "X boosted" header still shows the reposter's name
   - Scroll through feed — no crashes or visual glitches

2. **A3 — Repost avatars on own profile:** Open own profile, find kind-6 reposts. Verify same as above.

3. **A3 — Repost avatars on other user profiles:** Open another user's profile. Verify same as above.

4. **A4 — User profile posts:** Tap on a user's avatar to open their profile. Verify:
   - Posts load (should see more than ~4 days of history)
   - Scroll to bottom — more posts load (check Logcat for "Fetching older posts" log)
   - Check Logcat for "Connecting to N outbox relays" — confirms NIP-65 lookup
   - If user posts from niche relays, their posts should appear

5. **A4 — Connection cap:** Browse 5+ profiles of users on different obscure relays. Check Logcat for "Connection cap (25) reached" — should appear if pool grows to 25.

6. **Profile TTL:** Find a user whose profile you've viewed before. After 6+ hours, their profile should be re-fetched (check Logcat for profile fetch requests).

7. **Regression check:** Navigate to Following feed, thread view, notifications, search — all should work normally.
