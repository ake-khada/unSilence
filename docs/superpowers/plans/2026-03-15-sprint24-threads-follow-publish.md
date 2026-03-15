# Sprint 24: Thread Nesting, Follow/Unfollow, Publish Replaceable Kinds

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add visual thread nesting with depth indicators, follow/unfollow buttons on user profiles, and publish replaceable events (kind-0, kind-3, kind-10002) to the network.

**Architecture:** Thread nesting is a pure ViewModel+UI change — flatten the tree with depth annotations, render with indents and vertical lines. Follow/Unfollow adds a reactive button on UserProfileScreen backed by FollowDao + kind-3 publishing. Replaceable event publishing adds a `publishToRelays()` helper on RelayPool that targets write relays + indexer relays, then wires it into ProfileViewModel.saveProfile() and a new relay management screen.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Hilt, Quartz (Nostr), OkHttp WebSockets, ExoPlayer

---

## Chunk 1: Feature 1 — Nested Thread Replies

### Task 1: Flatten thread tree with depth annotations in ThreadViewModel

**Files:**
- Modify: `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/ui/thread/ThreadViewModel.kt`

The current `ThreadUiState.replies` is a flat `List<FeedRow>` sorted by `created_at ASC`. The SQL already returns `reply_to_id` for each row. We need to build a tree and flatten it with depth.

- [ ] **Step 1: Add DepthRow data class and update ThreadUiState**

In `ThreadViewModel.kt`, add before the class:

```kotlin
data class DepthRow(val row: FeedRow, val depth: Int)
```

Change `ThreadUiState`:

```kotlin
data class ThreadUiState(
    val focusedNote: FeedRow? = null,
    val replies: List<DepthRow> = emptyList(),
    val loading: Boolean = true,
)
```

- [ ] **Step 2: Implement tree flattening in the collect block**

Replace the `.collect` block inside `init`:

```kotlin
.collect { rows ->
    val focusedId = eventIdFlow.value ?: return@collect
    val focused = rows.firstOrNull { it.id == focusedId }
    // Filter to kind-1 only, exclude the focused note
    val replyRows = rows.filter { it.id != focusedId && it.kind == 1 }

    // Build parent→children map
    val childrenOf = replyRows.groupBy { it.replyToId ?: it.rootId ?: focusedId }
        .mapValues { (_, v) -> v.sortedBy { it.createdAt } }

    // DFS flatten with depth
    val flatList = mutableListOf<DepthRow>()
    fun walk(parentId: String, depth: Int) {
        childrenOf[parentId]?.forEach { row ->
            flatList.add(DepthRow(row, depth.coerceAtMost(4)))
            walk(row.id, depth + 1)
        }
    }
    walk(focusedId, 1)

    _uiState.value = ThreadUiState(
        focusedNote = focused,
        replies     = flatList,
        loading     = false,
    )
}
```

- [ ] **Step 3: Build and verify compilation**

Run: `./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk assembleDebug 2>&1 | grep -E "^e:|BUILD"`
Expected: BUILD SUCCESSFUL (ThreadScreen will have type errors — that's next)

### Task 2: Render nested replies in ThreadScreen

**Files:**
- Modify: `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/ui/thread/ThreadScreen.kt`

- [ ] **Step 1: Add drawBehind import and update reply rendering**

Add imports at top:

```kotlin
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
```

Replace the `items(state.replies, key = { it.id }) { reply ->` block (lines 158-175) with:

```kotlin
items(state.replies, key = { it.row.id }) { depthRow ->
    val reply = depthRow.row
    val depth = depthRow.depth
    val indent = (depth * 20).dp
    val lineColor = Color.White.copy(alpha = 0.06f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // Draw vertical nesting lines for each depth level
                for (d in 1..depth) {
                    val x = (d * 20).dp.toPx()
                    drawLine(
                        color       = lineColor,
                        start       = Offset(x, 0f),
                        end         = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .padding(start = indent),
    ) {
        NoteCard(
            row             = reply,
            onAuthorClick   = onAuthorClick,
            hasReacted      = reply.engagementId in reactedIds,
            hasReposted     = reply.engagementId in repostedIds,
            hasZapped       = reply.engagementId in zappedIds,
            isNwcConfigured = isNwcConfigured,
            onReact         = { actionsViewModel.react(reply.id, reply.pubkey) },
            onRepost        = { actionsViewModel.repost(reply.id, reply.pubkey, reply.relayUrl) },
            onQuote         = onQuote,
            onZap           = { amt -> actionsViewModel.zap(reply.id, reply.pubkey, reply.relayUrl, amt) },
            onSaveNwcUri    = { uri -> actionsViewModel.saveNwcUri(uri) },
            lookupProfile   = actionsViewModel::lookupProfile,
            lookupEvent     = actionsViewModel::lookupEvent,
            fetchOgMetadata = actionsViewModel::fetchOgMetadata,
        )
    }
}
```

- [ ] **Step 2: Update reply count to use new structure**

Change `state.replies.size` reference (line 149) — it should still work since `DepthRow` list `.size` is correct. Just verify:

```kotlin
"${state.replies.size} ${if (state.replies.size == 1) "reply" else "replies"}"
```

- [ ] **Step 3: Build and verify**

Run: `./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk assembleDebug 2>&1 | grep -E "^e:|BUILD"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/thread/ThreadViewModel.kt \
       app/src/main/kotlin/com/unsilence/app/ui/thread/ThreadScreen.kt
git commit -m "feat: nested thread replies with depth indentation and vertical lines"
```

---

## Chunk 2: Feature 2 — Follow/Unfollow Button

### Task 3: Add isFollowing query to FollowDao

**Files:**
- Modify: `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/data/db/dao/FollowDao.kt`

- [ ] **Step 1: Add reactive isFollowing query**

Add to `FollowDao`:

```kotlin
/** Reactive check: does this pubkey exist in the follows table? */
@Query("SELECT COUNT(*) > 0 FROM follows WHERE pubkey = :pubkey")
abstract fun isFollowingFlow(pubkey: String): Flow<Boolean>

@Insert(onConflict = OnConflictStrategy.REPLACE)
abstract suspend fun insert(follow: FollowEntity)

@Query("DELETE FROM follows WHERE pubkey = :pubkey")
abstract suspend fun delete(pubkey: String)
```

### Task 4: Add publishToRelays() to RelayPool

**Files:**
- Modify: `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt`

- [ ] **Step 1: Add targeted publish method**

Add below the existing `publish()` method:

```kotlin
/**
 * Publish an event to specific relay URLs. Connects if not already connected.
 * Used for replaceable events (kind 0, 3, 10002) that target write + indexer relays.
 */
fun publishToRelays(eventJson: String, relayUrls: List<String>) {
    val cmd = buildJsonArray {
        add(JsonPrimitive("EVENT"))
        add(NostrJson.parseToJsonElement(eventJson))
    }.toString()

    for (url in relayUrls) {
        val conn = connections[url]
        if (conn != null) {
            conn.send(cmd)
        } else {
            // Connect and send once ready
            scope.launch {
                val newConn = connectSingle(url)
                newConn?.send(cmd)
            }
        }
    }
    Log.d(TAG, "Published event to ${relayUrls.size} targeted relay(s)")
}
```

Note: If `connectSingle` doesn't exist as a private method, use the existing `connect()` pattern — connect to the URL list first, then send. Adjust based on actual RelayPool internals. An alternative simpler approach:

```kotlin
fun publishToRelays(eventJson: String, relayUrls: List<String>) {
    val cmd = buildJsonArray {
        add(JsonPrimitive("EVENT"))
        add(NostrJson.parseToJsonElement(eventJson))
    }.toString()

    // Send to already-connected relays immediately
    val sent = mutableSetOf<String>()
    for (url in relayUrls) {
        connections[url]?.let { it.send(cmd); sent.add(url) }
    }

    // Connect to remaining relays and send
    val remaining = relayUrls.filter { it !in sent }
    if (remaining.isNotEmpty()) {
        scope.launch {
            connect(remaining)
            delay(2_000) // Allow connections to establish
            for (url in remaining) {
                connections[url]?.send(cmd)
            }
        }
    }

    Log.d(TAG, "Published event to ${sent.size} connected + ${remaining.size} pending relay(s)")
}
```

### Task 5: Add follow/unfollow logic to UserProfileViewModel

**Files:**
- Modify: `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileViewModel.kt`

- [ ] **Step 1: Add dependencies to constructor**

Add to constructor:

```kotlin
private val keyManager: KeyManager,
private val signingManager: SigningManager,
private val followDao: FollowDao,
private val relayListDao: RelayListDao,
```

Add imports:

```kotlin
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.dao.RelayListDao
import com.unsilence.app.data.db.entity.FollowEntity
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
```

- [ ] **Step 2: Add isFollowing flow and follow/unfollow methods**

Add to the class body:

```kotlin
/** Whether the logged-in user follows the viewed pubkey. */
@OptIn(ExperimentalCoroutinesApi::class)
val isFollowing: Flow<Boolean> = _pubkeyHex
    .filterNotNull()
    .flatMapLatest { followDao.isFollowingFlow(it) }

val followLoading = MutableStateFlow(false)

private val myPubkey: String? = keyManager.getPublicKeyHex()

fun toggleFollow() {
    val targetPubkey = _pubkeyHex.value ?: return
    if (myPubkey == null) return
    if (followLoading.value) return
    followLoading.value = true

    viewModelScope.launch(Dispatchers.IO) {
        try {
            val currentFollows = followDao.allPubkeys()
            val nowFollowing = targetPubkey in currentFollows
            val newFollowList = if (nowFollowing) {
                currentFollows.filter { it != targetPubkey }
            } else {
                currentFollows + targetPubkey
            }

            // Build kind-3 event with all p-tags
            val nowSeconds = System.currentTimeMillis() / 1000L
            val tags = newFollowList.map { arrayOf("p", it) }.toTypedArray()
            val template = EventTemplate<Event>(
                createdAt = nowSeconds,
                kind      = 3,
                tags      = tags,
                content   = "",
            )
            val signed = signingManager.sign(template) ?: return@launch

            // Publish to write relays + indexer relays
            val writeUrls = getWriteRelayUrls(myPubkey)
            val targetUrls = (writeUrls + INDEXER_RELAY_URLS).distinct()
            relayPool.publishToRelays(toEventJson(signed), targetUrls)

            // Update local follows table
            if (nowFollowing) {
                followDao.delete(targetPubkey)
            } else {
                followDao.insert(FollowEntity(pubkey = targetPubkey, followedAt = nowSeconds))
            }
        } finally {
            followLoading.value = false
        }
    }
}

private suspend fun getWriteRelayUrls(pubkey: String): List<String> {
    val relayList = relayListDao.getByPubkey(pubkey) ?: return GLOBAL_RELAY_URLS
    return runCatching {
        Json.decodeFromString<List<String>>(relayList.writeRelays)
    }.getOrDefault(GLOBAL_RELAY_URLS)
}

private fun toEventJson(event: Event): String = buildJsonObject {
    put("id",         event.id)
    put("pubkey",     event.pubKey)
    put("created_at", event.createdAt)
    put("kind",       event.kind)
    put("tags",       buildJsonArray {
        event.tags.forEach { row ->
            add(buildJsonArray { row.forEach { cell -> add(JsonPrimitive(cell)) } })
        }
    })
    put("content",    event.content)
    put("sig",        event.sig)
}.toString()

companion object {
    private val INDEXER_RELAY_URLS = listOf(
        "wss://purplepag.es",
        "wss://user.kindpag.es",
        "wss://indexer.coracle.social",
        "wss://antiprimal.net",
    )
    private val GLOBAL_RELAY_URLS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://nostr.mom",
        "wss://relay.nostr.net",
        "wss://relay.primal.net",
    )
}
```

### Task 6: Add Follow/Unfollow button to UserProfileScreen

**Files:**
- Modify: `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileScreen.kt`

- [ ] **Step 1: Add imports and collect state**

Add imports:

```kotlin
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
```

In the composable body, after existing state collectors:

```kotlin
val isFollowing    by viewModel.isFollowing.collectAsStateWithLifecycle(initialValue = false)
val followLoading  by viewModel.followLoading.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Add Follow button after the about/bio section**

After the `about` block (after `Spacer(Modifier.height(Spacing.small))` at ~line 250), before `Spacer(Modifier.height(Spacing.large))`:

```kotlin
// Follow/Unfollow button
Row(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = Spacing.medium),
    horizontalArrangement = Arrangement.Center,
) {
    if (followLoading) {
        CircularProgressIndicator(
            color    = Cyan,
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
        )
    } else if (isFollowing) {
        OutlinedButton(
            onClick = { viewModel.toggleFollow() },
            border  = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                brush = androidx.compose.ui.graphics.SolidColor(Cyan),
            ),
            modifier = Modifier.widthIn(min = 120.dp),
        ) {
            Text("Following", color = Cyan, fontSize = 14.sp)
        }
    } else {
        Button(
            onClick  = { viewModel.toggleFollow() },
            colors   = ButtonDefaults.buttonColors(containerColor = Cyan),
            modifier = Modifier.widthIn(min = 120.dp),
        ) {
            Text("Follow", color = Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
```

- [ ] **Step 3: Build and verify**

Run: `./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk assembleDebug 2>&1 | grep -E "^e:|BUILD"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/db/dao/FollowDao.kt \
       app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt \
       app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileViewModel.kt \
       app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileScreen.kt
git commit -m "feat: follow/unfollow button with kind-3 publishing to write+indexer relays"
```

---

## Chunk 3: Feature 3 — Publish Replaceable Kinds

### Task 7: Feature 3A — Edit Profile publishes kind-0 to network

**Files:**
- Modify: `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileViewModel.kt`

The existing `saveProfile()` already builds kind-0 and calls `relayPool.publish()` which broadcasts to all connected relays. This needs to ALSO publish to indexer relays to ensure discovery.

- [ ] **Step 1: Add relayListDao dependency and targeted publishing**

Add to ProfileViewModel constructor:

```kotlin
private val relayListDao: RelayListDao,
```

Add import:

```kotlin
import com.unsilence.app.data.db.dao.RelayListDao
```

- [ ] **Step 2: Update saveProfile to publish to write + indexer relays**

In `saveProfile()`, after the existing `relayPool.publish(toEventJson(signed))` line, add targeted publishing:

```kotlin
relayPool.publish(toEventJson(signed))

// Also publish to indexer relays for discoverability
val writeUrls = pubkeyHex?.let { getWriteRelayUrls(it) }.orEmpty()
val indexerUrls = listOf(
    "wss://purplepag.es",
    "wss://user.kindpag.es",
    "wss://indexer.coracle.social",
    "wss://antiprimal.net",
)
val targetUrls = (writeUrls + indexerUrls).distinct()
relayPool.publishToRelays(toEventJson(signed), targetUrls)
```

Add the helper:

```kotlin
private suspend fun getWriteRelayUrls(pubkey: String): List<String> {
    val relayList = relayListDao.getByPubkey(pubkey) ?: return emptyList()
    return runCatching {
        kotlinx.serialization.json.Json.decodeFromString<List<String>>(relayList.writeRelays)
    }.getOrDefault(emptyList())
}
```

### Task 8: Feature 3B — Relay Management Screen

**Files:**
- Create: `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/ui/relays/RelayManagementScreen.kt`
- Create: `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/ui/relays/RelayManagementViewModel.kt`
- Modify: `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/ui/profile/SettingsScreen.kt`
- Modify: `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/data/db/entity/RelayListEntity.kt`
- Modify: `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/data/db/dao/RelayListDao.kt`

This is the largest task. The relay management screen shows the user's NIP-65 relay list (kind 10002) and allows adding/removing relays and changing read/write markers.

- [ ] **Step 1: Extend RelayListEntity to store full relay data (read+write markers)**

The current entity only stores write relays as a JSON list. For full NIP-65 management we need to store all relays with their markers. Add a new entity for the user's OWN relay config (separate from the outbox-routing entity that tracks OTHER users' write relays):

Create `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/data/db/entity/OwnRelayEntity.kt`:

```kotlin
package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single relay in the logged-in user's NIP-65 relay list (kind 10002).
 * Each relay has a URL and read/write markers.
 */
@Entity(tableName = "own_relays")
data class OwnRelayEntity(
    @PrimaryKey
    @ColumnInfo(name = "url")
    val url: String,

    @ColumnInfo(name = "read")
    val read: Boolean = true,

    @ColumnInfo(name = "write")
    val write: Boolean = true,
)
```

- [ ] **Step 2: Create OwnRelayDao**

Create `/home/aivii/projects/unsilence/app/src/main/kotlin/com/unsilence/app/data/db/dao/OwnRelayDao.kt`:

```kotlin
package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unsilence.app.data.db.entity.OwnRelayEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class OwnRelayDao {

    @Query("SELECT * FROM own_relays ORDER BY url ASC")
    abstract fun allFlow(): Flow<List<OwnRelayEntity>>

    @Query("SELECT * FROM own_relays ORDER BY url ASC")
    abstract suspend fun getAll(): List<OwnRelayEntity>

    @Query("SELECT url FROM own_relays WHERE `write` = 1")
    abstract suspend fun writeRelayUrls(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(relay: OwnRelayEntity)

    @Query("DELETE FROM own_relays WHERE url = :url")
    abstract suspend fun delete(url: String)

    @Transaction
    open suspend fun replaceAll(relays: List<OwnRelayEntity>) {
        clearAll()
        relays.forEach { upsert(it) }
    }

    @Query("DELETE FROM own_relays")
    abstract suspend fun clearAll()
}
```

- [ ] **Step 3: Add OwnRelayEntity to AppDatabase and create migration**

Modify the `@Database` annotation in AppDatabase to include `OwnRelayEntity::class` in `entities`, bump version, and add migration:

```kotlin
// In Migrations.kt, add:
val MIGRATION_N_NEXT = object : Migration(currentVersion, currentVersion + 1) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS own_relays (
                url TEXT NOT NULL PRIMARY KEY,
                `read` INTEGER NOT NULL DEFAULT 1,
                `write` INTEGER NOT NULL DEFAULT 1
            )
        """)
    }
}
```

Add `ownRelayDao()` abstract method to AppDatabase.

Provide `OwnRelayDao` in DatabaseModule:

```kotlin
@Provides
fun provideOwnRelayDao(db: AppDatabase): OwnRelayDao = db.ownRelayDao()
```

- [ ] **Step 4: Create RelayManagementViewModel**

```kotlin
package com.unsilence.app.ui.relays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.db.dao.OwnRelayDao
import com.unsilence.app.data.db.entity.OwnRelayEntity
import com.unsilence.app.data.relay.RelayPool
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

@HiltViewModel
class RelayManagementViewModel @Inject constructor(
    private val ownRelayDao: OwnRelayDao,
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
) : ViewModel() {

    val relays: Flow<List<OwnRelayEntity>> = ownRelayDao.allFlow()
    val publishing = MutableStateFlow(false)

    fun addRelay(url: String) {
        val normalizedUrl = url.trim().removeSuffix("/")
        if (normalizedUrl.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            ownRelayDao.upsert(OwnRelayEntity(url = normalizedUrl))
            publishKind10002()
        }
    }

    fun removeRelay(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ownRelayDao.delete(url)
            publishKind10002()
        }
    }

    fun toggleRead(relay: OwnRelayEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            ownRelayDao.upsert(relay.copy(read = !relay.read))
            publishKind10002()
        }
    }

    fun toggleWrite(relay: OwnRelayEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            ownRelayDao.upsert(relay.copy(write = !relay.write))
            publishKind10002()
        }
    }

    private suspend fun publishKind10002() {
        publishing.value = true
        try {
            val allRelays = ownRelayDao.getAll()
            val tags = allRelays.map { relay ->
                when {
                    relay.read && relay.write -> arrayOf("r", relay.url)
                    relay.read               -> arrayOf("r", relay.url, "read")
                    relay.write              -> arrayOf("r", relay.url, "write")
                    else                     -> return@map null
                }
            }.filterNotNull().toTypedArray()

            val template = EventTemplate<Event>(
                createdAt = System.currentTimeMillis() / 1000L,
                kind      = 10002,
                tags      = tags,
                content   = "",
            )
            val signed = signingManager.sign(template) ?: return

            val eventJson = toEventJson(signed)
            val writeUrls = allRelays.filter { it.write }.map { it.url }
            val indexerUrls = listOf(
                "wss://purplepag.es",
                "wss://user.kindpag.es",
                "wss://indexer.coracle.social",
                "wss://antiprimal.net",
            )
            relayPool.publishToRelays(eventJson, (writeUrls + indexerUrls).distinct())
        } finally {
            publishing.value = false
        }
    }

    private fun toEventJson(event: Event): String = buildJsonObject {
        put("id",         event.id)
        put("pubkey",     event.pubKey)
        put("created_at", event.createdAt)
        put("kind",       event.kind)
        put("tags",       buildJsonArray {
            event.tags.forEach { row ->
                add(buildJsonArray { row.forEach { cell -> add(JsonPrimitive(cell)) } })
            }
        })
        put("content",    event.content)
        put("sig",        event.sig)
    }.toString()
}
```

- [ ] **Step 5: Create RelayManagementScreen**

```kotlin
package com.unsilence.app.ui.relays

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.db.entity.OwnRelayEntity
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

@Composable
fun RelayManagementScreen(
    onDismiss: () -> Unit,
    viewModel: RelayManagementViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)
    val relays by viewModel.relays.collectAsStateWithLifecycle(initialValue = emptyList())
    var newRelayUrl by remember { mutableStateOf("") }

    Box(
        modifier = Modifier.fillMaxSize().background(Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Black)
                    .statusBarsPadding()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Text("Relays", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // Add relay input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value         = newRelayUrl,
                    onValueChange = { newRelayUrl = it },
                    textStyle     = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush   = SolidColor(Cyan),
                    singleLine    = true,
                    modifier      = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (newRelayUrl.isEmpty()) {
                                Text("wss://relay.example.com", color = TextSecondary, fontSize = 14.sp)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(Spacing.small))
                IconButton(
                    onClick = {
                        if (newRelayUrl.isNotBlank()) {
                            viewModel.addRelay(newRelayUrl)
                            newRelayUrl = ""
                        }
                    },
                ) {
                    Icon(Icons.Filled.Add, "Add relay", tint = Cyan)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)

            // Relay list
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(relays, key = { it.url }) { relay ->
                    RelayRow(
                        relay        = relay,
                        onToggleRead  = { viewModel.toggleRead(relay) },
                        onToggleWrite = { viewModel.toggleWrite(relay) },
                        onDelete      = { viewModel.removeRelay(relay.url) },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelayRow(
    relay: OwnRelayEntity,
    onToggleRead: () -> Unit,
    onToggleWrite: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = relay.url.removePrefix("wss://"),
                color    = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Row {
                MarkerChip(label = "Read", active = relay.read, onClick = onToggleRead)
                Spacer(Modifier.width(6.dp))
                MarkerChip(label = "Write", active = relay.write, onClick = onToggleWrite)
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Delete, "Remove relay", tint = Color(0xFFCF6679), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MarkerChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text     = label,
        color    = if (active) Cyan else TextSecondary,
        fontSize = 11.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (active) Cyan.copy(alpha = 0.1f) else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
```

- [ ] **Step 6: Wire SettingsScreen "Relays" item to RelayManagementScreen**

In `SettingsScreen.kt`, add state and navigation:

Add import:
```kotlin
import com.unsilence.app.ui.relays.RelayManagementScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Dns
```

Add state var in the composable:
```kotlin
var showRelays by remember { mutableStateOf(false) }
```

Add a "Relays" menu item (before the Wallet item):
```kotlin
SettingsItem(icon = Icons.Filled.Dns, label = "Relays", onClick = { showRelays = true })
```

At the end of the composable, add the overlay:
```kotlin
if (showRelays) {
    RelayManagementScreen(onDismiss = { showRelays = false })
}
```

- [ ] **Step 7: Seed own_relays from kind-10002 on bootstrap**

In `OutboxRouter.handleKind10002()`, when the event is for the logged-in user, also populate `own_relays`:

Add `OwnRelayDao` to OutboxRouter constructor:
```kotlin
private val ownRelayDao: OwnRelayDao,
```

In `handleKind10002()`, after the existing `relayListDao.upsert(...)`:

```kotlin
// If this is our own relay list, populate own_relays table
if (pubkey == keyManager.getPublicKeyHex()) {
    val ownRelays = tags
        .filter { tag -> tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "r" }
        .mapNotNull { tag ->
            val url = tag.jsonArray.getOrNull(1)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val marker = tag.jsonArray.getOrNull(2)?.jsonPrimitive?.content
            OwnRelayEntity(
                url   = url,
                read  = marker == null || marker.isBlank() || marker == "read",
                write = marker == null || marker.isBlank() || marker == "write",
            )
        }
    if (ownRelays.isNotEmpty()) {
        ownRelayDao.replaceAll(ownRelays)
        Log.d(TAG, "Seeded ${ownRelays.size} own relays from kind 10002")
    }
}
```

Add import:
```kotlin
import com.unsilence.app.data.db.dao.OwnRelayDao
import com.unsilence.app.data.db.entity.OwnRelayEntity
```

- [ ] **Step 8: Build and verify**

Run: `./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk assembleDebug 2>&1 | grep -E "^e:|BUILD"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: publish replaceable events (kind-0 profile, kind-3 follows, kind-10002 relays) to write+indexer relays"
```

---

## Summary of Files

| Action | File |
|--------|------|
| Modify | `app/.../ui/thread/ThreadViewModel.kt` |
| Modify | `app/.../ui/thread/ThreadScreen.kt` |
| Modify | `app/.../data/db/dao/FollowDao.kt` |
| Modify | `app/.../data/relay/RelayPool.kt` |
| Modify | `app/.../ui/profile/UserProfileViewModel.kt` |
| Modify | `app/.../ui/profile/UserProfileScreen.kt` |
| Modify | `app/.../ui/profile/ProfileViewModel.kt` |
| Modify | `app/.../ui/profile/SettingsScreen.kt` |
| Modify | `app/.../data/relay/OutboxRouter.kt` |
| Modify | `app/.../data/db/AppDatabase.kt` |
| Modify | `app/.../data/db/Migrations.kt` |
| Modify | `app/.../di/DatabaseModule.kt` |
| Create | `app/.../data/db/entity/OwnRelayEntity.kt` |
| Create | `app/.../data/db/dao/OwnRelayDao.kt` |
| Create | `app/.../ui/relays/RelayManagementViewModel.kt` |
| Create | `app/.../ui/relays/RelayManagementScreen.kt` |
