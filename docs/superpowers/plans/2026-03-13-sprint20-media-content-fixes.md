# Sprint 20: Media & Content Fixes — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix imeta video/image parsing (A8) and replace custom markdown renderer with a real library for NIP-23 articles (A7).

**Architecture:** A8 extracts inline imeta parsing from NoteCard into a shared `ImetaParser` utility, adds image support and dimension-based placeholder sizing. A7 adds `multiplatform-markdown-renderer-m3` as a dependency and replaces the custom `parseMarkdown()` function in `ArticleReaderScreen` with the library's `Markdown()` composable.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization.json, multiplatform-markdown-renderer-m3 0.39.2, Coil 3

---

## Chunk 1: A8 — ImetaParser Utility

### Task 1: Create ImetaParser data class and parser

**Files:**
- Create: `app/src/main/kotlin/com/unsilence/app/data/relay/ImetaParser.kt`

- [ ] **Step 1: Create the ImetaParser file**

Create `app/src/main/kotlin/com/unsilence/app/data/relay/ImetaParser.kt`:

```kotlin
package com.unsilence.app.data.relay

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Structured media entry parsed from a NIP-92 imeta tag.
 *
 * @param url       media URL (required — entries without a URL are dropped)
 * @param mimeType  MIME type from the "m" field, e.g. "video/mp4", "image/jpeg"
 * @param width     pixel width parsed from the "dim" field (e.g. 1920 from "1920x1080")
 * @param height    pixel height parsed from the "dim" field
 */
data class ImetaMedia(
    val url: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
)

/**
 * Parses NIP-92 imeta tags from an event's JSON tags string.
 *
 * Tag format: `["imeta", "url https://...", "m video/mp4", "dim 1920x1080", ...]`
 * Each entry after index 0 is a space-delimited key-value pair.
 */
object ImetaParser {

    /** Parse all imeta tags into structured media entries. */
    fun parse(tagsJson: String): List<ImetaMedia> = runCatching {
        NostrJson.parseToJsonElement(tagsJson).jsonArray
            .filter { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "imeta" }
            .mapNotNull { tag ->
                val kvMap = tag.jsonArray.drop(1).associate { entry ->
                    val s = entry.jsonPrimitive.content
                    val space = s.indexOf(' ')
                    if (space < 0) s to "" else s.substring(0, space) to s.substring(space + 1)
                }
                val url = kvMap["url"] ?: return@mapNotNull null
                val dim = kvMap["dim"]
                val (w, h) = if (dim != null && dim.contains("x")) {
                    val parts = dim.split("x", limit = 2)
                    parts[0].toIntOrNull() to parts[1].toIntOrNull()
                } else null to null
                ImetaMedia(
                    url = url,
                    mimeType = kvMap["m"],
                    width = w,
                    height = h,
                )
            }
    }.getOrElse { emptyList() }

    /** Only video/* entries. */
    fun videos(tagsJson: String): List<ImetaMedia> =
        parse(tagsJson).filter { it.mimeType?.startsWith("video/") == true }

    /** Only image/* entries. */
    fun images(tagsJson: String): List<ImetaMedia> =
        parse(tagsJson).filter { it.mimeType?.startsWith("image/") == true }
}
```

- [ ] **Step 2: Verify it compiles**

Build from Android Studio (do NOT run `./gradlew` from terminal while Studio is open). Confirm no compilation errors.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/relay/ImetaParser.kt
git commit -m "feat(A8): add ImetaParser utility for NIP-92 media tags"
```

---

### Task 2: Replace inline imeta parsing in NoteCard + merge imeta images

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt:180-204`

- [ ] **Step 1: Add import and replace inline imeta block**

In `NoteCard.kt`, add import at the top (after the existing `extractRepostAuthorPubkey` import on line 75):

```kotlin
import com.unsilence.app.data.relay.ImetaParser
```

Replace lines 180-204 (from `val imageUrls` through `val videoUrls`):

**Old code (lines 180-204):**
```kotlin
    val imageUrls      = IMAGE_URL_REGEX.findAll(contentNoNostr).map { it.value }.toList()
    val afterImages    = IMAGE_URL_REGEX.replace(contentNoNostr, "")
    val regexVideoUrls = VIDEO_URL_REGEX.findAll(afterImages).map { it.value }.toList()
    val afterVideos    = VIDEO_URL_REGEX.replace(afterImages, "")
    val linkUrls       = LINK_URL_REGEX.findAll(afterVideos).map { it.value }.distinct().take(3).toList()
    val textContent    = LINK_URL_REGEX.replace(afterVideos, "").trim()

    // Parse imeta tags for video content (NIP-92).
    // Each imeta tag is ["imeta", "key value", ...]; we look for m=video/* + url.
    val imetaVideoUrls: List<String> = runCatching {
        NostrJson.parseToJsonElement(row.tags).jsonArray
            .filter { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "imeta" }
            .mapNotNull { tag ->
                val kvMap = tag.jsonArray.drop(1).associate { entry ->
                    val s = entry.jsonPrimitive.content
                    val space = s.indexOf(' ')
                    if (space < 0) s to "" else s.substring(0, space) to s.substring(space + 1)
                }
                val mime = kvMap["m"] ?: return@mapNotNull null
                if (!mime.startsWith("video/")) return@mapNotNull null
                kvMap["url"]
            }
    }.getOrElse { emptyList() }

    val videoUrls = (regexVideoUrls + imetaVideoUrls).distinct()
```

**New code:**
```kotlin
    // ── Media extraction: regex from content + imeta from tags ────────────────
    val imetaMedia    = ImetaParser.parse(row.tags)
    val regexImageUrls = IMAGE_URL_REGEX.findAll(contentNoNostr).map { it.value }.toList()
    val imetaImageUrls = imetaMedia.filter { it.mimeType?.startsWith("image/") == true }.map { it.url }
    val imageUrls      = (regexImageUrls + imetaImageUrls).distinct()

    val afterImages    = IMAGE_URL_REGEX.replace(contentNoNostr, "")
    val regexVideoUrls = VIDEO_URL_REGEX.findAll(afterImages).map { it.value }.toList()
    val imetaVideoUrls = imetaMedia.filter { it.mimeType?.startsWith("video/") == true }.map { it.url }
    val videoUrls      = (regexVideoUrls + imetaVideoUrls).distinct()

    val afterVideos    = VIDEO_URL_REGEX.replace(afterImages, "")
    val linkUrls       = LINK_URL_REGEX.findAll(afterVideos).map { it.value }.distinct().take(3).toList()
    val textContent    = LINK_URL_REGEX.replace(afterVideos, "").trim()
```

- [ ] **Step 2: Verify it compiles**

Build from Android Studio. Confirm no compilation errors. The `jsonArray` import (line 81) is still needed for other code in NoteCard — do NOT remove it.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt
git commit -m "refactor(A8): replace inline imeta parsing with ImetaParser, add image support"
```

---

### Task 3: Dimension-based placeholder sizing for video and images

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt:330-346` (image section)
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt:358-371` (video section)
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt:613-630` (VideoThumbnailCard)

- [ ] **Step 1: Update VideoThumbnailCard to accept optional aspect ratio**

Find the `VideoThumbnailCard` function (line 613). Replace its signature and height logic:

**Old (lines 613-630):**
```kotlin
private fun VideoThumbnailCard(url: String, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier          = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(Color(0xFF1A1A1A))
            .clickable { onPlay() },
        contentAlignment  = Alignment.Center,
    ) {
        Icon(
            imageVector        = Icons.Filled.PlayArrow,
            contentDescription = "Play video",
            tint               = Color.White.copy(alpha = 0.85f),
            modifier           = Modifier.size(52.dp),
        )
    }
}
```

**New:**
```kotlin
private fun VideoThumbnailCard(
    url: String,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    aspectRatio: Float? = null,
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
        Icon(
            imageVector        = Icons.Filled.PlayArrow,
            contentDescription = "Play video",
            tint               = Color.White.copy(alpha = 0.85f),
            modifier           = Modifier.size(52.dp),
        )
    }
}
```

Add these imports at the top of the file (after the existing `height` import on line 65):

```kotlin
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
```

- [ ] **Step 2: Pass aspect ratio from imeta to VideoThumbnailCard call site**

Find the video thumbnail section (around line 359 after step 1's edits — search for `videoUrls.firstOrNull`). Replace the call:

**Old:**
```kotlin
        videoUrls.firstOrNull()?.let { url ->
            VideoThumbnailCard(
                url    = url,
                onPlay = {
                    if (isDirectVideoUrl(url)) {
                        showVideoPlayer = url
                    } else {
                        runCatching { uriHandler.openUri(url) }
                    }
                },
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
        }
```

**New:**
```kotlin
        videoUrls.firstOrNull()?.let { url ->
            val videoMeta = imetaMedia.firstOrNull {
                it.url == url && it.width != null && it.height != null
            }
            VideoThumbnailCard(
                url        = url,
                onPlay     = {
                    if (isDirectVideoUrl(url)) {
                        showVideoPlayer = url
                    } else {
                        runCatching { uriHandler.openUri(url) }
                    }
                },
                aspectRatio = if (videoMeta != null) videoMeta.width!!.toFloat() / videoMeta.height!! else null,
                modifier    = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
        }
```

- [ ] **Step 3: Use imeta dimensions for image placeholder sizing**

Find the image section (around line 331 — search for `imageUrls.firstOrNull`). Replace the `SubcomposeAsyncImage` modifier:

**Old:**
```kotlin
                modifier           = Modifier
                    .fillMaxWidth()
                    // Reserve space before the image loads — prevents cards below from
                    // jumping up when text renders before the image arrives.
                    .defaultMinSize(minHeight = 200.dp)
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = if (imageUrls.size > 1) 2.dp else Spacing.small)
                    .clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
```

**New:**
```kotlin
                modifier           = Modifier
                    .fillMaxWidth()
                    .then(
                        imetaMedia.firstOrNull { it.url == url && it.width != null && it.height != null }
                            ?.let { Modifier.aspectRatio(it.width!!.toFloat() / it.height!!, matchHeightConstraintsFirst = false) }
                            ?: Modifier.defaultMinSize(minHeight = 200.dp)
                    )
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = if (imageUrls.size > 1) 2.dp else Spacing.small)
                    .clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
```

- [ ] **Step 4: Verify it compiles and test on emulator**

Build from Android Studio. Launch on emulator. Check:
1. Notes with video imeta tags show correct aspect ratio thumbnail (not hardcoded 180dp)
2. Notes with image imeta tags size correctly
3. Notes with regex-only media still work (fallback to 180dp / 200dp)
4. Tapping video thumbnail opens ExoPlayer

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/NoteCard.kt
git commit -m "feat(A8): dimension-based placeholder sizing from imeta dim field"
```

---

## Chunk 2: A7 — Markdown Rendering

### Task 4: Add markdown renderer dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add version and library entries to libs.versions.toml**

In `gradle/libs.versions.toml`, add after line 21 (`identikon = "1.0.0"`):

```toml
markdown-renderer  = "0.39.2"
```

In the `[libraries]` section, add after line 72 (after `identikon-android`):

```toml

# Markdown rendering (NIP-23 articles)
markdown-renderer-m3            = { group = "com.mikepenz",               name = "multiplatform-markdown-renderer-m3",    version.ref = "markdown-renderer" }
markdown-renderer-coil3         = { group = "com.mikepenz",               name = "multiplatform-markdown-renderer-coil3", version.ref = "markdown-renderer" }
```

- [ ] **Step 2: Add implementation lines to build.gradle.kts**

In `app/build.gradle.kts`, add after line 87 (after `implementation(libs.coil.network.okhttp)`):

```kotlin

    // Markdown rendering (NIP-23 long-form articles)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.coil3)
```

- [ ] **Step 3: Sync Gradle**

In Android Studio: File → Sync Project with Gradle Files. Confirm sync succeeds with no errors.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "deps(A7): add multiplatform-markdown-renderer-m3 + coil3 integration"
```

---

### Task 5: Replace parseMarkdown() with Markdown() composable

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/ui/feed/ArticleReaderScreen.kt`

- [ ] **Step 1: Replace imports**

Replace the entire import block (lines 1-47) with:

```kotlin
package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
```

- [ ] **Step 2: Replace the Text(parseMarkdown(...)) block with Markdown()**

Replace lines 123-133 (the body content section):

**Old:**
```kotlin
                // ── Body content (simple markdown: ## headers, **bold**) ────────
                Text(
                    text       = parseMarkdown(row.content),
                    color      = MaterialTheme.colorScheme.onSurface,
                    fontSize   = 15.sp,
                    lineHeight = 24.sp,
                    modifier   = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium)
                        .padding(bottom = Spacing.xl),
                )
```

**New:**
```kotlin
                // ── Body content (full markdown via multiplatform-markdown-renderer) ──
                Markdown(
                    content          = row.content,
                    imageTransformer = Coil3ImageTransformerImpl,
                    colors           = markdownColor(
                        text                 = Color.White,
                        codeBackground       = Color(0xFF1A1A1A),
                        inlineCodeBackground = Color(0xFF1A1A1A),
                        dividerColor         = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    typography       = markdownTypography(
                        h1        = MaterialTheme.typography.headlineLarge.copy(color = Color.White),
                        h2        = MaterialTheme.typography.headlineMedium.copy(color = Color.White),
                        h3        = MaterialTheme.typography.headlineSmall.copy(color = Color.White),
                        paragraph = MaterialTheme.typography.bodyLarge.copy(color = Color.White, lineHeight = 24.sp),
                        code      = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace),
                        textLink  = TextLinkStyles(style = SpanStyle(color = Cyan)),
                    ),
                    modifier         = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium)
                        .padding(bottom = Spacing.xl),
                )
```

- [ ] **Step 3: Delete the parseMarkdown() function**

Delete lines 148-174 (the entire `parseMarkdown` function and its KDoc comment):

```kotlin
/**
 * Minimal markdown → AnnotatedString renderer.
 *  - Lines starting with "## " → bold 18sp (header)
 *  - **text** spans → bold (any nesting level)
 */
private fun parseMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    ...
}
```

- [ ] **Step 4: Verify it compiles**

Build from Android Studio. If `TextLinkStyles` doesn't resolve, check the import — it may be `androidx.compose.ui.text.TextLinkStyles` or `com.mikepenz.markdown.model.TextLinkStyles` depending on version. Adjust the import accordingly.

If `markdownColor` parameters don't match (API may vary slightly by version), check the library's actual `markdownColor()` function signature and adjust parameter names. The intent is: white text, dark code backgrounds, cyan links.

- [ ] **Step 5: Test on emulator**

Launch on emulator. Open a kind-30023 article. Verify:
1. Headings render at different sizes (h1 > h2 > h3)
2. Bold and italic text render correctly
3. Links are clickable (open in browser)
4. Code blocks have dark background
5. Lists (bulleted/numbered) render properly
6. Images within article content load via Coil
7. Banner image at top still works (unchanged)
8. Title still renders (unchanged)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/ui/feed/ArticleReaderScreen.kt
git commit -m "feat(A7): replace custom parseMarkdown with multiplatform-markdown-renderer"
```

---

## Notes for implementer

- **Do NOT run `./gradlew` from terminal** while Android Studio is open — use Android Studio's build system.
- **Git operations** must be run from the `aivii` user terminal, not `android-dev`.
- The `TextLinkStyles` import may need adjustment at build time — the spec reviewer confirmed the parameter name is correct for v0.39.2 but the exact import path should be verified against the actual resolved dependency.
- `ImetaParser` uses the existing `NostrJson` instance (same file's package) — no new JSON parser needed.
- The `aspectRatio` modifier with `matchHeightConstraintsFirst = false` means width is matched first (fillMaxWidth), then height is derived from the ratio. This is the standard behavior for media cards.
