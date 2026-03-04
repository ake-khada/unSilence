# Barq — Android Nostr Client

Barq is a minimal, fast, sovereign Android Nostr client. Forked from Wisp. Named after the Arabic word for lightning (برق). Built for the Bitcoin/Nostr community as a freedom tool.

**Package:** `com.barq.app`
**GitHub:** https://github.com/ake-khada/barq
**Upstream (Wisp):** https://github.com/barrydeen/wisp

---

## Dev Environment

| User | Role |
|------|------|
| `aivii` | Main user — Android Studio, git push, sudo |
| `android-dev` | Claude Code only — scoped to project dir |

- **JDK:** 17 (`org.gradle.java.home=/usr/lib/jvm/java-17-openjdk` in gradle.properties)
- **Gradle:** 8.9
- **Emulator:** Android 16.0 Baklava x86_64, KVM enabled
- **Android Studio:** Panda 2025.3.1 via JetBrains Toolbox

### Known Issues & Fixes
- **Gradle lock conflict** → `pkill -f gradle` from main terminal before building
- **Permission denied on kotlin files** → android-dev lacks write on some files owned by aivii. Use `git rm` or Edit tool instead of bash `rm`. For new files use Write tool.
- **Git push** → always from main user terminal, android-dev has no terminal auth
- **Claude Code launch** → always `cd /home/aivii/projects/barq` first, then `claude`

### Build Commands
```bash
# Debug build
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug

# Clean build
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew clean assembleDebug
```

---

## Architecture

### Stack
Kotlin 2.0 + Jetpack Compose (Material 3), MVVM, Room SQLite (to be added), no external cache relay.

```
UI (ui/screen/, ui/component/)
  → ViewModel (viewmodel/)
  → Repository (repo/)
  → Protocol (nostr/)
  → Relay (relay/)
```

### Performance Strategy
```
User opens feed
→ 1. Serve from Room SQLite cache instantly (0ms)
→ 2. Fill gaps from scored RelayPool (outbox model)
→ 3. Persist new events back to SQLite
```

No Primal, no external cache relay. Full local sovereignty.

### Key Existing Components (from Wisp — keep these)
- `RelayPool` + `RelayScoreBoard` — scores relays by latency/reliability, routes to fastest
- `OutboxRouter` — outbox model, sends events to correct relay per pubkey
- `RelayHealthTracker` + `RelayProber` — drops dead relays fast
- `SubscriptionManager` — deduplicates subscriptions across relay connections
- `MetadataFetcher` — lazy loads profiles only when visible

### To Add
- **Room DB** — local SQLite event cache (top priority, underpins all speed)
- **Lazy subscription batching** — batch multiple filters into single REQ per relay

---

## NIPs

### Inherited from Wisp (verify each exists)
NIP-01, 02, 04, 05, 09, 10, 11, 17, 18, 19, 25, 30, 37, 44, 47, 51, 57, 65

### To Verify in Codebase (may already exist)
- NIP-46 — remote signing
- NIP-55 — Android signer / Amber (`SignerIntentBridge.kt` likely covers this)
- NIP-56 — reporting / flagging
- NIP-68 — picture-first posts
- NIP-36 — content warnings

### To Implement
- **NIP-23** — long-form content reading (articles in feed)
- **NIP-50** — search
- **NIP-85** — Trusted Assertions (Brainstorm WoT integration)
- **NIP-96** — HTTP file storage / media uploads with EXIF stripping

---

## Feature Roadmap (in order)

### 1. AMOLED Black Theme
- Pure `#000000` backgrounds everywhere — no dark grey
- No light theme, no system theme detection. Barq is always dark. Period.
- Bitcoin orange (`#F7931A`) as the sole accent color
- Remove all light theme code and `isSystemInDarkTheme()` checks

### 2. Immersive Scrolling
- Top app bar + bottom nav hide on scroll down
- Reappear on scroll up
- Use Jetpack Compose `enterAlwaysScrollBehavior()`

### 3. Portrait Video in Feed
- Detect portrait video (height > width, i.e. 9:16)
- Render tall filling card width, not cropped to landscape box
- Similar to Amethyst's video rendering

### 4. Auto-play Video on Mute
- Videos auto-play silently when scrolled into view
- Tap to unmute
- Pause when scrolled out of view

### 5. Long-form Content Reading (NIP-23)
- Show kind-30023 articles in feed as cards
- Tap to open full article reading view
- No writing required for v1, reading only

### 6. Zap Icon → ⚡
- Replace all ₿ (Bitcoin symbol) with ⚡ (lightning bolt) for zap actions
- Apply to action bar, zap button, wallet icon in drawer

### 7. Boost Display (Amethyst-style)
- Remove mid-card retweet icon
- Add header row above boosted posts: `[repost icon] [reposter name] boosted · [time]`
- No double avatar stack needed — keep it simple like Amethyst

### 8. Expanded Reactions Panel (Amethyst-style)
- Expandable section below post showing:
  - Zaps: avatar + zap message + `⚡ X sats` on right, sorted by amount
  - Boosts: repost icon + avatar
  - Emoji reactions: grouped by emoji type + avatars
- Triggered by tapping reaction counts

### 9. Relay Feeds + Relay Sets (Jumble-style)
- Remove top center feed selector pill/dropdown from feed screen
- Feed selection moves entirely to drawer under **Feeds**
- Feeds drawer item expands to show:
  - Following (default)
  - Global
  - [User-created relay sets]
  - `+` button to add new relay feed or relay set
- Relay sets: user-named combinations of 2+ relays
- **No DMs in any feed ever**

### 10. WoT via NIP-85 (Brainstorm)
- Optional integration — user connects Brainstorm account
- Publish kind 10040 to declare Brainstorm as trusted service provider
- Consume kind 30382 trust scores
- Show subtle trust indicator on profiles
- Use for spam filtering in relay set feeds

---

## UI / Design Rules

- **AMOLED black only** — `#000000`, never `#121212` or similar
- **Bitcoin orange** (`#F7931A`) for all accents, interactive elements, highlights
- **No light theme** — delete any light theme code encountered
- **Default avatar** — moon with sunglasses icon for accounts with no profile picture
- **No ads ever**

### Drawer Menu (final spec)
```
[Avatar + Name + NIP-05]
[Tor icon] [Moon icon] [QR icon] [⚡ icon]

Profile
Feeds ▼
  └ Following
  └ Global
  └ [Relay Set 1]
  └ [Relay Set 2]
  └ + Add Feed/Set
Search
Wallet (⚡ icon, not ₿)
Lists
Drafts
Settings ▼
  └ Relays
  └ Media Servers
  └ Keys
  └ Safety
  └ Social Graph
  └ Custom Emojis
  └ Console
  [removed: Proof of Work, Messages]

Logout
```

---

## Upstream Sync (Wisp)

To pull Wisp updates into Barq:
```bash
git fetch upstream
# Cherry-pick specific commits rather than full merge
git cherry-pick <commit-hash>
```

**Do NOT use `git merge --theirs`** — it overwrites Barq renames with Wisp names.
Always use `--ours` to keep Barq as base, then selectively apply upstream code changes.

Upstream notable additions (v0.1 → v0.3.6) already merged:
- Media tab on user profiles (video thumbnails)
- Scroll position preservation on back navigation
- Tor .onion relay support
- Mute thread
- Shared OkHttpClient for image loading (perf fix)
- New notes bubble count fix
- Reply context expandable

---

## Distribution Plan
1. F-Droid (primary — our audience lives here)
2. Zapstore (Nostr-native app store)
3. GitHub releases (signed APKs)
4. Play Store (optional, later)

---

## Product Notes
- **Onboarding:** Must show content in <2 seconds on first open. Pre-seed Room cache from relay on signup.
- **Panic wipe:** Settings option to wipe all local data + keys instantly
- **EXIF stripping:** Strip metadata from all images before upload
- **Deep linking:** Handle `nostr:` URI scheme
- **Haptic feedback:** On zap send
- **Draft saving:** Auto-save post drafts

---

## Session Checklist
- [ ] `pkill -f gradle` before opening Android Studio
- [ ] Launch Claude Code from `/home/aivii/projects/barq`
- [ ] Check git status before starting work
- [ ] Build with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`
- [ ] Push from main user terminal (not android-dev)
