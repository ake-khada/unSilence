# unSilence Sprint 23: Profile & Zaps Polish

> **For Claude Code:** Read this entire file before brainstorming. Always check
> ACTUAL source files before making changes. 22 sprints complete.
> Also read CLAUDE.md in the repo root — it's the project's design bible.

---

## Codebase Constraints (non-negotiable)

- **DI:** Hilt only. No Koin. KSP, not kapt.
- **Events:** Quartz library types. Use `EventTemplate` constructor for JVM 17 compat.
- **Room migrations:** Index names MUST use `index_tablename_col1_col2` convention with backticks. Vararg syntax for @Index. Every migration index MUST be declared in @Entity.
- **Versions:** Kotlin 2.3.0, KSP 2.3.0, Hilt 2.58, Room 2.7.1, AGP 8.9.1, Gradle 8.11.1, Compose BOM 2025.05.00, compileSdk/targetSdk 36, markdown-renderer 0.28.0.
- **Build:** Never run `./gradlew` from terminal while Android Studio is open.
- **Git:** Operations from `aivii` user terminal, not `android-dev`.
- **Architecture:** Relay → EventProcessor → Room → Flow/StateFlow → Compose UI.
- **Theme:** AMOLED black #000000, cyan #00E5FF, zap amber #FFAB00. No light theme.
- **Spacing:** Golden ratio system — 5/8/12/20/32/52dp. See CLAUDE.md.

---

## Sprint 23 Goal

Six changes that transform the app from "work in progress" to "looks finished":

1. Zap total sats display (⚡ 21k instead of ⚡ 3)
2. Profile tabs (Notes / Replies / Longform)
3. Following count (exact, from kind-3 p-tags)
4. Followers count (approximate, via NIP-45 COUNT)
5. Own-profile repost names fix
6. 81 frames skipped on startup fix

---

## Task 1: Zap Total Sats Display

### Problem
The action bar shows zap COUNT (how many times zapped) not zap AMOUNT (total sats).
CLAUDE.md wireframe shows `⚡ 21k` — total sats, formatted compactly.

### What needs to happen

**1a. extractZapSats() utility**
Parse bolt11 invoice from kind-9735 zap receipts to get actual sats amount.

From the spec (already designed, just not implemented):
```kotlin
fun extractZapSats(receipt: Event): Long {
    // 1. Get the embedded zap request JSON from the "description" tag
    val descriptionJson = receipt.tags
        .firstOrNull { it.size >= 2 && it[0] == "description" }
        ?.get(1) ?: return 0L

    return try {
        val zapRequest = Json.decodeFromString<Event>(descriptionJson)
        // 2. Read the "amount" tag from the zap request (millisats)
        val msatsString = zapRequest.tags
            .firstOrNull { it.size >= 2 && it[0] == "amount" }
            ?.get(1)
        val msats = msatsString?.toLongOrNull() ?: return 0L
        msats / 1_000L
    } catch (e: Exception) { 0L }
}
```

Also check the `bolt11` tag on the receipt itself as primary source (more accurate).

**1b. toCompactSats() formatter**
```kotlin
fun Long.toCompactSats(): String = when {
    this >= 1_000_000 -> String.format("%.1fM", this / 1_000_000.0)
    this >= 1_000     -> String.format("%.1fk", this / 1_000.0)
    else              -> this.toString()
}
```

**1c. Room migration — add zap_total_sats column**
- Add `zapTotalSats: Long = 0` to EventEntity (or whatever the events table entity is)
- Migration: `ALTER TABLE events ADD COLUMN zap_total_sats INTEGER NOT NULL DEFAULT 0`
- Bump DB version

**1d. EventProcessor — aggregate zap sats**
When processing kind-9735 (zap receipt):
- Extract target event ID from `e` tag
- Extract sats via extractZapSats()
- UPDATE events SET zap_total_sats = zap_total_sats + :sats WHERE id = :targetId

**1e. UI — display sats instead of count**
In the action bar, change the zap display from `zapCount` to `zapTotalSats.toCompactSats()`.
Use amber color (#FFAB00) for the ⚡ icon and amount — this is the zap accent per CLAUDE.md.

---

## Task 2: Profile Tabs (Notes / Replies / Longform)

### Problem
User profiles show all posts in one flat list. Need tabs to separate content types.

### What needs to happen

**Tab row below profile header:**
Three tabs: `Notes` | `Replies` | `Longform`
- Notes: kind 1 where replyToId IS NULL (top-level posts only)
- Replies: kind 1 where replyToId IS NOT NULL
- Longform: kind 30023

Default: Notes tab selected.

**DAO queries:**
Three variants of the user posts query, filtered by type:
- `userNotesFlow(pubkey, limit)` — kind=1, replyToId IS NULL
- `userRepliesFlow(pubkey, limit)` — kind=1, replyToId IS NOT NULL
- `userLongformFlow(pubkey, limit)` — kind=30023

**ViewModel:**
Track selected tab. Switch between flows based on tab selection.

**UI:**
Tab row using Material 3 `TabRow` or `ScrollableTabRow`. Cyan underline on selected tab.
Each tab shows its own LazyColumn content.

---

## Task 3: Following Count

### Problem
Profile shows no following count. It should show exact count from kind-3.

### What needs to happen

The follows table already exists in Room. Just count it:
```kotlin
@Query("SELECT COUNT(*) FROM follows")
suspend fun count(): Int
```

Display on profile: "123 following"

This might already partially exist — FeedViewModel uses `followDao.count()` for default feed selection (Sprint 22). Just need to expose it on the profile screen.

---

## Task 4: Followers Count (NIP-45)

### Problem
No follower count displayed. Needs NIP-45 COUNT query to indexer relays.

### What needs to happen

**Send COUNT request to indexer relays:**
```json
["COUNT", "<sub-id>", {"kinds": [3], "#p": ["<target_pubkey>"]}]
```

**Handle COUNT response:**
```json
["COUNT", "<sub-id>", {"count": 12500}]
```

Query multiple indexers (`purplepag.es`, `user.kindpag.es`, `indexer.coracle.social`), take the highest count.

**Display:** "~12.5k followers" — always prefixed with ~ (approximate).

**Cache:** Store in Room, refresh daily. Could use a `user_stats` table or just a column on the users table.

**If relay doesn't support NIP-45:** Show "—" instead of a number.

---

## Task 5: Own-Profile Repost Names Fix

### Problem
ProfileViewModel (own profile) doesn't trigger `fetchMissingProfiles` for repost original authors, so repost names show as truncated hex.

### What needs to happen

Add the same `collectLatest` init block to `ProfileViewModel` that already works in `UserProfileViewModel` and `FeedViewModel`:

```kotlin
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

Plus add `fetchedProfilePubkeys` set and clear it appropriately.

---

## Task 6: 81 Frames Skipped on Startup

### Problem
`Choreographer: Skipped 81 frames!` on launch. Relay connections blocking main thread.

### What needs to happen

Check `AppBootstrapper.bootstrap()` and `RelayPool.connect()` — if any relay connection or subscription work happens on `Dispatchers.Main`, move it to `Dispatchers.IO`.

The bootstrap sequence (connect relays, fetch kind-3, fetch kind-10002) should all be on IO. If `viewModelScope.launch` is used without a dispatcher, it defaults to Main — that's the likely cause.

Also check if `EventProcessor.start()` or `OutboxRouter.start()` do heavy init work on the calling thread.

---

## Execution Order

1. Task 6 first (startup fix — affects every launch)
2. Task 5 (own-profile repost — one-liner)
3. Task 1 (zap sats — Room migration + pipeline + UI)
4. Task 3 (following count — trivial)
5. Task 2 (profile tabs — UI work)
6. Task 4 (followers count — NIP-45, most complex)
