# unSilence — Nostr Android Client

> **This is the single source of truth for the unSilence project.**
> Claude Code: read this file before every session. Update it when things change.

---

## IDENTITY

**unSilence** is a **relay-browsing Nostr client for Android** with **one-tap NWC zaps** and **feed filtering**.

- **Name:** unSilence (camelCase — lowercase u, uppercase S)
- **Icon:** uS monogram — minimal, works at all sizes from 24dp notification to 512px Play Store
- **Brand color:** Cyan (`#00E5FF`) on AMOLED black (`#000000`)
- **Positioning:** Politically neutral in freedom tech. No Bitcoin orange (faction signal), no purple (Damus/Amethyst own it), no lightning bolt (alienates non-Lightning users). The brand says "censorship resistance" without pledging allegiance to any tribe.

It is NOT a general-purpose social client. It is NOT Amethyst. It is NOT Damus.
It is Jumble's relay-centric philosophy + native Android + lightning-fast zaps.

---

## HARD LESSONS (DO NOT REPEAT)

1. **Never fork a full client.** Forking an existing Nostr client and ripping out its relay guts (`OutboxRouter`, `RelayScoreBoard`, `RelayProber`, `SocialGraphViewModel`) doesn't work — old routing fights new routing. Start fresh, use libraries.

2. **Fundamentals before features.** Don't build a 9-item roadmap before the follow feed works. Posts missing (no NIP-65 outbox), search broken, relays storming — a feed that loads fast IS the product.

3. **Cache is priority #1, not #9.** Without Room SQLite, every screen transition re-fetches from relays. The architecture is: `Relay → Room → Flow → Compose`. The UI never waits on the network.

4. **One identity.** Don't try to be Jumble (relay feeds) + Damus (video autoplay) + Amethyst (everything). Pick one thing, do it perfectly.

5. **Kind 22 portrait video is a v1.1 feature.** ExoPlayer instance pooling, visibility-based autoplay, ANR from `onGloballyPositioned` — confirmed problems. Don't touch this in v1.

---

## DEV ENVIRONMENT

### Two-User Setup (Security Boundary — Intentional)

- `aivii` — Main user. Has git access, sudo, runs Android Studio.
- `android-dev` — Scoped to Claude Code only. No git write, no sudo.

### One-Time Permission Fix (Run Once, Never Again)

```bash
# Run as aivii. Sets default ACLs so android-dev can always read/write build outputs
# and aivii can always read/write what android-dev creates.

# Install ACL tools if needed
sudo apt install acl

# Project directory
PROJECT=~/projects/unsilence

# Give android-dev read/write to the project
sudo setfacl -R -m u:android-dev:rwX "$PROJECT"
sudo setfacl -R -d -m u:android-dev:rwX "$PROJECT"

# Give aivii read/write to everything android-dev creates
sudo setfacl -R -m u:aivii:rwX "$PROJECT"
sudo setfacl -R -d -m u:aivii:rwX "$PROJECT"

# Gradle/Kotlin caches — both users need access
for dir in "$PROJECT/.gradle" "$PROJECT/.kotlin" "$PROJECT/app/build"; do
  mkdir -p "$dir"
  sudo setfacl -R -m u:android-dev:rwX "$dir"
  sudo setfacl -R -d -m u:android-dev:rwX "$dir"
  sudo setfacl -R -m u:aivii:rwX "$dir"
  sudo setfacl -R -d -m u:aivii:rwX "$dir"
done
```

After this runs once, both users can build without permission errors. No more `chmod` per session.

### Build Rules

- **JDK:** 17 (Android Studio bundled JBR at `/home/aivii/.local/share/JetBrains/Toolbox/apps/android-studio/jbr`)
- **Gradle:** Set `org.gradle.java.home` in `gradle.properties` to the above path
- **Never run `./gradlew` from terminal while Android Studio is open** — use Studio to build
- **All git operations from `aivii` terminal only** — `android-dev` cannot write `.git/objects`
- **Emulator:** Android 16.0 Baklava (or whatever is current)

---

## ARCHITECTURE

```
┌─────────────────────────────────────────────────────┐
│                    Compose UI                        │
│  (LazyColumn, feeds, profiles, zap buttons, filters) │
├─────────────────────────────────────────────────────┤
│                   ViewModels                         │
│  (FeedVM, ProfileVM, ZapVM, RelaySetVM, FilterVM)    │
├─────────────────────────────────────────────────────┤
│                  Repository Layer                    │
│  (EventRepo, UserRepo, RelayRepo, ZapRepo)           │
├─────────────────────────────────────────────────────┤
│              Room SQLite Database                     │
│  events | users | relay_sets | relay_info | zaps     │
├─────────────────────────────────────────────────────┤
│         Nostr Service Layer (Quartz)                 │
│  WebSocket connections, event signing, NIP impls     │
├─────────────────────────────────────────────────────┤
│                   Network                            │
│  OkHttp WebSockets to relays                         │
└─────────────────────────────────────────────────────┘
```

**The golden rule:** UI reads ONLY from Room via Flow/StateFlow. Network writes to Room. The UI never waits on a WebSocket.

### Key Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| `com.vitorpamplona.quartz:quartz-android` | Nostr protocol (events, signing, relay comms, NIP-55 Amber) | latest (1.05.x+) |
| `androidx.room:room-*` | Local SQLite cache | 2.6.x |
| `io.coil-kt:coil-compose` | Image loading with blurhash | 2.x |
| `io.coil-kt:coil-gif` | Animated GIF/WebP rendering | 2.x |
| `io.github.thibseisel:identikon` | Pubkey-derived unique identicon avatars (KMP) | 1.x |
| `com.squareup.okhttp3:okhttp` | WebSocket (via Quartz) | 4.x |
| `com.google.dagger:hilt-android` | DI | 2.x |
| `androidx.media3:media3-exoplayer` | Video playback (kind 21 tap-to-play only in v1) | 1.x |

### Package Structure

```
com.unsilence.app/
├── data/
│   ├── db/                  # Room database, DAOs, entities
│   ├── relay/               # Relay connection management, Quartz wrapper
│   ├── repository/          # EventRepo, UserRepo, RelayRepo, ZapRepo
│   └── nwc/                 # NWC connection, zap flow
├── domain/
│   ├── model/               # Domain models (Note, User, RelaySet, Filter)
│   └── filter/              # Feed filter logic
├── ui/
│   ├── feed/                # Feed screen + feed item renderers
│   ├── profile/             # Profile screen
│   ├── relay/               # Relay set management screens
│   ├── settings/            # Settings, NWC setup, key management
│   ├── compose/             # Compose new note screen
│   └── theme/               # AMOLED theme, colors, typography
└── di/                      # Hilt modules
```

---

## THEME

- Background: `#000000` (pure AMOLED black, always, no exceptions)
- Primary accent: `#00E5FF` (cyan — brand color, buttons, selected states, links, active tabs)
- Secondary accent: `#00B8D4` (darker cyan for pressed/dimmed states)
- Zap accent: `#FFAB00` (warm amber, ONLY for zap-specific UI: ⚡ amounts, zap confirmations, zap animations — not used anywhere else)
- Text: `#FFFFFF` primary, `#888888` secondary, `#00E5FF` links/interactive
- No light theme. Ever.
- Default avatar fallback: **pubkey-derived identicon** — NOT a universal placeholder. Each pubkey generates a unique geometric pattern AND unique color, both derived from the pubkey hash. Full color spectrum — color is part of the identity (e.g., "the green diamond person" vs "the purple zigzag person"). Generated locally, no API dependency. Critical for notification stacking where multiple avatars appear in a row — patterns AND colors must be distinct at a glance.
- App icon: **uS** monogram in cyan on black, no glow effects, flat/minimal, must be legible at 24dp

### Golden Ratio Spacing System

All spacing, sizing, and proportions derive from a single scale: **360dp (standard Android screen width) divided successively by φ (1.618)**. Every value in the system relates to its neighbors by the golden ratio.

**The scale:** `5 → 8 → 12 → 20 → 32 → 52 → 85`

Each step ≈ previous × 1.618. Rounding keeps values practical for dp.

| Element | Value | Scale Position | Notes |
|---------|-------|---------------|-------|
| Micro spacing (icon gaps, inline) | **5dp** | φ⁹ | Between action bar icons, inline elements |
| Card gap / divider spacing | **8dp** | φ⁸ | Between note cards in feed |
| Media corner radius | **8dp** | φ⁸ | All images, videos, thumbnails, link previews |
| Side padding (screen edge → content) | **12dp** | φ⁷ | Left + right = 24dp total → 336dp content width |
| Card internal padding (horizontal) | **12dp** | φ⁷ | Text, media, action bar all share this margin |
| Card internal padding (vertical) | **8dp** | φ⁸ | Top/bottom within card |
| Action bar icon size | **20dp** | φ⁶ | 💬 🔁 🤙 ⚡ icons |
| Avatar size (feed) | **32dp** | φ⁵ | Compact, more room for content |
| Top bar height | **52dp** | φ⁴ | Single-row: dropdown + icons |
| Bottom nav height | **52dp** | φ⁴ | 4 tabs: Home, Notifications, Profile, Settings |

**Why 12dp side padding, not 16dp (Material default):** 16dp is arbitrary. 12dp is φ⁷ of screen width — mathematically derived, slightly tighter, gives more content breathing room on AMOLED where the black background IS the frame.

**Content area:** 360 - 24 = **336dp** (93.3% of screen width)

```kotlin
// Spacing constants for Claude Code
object Spacing {
    val micro = 5.dp      // φ⁹
    val small = 8.dp      // φ⁸  — card gaps, corner radius, vertical padding
    val medium = 12.dp    // φ⁷  — side padding, horizontal card padding
    val large = 20.dp     // φ⁶  — icon sizes
    val xl = 32.dp        // φ⁵  — avatars
    val xxl = 52.dp       // φ⁴  — bar heights
}

object Sizing {
    val avatar = 32.dp
    val actionIcon = 20.dp
    val topBarHeight = 52.dp
    val bottomNavHeight = 52.dp
    val mediaCornerRadius = 8.dp
}
```

---

## NIPs — WHAT TO IMPLEMENT AND WHEN

### v1 — Ship These

| NIP | Kind(s) | What | Notes |
|-----|---------|------|-------|
| **01** | all | Basic protocol (events, REQ, CLOSE, filters) | Foundation of everything |
| **02** | 3 | Follow list | Contact list — who user follows |
| **09** | 5 | Event Deletion Request | Delete own posts. Publish kind 5 with `e`/`a` tags referencing target events |
| **10** | 1 | Text notes + threading (root/reply tags) | Core feed content |
| **11** | — | Relay information document | Show relay name, description, supported NIPs |
| **18** | 6, 16 | Reposts | Boosts in feed |
| **19** | — | bech32 encoding (npub, note, nevent, nprofile) | Human-readable IDs |
| **21** | — | `nostr:` URI scheme | Foundation for NIP-27 clickable links in content |
| **24** | — | Extra metadata fields & tags | `display_name`, `website`, `banner`, `bot` in kind 0 profiles |
| **25** | 7 | Reactions | Like/react, custom emoji reactions (NIP-30), filter counts |
| **27** | — | Text Note References | Render `nostr:npub1...`, `nostr:note1...` as clickable links |
| **30** | — | Custom Emoji | Parse `:shortcode:` → inline images from `emoji` tags. Applies to kind 0, 1, 7 |
| **36** | — | Sensitive Content / Content Warning | Blur content behind warning, tap to reveal. Critical for relay browsing |
| **40** | — | Expiration Timestamp | Don't display events past their `expiration` tag. Simple timestamp check |
| **42** | — | Authentication of clients to relays | Required for auth-gated relays. Quartz handles via `RelayAuthenticator` |
| **45** | — | Event Counts (COUNT verb) | Follower counts via indexer relays, approximate |
| **47** | 23194/23195 | Nostr Wallet Connect (NWC) | One-tap zaps — THE differentiator |
| **55** | — | Android Signer (Amber) | External key management via Quartz |
| **57** | 9734/9735 | Lightning Zaps | Zap request/receipt, filter by zap amount |
| **65** | 10002 | Relay List Metadata | Outbox model — where people read/write |
| **68** | 20 | Picture events | Image gallery, `imeta` tags, blurhash |
| **71** | 21 | Video events (landscape) | Tap-to-play only in v1, NO autoplay |
| **92** | — | Media Attachments (`imeta` tags) | Inline metadata for media URLs in content |
| **B7** | 10063 | Blossom media | Upload images/GIFs. Default free server + user can add own. SHA-256 addressed |

### v1.1 — Next Release

| NIP | Kind(s) | What | Notes |
|-----|---------|------|-------|
| **05** | — | DNS identifier (NIP-05 verification) | Verified badges |
| **22** | 1111 | Comments (on kinds 20, 21, 30023) | Comment threading for media posts |
| **46** | — | Nostr Remote Signing / Nostr Connect | Alternative to NIP-55 for remote signers (nsecBunker) |
| **49** | — | Private Key Encryption (`ncryptsec`) | Secure nsec export/backup with password encryption |
| **50** | — | Search | NIP-50 relay search capability |
| **51** | 30000+ | Lists | Relay sets as Nostr events, mute lists, bookmarks |
| **56** | 1984 | Reporting | Report spam/abuse/illegal content. Essential for relay browsing |
| **71** | 22 | Short-form portrait video | ExoPlayer pooling + visibility autoplay — hard, do it right |

### v2+ — Future

| NIP | What |
|-----|------|
| **23** | Long-form content (kind 30023) |
| **66** | Relay Discovery & Liveness Monitoring |
| **77** | Negentropy Syncing |
| **85** | Trusted Assertions (WoT) |

### NOT implementing

- NIP-04/17 (DMs) — out of scope, period
- NIP-29/72 (communities/groups) — out of scope
- NIP-37 (draft events) — requires NIP-44 encryption + private relay infra we don't have. Local Room drafts achieve the same thing for a single-device app with zero complexity
- NIP-53 (live activities) — out of scope

---

## FEED FILTER SYSTEM (Key Feature)

The filter system is what makes relay browsing useful instead of a firehose.

### Filter Model

```kotlin
enum class ContentType {
    ALL,              // everything — notes + replies
    NOTES_ONLY,       // top-level posts only (no root/reply e tags)
    REPLIES_ONLY      // replies/conversations only (has root/reply e tags)
}

data class FeedFilter(
    // Content type — notes vs conversations
    val contentType: ContentType = ContentType.NOTES_ONLY,  // default: notes only
    // Sensitive content
    val hideSensitive: Boolean = false,    // hide NIP-36 content-warning events entirely
    // Kind toggles — which event types to show
    val showKind1: Boolean = true,     // text notes
    val showKind6: Boolean = true,     // reposts
    val showKind20: Boolean = true,    // pictures
    val showKind21: Boolean = true,    // video
    // Engagement thresholds — minimum counts to display
    val minReactions: Int = 0,         // kind 7 count
    val minZapAmount: Long = 0,        // total sats from kind 9735
    val minReplies: Int = 0,           // reply count
    // Saved per relay set
    val relaySetId: String? = null
)

// NOTE: Kotlin defaults above are for regular relay sets (all zeros).
// Global feed ships with FeedFilter(minReactions = 3) — set when creating the built-in Global relay set.
```

### Notes vs Conversations — How It Works Technically

The distinction is purely NIP-10 tag parsing, same kind 1 events:

- **Note (top-level post):** kind 1 event with NO root `e` tag and NO reply `e` tag
- **Reply (conversation):** kind 1 event WITH root and/or reply `e` tags
- **Kind 20/21 (pictures/video):** always top-level, never replies (replies to these use kind 1111 per NIP-22)

The `reply_to_id` and `root_id` fields already exist in the events Room table. A note has both as `null`. A reply has at least one set.

**Default is Notes Only.** When browsing a relay, you want to see original content, not orphaned replies with no context. Users can switch to All or Replies Only via the filter sheet.

**Important for Claude Code:** The REQ to relays always fetches ALL kind 1 events (notes AND replies). We need replies in Room for thread views and reply counts. The notes/replies split is a **client-side Room query filter only** — never filter this at the relay level.

### How It Works

1. Events arrive from relay → stored in Room with `kind`, `relay_url`, `event_id`
2. Reactions (kind 7), zap receipts (kind 9735), and replies (kind 1 with `e` tags) are counted per event in Room
3. Feed query joins events with engagement counts, applies filter thresholds
4. All filtering is **client-side against Room** — no extra relay queries

### Room Query (Pseudocode)

```sql
SELECT e.*, 
    COUNT(DISTINCT r.id) as reaction_count,
    COALESCE(SUM(z.amount), 0) as zap_total,
    COUNT(DISTINCT rep.id) as reply_count
FROM events e
LEFT JOIN reactions r ON r.target_event_id = e.id
LEFT JOIN zap_receipts z ON z.target_event_id = e.id  
LEFT JOIN events rep ON rep.reply_to_id = e.id
WHERE e.relay_url IN (:relaySetUrls)
    AND e.kind IN (:enabledKinds)
    -- Content type filter
    AND CASE :contentType
        WHEN 'NOTES_ONLY' THEN (e.reply_to_id IS NULL AND e.root_id IS NULL)
        WHEN 'REPLIES_ONLY' THEN (e.reply_to_id IS NOT NULL OR e.root_id IS NOT NULL)
        ELSE 1  -- ALL: no filter
    END
    -- Sensitive content filter
    AND CASE WHEN :hideSensitive THEN e.hasContentWarning = 0 ELSE 1 END
GROUP BY e.id
HAVING reaction_count >= :minReactions
    AND zap_total >= :minZapAmount
    AND reply_count >= :minReplies
ORDER BY e.created_at DESC
```

### Filter UI

- Filter icon (funnel) in top bar, next to search
- Tapping opens a bottom sheet:
  - **Content type:** segmented button — Notes / All / Replies (default: Notes)
  - **Kind toggles:** chips for Text / Pictures / Video / Reposts
  - **Minimum reactions:** slider (0–100+)
  - **Minimum zaps:** slider (0–100k+ sats)  
  - **Minimum replies:** slider (0–50+)
- Filters save per relay set
- Active filter shows dot indicator on the filter icon (dot appears when anything is non-default)

---

## KIND RENDERING SPECS

**Media alignment rule:** ALL visual media renders inside the note card with **12dp horizontal padding** (φ⁷ — same as text content). This applies to: images (kind 20 `imeta`), inline URL images in kind 1, GIFs, video thumbnails (kind 21), link preview cards (OpenGraph), and any future media type. Nothing bleeds edge-to-edge. Everything aligns — avatar, name, text, media, action bar — same left/right margins. **8dp rounded corners** (φ⁸) on all media.

### Kind 1 — Text Note (NIP-10)

Standard note card:
- Author avatar (Coil, blurhash placeholder, pubkey-derived identicon fallback) + display name + timestamp
- Content text with NIP-27 `nostr:` link rendering (clickable npub, note, nevent)
- Content text with NIP-30 custom emoji rendering (`:shortcode:` → inline images)
- Inline image/GIF previews if URLs detected in content (card-width, rounded corners, same padding as text)
- Link preview cards for non-media URLs (OpenGraph: title, description, thumbnail — card-width, same padding)
- Action bar: icon + count only, no labels, evenly spaced. Left to right:
  - `💬 3` — reply count (tap → thread view)
  - `🔁 2` — repost count (tap → repost, long-press → quote)
  - `🤙 12` — reaction count, shows top emoji if not default (tap → react, long-press → emoji picker)
  - `⚡ 21k` — zap total in sats, shortened (tap → default zap, long-press → amount picker)
  - Counts use `#888888` secondary text. Icons use `#888888` default, `#00E5FF` cyan when you've interacted (you liked, you zapped, you replied, you reposted)

### Kind 6 — Repost (NIP-18)

- "🔁 @user reposted" header
- Render the embedded original event (kind 1, 20, or 21) below inside a subtle bordered card
- **Media alignment rule applies inside reposts too** — embedded images, videos, GIFs, link previews all use the same padding as text within the repost card. No edge-to-edge bleed, even though the content is nested.

### Kind 7 — Reaction (NIP-25)

- Not rendered as feed items — counted as engagement metrics on other events
- If content is `+` → count as like
- If content is `-` → count as dislike (display separately if desired)
- If content is a unicode emoji → display the emoji as the reaction
- If content is `:shortcode:` with an `emoji` tag → render as custom emoji image (NIP-30)
- Reaction display on notes: show top 3-4 distinct reaction emojis with counts (e.g., 🤙 12  🔥 5  :custom: 3)

### Custom Emoji Rendering (NIP-30)

Applies to kind 0 (profiles), kind 1 (notes), and kind 7 (reactions).

**Parsing:**
1. Scan event content for `:shortcode:` patterns (regex: `:[a-zA-Z0-9_]+:`)
2. For each match, look up the shortcode in the event's `emoji` tags
3. If found, replace with inline image loaded from the tag's URL
4. If not found (no matching `emoji` tag), leave as plain text `:shortcode:`

**Rendering:**
- Inline images sized to match text line height (~20dp)
- Loaded via Coil with caching (these images are small and reused constantly)
- In profiles (kind 0): emojify `name` and `about` fields

**User's Emoji List (kind 10030):**
- Users can publish a kind 10030 event listing their preferred custom emojis
- Contains `emoji` tags with shortcodes and URLs
- When composing a note, show these as a picker (like a custom emoji keyboard)
- When user types `:` in compose, show autocomplete from their emoji list
- Outgoing events include the relevant `emoji` tags for any custom emoji used

```kotlin
// Content parser pseudocode
fun emojify(content: String, emojiTags: List<List<String>>): AnnotatedString {
    val emojiMap = emojiTags
        .filter { it.size >= 3 && it[0] == "emoji" }
        .associate { it[1] to it[2] }  // shortcode → URL

    // Replace :shortcode: with inline image annotations
    val regex = Regex(":([a-zA-Z0-9_]+):")
    return buildAnnotatedString {
        var lastIndex = 0
        regex.findAll(content).forEach { match ->
            append(content.substring(lastIndex, match.range.first))
            val url = emojiMap[match.groupValues[1]]
            if (url != null) {
                appendInlineContent(match.groupValues[1]) // Compose InlineContent
            } else {
                append(match.value) // no matching tag, leave as text
            }
            lastIndex = match.range.last + 1
        }
        append(content.substring(lastIndex))
    }
}
```

### Kind 20 — Picture (NIP-68)

Image-first card:
- Parse `imeta` tags for image URLs, dimensions (`dim`), blurhash, alt text
- Render as image gallery (single image card-width with same padding as text, multiple as grid/carousel)
- Show blurhash placeholder while loading (from `imeta` blurhash field)
- `title` tag as overlay or caption
- `content` field as description below image
- Same action bar as kind 1
- Tap image → fullscreen viewer with pinch-zoom

### Kind 21 — Video Event (NIP-71)

Video card (v1: tap-to-play only, NO autoplay):
- Parse `imeta` tags for video URL, thumbnail URL, dimensions
- Show thumbnail with play button overlay (card-width, rounded corners, same padding as text)
- Tap → ExoPlayer starts playback inline (card-width, NOT fullscreen), muted by default
- Tap video → unmute + fullscreen option
- `title` tag as title above/below video
- `content` as description
- Same action bar as kind 1

**v1 constraint:** One ExoPlayer instance at a time. When a new video starts, the previous one stops. No pooling, no visibility detection. Simple and stable.

### Kind 22 — Portrait Video (NIP-71) — v1.1 ONLY

NOT in v1. When implemented:
- Full-screen vertical card
- Auto-play on scroll visibility (use `LazyListState.layoutInfo.visibleItemsInfo`, NOT `onGloballyPositioned`)
- ExoPlayer instance pool (3-5 instances, recycle on scroll)
- Muted by default, tap to unmute
- Swipe up/down for next/prev

### Sensitive Content (NIP-36) — Applies to ALL Kinds

Any event with a `content-warning` tag gets blurred behind a warning overlay.

**Detection:**
- Check event tags for `["content-warning", "<optional reason>"]`
- Also check for NIP-32 labels: `["L", "content-warning"]` + `["l", "reason", "content-warning"]`

**Rendering:**
- Content (text, images, video thumbnails) is fully blurred (Gaussian blur, ~25px radius)
- Warning overlay shows: `⚠️ Sensitive Content` + the reason if provided (e.g., "nudity", "violence")
- Tap overlay → content reveals with a brief unblur animation
- Revealed state persists for the session (don't re-blur if user scrolls away and back)
- Kind 20 images: blur the image, show warning over it
- Kind 21 video: blur the thumbnail, warning blocks tap-to-play until acknowledged

**Settings:**
- Global toggle in Settings: "Show sensitive content without warning" (default: OFF)
- When ON, content-warning events render normally with a small ⚠️ badge in the corner

**Compose:**
- When composing, option to mark as sensitive (adds `content-warning` tag)
- Optional reason text field

```kotlin
// Detection in event processor
fun hasCW(tags: List<List<String>>): Pair<Boolean, String?> {
    val cwTag = tags.find { it.size >= 1 && it[0] == "content-warning" }
    return if (cwTag != null) {
        Pair(true, cwTag.getOrNull(1))  // (isSensitive, reason?)
    } else {
        Pair(false, null)
    }
}
```

---

## POST COUNTDOWN PREVIEW

After hitting "Post", the note doesn't publish immediately. Instead:

### Flow

```
User hits Post
    │
    ├─ Compose screen transitions to a preview overlay
    │
    ├─ The note is rendered EXACTLY as it will appear in the feed:
    │   - Avatar, display name, timestamp ("just now")
    │   - Full content with NIP-30 emoji, NIP-27 links rendered
    │   - Images/media previews if present
    │   - NIP-36 content-warning blur if marked sensitive
    │   - Action bar (greyed out, not interactive)
    │
    ├─ Circular countdown timer: 5 seconds (cyan ring draining)
    │
    ├─ Two options:
    │   [Cancel] — aborts, returns to compose screen with content preserved
    │   [Send Now] — skips countdown, publishes immediately
    │
    └─ Countdown reaches 0 → event is signed and published to relays
       → Brief success animation → returns to feed
```

### Why This Matters

- Catches typos after you've already "committed" mentally to posting
- Verifies custom emoji rendered correctly
- Confirms images/links look right
- Prevents accidental posts (pocket-post, wrong relay set)
- The rendered preview IS the feed card component — same composable, zero extra work

### Implementation

```kotlin
@Composable
fun PostPreviewCountdown(
    event: UnsignedEvent,      // not yet signed/published
    onCancel: () -> Unit,
    onPublished: () -> Unit
) {
    var secondsLeft by remember { mutableIntStateOf(5) }
    var cancelled by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        while (secondsLeft > 0 && !cancelled) {
            delay(1000)
            secondsLeft--
        }
        if (!cancelled) {
            // Sign and publish
            signAndPublish(event)
            onPublished()
        }
    }
    
    // Full-screen overlay with dimmed background
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f))) {
        // Reuse the exact same NoteCard composable from the feed
        NoteCard(event = event.toPreview(), modifier = Modifier.align(Alignment.Center))
        
        // Countdown ring + buttons at bottom
        CountdownRing(secondsLeft = secondsLeft, total = 5)  // cyan ring
        Row {
            TextButton(onClick = { cancelled = true; onCancel() }) { Text("Cancel") }
            TextButton(onClick = { secondsLeft = 0 }) { Text("Send Now") }
        }
    }
}
```

### Settings

- Countdown duration configurable in Settings (default: 5 seconds, options: 3/5/10/off)
- "Off" skips preview entirely, publishes on tap (for power users)

---

## MEDIA UPLOAD & GIFs (Blossom / NIP-92)

All media uploads go through Blossom. One protocol, one upload path, no complexity.

### How It Works

```
User attaches image/GIF in compose (from gallery, camera, or GIF picker)
    │
    ├─ 1. Client computes SHA-256 hash of the file
    │
    ├─ 2. Client signs a kind 24242 Blossom auth event:
    │     {"kind": 24242, "tags": [["t", "upload"], ["x", "<sha256>"]], ...}
    │
    ├─ 3. Client PUTs file to Blossom server with signed event in Authorization header
    │     → Server returns URL: https://blossom.server/<sha256>.gif
    │
    ├─ 4. Client inserts URL into note content
    │
    ├─ 5. Client adds NIP-92 `imeta` tag with metadata:
    │     ["imeta", "url <url>", "m image/gif", "dim 480x270", "x <sha256>"]
    │
    └─ 6. Event published with URL in content + imeta tag
```

### Blossom Server Configuration

- **Default:** `blossom.nostr.build` (free, widely used, supports images/GIFs)
- **Settings → Media Server:** user can add their own Blossom server URL for more space/control
- **Upload limit:** respect server's size limits, show clear error if file too large
- Fetch user's kind 10063 (Blossom server list) if they've published one — use their preferred server
- If user has no kind 10063 and hasn't configured a server, use the default

### GIF Support — Three Ways

**1. Render GIFs in feed (automatic):**
- Coil + `coil-gif` renders any `.gif` / animated `.webp` URL in content
- Animated by default, respects Android's data saver / reduced motion settings
- GIFs in kind 20 `imeta` tags render the same as static images but animated

**2. Paste GIF URL in compose:**
- User pastes a GIF URL (from Tenor, Giphy, any source)
- Compose shows inline preview (animated)
- Published as-is in content — no upload needed, URL is the media

**3. GIF picker in compose (Tenor integration):**
- GIF icon (🎞️) in compose toolbar, next to image attach
- Opens search-powered GIF picker (Tenor API — free tier, 5k req/day)
- User searches, taps GIF → URL inserted into content
- No upload needed — Tenor hosts the GIF, we just reference the URL
- Fallback: if Tenor is down, show "paste GIF URL" option

**4. Upload GIF from device:**
- Same flow as image upload — pick from gallery, upload to Blossom
- Server returns URL, client embeds in content with `imeta` tag
- Works for any media: photos, GIFs, screenshots

### Compose Toolbar

```
┌─────────────────────────────────────┐
│ What's on your mind?                │
│                                     │
│ [blinking cursor]                   │
│                                     │
│                                     │
├─────────────────────────────────────┤
│ [📷] [🎞️] [⚠️] [😀]        [Post] │
│  │    │    │    │                    │
│  │    │    │    └ Custom emoji picker│
│  │    │    └ Content warning toggle  │
│  │    └ GIF picker (Tenor search)   │
│  └ Image attach (gallery/camera)    │
└─────────────────────────────────────┘
```

---

## ONE-TAP ZAP FLOW (NIP-47 + NIP-57)

### Setup (Once)

1. User goes to Settings → Wallet
2. Scans QR code or pastes `nostr+walletconnect://` URI
3. Client extracts: wallet pubkey, relay URL, secret key
4. Stores in Android Keystore (encrypted)
5. User sets default zap amount (e.g., 21 sats)

### Zap Execution (Every Tap)

```
User taps ⚡ on note
    │
    ├─ 1. Client creates kind 9734 (zap request) event
    │     - Tags: target event id, target pubkey, relay hints
    │     - Signed with user's key
    │
    ├─ 2. Client sends GET to recipient's lnurl callback
    │     - Includes zap request as query param
    │     - Receives Lightning invoice back
    │
    ├─ 3. Client sends NWC `pay_invoice` (kind 23194)
    │     - Encrypted with NWC secret
    │     - Sent to NWC relay
    │
    ├─ 4. Wallet service receives, pays invoice
    │
    ├─ 5. Recipient's lnurl server publishes kind 9735 (zap receipt)
    │
    └─ 6. Client sees zap receipt, updates UI
         - ⚡ icon animates: amber fill on send, confirmed on receipt
```

### Long Press

Long press ⚡ → amount picker (preset buttons: 21, 100, 500, 1000, 5000, custom)

---

## FOLLOW FEED — HOW IT ACTUALLY WORKS

The follow feed is the most important feed in the app alongside relay feeds. Getting it right is critical.

### The Outbox Model (NIP-65)

You don't just connect to "some relays" and hope your follows' posts show up. That's the naive approach and it leads to missing posts. The correct flow:

```
1. Fetch user's kind 3 (contact list)
   → Gives you the list of pubkeys you follow
   
2. For each followed pubkey, fetch their kind 10002 (relay list metadata)
   → Tells you WHERE each person writes their posts
   → These are their "write relays" / "outbox relays"
   
3. Group followed pubkeys by their write relays
   → e.g., relay.damus.io serves 40 of your follows,
     nos.lol serves 25, etc.
   
4. Open connections to those write relays
   → Send REQ with authors filter for the pubkeys that write there
   → {"kinds": [1, 6, 20, 21], "authors": ["pubkey1", "pubkey2", ...]}
   
5. Events arrive → into Room → into feed
```

### Bootstrapping (New User / Cold Start)

When you don't yet have kind 10002 data for your follows:

1. Query **indexer relays** first: `wss://purplepag.es`, `wss://user.kindpag.es`, `wss://indexer.coracle.social`, `wss://cache2.primal.net`
2. These relays specialize in storing kind 0 (profiles) and kind 10002 (relay lists)
3. Batch request: `{"kinds": [10002], "authors": [all followed pubkeys]}`
4. Store results in `relay_list_metadata` Room table
5. NOW you have the routing table — connect to write relays and fetch posts

### Efficient Connection Management

If you follow 500 people across 80 different relays, you do NOT open 80 connections. Strategy:

1. **Rank relays by coverage** — relay.damus.io might serve 200 of your follows, nos.lol might serve 150. Start with the highest-coverage relays.
2. **Cap at 15 concurrent connections** — covers ~90% of follows for most users.
3. **Batch authors per relay** — one REQ per relay with all relevant pubkeys in the `authors` filter.
4. **Background refresh** — periodically re-fetch kind 10002 for follows (people change relays). Daily is fine.
5. **Fallback** — if a followed user has no kind 10002, try the relay hint from their `p` tag in the kind 3, or query indexer relays for their posts directly.

### "Follows" in the Relay Selector Dropdown

"Follows" appears as a special entry in the relay/set dropdown. It's not a relay set you manually configure — it's auto-generated from the outbox routing table. When selected, the app connects to followed users' write relays and shows their posts. The filter system (min reactions, min zaps, etc.) applies here too.

---

## GLOBAL FEED — MAKING THE FIREHOSE USABLE

Every client has a global feed. We must too. The problem: public relays are firehoses of spam. The solution: **our filter system is the answer.**

### How Global Works

Global is a **pre-configured relay set** that ships with the app. It connects to 3-4 large, reliable public relays and shows all events without an `authors` filter.

**Default global relays:**
```
wss://relay.damus.io     — largest, most popular relay on Nostr
wss://nos.lol            — large, reliable, well-maintained
wss://nostr.mom          — large public relay, good uptime
wss://relay.nostr.net    — public, reliable
```

**REQ construction (per relay):**
```json
{"kinds": [1, 6, 20, 21], "limit": 50}
```

No `authors` filter. That's what makes it "global."

### The Filter System IS the Spam Defense

Here's the key insight: Amethyst and Damus show you the raw firehose and it's unusable. We have engagement filters. The global feed ships with **aggressive default filters** that other feeds don't have:

| Filter | Global Default | Other Feeds Default |
|--------|---------------|-------------------|
| Content type | Notes Only | Notes Only |
| Min reactions | **3** | 0 |
| Min zaps | **0** | 0 |
| Min replies | **0** | 0 |
| Hide sensitive | **Off** | Off |

`minReactions: 3` alone kills ~95% of spam and bot posts — bots don't get reacted to by real humans. Users can slide it down to 0 for the raw feed, or crank it up to 10+ for only the most popular content.

### Deduplication

Same event from multiple relays → Room uses `event.id` as primary key → one row. If `relay.damus.io` and `nos.lol` both send the same note, it's stored once. The `relayUrl` field tracks which relay delivered it first.

### "Global" in the Relay Selector Dropdown

"Global" appears as a built-in entry alongside "Follows" and user-created relay sets. It cannot be deleted but the relay list inside it IS editable — users can add/remove relays from the global set in Settings. The key difference from other relay sets: it ships with `minReactions: 3` as the default filter.

### Why Not WoT for Global Spam Filtering?

NIP-85 Web of Trust would be the ideal spam defense — only show posts from people within N degrees of your social graph. But WoT is v2+ complexity. The engagement filter achieves 80% of the benefit with 0% of the WoT implementation cost. When we add WoT later, it layers on top of the engagement filter, not replaces it.

### First Launch Experience

1. App opens → Global is the default selected feed (user has no follows yet)
2. Global feed loads from the 4 default relays with `minReactions: 3`
3. User sees real, quality content immediately — not spam
4. Filter icon shows dot (indicating non-zero filter is active)
5. User can explore, follow people, create relay sets
6. Once they have follows, "Follows" feed becomes more useful than Global

---

## NOTIFICATIONS

The 🔔 tab in the bottom nav. Shows activity on YOUR content and direct interactions with you.

### What Triggers a Notification

| Type | How Detected | Display |
|------|-------------|---------|
| **Reply** | kind 1 with your event ID in `e` tags | "@user replied to your note" + reply preview |
| **Reaction** | kind 7 with your event ID in `e` tags | "@user reacted 🤙 to your note" |
| **Zap** | kind 9735 with your event/pubkey | "@user zapped ⚡ 1,000 sats on your note" |
| **Repost** | kind 6 referencing your event | "@user reposted your note" |
| **Mention** | kind 1 with your pubkey in `p` tags (not a reply to your event) | "@user mentioned you" + note preview |
| **New follower** | kind 3 with your pubkey in `p` tags | "@user followed you" |

### How It Works

1. Subscribe to relays with filter: `{"#p": ["<your_pubkey>"], "kinds": [1, 6, 7, 9735]}`
2. Also query for kind 3 events tagging your pubkey (for new followers — via indexer relays, batched, not real-time)
3. Incoming events → parse type → store in Room `notifications` table
4. Notification tab reads from Room, grouped by time (Today, Yesterday, This Week, Older)

### Notification Tab UI

Same minimalistic icon + count philosophy as the action bar. Notifications are grouped and show shortened previews.

**Display style per notification type:**
- **Reaction:** `🤙 @alice` → just avatar + name + emoji, no note preview (the reaction IS the content)
- **Zap:** `⚡ @bob  2,100 sats` → avatar + name + amount
- **Reply:** `💬 @carol` + truncated reply text (max 1 line, ellipsis) + reference to your original note (also truncated, dimmed)
- **Repost:** `🔁 @dave` + truncated 1-line preview of YOUR note that was reposted (dimmed text, like Amethyst)
- **Mention:** `@eve mentioned you` + truncated note preview (1 line)
- **Follow:** `👤 @frank followed you`

**Stacking:** Multiple reactions/zaps on the same note collapse into one row:
- `🤙 @alice, @bob, @carol +8 others reacted to your note` + truncated original note
- `⚡ @dave, @eve +3 others zapped 12,500 sats on your note` + truncated original note

```
┌─────────────────────────────────────┐
│ Notifications                       │
├─────────────────────────────────────┤
│                                     │
│ TODAY                               │
│ 🤙 alice, bob +3     your note text…│
│ ⚡ carol  2,100 sats  your note te… │
│ 💬 dave              "Great point…" │
│                       ↳ your note…  │
│                                     │
│ YESTERDAY                           │
│ 🔁 eve               your note te… │
│ 👤 frank followed you               │
│ ⚡ grace, hal  8,000  your note te… │
│                                     │
└─────────────────────────────────────┘
```

**Key UX rules:**
- No verbose text like "reacted to your note" — the icon IS the verb
- Stacked avatars (overlapping circles, max 3 shown + "+N" count)
- Your original note preview always in `#888888` secondary text, max 1 line, truncated
- Reply content in `#FFFFFF` primary text (it's the new content worth reading)
- Tap any notification → navigate to the original note in thread view

### Unread Badge

- Cyan dot on 🔔 icon when there are unseen notifications
- Tapping the Notifications tab clears the dot
- Optional: unread count badge (small number) on the icon

### Room Table

```kotlin
@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey val id: String,          // unique: derived from source event id + type
    val type: String,                    // "reply", "reaction", "zap", "repost", "mention", "follow"
    val sourceEventId: String?,          // the event that triggered the notification
    val targetEventId: String?,          // your event that was interacted with
    val actorPubkey: String,             // who did the action
    val content: String?,                // reaction emoji, reply preview, zap amount
    val amountMsats: Long?,             // for zaps
    val createdAt: Long,
    val seen: Boolean = false            // read/unread state
)
```

---

## FOLLOWER COUNT — WHY CLIENTS DISAGREE AND HOW TO FIX IT

### The Problem

"Followers" = people who have YOUR pubkey in their kind 3 contact list. But kind 3 events are scattered across thousands of relays. No single relay has all of them. So:

- **Amethyst** queries the relays you're connected to → sees subset → shows 2,400
- **Primal** runs a centralized cache scraping 200+ relays → sees more → shows 15,000
- **Damus** queries its own set → shows 8,500
- All three numbers are "correct" for the data each client can see

This is inherent to decentralized protocols. There is no canonical follower count.

### Our Approach: Best-Effort via NIP-45 + Indexers

**NIP-45** defines a `COUNT` verb — instead of downloading thousands of kind 3 events just to count them, you ask the relay to count for you:

```json
["COUNT", "followers", {"kinds": [3], "#p": ["<target_pubkey>"]}]
```

Response: `["COUNT", "followers", {"count": 12500}]`

Some relays return approximate counts (probabilistic/HyperLogLog) which is fine — follower counts are vanity metrics anyway.

**Implementation strategy:**

1. Send NIP-45 COUNT to multiple indexer relays:
   - `wss://purplepag.es` (profile/relay list indexer)
   - `wss://user.kindpag.es` (kind-specific indexer)
   - `wss://indexer.coracle.social` (Coracle's indexer)
   - `wss://cache2.primal.net` (Primal's cache)
2. Take the **highest count** returned (larger dataset = more complete)
3. Display as approximate: "~12.5k followers"
4. Cache in Room, refresh daily
5. If no relay supports NIP-45, fall back to: don't show a count, or show "—"

**Never show a precise number.** It's misleading. Always prefix with ~ or say "about."

### Following Count (Easy)

The count of people YOU follow is trivially accurate — just count the `p` tags in your own kind 3 event. This is always exact.

### Room Table for Follower Stats

```kotlin
@Entity(tableName = "user_stats")
data class UserStatsEntity(
    @PrimaryKey val pubkey: String,
    val followerCount: Long?,         // approximate, from NIP-45
    val followingCount: Int?,          // exact, from kind 3 p-tag count
    val noteCount: Long?,             // approximate
    val zapTotal: Long?,              // total sats received, approximate
    val lastFetched: Long             // when we last queried this
)
```

---

## ROOM DATABASE SCHEMA

```kotlin
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,          // event id (hex)
    val pubkey: String,                  // author pubkey
    val kind: Int,                       // event kind
    val content: String,                 // event content
    val createdAt: Long,                 // unix timestamp
    val tags: String,                    // JSON-serialized tags array
    val sig: String,                     // signature
    val relayUrl: String,                // which relay sent this
    val replyToId: String? = null,       // parsed from e tags (NIP-10 reply)
    val rootId: String? = null,          // parsed from e tags (NIP-10 root)
    val hasContentWarning: Boolean = false, // NIP-36 content-warning tag present
    val contentWarningReason: String? = null, // optional CW reason text
    val cachedAt: Long                   // when we stored it locally
)

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val pubkey: String,
    val name: String?,
    val displayName: String?,
    val about: String?,
    val picture: String?,               // avatar URL
    val nip05: String?,
    val lud16: String?,                 // lightning address for zaps
    val banner: String?,
    val updatedAt: Long
)

@Entity(tableName = "relay_sets")
data class RelaySetEntity(
    @PrimaryKey val id: String,         // UUID or "global"/"follows" for built-ins
    val name: String,                   // "Global", "Follows", "Bitcoin", etc.
    val relayUrls: String,              // JSON array of URLs
    val isDefault: Boolean = false,     // currently selected feed
    val isBuiltIn: Boolean = false,     // true for Global + Follows — can edit but not delete
    val filterJson: String? = null      // serialized FeedFilter (Global defaults to minReactions=3)
)

@Entity(tableName = "relay_info")
data class RelayInfoEntity(
    @PrimaryKey val url: String,
    val name: String?,
    val description: String?,
    val supportedNips: String?,         // JSON array of ints
    val software: String?,
    val version: String?,
    val lastChecked: Long
)

@Entity(tableName = "reactions")
data class ReactionEntity(
    @PrimaryKey val eventId: String,    // the reaction event id
    val targetEventId: String,          // what was reacted to
    val pubkey: String,
    val content: String,                // "+", "-", emoji
    val createdAt: Long
)

@Entity(tableName = "zap_receipts")
data class ZapReceiptEntity(
    @PrimaryKey val eventId: String,    // kind 9735 event id
    val targetEventId: String?,         // what was zapped
    val senderPubkey: String?,
    val recipientPubkey: String,
    val amountMsats: Long,              // parsed from bolt11
    val createdAt: Long
)

@Entity(tableName = "relay_list_metadata")
data class RelayListEntity(
    @PrimaryKey val pubkey: String,
    val writeRelays: String,            // JSON array
    val readRelays: String,             // JSON array
    val updatedAt: Long
)

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val id: String,         // UUID
    val kind: Int,                      // target event kind (1, 20, etc.)
    val content: String,                // draft content
    val tags: String,                   // JSON-serialized tags (reply refs, emoji, CW, etc.)
    val replyToId: String? = null,      // if replying to a note
    val relaySetId: String? = null,     // which relay set to publish to
    val hasContentWarning: Boolean = false,
    val contentWarningReason: String? = null,
    val createdAt: Long,                // when draft was created
    val updatedAt: Long                 // last edit timestamp
)
```

---

## SPRINT PLAN

### Sprint 1 (Week 1-2): "Show me a relay feed"

**Goal:** Connect to one relay, display kind 1 events from Room.

- [ ] New Android Studio project: `com.unsilence.app`, Kotlin, Compose, Material 3
- [ ] Add dependencies: Quartz, Room, Hilt, Coil, OkHttp
- [ ] AMOLED theme from line 1 (`#000000` bg, `#00E5FF` cyan accent)
- [ ] Room database with events, users tables + DAOs
- [ ] Relay connection service wrapping Quartz `NostrClient`
- [ ] Event processor: validate → parse NIP-10 threading → detect NIP-36 content-warning → check NIP-40 expiration → insert Room
- [ ] NIP-40: skip inserting events with `expiration` tag in the past; periodically prune expired events from Room
- [ ] Profile metadata fetcher (kind 0) → users table
- [ ] Feed screen: `LazyColumn` reading events from Room via `Flow`
- [ ] Basic note card: avatar, name, time, content, placeholder action bar
- [ ] Pubkey-derived identicon fallback avatars: unique pattern + unique color per pubkey, full color spectrum, generated locally from hash
- [ ] Global feed as default on first launch: 4 hardcoded relays, `minReactions: 3` default filter
- [ ] Default global relays: `wss://relay.damus.io`, `wss://nos.lol`, `wss://nostr.mom`, `wss://relay.nostr.net`

**Done when:** App launches, connects to global relays, shows scrolling feed of text notes with author info, filtered to posts with 3+ reactions. All data persists in Room across app restarts.

### Sprint 2 (Week 3-4): "Relay sets + kind 20 pictures"

**Goal:** Multiple relay feeds, relay set CRUD, picture rendering.

- [ ] relay_sets + relay_info tables in Room
- [ ] Relay set management: create, edit, delete, set default
- [ ] Relay dropdown selector: switch between sets and individual relays (see UI wireframe)
- [ ] NIP-11 relay info fetch + display (relay detail screen)
- [ ] NIP-42 relay authentication: handle AUTH challenges from relays via Quartz `RelayAuthenticator`
- [ ] Kind 20 picture rendering: parse `imeta` tags, image gallery, blurhash
- [ ] NIP-36 content warning: blur overlay on flagged events, tap to reveal, settings toggle
- [ ] Kind 6 repost rendering
- [ ] Pull-to-refresh
- [ ] Chronological ordering (no algorithm)
- [ ] 4-tab bottom nav: Home | Notifications | Profile | Settings
- [ ] Immersive scroll: top bar + bottom nav hide on scroll down, reappear on scroll up (NestedScrollConnection)
- [ ] Single-row top bar: relay dropdown + search + filter + compose icons

**Done when:** User can create relay sets, switch between them, see text notes and picture posts from different relays.

### Sprint 3 (Week 5-6): "Post, reply, react, filter"

**Goal:** Full interaction + the feed filter system.

- [ ] Key management: generate keypair OR import nsec
- [ ] NIP-55 Amber signer integration (via Quartz `NostrSignerExternal`)
- [ ] Compose new note screen → publish kind 1 to write relays
- [ ] Compose: auto-save to local Room drafts table (debounced, 2s after last keystroke)
- [ ] Drafts list accessible from compose screen (resume/delete old drafts)
- [ ] Compose: option to mark as sensitive (NIP-36 content-warning tag + optional reason)
- [ ] Compose: image attach from gallery/camera → upload to Blossom server → URL in content + `imeta` tag
- [ ] Compose: GIF attach from gallery → same Blossom upload flow
- [ ] Compose: paste image/GIF URL → inline preview in compose
- [ ] Blossom server config in settings (default: `blossom.nostr.build`, user can add own)
- [ ] **Post countdown preview:** after hitting Post, show rendered preview of note as it will appear in feed with 5-second countdown. Tap "Cancel" to abort, countdown finishes → event published. (See Post Countdown Preview spec)
- [ ] Reply to notes (NIP-10 root + reply `e` tags)
- [ ] NIP-09 event deletion: long-press own note → delete option → publish kind 5 with `e` tag → remove from local Room
- [ ] Handle incoming kind 5 deletion requests: hide/remove referenced events from feed
- [ ] Reactions table in Room + kind 7 processing
- [ ] Reaction counts on note cards
- [ ] Custom emoji reactions: render `:shortcode:` in kind 7 content as inline images (NIP-30)
- [ ] Reaction display: show top 3-4 distinct emoji with counts on each note
- [ ] NIP-30 content emojify: parse `:shortcode:` in kind 1 content + kind 0 profiles → inline images
- [ ] Display reply threads (tap note → thread view)
- [ ] **Feed filter system:**
  - [ ] ContentType enum (NOTES_ONLY, ALL, REPLIES_ONLY) — default: NOTES_ONLY
  - [ ] Filter model (content type, kinds, min reactions, min zaps, min replies)
  - [ ] Room query with content type WHERE clause + engagement joins + HAVING thresholds
  - [ ] Filter bottom sheet UI (Notes/All/Replies segmented button + kind chips + sliders)
  - [ ] Filter icon with active indicator (dot when non-default)
  - [ ] Filters persist per relay set

**Done when:** User can post, reply, react, and filter any relay feed by engagement thresholds and content type.

### Sprint 4 (Week 7-8): "One-tap zaps"

**Goal:** NWC pairing + full zap flow.

- [ ] NWC setup screen: QR scanner + paste URI + `nostr+walletconnect://` deeplink
- [ ] NWC connection stored in Android Keystore
- [ ] Zap button (⚡) on every note card
- [ ] Zap flow: kind 9734 → lnurl invoice → NWC pay_invoice → kind 9735 receipt
- [ ] zap_receipts table in Room
- [ ] Zap total display on notes (⚡ 21k sats)
- [ ] Zap animation: amber fill on send, confirm on receipt
- [ ] Default zap amount in settings
- [ ] Long-press ⚡ for custom amount picker
- [ ] NIP-57 zap receipt validation (verify bolt11 description hash)

**Done when:** User pairs wallet once, then one-tap zaps any note. Zap amounts display on all notes. Filter by zap amount works.

### Sprint 5 (Week 9-10): "NIP-65 outbox + follows feed + follower counts"

**Goal:** Proper follow feed via outbox model + accurate-as-possible follower counts.

**Follow Feed (see FOLLOW FEED section for full architecture):**
- [ ] relay_list_metadata table in Room
- [ ] Bootstrap: batch-fetch kind 10002 from indexer relays (`purplepag.es`, `user.kindpag.es`, `indexer.coracle.social`, `cache2.primal.net`)
- [ ] Build routing table in Room: pubkey → write relay URLs
- [ ] Rank relays by follow coverage, connect to top relays first
- [ ] "Follows" entry in relay dropdown: auto-populated from outbox routing table
- [ ] Batch REQ per relay: `{"kinds": [1, 6, 20, 21], "authors": [pubkeys for that relay]}`
- [ ] Smart connection management: connect on demand, disconnect after 60s idle, max 15 concurrent
- [ ] Exponential backoff for failing relays
- [ ] Fallback for follows with no kind 10002: use relay hint from kind 3 `p` tag, or query indexers
- [ ] Background refresh of kind 10002 data (daily)

**Follow/Unfollow:**
- [ ] NIP-02 follow/unfollow (publish updated kind 3 with full `p` tag list)
- [ ] Publish user's own kind 10002 relay list
- [ ] Following count: exact, from own kind 3 `p` tag count

**Follower Counts (see FOLLOWER COUNT section for full architecture):**
- [ ] user_stats table in Room
- [ ] NIP-45 COUNT query to indexer relays: `{"kinds": [3], "#p": ["<pubkey>"]}`
- [ ] Query multiple indexers, take highest count
- [ ] Display as approximate: "~12.5k followers"
- [ ] Cache in Room, refresh daily

**Video:**
- [ ] Kind 21 video rendering: thumbnail + tap-to-play (single ExoPlayer instance)

**Notifications (see NOTIFICATIONS section for full spec):**
- [ ] notifications table in Room
- [ ] Subscribe to relays with `{"#p": ["<your_pubkey>"], "kinds": [1, 6, 7, 9735]}`
- [ ] Parse incoming events into notification types (reply, reaction, zap, repost, mention)
- [ ] Notification screen: grouped by time (Today, Yesterday, This Week, Older)
- [ ] Unread badge: cyan dot on 🔔 icon, clears on tap
- [ ] New follower detection via indexer relay queries (batched, not real-time)

**Done when:** Follows feed shows posts from all followed users via their declared write relays. Profile screens show approximate follower counts. Follow/unfollow works. Video notes play on tap. Notifications tab shows replies, reactions, zaps, reposts, mentions, and new followers.

### Sprint 6 (Week 11-12): "Polish + ship"

**Goal:** Production quality.

- [ ] NIP-05 verification badges
- [ ] NIP-19 clickable links (npub, note, nevent, nprofile → navigate)
- [ ] NIP-27 `nostr:` URI rendering in content
- [ ] Link previews (OpenGraph fetch)
- [ ] User profile screen: avatar, banner, bio, notes tab, zaps tab
- [ ] Custom emoji compose: fetch user's kind 10030 emoji list, show picker in compose screen, autocomplete on `:` typing
- [ ] Custom emoji reactions: when reacting, show user's custom emoji alongside standard emoji
- [ ] GIF picker in compose: Tenor API integration, search + tap to insert URL (see Media Upload & GIFs spec)
- [ ] Search (NIP-50 on supporting relays, or local Room search)
- [ ] Settings: relay management, zap defaults, key export, theme (always dark lol)
- [ ] New note indicator (cyan dot on Home tab when scrolled down, no banners — see New Posts Behavior spec)
- [ ] Image fullscreen viewer with pinch-zoom
- [ ] Performance profiling pass (Android Studio profiler, fix jank)
- [ ] Crash reporting (Sentry or Firebase Crashlytics)
- [ ] APK signing + release build
- [ ] README + screenshots

**Done when:** App is stable, fast, beautiful. Ready for sideload distribution or F-Droid.

---

## UI WIREFRAME

### Immersive Scroll — Single Top Row

One row of chrome. Top bar and bottom nav both hide on scroll down, reappear on scroll up. Maximum content area.

```
┌─────────────────────────────────────┐
│ [Global ▾]           [🔍] [▽] [✎]  │  ← Single row: relay dropdown + actions
├─────────────────────────────────────┤
│                                     │
│  ◆ @jack · 2m                      │
│  Lightning is the future of...      │
│  💬 3  🔁 2  🤙 12  ⚡ 21k       │
│  ─────────────────────────────────  │
│                                     │
│  [════════════════════════]         │  ← Kind 20: image aligned with text
│  [════════ IMAGE ═════════]         │
│  [════════════════════════]         │
│  📸 @photographer · 15m             │
│  Sunset over the mountains          │
│  💬 8  🔁 5  🤙 45  ⚡ 50k       │
│  ─────────────────────────────────  │
│                                     │
│  [▶ VIDEO THUMBNAIL ══════]         │  ← Kind 21: tap to play (aligned with text)
│  🎬 @creator · 1h                   │
│  New dev tutorial                   │
│  💬 20  🔁 10  🤙 100  ⚡ 100k      │
│                                     │
├─────────────────────────────────────┤
│  [🏠 Home] [🔔 Notif] [👤 Profile] [⚙] │  ← Bottom nav (hides on scroll)
└─────────────────────────────────────┘

  ↓ SCROLLED DOWN — fully immersive ↓

┌─────────────────────────────────────┐
│                                     │  ← Top bar hidden
│  ◆ @jack · 2m                      │
│  Lightning is the future of...      │
│  💬 3  🔁 2  🤙 12  ⚡ 21k       │
│  ─────────────────────────────────  │
│  ...                                │
│                                     │  ← Bottom nav hidden
└─────────────────────────────────────┘
```

### Top Row Elements

**Relay/Set Dropdown (left side):**
- Shows current relay set name or single relay name with ▾ chevron
- Tap opens a dropdown/bottom sheet:

```
┌─────────────────────────────────────┐
│ Select Feed                    [✕]  │
│                                     │
│ BUILT-IN                            │
│  ● Global                           │  ← Currently selected (cyan dot)
│  ○ Follows                          │  ← (appears after login)
│                                     │
│ MY RELAY SETS                       │
│  ○ Bitcoin                          │
│  ○ Dev                              │
│  [+ New Set]                        │  ← Create new relay set
│                                     │
│ INDIVIDUAL RELAYS                   │
│  ○ wss://relay.damus.io             │
│  ○ wss://nos.lol                    │
│  [+ Add Relay]                      │  ← Add relay by URL
│                                     │
│ [Manage Sets & Relays →]            │  ← Opens full settings screen
└─────────────────────────────────────┘
```

**Action icons (right side):**
- 🔍 Search
- ▽ Filter (dot indicator when active)
- ✎ Compose new note

### Immersive Scroll Behavior

Implemented via `NestedScrollConnection` in Compose:
- **Scroll down:** Top bar slides up off screen, bottom nav slides down off screen. Content gets full viewport.
- **Scroll up (any amount):** Both bars slide back in immediately.
- **Fling to top:** Bars reappear.
- **FAB behavior:** The ✎ compose button lives in the top bar, so it hides with it. This is intentional — when you're deep in scrolling/reading, the compose action is out of the way. Scroll up slightly and it's back.

```kotlin
// Implementation hint for Claude Code
val topBarState = rememberTopBarState() // custom state
val bottomBarState = rememberBottomBarState()

val nestedScrollConnection = remember {
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Positive delta = scrolling down = hide bars
            // Negative delta = scrolling up = show bars
            topBarState.onScroll(available.y)
            bottomBarState.onScroll(available.y)
            return Offset.Zero // don't consume, let content scroll
        }
    }
}
```

### New Posts Behavior

**No banners. No "X new notes" popups. Ever.**

Two modes based on scroll position:

**User is at the top of the feed (viewing latest):**
- New posts auto-insert at the top in real-time
- Smooth animation — new card slides in, existing content pushes down
- No interruption, no tap required

**User is scrolled down (reading older content):**
- New posts are buffered silently (stored in Room, not shown yet)
- Cyan dot appears on the 🏠 Home icon in bottom nav
- Tapping Home while already on Home scrolls to top AND inserts buffered posts
- Dot disappears once user reaches top

```kotlin
// Implementation hint for Claude Code
val listState = rememberLazyListState()
val isAtTop = remember {
    derivedStateOf { listState.firstVisibleItemIndex == 0 
        && listState.firstVisibleItemScrollOffset == 0 }
}

// In ViewModel
if (isAtTop.value) {
    // Insert new events directly into feed flow
} else {
    // Buffer in pendingEvents, show dot on nav
    _hasNewPosts.value = true
}
```

### Filter Bottom Sheet (on ▽ tap)

```
┌─────────────────────────────────────┐
│ Filter Feed                    [✕]  │
│                                     │
│ [● Notes] [ All ] [ Replies ]       │  ← Segmented button (default: Notes)
│                                     │
│ Show:  [Text ✓] [Pics ✓] [Vid ✓]   │  ← Kind toggle chips
│        [Reposts ✓]                  │
│                                     │
│ Hide sensitive content     [  ○  ]  │  ← Toggle (default: off)
│                                     │
│ Min reactions:  ───●──────── 5      │  ← Slider
│ Min zaps:       ──●───────── 100    │  ← Slider (sats)
│ Min replies:    ●──────────── 0     │  ← Slider
│                                     │
│ [Apply]                             │
└─────────────────────────────────────┘
```

---

## RULES FOR CLAUDE CODE

1. **Read this file first.** Every session.
2. **UI reads from Room, never from network directly.** If you're tempted to pass relay data straight to a composable, stop.
3. **No autoplay video in v1.** Kind 21 = thumbnail + tap to play. Kind 22 = not implemented.
4. **Test on real relays.** Global defaults: `wss://relay.damus.io`, `wss://nos.lol`, `wss://nostr.mom`, `wss://relay.nostr.net`. Indexer relays: `wss://purplepag.es`, `wss://user.kindpag.es`, `wss://indexer.coracle.social`, `wss://cache2.primal.net`.
5. **AMOLED black + cyan everywhere.** `Color(0xFF000000)` backgrounds, `Color(0xFF00E5FF)` accents. If you're using `MaterialTheme.colorScheme.surface` make sure it's mapped to pure black. Only use warm amber (`#FFAB00`) for zap-specific elements.
6. **Don't over-engineer relay connections.** Start simple: connect when needed, disconnect after 60s idle, max 15 concurrent. Backoff on failure. That's it.
7. **All git from aivii.** You (Claude Code running as `android-dev`) cannot push. Stage changes, tell the human to commit.
8. **Update this file** when architecture decisions change, sprints complete, or new learnings emerge.

---

## THE ONE RULE

**If it doesn't help the user browse relay feeds, filter content, or zap someone, it doesn't go in v1.**
