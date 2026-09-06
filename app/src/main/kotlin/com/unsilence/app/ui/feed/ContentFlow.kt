package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.PaymentTarget
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.model.shouldRenderAsCard
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary

/** Maximum number of OG preview cards rendered per note. */
private const val MAX_OG_CARDS = 1
private const val COLLAPSED_CARD_SCREEN_FRACTION = 0.80f
private const val CONTENT_LINE_HEIGHT_DP = 22f
private const val COLLAPSED_CARD_CHROME_DP = 104f

/**
 * Walks the segment list from an [EventModel] and renders each content section
 * in source order.
 *
 * Image or video runs separated only by blank text collapse into one media
 * carousel; consecutive Text/MentionPubkey/Link runs become one InlineText
 * (URLs render as inline cyan clickable text).
 * After each text run, OG preview cards render for links in that run (up to
 * [MAX_OG_CARDS] total per note).
 *
 * [nestDepth] controls quote nesting. The first two quote levels render full
 * content, the third is compact text, and deeper content becomes a terminal
 * continuation chip. QuoteCard increments depth before recursing.
 */
@Composable
internal fun ContentFlow(
    model: EventModel,
    role: CardRole,
    host: EventCardHost,
    videoOwnerId: String,
    onOpenFullscreen: (() -> Unit)? = null,
    nestDepth: Int = 0,
    knownLightningAddress: String? = null,
    modifier: Modifier = Modifier,
) {
    val actions = host.actions
    val services = host.services
    val surface = host.surface
    val videoScope = surface.videoScope
    val lookupProfile: suspend (String) -> com.unsilence.app.data.memory.UserEntity? =
        { pubkey -> host.lookupProfile(pubkey) }
    val navigateId = model.navigateId
    val showVideo = role != CardRole.Article
    val isEmbedded = role == CardRole.Embedded
    val videoModels = remember(model.media.videos) { model.media.videos.map { it.model } }

    LaunchedEffect(model.id, videoOwnerId, videoModels) {
        if (videoModels.isNotEmpty()) videoScope?.registerVideoModels(videoOwnerId, videoModels)
    }

    // Collapsed long-note budget: keep the whole card close to 80% of the
    // viewport, not just the text run. Reserve rough chrome for author header,
    // Show more, and action bar; the remaining height becomes text lines.
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val screenHeightDp = windowHeightPx / density.density
    val lineBudget = remember(screenHeightDp) {
        (((screenHeightDp * COLLAPSED_CARD_SCREEN_FRACTION) - COLLAPSED_CARD_CHROME_DP) /
            CONTENT_LINE_HEIGHT_DP).toInt().coerceIn(12, 32)
    }
    var hasTextOverflow by remember(model.segments) { mutableStateOf(false) }
    var expanded by remember(model.navigateId) { mutableStateOf(false) }
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
        segmentLoop@ while (i < model.segments.size) {
            val current = model.segments[i]
            if (current.isInlineContent(knownLightningAddress)) {
                // Collect consecutive text/mention/link/hashtag runs. A bare
                // email-shaped LUD-16 candidate remains ordinary inline text
                // unless the author's trusted profile advertises it as lud16.
                var j = i
                while (j < model.segments.size &&
                    model.segments[j].isInlineContent(knownLightningAddress)) j++
                val run = model.segments.subList(i, j).map(Segment::asInlineContent)

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
                    onAuthorClick = actions.onAuthorClick,
                    onHashtagClick = actions.onHashtagClick,
                    onTextClick   = { actions.onNoteClick(navigateId) },
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

                // Once a top-level text run exceeds the collapsed viewport
                // budget, stop rendering the remaining source-order content
                // (OG cards, images, later text) until expanded. Otherwise a
                // long post can still exceed the screen because the capped
                // text is followed by a link preview or media card.
                if (!isEmbedded && !expanded && hasTextOverflow) {
                    break@segmentLoop
                }

                // Render OG preview cards for the links we removed from
                // inline text. showMinimalFallback=true so a failed OG
                // fetch still produces a card (favicon + hostname).
                for (link in ogToRender) {
                    OgPreviewCard(
                        url               = link.url,
                        fetchOgMetadata   = services.fetchOgMetadata,
                        hasCachedOgMetadata = services.hasCachedOgMetadata,
                        imageDimensionCache = services.imageDimensionCache,
                        onDirectImageClick = if (isEmbedded) {
                            { actions.onNoteClick(navigateId) }
                        } else null,
                        showMinimalFallback = true,
                        modifier          = Modifier
                            .padding(horizontal = hPad)
                            .padding(bottom = Spacing.small),
                    )
                    ogCardsRendered++
                }

                i = j
                continue@segmentLoop
            }

            when (current) {
                is Segment.Payment -> {
                    PaymentTargetCard(
                        target = current.target,
                        modifier = Modifier
                            .padding(horizontal = hPad)
                            .padding(bottom = Spacing.small),
                    )
                    i++
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
                        imageDimensionCache = services.imageDimensionCache,
                        // Embedded quote images should open the quoted note, not
                        // the parent card's media viewer. Top-level images keep
                        // fullscreen media behavior.
                        onImageClick        = if (isEmbedded) {
                            { actions.onNoteClick(navigateId) }
                        } else null,
                        modifier            = Modifier
                            .padding(horizontal = hPad)
                            .padding(bottom = Spacing.small),
                    )
                    i = j
                }
                is Segment.Video -> {
                    // Collect videos, absorbing blank-line text between them.
                    var j = i
                    while (j < model.segments.size) {
                        when (val segment = model.segments[j]) {
                            is Segment.Video -> j++
                            is Segment.Text -> if (segment.text.isBlank()) j++ else break
                            else -> break
                        }
                    }
                    val videos = model.segments.subList(i, j)
                        .filterIsInstance<Segment.Video>()
                    EventVideoGrid(
                        videos           = videos,
                        isActiveVideo    = showVideo && videoScope?.isActiveVideo(videoOwnerId) == true,
                        activeVideoUrl   = videoScope?.activeVideoUrl,
                        selectedVideoUrl = videoScope?.selectedVideoUrl(videoOwnerId),
                        onVideoSelected  = videoScope?.let { scope ->
                            { video -> scope.selectVideo(videoOwnerId, video.videoUrl) }
                        },
                        isFullscreen     = videoScope?.showFullscreenVideo == true,
                        onOpenFullscreen = { video ->
                            onOpenFullscreen?.invoke()
                                ?: videoScope?.openFullscreen(videoOwnerId, video.videoUrl)
                        },
                        exoPlayer        = if (showVideo) videoScope?.exoPlayer else null,
                        isMuted          = videoScope?.isMuted ?: true,
                        onToggleMute     = { videoScope?.toggleMute() ?: Unit },
                        thumbnailCache   = services.thumbnailCache,
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
                        host            = host,
                        videoOwnerId    = videoOwnerId,
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
                            segment       = seg,
                            host          = host,
                            nestDepth     = nestDepth,
                            modifier      = chipMod,
                        )
                    } else {
                        AddressChip(
                            segment       = seg,
                            host          = host,
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
                        onAuthorClick = actions.onAuthorClick,
                        onHashtagClick = actions.onHashtagClick,
                        onTextClick   = { actions.onNoteClick(navigateId) },
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
                // These variants always take the inline-content branch above;
                // keep the sealed when exhaustive if Segment grows later.
                is Segment.Text,
                is Segment.MentionPubkey,
                is Segment.Link,
                is Segment.Hashtag -> i++
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

private fun Segment.isInlineContent(knownLightningAddress: String?): Boolean = when (this) {
    is Segment.Text, is Segment.MentionPubkey, is Segment.Link, is Segment.Hashtag -> true
    is Segment.Payment -> target is PaymentTarget.LightningAddress &&
        !target.shouldRenderAsCard(knownLightningAddress)
    else -> false
}

private fun Segment.asInlineContent(): Segment = when (this) {
    is Segment.Payment -> Segment.Text(target.displayValue)
    else -> this
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
        if (trimmed.isEmpty()) { run.removeAt(0); continue }
        if (trimmed != first.text) run[0] = Segment.Text(trimmed)
        break
    }
    // Trim trailing newlines from last Text
    while (run.isNotEmpty() && run.last() is Segment.Text) {
        val last = run.last() as Segment.Text
        val trimmed = last.text.trimEnd('\n')
        if (trimmed.isEmpty()) { run.removeAt(run.lastIndex); continue }
        if (trimmed != last.text) run[run.lastIndex] = Segment.Text(trimmed)
        break
    }
}
