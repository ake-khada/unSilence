# Sprint 21: Inline Video Autoplay — Design Spec

> **For Claude Code:** Read this spec before implementing. Check actual source files
> before making changes.

---

## Goal

Implement inline video autoplay in FeedScreen with a single shared ExoPlayer,
scroll-based activation, global mute toggle, fullscreen dialog, and fix the
double-rendering bug where video URLs render in multiple components.

---

## Scope

- **In scope:** FeedScreen only. Double-rendering fix (applies globally in NoteCard).
- **Out of scope:** UserProfileScreen video autoplay (future sprint, same pattern).

---

## Codebase Constraints

- Hilt DI only (KSP, not kapt)
- Kotlin 2.3.0, Media3 1.5.1, Compose BOM 2025.05.00, compileSdk/targetSdk 36
- Never run `./gradlew` from terminal while Android Studio is open
- Git operations from `aivii` user terminal only
- Architecture: Relay → EventProcessor → Room → Flow/StateFlow → Compose UI

---

## 1. Double-Rendering Fix & Media Deduplication

**Problem:** Video URLs render in multiple components — primarily as a link card
(from `LINK_URL_REGEX`) AND as VideoThumbnailCard. Imeta image/video sets are
already disjoint by mime type, so the real overlap is between regex-matched
video URLs and link URLs. The `imageUrls` dedup is defensive.

**Fix:** In NoteCard's `MediaExtraction` `remember` block, after all URL
extraction (regex + imeta merge):

1. Collect final `videoUrls` set
2. Remove all `videoUrls` from `imageUrls` (defensive — unlikely overlap)
3. Remove all `videoUrls` from `linkUrls` (primary fix)
4. Each URL renders in exactly one component

This is a `removeAll` at the end of the extraction block. No new classes needed.

---

## 2. Shared ExoPlayer & Scroll-Based Activation

### FeedScreen Owns

- `exoPlayer: ExoPlayer` — single instance via `remember` using
  `LocalContext.current`, released in
  `DisposableEffect(Unit) { onDispose { exoPlayer.release() } }`.
  `repeatMode = ExoPlayer.REPEAT_MODE_ALL` for looping.
- `activeVideoNoteId: String?` — ID of the note whose video is currently playing
- `isMuted: Boolean` — global mute state, default `true`. Once user unmutes,
  all subsequent videos play with sound until they mute again.
- `showFullscreenVideo: Boolean` — whether fullscreen dialog is showing
- `preFullscreenMuted: Boolean` — saved mute state before entering fullscreen,
  restored on dismiss

### Video-Item Precomputation

A `remember(feedItems)` block scans feed items to build a `Set<String>` of note
IDs that have video:

```kotlin
val noteIdsWithVideo = remember(feedItems) {
    feedItems.filter { row ->
        row.kind != 30023 &&  // exclude articles (ArticleCard, not NoteCard)
        (ImetaParser.videos(row.tags).isNotEmpty() ||
            VIDEO_URL_REGEX.containsMatchIn(row.content))
    }.map { it.id }.toSet()
}
```

This set is used by scroll detection to identify which visible items are video
candidates. Kind-30023 articles are excluded since they render as ArticleCard,
not NoteCard, and would never display InlineAutoPlayVideo.

### Scroll Detection

`LaunchedEffect` with `snapshotFlow` on `listState.layoutInfo.visibleItemsInfo`:

- Match visible items to notes via the `key` property of `LazyListItemInfo`
  (FeedScreen uses `key = { it.id }` for list items)
- Filter to items whose key is in `noteIdsWithVideo`
- Find the video item closest to viewport center
- Update `activeVideoNoteId` via `distinctUntilChanged`
- If no video items are visible, set `activeVideoNoteId = null` (stops playback)
- When `showFullscreenVideo` is true, skip detection (don't change active video
  while watching fullscreen)

### NoteCard Parameters (new)

```kotlin
exoPlayer: ExoPlayer?        // null when video not supported
isActiveVideo: Boolean        // this note's video is the active one
isMuted: Boolean              // global mute state
onToggleMute: () -> Unit      // toggle global mute
onOpenFullscreen: () -> Unit  // request fullscreen for this video
```

NoteCard renders `InlineAutoPlayVideo` when `isActiveVideo == true`, or
`VideoThumbnailCard` when `isActiveVideo == false` (for notes that have video).

**Multiple videos per note:** Only the first video URL is used for autoplay.
`videoUrls.firstOrNull()` is the autoplay candidate. Remaining video URLs render
as VideoThumbnailCards below (existing behavior).

---

## 3. InlineAutoPlayVideo Composable

New file: `app/src/main/kotlin/com/unsilence/app/ui/feed/InlineAutoPlayVideo.kt`

### Parameters

```kotlin
fun InlineAutoPlayVideo(
    exoPlayer: ExoPlayer,
    videoUrl: String,
    aspectRatio: Float?,       // from imeta dim, null = 16:9 default
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
)
```

### Behavior

- `LaunchedEffect(videoUrl)` — sets media item, prepares, plays. Re-entry after
  scrolling away and back is handled automatically: NoteCard removes
  InlineAutoPlayVideo from composition when inactive and re-adds it when active
  again, which re-triggers the LaunchedEffect.
- Apply mute: `LaunchedEffect(isMuted) { exoPlayer.volume = if (isMuted) 0f else 1f }`
- Render via `AndroidView` wrapping Media3 `PlayerView` with
  `useController = false`
- Aspect ratio from imeta `dim` if available, else 16:9
- Min height 120dp, max height 300dp

### Overlay

- Mute/unmute icon button — top-right corner, semi-transparent background
- Tap anywhere else → `onOpenFullscreen()`

---

## 4. VideoThumbnailCard Improvements

Existing composable in NoteCard.kt. Changes:

- **Poster frame:** If imeta has an image URL associated with the video (or a
  `thumb` field in imeta), show that image as the thumbnail instead of black
- **Fallback:** Dark placeholder with centered play icon (current behavior)
- **Sizing:** Keep existing dim-based aspect ratio + 120-300dp height capping
- **Tap:** Calls `onOpenFullscreen()` — FeedScreen makes the video active and
  opens fullscreen

---

## 5. FullScreenVideoDialog

Can live in NoteCard.kt or a separate file.

### Parameters

```kotlin
fun FullScreenVideoDialog(
    exoPlayer: ExoPlayer,
    onDismiss: () -> Unit,
)
```

### Behavior

- `Dialog` with `usePlatformDefaultWidth = false`, full-screen black background
- `PlayerView` with `useController = true` (progress bar, play/pause, seek)
- **Unmuted in fullscreen** — FeedScreen saves `preFullscreenMuted` state before
  opening, sets volume to 1f. On dismiss, restores the saved mute state.
- **No volume controls in PlayerView** — hide Media3's default volume button to
  prevent player/app mute state desync. Use
  `playerView.setShowVolumeButton(false)`.
- Close button top-left (same pattern as ArticleReaderScreen)
- On dismiss: playback continues inline at same position (same ExoPlayer
  instance, no seek needed)

---

## 6. Integration Flow

```
User scrolls feed
  → snapshotFlow detects video note closest to center
  → activeVideoNoteId updated
  → NoteCard with matching ID receives isActiveVideo=true
  → InlineAutoPlayVideo sets media item, prepares, plays muted
  → Previous active video's NoteCard gets isActiveVideo=false
  → That NoteCard switches to VideoThumbnailCard

User taps mute toggle
  → isMuted flipped globally
  → All future videos play with new mute state

User taps video
  → FeedScreen saves preFullscreenMuted, unmutes, shows FullScreenVideoDialog
  → Same ExoPlayer, same position, now with controls + sound

User dismisses fullscreen
  → Restores mute to preFullscreenMuted
  → Playback continues inline at same timestamp
```

---

## Files Modified

- `NoteCard.kt` — deduplication in MediaExtraction, new params (exoPlayer,
  isActiveVideo, isMuted, onToggleMute, onOpenFullscreen), conditional rendering
  of InlineAutoPlayVideo vs VideoThumbnailCard, poster frame in
  VideoThumbnailCard, FullScreenVideoDialog
- `FeedScreen.kt` — shared ExoPlayer, activeVideoNoteId, isMuted,
  preFullscreenMuted, showFullscreenVideo, video-item precomputation, scroll
  detection, pass new params to NoteCard
- **New:** `InlineAutoPlayVideo.kt` — autoplay composable with PlayerView

---

## Key Risks

1. **Scroll performance:** Use `distinctUntilChanged` on active video ID, not
   full layout info. Only compare indexes of video items.
2. **ExoPlayer lifecycle:** Must release on screen dispose. Single instance only.
3. **Video URL detection accuracy:** Use both ImetaParser.videos() and regex for
   precomputation to catch all cases.
4. **Config changes:** ExoPlayer in `remember` survives recomposition but not
   activity recreation. Acceptable for v1.
