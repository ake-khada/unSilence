# Sprint 23: Profile & Zaps Polish — Design Spec

> **For Claude Code:** Read this spec and CLAUDE.md before implementing. Check actual source files before making changes.

**Goal:** Six changes that transform the app from "work in progress" to "looks finished": zap total sats display, profile tabs, following/followers count, own-profile repost names fix, and startup frame skip investigation.

**Architecture:** All changes follow the existing Relay → EventProcessor → Room → Flow/StateFlow → Compose UI pipeline. No new architectural patterns introduced.

---

## Task 6: Startup Frame Skip Investigation

**Priority:** First — affects every launch.

**Problem:** `Choreographer: Skipped 81 frames!` seen in logcat on cold launch with Amber login. Sprint context suggests relay connections blocking main thread, but `AppBootstrapper.bootstrap()` already runs on `Dispatchers.IO`.

**Investigation targets:**
1. `RootViewModel` init block — any `viewModelScope.launch {}` without explicit dispatcher defaults to `Dispatchers.Main`
2. `FeedViewModel.init` — the combine/flatMapLatest chain collects StateFlows on Main
3. Any `LaunchedEffect` in root composables doing heavy work
4. Compose initial composition overhead (not fixable)

**Fix pattern:** If `viewModelScope.launch` calls are found doing heavy work on Main, wrap in `withContext(Dispatchers.IO)` or use `viewModelScope.launch(Dispatchers.IO)`.

**Bail-out:** If the skipped frames come from Compose initial composition/layout, that's framework overhead — skip this task. Document findings.

---

## Task 5: Own-Profile Repost Names Fix

**Priority:** Second — one init block addition.

**Problem:** `ProfileViewModel` doesn't call `fetchMissingProfiles` for repost original authors. Reposted notes on own profile show truncated hex pubkeys instead of display names. `UserProfileViewModel` already has this fix (lines 81-95).

**Fix:**
Add to `ProfileViewModel`:
```kotlin
private val fetchedProfilePubkeys = mutableSetOf<String>()

init {
    viewModelScope.launch {
        postsFlow.collectLatest { rows ->
            val pubkeys = rows.flatMap { row ->
                val embedded = if (row.kind == 6) {
                    extractRepostAuthorPubkey(row.content, row.tags)
                } else null
                listOfNotNull(row.pubkey, embedded)
            }.distinct()
            val newPubkeys = pubkeys.filter { it !in fetchedProfilePubkeys }
            if (newPubkeys.isNotEmpty()) {
                fetchedProfilePubkeys.addAll(newPubkeys)
                userRepository.fetchMissingProfiles(newPubkeys)
            }
        }
    }
}
```

**Files modified:** `ProfileViewModel.kt` only.

**Import needed:** `extractRepostAuthorPubkey` — verify import path from `UserProfileViewModel`.

---

## Task 1: Zap Total Sats Display

**Priority:** Third — Room migration + pipeline + UI.

### 1a. Room Migration (v4 → v5)

**EventEntity** — add column:
```kotlin
@ColumnInfo(name = "zap_total_sats") val zapTotalSats: Long = 0,
```

**Migration:**
```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN zap_total_sats INTEGER NOT NULL DEFAULT 0")
    }
}
```

**AppDatabase:** Bump version to 5, add `MIGRATION_4_5` to migrations list.

No new indexes needed — `zap_total_sats` is never used in WHERE/ORDER clauses.

### 1b. extractZapSats() Utility

Needs a private `tagValue()` helper since `EventEntity.tags` is a JSON string (not parsed). The existing `articleTagValue()` in `ArticleReaderScreen.kt` is private and inaccessible.

```kotlin
/** Extract a tag value from a JSON-serialized tags array: [["key","value"],...] */
private fun tagValue(tagsJson: String, key: String): String? = runCatching {
    Json.parseToJsonElement(tagsJson).jsonArray
        .firstOrNull { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == key }
        ?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content
}.getOrNull()

/**
 * Extract sats from a kind-9735 zap receipt.
 * Primary: parse bolt11 invoice via Quartz's LnInvoiceUtil.
 * Fallback: read "amount" tag from embedded zap request (millisats).
 */
private fun extractZapSats(tagsJson: String): Long {
    // Primary: parse bolt11 tag via Quartz's LnInvoiceUtil
    val bolt11 = tagValue(tagsJson, "bolt11")
    if (bolt11 != null) {
        try {
            val sats = LnInvoiceUtil.getAmountInSats(bolt11)
            if (sats > BigDecimal.ZERO) return sats.toLong()
        } catch (_: Exception) {}
    }

    // Fallback: read "amount" tag from embedded zap request (millisats)
    val descriptionJson = tagValue(tagsJson, "description") ?: return 0L
    return try {
        val zapRequest = Json.decodeFromString<JsonObject>(descriptionJson)
        val tags = zapRequest["tags"]?.jsonArray
        val amountTag = tags?.firstOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "amount"
        }
        val msats = amountTag?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content?.toLongOrNull()
        (msats ?: 0L) / 1_000L
    } catch (_: Exception) { 0L }
}
```

**Location:** Private functions in `EventProcessor.kt` — the only consumer.

**Dependency:** `com.vitorpamplona.quartz.lightning.LnInvoiceUtil` — available via `quartz-android` dependency. Verify import path exists in Quartz 1.05.1 during implementation; if not, skip bolt11 parsing and use amount-tag-only path.

### 1c. toCompactSats() Formatter

```kotlin
fun Long.toCompactSats(): String = when {
    this >= 1_000_000 -> {
        val s = String.format("%.1fM", this / 1_000_000.0)
        if (s.endsWith(".0M")) s.dropLast(3) + "M" else s
    }
    this >= 1_000 -> {
        val s = String.format("%.1fk", this / 1_000.0)
        if (s.endsWith(".0k")) s.dropLast(3) + "k" else s
    }
    else -> this.toString()
}
```

**Location:** In `NoteCard.kt` alongside the existing `formatCount()` function.

### 1d. EventProcessor — Aggregate Zap Sats

Kind-9735 zap receipts flow through the COLD lane: `process()` → `buildContentEvent()` → `coldChannel` → `drainCold()` → `flushBatch()`.

**Where to add sats aggregation:** Inside `flushBatch()`, after the batch `insertOrIgnoreBatch()` call. Iterate over the batch, filter kind-9735 events, extract sats, and call `addZapSats()`:

```kotlin
// Inside flushBatch(), after insertOrIgnoreBatch(batch):
batch.filter { it.kind == 9735 && it.rootId != null }.forEach { receipt ->
    val sats = extractZapSats(receipt.tags)
    if (sats > 0) {
        eventDao.addZapSats(receipt.rootId!!, sats)
    }
}
```

The `rootId` on `EventEntity` is already parsed from NIP-10 `e` tags by `parseNip10Threading()` — it contains the zapped event's ID.

**Dedup note:** `seenIds` already deduplicates by event ID before processing. Kind-9735 receipts with the same ID won't be processed twice. No additional dedup needed.

**New DAO method:**
```kotlin
@Query("UPDATE events SET zap_total_sats = zap_total_sats + :sats WHERE id = :eventId")
suspend fun addZapSats(eventId: String, sats: Long)
```

### 1e. FeedRow — Add zapTotalSats

**FeedRow data class** — add field:
```kotlin
@ColumnInfo(name = "zap_total_sats") val zapTotalSats: Long,
```

**All existing FeedRow SQL queries must add `e.zap_total_sats` to their SELECT.** Room requires every non-default `@ColumnInfo` field to be present in every query returning `FeedRow`. The complete list of queries to update:

1. `feedFlow()` — global feed query
2. `followingFeedFlow()` — following feed query
3. `userPostsFlow()` — existing user posts (kept for backward compat)
4. `threadFlow()` — thread detail query
5. `searchNotesFlow()` — NIP-50 search results (if it returns `FeedRow`)

Add to each SELECT clause:
```sql
e.zap_total_sats,
```

Keep `zap_count` in FeedRow — removing it requires touching every query and the zap count JOIN. The UI will use `zapTotalSats` instead of `zapCount` for display.

### 1f. UI — Display Sats Instead of Count

**ZapButton changes:**
- Change parameter from `count: Int` to `sats: Long`
- Display `sats.toCompactSats()` instead of `formatCount(count)`
- Amber color `#FFAB00` (`ZapAmber`) for both icon and amount — already implemented for zapped state, extend to always-amber for the zap display

**NoteCard usage:**
```kotlin
ZapButton(
    sats = row.zapTotalSats,
    hasZapped = hasZapped,
    onTap = { ... },
    onLongPress = { ... },
)
```

---

## Task 3: Following Count

**Priority:** Fourth — trivial, bundle with profile UI work.

**Own profile only.** Other users' following count deferred to a future sprint.

**Changes:**

**ProfileViewModel:**
```kotlin
@Inject constructor(
    ...
    private val followDao: FollowDao,  // add injection
) {
    val followingCount: StateFlow<Int> = followDao.countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
```

**Note:** `FollowDao.count()` is a suspend fun. Need to check if there's a `countFlow()` or add one:
```kotlin
@Query("SELECT COUNT(*) FROM follows")
fun countFlow(): Flow<Int>
```

**ProfileScreen:**
Change `StatLabel(label = "Following", value = viewModel.following)` to use `viewModel.followingCount.collectAsState()`.

**Remove:** The hardcoded `val following: Int? = null` from `ProfileViewModel`.

---

## Task 2: Profile Tabs (Notes / Replies / Longform)

**Priority:** Fifth — UI work.

### Tab Enum

```kotlin
enum class ProfileTab { NOTES, REPLIES, LONGFORM }
```

### New DAO Queries (additive — do NOT modify existing userPostsFlow)

Three new methods on `EventDao`:

**Important:** Use explicit column enumeration matching the existing `feedFlow`/`userPostsFlow` pattern. Do NOT use `SELECT e.*` — Room will fail because `e.*` includes columns (e.g., `sig`) that `FeedRow` does not map.

```kotlin
@Query("""
    SELECT
        e.id, e.pubkey, e.kind, e.content, e.created_at, e.tags,
        e.relay_url, e.reply_to_id, e.root_id,
        e.has_content_warning, e.content_warning_reason, e.cached_at,
        e.zap_total_sats,
        u.name            AS author_name,
        u.display_name    AS author_display_name,
        u.picture         AS author_picture,
        u.nip05           AS author_nip05,
        COUNT(DISTINCT r.event_id)  AS reaction_count,
        COUNT(DISTINCT rep.id)      AS reply_count,
        COUNT(DISTINCT rp.id)       AS repost_count,
        COUNT(DISTINCT z.id)        AS zap_count
    FROM events e
    LEFT JOIN users     u   ON u.pubkey          = e.pubkey
    LEFT JOIN reactions r   ON r.target_event_id = e.id
    LEFT JOIN events    rep ON rep.reply_to_id   = e.id AND rep.kind = 1
    LEFT JOIN events    rp  ON rp.root_id        = e.id AND rp.kind  = 6
    LEFT JOIN events    z   ON z.root_id         = e.id AND z.kind  = 9735
    WHERE e.pubkey = :pubkey AND e.kind = 1
      AND e.reply_to_id IS NULL AND e.root_id IS NULL
    GROUP BY e.id
    ORDER BY e.created_at DESC
    LIMIT :limit
""")
fun userNotesFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>>

@Query("""
    SELECT
        e.id, e.pubkey, e.kind, e.content, e.created_at, e.tags,
        e.relay_url, e.reply_to_id, e.root_id,
        e.has_content_warning, e.content_warning_reason, e.cached_at,
        e.zap_total_sats,
        u.name            AS author_name,
        u.display_name    AS author_display_name,
        u.picture         AS author_picture,
        u.nip05           AS author_nip05,
        COUNT(DISTINCT r.event_id)  AS reaction_count,
        COUNT(DISTINCT rep.id)      AS reply_count,
        COUNT(DISTINCT rp.id)       AS repost_count,
        COUNT(DISTINCT z.id)        AS zap_count
    FROM events e
    LEFT JOIN users     u   ON u.pubkey          = e.pubkey
    LEFT JOIN reactions r   ON r.target_event_id = e.id
    LEFT JOIN events    rep ON rep.reply_to_id   = e.id AND rep.kind = 1
    LEFT JOIN events    rp  ON rp.root_id        = e.id AND rp.kind  = 6
    LEFT JOIN events    z   ON z.root_id         = e.id AND z.kind  = 9735
    WHERE e.pubkey = :pubkey AND e.kind = 1
      AND (e.reply_to_id IS NOT NULL OR e.root_id IS NOT NULL)
    GROUP BY e.id
    ORDER BY e.created_at DESC
    LIMIT :limit
""")
fun userRepliesFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>>

@Query("""
    SELECT
        e.id, e.pubkey, e.kind, e.content, e.created_at, e.tags,
        e.relay_url, e.reply_to_id, e.root_id,
        e.has_content_warning, e.content_warning_reason, e.cached_at,
        e.zap_total_sats,
        u.name            AS author_name,
        u.display_name    AS author_display_name,
        u.picture         AS author_picture,
        u.nip05           AS author_nip05,
        0 AS reaction_count, 0 AS reply_count, 0 AS repost_count, 0 AS zap_count
    FROM events e
    LEFT JOIN users u ON u.pubkey = e.pubkey
    WHERE e.pubkey = :pubkey AND e.kind = 30023
    GROUP BY e.id
    ORDER BY e.created_at DESC
    LIMIT :limit
""")
fun userLongformFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>>
```

**Note:** Longform query skips engagement JOINs (articles don't typically have reactions/replies in this app). If needed later, add them.

**Note:** Notes query removes the `OR e.kind = 6` (reposts) from the original `userPostsFlow`. The Notes tab shows only original kind-1 posts. Reposts could be added as a 4th tab later.

### ViewModel Changes

**Both ProfileViewModel and UserProfileViewModel:**
```kotlin
val selectedTab = MutableStateFlow(ProfileTab.NOTES)

val tabPostsFlow: Flow<List<FeedRow>> = selectedTab.flatMapLatest { tab ->
    when (tab) {
        ProfileTab.NOTES    -> eventRepository.userNotesFlow(pubkey)
        ProfileTab.REPLIES  -> eventRepository.userRepliesFlow(pubkey)
        ProfileTab.LONGFORM -> eventRepository.userLongformFlow(pubkey)
    }
}
```

**EventRepository:** Add pass-through methods for the three new DAO queries.

### Shared Composable

```kotlin
@Composable
fun ProfileTabRow(
    selectedTab: ProfileTab,
    onTabSelected: (ProfileTab) -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedTab.ordinal,
        containerColor = Black,
        contentColor = Color.White,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                color = Cyan,
            )
        },
    ) {
        ProfileTab.entries.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                text = {
                    Text(
                        text = when (tab) {
                            ProfileTab.NOTES    -> "Notes"
                            ProfileTab.REPLIES  -> "Replies"
                            ProfileTab.LONGFORM -> "Longform"
                        },
                        color = if (selectedTab == tab) Color.White else TextSecondary,
                    )
                },
            )
        }
    }
}
```

**Location:** New file `ui/profile/ProfileTab.kt` — shared between both profile screens.

**Both ProfileScreen and UserProfileScreen:**
- Add `ProfileTabRow` between profile header and posts list
- Switch from `postsFlow` / `userPostsFlow` to `tabPostsFlow` for the LazyColumn
- Add empty state text per tab

**Longform tap:** Kind-30023 items open `ArticleReaderScreen` (already built).

---

## Task 4: Followers Count (NIP-45 COUNT)

**Priority:** Last — with bail-out option.

**Scope:** Minimal. Single relay, cached result.

### RelayPool.sendCount()

```kotlin
suspend fun sendCount(
    relayUrl: String,
    filter: JsonObject,
): Long? = withContext(Dispatchers.IO) {
    // 1. Open temporary WebSocket to relayUrl (or reuse existing connection)
    // 2. Send: ["COUNT", "<sub-id>", filter]
    // 3. Wait for: ["COUNT", "<sub-id>", {"count": N}]
    // 4. Close subscription, return N
    // 5. Timeout after 10 seconds → return null
}
```

**Filter for followers:**
```json
{"kinds": [3], "#p": ["<target_pubkey>"]}
```

**Implementation:** Use existing `RelayConnection` if one is open to the relay, otherwise open a temporary connection. Parse the COUNT response as a one-shot — don't route through EventProcessor.

### Caching

**UserEntity** — add column:
```kotlin
@ColumnInfo(name = "follower_count") val followerCount: Long? = null,
@ColumnInfo(name = "follower_count_updated_at") val followerCountUpdatedAt: Long? = null,
```

**Migration (v5 → v6):** Two ALTER TABLE statements on users table.

**Refresh policy:** If `followerCountUpdatedAt` is null or older than 24 hours, re-fetch.

### ViewModel

**ProfileViewModel:**
```kotlin
val followerCount = MutableStateFlow<Long?>(null)

init {
    viewModelScope.launch(Dispatchers.IO) {
        val cached = userDao.getFollowerCount(pubkeyHex)
        if (cached != null && !isStale(cached.updatedAt)) {
            followerCount.value = cached.count
            return@launch
        }
        val count = relayPool.sendCount(
            relayUrl = "wss://purplepag.es",
            filter = buildJsonObject {
                put("kinds", buildJsonArray { add(3) })
                put("#p", buildJsonArray { add(pubkeyHex) })
            },
        )
        if (count != null) {
            followerCount.value = count
            userDao.updateFollowerCount(pubkeyHex, count, System.currentTimeMillis() / 1000)
        }
    }
}
```

### UI

**ProfileScreen** — change `StatLabel(label = "Followers", value = viewModel.followers)`:
```kotlin
val followerCount by viewModel.followerCount.collectAsState()
StatLabel(
    label = "followers",
    value = followerCount?.let { "~${it.toCompactSats()}" } ?: "—",
)
```

**Note:** `StatLabel` may need to accept `String` instead of `Int?` to support the `~` prefix and `—` fallback.

### Bail-out

If `sendCount()` implementation gets complicated (WebSocket message routing, response parsing edge cases), skip this task entirely. Show `—` for followers, ship following count only. Followers can come in Sprint 24.

---

## Execution Order

1. **Task 6** — Startup frame skip (investigate, fix or skip)
2. **Task 5** — Own-profile repost names (one init block)
3. **Task 1** — Zap total sats (migration v4→v5 + pipeline + UI)
4. **Task 3** — Following count (expose existing DAO on profile)
5. **Task 2** — Profile tabs (shared composable, 3 DAO queries)
6. **Task 4** — Followers NIP-45 COUNT (last, bail-out available)

**Migration strategy:** Task 1 creates `MIGRATION_4_5` (adds `zap_total_sats` to events table). If Task 4 proceeds, it creates `MIGRATION_5_6` (adds `follower_count` + `follower_count_updated_at` to users table). They are separate migrations — Task 4 has a bail-out option, so its migration must not be bundled with Task 1's.

---

## Files Modified

| File | Changes |
|------|---------|
| `EventEntity.kt` | Add `zapTotalSats` column |
| `EventDao.kt` | Add `addZapSats()`, `userNotesFlow()`, `userRepliesFlow()`, `userLongformFlow()`, add `zap_total_sats` to all FeedRow queries |
| `FeedRow` (in EventDao.kt) | Add `zapTotalSats: Long` field |
| `EventProcessor.kt` | Add `tagValue()`, `extractZapSats()`, call `addZapSats()` in `flushBatch()` after kind-9735 insert |
| `Migrations.kt` | Add `MIGRATION_4_5` (and `MIGRATION_5_6` if Task 4 proceeds) |
| `AppDatabase.kt` | Bump version to 5 (or 6), register migration(s) |
| `DatabaseModule.kt` | Add `MIGRATION_4_5` (and `MIGRATION_5_6`) to `.addMigrations()` call |
| `EventRepository.kt` | Add pass-through methods for tab queries |
| `FollowDao.kt` | Add `countFlow(): Flow<Int>` method |
| `ProfileViewModel.kt` | Add `fetchMissingProfiles` collectLatest, `followingCount`, `selectedTab`, `tabPostsFlow`, `followerCount` |
| `UserProfileViewModel.kt` | Add `selectedTab`, `tabPostsFlow` |
| `ProfileScreen.kt` | Add tab row, use `tabPostsFlow`, show following/followers counts |
| `UserProfileScreen.kt` | Add tab row, use `tabPostsFlow` |
| `NoteCard.kt` | Change ZapButton to use `sats: Long` + `toCompactSats()` |
| `RelayPool.kt` | Add `sendCount()` method (if Task 4 proceeds) |
| `UserEntity.kt` | Add `followerCount`, `followerCountUpdatedAt` columns (if Task 4 proceeds) |

## New Files

| File | Purpose |
|------|---------|
| `ui/profile/ProfileTab.kt` | `ProfileTab` enum + `ProfileTabRow` composable |
