# Relay Settings Unification — Design Spec

## Goal

Unify the two disconnected relay set systems (local `RelaySetEntity` vs NIP-51 `NostrRelaySetEntity`), expose hardcoded indexer/search relay defaults in the settings UI, make the feed picker and relay settings share a single source of truth, fix URL normalization crashes, and structurally prepare for NIP-42 AUTH.

## Architecture

The local `relay_sets` table is eliminated. Custom feed relay sets are stored exclusively as NIP-51 kind 30002 events in `nostr_relay_sets` / `nostr_relay_set_members`. The "Global" and "Following" feeds remain as `FeedType` sealed class variants (no DB rows). Hardcoded indexer relays are seeded into `relay_configs` as kind 99 (local-only, never published).

### Data Flow

```
User's kind 10002 (read relays) → Global feed relay list (fallback: hardcoded defaults)
User's kind 30002 (relay sets)  → Feed picker spinner + Relay Settings "Relay Sets" section
User's kind 10007 (search)      → SearchViewModel relay list + Relay Settings "Search" tab
Kind 99 (local indexer relays)  → AppBootstrapper + Relay Settings "Index" tab
Kind 99 (local indexer relays)  → RelayManagementViewModel publish targets (replaces hardcoded lists)
```

## Data Model Changes

### Eliminated

- **`RelaySetEntity`** table (`relay_sets`) — dropped via Room migration
- **`RelaySetDao`** — deleted
- **`RelaySetRepository`** — deleted
- **`CreateRelaySetViewModel`** — delegates to `RelayManagementViewModel.createRelaySet()`

### Modified

- **`RelayConfigEntity`** gains kind 99 for local-only indexer relays (never published)
- **`RelayConfigDao`** adds `getIndexerRelays()` flow and `getIndexerRelayUrls()` snapshot query
- **`FeedType.RelaySet`** changes from `id: String` (UUID) to `dTag: String` (NIP-51 d-tag). `FeedType` is ephemeral state in `FeedViewModel._feedType` (a `MutableStateFlow`, not persisted). On app restart, the feed defaults to Following (if follows exist) or Global — no migration of selected feed type needed.
- **`RelayPool`** constructor gains `SigningManager` dependency (wired for future NIP-42)
- **`AppBootstrapper`** uses kind 10002 read relays for global feed connection (falls back to hardcoded `GLOBAL_RELAY_URLS` until kind 10002 data arrives). Gains `NostrRelaySetDao` Hilt dependency. Teardown gains `nostrRelaySetDao.clearAll()` to clear relay sets on logout.
- **`FeedViewModel`** reads from `NostrRelaySetDao` instead of `RelaySetDao`; Global feed uses kind 10002 read relays from `RelayConfigDao`. Gains `KeyManager` and `NostrRelaySetDao` dependencies. For `FeedType.RelaySet`, resolves relay URLs via `nostrRelaySetDao.getSetMembersSnapshot(dTag, ownerPubkey)`.
- **`ThreadViewModel`** — currently depends on `RelaySetRepository`. Must be updated to use `RelayConfigDao` (kind 10002 read relays) for relay URL resolution instead.
- **`NostrRelaySetDao.getAllSets()`** — must be scoped by `ownerPubkey` parameter so the feed picker only shows the user's own sets, not sets fetched from other users' profiles.

### Indexer URL Consolidation

The hardcoded `INDEXER_RELAY_URLS` lists in `AppBootstrapper` (line 27) and duplicated in `RelayManagementViewModel.publishChanges()` (line 283) and `publishRelaySet()` (line 318) are replaced by reading from `RelayConfigDao.getIndexerRelayUrls()` (kind 99). This means:
- `AppBootstrapper` reads kind 99 relays for bootstrap (falls back to hardcoded defaults on truly first launch before seeding)
- `RelayManagementViewModel.publishChanges()` and `publishRelaySet()` read kind 99 relays as publish targets instead of hardcoding
- `UserProfileViewModel` (companion `INDEXER_RELAY_URLS`, used for publishing follow events) also reads from kind 99
- The single hardcoded default list exists only in the seeding logic

### Seeding

Seeding runs in `AppBootstrapper.bootstrap()` (not in the Room migration) as a one-time check:
- Kind 99 (indexer): if no kind 99 rows exist, insert `purplepag.es`, `user.kindpag.es`, `indexer.coracle.social`, `antiprimal.net`
- Kind 10007 (search): if no kind 10007 rows exist after bootstrap fetch completes, insert `relay.nostr.band`, `search.nos.today`

The Room migration only handles structural changes (drop `relay_sets` table, migrate existing data). Seeding is separated because it depends on runtime state (whether network data has arrived).

### URL Normalization

`normalizeRelayUrl()` moves from `RelayManagementViewModel.Companion` to a top-level utility function in `com.unsilence.app.data.relay.RelayUrlUtil.kt`. Applied at every URL entry point:
- `CreateRelaySetScreen` — normalize on add-to-list (so the displayed URL is clean before submission)
- `AddRelayInput` composable in relay settings — normalize on add
- `RelayPool.connect()` — safety net normalization
- `RelayManagementViewModel` — already normalizes, switches to shared utility

Rules: trim → strip http(s):// → prepend wss:// if missing → validate domain has dot → strip trailing slash.

### d-tag Collision Handling

`RelayManagementViewModel.createRelaySet()` derives d-tags as `name.lowercase().replace(Regex("[^a-z0-9-]"), "-")`. To handle collisions: if a set with the derived d-tag already exists (checked via `nostrRelaySetDao`), append a numeric suffix (`-2`, `-3`, etc.) until unique.

## UI Changes

### Relay Management Screen (Redesigned)

**Top: Three horizontal tabs** (segmented control style)

| Tab | Kind | Publishable | Content |
|-----|------|-------------|---------|
| Inbox/Outbox | 10002 | Yes | Read/Write relays with R/W marker chips |
| Index | 99 | No (local) | Indexer relays for metadata. Warning when empty |
| Search | 10007 | Yes | Search relays for NIP-50 |

Each tab shows a compact relay list with inline add (text field + add button) and delete (trash icon). Relay URLs displayed without `wss://` prefix.

**Below tabs: Three collapsible sections**

1. **Relay Sets** (kind 30002) — expandable to see members. "+ New" button creates a set. Each set has delete. These are the same sets that appear in the feed picker.
2. **Favorites** (kind 10012) — individual relay URLs and set references. Add relay input + set picker.
3. **Blocked** (kind 10006) — simple URL list with add/delete.

### Feed Picker (Redesigned)

**Replaces the dropdown menu** with a bottom sheet spinner:

- **Trigger:** Tapping the feed name (e.g., "Global ▾") in the top bar
- **Bottom sheet:** Dark background (Black / `#0A0A0A`)
- **Spinner:** `LazyColumn` with `SnapFlingBehavior` — items snap to center position
  - Center item: cyan text, full opacity
  - Adjacent items: `TextSecondary`, reduced opacity
  - Item height: 52dp (Sizing.xxl / φ⁴). 3 items visible in the spinner window (156dp).
  - Order: Global → Following → (divider) → kind 30002 relay sets (user's own, scoped by pubkey)
- **Below spinner:** Row with "+ New Relay Set" and "⚙ Relay Settings" buttons
- **Long-press** on a custom set shows delete confirmation
- **Selection** dismisses sheet and switches feed
- `CreateRelaySetScreen` now creates kind 30002 events via `RelayManagementViewModel`

### Indexer Warning

When the kind 99 indexer relay list becomes empty, show inline warning text in the Index tab:
> "No indexer relays configured. Profile and follow list resolution will not work."

## Migration Plan

Room database migration (incremental version bump):

1. Read all non-built-in `RelaySetEntity` rows from `relay_sets`
2. For each, insert a `NostrRelaySetEntity` (d-tag derived from name, with collision suffix if needed) and corresponding `NostrRelaySetMemberEntity` rows into the NIP-51 tables
3. Drop the `relay_sets` table

Seeding (kind 99 indexer relays, kind 10007 search defaults) happens in `AppBootstrapper.bootstrap()`, not in the migration — see Seeding section above.

## Logout Teardown

`AppBootstrapper.teardown()` must also clear:
- `nostrRelaySetDao.clearAllSets()` — remove user's relay sets
- `nostrRelaySetDao.clearAllMembers()` — remove relay set members

These are user-specific data that should not persist across accounts.

## NIP-42 AUTH Preparation

Structural only — full implementation is Sub-project 2:

- `RelayPool` constructor gains `SigningManager` dependency
- `listenForEvents()` gains `AUTH` message intercept (stub: logs and ignores)
- No kind 22242 signing logic yet

## Out of Scope

- Full NIP-42 AUTH implementation (Sub-project 2)
- Relay connection status indicators in settings UI
- Per-relay-set filter persistence (was in `RelaySetEntity.filterJson`, not migrated — filters remain global via `FeedViewModel._filter`)
- `OwnRelayEntity` cleanup (dead table, can be dropped in a future migration)
- `RelayListEntity` / `RelayListDao` — still in use for NIP-65 outbox routing, not affected by this change
