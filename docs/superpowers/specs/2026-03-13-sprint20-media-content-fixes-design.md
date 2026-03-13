# Sprint 20: Media & Content Fixes — Design Spec

> **Scope:** A8 (imeta video/image parsing) and A7 (article markdown rendering).
> A6 (status bar fix) was already shipped in a prior commit — dropped from this sprint.

---

## A8: ImetaParser Utility (NIP-92)

### Problem

NoteCard has inline imeta parsing (lines 187-202) that extracts video URLs from `imeta` tags. It works for the basic video case but:

1. **Only extracts videos** — ignores `image/*` entries. Images delivered via imeta-only posts (no URL in content) don't display.
2. **No dimension support** — video thumbnails use hardcoded 180dp height; images use hardcoded 200dp `defaultMinSize`. The `dim` field (`WxH`) in imeta tags is ignored.
3. **Inline, not reusable** — the parsing logic is embedded in NoteCard's composable body.

### Design

**New file:** `app/src/main/kotlin/com/unsilence/app/data/relay/ImetaParser.kt`

**Data model:**

```kotlin
data class ImetaMedia(
    val url: String,
    val mimeType: String?,   // "video/mp4", "image/jpeg", etc.
    val width: Int?,          // from "dim" field, e.g. 1920 from "1920x1080"
    val height: Int?,         // from "dim" field, e.g. 1080 from "1920x1080"
)
```

**Parser:**

```kotlin
object ImetaParser {
    /** Parse all imeta tags into structured media entries. */
    fun parse(tagsJson: String): List<ImetaMedia>

    /** Convenience: only video/* entries. */
    fun videos(tagsJson: String): List<ImetaMedia> =
        parse(tagsJson).filter { it.mimeType?.startsWith("video/") == true }

    /** Convenience: only image/* entries. */
    fun images(tagsJson: String): List<ImetaMedia> =
        parse(tagsJson).filter { it.mimeType?.startsWith("image/") == true }
}
```

Parsing logic mirrors the existing inline code: iterate JSON tag array, filter elements where index 0 is `"imeta"`, build key-value map from space-delimited entries (`"key value"`), extract `url`, `m`, and parse `dim` as `WxH` split on `x`.

**NoteCard integration:**

- Replace inline imeta block (lines 187-202) with `ImetaParser.videos(row.tags).map { it.url }`.
- Add imeta image extraction: `ImetaParser.images(row.tags).map { it.url }`.
- Merge imeta images with regex-extracted images: `val imageUrls = (regexImageUrls + imetaImageUrls).distinct()`.
- Video merge stays the same pattern: `val videoUrls = (regexVideoUrls + imetaVideoUrls).distinct()`.

**Dimension-based placeholder sizing:**

- `VideoThumbnailCard`: if the first video imeta entry has non-null `width`/`height`, compute aspect ratio and derive height from `fillMaxWidth()`. Cap at 300dp max, floor at 120dp min. Fall back to 180dp if no dimensions.
- Image `SubcomposeAsyncImage`: same approach — use imeta dimensions for `defaultMinSize` height instead of hardcoded 200dp.

**Blurhash:** Skipped — no blurhash library in the project; adding one is out of scope.

### Files touched

| File | Change |
|------|--------|
| `data/relay/ImetaParser.kt` | **Create** — data class + parser object |
| `ui/feed/NoteCard.kt` | Replace inline imeta block with `ImetaParser` calls; merge imeta images; dimension-based sizing for video + image placeholders |

---

## A7: Markdown Rendering for NIP-23 Articles

### Problem

`ArticleReaderScreen` renders article body through a custom `parseMarkdown()` function that only supports `##` headers and `**bold**`. No links, images, code blocks, lists, blockquotes, italic, or other heading levels. Content displays as near-plain text.

### Design

**Dependency:** `com.mikepenz:multiplatform-markdown-renderer-m3` (Compose-native, Material 3). Also `multiplatform-markdown-renderer-coil3` for inline image rendering via existing Coil setup.

**Dependency additions:**

- `gradle/libs.versions.toml`: add version `markdown-renderer = "0.39.2"`, library entries for `multiplatform-markdown-renderer-m3` and `multiplatform-markdown-renderer-coil3`.
- `app/build.gradle.kts`: add `implementation` lines for both.

**ArticleReaderScreen changes:**

Replace `Text(text = parseMarkdown(row.content), ...)` (lines 123-133) with:

```kotlin
Markdown(
    content          = row.content,
    imageTransformer = Coil3ImageTransformerImpl,
    modifier         = Modifier
        .fillMaxWidth()
        .padding(horizontal = Spacing.medium)
        .padding(bottom = Spacing.xl),
    colors     = markdownColor(
        text                = Color.White,
        codeBackground      = Color(0xFF1A1A1A),
        inlineCodeBackground = Color(0xFF1A1A1A),
        dividerColor        = MaterialTheme.colorScheme.surfaceVariant,
    ),
    typography = markdownTypography(
        h1 = MaterialTheme.typography.headlineLarge.copy(color = Color.White),
        h2 = MaterialTheme.typography.headlineMedium.copy(color = Color.White),
        h3 = MaterialTheme.typography.headlineSmall.copy(color = Color.White),
        paragraph = MaterialTheme.typography.bodyLarge.copy(color = Color.White, lineHeight = 24.sp),
        code = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace),
        textLink = TextLinkStyles(style = SpanStyle(color = Cyan)),
    ),
)
```

The `Coil3ImageTransformerImpl` (from `multiplatform-markdown-renderer-coil3`) enables inline image rendering via the existing Coil setup. Without it, images in article markdown content would silently not render.

**Link click handling:** The library uses `LocalUriHandler` by default, which opens links in the system browser. `nostr:` URIs within article content will open the browser and fail — this is a known limitation, acceptable for Sprint 20. Internal routing for `nostr:` links is a follow-up.

**Delete:** `parseMarkdown()` function (lines 148-174) and its unused imports (`AnnotatedString`, `SpanStyle`, `buildAnnotatedString`, `withStyle`).

**Not touched:** Banner image rendering, title extraction from tags, top bar, dialog structure, `articleTagValue()` helper — all remain as-is.

### Supported markdown elements (provided by library)

Headings (all levels), bold, italic, strikethrough, links (clickable), inline code, fenced code blocks, images, blockquotes, ordered/unordered lists, horizontal rules, tables.

### Files touched

| File | Change |
|------|--------|
| `gradle/libs.versions.toml` | Add markdown-renderer version + library entries |
| `app/build.gradle.kts` | Add implementation dependencies |
| `ui/feed/ArticleReaderScreen.kt` | Replace `Text(parseMarkdown(...))` with `Markdown()` composable; delete `parseMarkdown()` function; add theming config; remove unused imports |

---

## Priority order

1. **A8 first** — ImetaParser utility, NoteCard integration (videos are broken for users now)
2. **A7 second** — markdown rendering (articles are less common but visually broken)

## Out of scope

- A6 status bar fix (already shipped)
- Blurhash placeholders (would require new dependency)
- Video player improvements (ExoPlayer works, just needs correct URLs)
- Article feed card changes (ArticleCard.kt is fine as-is)
