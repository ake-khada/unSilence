package com.unsilence.app.ui.feed

import com.unsilence.app.ui.theme.AppTextStyles
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.sp
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.PaymentTarget
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.model.shouldRenderAsCard
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary

/** Maximum number of OG preview cards rendered per note. */
private const val MAX_OG_CARDS = 1
private const val COLLAPSED_CARD_SCREEN_FRACTION = 0.80f
private const val COLLAPSED_CARD_CHROME_DP = 104f

/**
 * Walks the segment list from an [EventModel] and renders each content section
 * in source order.
 *
 * Image or video runs separated only by blank text collapse into one media
 * carousel; consecutive Text/MentionPubkey/Link runs become one InlineText
 * (URLs render as inline cyan clickable text).
 * Standalone links become preview blocks at their source position; sentence
 * links stay inline with a preview after the run (up to [MAX_OG_CARDS] per note).
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

    // Collapsed long-note budget: keep the whole card close to 80% of the
    // viewport, not just the text run. Reserve rough chrome for author header,
    // Show more, and action bar; the remaining height becomes text lines.
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val screenHeightDp = windowHeightPx / density.density
    val lineHeightDp = with(density) { 22.sp.toDp().value }
    val lineBudget = remember(screenHeightDp, lineHeightDp) {
        (((screenHeightDp * COLLAPSED_CARD_SCREEN_FRACTION) - COLLAPSED_CARD_CHROME_DP) /
            lineHeightDp).toInt().coerceIn(6, 32)
    }
    var expanded by remember(model.navigateId) { mutableStateOf(false) }
    val maxLines = when {
        isEmbedded -> 6
        !expanded -> lineBudget
        else -> Int.MAX_VALUE
    }
    val overflow = if (maxLines < Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip

    val hPad = if (isEmbedded) 0.dp else Spacing.medium

    // Resolve the actual card width before composing text. The overflow decision
    // and the entire source-order tail are final in the first layout frame.
    BoxWithConstraints(modifier = modifier) {
        val textWidthPx = (constraints.maxWidth - with(density) { hPad.roundToPx() * 2 }).coerceAtLeast(0)
        var hasTextOverflow = false
        Column {
            var i = 0
            var ogCardsRendered = 0
            var remainingLines = maxLines
            segmentLoop@ while (i < model.segments.size) {
                val current = model.segments[i]
                if (current.isInlineContent(knownLightningAddress)) {
                    // Collect consecutive text/mention/link/hashtag runs. A bare
                    // email-shaped LUD-16 candidate remains ordinary inline text
                    // unless the author's trusted profile advertises it as lud16.
                    var j = i
                    while (j < model.segments.size &&
                        model.segments[j].isInlineContent(knownLightningAddress)) j++
                    val allowPreview = ogCardsRendered < MAX_OG_CARDS
                    val blocks = remember(model.segments, i, j, allowPreview, knownLightningAddress) {
                        planInlineRun(model.segments.subList(i, j).map(Segment::asInlineContent), allowPreview)
                    }
                    for (block in blocks) {
                        if (remainingLines == 0) {
                            hasTextOverflow = true
                            break@segmentLoop
                        }
                        when (block) {
                            is InlineRunBlock.Text -> {
                                val layout = InlineText(
                                    segments      = block.segments,
                                    kind          = model.effectiveKind,
                                    lookupProfile = lookupProfile,
                                    onAuthorClick = actions.onAuthorClick,
                                    onHashtagClick = actions.onHashtagClick,
                                    onTextClick   = { actions.onNoteClick(navigateId) },
                                    customEmojis  = model.customEmojis,
                                    maxLines      = remainingLines,
                                    overflow      = overflow,
                                    overflowWidthPx = textWidthPx.takeIf { maxLines != Int.MAX_VALUE },
                                    modifier      = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = hPad)
                                        .padding(bottom = Spacing.small),
                                )
                                remainingLines = (remainingLines - layout.lineCount).coerceAtLeast(0)
                                hasTextOverflow = layout.hasOverflow
                                // Exact preflight keeps hidden media/quotes out of
                                // composition from frame one, including split runs.
                                if (hasTextOverflow) break@segmentLoop
                            }
                            is InlineRunBlock.Preview -> {
                                OgPreviewCard(
                                    url               = block.url,
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
                        }
                    }

                    i = j
                    continue@segmentLoop
                }

                if (remainingLines == 0) {
                    hasTextOverflow = true
                    break@segmentLoop
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
                        // Grant playback only for the media run actually composed.
                        // A collapsed long-text tail must not grant hidden audio.
                        val visibleVideoModels = remember(videos) { videos.map { it.model } }
                        if (showVideo && videoScope != null) {
                            DisposableEffect(videoScope, videoOwnerId, visibleVideoModels) {
                                val unregister = videoScope.registerVideoModels(videoOwnerId, visibleVideoModels)
                                onDispose { unregister() }
                            }
                        }
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
                        val layout = InlineText(
                            segments      = seg.segments,
                            kind          = model.effectiveKind,
                            lookupProfile = lookupProfile,
                            onAuthorClick = actions.onAuthorClick,
                            onHashtagClick = actions.onHashtagClick,
                            onTextClick   = { actions.onNoteClick(navigateId) },
                            customEmojis  = model.customEmojis,
                            maxLines      = remainingLines,
                            overflow      = overflow,
                            textColor     = TextSecondary,
                            overflowWidthPx = (textWidthPx - with(density) { Spacing.small.roundToPx() })
                                .takeIf { maxLines != Int.MAX_VALUE },
                            modifier      = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = hPad)
                                .padding(bottom = Spacing.small)
                                .drawBehind { drawRect(color = railColor, size = Size(3.dp.toPx(), size.height)) }
                                .padding(start = Spacing.small),
                        )
                        remainingLines = (remainingLines - layout.lineCount).coerceAtLeast(0)
                        hasTextOverflow = layout.hasOverflow
                        if (hasTextOverflow) break@segmentLoop
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

            // No onTextLayout state feedback: this row exists in the first layout.
            if (!isEmbedded && (hasTextOverflow || expanded)) {
                Text(
                    text     = if (expanded) "Show less" else "Show more",
                    color    = TextSecondary,
                    style = AppTextStyles.bodySmall,
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
                    style = AppTextStyles.caption,
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
