# unSilence — Architecture Vision

**Core mission:** The fastest, most efficient Android-native Nostr client. Less is more. Every millisecond of latency, every byte of RAM, every unnecessary network call is the enemy. The user should feel like they're browsing a local database that happens to sync with the world.

---

## Guiding Principles

1. **Room is the only read source.** UI never waits on a WebSocket. Every screen renders from Room immediately, then updates reactively as fresh data arrives.

2. **Network fills gaps, not screens.** Fetches are driven by what's *missing*, not by what the user clicked. If Room has it, show it. If Room doesn't, fetch it in the background while showing honest loading states.

3. **Honest UI states over fake emptiness.** Never show "No posts yet" when the real state is "haven't asked yet." The user deserves to know: loaded, loading, partial, failed, empty.

4. **Scoped relay sets, not broadcast.** Profiles go to indexer relays. Feed goes to the user's outbox model. Search goes to NIP-50 relays. Global goes to high-throughput public relays. Never ask every relay for everything.

5. **Dedup before parse, prune without breaking.** The `seenIds` cache eliminates ~80% of duplicate processing before JSON parsing. Pruning never deletes events that are still visible or referenced.

6. **Sequential where it matters, parallel where it's safe.** Bootstrap is sequential (connect → fetch follows → fetch profiles → open feed). Feed rendering is parallel (Room Flows update independently). Never race when correctness depends on order.

7. **Native performance is the feature.** No WebView. No Flutter bridge. No React Native. Kotlin + Compose + Room + OkHttp WebSockets. The stack is the competitive advantage.

---

## Current Architecture (v1 — Sprints 1-23)

```
Relay (WebSocket)
    ↓ raw JSON strings
EventProcessor (dedup → parse → route by kind)
    ↓ ProcessedEvent entities
Room (events, users, follows, reactions, relay_lists)
    ↓ Flow<List<FeedRow>> / Flow<UserEntity> / etc.
ViewModel (StateFlow, mapping, pagination)
    ↓ Compose state
UI (Jetpack Compose, Material 3, AMOLED black theme)
```

**What works well:**
- seenIds dedup before JSON parsing (~80% reduction)
- Composite DB indexes on events table (eliminated "Long db operation")
- One-shot vs persistent subscription lifecycle
- engagementId pattern (rootId ?: id) for correct repost reaction state
- Sequential bootstrap (connect indexers → fetch kind-3 → fetch profiles → connect global)
- FIFO pruning at 800K rows

**What's missing:**
- No knowledge of what's been fetched vs what hasn't (fake emptiness)
- Offset-based pagination in some places (fragile)
- No fetch deduplication (same profile requested 10x from 10 screens)
- Pruning can delete events still visible in UI
- No relay health tracking (dead relays stay in rotation)

---

## v2 Architecture — The Coverage Ledger

The single architectural upgrade that makes everything coherent: **track what you've fetched, not just what you have.**

### The Problem

Room tells you what events you own. It doesn't tell you:
- Have I asked relay X for this user's posts?
- Did that request complete (EOSE) or timeout?
- Is this empty profile tab genuinely empty, or have I just never fetched?
- Did I fetch posts from 2 hours ago but not from yesterday?

Without this knowledge, every "No posts yet" is a lie. Every scroll-to-load-more is a guess. Every app restart refetches everything from scratch.

### The Solution: Coverage Table

```kotlin
@Entity(tableName = "coverage")
data class CoverageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    
    // What was fetched
    val scopeType: String,      // "home", "user_posts", "thread", "profile_meta", "reactions", "search"
    val scopeKey: String,       // pubkey, note id, or hashed filter — stable key
    
    // Time window covered
    val sinceTs: Long,          // Nostr filter `since` value
    val untilTs: Long,          // Nostr filter `until` value
    
    // Relay context
    val relaySetId: String,     // "indexers", "global", "outbox:pubkey", "search"
    
    // Fetch state
    val status: String,         // "pending", "partial", "complete", "failed"
    val eoseCount: Int,         // how many relays sent EOSE
    val expectedRelays: Int,    // how many relays were queried
    
    // Pagination cursors
    val oldestSeenTs: Long,     // oldest event.created_at in this window
    val newestSeenTs: Long,     // newest event.created_at in this window
    val nextCursor: Long,       // usually oldestSeenTs - 1 for backward pagination
    
    // Freshness
    val lastAttemptAt: Long,
    val lastSuccessAt: Long,
    val staleAfterMs: Long,     // how long before this coverage is considered stale
)
```

### Fetch Contract

Every screen asks for *coverage*, not *network*:

```kotlin
// Instead of: relayPool.fetchUserPosts(pubkey)
// Do:         repository.ensureCoverage(UserPosts(pubkey, until=now, limit=50))

sealed class CoverageIntent {
    data class HomeWindow(val until: Long = now(), val limit: Int = 50) : CoverageIntent()
    data class UserPosts(val pubkey: String, val until: Long = now(), val limit: Int = 50) : CoverageIntent()
    data class Thread(val rootId: String) : CoverageIntent()
    data class Profiles(val pubkeys: List<String>) : CoverageIntent()
    data class Reactions(val eventId: String) : CoverageIntent()
}
```

The repository then:
1. Looks up coverage for that scope
2. If window is missing or stale → enqueue fetch for just the missing interval
3. Merge events idempotently into Room
4. Mark interval as complete only after EOSE from the relay set

### Gap-Free Pagination

Never page by offset. Page by time boundaries:

```
cursor = oldest_loaded_created_at - 1
next_filter = { kinds: [1], authors: [...], until: cursor, limit: 50 }
```

Overlap slightly (10-20 events or a few seconds) because relay ordering is messy and equal timestamps happen. Dedup by event ID on insert.

Mark each window:
- `complete` → EOSE received, all expected relays responded
- `partial` → fewer than `limit` events returned, no EOSE yet
- `failed` → timeout, all relays errored

### Honest UI States

Every screen reads coverage + data together:

```kotlin
data class FeedState(
    val posts: List<FeedRow>,
    val coverage: CoverageStatus,  // LOADING, PARTIAL, COMPLETE, STALE, FAILED
    val canLoadMore: Boolean,      // true if nextCursor exists and coverage != COMPLETE for older window
)
```

The UI renders:
- `LOADING` + empty → shimmer/skeleton
- `PARTIAL` + some posts → show posts + "Loading more..." at bottom
- `COMPLETE` + empty → "No posts" (genuinely empty)
- `STALE` + cached posts → show cached + refresh indicator
- `FAILED` → "Couldn't connect. Tap to retry."

No more fake emptiness. No more guessing.

### Scoped Relay Sets

Formalize what we already do informally:

```kotlin
enum class RelaySet {
    INDEXERS,       // purplepag.es, user.kindpag.es, indexer.coracle.social, antiprimal.net
    GLOBAL,         // relay.damus.io, nos.lol, nostr.mom, relay.nostr.net, relay.primal.net, relay.ditto.pub
    SEARCH,         // relay.noswhere.com, search.nos.today, antiprimal.net
    OUTBOX,         // per-user write relays from kind-10002
    USER_DECLARED,  // relays the user manually added
}
```

Coverage becomes "complete relative to relay set X" — a sane, measurable definition.

### Pruning Without Breaking

Never prune if:
- The event is inside an active coverage window
- A materialized row (feed, thread, profile) references it
- It's newer than the preserved cursor horizon for its scope

Prune only:
- Events outside all active coverage windows
- Events older than the oldest cursor in their scope
- Events with no referencing materialized rows

### Relay Health Tracking

```kotlin
@Entity(tableName = "relay_health")
data class RelayHealthEntity(
    @PrimaryKey val url: String,
    val lastConnectAttempt: Long,
    val lastSuccessfulConnect: Long,
    val lastEoseReceived: Long,
    val consecutiveFailures: Int,
    val averageLatencyMs: Long,
    val eventsReceived: Long,       // lifetime counter
    val status: String,             // "healthy", "degraded", "dead"
)
```

Dead relays get deprioritized. Healthy relays get more traffic. The user sees connection quality in relay management UI.

### Profile Fetch Deduplication

```kotlin
class ProfileFetchTracker {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    
    suspend fun ensureProfile(pubkey: String) {
        inFlight.getOrPut(pubkey) {
            CompletableDeferred<Unit>().also { deferred ->
                scope.launch {
                    relayPool.fetchProfiles(listOf(pubkey))
                    // Wait for Room to have the profile or timeout
                    withTimeoutOrNull(5_000) {
                        userDao.userFlow(pubkey).filterNotNull().first()
                    }
                    deferred.complete(Unit)
                    inFlight.remove(pubkey)
                }
            }
        }.await()
    }
}
```

10 screens requesting the same profile → 1 relay request. The rest await the same deferred.

---

## Implementation Phases

### Phase 1 — Immediate (no schema changes)
- Honest UI states: shimmer while fetching, "Couldn't connect" on failure
- Cursor-based pagination everywhere (replace offset-based)
- Profile fetch deduplication (in-memory tracker)
- Safe pruning (check references before delete)

### Phase 2 — Coverage Ledger (Room migration)
- Add `coverage` table
- Refactor repositories to use CoverageIntent pattern
- Wire UI states to coverage status
- Add relay health table

### Phase 3 — Materialized Views
- Precomputed feed rows (denormalized for instant rendering)
- Thread materialization (tree built on insert, not on read)
- Profile completeness tracking

### Phase 4 — Intelligence
- Relay scoring (route requests to fastest/most-complete relays)
- Predictive prefetch (prefetch profiles for visible feed items)
- Adaptive pruning (keep more data for active users, less for dormant)
- NIP-85 Web of Trust integration via antiprimal.net

---

## The North Star

When a user opens unSilence:
- **0ms:** Cached feed renders from Room. Instantly.
- **<500ms:** Fresh events start appearing (relay connections restored, persistent subs active).
- **<2s:** All visible profiles resolved, avatars loaded.
- **Scroll:** Infinite, gap-free, no "Loading..." spinners mid-feed.
- **Profile tap:** Instant render from cache, background refresh if stale.
- **Thread tap:** Full tree renders from Room, missing branches fetched silently.
- **Background → foreground:** Feed picks up exactly where it left off. No re-fetch. No stale data.

That's the bar. Every architectural decision serves this experience. If a feature makes the app slower, it doesn't ship. If an abstraction adds latency, it gets flattened. Speed is the product.

---

*This document is the architectural north star for unSilence v2. It should be updated as we learn from real-world usage and as the Nostr protocol evolves.*
