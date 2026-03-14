# Sprint 21: Inline Video Autoplay — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development
> (if subagents available) or superpowers:executing-plans to implement this plan.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement inline video autoplay in FeedScreen with a single shared
ExoPlayer, scroll-based activation, global mute toggle, fullscreen dialog, and
fix the double-rendering bug.

**Architecture:** FeedScreen owns a single ExoPlayer instance and tracks which
video note is active via scroll detection. NoteCard receives the player and
renders either InlineAutoPlayVideo (active) or VideoThumbnailCard (inactive).
Fullscreen dialog reuses the same ExoPlayer at the same playback position.

**Tech Stack:** Kotlin 2.3.0, Jetpack Compose (BOM 2025.05.00), Media3/ExoPlayer
1.5.1, Hilt 2.58, Room 2.7.1

**Spec:** `docs/superpowers/specs/2026-03-13-sprint21-inline-video-autoplay-design.md`

---

## Chunk 1: Foundation (Tasks 1-3)

### Task 1: Add `thumb` field to ImetaParser

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/ImetaParser.kt`

- [ ] **Step 1: Add `thumb` field to `ImetaMedia` data class**

```kotlin
data class ImetaMedia(
    val url: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val thumb: String?,
)
```

- [ ] **Step 2: Pass `thumb` from kvMap in `parse()`**

In the `mapNotNull` block, after the `ImetaMedia(` constructor, add the thumb
field:

```kotlin
ImetaMedia(
    url = url,
    mimeType = kvMap["m"],
    width = w,
    height = h,
    thumb = kvMap["thumb"],
)
```

- [ ] **Step 3: Verify no compile errors**

Open the file in Android Studio and confirm no red underlines. Do NOT run
`./gradlew` from terminal.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/relay/ImetaParser.kt
git commit -m "feat: add thumb field to ImetaMedia for video poster frames"
```

---

### Task 2: Fix double-rendering — deduplicate video URLs from image/link lists

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt:192-208`

- [ ] **Step 1: Add deduplication after MediaExtraction construction**

Replace the current `MediaExtraction(...)` block (lines 202-208) with:

```kotlin
        val allVideoUrls = (regexVideoUrls + imetaVideoUrls).distinct()

        MediaExtraction(
            imageUrls   = (regexImageUrls + imetaImageUrls).distinct()
                              .filter { it !in allVideoUrls },
            videoUrls   = allVideoUrls,
            linkUrls    = LINK_URL_REGEX.findAll(afterVideos).map { it.value }.distinct().take(3).toList()
                              .filter { it !in allVideoUrls },
            textContent = LINK_URL_REGEX.replace(afterVideos, "").trim(),
        )
```

This ensures no video URL appears in imageUrls or linkUrls.

- [ ] **Step 2: Verify no compile errors in Android Studio**

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt
git commit -m "fix: deduplicate video URLs from image and link lists to prevent double rendering"
```

---

### Task 3: Make VIDEO_URL_REGEX accessible and add NoteCard video params

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt`

This task changes `VIDEO_URL_REGEX` from `private` to `internal` so FeedScreen
can use it for video precomputation. It also adds new parameters to `NoteCard`
for video autoplay support, without using them yet.

- [ ] **Step 1: Change VIDEO_URL_REGEX visibility**

Change line 109 from:
```kotlin
private val VIDEO_URL_REGEX = Regex(
```
to:
```kotlin
internal val VIDEO_URL_REGEX = Regex(
```

- [ ] **Step 2: Add video autoplay parameters to NoteCard**

Add new parameters after the existing `originalAuthorProfile` parameter
(line 163):

```kotlin
    originalAuthorProfile: UserEntity? = null,
    exoPlayer: ExoPlayer? = null,
    isActiveVideo: Boolean = false,
    isMuted: Boolean = true,
    onToggleMute: () -> Unit = {},
    onOpenFullscreen: () -> Unit = {},
```

All have defaults so existing call sites don't break.

- [ ] **Step 3: Verify no compile errors in Android Studio**

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt
git commit -m "feat: add video autoplay params to NoteCard, expose VIDEO_URL_REGEX"
```

---

## Chunk 2: InlineAutoPlayVideo & VideoThumbnailCard improvements (Tasks 4-5)

### Task 4: Create InlineAutoPlayVideo composable

**Files:**
- Create: `app/src/main/kotlin/com/unsilence/app/ui/feed/InlineAutoPlayVideo.kt`

- [ ] **Step 1: Create the file with full implementation**

```kotlin
package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.unsilence.app.ui.theme.Sizing

@Composable
fun InlineAutoPlayVideo(
    exoPlayer: ExoPlayer,
    videoUrl: String,
    aspectRatio: Float?,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Set up media and start playback when this composable enters composition.
    // Re-entry after scrolling away and back is handled automatically:
    // NoteCard removes this composable when inactive and re-adds when active,
    // which re-triggers the LaunchedEffect.
    LaunchedEffect(videoUrl) {
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Sync mute state reactively
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (aspectRatio != null && aspectRatio > 0f)
                    Modifier
                        .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
                        .defaultMinSize(minHeight = 120.dp)
                        .heightIn(max = 300.dp)
                else
                    Modifier.height(200.dp)
            )
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .clickable { onOpenFullscreen() },
    ) {
        // ExoPlayer surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            update = { view -> view.player = exoPlayer },
            modifier = Modifier.matchParentSize(),
        )

        // Mute toggle — top-right
        IconButton(
            onClick = onToggleMute,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(36.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape),
        ) {
            Icon(
                imageVector = if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
```

- [ ] **Step 2: Verify no compile errors in Android Studio**

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/InlineAutoPlayVideo.kt
git commit -m "feat: create InlineAutoPlayVideo composable with mute toggle"
```

---

### Task 5: Improve VideoThumbnailCard with poster frame and new callback

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt:627-655`

- [ ] **Step 1: Add posterUrl parameter and image rendering to VideoThumbnailCard**

Replace the current `VideoThumbnailCard` function (lines 626-655) with:

```kotlin
/** Tap-to-play placeholder shown for detected video URLs. */
@Composable
private fun VideoThumbnailCard(
    url: String,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    aspectRatio: Float? = null,
    posterUrl: String? = null,
) {
    Box(
        modifier          = modifier
            .fillMaxWidth()
            .then(
                if (aspectRatio != null && aspectRatio > 0f)
                    Modifier.aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
                        .defaultMinSize(minHeight = 120.dp)
                        .heightIn(max = 300.dp)
                else Modifier.height(180.dp)
            )
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(Color(0xFF1A1A1A))
            .clickable { onPlay() },
        contentAlignment  = Alignment.Center,
    ) {
        if (!posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Icon(
            imageVector        = Icons.Filled.PlayArrow,
            contentDescription = "Play video",
            tint               = Color.White.copy(alpha = 0.85f),
            modifier           = Modifier.size(52.dp),
        )
    }
}
```

Note: `ContentScale` is already imported at line 49.

- [ ] **Step 2: Update the VideoThumbnailCard call site to pass posterUrl**

Replace the video rendering block (lines 369-385) with:

```kotlin
        videoUrls.firstOrNull()?.let { url ->
            val videoMeta = imetaMedia.firstOrNull {
                it.url == url && it.width != null && it.height != null
            }
            val posterUrl = imetaMedia.firstOrNull { it.url == url }?.thumb

            if (isActiveVideo && exoPlayer != null && isDirectVideoUrl(url)) {
                InlineAutoPlayVideo(
                    exoPlayer       = exoPlayer,
                    videoUrl        = url,
                    aspectRatio     = if (videoMeta != null) videoMeta.width!!.toFloat() / videoMeta.height!! else null,
                    isMuted         = isMuted,
                    onToggleMute    = onToggleMute,
                    onOpenFullscreen = onOpenFullscreen,
                    modifier        = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                )
            } else {
                VideoThumbnailCard(
                    url         = url,
                    onPlay      = {
                        if (isDirectVideoUrl(url)) {
                            onOpenFullscreen()
                        } else {
                            runCatching { uriHandler.openUri(url) }
                        }
                    },
                    aspectRatio = if (videoMeta != null) videoMeta.width!!.toFloat() / videoMeta.height!! else null,
                    posterUrl   = posterUrl,
                    modifier    = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                )
            }
        }
```

This replaces the old `showVideoPlayer = url` logic with the new
`onOpenFullscreen()` callback for direct video URLs.

- [ ] **Step 3: Remove the old VideoPlayerScreen dialog and its state**

Delete the `showVideoPlayer` state variable (line 168):
```kotlin
    var showVideoPlayer   by remember { mutableStateOf<String?>(null) }
```

Delete the VideoPlayerScreen invocation (lines 508-510):
```kotlin
    showVideoPlayer?.let { url ->
        VideoPlayerScreen(url = url, onDismiss = { showVideoPlayer = null })
    }
```

Delete the entire `VideoPlayerScreen` composable (lines 657-693).

The `DisposableEffect`, `ExoPlayer.Builder`, `MediaItem` imports on lines 40,
69-71 should be kept — they're still used by the new video code or needed
by other imports. The `AndroidView` import (line 56) is also still needed
by InlineAutoPlayVideo (though it's in a different file, keep it for safety;
the compiler will flag unused imports if they truly aren't needed).

- [ ] **Step 4: Verify no compile errors in Android Studio**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt
git commit -m "feat: add poster frame to VideoThumbnailCard, wire InlineAutoPlayVideo, remove old VideoPlayerScreen"
```

---

## Chunk 3: FullScreenVideoDialog & FeedScreen integration (Tasks 6-7)

### Task 6: Create FullScreenVideoDialog

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt` (add at bottom, before helpers section)

- [ ] **Step 1: Add FullScreenVideoDialog composable**

Add this after the `VideoThumbnailCard` composable and before the
`EmbeddedQuoteCard` composable:

```kotlin
/** Full-screen video dialog reusing the shared ExoPlayer. */
@Composable
fun FullScreenVideoDialog(
    exoPlayer: ExoPlayer,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        setShowVolumeButton(false)
                    }
                },
                update = { view -> view.player = exoPlayer },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick  = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector        = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint               = Color.White,
                )
            }
        }
    }
}
```

Note: This is `fun` not `private fun` because FeedScreen needs to call it.

- [ ] **Step 2: Verify no compile errors in Android Studio**

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt
git commit -m "feat: add FullScreenVideoDialog with shared ExoPlayer"
```

---

### Task 7: Wire up FeedScreen — shared ExoPlayer, scroll detection, fullscreen

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/FeedScreen.kt`

This is the main integration task. FeedScreen gets a shared ExoPlayer, scroll-based
active video detection, global mute state, and fullscreen dialog.

- [ ] **Step 1: Add imports**

Add these imports after the existing imports:

```kotlin
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.media3.exoplayer.ExoPlayer
import com.unsilence.app.data.relay.ImetaParser
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
```

- [ ] **Step 2: Add shared ExoPlayer and video state inside FeedScreen**

After the `var articleRow` line (line 50), add:

```kotlin
    // ── Video autoplay state ─────────────────────────────────────────────────
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    var activeVideoNoteId by remember { mutableStateOf<String?>(null) }
    var isMuted by remember { mutableStateOf(true) }
    var showFullscreenVideo by remember { mutableStateOf(false) }
    var preFullscreenMuted by remember { mutableStateOf(true) }
```

- [ ] **Step 3: Add video precomputation inside the `else` branch (after state.events is non-empty)**

Inside the `else ->` branch (line 82), before the `LazyColumn`, add:

```kotlin
                // Precompute which notes have video for scroll detection
                val noteIdsWithVideo = remember(state.events) {
                    state.events.filter { row ->
                        row.kind != 30023 &&
                        (ImetaParser.videos(row.tags).isNotEmpty() ||
                            VIDEO_URL_REGEX.containsMatchIn(row.content))
                    }.map { it.id }.toSet()
                }
```

- [ ] **Step 4: Add scroll-based active video detection**

After the pagination `LaunchedEffect(Unit)` block (after line 143), add:

```kotlin
                // Keep a stable reference that the long-lived LaunchedEffect can read
                // without restarting when the set changes (e.g. after pagination).
                val noteIdsWithVideoState = rememberUpdatedState(noteIdsWithVideo)

                // Active video detection: find video note closest to viewport center
                LaunchedEffect(Unit) {
                    snapshotFlow { listState.layoutInfo }
                        .map { layoutInfo ->
                            if (showFullscreenVideo) return@map activeVideoNoteId
                            val currentIds = noteIdsWithVideoState.value
                            val viewportCenter = (layoutInfo.viewportStartOffset +
                                layoutInfo.viewportEndOffset) / 2
                            layoutInfo.visibleItemsInfo
                                .filter { (it.key as? String) in currentIds }
                                .minByOrNull {
                                    val itemCenter = it.offset + it.size / 2
                                    kotlin.math.abs(itemCenter - viewportCenter)
                                }
                                ?.key as? String
                        }
                        .distinctUntilChanged()
                        .collect { newActiveId ->
                            if (activeVideoNoteId != newActiveId) {
                                activeVideoNoteId = newActiveId
                                if (newActiveId == null) {
                                    exoPlayer.playWhenReady = false
                                }
                            }
                        }
                }
```

- [ ] **Step 5: Pass video params to NoteCard**

Update the `NoteCard(...)` call (lines 103-117) to add the video parameters:

```kotlin
                            NoteCard(
                                row                    = row,
                                onNoteClick            = onNoteClick,
                                onAuthorClick          = onAuthorClick,
                                hasReacted             = row.engagementId in reactedIds,
                                hasReposted            = row.engagementId in repostedIds,
                                hasZapped              = row.engagementId in zappedIds,
                                isNwcConfigured        = isNwcConfigured,
                                originalAuthorProfile  = originalAuthorProfile,
                                onReact                = { actionsViewModel.react(row.id, row.pubkey) },
                                onRepost               = { actionsViewModel.repost(row.id, row.pubkey, row.relayUrl) },
                                onQuote                = onQuote,
                                onZap                  = { amt -> actionsViewModel.zap(row.id, row.pubkey, row.relayUrl, amt) },
                                onSaveNwcUri           = { uri -> actionsViewModel.saveNwcUri(uri) },
                                exoPlayer              = exoPlayer,
                                isActiveVideo          = row.id == activeVideoNoteId,
                                isMuted                = isMuted,
                                onToggleMute           = { isMuted = !isMuted },
                                onOpenFullscreen       = {
                                    activeVideoNoteId = row.id
                                    preFullscreenMuted = isMuted
                                    isMuted = false
                                    showFullscreenVideo = true
                                },
                            )
```

- [ ] **Step 6: Add FullScreenVideoDialog**

After the `articleRow?.let` block (after line 150), add:

```kotlin
    if (showFullscreenVideo) {
        FullScreenVideoDialog(
            exoPlayer = exoPlayer,
            onDismiss = {
                showFullscreenVideo = false
                isMuted = preFullscreenMuted
            },
        )
    }
```

- [ ] **Step 7: Verify no compile errors in Android Studio**

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/FeedScreen.kt
git commit -m "feat: wire shared ExoPlayer, scroll detection, and fullscreen in FeedScreen"
```

---

## Chunk 4: Cleanup & verification (Task 8)

### Task 8: Clean up unused imports and verify end-to-end

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt`

- [ ] **Step 1: Remove unused imports from NoteCard.kt**

After removing `VideoPlayerScreen` and `showVideoPlayer`, these imports in
NoteCard.kt may now be unused. Check and remove if so:

- `import androidx.compose.runtime.DisposableEffect` (line 40) — still needed?
  Only if used elsewhere in NoteCard. If not, remove.
- `import androidx.compose.ui.viewinterop.AndroidView` (line 56) — used by
  InlineAutoPlayVideo.kt now, not NoteCard.kt. Remove if unused.
- `import androidx.media3.common.MediaItem` (line 69) — used by
  InlineAutoPlayVideo.kt now. Remove from NoteCard if unused.
- `import androidx.media3.exoplayer.ExoPlayer` (line 70) — still needed for the
  `exoPlayer` parameter type.
- `import androidx.media3.ui.PlayerView` (line 71) — used by
  FullScreenVideoDialog if it lives in NoteCard.kt. Keep.

Check each import against actual usage and remove unused ones.

- [ ] **Step 2: Verify the full app compiles**

Open Android Studio, wait for indexing, and verify no red underlines across all
modified files:
- `ImetaParser.kt`
- `NoteCard.kt`
- `InlineAutoPlayVideo.kt`
- `FeedScreen.kt`

- [ ] **Step 3: Manual test on emulator**

Build and run on emulator. Test:
1. **Double-rendering fix:** Video posts should show ONE video component, not a
   black box above the video thumbnail
2. **Autoplay:** Scroll through feed — the video closest to center should start
   playing automatically (muted)
3. **Mute toggle:** Tap the speaker icon on an active video — sound should toggle
   globally (next video that activates should maintain the mute state)
4. **Fullscreen:** Tap the video → fullscreen dialog opens with controls, sound
   unmuted. Dismiss → returns to inline playback at same position, mute state
   restored
5. **Poster frames:** Videos with imeta `thumb` tags should show the thumbnail
   image instead of a black box when not active
6. **Non-direct URLs:** YouTube/Streamable links should still open in browser
   when tapped

- [ ] **Step 4: Commit cleanup**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt
git commit -m "chore: remove unused imports after video autoplay refactor"
```
