# unSilence Sprint 20: Media & Content Fixes

> **For Claude Code:** Read this entire file before brainstorming. Always check
> ACTUAL source files before making changes. Sprints 18-19 already shipped.

---

## Codebase Constraints (non-negotiable)

- **DI:** Hilt only. No Koin. KSP, not kapt.
- **Events:** Quartz library types. Use `EventTemplate` constructor for JVM 17 compat.
- **Room migrations:** Index names MUST use `index_tablename_col1_col2` convention (with backticks in SQL). Every migration index MUST also be declared in `@Entity(indices=[...])` using vararg syntax: `Index("col1", "col2")`.
- **Versions:** Kotlin 2.3.0, KSP 2.3.0, Hilt 2.58, Room 2.7.1, AGP 8.9.1, Gradle 8.11.1, Compose BOM 2025.05.00, compileSdk/targetSdk 36.
- **Build:** Never run `./gradlew` from terminal while Android Studio is open.
- **Git:** Operations from `aivii` user terminal, not `android-dev`.
- **Architecture:** Relay → EventProcessor → Room → Flow/StateFlow → Compose UI.

---

## Bugs to Fix in Sprint 20

### A6. CreateRelaySet obscured behind status bar
- **Symptom:** CreateRelaySet screen content hidden behind system status bar
- **Root cause:** Missing WindowInsets handling
- **Fix:** Add `Modifier.statusBarsPadding()` or proper Scaffold/TopAppBar inset handling to the CreateRelaySet screen
- **This is a one-liner.** Find the screen, add the padding. Done.

### A7. Article (NIP-23) image renders below action bar + raw markdown
- **Symptom:** Long-form article content (kind 30023) shows raw markdown syntax and images in wrong position
- **Root cause:** No markdown renderer — content displays as plain text. Header image from `image` tag not parsed.
- **Fix:**
  1. Add a Compose-compatible markdown rendering library
  2. For kind-30023 events: parse the `image` tag for header image, render at top
  3. Render article `content` through markdown composable instead of plain `Text()`
  4. Support: headings, bold/italic, links (clickable), code blocks, inline code, images, blockquotes, lists
  5. Ensure article content renders BELOW the action bar, not behind it
- **Library recommendation:** `com.mikepenz:multiplatform-markdown-renderer-m3` (Compose-native, Material 3). Check if there's something lighter or already in dependencies first.

### A8. Videos from nostr.build show black — imeta tag parsing needed
- **Symptom:** Video links from nostr.build (and similar) show black rectangle instead of playing
- **Root cause:** Video URLs are in `imeta` tags (NIP-92) rather than plaintext in content. Media extraction only scans content for URLs.
- **Fix:**
  1. Parse `imeta` tags from the event's tags array. Format: `["imeta", "url https://...", "m video/mp4", "dim 1920x1080", "blurhash ..."]`
  2. Extract `url` and `m` (mime type) fields
  3. If mime type starts with `video/`, treat as video URL
  4. Feed video URLs to ExoPlayer alongside content-extracted URLs
  5. Also handle image URLs from imeta for proper display
  6. Use `dim` for placeholder sizing, `blurhash` for placeholder visuals if available
- **Also fix:** FeedScreen video detection currently maps ALL notes as video candidates (the TODO from the spec). Add actual `hasVideo(note)` check.

---

## Sprint 20 Task Breakdown

### Task 1: A6 — CreateRelaySet WindowInsets fix
- Find the CreateRelaySet screen composable
- Add proper status bar padding
- Smallest possible change

### Task 2: A8 — imeta Tag Parser (NIP-92)
- Create an `ImetaParser` utility that extracts structured media info from imeta tags
- Parse: `url`, `m` (mime type), `dim` (dimensions), `blurhash`, `x` (hash)
- Integrate into the existing media extraction/display pipeline
- Ensure videos from nostr.build play correctly via ExoPlayer
- Fix video detection in feed (add hasVideo check)

### Task 3: A7 — Markdown Renderer for NIP-23 Articles
- Add markdown rendering dependency
- Create an ArticleScreen or modify existing article display
- Parse `image` tag for header image
- Render content through markdown composable
- Ensure proper positioning below action bar

---

## Priority Order

A6 first (one-liner), then A8 (most impactful — videos are broken), then A7 (articles are less common but still visible).
