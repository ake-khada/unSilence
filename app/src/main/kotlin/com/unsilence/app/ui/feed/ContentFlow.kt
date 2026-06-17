package com.unsilence.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary

/** Maximum number of OG preview cards rendered per note. */
private const val MAX_OG_CARDS = 1

/**
 * Walks the segment list from an [EventModel] and renders each content section
 * in source order.
 *
 * Consecutive Image segments collapse into one EventMediaGrid, consecutive
 * Video segments into one EventVideoGrid, consecutive Text/MentionPubkey/Link
 * runs into one InlineText (URLs render as inline cyan clickable text).
 * After each text run, OG preview cards render for links in that run (up to
 * [MAX_OG_CARDS] total per note).
 *
 * [nestDepth] controls quote nesting. At depth 0 (top-level cards), quotes
 * render as full QuoteCards. QuoteCard increments depth before calling
 * ContentFlow recursively. At depth >= 1, QuoteCards render text-only.
 */
@Composable
internal fun ContentFlow(
    model: EventModel,
    role: CardRole,
    onNoteClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onHashtagClick: (String) -> Unit = {},
    lookupProfile: (suspend (String) -> UserEntity?)?,
    lookupEvent: (suspend (String, List<String>) -> EventEntity?)?,
    lookupEventWithAuthor: (suspend (String, List<String>, String?) -> EventEntity?)? = null,
    lookupModel: ((String) -> EventModel?)? = null,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)?,
    imageDimensionCache: ImageDimensionCache?,
    isActiveVideo: Boolean = false,
    activeVideoUrl: String? = null,
    isFullscreen: Boolean = false,
    onOpenFullscreen: () -> Unit = {},
    exoPlayer: ExoPlayer? = null,
    isMuted: Boolean = true,
    onToggleMute: () -> Unit = {},
    thumbnailCache: VideoThumbnailCache? = null,
    sensitiveMode: SensitiveContentMode = SensitiveContentMode.SHOW,
    nestDepth: Int = 0,
    modifier: Modifier = Modifier,
) {
    val navigateId = model.navigateId
    val showVideo = role != CardRole.Article
    val isEmbedded = role == CardRole.Embedded

    // Viewport-relative line budget: ~95% of screen in text lines (lineHeight=22sp≈22dp)
    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val lineBudget = remember(screenHeightDp) {
        ((screenHeightDp * 0.95f) / 22f).toInt().coerceIn(15, 50)
    }
    var hasTextOverflow by remember(model.segments) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val maxLines = when {
        isEmbedded -> 6
        !expanded -> lineBudget
        else -> Int.MAX_VALUE
    }
    val overflow = if (maxLines < Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip

    val hPad = if (isEmbedded) 0.dp else Spacing.medium

    Column(modifier = modifier) {
        var i = 0
        var ogCardsRendered = 0
        while (i < model.segments.size) {
            when (model.segments[i]) {
                is Segment.Text, is Segment.MentionPubkey, is Segment.Link, is Segment.Hashtag -> {
                    // Collect consecutive text/mention/link/hashtag run
                    var j = i
                    while (j < model.segments.size &&
                        (model.segments[j] is Segment.Text ||
                            model.segments[j] is Segment.MentionPubkey ||
                            model.segments[j] is Segment.Link ||
                            model.segments[j] is Segment.Hashtag)) j++
                    val run = model.segments.subList(i, j).toList()

                    // Pick the link(s) in this run that will become an OG
                    // preview card, and HIDE them from the inline text.
                    // Showing the URL inline AND as the OG card duplicates
                    // information — Amethyst, Damus, Jumble all elide the
                    // URL when a preview is rendered. The OG card itself
                    // displays the title + thumbnail; if the OG fetch
                    // returns nothing useful, MinimalLinkCard's favicon +
                    // hostname covers the case so the user always sees
                    // some affordance for the link.
                    val ogToRender = mutableListOf<Segment.Link>()
                    val runForInline = mutableListOf<Segment>()
                    val availableSlots = MAX_OG_CARDS - ogCardsRendered
                    for (seg in run) {
                        if (seg is Segment.Link && ogToRender.size < availableSlots) {
                            ogToRender.add(seg)
                        } else {
                            runForInline.add(seg)
                        }
                    }

                    // Strip blank-line padding at media boundaries so text
                    // sits tight against adjacent images/videos. The \n\n
                    // separators in the content string create visible gaps
                    // without this trim.
                    trimTextRunEdges(runForInline)

                    // A run may be link-only (e.g. a bare-URL note): runForInline
                    // is empty but ogToRender still holds the link. Skip only the
                    // inline text in that case — the OG card loop below MUST still
                    // run, otherwise the note renders completely blank (no URL, no
                    // preview). Do NOT `continue` here.
                    if (runForInline.isNotEmpty()) InlineText(
                        segments      = runForInline,
                        lookupProfile = lookupProfile,
                        onAuthorClick = onAuthorClick,
                        onHashtagClick = onHashtagClick,
                        onTextClick   = { onNoteClick(navigateId) },
                        customEmojis  = model.customEmojis,
                        maxLines      = maxLines,
                        overflow      = overflow,
                        onTextLayoutResult = if (!isEmbedded) { result ->
                            if (result.hasVisualOverflow) hasTextOverflow = true
                        } else null,
                        modifier      = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = hPad)
                            .padding(bottom = Spacing.small),
                    )

                    // Render OG preview cards for the links we removed from
                    // inline text. showMinimalFallback=true so a failed OG
                    // fetch still produces a card (favicon + hostname).
                    for (link in ogToRender) {
                        OgPreviewCard(
                            url               = link.url,
                            fetchOgMetadata   = fetchOgMetadata,
                            showMinimalFallback = true,
                            modifier          = Modifier
                                .padding(horizontal = hPad)
                                .padding(bottom = Spacing.small),
                        )
                        ogCardsRendered++
                    }

                    i = j
                }
                is Segment.Image -> {
                    // Collect images, absorbing blank-line text between them
                    var j = i
                    while (j < model.segments.size) {
                        when (val seg = model.segments[j]) {
                            is Segment.Image -> j++
                            is Segment.Text -> if (seg.text.isBlank()) j++ else break
                            else -> break
                        }
                    }
                    val images = model.segments.subList(i, j)
                        .filterIsInstance<Segment.Image>()
                    EventMediaGrid(
                        images              = images,
                        imageDimensionCache = imageDimensionCache,
                        modifier            = Modifier
                            .padding(horizontal = hPad)
                            .padding(bottom = Spacing.small),
                    )
                    i = j
                }
                is Segment.Video -> {
                    // Collect consecutive videos
                    var j = i
                    while (j < model.segments.size && model.segments[j] is Segment.Video) j++
                    val videos = model.segments.subList(i, j)
                        .filterIsInstance<Segment.Video>()
                    EventVideoGrid(
                        videos           = videos,
                        isActiveVideo    = if (showVideo) isActiveVideo else false,
                        activeVideoUrl   = activeVideoUrl,
                        isFullscreen     = isFullscreen,
                        onOpenFullscreen = onOpenFullscreen,
                        exoPlayer        = if (showVideo) exoPlayer else null,
                        isMuted          = isMuted,
                        onToggleMute     = onToggleMute,
                        thumbnailCache   = thumbnailCache,
                        modifier         = Modifier
                            .padding(horizontal = hPad)
                            .padding(bottom = Spacing.small),
                    )
                    i = j
                }
                is Segment.QuoteEvent -> {
                    val seg = model.segments[i] as Segment.QuoteEvent
                    QuoteCard(
                        segment         = seg,
                        onNoteClick     = onNoteClick,
                        onAuthorClick   = onAuthorClick,
                        onHashtagClick  = onHashtagClick,
                        lookupEvent     = if (seg.author != null && lookupEventWithAuthor != null) {
                            { id, hints -> lookupEventWithAuthor.invoke(id, hints, seg.author) }
                        } else lookupEvent,
                        lookupProfile   = lookupProfile,
                        lookupModel     = lookupModel,
                        fetchOgMetadata = fetchOgMetadata,
                        imageDimensionCache = imageDimensionCache,
                        exoPlayer       = if (showVideo) exoPlayer else null,
                        isActiveVideo   = if (showVideo) isActiveVideo else false,
                        activeVideoUrl  = activeVideoUrl,
                        isMuted         = isMuted,
                        onToggleMute    = onToggleMute,
                        thumbnailCache  = thumbnailCache,
                        sensitiveMode   = sensitiveMode,
                        nestDepth       = nestDepth,
                        modifier        = Modifier
                            .padding(horizontal = hPad)
                            .padding(bottom = Spacing.small),
                    )
                    i++
                }
                is Segment.QuoteAddress -> {
                    val seg = model.segments[i] as Segment.QuoteAddress
                    val chipMod = Modifier
                        .padding(horizontal = hPad)
                        .padding(bottom = Spacing.small)
                    if (seg.kind == 30023) {
                        // Quoted long-form → the canonical article card (same as feed).
                        EmbeddedArticleCard(
                            coord         = "30023:${seg.author}:${seg.dTag}",
                            author        = seg.author,
                            dTag          = seg.dTag,
                            hints         = seg.hints,
                            onNoteClick   = onNoteClick,
                            onAuthorClick = onAuthorClick,
                            nestDepth     = nestDepth,
                            sensitiveMode = sensitiveMode,
                            modifier      = chipMod,
                        )
                    } else {
                        AddressChip(
                            segment       = seg,
                            onNoteClick   = onNoteClick,
                            lookupProfile = lookupProfile,
                            modifier      = chipMod,
                        )
                    }
                    i++
                }
                is Segment.YouTube -> {
                    val seg = model.segments[i] as Segment.YouTube
                    YouTubeCard(
                        segment  = seg,
                        modifier = Modifier.padding(
                            horizontal = hPad,
                            vertical = Spacing.small,
                        ),
                    )
                    i++
                }
                is Segment.BlockQuote -> {
                    val seg = model.segments[i] as Segment.BlockQuote
                    // Muted inline text with a left accent rail drawn behind it
                    // (drawBehind matches the text height automatically, so the rail
                    // grows with the quote without an IntrinsicSize measure pass).
                    val railColor = TextSecondary.copy(alpha = 0.5f)
                    InlineText(
                        segments      = seg.segments,
                        lookupProfile = lookupProfile,
                        onAuthorClick = onAuthorClick,
                        onHashtagClick = onHashtagClick,
                        onTextClick   = { onNoteClick(navigateId) },
                        customEmojis  = model.customEmojis,
                        maxLines      = maxLines,
                        overflow      = overflow,
                        textColor     = TextSecondary,
                        onTextLayoutResult = if (!isEmbedded) { result ->
                            if (result.hasVisualOverflow) hasTextOverflow = true
                        } else null,
                        modifier      = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = hPad)
                            .padding(bottom = Spacing.small)
                            .drawBehind { drawRect(color = railColor, size = Size(3.dp.toPx(), size.height)) }
                            .padding(start = Spacing.small),
                    )
                    i++
                }
            }
        }

        // "Show more" toggle — only when text actually overflows the viewport budget
        if (!isEmbedded && (hasTextOverflow || expanded)) {
            Text(
                text     = if (expanded) "Show less" else "Show more",
                color    = TextSecondary,
                fontSize = AppType.bodySmall,
                modifier = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.micro)
                    .clickable { expanded = !expanded },
            )
        }

        // Truncation chip — the post's content was capped by the spam-post DoS bound.
        // No tap-to-expand by design: expanding would re-create the freeze we prevent.
        if (model.truncated) {
            Text(
                text     = "Long post — truncated",
                color    = TextSecondary,
                fontSize = AppType.caption,
                modifier = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Surface2)
                    .padding(horizontal = Spacing.small, vertical = Spacing.micro),
            )
        }
    }
}

/**
 * Trims leading blank lines from the first Text segment and trailing blank
 * lines from the last Text segment in a run. Removes segments that become
 * empty after trimming. This keeps text tight against adjacent media
 * (images, videos) without the paragraph-level gaps that \n\n separators
 * create in the content string.
 */
private fun trimTextRunEdges(run: MutableList<Segment>) {
    // Trim leading newlines from first Text
    while (run.isNotEmpty() && run.first() is Segment.Text) {
        val first = run.first() as Segment.Text
        val trimmed = first.text.trimStart('\n')
        if (trimmed.isEmpty()) { run.removeFirst(); continue }
        if (trimmed != first.text) run[0] = Segment.Text(trimmed)
        break
    }
    // Trim trailing newlines from last Text
    while (run.isNotEmpty() && run.last() is Segment.Text) {
        val last = run.last() as Segment.Text
        val trimmed = last.text.trimEnd('\n')
        if (trimmed.isEmpty()) { run.removeLast(); continue }
        if (trimmed != last.text) run[run.lastIndex] = Segment.Text(trimmed)
        break
    }
}
