# Relay Settings Unification — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify the two disconnected relay set systems (local `RelaySetEntity` vs NIP-51 `NostrRelaySetEntity`), expose indexer/search defaults in the settings UI, replace the feed dropdown with a bottom-sheet spinner, fix URL normalization crashes, and structurally prepare for NIP-42 AUTH.

**Architecture:** The local `relay_sets` table is eliminated. Custom feed relay sets are stored exclusively as NIP-51 kind 30002 events. Hardcoded indexer relays are seeded into `relay_configs` as kind 99 (local-only). `FeedType.RelaySet` changes from UUID `id` to NIP-51 `dTag`. A shared `normalizeRelayUrl()` utility prevents crashes.

**Tech Stack:** Kotlin, Jetpack Compose, Room (SQLite), Hilt DI, Nostr protocol (NIP-51, NIP-65, NIP-42)

**Spec:** `docs/superpowers/specs/2026-03-18-relay-unification-design.md`

---

### Task 1: URL Normalization Utility

**Files:**
- Create: `app/src/main/kotlin/com/unsilence/app/data/relay/RelayUrlUtil.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/relays/RelayManagementViewModel.kt:346-358` — delegate to shared utility
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/relays/CreateRelaySetScreen.kt:155-159` — normalize on add
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt` — safety net normalization in `connect()`

- [ ] **Step 1: Create `RelayUrlUtil.kt`**

Create the shared normalization function extracted from `RelayManagementViewModel.Companion`:

```kotlin
// app/src/main/kotlin/com/unsilence/app/data/relay/RelayUrlUtil.kt
package com.unsilence.app.data.relay

/**
 * Normalize a relay URL for consistent storage and comparison.
 * Rules: trim → strip http(s):// → prepend wss:// if missing → validate domain has dot → strip trailing slash.
 * Returns null if the URL is blank or has no valid domain.
 */
fun normalizeRelayUrl(raw: String): String? {
    var url = raw.trim().removeSuffix("/")
    if (url.isBlank()) return null
    url = url.removePrefix("https://").removePrefix("http://")
    if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
        url = "wss://$url"
    }
    val host = url.removePrefix("wss://").removePrefix("ws://").split("/").firstOrNull() ?: return null
    if (!host.contains(".")) return null
    return url
}

/** Hardcoded global relay defaults — single source of truth for fallbacks. */
val GLOBAL_RELAY_URLS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://nostr.mom",
    "wss://relay.nostr.net",
    "wss://relay.ditto.pub",
    "wss://relay.primal.net",
)
```

- [ ] **Step 2: Update `RelayManagementViewModel` to use shared utility**

In `RelayManagementViewModel.kt`, replace the `companion object` block (lines 346-359) and update the import:

```kotlin
// Replace companion object at bottom of file:
companion object {
    /** @deprecated Use top-level normalizeRelayUrl() from RelayUrlUtil.kt */
    internal fun normalizeRelayUrl(raw: String): String? =
        com.unsilence.app.data.relay.normalizeRelayUrl(raw)
}
```

Add import at top:
```kotlin
import com.unsilence.app.data.relay.normalizeRelayUrl
```

Remove `Companion.` prefix from all `normalizeRelayUrl` calls in the file (lines 61, 99, 120, 139, 177, 204) — they already call it unqualified, so the top-level import takes priority over the companion.

- [ ] **Step 3: Normalize URLs in `CreateRelaySetScreen` on add**

In `CreateRelaySetScreen.kt`, update the add-relay onClick (lines 155-160):

```kotlin
// Replace lines 155-160:
IconButton(
    onClick  = {
        val normalized = com.unsilence.app.data.relay.normalizeRelayUrl(newRelayUrl)
        if (normalized != null && !relayUrls.contains(normalized)) {
            relayUrls.add(normalized)
            newRelayUrl = ""
        }
    },
    modifier = Modifier.size(36.dp),
) {
```

Add import at top:
```kotlin
import com.unsilence.app.data.relay.normalizeRelayUrl
```

- [ ] **Step 4: Add safety-net normalization to `RelayPool.connect()`**

In `RelayPool.kt`, find the `connect()` method and normalize URLs at entry:

```kotlin
// At the start of connect(), normalize incoming URLs:
fun connect(urls: List<String>, isHomeFeed: Boolean = false) {
    val normalizedUrls = urls.mapNotNull { normalizeRelayUrl(it) }
    // ... rest of method uses normalizedUrls instead of urls
```

Add import: `import com.unsilence.app.data.relay.normalizeRelayUrl` (already in same package, so just the function name works).

- [ ] **Step 5: Build to verify compilation**

Run: `cd /home/aivii/projects/unsilence && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/relay/RelayUrlUtil.kt \
       app/src/main/kotlin/com/unsilence/app/ui/relays/RelayManagementViewModel.kt \
       app/src/main/kotlin/com/unsilence/app/ui/relays/CreateRelaySetScreen.kt \
       app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt
git commit -m "feat: extract normalizeRelayUrl() + GLOBAL_RELAY_URLS to shared utility, fix crashes"
```

---

### Task 2: RelayConfigDao Kind 99 Queries

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/dao/RelayConfigDao.kt` — add kind 99 queries

- [ ] **Step 1: Add kind 99 indexer relay queries**

In `RelayConfigDao.kt`, add these queries after the kind 10012 section (after line 57):

```kotlin
    /** Kind 99 (local-only): indexer relays for metadata resolution. */
    @Query("SELECT * FROM relay_configs WHERE kind = 99 ORDER BY relay_url ASC")
    abstract fun getIndexerRelays(): Flow<List<RelayConfigEntity>>

    /** Kind 99 snapshot (non-reactive, for publishing targets). */
    @Query("SELECT relay_url FROM relay_configs WHERE kind = 99")
    abstract suspend fun getIndexerRelayUrls(): List<String>
```

- [ ] **Step 2: Build to verify**

Run: `cd /home/aivii/projects/unsilence && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/db/dao/RelayConfigDao.kt
git commit -m "feat: add kind 99 indexer relay queries to RelayConfigDao"
```

---

### Task 3: NostrRelaySetDao Owner Pubkey Scoping

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/dao/NostrRelaySetDao.kt:12` — scope `getAllSets()` by ownerPubkey

- [ ] **Step 1: Add ownerPubkey parameter to `getAllSets()`**

In `NostrRelaySetDao.kt`, replace line 12:

```kotlin
    // Old:
    @Query("SELECT * FROM nostr_relay_sets ORDER BY title ASC")
    abstract fun getAllSets(): Flow<List<NostrRelaySetEntity>>

    // New:
    @Query("SELECT * FROM nostr_relay_sets WHERE owner_pubkey = :ownerPubkey ORDER BY title ASC")
    abstract fun getAllSets(ownerPubkey: String): Flow<List<NostrRelaySetEntity>>

    /** Claim orphaned relay sets (from migration with empty owner_pubkey) for the current user. */
    @Query("UPDATE nostr_relay_sets SET owner_pubkey = :ownerPubkey WHERE owner_pubkey = ''")
    abstract suspend fun claimOrphanedSets(ownerPubkey: String)

    @Query("UPDATE nostr_relay_set_members SET owner_pubkey = :ownerPubkey WHERE owner_pubkey = ''")
    abstract suspend fun claimOrphanedMembers(ownerPubkey: String)

    @Transaction
    open suspend fun claimOrphaned(ownerPubkey: String) {
        claimOrphanedSets(ownerPubkey)
        claimOrphanedMembers(ownerPubkey)
    }
```

- [ ] **Step 2: Update callers of `getAllSets()`**

In `RelayManagementViewModel.kt`, update line 53:

```kotlin
    // Old:
    val relaySets: Flow<List<NostrRelaySetEntity>> = nostrRelaySetDao.getAllSets()

    // New:
    val relaySets: Flow<List<NostrRelaySetEntity>> =
        ownerPubkey?.let { nostrRelaySetDao.getAllSets(it) } ?: emptyFlow()
```

- [ ] **Step 3: Build to verify**

Run: `cd /home/aivii/projects/unsilence && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/db/dao/NostrRelaySetDao.kt \
       app/src/main/kotlin/com/unsilence/app/ui/relays/RelayManagementViewModel.kt
git commit -m "feat: scope NostrRelaySetDao.getAllSets() by ownerPubkey"
```

---

### Task 4: Room Migration v10→v11 (Drop relay_sets, Migrate Data)

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/Migrations.kt` — add MIGRATION_10_11
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/AppDatabase.kt` — bump version, remove RelaySetEntity, remove relaySetDao
- Modify: `app/src/main/kotlin/com/unsilence/app/di/DatabaseModule.kt` — remove RelaySetDao provider, add migration

- [ ] **Step 1: Write the migration**

In `Migrations.kt`, add at the top of the file (after imports, before MIGRATION_9_10):

```kotlin
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── Step 1: Migrate non-built-in relay sets to NIP-51 nostr_relay_sets ──
        val cursor = db.query("SELECT id, name, relay_urls FROM relay_sets WHERE is_built_in = 0")
        try {
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                val relayUrlsJson = cursor.getString(2) ?: continue

                // Derive d-tag from name
                var dTag = name.lowercase().replace(Regex("[^a-z0-9-]"), "-")

                // Handle collision: check if dTag already exists
                var suffix = 1
                while (true) {
                    val checkCursor = db.query(
                        "SELECT COUNT(*) FROM nostr_relay_sets WHERE d_tag = ?",
                        arrayOf(dTag)
                    )
                    val exists = checkCursor.use { c ->
                        c.moveToFirst() && c.getInt(0) > 0
                    }
                    if (!exists) break
                    suffix++
                    dTag = "${name.lowercase().replace(Regex("[^a-z0-9-]"), "-")}-$suffix"
                }

                val now = System.currentTimeMillis() / 1000L

                // Insert relay set header (owner_pubkey = "" — claimed by AppBootstrapper post-migration)
                db.execSQL(
                    "INSERT OR IGNORE INTO nostr_relay_sets (d_tag, owner_pubkey, title, event_created_at) VALUES (?, '', ?, ?)",
                    arrayOf(dTag, name, now)
                )

                // Parse relay URLs, normalize, and insert members
                try {
                    val urls = org.json.JSONArray(relayUrlsJson)
                    for (i in 0 until urls.length()) {
                        var url = urls.optString(i) ?: continue
                        // Inline normalization (can't call Kotlin util from migration easily)
                        url = url.trim().removeSuffix("/")
                        if (url.isBlank()) continue
                        url = url.removePrefix("https://").removePrefix("http://")
                        if (!url.startsWith("wss://") && !url.startsWith("ws://")) url = "wss://$url"
                        db.execSQL(
                            "INSERT OR IGNORE INTO nostr_relay_set_members (set_d_tag, owner_pubkey, relay_url) VALUES (?, '', ?)",
                            arrayOf(dTag, url)
                        )
                    }
                } catch (_: Exception) {
                    // Skip malformed JSON
                }
            }
        } finally {
            cursor.close()
        }

        // ── Step 2: Drop relay_sets table ──
        db.execSQL("DROP TABLE IF EXISTS relay_sets")
    }
}
```

- [ ] **Step 2: Update `AppDatabase.kt`**

Remove `RelaySetEntity` from entities list, remove `relaySetDao()`, bump version to 11:

```kotlin
// In the entities array, remove: RelaySetEntity::class,
// In the abstract class body, remove: abstract fun relaySetDao(): RelaySetDao
// Change: version = 11

// Also remove these imports:
// import com.unsilence.app.data.db.dao.RelaySetDao
// import com.unsilence.app.data.db.entity.RelaySetEntity
```

The full updated `@Database` annotation:
```kotlin
@Database(
    entities = [
        EventEntity::class,
        UserEntity::class,
        ReactionEntity::class,
        FollowEntity::class,
        RelayListEntity::class,
        OwnRelayEntity::class,
        EventStatsEntity::class,
        TagEntity::class,
        EventRelayEntity::class,
        RelayConfigEntity::class,
        NostrRelaySetEntity::class,
        NostrRelaySetMemberEntity::class,
        CoverageEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
```

- [ ] **Step 3: Update `DatabaseModule.kt`**

Add MIGRATION_10_11 to the builder and remove `provideRelaySetDao`:

```kotlin
// In addMigrations(), add MIGRATION_10_11:
.addMigrations(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
    MIGRATION_9_10, MIGRATION_10_11,
)

// Remove line 54:
// @Provides fun provideRelaySetDao(db: AppDatabase): RelaySetDao = db.relaySetDao()

// Add import:
import com.unsilence.app.data.db.MIGRATION_10_11

// Remove import:
// import com.unsilence.app.data.db.dao.RelaySetDao
```

- [ ] **Step 4: Do NOT build yet — consumers still reference deleted types. Proceed to Task 5 before building.**

Note: Tasks 4 and 5 are committed together as a single atomic change to avoid broken CI.

---

### Task 5: Delete RelaySetEntity/RelaySetDao/RelaySetRepository, Update Consumers

**Files:**
- Delete: `app/src/main/kotlin/com/unsilence/app/data/db/entity/RelaySetEntity.kt`
- Delete: `app/src/main/kotlin/com/unsilence/app/data/db/dao/RelaySetDao.kt`
- Delete: `app/src/main/kotlin/com/unsilence/app/data/repository/RelaySetRepository.kt`
- Delete: `app/src/main/kotlin/com/unsilence/app/ui/relays/CreateRelaySetViewModel.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/FeedViewModel.kt` — replace RelaySetRepository with NostrRelaySetDao + RelayConfigDao
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/thread/ThreadViewModel.kt` — replace RelaySetRepository with RelayConfigDao
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/navigation/AppNavigation.kt` — update CreateRelaySetScreen usage

- [ ] **Step 1: Delete the 4 files**

Delete:
- `app/src/main/kotlin/com/unsilence/app/data/db/entity/RelaySetEntity.kt`
- `app/src/main/kotlin/com/unsilence/app/data/db/dao/RelaySetDao.kt`
- `app/src/main/kotlin/com/unsilence/app/data/repository/RelaySetRepository.kt`
- `app/src/main/kotlin/com/unsilence/app/ui/relays/CreateRelaySetViewModel.kt`

- [ ] **Step 2: Update `FeedType` sealed class**

In `FeedViewModel.kt`, change `FeedType.RelaySet` from UUID to d-tag (line 43):

```kotlin
sealed class FeedType {
    data object Global    : FeedType()
    data object Following : FeedType()
    data class  RelaySet(val dTag: String, val name: String) : FeedType()
}
```

- [ ] **Step 3: Rewrite `FeedViewModel` constructor and init**

Replace the constructor and key parts of `FeedViewModel`:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val followDao: FollowDao,
    private val coverageRepository: CoverageRepository,
    private val cardHydrator: CardHydrator,
    private val keyManager: KeyManager,
    private val relayConfigDao: RelayConfigDao,
    private val nostrRelaySetDao: NostrRelaySetDao,
) : ViewModel() {
```

Replace `userSetsFlow` (line 72):

```kotlin
    /** All relay sets (user's own NIP-51 kind 30002) for the feed picker. */
    val userSetsFlow: StateFlow<List<NostrRelaySetEntity>> =
        keyManager.getPublicKeyHex()?.let { pk ->
            nostrRelaySetDao.getAllSets(pk)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        } ?: MutableStateFlow(emptyList())
```

Replace `feedTypeLabel` (line 136):

```kotlin
    val feedTypeLabel: String get() = when (val t = _feedType.value) {
        is FeedType.Global    -> "Global"
        is FeedType.Following -> "Following"
        is FeedType.RelaySet  -> t.name
    }
```

Replace the `init` block (lines 173-262). Key changes:
- Remove `relaySetRepository.seedDefaults()` call
- Read kind 10002 read relay URLs for Global feed (fallback to `GLOBAL_RELAY_URLS`)
- For `FeedType.RelaySet`, resolve URLs via `nostrRelaySetDao.getSetMembersSnapshot(dTag, ownerPubkey)`

```kotlin
    init {
        viewModelScope.launch {
            val hasFollows = followDao.count() > 0
            if (hasFollows) _feedType.value = FeedType.Following

            // Read kind 10002 relay URLs for Global feed, fallback to hardcoded defaults
            val readRelays = relayConfigDao.getAllReadWriteRelays()
                .filter { it.marker == null || it.marker == "read" }
                .map { it.relayUrl }
            val globalUrls = readRelays.ifEmpty { GLOBAL_RELAY_URLS }

            relayPool.connect(globalUrls)

            combine(_feedType, _filter) { type, filter -> type to filter }
                .flatMapLatest { (type, filter) ->
                    newestTimestamp     = 0L
                    hasNewTopPost       = false
                    lastOldestTimestamp = 0L
                    _displayLimit.value = 200
                    cardHydrator.clearCache()

                    val intent = CoverageIntent.HomeFeed()
                    val status = coverageRepository.ensureCoverage(intent)
                    _uiState.value = FeedUiState(loading = status != CoverageStatus.COMPLETE, coverageStatus = status)

                    viewModelScope.launch {
                        delay(10_000)
                        if (_uiState.value.coverageStatus == CoverageStatus.LOADING) {
                            coverageRepository.markFailed(
                                intent.scopeType, intent.scopeKey, intent.relaySetId
                            )
                            _uiState.update { it.copy(loading = false, coverageStatus = CoverageStatus.FAILED) }
                        }
                    }

                    when (type) {
                        is FeedType.Global    -> {
                            currentRelayUrls = globalUrls
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.feedFlow(globalUrls, filter, limit)
                            }
                        }
                        is FeedType.Following -> {
                            currentRelayUrls = emptyList()
                            outboxRouter.start()
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.followingFeedFlow(filter, limit)
                            }
                        }
                        is FeedType.RelaySet  -> {
                            val ownerPk = keyManager.getPublicKeyHex() ?: ""
                            val members = nostrRelaySetDao.getSetMembersSnapshot(type.dTag, ownerPk)
                            val setUrls = members.map { it.relayUrl }.ifEmpty { globalUrls }
                            currentRelayUrls = setUrls
                            relayPool.connect(setUrls)
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.feedFlow(setUrls, filter, limit)
                            }
                        }
                    }
                }
                .collectLatest { rows ->
                    val incomingNewest = rows.firstOrNull()?.createdAt ?: 0L
                    if (newestTimestamp > 0 && incomingNewest > newestTimestamp) {
                        hasNewTopPost = true
                    }
                    newestTimestamp = incomingNewest

                    val intent = CoverageIntent.HomeFeed()
                    val status = coverageRepository.getStatus(
                        intent.scopeType, intent.scopeKey, intent.relaySetId
                    )
                    _uiState.value = FeedUiState(
                        events = rows,
                        loading = false,
                        coverageStatus = status,
                    )
                }
        }
    }
```

Add needed imports:
```kotlin
import com.unsilence.app.data.db.dao.NostrRelaySetDao
import com.unsilence.app.data.db.dao.RelayConfigDao
import com.unsilence.app.data.db.entity.NostrRelaySetEntity
```

Remove old imports:
```kotlin
// Remove: import com.unsilence.app.data.db.entity.RelaySetEntity
// Remove: import com.unsilence.app.data.repository.RelaySetRepository
```

Add import for shared `GLOBAL_RELAY_URLS` from `RelayUrlUtil.kt`:
```kotlin
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
```

- [ ] **Step 4: Update `ThreadViewModel`**

Replace `RelaySetRepository` with `RelayConfigDao` in `ThreadViewModel.kt`:

Constructor (line 44):
```kotlin
@HiltViewModel
class ThreadViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val relayConfigDao: RelayConfigDao,
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
) : ViewModel() {
```

`loadThread()` method (lines 96-101):
```kotlin
    fun loadThread(eventId: String) {
        if (eventIdFlow.value == eventId) return
        eventIdFlow.value = eventId
        _uiState.value = ThreadUiState(loading = true)
        viewModelScope.launch {
            // Use kind 10002 read relays for thread fetch
            val readRelays = relayConfigDao.getAllReadWriteRelays()
                .filter { it.marker == null || it.marker == "read" }
                .map { it.relayUrl }
            val urls = readRelays.ifEmpty { GLOBAL_RELAY_URLS }
            relayPool.fetchThread(urls, eventId)
        }
    }
```

Replace imports:
```kotlin
// Remove: import com.unsilence.app.data.repository.RelaySetRepository
// Add:    import com.unsilence.app.data.db.dao.RelayConfigDao
// Add:    import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
```

- [ ] **Step 5: Update `CreateRelaySetScreen` to use `RelayManagementViewModel`**

In `CreateRelaySetScreen.kt`, replace the `CreateRelaySetViewModel` reference:

```kotlin
@Composable
fun CreateRelaySetScreen(
    onDismiss: () -> Unit,
    viewModel: RelayManagementViewModel = hiltViewModel(),
) {
```

Update the create button onClick (line 91):
```kotlin
    TextButton(
        onClick  = {
            if (name.isNotBlank() && relayUrls.isNotEmpty()) {
                viewModel.createRelaySet(name.trim(), relayUrls.toList())
                onDismiss()
            }
        },
        enabled  = name.isNotBlank() && relayUrls.isNotEmpty(),
    ) {
```

Replace import:
```kotlin
// Remove: import com.unsilence.app.ui.relays.CreateRelaySetViewModel (if present)
// The hiltViewModel() call already works since RelayManagementViewModel is @HiltViewModel
```

- [ ] **Step 6: Update `AppNavigation.kt` feed dropdown**

In `AppNavigation.kt`, update the feed picker dropdown to use `NostrRelaySetEntity` instead of `RelaySetEntity`:

Replace lines 118 and 249-254:
```kotlin
    // Line 118: change type
    val userSets      by feedViewModel.userSetsFlow.collectAsStateWithLifecycle()

    // Lines 249-254: update relay set items
    userSets.forEach { set ->
        val isActive = feedType is FeedType.RelaySet && (feedType as FeedType.RelaySet).dTag == set.dTag
        DropdownMenuItem(
            text    = { Text(set.title ?: set.dTag, color = if (isActive) Cyan else Color.White, fontSize = 14.sp) },
            onClick = { feedViewModel.setFeedType(FeedType.RelaySet(set.dTag, set.title ?: set.dTag)); showFeedDropdown = false },
        )
    }
```

Remove unused import:
```kotlin
// Remove: import com.unsilence.app.data.db.entity.RelaySetEntity (if present)
```

- [ ] **Step 7: Build to verify**

Run: `cd /home/aivii/projects/unsilence && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit (includes Task 4 migration + Task 5 consumer updates)**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/db/Migrations.kt \
       app/src/main/kotlin/com/unsilence/app/data/db/AppDatabase.kt \
       app/src/main/kotlin/com/unsilence/app/di/DatabaseModule.kt \
       app/src/main/kotlin/com/unsilence/app/ui/feed/FeedViewModel.kt \
       app/src/main/kotlin/com/unsilence/app/ui/thread/ThreadViewModel.kt \
       app/src/main/kotlin/com/unsilence/app/ui/relays/CreateRelaySetScreen.kt \
       app/src/main/kotlin/com/unsilence/app/ui/navigation/AppNavigation.kt
git rm app/src/main/kotlin/com/unsilence/app/data/db/entity/RelaySetEntity.kt \
      app/src/main/kotlin/com/unsilence/app/data/db/dao/RelaySetDao.kt \
      app/src/main/kotlin/com/unsilence/app/data/repository/RelaySetRepository.kt \
      app/src/main/kotlin/com/unsilence/app/ui/relays/CreateRelaySetViewModel.kt
git commit -m "feat: migrate v10→v11, eliminate RelaySetEntity/RelaySetDao/RelaySetRepository, use NIP-51 as single source of truth"
```

---

### Task 6: Indexer URL Consolidation + Seeding

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/AppBootstrapper.kt` — read kind 99, seed indexers, update teardown
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/relays/RelayManagementViewModel.kt:283-288,318-323` — read kind 99 instead of hardcoding
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileViewModel.kt:214-219` — read kind 99

- [ ] **Step 1: Update `AppBootstrapper` — add seeding + kind 99 reads + NostrRelaySetDao teardown**

Add `NostrRelaySetDao` to constructor:

```kotlin
@Singleton
class AppBootstrapper @Inject constructor(
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val eventProcessor: EventProcessor,
    private val outboxRouter: OutboxRouter,
    private val maintenanceJob: DatabaseMaintenanceJob,
    private val signingManager: SigningManager,
    private val followDao: FollowDao,
    private val relayConfigDao: RelayConfigDao,
    private val nostrRelaySetDao: NostrRelaySetDao,
    private val userDao: UserDao,
    private val nwcManager: NwcManager,
    private val sharedPlayerHolder: SharedPlayerHolder,
    private val cardHydrator: CardHydrator,
) {
```

Add import:
```kotlin
import com.unsilence.app.data.db.dao.NostrRelaySetDao
import com.unsilence.app.data.db.entity.RelayConfigEntity
```

Replace the `INDEXER_RELAY_URLS` constant and add a seeding helper at the top:

```kotlin
/** Default indexer relays — only used for first-launch seeding. */
private val DEFAULT_INDEXER_URLS = listOf(
    "wss://purplepag.es",
    "wss://user.kindpag.es",
    "wss://indexer.coracle.social",
    "wss://antiprimal.net",
)

/** Default search relays — seeded if none found after bootstrap fetch. */
private val DEFAULT_SEARCH_URLS = listOf(
    "wss://relay.nostr.band",
    "wss://search.nos.today",
)
```

At the start of `bootstrap()`, add seeding before Step 1:

```kotlin
    suspend fun bootstrap(pubkeyHex: String) {
        outboxRouter.start()

        // ── Claim orphaned relay sets from migration (owner_pubkey = "") ─
        nostrRelaySetDao.claimOrphaned(pubkeyHex)

        // ── Seed kind 99 indexer relays if none exist ───────────────────
        val existingIndexers = relayConfigDao.getIndexerRelayUrls()
        if (existingIndexers.isEmpty()) {
            relayConfigDao.insertAll(
                DEFAULT_INDEXER_URLS.map { url ->
                    RelayConfigEntity(kind = 99, relayUrl = url)
                }
            )
        }

        // ── Step 1: Connect to indexer relays (read from kind 99) ───────
        val indexerUrls = relayConfigDao.getIndexerRelayUrls()
        val ready = relayPool.connectAndAwait(indexerUrls, timeoutMs = 5_000)
        Log.d(TAG, "Step 1: $ready indexer relay(s) connected")
```

Replace `INDEXER_RELAY_URLS` with `indexerUrls` in Step 4b:
```kotlin
        relayPool.fetchRelayEcosystem(pubkeyHex, indexerUrls)
```

Replace Step 5 to use kind 10002 read relays:
```kotlin
        // ── Step 5: Connect to global relays (kind 10002 read relays) ───
        val readRelays = relayConfigDao.getAllReadWriteRelays()
            .filter { it.marker == null || it.marker == "read" }
            .map { it.relayUrl }
        val globalUrls = readRelays.ifEmpty { GLOBAL_RELAY_URLS }
        relayPool.connect(globalUrls, isHomeFeed = true)
        Log.d(TAG, "Step 5: global relays connecting (${globalUrls.size} URLs)")

        // ── Seed kind 10007 search relays if none exist after fetch ─────
        val existingSearch = relayConfigDao.searchRelayUrls()
        if (existingSearch.isEmpty()) {
            relayConfigDao.insertAll(
                DEFAULT_SEARCH_URLS.map { url ->
                    RelayConfigEntity(kind = 10007, relayUrl = url)
                }
            )
        }
```

Update `teardown()` — add NIP-51 relay set cleanup after `relayConfigDao.clearAll()`:
```kotlin
        // 3. Clear only user-specific tables
        followDao.clearAll()
        relayConfigDao.clearAll()
        nostrRelaySetDao.clearAllSets()
        nostrRelaySetDao.clearAllMembers()
```

Update the `GLOBAL_RELAY_URLS` import to use the shared constant:
```kotlin
// Remove: import com.unsilence.app.data.repository.GLOBAL_RELAY_URLS
// Add:    import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
```

- [ ] **Step 2: Update `RelayManagementViewModel` to read kind 99 indexer URLs**

Add kind 99 flow (after line 50):
```kotlin
    /** Kind 99 (local-only) indexer relays. */
    val indexerRelays: Flow<List<RelayConfigEntity>> = relayConfigDao.getIndexerRelays()
```

Add kind 99 add/remove methods (after the kind 10012 section, before kind 30002):
```kotlin
    // ── Kind 99: Indexer relays (local-only, never published) ───────────────

    fun addIndexerRelay(url: String) {
        val normalized = normalizeRelayUrl(url) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            relayConfigDao.insert(
                RelayConfigEntity(kind = 99, relayUrl = normalized)
            )
        }
    }

    fun removeIndexerRelay(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            relayConfigDao.deleteRelay(99, url)
        }
    }
```

Replace hardcoded indexer URLs in `publishChanges()` (lines 283-288):
```kotlin
            val indexerUrls = relayConfigDao.getIndexerRelayUrls()
```

Replace hardcoded indexer URLs in `publishRelaySet()` (lines 318-323):
```kotlin
            val indexerUrls = relayConfigDao.getIndexerRelayUrls()
```

- [ ] **Step 3: Update `UserProfileViewModel` to read kind 99**

Add `RelayConfigDao` to constructor. Replace `INDEXER_RELAY_URLS` companion constant usage at line 177:

In the constructor, add `private val relayConfigDao: RelayConfigDao`.

In `toggleFollow()`, replace the line that uses `INDEXER_RELAY_URLS`:
```kotlin
                val indexerUrls = relayConfigDao.getIndexerRelayUrls()
                val targetUrls = (writeUrls + indexerUrls).distinct()
```

In `getWriteRelayUrls()`, replace the fallback to use the shared constant:
```kotlin
    private suspend fun getWriteRelayUrls(pubkey: String): List<String> {
        val relayList = relayListDao.getByPubkey(pubkey) ?: return GLOBAL_RELAY_URLS
        return runCatching {
            Json.decodeFromString<List<String>>(relayList.writeRelays)
        }.getOrDefault(GLOBAL_RELAY_URLS)
    }
```

Remove both `INDEXER_RELAY_URLS` and `GLOBAL_RELAY_URLS` from the companion object. Add imports:
```kotlin
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
```

- [ ] **Step 4: Build to verify**

Run: `cd /home/aivii/projects/unsilence && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/AppBootstrapper.kt \
       app/src/main/kotlin/com/unsilence/app/ui/relays/RelayManagementViewModel.kt \
       app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileViewModel.kt
git commit -m "feat: consolidate indexer URLs to kind 99, add seeding, fix teardown"
```

---

### Task 7: RelayPool SigningManager Dependency + AUTH Stub

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt:55-60,308-363` — add SigningManager, intercept AUTH

- [ ] **Step 1: Add `SigningManager` to `RelayPool` constructor**

In `RelayPool.kt`, add to constructor (line 55):

```kotlin
@Singleton
class RelayPool @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val processor: EventProcessor,
    private val relayConfigDao: dagger.Lazy<com.unsilence.app.data.db.dao.RelayConfigDao>,
    private val subscriptionRegistry: dagger.Lazy<SubscriptionRegistry>,
    private val coverageRepository: dagger.Lazy<com.unsilence.app.data.repository.CoverageRepository>,
    private val signingManager: com.unsilence.app.data.auth.SigningManager,
) {
```

- [ ] **Step 2: Add AUTH intercept in `listenForEvents()`**

In `listenForEvents()`, add an AUTH handler after the NOTICE handler (after line 332):

```kotlin
                // NIP-42 AUTH challenge — structural preparation (stub: log and ignore)
                if (raw.startsWith("[\"AUTH\"")) {
                    val challenge = raw.substringAfter("[\"AUTH\",\"", "").substringBefore("\"")
                    Log.d(TAG, "AUTH challenge from ${conn.url}: ${challenge.take(20)}… (not yet implemented)")
                    return@consumeEach
                }
```

- [ ] **Step 3: Build to verify**

Run: `cd /home/aivii/projects/unsilence && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt
git commit -m "feat: add SigningManager to RelayPool, stub NIP-42 AUTH intercept"
```

---

### Task 8: Relay Management Screen Redesign (Tab Layout)

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/relays/RelayManagementScreen.kt` — complete rewrite with tabs + collapsible sections

- [ ] **Step 1: Read the current `RelayManagementScreen.kt` fully**

Read the full file to understand current structure before rewriting.

- [ ] **Step 2: Rewrite `RelayManagementScreen.kt` with tab layout**

The new layout has:
- Top: Three horizontal tabs (Inbox/Outbox, Index, Search) as a segmented control
- Below tabs: Three collapsible sections (Relay Sets, Favorites, Blocked)
- Each tab shows a compact relay list with inline add + delete
- Relay URLs displayed without `wss://` prefix

```kotlin
package com.unsilence.app.ui.relays

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.db.entity.NostrRelaySetEntity
import com.unsilence.app.data.db.entity.NostrRelaySetMemberEntity
import com.unsilence.app.data.db.entity.RelayConfigEntity
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

private val TabNames = listOf("Inbox/Outbox", "Index", "Search")

@Composable
fun RelayManagementScreen(
    onDismiss: () -> Unit,
    viewModel: RelayManagementViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)
    var selectedTab by remember { mutableIntStateOf(0) }

    val readWriteRelays by viewModel.readWriteRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val indexerRelays   by viewModel.indexerRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val searchRelays    by viewModel.searchRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val blockedRelays   by viewModel.blockedRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteRelays  by viewModel.favoriteRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val relaySets       by viewModel.relaySets.collectAsStateWithLifecycle(initialValue = emptyList())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top bar ─────────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Sizing.topBarHeight)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint               = Color.White,
                        )
                    }
                    Text(
                        text       = "Relay Settings",
                        color      = Color.White,
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // ── Tab row ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TabNames.forEachIndexed { index, name ->
                    val isSelected = index == selectedTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Cyan.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { selectedTab = index }
                            .padding(vertical = Spacing.small),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text       = name,
                            color      = if (isSelected) Cyan else TextSecondary,
                            fontSize   = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF222222))

            // ── Scrollable content ──────────────────────────────────────────
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // ── Tab content ─────────────────────────────────────────────
                when (selectedTab) {
                    0 -> {
                        // Inbox/Outbox (kind 10002)
                        item {
                            AddRelayInput(placeholder = "wss://relay.example.com") { url ->
                                viewModel.addReadWriteRelay(url)
                            }
                        }
                        items(readWriteRelays, key = { it.id }) { relay ->
                            ReadWriteRelayRow(
                                relay         = relay,
                                onToggleMarker = { viewModel.toggleMarker(relay) },
                                onRemove       = { viewModel.removeReadWriteRelay(relay.relayUrl) },
                            )
                        }
                    }
                    1 -> {
                        // Index (kind 99, local-only)
                        item {
                            AddRelayInput(placeholder = "wss://indexer.example.com") { url ->
                                viewModel.addIndexerRelay(url)
                            }
                        }
                        if (indexerRelays.isEmpty()) {
                            item {
                                Text(
                                    text     = "No indexer relays configured. Profile and follow list resolution will not work.",
                                    color    = Color(0xFFFF6B6B),
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(Spacing.medium),
                                )
                            }
                        }
                        items(indexerRelays, key = { it.id }) { relay ->
                            SimpleRelayRow(
                                url      = relay.relayUrl,
                                onRemove = { viewModel.removeIndexerRelay(relay.relayUrl) },
                            )
                        }
                    }
                    2 -> {
                        // Search (kind 10007)
                        item {
                            AddRelayInput(placeholder = "wss://search.example.com") { url ->
                                viewModel.addSearchRelay(url)
                            }
                        }
                        items(searchRelays, key = { it.id }) { relay ->
                            SimpleRelayRow(
                                url      = relay.relayUrl,
                                onRemove = { viewModel.removeSearchRelay(relay.relayUrl) },
                            )
                        }
                    }
                }

                // ── Divider between tabs and sections ───────────────────────
                item { Spacer(Modifier.height(Spacing.medium)) }
                item { HorizontalDivider(color = Color(0xFF222222)) }

                // ── Collapsible: Relay Sets (kind 30002) ────────────────────
                item {
                    CollapsibleSection(
                        title = "Relay Sets",
                        count = relaySets.size,
                    ) {
                        relaySets.forEach { set ->
                            RelaySetRow(
                                set       = set,
                                viewModel = viewModel,
                                onDelete  = { viewModel.deleteRelaySet(set.dTag) },
                            )
                        }
                    }
                }

                // ── Collapsible: Favorites (kind 10012) ─────────────────────
                item {
                    CollapsibleSection(
                        title = "Favorites",
                        count = favoriteRelays.size,
                    ) {
                        AddRelayInput(placeholder = "wss://favorite.example.com") { url ->
                            viewModel.addFavoriteRelay(url)
                        }
                        favoriteRelays.filter { it.setRef == null }.forEach { relay ->
                            SimpleRelayRow(
                                url      = relay.relayUrl,
                                onRemove = { viewModel.removeFavoriteRelay(relay.relayUrl) },
                            )
                        }
                    }
                }

                // ── Collapsible: Blocked (kind 10006) ───────────────────────
                item {
                    CollapsibleSection(
                        title = "Blocked",
                        count = blockedRelays.size,
                    ) {
                        AddRelayInput(placeholder = "wss://blocked.example.com") { url ->
                            viewModel.addBlockedRelay(url)
                        }
                        blockedRelays.forEach { relay ->
                            SimpleRelayRow(
                                url      = relay.relayUrl,
                                onRemove = { viewModel.removeBlockedRelay(relay.relayUrl) },
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(Spacing.xl)) }
            }
        }
    }
}

// ── Sub-composables ─────────────────────────────────────────────────────────

@Composable
private fun AddRelayInput(placeholder: String, onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value         = input,
            onValueChange = { input = it },
            textStyle     = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush   = SolidColor(Cyan),
            singleLine    = true,
            decorationBox = { inner ->
                Box(modifier = Modifier.weight(1f)) {
                    if (input.isEmpty()) {
                        Text(placeholder, color = TextSecondary, fontSize = 14.sp)
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = {
                if (input.isNotBlank()) {
                    onAdd(input.trim())
                    input = ""
                }
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add", tint = Cyan)
        }
    }
}

/** Display URL without wss:// prefix for compactness. */
private fun displayUrl(url: String): String =
    url.removePrefix("wss://").removePrefix("ws://")

@Composable
private fun SimpleRelayRow(url: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = displayUrl(url),
            color    = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ReadWriteRelayRow(
    relay: RelayConfigEntity,
    onToggleMarker: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = displayUrl(relay.relayUrl),
            color    = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // R/W marker chip
        val markerLabel = when (relay.marker) {
            "read"  -> "R"
            "write" -> "W"
            else    -> "R/W"
        }
        Text(
            text     = markerLabel,
            color    = Cyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Cyan.copy(alpha = 0.15f))
                .clickable { onToggleMarker() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    count: Int,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text       = title,
                color      = Color.White,
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.weight(1f),
            )
            Text(
                text     = "$count",
                color    = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column { content() }
        }
    }
}

@Composable
private fun RelaySetRow(
    set: NostrRelaySetEntity,
    viewModel: RelayManagementViewModel,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val members by viewModel.getSetMembers(set.dTag).collectAsStateWithLifecycle(initialValue = emptyList())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = Spacing.medium, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text     = set.title ?: set.dTag,
                color    = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text  = "${members.size} relays",
                color = TextSecondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete set", tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = Spacing.medium)) {
                members.forEach { member ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(displayUrl(member.relayUrl), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { viewModel.removeRelayFromSet(set.dTag, member.relayUrl) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                AddRelayInput(placeholder = "Add relay to set") { url ->
                    viewModel.addRelayToSet(set.dTag, url)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Build to verify**

Run: `cd /home/aivii/projects/unsilence && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/relays/RelayManagementScreen.kt
git commit -m "feat: redesign RelayManagementScreen with tab layout (Inbox/Outbox, Index, Search)"
```

---

### Task 9: Feed Picker Bottom Sheet Spinner

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/navigation/AppNavigation.kt:226-261` — replace dropdown with bottom sheet spinner

- [ ] **Step 1: Replace the feed dropdown with a bottom sheet trigger + dialog**

In `AppNavigation.kt`, replace the dropdown (lines 226-261) with a bottom sheet:

Replace the `Box` containing the dropdown with just a clickable text:
```kotlin
                        Text(
                            text     = "${feedViewModel.feedTypeLabel} ▾",
                            color    = Cyan,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 120.dp)
                                .clickable { showFeedDropdown = true },
                        )
```

Add the bottom sheet composable at the bottom of the file (before `PlaceholderScreen`). The imports needed:

```kotlin
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
```

Replace the `showFeedDropdown` dialog in the overlay section. Remove the old `DropdownMenu` block and instead show a fullscreen dialog:

```kotlin
            // ── Feed picker bottom sheet ────────────────────────────────────
            if (showFeedDropdown) {
                val relayManagementVm: RelayManagementViewModel = hiltViewModel()
                FeedPickerSheet(
                    feedType       = feedType,
                    userSets       = userSets,
                    onSelect       = { type ->
                        feedViewModel.setFeedType(type)
                        showFeedDropdown = false
                    },
                    onNewRelaySet  = { showFeedDropdown = false; showCreateRelaySet = true },
                    onRelaySettings = { showFeedDropdown = false; /* TODO: navigate to relay settings */ },
                    onDeleteSet    = { dTag ->
                        relayManagementVm.deleteRelaySet(dTag)
                        // If we just deleted the active feed, switch to Global
                        if (feedType is FeedType.RelaySet && (feedType as FeedType.RelaySet).dTag == dTag) {
                            feedViewModel.setFeedType(FeedType.Global)
                        }
                    },
                    onDismiss      = { showFeedDropdown = false },
                )
            }
```

Add the `FeedPickerSheet` composable:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedPickerSheet(
    feedType: FeedType,
    userSets: List<NostrRelaySetEntity>,
    onSelect: (FeedType) -> Unit,
    onNewRelaySet: () -> Unit,
    onRelaySettings: () -> Unit,
    onDeleteSet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDeleteDTag by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0A))
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { /* consume */ },
            ) {
                // Build feed list: Global, Following, divider marker, then user sets
                data class FeedItem(val type: FeedType?, val label: String, val isDivider: Boolean = false, val dTag: String? = null)

                val items = buildList {
                    add(FeedItem(FeedType.Global, "Global"))
                    add(FeedItem(FeedType.Following, "Following"))
                    if (userSets.isNotEmpty()) {
                        add(FeedItem(null, "", isDivider = true))
                        userSets.forEach { set ->
                            add(FeedItem(FeedType.RelaySet(set.dTag, set.title ?: set.dTag), set.title ?: set.dTag, dTag = set.dTag))
                        }
                    }
                }

                // Find current selection index
                val currentIndex = items.indexOfFirst { item ->
                    item.type != null && when {
                        item.type is FeedType.Global && feedType is FeedType.Global -> true
                        item.type is FeedType.Following && feedType is FeedType.Following -> true
                        item.type is FeedType.RelaySet && feedType is FeedType.RelaySet ->
                            (item.type as FeedType.RelaySet).dTag == (feedType as FeedType.RelaySet).dTag
                        else -> false
                    }
                }.coerceAtLeast(0)

                val spinnerState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
                val flingBehavior = rememberSnapFlingBehavior(lazyListState = spinnerState)

                // Spinner window: 3 items visible (156dp)
                val itemHeight = 52.dp
                val spinnerHeight = itemHeight * 3

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(spinnerHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    LazyColumn(
                        state = spinnerState,
                        flingBehavior = flingBehavior,
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Padding items for snap alignment
                        item { Spacer(Modifier.height(itemHeight)) }

                        itemsIndexed(items) { index, item ->
                            if (item.isDivider) {
                                HorizontalDivider(
                                    color = Color(0xFF333333),
                                    modifier = Modifier
                                        .height(itemHeight)
                                        .padding(vertical = 20.dp)
                                        .fillMaxWidth(0.5f),
                                )
                            } else {
                                val centerIndex by remember {
                                    derivedStateOf {
                                        val layoutInfo = spinnerState.layoutInfo
                                        val center = layoutInfo.viewportStartOffset + layoutInfo.viewportSize.height / 2
                                        layoutInfo.visibleItemsInfo.minByOrNull {
                                            kotlin.math.abs((it.offset + it.size / 2) - center)
                                        }?.index?.minus(1) ?: 0  // -1 for padding item
                                    }
                                }
                                val isCenter = index == centerIndex
                                Box(
                                    modifier = Modifier
                                        .height(itemHeight)
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { item.type?.let { onSelect(it) } },
                                            onLongClick = {
                                                // Long-press on custom sets shows delete confirmation
                                                if (item.dTag != null) confirmDeleteDTag = item.dTag
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text       = item.label,
                                        color      = if (isCenter) Cyan else TextSecondary,
                                        fontSize   = if (isCenter) 18.sp else 15.sp,
                                        fontWeight = if (isCenter) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier   = Modifier.padding(horizontal = Spacing.medium),
                                    )
                                }
                            }
                        }

                        // Padding items for snap alignment
                        item { Spacer(Modifier.height(itemHeight)) }
                    }
                }

                Spacer(Modifier.height(Spacing.large))

                // Action buttons below spinner
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    Text(
                        text     = "+ New Relay Set",
                        color    = Cyan,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { onNewRelaySet() },
                    )
                    Text(
                        text     = "⚙ Relay Settings",
                        color    = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { onRelaySettings() },
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    confirmDeleteDTag?.let { dTag ->
        val setName = userSets.firstOrNull { it.dTag == dTag }?.title ?: dTag
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteDTag = null },
            title = { Text("Delete Relay Set", color = Color.White) },
            text = { Text("Delete \"$setName\"? This cannot be undone.", color = TextSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onDeleteSet(dTag)
                    confirmDeleteDTag = null
                }) { Text("Delete", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDeleteDTag = null }) {
                    Text("Cancel", color = Cyan)
                }
            },
            containerColor = Color(0xFF1A1A1A),
        )
    }
}
```

Add needed imports:
```kotlin
import com.unsilence.app.data.db.entity.NostrRelaySetEntity
import com.unsilence.app.ui.relays.RelayManagementViewModel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
```

Remove old dropdown imports if unused:
```kotlin
// Remove: import androidx.compose.material3.DropdownMenu
// Remove: import androidx.compose.material3.DropdownMenuItem
```

- [ ] **Step 2: Build to verify**

Run: `cd /home/aivii/projects/unsilence && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/navigation/AppNavigation.kt
git commit -m "feat: replace feed dropdown with bottom sheet spinner (snap-to-center)"
```

---

### Task 10: d-tag Collision Handling in createRelaySet

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/relays/RelayManagementViewModel.kt:173-186` — add collision suffix

- [ ] **Step 1: Update `createRelaySet()` with collision check**

In `RelayManagementViewModel.kt`, replace the `createRelaySet()` method:

```kotlin
    fun createRelaySet(name: String, relays: List<String>) {
        val baseDTag = name.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // Handle d-tag collision: append numeric suffix if needed
            var dTag = baseDTag
            var suffix = 1
            while (nostrRelaySetDao.maxCreatedAt(dTag, pk) != null) {
                suffix++
                dTag = "$baseDTag-$suffix"
            }

            val members = relays.mapNotNull { normalizeRelayUrl(it) }
                .map { NostrRelaySetMemberEntity(setDTag = dTag, ownerPubkey = pk, relayUrl = it) }
            nostrRelaySetDao.replaceSet(
                NostrRelaySetEntity(dTag = dTag, ownerPubkey = pk, title = name),
                members,
                nowSeconds(),
            )
            publishRelaySet(dTag)
        }
    }
```

- [ ] **Step 2: Build to verify**

Run: `cd /home/aivii/projects/unsilence && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/relays/RelayManagementViewModel.kt
git commit -m "feat: handle d-tag collisions in createRelaySet with numeric suffix"
```

---

### Task 11: Final Integration Verification

- [ ] **Step 1: Full build**

Run: `cd /home/aivii/projects/unsilence && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify no remaining references to deleted files**

Search for any lingering `RelaySetRepository`, `RelaySetDao`, `RelaySetEntity`, `CreateRelaySetViewModel` references:
```bash
cd /home/aivii/projects/unsilence && grep -r "RelaySetRepository\|RelaySetDao\|RelaySetEntity\|CreateRelaySetViewModel" --include="*.kt" app/src/main/kotlin/ | grep -v "\.bak" | grep -v "Migrations.kt"
```
Expected: no output (Migrations.kt may reference old table name in SQL, that's fine)

- [ ] **Step 3: Verify no hardcoded indexer URLs remain outside seeding**

```bash
cd /home/aivii/projects/unsilence && grep -rn "purplepag.es\|user.kindpag.es\|indexer.coracle.social\|antiprimal.net" --include="*.kt" app/src/main/kotlin/
```
Expected: only in `AppBootstrapper.kt` (the seeding defaults)

- [ ] **Step 4: Commit any fixes from verification**

If any issues found, fix and commit.
