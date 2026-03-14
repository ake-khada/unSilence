# unSilence Sprint 22: Follows Feed + Amber Signing + Feed Pagination

> **For Claude Code:** Read this entire file before brainstorming. Always check
> ACTUAL source files before making changes. 21 sprints complete.

---

## Codebase Constraints (non-negotiable)

- **DI:** Hilt only. No Koin. KSP, not kapt.
- **Events:** Quartz library types. Use `EventTemplate` constructor for JVM 17 compat.
- **Room migrations:** Index names MUST use `index_tablename_col1_col2` convention with backticks. Vararg syntax for @Index.
- **Versions:** Kotlin 2.3.0, KSP 2.3.0, Hilt 2.58, Room 2.7.1, AGP 8.9.1, Gradle 8.11.1, Compose BOM 2025.05.00, compileSdk/targetSdk 36.
- **Build:** Never run `./gradlew` from terminal while Android Studio is open.
- **Git:** Operations from `aivii` user terminal, not `android-dev`.
- **Architecture:** Relay → EventProcessor → Room → Flow/StateFlow → Compose UI.

---

## PRE-SPRINT HOTFIX: Markdown Renderer Crash

**Before anything else:** Downgrade `markdown-renderer` in `gradle/libs.versions.toml` from `0.39.2` to a version compatible with Compose BOM 2025.05.00. Version 0.39.2 crashes at runtime with:
```
NoSuchMethodError: getApplyOnDeactivatedNodeAssertion() in ComposeUiNode$Companion
```
Try `0.27.0` or `0.28.0` first. If those don't work, check the library's GitHub releases for which version targets our Compose version. The rest of the ArticleReaderScreen code stays the same — only the version number changes.

---

## Sprint 22 Goal

Fix three interconnected issues in one coherent sprint:

1. **Amber signing** — build a signing abstraction that works for both nsec and Amber login
2. **Follow list fetch** — fetch the user's kind-3 contact list on bootstrap (requires signing for Amber)
3. **Feed pagination** — default to Following feed with scroll-to-load-more on both Following and Global feeds

These are the top 3 usability issues. They share the same pipeline: signing → follow list → feed.

---

## Problem 1: Amber Signing (blocks everything else)

**Current state:** When logged in via Amber, `KeyManager` has the user's pubkey but NO private key. Any operation requiring signing (posting, reactions, reposts, zaps, fetching authenticated relay data) silently fails or doesn't trigger.

**What needs to happen:**

Create a `SigningManager` (or extend `KeyManager`) that abstracts signing:
- If nsec login → sign locally using the private key (current behavior)
- If Amber login → launch Amber's signing intent, wait for the signed result

**Amber signing flow (Android Intent-based):**
1. Build the unsigned event JSON
2. Create an Intent for `com.greenart7c3.nostrsigner` (Amber's package)
3. Intent extras: `"type" = "sign_event"`, `"event" = unsignedEventJson`, `"current_user" = pubkeyHex`
4. Launch via `ActivityResultLauncher`
5. Amber shows a signing prompt to the user
6. On result: extract the signed event from the result intent (`"signature"` extra or full signed event JSON)
7. Publish the signed event to relays

**This applies to ALL signed operations:**
- Posting (kind 1)
- Replies (kind 1 with e/p tags)
- Reactions (kind 7)
- Reposts (kind 6)
- Zaps (kind 9734 zap request)
- Delete (kind 5)

**Check how the existing code currently signs events** — trace from ComposePostScreen/actions through to relay publish. Find where the private key is used and add the Amber branch.

---

## Problem 2: Follow List (depends on signing)

**Current state:** `AppBootstrapper.bootstrap()` fetches kind-3 from relays. But:
- The kind-3 fetch might not populate Room correctly for Amber users
- The follows list may not be used to filter the default feed
- No re-fetch mechanism when follows change

**What needs to happen:**
1. On bootstrap: fetch kind-3 for the logged-in pubkey from indexer relays
2. Parse `p` tags → list of followed pubkeys
3. Store in Room (follows table or contacts table — check what exists)
4. The Following feed queries Room for events WHERE pubkey IN (followed pubkeys)
5. Following feed is the DEFAULT on app launch

---

## Problem 3: Feed Pagination (CRITICAL)

**Current state:** Global feed shows ~50 posts from initial relay fetch. No scroll-to-load-more. When filters (time range, etc.) are applied, only those ~50 posts are filtered → shows 5-10 results.

**What needs to happen:**

Apply the SAME pagination pattern already working in `UserProfileScreen` (Sprint 19):

1. **Scroll-to-bottom detection** on FeedScreen — `snapshotFlow` on `LazyListState`, trigger when within 5 items of end
2. **`fetchOlderEvents(untilTimestamp)`** — relay subscription with `until` filter for both global and following feeds
3. **Growing query window** — `_displayLimit` StateFlow starting at 200, incrementing by 200 on each load-more
4. **Initial fetch limit** — increase from 50 to 200
5. **Both feeds need this** — Following feed AND Global feed

**The UserProfileScreen already has this exact pattern:**
- `_displayLimit = MutableStateFlow(200)`
- `postsFlow` uses `combine(_pubkeyHex, _displayLimit)` with `flatMapLatest`
- `loadMore(currentOldest)` bumps limit + calls `fetchOlderPosts`
- `snapshotFlow { shouldLoadMore.value }` triggers loading

Copy and adapt this pattern for `FeedViewModel` / `FollowingFeedViewModel`.

---

## Default Feed Behavior

- **First launch / app open:** Show Following feed (posts from followed pubkeys)
- **If no follows yet:** Show Global feed as fallback
- **Tab/toggle:** User can switch between Following and Global
- **Both feeds paginate independently**

---

## Execution Order

1. **Hotfix:** Markdown renderer downgrade (one-line change, commit immediately)
2. **Task group A:** Amber signing abstraction (SigningManager or KeyManager extension)
3. **Task group B:** Follow list fetch + storage on bootstrap
4. **Task group C:** Feed pagination on FeedScreen (both Following and Global)
5. **Task group D:** Default to Following feed on launch

---

## Key Risk

The Amber signing flow requires `ActivityResultLauncher` which is tied to Activity/Fragment lifecycle. If the signing trigger comes from a ViewModel, it needs to communicate back to the Activity to launch the intent. Check how the existing Amber login flow handles this — reuse the same pattern.
