# UI Polish Sprint — Design Spec

## Goal

Fix feed picker UX (inline popup instead of full-screen dialog), convert relay settings to 6 swipeable tabs, add favorite relay actions (add-to-set + start-feed), fix avatar/alignment in ComposeScreen and ThreadScreen reply bar.

## Changes

### 1. Feed Picker — Inline Popup Overlay

**Current:** Full-screen dark `Dialog` with snap-scroll spinner. Feels like page navigation for a small selection list.

**New:** `Popup` composable anchored below the feed label text in the top bar. Small floating overlay.

- Anchored to the feed label ("Global ▾") in the top bar using `Popup(alignment = Alignment.TopStart, offset = ...)`
- Dark background `Color(0xFF0A0A0A)` with rounded corners
- `LazyColumn` with `SnapFlingBehavior` — items snap to center position
- Center item: Cyan text, 18sp, SemiBold. Adjacent: TextSecondary, 15sp
- Item height: 52dp. 3 items visible (156dp window)
- Order: Global → Following → (divider) → kind 30002 relay sets
- Below spinner: Row with "+ New Relay Set" (Cyan) and "⚙ Relay Settings" (TextSecondary)
- Long-press on custom set → delete confirmation AlertDialog
- Selection dismisses popup and switches feed
- Outside tap dismisses popup
- Width: 200dp (or 0.55f of screen width)
- **"⚙ Relay Settings" button**: Currently a no-op (relay settings is opened from Profile → Settings). Add a `showRelaySettings` state to `AppNavigation` and show `RelayManagementScreen` overlay when tapped.

**File:** `AppNavigation.kt` — replace the `FeedPickerSheet` composable and its invocation. Add `showRelaySettings` state + overlay.

### 2. Relay Settings — 6 Swipeable Tabs (HorizontalPager)

**Current:** 3 fixed tabs (Inbox/Outbox, Index, Search) + 3 collapsible sections below (Relay Sets, Favorites, Blocked).

**New:** 6 tabs in a `ScrollableTabRow` + `HorizontalPager`. Each tab is a full page. Swipe left/right to navigate.

| Tab | Label | Kind | Content |
|-----|-------|------|---------|
| 0 | Inbox/Outbox | 10002 | R/W relays with marker chips + AddRelayInput |
| 1 | Index | 99 | Indexer relays + AddRelayInput, empty warning |
| 2 | Search | 10007 | Search relays + AddRelayInput |
| 3 | Relay Sets | 30002 | List of sets, expandable members, delete. **No AddRelayInput** — set creation uses `CreateRelaySetScreen`. Show a "+ New Relay Set" button instead. |
| 4 | Favorites | 10012 | Relay URLs + AddRelayInput, set refs, set picker, long-press actions |
| 5 | Blocked | 10006 | Simple URL list + AddRelayInput |

- `ScrollableTabRow` with Cyan indicator on selected tab
- `HorizontalPager` synced with tab selection — requires `@OptIn(ExperimentalFoundationApi::class)`
- Tab indicator: Cyan underline on selected tab
- URL-based tabs (0, 1, 2, 4, 5): `LazyColumn` with `AddRelayInput` at top + relay list
- Relay Sets tab (3): `LazyColumn` with "+ New Relay Set" button at top + expandable set list
- Remove all `CollapsibleSection` composables — each section becomes its own page
- `RelayManagementScreen` gains an `onStartFeed: ((String, String) -> Unit)?` callback parameter for the favorites "Start Feed" action. `AppNavigation` wires this to `feedViewModel.setFeedType(FeedType.SingleRelay(...))` and dismisses the screen.

**File:** `RelayManagementScreen.kt` — rewrite using `HorizontalPager`.

### 3. Favorites — Add-to-Set and Start-Feed Actions

On the Favorites tab (page 4), each favorite relay gets action capabilities:

**Long-press** on a favorite relay URL → shows a `DropdownMenu` with:
- **"Add to Set →"** — expands to show list of existing relay sets. Tapping a set calls `viewModel.addRelayToSet(set.dTag, relay.relayUrl)`
- **"Start Feed"** — calls `onStartFeed(url, label)` which flows up to `AppNavigation`, sets the feed type, and dismisses relay settings

**Implementation:** Add `FeedType.SingleRelay(url: String, label: String)` to the `FeedType` sealed class. Update all exhaustive `when` expressions:
- `feedTypeLabel` (line ~142): add `is FeedType.SingleRelay -> t.label`
- `refresh()` (line ~154): add `is FeedType.SingleRelay` branch (same as Global — connect + feedFlow with single URL)
- `init` block `flatMapLatest` (line ~216): add `SingleRelay` branch that calls `relayPool.connect(listOf(url))` then returns `eventRepository.feedFlow(listOf(url), filter, limit)`

**Files:**
- `FeedViewModel.kt` — add `FeedType.SingleRelay` variant, handle in all `when` expressions and `init` block
- `RelayManagementScreen.kt` — add long-press menu on favorite relay rows, accept `onStartFeed` callback
- `AppNavigation.kt` — handle new feed type in label display, wire `onStartFeed` callback

### 4. ComposeScreen — Avatar + Text Alignment

**Current:** `Row(verticalAlignment = Alignment.Top)` — avatar sits at row top, text field starts lower due to internal TextField padding. Looks misaligned.

**Fix:** Keep `Alignment.Top`. Add `Modifier.padding(top = 2.dp)` to the avatar Box so its visual center aligns with the text's first line baseline.

**File:** `ComposeScreen.kt` — add padding to avatar Box in the compose Row.

### 5. ThreadScreen Reply Bar — Real Avatar

**Current:** Reply bar shows only `IdentIcon` for the user. No profile picture overlay.

**Fix:** Match the ComposeScreen pattern — IdentIcon as base layer, `AsyncImage` overlay if the user has a profile picture URL.

Add `val userAvatarUrl: StateFlow<String?>` to `ThreadViewModel`, populated from `userRepository.userFlow(pubkeyHex)` mapping to `picture` field. This follows the same pattern used by `FeedViewModel` and `ComposeViewModel`.

In `ThreadScreen.kt`, collect `userAvatarUrl` and add `AsyncImage` overlay inside the reply bar's avatar Box.

**Files:** `ThreadViewModel.kt` (add `UserRepository` dependency + avatar flow), `ThreadScreen.kt` (add AsyncImage overlay + collect avatar state).

## Out of Scope

- NIP-42 AUTH implementation
- Relay connection status indicators
- NoteCard internal layout changes (the navigation bug was fixed separately)
