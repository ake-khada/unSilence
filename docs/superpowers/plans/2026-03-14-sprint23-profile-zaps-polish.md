# Sprint 23: Profile & Zaps Polish — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Six changes that transform the app from "work in progress" to "looks finished": zap total sats, profile tabs, following count, followers count (NIP-45), own-profile repost names fix, and startup frame skip investigation.

**Architecture:** All changes follow the existing Relay → EventProcessor → Room → Flow/StateFlow → Compose UI pipeline. No new architectural patterns. One Room migration (v4→v5), one optional migration (v5→v6 if NIP-45 task proceeds).

**Tech Stack:** Kotlin 2.3.0, Room 2.7.1, Hilt 2.58 (KSP), Compose BOM 2025.05.00, Quartz 1.05.1, compileSdk 36.

**Build constraint:** Never run `./gradlew` from terminal while Android Studio is open. Verify builds in Android Studio. Git operations from `aivii` user terminal only.

**Spec:** `docs/superpowers/specs/2026-03-14-sprint23-profile-zaps-polish-design.md`

---

## Chunk 1: Quick Fixes (Tasks 6, 5)

### Task 1: Startup Frame Skip Investigation

**Files:**
- Investigate: `app/src/main/kotlin/com/unsilence/app/ui/feed/FeedViewModel.kt`
- Investigate: `app/src/main/kotlin/com/unsilence/app/ui/onboarding/RootViewModel.kt`
- Investigate: `app/src/main/kotlin/com/unsilence/app/data/AppBootstrapper.kt`

**Context:** `Choreographer: Skipped 81 frames!` on cold launch with Amber login. AppBootstrapper already runs on `Dispatchers.IO`. Need to find if any `viewModelScope.launch` defaults to Main with heavy work.

- [ ] **Step 1: Investigate FeedViewModel.init**

`FeedViewModel.kt:132` — `viewModelScope.launch { ... }` has NO explicit dispatcher. This defaults to `Dispatchers.Main`. Inside it:
- `followDao.count()` — Room auto-dispatches to IO, fine
- `relaySetRepository.seedDefaults()` — likely Room, auto-dispatches
- `relaySetRepository.defaultSet()` — likely Room
- `relayPool.connect(urls)` — launches on RelayPool's own IO scope
- The `combine/flatMapLatest/collectLatest` chain collects on Main

The collection callback (lines 181-204) does list operations on fetched rows — lightweight. The real frame skip is likely Compose laying out 200 NoteCard items simultaneously on first render.

Check `RootViewModel.kt` — it's clean: `onOnboardingComplete()` just calls `bootstrapper.bootstrap()` which runs on its own IO scope. No Main thread work.

Read the code, assess, and decide: is there a fixable dispatcher issue, or is this Compose composition overhead?

- [ ] **Step 2: Apply fix OR document skip**

**If fixable:** The `viewModelScope.launch` at `FeedViewModel.kt:132` could be changed to `viewModelScope.launch(Dispatchers.IO)` BUT the `collectLatest` callback updates `_uiState` (a MutableStateFlow) and `hasNewTopPost` (a mutableStateOf). StateFlow emissions are thread-safe, but `mutableStateOf` must be updated on Main. So switching the entire launch to IO would break Compose state.

**Most likely outcome:** The 81 frames are Compose initial composition of the feed list (200 items). This is framework overhead — not fixable without lazy pagination of the LazyColumn (which already exists). **Skip this task.** Document finding as a comment in the commit.

- [ ] **Step 3: Commit investigation findings**

```bash
git add -A && git commit -m "chore: investigate startup frame skip — Compose composition overhead, not dispatcher issue"
```

---

### Task 2: Own-Profile Repost Names Fix

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileViewModel.kt:64-73`

**Context:** `ProfileViewModel` doesn't call `fetchMissingProfiles` for repost original authors. `UserProfileViewModel` (lines 77-95) already has this pattern. Copy-paste-adapt.

- [ ] **Step 1: Add fetchedProfilePubkeys set and collectLatest init block**

In `ProfileViewModel.kt`, add import for `extractRepostAuthorPubkey`:
```kotlin
import com.unsilence.app.data.relay.extractRepostAuthorPubkey
import kotlinx.coroutines.flow.collectLatest
```

Add the tracking set before the existing `init` block (after line 63):
```kotlin
private val fetchedProfilePubkeys = mutableSetOf<String>()
```

Modify the existing `init` block at line 67 to add a second launch:
```kotlin
init {
    viewModelScope.launch {
        if (pubkeyHex != null) {
            userRepository.fetchMissingProfiles(listOf(pubkeyHex))
        }
    }
    // Fetch missing profiles for repost original authors as posts arrive
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

- [ ] **Step 2: Build and verify**

Build in Android Studio. Verify no compile errors. Test by opening own profile with reposts — original author names should now resolve.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileViewModel.kt
git commit -m "fix: resolve repost author names on own profile

Add fetchMissingProfiles collectLatest to ProfileViewModel init,
matching the existing pattern in UserProfileViewModel."
```

---

## Chunk 2: Zap Total Sats (Task 1)

### Task 3: Room Migration v4→v5 — Add zap_total_sats Column

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/entity/EventEntity.kt:62-64`
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/Migrations.kt:1-20`
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/AppDatabase.kt:28`
- Modify: `app/src/main/kotlin/com/unsilence/app/di/DatabaseModule.kt:31`

- [ ] **Step 1: Add zapTotalSats to EventEntity**

In `EventEntity.kt`, add after the `cachedAt` field (line 63):
```kotlin
    /** Aggregated zap amount in sats from kind-9735 receipts */
    @ColumnInfo(name = "zap_total_sats")
    val zapTotalSats: Long = 0,
```

- [ ] **Step 2: Add MIGRATION_4_5 to Migrations.kt**

Add at the top of `Migrations.kt` (before `MIGRATION_3_4` at line 16), with doc comment update:
```kotlin
/**
 * Room schema migrations.
 *
 * v1 → v2: Add follows and relay_list_metadata tables (NIP-65 outbox routing).
 * v2 → v3: Replace single-column indexes with composite indexes for query performance.
 * v3 → v4: Add (root_id, created_at) index for thread queries.
 * v4 → v5: Add zap_total_sats column to events for displaying total zap amount.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN zap_total_sats INTEGER NOT NULL DEFAULT 0")
    }
}
```

- [ ] **Step 3: Bump AppDatabase version to 5**

In `AppDatabase.kt` line 28, change `version = 4` to `version = 5`.

- [ ] **Step 4: Register MIGRATION_4_5 in DatabaseModule**

In `DatabaseModule.kt` line 31, add the import and migration:
```kotlin
import com.unsilence.app.data.db.MIGRATION_4_5
```

Change `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)` to:
```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
```

- [ ] **Step 5: Build and verify**

Build in Android Studio. Room schema validation should pass — the entity column matches the migration SQL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/db/entity/EventEntity.kt \
       app/src/main/kotlin/com/unsilence/app/data/db/Migrations.kt \
       app/src/main/kotlin/com/unsilence/app/data/db/AppDatabase.kt \
       app/src/main/kotlin/com/unsilence/app/di/DatabaseModule.kt
git commit -m "feat: add zap_total_sats column to events table (migration v4→v5)"
```

---

### Task 4: Add zap_total_sats to FeedRow and All Existing Queries

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/dao/EventDao.kt:12-36,55-96,111-147,150-185,188-209,242-256`

- [ ] **Step 1: Add zapTotalSats to FeedRow data class**

In `EventDao.kt`, add after `cachedAt` field (line 25) and before the Author section comment (line 26):
```kotlin
    @ColumnInfo(name = "zap_total_sats")     val zapTotalSats: Long,
```

The full FeedRow now has 21 fields.

- [ ] **Step 2: Add e.zap_total_sats to feedFlow query**

In the `feedFlow` SELECT (lines 56-76), add after `e.cached_at,` (line 68):
```sql
            e.zap_total_sats,
```

- [ ] **Step 3: Add e.zap_total_sats to followingFeedFlow query**

In the `followingFeedFlow` SELECT (lines 112-132), add after `e.cached_at,` (line 124):
```sql
            e.zap_total_sats,
```

- [ ] **Step 4: Add e.zap_total_sats to userPostsFlow query**

In the `userPostsFlow` SELECT (lines 151-171), add after `e.cached_at,` (line 163):
```sql
            e.zap_total_sats,
```

- [ ] **Step 5: Add e.zap_total_sats to threadFlow query**

In the `threadFlow` SELECT (lines 189-197), add after `e.cached_at,` (line 191):
```sql
            e.zap_total_sats,
```

- [ ] **Step 6: Add e.zap_total_sats to searchNotes query**

In the `searchNotes` SELECT (lines 243-248), add after `e.cached_at,` (line 245):
```sql
            e.zap_total_sats,
```

- [ ] **Step 7: Add addZapSats DAO method**

Add after the `searchNotes` method (after line 256):
```kotlin
    /** Increment the zap sats total for the given event. Called by EventProcessor for kind-9735. */
    @Query("UPDATE events SET zap_total_sats = zap_total_sats + :sats WHERE id = :eventId")
    suspend fun addZapSats(eventId: String, sats: Long)
```

- [ ] **Step 8: Build and verify**

Build in Android Studio. All 5 existing queries + the new DAO method should compile against the updated FeedRow and EventEntity.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/db/dao/EventDao.kt
git commit -m "feat: add zap_total_sats to FeedRow and all feed queries"
```

---

### Task 5: extractZapSats + EventProcessor Integration

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/EventProcessor.kt:1-27,361-379`

- [ ] **Step 1: Add imports**

Add to `EventProcessor.kt` imports (after line 24):
```kotlin
import java.math.BigDecimal
```

Note: `jsonPrimitive` is already imported. Do NOT add `import kotlinx.serialization.json.Json` — the code uses `NostrJson` (defined in the same package) for all JSON parsing.

Check if `LnInvoiceUtil` is accessible. Add import:
```kotlin
import com.vitorpamplona.quartz.lightning.LnInvoiceUtil
```

If the import path doesn't resolve (Quartz 1.05.1 may not expose it), skip bolt11 parsing and use amount-tag-only approach.

- [ ] **Step 2: Add tagValue helper and extractZapSats function**

Add before the `flushBatch` method (before line 361):

```kotlin
    // ── Zap sats extraction ─────────────────────────────────────────────

    /** Extract a tag value from a JSON-serialized tags array: [["key","value"],...] */
    private fun tagValue(tagsJson: String, key: String): String? = runCatching {
        NostrJson.parseToJsonElement(tagsJson).jsonArray
            .firstOrNull { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == key }
            ?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content
    }.getOrNull()

    /**
     * Extract sats from a kind-9735 zap receipt's tags.
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
            } catch (_: Exception) { }
        }

        // Fallback: read "amount" tag from embedded zap request (millisats)
        val descriptionJson = tagValue(tagsJson, "description") ?: return 0L
        return try {
            val zapRequest = NostrJson.parseToJsonElement(descriptionJson).jsonObject
            val tags = zapRequest["tags"]?.jsonArray
            val amountTag = tags?.firstOrNull { tag ->
                tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "amount"
            }
            val msats = amountTag?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content?.toLongOrNull()
            (msats ?: 0L) / 1_000L
        } catch (_: Exception) { 0L }
    }
```

**Note:** Use `NostrJson` (already imported/used in this file) instead of `Json` for parsing consistency. Check if `NostrJson` is the same as `kotlinx.serialization.json.Json` — it's defined in `NostrJson.kt` in the same package.

- [ ] **Step 3: Add zap sats aggregation to flushBatch**

In `flushBatch()`, after the `eventDao.insertOrIgnoreBatch()` call (line 376), add:

```kotlin
        // Aggregate zap sats for kind-9735 receipts
        for (entity in events.values) {
            if (entity.kind == 9735 && entity.rootId != null) {
                val sats = extractZapSats(entity.tags)
                if (sats > 0) {
                    eventDao.addZapSats(entity.rootId, sats)
                }
            }
        }
```

The full `flushBatch` now looks like:
```kotlin
    private suspend fun flushBatch(batch: List<ProcessedEvent>) {
        val events    = LinkedHashMap<String, EventEntity>()
        val users     = LinkedHashMap<String, UserEntity>()
        val reactions = LinkedHashMap<String, ReactionEntity>()

        for (item in batch) {
            when (item) {
                is ProcessedEvent.Event    -> events[item.entity.id]          = item.entity
                is ProcessedEvent.User     -> users[item.entity.pubkey]       = item.entity
                is ProcessedEvent.Reaction -> reactions[item.entity.eventId]  = item.entity
            }
        }

        if (events.isNotEmpty())    eventDao.insertOrIgnoreBatch(events.values.toList())
        if (users.isNotEmpty())     userDao.upsertBatch(users.values.toList())
        if (reactions.isNotEmpty()) reactionDao.insertOrIgnoreBatch(reactions.values.toList())

        // Aggregate zap sats for kind-9735 receipts
        for (entity in events.values) {
            if (entity.kind == 9735 && entity.rootId != null) {
                val sats = extractZapSats(entity.tags)
                if (sats > 0) {
                    eventDao.addZapSats(entity.rootId, sats)
                }
            }
        }
    }
```

- [ ] **Step 4: Build and verify**

Build in Android Studio. Check that `LnInvoiceUtil` import resolves. If not, remove the bolt11 block and keep only the amount-tag fallback.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/relay/EventProcessor.kt
git commit -m "feat: extract and aggregate zap sats from kind-9735 receipts

Uses Quartz LnInvoiceUtil for bolt11 parsing with amount-tag fallback.
Aggregation runs in flushBatch() after batch insert."
```

---

### Task 6: ZapButton UI — Display Sats Instead of Count

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt:488-497,606-640,823-827`

- [ ] **Step 1: Add toCompactSats formatter**

In `NoteCard.kt`, add after `formatCount` (after line 827). Use `internal` visibility so `ProfileScreen` can reuse it for follower count display:
```kotlin
/** Format sats compactly: 21000 → "21k", 1500000 → "1.5M" */
internal fun Long.toCompactSats(): String = when {
    this >= 1_000_000 -> {
        val s = "%.1fM".format(this / 1_000_000.0)
        if (s.endsWith(".0M")) s.dropLast(3) + "M" else s
    }
    this >= 1_000 -> {
        val s = "%.1fk".format(this / 1_000.0)
        if (s.endsWith(".0k")) s.dropLast(3) + "k" else s
    }
    else -> this.toString()
}
```

- [ ] **Step 2: Change ZapButton to accept sats: Long**

Replace the `ZapButton` composable (lines 606-640) with:
```kotlin
/** Zap button: Amber when sats > 0 or user has zapped, supports single tap and long-press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZapButton(
    sats: Long,
    hasZapped: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val tint = if (sats > 0 || hasZapped) ZapAmber else ActionTint
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier
            .defaultMinSize(minWidth = 48.dp)
            .combinedClickable(
                onClick     = onTap,
                onLongClick = onLongPress,
            ),
    ) {
        Icon(
            imageVector        = Icons.Filled.ElectricBolt,
            contentDescription = "Zap",
            tint               = tint,
            modifier           = Modifier.size(Sizing.actionIcon),
        )
        if (sats > 0) {
            Spacer(Modifier.width(Spacing.micro))
            Text(
                text     = sats.toCompactSats(),
                color    = tint,
                fontSize = 12.sp,
            )
        }
    }
}
```

- [ ] **Step 3: Update ZapButton call site**

In `NoteCard.kt` at the ZapButton call (lines 488-497), change `count = row.zapCount` to `sats = row.zapTotalSats`:
```kotlin
            ZapButton(
                sats          = row.zapTotalSats,
                hasZapped     = hasZapped,
                onTap         = {
                    if (isNwcConfigured) onZap(1_000L) else showConnectWallet = true
                },
                onLongPress   = {
                    if (isNwcConfigured) showZapPicker = true else showConnectWallet = true
                },
            )
```

- [ ] **Step 4: Build and verify**

Build in Android Studio. The ZapButton should now show sats amounts like "21k" instead of zap counts like "3".

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt
git commit -m "feat: display total zap sats instead of zap count in action bar

Shows compact sats (21k, 1.5M) with amber color. Uses toCompactSats() formatter."
```

---

## Chunk 3: Following Count + Profile Tabs (Tasks 3, 2)

### Task 7: Following Count on Own Profile

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/dao/FollowDao.kt:41-43`
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileViewModel.kt:33-39,64-65`
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileScreen.kt:74-75,228-238,382-403`

- [ ] **Step 1: Add countFlow() to FollowDao**

In `FollowDao.kt`, add after the existing `count()` method (after line 42):
```kotlin
    /** Reactive follow count — re-emits on every follow list change. */
    @Query("SELECT COUNT(*) FROM follows")
    abstract fun countFlow(): Flow<Int>
```

- [ ] **Step 2: Add followDao injection and followingCount to ProfileViewModel**

In `ProfileViewModel.kt`, add `FollowDao` to constructor (line 38, after `relayPool`):
```kotlin
    private val followDao: com.unsilence.app.data.db.dao.FollowDao,
```

Add import:
```kotlin
import com.unsilence.app.data.db.dao.FollowDao
```

Replace the hardcoded `val following: Int? = null` and `val followers: Int? = null` (lines 64-65) with:
```kotlin
    /** Live following count from local follows table. */
    val followingCount: StateFlow<Int> = followDao.countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
```

- [ ] **Step 3: Update StatLabel to accept String**

In `ProfileScreen.kt`, change `StatLabel` (lines 382-397) to accept `String` for the value:
```kotlin
@Composable
private fun StatLabel(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text       = value,
            color      = Color.White,
            fontSize   = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text     = label,
            color    = TextSecondary,
            fontSize = 13.sp,
        )
    }
}
```

Remove the `formatStatCount` function (lines 399-403) — no longer needed since callers will format before passing.

- [ ] **Step 4: Update stats row in ProfileScreen**

In `ProfileScreen.kt`, add state collection (after line 75):
```kotlin
    val followingCount by viewModel.followingCount.collectAsStateWithLifecycle()
```

Update the stats row (lines 228-238):
```kotlin
                // Following / Followers stats row
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    StatLabel(label = "Following", value = "$followingCount")
                    Spacer(Modifier.size(20.dp))
                    StatLabel(label = "Followers", value = "—")
                }
```

- [ ] **Step 5: Build and verify**

Build in Android Studio. Profile should show the actual following count and "—" for followers.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/db/dao/FollowDao.kt \
       app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileViewModel.kt \
       app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileScreen.kt
git commit -m "feat: show live following count on own profile

Adds FollowDao.countFlow() reactive query. Followers shows '—' until NIP-45 support."
```

---

### Task 8: Profile Tab Queries — DAO + Repository

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/dao/EventDao.kt` (add 3 new query methods)
- Modify: `app/src/main/kotlin/com/unsilence/app/data/repository/EventRepository.kt` (add 3 pass-through methods)

- [ ] **Step 1: Add userNotesFlow to EventDao**

Add after `userPostsFlow` method (after line 185):
```kotlin
    /** Notes tab: kind 1 top-level posts only (no replies, no reposts). */
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
        LEFT JOIN events    z   ON z.root_id         = e.id AND z.kind   = 9735
        WHERE e.pubkey = :pubkey AND e.kind = 1
          AND e.reply_to_id IS NULL AND e.root_id IS NULL
        GROUP BY e.id
        ORDER BY e.created_at DESC
        LIMIT :limit
    """)
    fun userNotesFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>>
```

- [ ] **Step 2: Add userRepliesFlow to EventDao**

Add after `userNotesFlow`:
```kotlin
    /** Replies tab: kind 1 events that are replies (have reply_to_id or root_id). */
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
        LEFT JOIN events    z   ON z.root_id         = e.id AND z.kind   = 9735
        WHERE e.pubkey = :pubkey AND e.kind = 1
          AND (e.reply_to_id IS NOT NULL OR e.root_id IS NOT NULL)
        GROUP BY e.id
        ORDER BY e.created_at DESC
        LIMIT :limit
    """)
    fun userRepliesFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>>
```

- [ ] **Step 3: Add userLongformFlow to EventDao**

Add after `userRepliesFlow`:
```kotlin
    /** Longform tab: kind 30023 articles (NIP-23). */
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

- [ ] **Step 4: Add pass-through methods to EventRepository**

In `EventRepository.kt`, add after `userPostsFlow` (after line 45):
```kotlin
    fun userNotesFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>> =
        eventDao.userNotesFlow(pubkey, limit)

    fun userRepliesFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>> =
        eventDao.userRepliesFlow(pubkey, limit)

    fun userLongformFlow(pubkey: String, limit: Int = 200): Flow<List<FeedRow>> =
        eventDao.userLongformFlow(pubkey, limit)
```

- [ ] **Step 5: Build and verify**

Build in Android Studio. All queries should compile against FeedRow.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/db/dao/EventDao.kt \
       app/src/main/kotlin/com/unsilence/app/data/repository/EventRepository.kt
git commit -m "feat: add filtered DAO queries for profile tabs (notes/replies/longform)"
```

---

### Task 9: ProfileTab Enum + ProfileTabRow Composable

**Files:**
- Create: `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileTab.kt`

- [ ] **Step 1: Create ProfileTab.kt**

Create `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileTab.kt`:
```kotlin
package com.unsilence.app.ui.profile

import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.TextSecondary

enum class ProfileTab { NOTES, REPLIES, LONGFORM }

@Composable
fun ProfileTabRow(
    selectedTab: ProfileTab,
    onTabSelected: (ProfileTab) -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedTab.ordinal,
        containerColor   = Black,
        contentColor     = Color.White,
        indicator        = { tabPositions ->
            if (selectedTab.ordinal < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                    color    = Cyan,
                )
            }
        },
    ) {
        ProfileTab.entries.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick  = { onTabSelected(tab) },
                text     = {
                    Text(
                        text  = when (tab) {
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

- [ ] **Step 2: Build and verify**

Build in Android Studio. The new file should compile cleanly.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileTab.kt
git commit -m "feat: add ProfileTab enum and ProfileTabRow composable"
```

---

### Task 10: Wire Profile Tabs into ViewModels + Screens

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileViewModel.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileViewModel.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileScreen.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileScreen.kt`

- [ ] **Step 1: Add tab state and tabPostsFlow to ProfileViewModel**

In `ProfileViewModel.kt`, add imports:
```kotlin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
```

Add after `postsFlow` (after line 53):
```kotlin
    // ── Profile tabs ──────────────────────────────────────────────────
    val selectedTab = MutableStateFlow(ProfileTab.NOTES)

    @OptIn(ExperimentalCoroutinesApi::class)
    val tabPostsFlow: Flow<List<FeedRow>> =
        if (pubkeyHex != null) {
            selectedTab.flatMapLatest { tab ->
                when (tab) {
                    ProfileTab.NOTES    -> eventRepository.userNotesFlow(pubkeyHex)
                    ProfileTab.REPLIES  -> eventRepository.userRepliesFlow(pubkeyHex)
                    ProfileTab.LONGFORM -> eventRepository.userLongformFlow(pubkeyHex)
                }
            }
        } else emptyFlow()
```

Update the `fetchMissingProfiles` collectLatest to use `tabPostsFlow` instead of `postsFlow`:
```kotlin
    // Fetch missing profiles for repost original authors as posts arrive
    viewModelScope.launch {
        tabPostsFlow.collectLatest { rows ->
            // ... same body as before
        }
    }
```

- [ ] **Step 2: Add tab state and tabPostsFlow to UserProfileViewModel**

In `UserProfileViewModel.kt`, add after `postsFlow` (after line 61):
```kotlin
    // ── Profile tabs ──────────────────────────────────────────────────
    val selectedTab = MutableStateFlow(ProfileTab.NOTES)

    val tabPostsFlow: Flow<List<FeedRow>> =
        combine(_pubkeyHex.filterNotNull(), selectedTab) { pk, tab -> pk to tab }
            .flatMapLatest { (pk, tab) ->
                when (tab) {
                    ProfileTab.NOTES    -> eventRepository.userNotesFlow(pk)
                    ProfileTab.REPLIES  -> eventRepository.userRepliesFlow(pk)
                    ProfileTab.LONGFORM -> eventRepository.userLongformFlow(pk)
                }
            }
```

Reset tab on profile load — in `loadProfile()` (line 102), add:
```kotlin
        selectedTab.value = ProfileTab.NOTES
```

Update the `fetchMissingProfiles` collectLatest to use `tabPostsFlow` instead of `postsFlow`.

- [ ] **Step 3: Update ProfileScreen to use tabs**

In `ProfileScreen.kt`, add imports:
```kotlin
import androidx.compose.runtime.collectAsState
```

Change posts collection (line 75) from:
```kotlin
    val posts           by viewModel.postsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
```
to:
```kotlin
    val posts           by viewModel.tabPostsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedTab     by viewModel.selectedTab.collectAsStateWithLifecycle()
```

Replace the "Posts section header" item (lines 244-256) with:
```kotlin
            // ── Profile tabs ──────────────────────────────────────────────────
            item {
                ProfileTabRow(
                    selectedTab   = selectedTab,
                    onTabSelected = { viewModel.selectedTab.value = it },
                )
            }
```

Update the empty state text to be tab-aware:
```kotlin
            if (posts.isEmpty()) {
                item {
                    Text(
                        text     = when (selectedTab) {
                            ProfileTab.NOTES    -> "No notes yet"
                            ProfileTab.REPLIES  -> "No replies yet"
                            ProfileTab.LONGFORM -> "No articles yet"
                        },
                        color    = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.medium),
                    )
                }
            }
```

- [ ] **Step 4: Update UserProfileScreen to use tabs**

Apply the same pattern to `UserProfileScreen.kt`:

Change posts collection (line 81):
```kotlin
    val posts           by viewModel.tabPostsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedTab     by viewModel.selectedTab.collectAsStateWithLifecycle()
```

Replace the "Posts section header" item (lines 257-269) with the same `ProfileTabRow` pattern.

Update empty state text to be tab-aware (same as ProfileScreen).

- [ ] **Step 5: Handle longform article taps**

In both `ProfileScreen.kt` and `UserProfileScreen.kt`, the `NoteCard` already handles kind-30023 via `ArticleReaderScreen` if that's wired up. Verify: check if NoteCard or the profile screens handle article taps. If not, add:

```kotlin
// In the items() block, before NoteCard:
var showArticle by remember { mutableStateOf<FeedRow?>(null) }

// After NoteCard, check for article tap:
if (row.kind == 30023) {
    // NoteCard click should open ArticleReaderScreen
}
```

Check existing code first — this may already work via the existing NoteCard article handling.

- [ ] **Step 6: Build and verify**

Build in Android Studio. Test:
- Own profile: tabs should show Notes/Replies/Longform with cyan underline
- Other user profile: same tabs
- Tab switching should filter content correctly

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileViewModel.kt \
       app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileViewModel.kt \
       app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileScreen.kt \
       app/src/main/kotlin/com/unsilence/app/ui/profile/UserProfileScreen.kt
git commit -m "feat: add Notes/Replies/Longform tabs to profile screens

Shared ProfileTabRow composable, tab-specific DAO queries via flatMapLatest.
Both own profile and other user profiles get the same tab system."
```

---

## Chunk 4: Followers Count — NIP-45 (Task 4)

### Task 11: RelayPool.sendCount() — NIP-45 COUNT Support

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt`

**Bail-out:** If this gets complicated, skip. Show "—" for followers, ship what we have.

- [ ] **Step 1: Read RelayPool and RelayConnection to understand WebSocket messaging**

Read the full `RelayPool.kt` and the `RelayConnection` class to understand:
- How messages are sent to relays (`send()` or `sendMessage()`)
- How responses are received and dispatched
- Whether there's a way to intercept a one-shot response without routing through EventProcessor

- [ ] **Step 2: Implement sendCount()**

Add to `RelayPool.kt`:

```kotlin
    /**
     * NIP-45 COUNT query: send a COUNT request to a single relay and wait for the response.
     * Returns the count, or null if the relay doesn't support NIP-45 or times out.
     */
    suspend fun sendCount(relayUrl: String, filter: JsonObject): Long? =
        withContext(Dispatchers.IO) {
            try {
                val subId = "count-${System.nanoTime()}"
                val countRequest = buildJsonArray {
                    add("COUNT")
                    add(subId)
                    add(filter)
                }.toString()

                // Use existing connection if available, otherwise open temporary one
                val conn = connections[relayUrl]
                if (conn == null) return@withContext null

                val result = CompletableDeferred<Long?>()

                // Register a one-shot message listener for the COUNT response
                conn.setCountListener(subId) { count ->
                    result.complete(count)
                }

                conn.send(countRequest)

                // Wait up to 10 seconds for response
                withTimeoutOrNull(10_000) { result.await() }
            } catch (_: Exception) { null }
        }
```

**Note:** This depends on `RelayConnection` having a way to register a one-shot listener for COUNT responses. Read the actual `RelayConnection` code to determine the right approach. If `RelayConnection` routes everything through `EventProcessor.process()` and there's no easy way to intercept COUNT responses, the implementation needs to:

1. Add COUNT response detection in `RelayConnection.onMessage()`
2. Route COUNT responses to a callback instead of EventProcessor

If this requires significant changes to RelayConnection, **bail out** — skip Task 11 entirely.

- [ ] **Step 3: If bail-out — skip to commit with TODO comment**

If sendCount is too complex, add a TODO to `ProfileViewModel.kt`:
```kotlin
    // TODO Sprint 24: Add NIP-45 COUNT for followers via RelayPool.sendCount()
```

- [ ] **Step 4: Build and verify**

Build in Android Studio.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add NIP-45 COUNT support to RelayPool (followers count)"
# OR if bailed out:
git commit -m "chore: skip NIP-45 followers count — deferred to Sprint 24"
```

---

### Task 12: Wire Followers Count into ProfileViewModel + UI

**Only if Task 11 succeeded. If bailed out, skip this task.**

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/entity/UserEntity.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/Migrations.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/AppDatabase.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/di/DatabaseModule.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/data/db/dao/UserDao.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileViewModel.kt`
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/profile/ProfileScreen.kt`

- [ ] **Step 1: Add follower columns to UserEntity**

In `UserEntity.kt`, add after `updatedAt` (after line 35):
```kotlin
    @ColumnInfo(name = "follower_count")
    val followerCount: Long? = null,

    @ColumnInfo(name = "follower_count_updated_at")
    val followerCountUpdatedAt: Long? = null,
```

- [ ] **Step 2: Add MIGRATION_5_6**

In `Migrations.kt`, add:
```kotlin
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE users ADD COLUMN follower_count INTEGER")
        db.execSQL("ALTER TABLE users ADD COLUMN follower_count_updated_at INTEGER")
    }
}
```

Bump `AppDatabase.kt` version to 6. Add `MIGRATION_5_6` to `DatabaseModule.kt`.

- [ ] **Step 3: Add UserDao methods for follower count**

In `UserDao.kt`, add:
```kotlin
    @Query("SELECT follower_count FROM users WHERE pubkey = :pubkey")
    suspend fun getFollowerCount(pubkey: String): Long?

    @Query("SELECT follower_count_updated_at FROM users WHERE pubkey = :pubkey")
    suspend fun getFollowerCountUpdatedAt(pubkey: String): Long?

    @Query("UPDATE users SET follower_count = :count, follower_count_updated_at = :updatedAt WHERE pubkey = :pubkey")
    suspend fun updateFollowerCount(pubkey: String, count: Long, updatedAt: Long)
```

- [ ] **Step 4: Add follower count fetching to ProfileViewModel**

In `ProfileViewModel.kt`, add:
```kotlin
    val followerCount = MutableStateFlow<Long?>(null)

    init {
        // Fetch follower count via NIP-45
        if (pubkeyHex != null) {
            viewModelScope.launch(Dispatchers.IO) {
                // Check cache first
                val cached = userDao.getFollowerCount(pubkeyHex)
                val cachedAt = userDao.getFollowerCountUpdatedAt(pubkeyHex)
                val oneDayAgo = System.currentTimeMillis() / 1000 - 86_400

                if (cached != null && cachedAt != null && cachedAt > oneDayAgo) {
                    followerCount.value = cached
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
    }
```

Add necessary imports and `UserDao` injection.

- [ ] **Step 5: Update ProfileScreen stats row**

Change the Followers StatLabel:
```kotlin
    val followerCount by viewModel.followerCount.collectAsStateWithLifecycle()
```

```kotlin
    StatLabel(
        label = "Followers",
        value = followerCount?.let { "~${it.toCompactSats()}" } ?: "—",
    )
```

Note: `toCompactSats()` is declared `internal` in NoteCard.kt (Task 6). Import it directly: `import com.unsilence.app.ui.feed.toCompactSats`.

- [ ] **Step 6: Build and verify**

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: show approximate follower count via NIP-45 COUNT

Queries purplepag.es for kind-3 events mentioning the user's pubkey.
Cached in Room, refreshed daily. Shows '~12.5k followers' format."
```

---

## Post-Implementation

After all tasks are complete:
1. Build full project in Android Studio
2. Test on device: cold launch, own profile, other user profile, zap display, tab switching
3. Verify no regressions in feed, thread view, search
4. Final commit with any cleanup
