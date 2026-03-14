# unSilence Sprint 21: Inline Video Autoplay

> **For Claude Code:** Read this entire file before brainstorming. Always check
> ACTUAL source files before making changes. Sprints 18-20 already shipped.

---

## Codebase Constraints (non-negotiable)

- **DI:** Hilt only. No Koin. KSP, not kapt.
- **Events:** Quartz library types. Use `EventTemplate` constructor for JVM 17 compat.
- **Room migrations:** Index names MUST use `index_tablename_col1_col2` convention with backticks. Vararg syntax for @Index.
- **Versions:** Kotlin 2.3.0, KSP 2.3.0, Hilt 2.58, Room 2.7.1, AGP 8.9.1, Gradle 8.11.1, Compose BOM 2025.05.00, compileSdk/targetSdk 36.
- **Build:** Never run `./gradlew` from terminal while Android Studio is open.
- **Git:** Operations from `aivii` user terminal, not `android-dev`.
- **Architecture:** Relay → EventProcessor → Room → Flow/StateFlow → Compose UI.
- **Sprint 20 just shipped:** ImetaParser utility (NIP-92), markdown renderer for articles, MediaExtraction remember() optimization.

---

## Current Video State (broken)

1. **Double rendering:** Video URLs are matched by BOTH image regex and video regex (or rendered as both a link card and a VideoThumbnailCard). Results in a black box above the actual video content.
2. **No autoplay:** Videos show a static black `VideoThumbnailCard` with a play icon. Tap opens the video. No scroll-based autoplay.
3. **Black thumbnails:** `VideoThumbnailCard` shows a plain black box — no poster frame, no preview image.
4. **No fullscreen:** Limited or no fullscreen video dialog.

---

## Sprint 21 Goal

Implement inline video autoplay with a single shared ExoPlayer, scroll-based activation, muted default, and fullscreen dialog. Fix the double-rendering bug. Improve thumbnails.

---

## Design (from spec + architecture assessment)

### 1. Fix Double Rendering

Video URLs must be **excluded** from image URL lists and link URL lists. In NoteCard's media extraction:
- After extracting `videoUrls`, filter them OUT of `imageUrls` and `linkUrls`
- Only ONE component should render per video URL: either `InlineAutoPlayVideo` (if active) or `VideoThumbnailCard` (if not active)
- Check the current regex patterns — video extensions (.mp4, .mov, .webm, .m3u8) might also match the image regex

### 2. Shared ExoPlayer at Screen Level

In FeedScreen (and any other screen with video feeds):
```
val exoPlayer = remember {
    ExoPlayer.Builder(context).build().apply {
        repeatMode = ExoPlayer.REPEAT_MODE_ALL
    }
}
DisposableEffect(Unit) {
    onDispose { exoPlayer.release() }
}
```

Single instance, shared across all video items. Only the "active" video item gets the player.

### 3. Active Video Detection

Use `snapshotFlow` on `LazyListState` to find the video note closest to viewport center:

```
LaunchedEffect(listState) {
    snapshotFlow { listState.layoutInfo.visibleItemsInfo }
        .collect { visibleItems ->
            // Find the video item closest to viewport center
            val viewportCenter = (listState.layoutInfo.viewportStartOffset +
                listState.layoutInfo.viewportEndOffset) / 2
            
            // Filter to only items that have video
            // Find the one closest to center
            // Update activeVideoId
        }
}
```

The ViewModel (or screen-level state) tracks `activeVideoId: String?`.

### 4. InlineAutoPlayVideo Composable

For the active video item:
- Receives the shared `ExoPlayer` instance
- On becoming active: `exoPlayer.setMediaItem(MediaItem.fromUri(url))`, prepare, play muted
- On becoming inactive: `exoPlayer.playWhenReady = false`
- Mute/unmute toggle button (top-right, semi-transparent background)
- Tap anywhere → open fullscreen dialog
- Aspect ratio from imeta `dim` if available, else 16:9 default
- Minimum height 120dp, maximum 300dp

### 5. VideoThumbnailCard (non-active videos)

For videos that are NOT the active video:
- If imeta has a poster/thumbnail URL → show that image
- If no poster → show a dark placeholder with a play icon centered
- Show the dim-based aspect ratio if available
- Tap → becomes active (or opens fullscreen directly)

### 6. FullScreenVideoDialog

- Full-screen dialog with `usePlatformDefaultWidth = false`
- `PlayerView` with `useController = true` (shows progress bar, play/pause, seek)
- Volume unmuted in fullscreen
- Close button top-left
- On dismiss: return to muted inline playback if still in viewport

### 7. Screen Integration

- FeedScreen: shared ExoPlayer + activeVideoId + scroll detection + pass to NoteCard
- UserProfileScreen: same pattern (if videos appear in profiles)
- NoteCard: receives `exoPlayer`, `isActiveVideo`, renders either InlineAutoPlayVideo or VideoThumbnailCard

---

## Key Risks

1. **Scroll performance:** snapshotFlow on every frame can be expensive. Use `distinctUntilChanged` and only compare video item indexes, not full layout info.
2. **ExoPlayer lifecycle:** Must release on screen dispose. Must handle config changes (rotation).
3. **Memory:** Single ExoPlayer instance is critical — never create per-item players.
4. **Video URL detection:** Need a reliable `hasVideo(row)` check. Use ImetaParser + regex video URL detection.

---

## Files Likely Modified

- `NoteCard.kt` — video URL exclusion from images/links, accept exoPlayer + isActiveVideo params, render InlineAutoPlayVideo or VideoThumbnailCard conditionally
- `FeedScreen.kt` — shared ExoPlayer, activeVideoId state, scroll detection, pass to NoteCard
- `UserProfileScreen.kt` — same pattern as FeedScreen
- New: `InlineAutoPlayVideo.kt` — the autoplay composable
- Existing: `VideoThumbnailCard` in NoteCard.kt — improve with poster frame support
- New or modified: `FullScreenVideoDialog.kt` — fullscreen player
