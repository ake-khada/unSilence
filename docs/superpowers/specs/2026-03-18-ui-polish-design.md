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

**File:** `AppNavigation.kt` — replace the `FeedPickerSheet` composable and its invocation.

### 2. Relay Settings — 6 Swipeable Tabs (HorizontalPager)

**Current:** 3 fixed tabs (Inbox/Outbox, Index, Search) + 3 collapsible sections below (Relay Sets, Favorites, Blocked).

**New:** 6 tabs in a `ScrollableTabRow` + `HorizontalPager`. Each tab is a full page. Swipe left/right to navigate.

| Tab | Label | Kind | Content |
|-----|-------|------|---------|
| 0 | Inbox/Outbox | 10002 | R/W relays with marker chips |
| 1 | Index | 99 | Indexer relays, empty warning |
| 2 | Search | 10007 | Search relays |
| 3 | Relay Sets | 30002 | List of sets, expandable members, delete |
| 4 | Favorites | 10012 | Relay URLs, set refs, set picker, actions |
| 5 | Blocked | 10006 | Simple URL list |

- `ScrollableTabRow` with Cyan indicator on selected tab
- `HorizontalPager` synced with tab selection
- Tab indicator: Cyan underline on selected tab
- Each page: `LazyColumn` with `AddRelayInput` at top + relay list below
- Remove all `CollapsibleSection` composables — each section becomes its own page

**File:** `RelayManagementScreen.kt` — rewrite using `HorizontalPager`.

### 3. Favorites — Add-to-Set and Start-Feed Actions

On the Favorites tab (page 4), each favorite relay gets action capabilities:

**Long-press** on a favorite relay URL → shows a bottom popup or `DropdownMenu` with:
- **"Add to Set →"** — expands to show list of existing relay sets. Tapping a set calls `viewModel.addRelayToSet(set.dTag, relay.relayUrl)`
- **"Start Feed"** — dismisses relay settings and switches the feed to that single relay

**Implementation:** Add `FeedType.SingleRelay(url: String, label: String)` to the `FeedType` sealed class. This acts like `FeedType.RelaySet` but resolves to a single URL without a NIP-51 event. `FeedViewModel` handles it by connecting to just that URL and querying `eventRepository.feedFlow(listOf(url), filter, limit)`.

**Files:**
- `FeedViewModel.kt` — add `FeedType.SingleRelay` variant, handle in `init` block
- `RelayManagementScreen.kt` — add long-press menu on favorite relay rows
- `AppNavigation.kt` — handle new feed type in label display

### 4. ComposeScreen — Avatar + Text Alignment

**Current:** `Row(verticalAlignment = Alignment.Top)` — avatar sits at row top, text field starts lower due to internal TextField padding. Looks misaligned.

**Fix:** Keep `Alignment.Top` but add `Modifier.padding(top = 4.dp)` to the BasicTextField to visually align the first line of text with the center of the avatar. Or switch to `Alignment.CenterVertically` if the text field is single-line initially.

Better approach: keep `Alignment.Top`, add small top padding to avatar so its center aligns with text baseline: `Modifier.padding(top = 2.dp)` on the avatar Box.

**File:** `ComposeScreen.kt` — adjust padding in the compose Row.

### 5. ThreadScreen Reply Bar — Real Avatar

**Current:** Reply bar shows only `IdentIcon` for the user. No profile picture overlay.

**Fix:** Match the ComposeScreen pattern — IdentIcon as base layer, `AsyncImage` overlay if the user has a profile picture URL.

Need to expose the user's avatar URL in `ThreadViewModel` or read it from `NoteActionsViewModel`/`UserDao` directly in the composable.

**Approach:** Add `val userAvatarUrl: StateFlow<String?>` to `ThreadViewModel`, populated from `userDao.userFlow(pubkeyHex)`.

**File:** `ThreadViewModel.kt` (add avatar flow), `ThreadScreen.kt` (add AsyncImage overlay).

## Out of Scope

- NIP-42 AUTH implementation
- Relay connection status indicators
- NoteCard internal layout changes (the navigation bug was fixed separately)
