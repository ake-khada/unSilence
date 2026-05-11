# Signal Cascade Audit — MemoryEventStore

**Date:** 2026-05-11
**Scope:** Full dependency map of every MES reactive signal, its triggers, consuming flows, cross-dependencies, noise profile, and dangerous edges a redesign must preserve.

---

## 1. Signal Inventory

### 1.1 Signal Declarations (MemoryEventStore.kt lines 232–254)

| Signal | Type | Initial | Public Accessor |
|--------|------|---------|-----------------|
| `_feedSignal` | `MutableStateFlow<Long>` | `0L` | `feedSignalFlow` (StateFlow) |
| `_profileSignal` | `MutableStateFlow<Long>` | `0L` | `profileSignalFlow` (StateFlow) |
| `_statsSignal` | `MutableStateFlow<Long>` | `0L` | `statsSignalFlow` (StateFlow) |
| `_followsSignal` | `MutableStateFlow<Long>` | `0L` | *(no public accessor)* |
| `_actionSignal` | `MutableStateFlow<Long>` | `0L` | `actionSignalFlow` (StateFlow) |
| `_relayConfigSignal` | `MutableStateFlow<Long>` | `0L` | *(no public accessor)* |
| `_relaySetSignal` | `MutableStateFlow<Long>` | `0L` | *(no public accessor)* |
| `_trustScoreSignal` | `MutableStateFlow<Long>` | `0L` | *(no public accessor)* |
| `_relayMonitorSignal` | `MutableStateFlow<Long>` | `0L` | *(no public accessor)* |
| `_snapshotRestoredSignal` | `MutableStateFlow<Long>` | `0L` | `snapshotRestoredFlow` (StateFlow) |

### 1.2 Signal → Trigger Kind Mapping

#### Via `markKindDirty()` (line 368–376)

| Event Kind | Signal(s) Bumped |
|------------|------------------|
| 0 (profile) | `_profileSignal` |
| 1 (note) | `_feedSignal` |
| 3 (contact list) | `_followsSignal` |
| 6 (repost) | `_feedSignal`, `_actionSignal` |
| 7 (reaction) | `_statsSignal`, `_actionSignal` |
| 9734 (zap request) | `_statsSignal`, `_actionSignal` |
| 9735 (zap receipt) | `_statsSignal` |
| 30023 (article) | `_feedSignal` |

#### Via Kind Handlers (direct-path or dirty flag)

| Event Kind | Handler | Signal Bumped |
|------------|---------|---------------|
| 10002 (relay list) | `handleRelayList` | `_relayConfigSignal` (if changed) |
| 10006 (blocked relays) | `handleBlocked` | `_relayConfigSignal` (if changed) |
| 10007 (search relays) | `handleSearchRelays` | `_relayConfigSignal` (if changed) |
| 10012 (favorites) | `handleFavorites` | `_relayConfigSignal` (if changed) |
| 30002 (relay set) | `handleRelaySetMaterialized` | `_relaySetSignal` (if changed) |
| 30166 (relay monitor) | `handleRelayMonitor` | `_relayMonitorSignal` |
| 30385 (trust score) | `handleTrustScore` | `_trustScoreSignal` |

#### Via Non-Insert Mutation APIs

| Caller | Signal(s) Bumped |
|--------|------------------|
| `invalidateFeedRowCache(eventIds)` | `_statsSignal` |
| `incrementZapStats(eventId, sats)` | *(none — callers manually bump via invalidateFeedRowCache or rely on kind-9735 arrival)* |
| `updateFollows(pubkey, …)` | `_followsSignal` (if changed) |
| `upsertRelaySet(set)` | `_relaySetSignal` (if changed) |
| `deleteRelaySet(…)` | `_relaySetSignal` |
| `addRelayToSet(…)` | `_relaySetSignal` |
| `removeRelayFromSet(…)` | `_relaySetSignal` |
| `addReadWriteRelay(…)` | `_relayConfigSignal` |
| `removeReadWriteRelay(…)` | `_relayConfigSignal` |
| `updateRelayMarker(…)` | `_relayConfigSignal` |
| `addBlockedRelay(…)` | `_relayConfigSignal` |
| `removeBlockedRelay(…)` | `_relayConfigSignal` |
| `addSearchRelay(…)` | `_relayConfigSignal` |
| `removeSearchRelay(…)` | `_relayConfigSignal` |
| `addFavoriteRelay(…)` | `_relayConfigSignal` |
| `removeFavoriteRelay(…)` | `_relayConfigSignal` |
| `removeFavoriteBySetRef(…)` | `_relayConfigSignal` |
| `clearUserState()` | `_followsSignal`, `_actionSignal`, `_relayConfigSignal`, `_relaySetSignal`, `_trustScoreSignal`, `_relayMonitorSignal` |

#### Via Snapshot Restore (both V2 and V3)

Follows signal is fired **early** (before events parse starts) when follows section is non-empty.
At end of restore: `_feedSignal`, `_profileSignal`, `_statsSignal`, `_followsSignal`, `_trustScoreSignal`, `_relayMonitorSignal`, `_snapshotRestoredSignal` all bumped once.

Note: `_relayConfigSignal`, `_relaySetSignal`, and `_actionSignal` are NOT bumped at snapshot restore end. (Relay config/sets/actions are not persisted in snapshot; actions use actor indexes which aren't snapshot-persisted.)

### 1.3 Batch Coalescing

`insertBatch(events)` accumulates dirty flags via `InsertDirty` across all events, then calls `flushDirty()` once. This means a 300-event batch with kinds 1, 7, and 0 bumps exactly 3 signals (`_feedSignal`, `_statsSignal`, `_profileSignal`) instead of 300.

Control-plane kinds (10002, 10006, 10007, 10012, 30002, 30166, 30385) use the same `InsertDirty` accumulator via their handlers accepting a `dirty: InsertDirty?` parameter.

---

## 2. Consuming Flows

### 2.1 Internal MES Flows (defined in MemoryEventStore.kt)

| Flow | Signals Observed | Query/Scan | Lines |
|------|-----------------|------------|-------|
| `userFeedFlow(pubkey, …)` | `combine(_feedSignal, _statsSignal, _profileSignal)` + `sample(200)` | `userFeedEvents(pubkey, …).map { toFeedRow(it) }` | 1497–1506 |
| `followsFlow(pubkey)` | `_followsSignal` | `getFollows(pubkey) ?: emptySet()` | 1508–1510 |
| `profileFlow(pubkey)` | `_profileSignal` | `getProfile(pubkey)` | 1512–1514 |
| `threadFlow(rootId)` | `_feedSignal` | `collectThread(rootId)` — full fixpoint DFS scan of eventsById | 1517–1519 |
| `threadFeedRowFlow(rootId)` | `combine(_feedSignal, _statsSignal, _profileSignal)` | `collectThread(rootId).map { toFeedRow(it) }` | 1522–1525 |
| `searchNotesFlow(query)` | `_feedSignal` | Full scan of eventsById for kind 1/30023 substring match | 1443–1447 |
| `searchUsersFlow(query)` | `_profileSignal` | Full scan of profilesByPubkey for name/displayName/about match | 1459–1463 |
| `eventEntityFlow(eventId)` | `_feedSignal` | `getEventEntity(eventId)` — single ConcurrentHashMap lookup | 1593–1594 |
| `userEntityFlow(pubkey)` | `_profileSignal` | `getUserEntity(pubkey)` — single ConcurrentHashMap lookup | 1597–1598 |
| `statsFlow(eventId)` | `combine(_feedSignal, _statsSignal, _actionSignal)` | O(1) lookups: `replyCount`, `repostCount`, `reactionCount`, `zapStats` | 1610–1623 |
| `notificationsFlow(…)` | `combine(_feedSignal, _statsSignal)` | `getNotifications()` — walks idsByKind for kinds 1/6/7/9735, filters #p tag | 1960–1964 |
| `reactedEventIdsFlow(pubkey)` | `_actionSignal` | `reactedTargetsByActor[pubkey]?.toSet()` — single map lookup | 1394–1397 |
| `repostedEventIdsFlow(pubkey)` | `_actionSignal` | `repostedTargetsByActor[pubkey]?.toSet()` — single map lookup | 1400–1403 |
| `zappedEventIdsFlow(pubkey)` | `_actionSignal` | `zappedTargetsByActor[pubkey]?.toSet()` — single map lookup | 1406–1409 |
| `allRelayListsFlow()` | `_relayConfigSignal` | `HashMap(relayListsByPubkey)` — full snapshot copy | 1647–1651 |
| `blockedRelayUrlsFlow(pubkey)` | `_relayConfigSignal` | `blockedRelaysByPubkey[pubkey]` — single lookup | 1669–1673 |
| `searchRelayUrlsFlow(pubkey)` | `_relayConfigSignal` | `searchRelaysByPubkey[pubkey]` — single lookup | 1675–1679 |
| `readWriteRelayConfigsFlow(pubkey)` | `_relayConfigSignal` | `readWriteRelayConfigsByPubkey[pubkey]` — single lookup | 1681–1685 |
| `favoriteRelayConfigsFlow(pubkey)` | `_relayConfigSignal` | `favoritesByPubkey[pubkey]` — single lookup | 1687–1691 |
| `trustScoresFlow()` | `_trustScoreSignal` | `HashMap(trustScoresByUrl)` — full snapshot copy | 1698–1702 |
| `relayMonitorsFlow()` | `_relayMonitorSignal` | `HashMap(relayMonitorsByUrl)` — full snapshot copy | 1711–1715 |
| `relayHealthFlow()` | `combine(_trustScoreSignal, _relayMonitorSignal)` | Full merge of trustScoresByUrl + relayMonitorsByUrl | 1731–1748 |
| `getAllSetsFlow(ownerPubkey)` | `_relaySetSignal` | `relaySetsByCoordinate.values.filter { ownerPubkey }` — full scan | 1758–1762 |
| `getSetMembersFlow(ownerPubkey, dTag)` | `_relaySetSignal` | `relaySetsByCoordinate["$ownerPubkey:$dTag"]?.members` — single lookup | 1764–1768 |

### 2.2 External Consumers (VMs, Repositories, Composables)

| Consumer | Flow Used | Location |
|----------|-----------|----------|
| **FeedViewModel** | `memoryEventStore.statsFlow(eventId)` via `statsCache.getOrPut` | FeedViewModel.kt:289–293 |
| **FeedViewModel** | `memoryEventStore.allRelayListsFlow().map { it.size }` | FeedViewModel.kt:201 (→ relayMetadataVersion) |
| **FeedViewModel** | `memoryEventStore.followsFlow(ownPubkey).map { it.size }` | FeedViewModel.kt:202 (→ relayMetadataVersion), :561 (→ hasFollows) |
| **FeedViewModel** | `memoryEventStore.readWriteRelayConfigsFlow(ownPubkey)` | FeedViewModel.kt:544 (cold-start wait) |
| **FeedViewModel** | `memoryEventStore.getAllSetsFlow(pk)` → `userSetsFlow` | FeedViewModel.kt:299 |
| **FeedViewModel** | `memoryEventStore.snapshotRestoredFlow` | FeedViewModel.kt:598 (one-shot) |
| **ProfileViewModel** | `memoryEventStore.userEntityFlow(pubkeyHex)` → `userFlow` | ProfileViewModel.kt:69 |
| **ProfileViewModel** | `memoryEventStore.userEntityFlow(pubkey)` via `profileCache.getOrPut` | ProfileViewModel.kt:149 |
| **ProfileViewModel** | `memoryEventStore.statsFlow(eventId)` via `statsCache.getOrPut` | ProfileViewModel.kt:158 |
| **ProfileViewModel** | `memoryEventStore.followsFlow(pk).map { it.size }` → `followingCount` | ProfileViewModel.kt:164 |
| **UserProfileViewModel** | `memoryEventStore.userEntityFlow(it)` → `userFlow` | UserProfileViewModel.kt:76 |
| **UserProfileViewModel** | `memoryEventStore.userEntityFlow(pubkey)` via `profileCache.getOrPut` | UserProfileViewModel.kt:119 |
| **UserProfileViewModel** | `memoryEventStore.statsFlow(eventId)` via `statsCache.getOrPut` | UserProfileViewModel.kt:128 |
| **UserProfileViewModel** | `memoryEventStore.followsFlow(myPubkey).map { target in it }` → `isFollowing` | UserProfileViewModel.kt:145 |
| **ThreadViewModel** | `memoryEventStore.threadFeedRowFlow(id)` | ThreadViewModel.kt:73 |
| **NotificationsViewModel** | `memoryEventStore.notificationsFlow(pubkey, 100, followedOnly)` | NotificationsViewModel.kt:94 |
| **NoteActionsViewModel** | `memoryEventStore.reactedEventIdsFlow(pk)` → `reactedEventIds` | NoteActionsViewModel.kt:89 |
| **NoteActionsViewModel** | `memoryEventStore.repostedEventIdsFlow(pk)` → `repostedEventIds` | NoteActionsViewModel.kt:99 |
| **NoteActionsViewModel** | `memoryEventStore.zappedEventIdsFlow(pk)` → `zappedEventIds` | NoteActionsViewModel.kt:143 |
| **NoteActionsViewModel** | `memoryEventStore.userEntityFlow(pubkey)` (one-shot) | NoteActionsViewModel.kt:253 |
| **NoteActionsViewModel** | `memoryEventStore.eventEntityFlow(eventId)` (one-shot) | NoteActionsViewModel.kt:300 |
| **SearchViewModel** | `memoryEventStore.feedSignalFlow.map { feedRowsByIds(ids) }` | SearchViewModel.kt:124 |
| **SearchViewModel** | `memoryEventStore.searchNotesFlow(query)` | SearchViewModel.kt:131 |
| **SearchViewModel** | `memoryEventStore.searchUsersFlow(query)` | SearchViewModel.kt:133 |
| **UserRepository** | `memoryEventStore.userEntityFlow(pubkey)` → `userFlow(pubkey)` | UserRepository.kt:16 |
| **AppBootstrapper** | `memoryEventStore.followsFlow(pubkeyHex)` (one-shot await) | AppBootstrapper.kt:168, 174, 181 |
| **AppBootstrapper** | `memoryEventStore.readWriteRelayConfigsFlow(pubkeyHex)` (one-shot await) | AppBootstrapper.kt:199, 207, 217, 221 |
| **RelayManagementViewModel** | `memoryEventStore.readWriteRelayConfigsFlow(it)` | RelayManagementViewModel.kt:42 |
| **RelayManagementViewModel** | `memoryEventStore.blockedRelayUrlsFlow(it)` | RelayManagementViewModel.kt:46 |
| **RelayManagementViewModel** | `memoryEventStore.searchRelayUrlsFlow(it)` | RelayManagementViewModel.kt:50 |
| **RelayManagementViewModel** | `memoryEventStore.favoriteRelayConfigsFlow(it)` | RelayManagementViewModel.kt:54 |
| **RelayManagementViewModel** | `memoryEventStore.getAllSetsFlow(it)` | RelayManagementViewModel.kt:61 |
| **RelayManagementViewModel** | `memoryEventStore.relayHealthFlow()` | RelayManagementViewModel.kt:65 |
| **RelayManagementViewModel** | `memoryEventStore.getSetMembersFlow(pk, dTag)` | RelayManagementViewModel.kt:227 |

---

## 3. Cross-Reference / Dependency Analysis

### 3.1 statsFlow — The Critical Cross-Dependency

`statsFlow(eventId)` at line 1610 combines THREE signals:

```kotlin
combine(_feedSignal, _statsSignal, _actionSignal) { _, _, _ -> }
```

**Why each signal is required:**

| Signal | Data It Carries For This Flow | Trigger Path |
|--------|-------------------------------|--------------|
| `_feedSignal` | **Reply count** — `handleNote()` increments `replyCounts[targetId]` when a kind-1 event arrives. kind-1 bumps `_feedSignal` (via `markKindDirty`), NOT `_statsSignal`. | kind-1 → `markKindDirty` → `d.feed = true` |
| `_statsSignal` | **Reaction count** (kind-7 → `handleReaction` → `reactionCounts`), **Repost count** (kind-6 → `handleRepost` → `repostCounts`), **Zap count/sats** (kind-9735 → `handleZapReceipt` → `zapStatsByEventId`) | kind-7/9734/9735 → `markKindDirty` → `d.stats = true` |
| `_actionSignal` | **No data change for statsFlow itself** — but `incrementZapStats` + `invalidateFeedRowCache` path bumps `_statsSignal` separately. `_actionSignal` catches the optimistic insert path (NoteActionsVM.react/repost/zap calls `MES.insert(signedEvent)` which triggers `markKindDirty` for kind 7/6/9734). However, `repostCount` is already updated by `handleRepost` and `reactionCount` by `handleReaction` during insert — both bump `_statsSignal` indirectly via `markKindDirty`. **Wait — kind 7 bumps `_statsSignal`, and kind 6 bumps `_feedSignal`.** So `_actionSignal` here is **redundant** for the optimistic path — the data changes are already covered by `_feedSignal` (repost count via kind-6) and `_statsSignal` (reaction count via kind-7). |

**CRITICAL FINDING — _feedSignal in statsFlow:**

`_feedSignal` is REQUIRED because:
- kind-1 events with `replyToId != null` call `handleNote()` which increments `replyCounts[targetId]` and `statsUpdatedAt[targetId]`
- kind-1 ONLY sets `d.feed = true` in `markKindDirty`, NEVER `d.stats = true`
- Therefore, a reply arriving will update `replyCounts` but ONLY bump `_feedSignal`
- If `_feedSignal` were removed from `statsFlow`, **reply counts would never update in the UI**

**SUBTLETY — kind-6 in statsFlow:**

Kind-6 reposts bump `_feedSignal` AND `_actionSignal` (but NOT `_statsSignal`):
- `handleRepost()` increments `repostCounts[targetId]` and `statsUpdatedAt[targetId]`
- `markKindDirty(6)` sets `d.feed = true` and `d.action = true`
- So repost count changes reach `statsFlow` via `_feedSignal` — removing it would break repost counts too

**FINDING — _actionSignal in statsFlow may be partially redundant:**

- Kind-7 → bumps both `_statsSignal` and `_actionSignal` — stats covers it
- Kind-6 → bumps both `_feedSignal` and `_actionSignal` — feed covers it
- Kind-9734 → bumps both `_statsSignal` and `_actionSignal` — stats covers it
- The only path where `_actionSignal` could matter is `incrementZapStats()`, which does NOT bump any signal itself. But `invalidateFeedRowCache()` bumps `_statsSignal`.
- However, removing `_actionSignal` from `statsFlow` is only safe IF the above analysis is exhaustive. Since `_actionSignal` carries `clearUserState()` bumps (logout), keeping it is defensive.

### 3.2 notificationsFlow — Correct Dual-Signal

```kotlin
combine(_feedSignal, _statsSignal) { _, _ -> }
```

Notification-eligible kinds and their signals:
| Kind | Signal |
|------|--------|
| 1 (reply/mention) | `_feedSignal` |
| 6 (repost) | `_feedSignal` |
| 7 (reaction) | `_statsSignal` |
| 9735 (zap receipt) | `_statsSignal` |

Both signals are required — each carries half the notification types.

### 3.3 userFeedFlow — Triple-Signal for FeedRow Hydration

```kotlin
combine(_feedSignal, _statsSignal, _profileSignal) { _, _, _ -> }
```

Returns `List<FeedRow>` where each FeedRow contains:
- Event data → triggered by `_feedSignal` (new kind-1/6/30023 events)
- Profile fields (authorName, authorPicture, etc.) → triggered by `_profileSignal` (kind-0)
- Stats (reactionCount, zapTotalSats, etc.) → triggered by `_statsSignal` (kind-7/9735)

All three are structurally required since `toFeedRow()` reads all three data domains.

### 3.4 threadFeedRowFlow — Same as userFeedFlow

```kotlin
combine(_feedSignal, _statsSignal, _profileSignal) { _, _, _ -> }
```

Same reasoning: thread replies are kind-1 (`_feedSignal`), rendered with profile data (`_profileSignal`) and engagement counts (`_statsSignal`).

### 3.5 allRelayListsFlow Cascade

`allRelayListsFlow()` → `_relayConfigSignal` → consumed by `FeedViewModel.relayMetadataVersion` via `combine(allRelayListsFlow().map { it.size }, followsFlow().map { it.size })`.

This means kind-10006 (blocked relays) or kind-10007 (search relays) or kind-10012 (favorites) bump `_relayConfigSignal`, which wakes `allRelayListsFlow()`, which wakes `relayMetadataVersion`, potentially triggering a feed resubscribe — even though those kinds don't affect relay lists (kind-10002). The `distinctUntilChanged` on `allRelayListsFlow` catches this (map size unchanged), but the intermediate scan (`HashMap(relayListsByPubkey)`) still runs.

---

## 4. Noise Candidates

### 4.1 HIGH NOISE — statsFlow receives _feedSignal for ALL kind-1 events

**Impact:** Every kind-1 note (whether a reply or a root post) bumps `_feedSignal`. statsFlow for EVERY observed event ID re-evaluates. With 100 visible cards each having a statsFlow, and 50 new notes arriving in a batch, that's 100 re-evaluations per batch — even though at most a few of those notes are replies TO visible events.

**Required data:** `replyCount(eventId)` uses `replyCounts[eventId]`. Only kind-1 events with `replyToId == eventId` or `rootId == eventId` increment this counter. The vast majority of kind-1 events are NOT replies to any visible event.

**Mitigation via distinctUntilChanged:** Each statsFlow instance uses `.distinctUntilChanged()` on the `EventStats` data class. So the Compose recomposition is suppressed for the 99% of events whose counts didn't change. But the O(1) map lookups (replyCount, repostCount, reactionCount, zapStats) still execute for every statsFlow instance on every `_feedSignal` bump.

**Noise magnitude:** ~100 statsFlow instances × 5 map lookups each = 500 lookups per kind-1 arrival. With batch coalescing, a 100-event batch triggers this once (not 100 times), so the real cost is 500 lookups per batch flush — cheap in absolute terms, but avoidable.

### 4.2 HIGH NOISE — statsFlow receives _statsSignal for ALL kind-7/9735 events

**Impact:** A kind-7 reaction for event X bumps `_statsSignal`. ALL active statsFlow instances re-evaluate. Same as 4.1 — distinctUntilChanged suppresses emission, but the scan runs.

**Noise magnitude:** Same as 4.1, but kind-7 events are typically higher volume than kind-1 in an active feed.

### 4.3 MEDIUM NOISE — notificationsFlow receives _feedSignal for ALL kind-1 events

**Impact:** Every kind-1 note (most of which don't mention the user) triggers a full notification scan: walks `idsByKind` for 4 kinds, sorts all candidates, filters #p tags. This is O(N) where N = total notification-eligible events.

**Mitigation:** `distinctUntilChanged()` on the output list prevents emission if no new notifications appeared. But the scan itself is expensive.

### 4.4 MEDIUM NOISE — userFeedFlow triple-signal

**Impact:** A kind-7 reaction (bumping `_statsSignal`) re-scans and re-maps all user feed events even though the user feed only contains kind-1/6/30023 events. The `toFeedRow()` cache mitigates the mapping cost but the `userFeedEvents()` scan still runs.

### 4.5 MEDIUM NOISE — threadFeedRowFlow triple-signal

**Impact:** Same as 4.4 but for thread view. A kind-0 profile update for an unrelated user triggers a full thread fixpoint DFS + FeedRow mapping.

### 4.6 LOW NOISE — allRelayListsFlow on _relayConfigSignal

**Impact:** kind-10006/10007/10012 events bump `_relayConfigSignal`, triggering `HashMap(relayListsByPubkey)` snapshot. The copy is wasted since relay lists (kind-10002 data) didn't change. `distinctUntilChanged` catches the output, but the HashMap copy is O(N) where N = number of relay lists.

### 4.7 LOW NOISE — searchNotesFlow on _feedSignal

**Impact:** During active search, every kind-1/6/30023 arrival triggers a full substring scan of all events. Mitigated by the fact that search is only active when the user is on the search screen.

### 4.8 NEGLIGIBLE — _actionSignal in statsFlow

**Impact:** `_actionSignal` bumps on kind-6/7/9734 inserts. These are already covered by `_feedSignal` (kind-6) and `_statsSignal` (kind-7/9734). The signal is redundant for data delivery but adds one extra wake-up per batch.

---

## 5. Dangerous Dependencies a Redesign MUST Preserve

### D1: _feedSignal → statsFlow.replyCount (CRITICAL)

**Do not remove `_feedSignal` from `statsFlow`.** Kind-1 replies increment `replyCounts[targetId]` but ONLY bump `_feedSignal`. There is no `_statsSignal` bump for kind-1 events. If `_feedSignal` is removed from `statsFlow`, reply counts will NEVER update in the UI.

A targeted-invalidation redesign must ensure that a kind-1 reply arriving causes the PARENT event's statsFlow to re-evaluate.

### D2: _feedSignal → statsFlow.repostCount (CRITICAL)

**Do not remove `_feedSignal` from `statsFlow`.** Kind-6 reposts increment `repostCounts[targetId]` but bump `_feedSignal` (not `_statsSignal`). Removing `_feedSignal` would break repost count updates.

### D3: _feedSignal → notificationsFlow for kind-1/6 (CRITICAL)

Notifications include replies (kind-1) and reposts (kind-6). Both are carried by `_feedSignal`. If `_feedSignal` is removed from `notificationsFlow`, reply and repost notifications would never appear.

### D4: handleNote() updates statsUpdatedAt but does NOT bump _statsSignal (SUBTLE)

`handleNote()` (kind-1 handler, line 506) writes to `replyCounts` and `statsUpdatedAt` — these are the SAME maps that `statsFlow` reads. But the signal that wakes `statsFlow` for this data is `_feedSignal`, not `_statsSignal`. A redesign that introduces per-event-ID invalidation must route the kind-1 handler's targetId into the parent's statsFlow somehow.

### D5: handleRepost() updates repostCounts but bumps only _feedSignal and _actionSignal (SUBTLE)

Same pattern as D4. `handleRepost()` writes `repostCounts[targetId]` and `statsUpdatedAt[targetId]`, but `markKindDirty(6)` only sets `d.feed` and `d.action`. The `_statsSignal` is NOT bumped for kind-6.

### D6: invalidateFeedRowCache bumps _statsSignal (RELAY PATH)

`RelayPool.handleEose()` calls `invalidateFeedRowCache(eventIds)` after one-shot engagement subs complete. This is the ONLY path that bumps `_statsSignal` without going through `insertCore` → `markKindDirty`. A redesign must preserve this path — it's how engagement counts fetched via one-shot subs surface in the UI.

### D7: incrementZapStats does NOT bump any signal

`NoteActionsVM.zap()` calls `incrementZapStats()` for optimistic UI, but this method does NOT bump `_statsSignal`. The optimistic zap count update reaches statsFlow only because `MES.insert(signedEvent)` (the kind-9734 insert on line 203) bumps `_statsSignal` via `markKindDirty`. If the insert and incrementZapStats are reordered, the zap count may read stale.

### D8: feedRowCache uses per-author/per-event keys (GOOD)

`toFeedRow()` uses `profileUpdatedAt[event.pubkey]` and `statsUpdatedAt[statsId]` as cache keys (not global signal values). This means a profile update for author X only invalidates X's rows. A redesign should preserve this property.

### D9: Snapshot restore bumps all signals at end (REQUIRED)

After restore, ALL downstream flows must re-evaluate against the newly populated data. The end-of-restore bump is the only mechanism for this. A per-event redesign must handle the snapshot-restore bulk-load case.

### D10: clearUserState bumps signals without any insert

Logout clears user-specific data and bumps signals so flows re-emit empty states. A per-event invalidation model must still support this "clear everything" broadcast.

### D11: kind-20/21 (pictures/videos) are NOT in markKindDirty

Kinds 20 and 21 are NOT handled by `markKindDirty()` — they don't match any case in the `when` block. This means picture/video events inserted via `insertBatch` will NOT bump any signal. They reach the feed via TimelineConsumer's Subscription tap callbacks (which bypass MES signals), but any MES-signal-driven flow (like `searchNotesFlow`) won't see them until the next signal bump from another kind.

---

## 6. Redesign Implications

### 6.1 Per-Event-ID Targeted Invalidation

The ideal redesign replaces global signals with per-event-ID invalidation:
- kind-7 reaction for event X → invalidate ONLY statsFlow(X)
- kind-1 reply to event Y → invalidate ONLY statsFlow(Y) AND feedSignal (for feed/thread/notification flows)

**Implementation sketch:**
- Replace `_statsSignal` with a `MutableSharedFlow<Set<String>>` emitting the set of affected event IDs
- `statsFlow(eventId)` filters: `invalidatedIds.filter { eventId in it }`
- `handleNote()` emits `setOf(targetId)` (the parent being replied to)
- `handleReaction()` emits `setOf(targetId)`
- `handleRepost()` emits `setOf(targetId)`
- `handleZapReceipt()` emits `setOf(targetId)`

**Risks:**
- SharedFlow with no replay: late collectors miss invalidations → stale counts. Use ConflatedBroadcastChannel or StateFlow with version per eventId.
- Snapshot restore: must emit ALL event IDs or fall back to a broadcast signal.
- `invalidateFeedRowCache(eventIds)` already provides event IDs — natural fit.

### 6.2 Separate _replySignal from _feedSignal

Since the noise in statsFlow comes from `_feedSignal` carrying BOTH "new feed content" and "reply count changed", splitting these could help:
- `_feedSignal` → bumped when new feed-visible events arrive (kind 1/6/20/21/30023)
- `_replyCountSignal` (or per-event) → bumped when `replyCounts[targetId]` changes

### 6.3 What NOT to Change

- `userFeedFlow` and `threadFeedRowFlow` legitimately need all three signals (feed+stats+profile) because `toFeedRow()` reads all three domains
- `notificationsFlow` legitimately needs `_feedSignal` + `_statsSignal`
- `followsFlow` is clean — single signal, no noise
- `_relayConfigSignal`, `_relaySetSignal`, `_trustScoreSignal`, `_relayMonitorSignal` are all well-scoped and have low noise
