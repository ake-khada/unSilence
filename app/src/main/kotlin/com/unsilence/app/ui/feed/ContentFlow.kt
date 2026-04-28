package com.unsilence.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing

/**
 * Walks the segment list from an [EventModel] and renders each content section
 * in source order.
 *
 * Consecutive Image segments collapse into one EventMediaGrid, consecutive
 * Video segments into one EventVideoGrid, consecutive Text/MentionPubkey
 * runs into one InlineText. Everything else renders inline at its source
 * position.
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
    lookupProfile: (suspend (String) -> UserEntity?)?,
    lookupEvent: (suspend (String, List<String>) -> EventEntity?)?,
    lookupModel: ((String) -> EventModel?)? = null,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)?,
    imageDimensionCache: ImageDimensionCache?,
    isActiveVideo: Boolean = false,
    isFullscreen: Boolean = false,
    onOpenFullscreen: () -> Unit = {},
    exoPlayer: ExoPlayer? = null,
    isMuted: Boolean = true,
    onToggleMute: () -> Unit = {},
    thumbnailCache: VideoThumbnailCache? = null,
    nestDepth: Int = 0,
    modifier: Modifier = Modifier,
) {
    val navigateId = model.navigateId
    val showVideo = role != CardRole.Article
    val isEmbedded = role == CardRole.Embedded

    // Compute total text length for collapse logic (over Text segments only)
    val textLength = remember(model.segments) {
        model.segments.sumOf {
            when (it) {
                is Segment.Text -> it.text.length
                is Segment.MentionPubkey -> 20  // chip estimate
                else -> 0
            }
        }
    }
    // Embedded quotes: always compact (6 lines), no expand toggle
    val isLong = !isEmbedded && textLength > 300
    var expanded by remember { mutableStateOf(false) }
    val maxLines = when {
        isEmbedded -> 6
        isLong && !expanded -> 8
        else -> Int.MAX_VALUE
    }
    val overflow = if (maxLines < Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip

    // Primary link = first Segment.Link in source order. When present,
    // suppress inline images (OG card shows hero image instead).
    val ogCandidate = model.media.ogCandidate
    val suppressImages = ogCandidate != null

    Column(modifier = modifier) {
        var i = 0
        while (i < model.segments.size) {
            when (model.segments[i]) {
                is Segment.Text, is Segment.MentionPubkey -> {
                    // Collect consecutive text/mention run
                    var j = i
                    while (j < model.segments.size &&
                        (model.segments[j] is Segment.Text ||
                            model.segments[j] is Segment.MentionPubkey)) j++
                    val run = model.segments.subList(i, j).toList()
                    InlineText(
                        segments      = run,
                        lookupProfile = lookupProfile,
                        onAuthorClick = onAuthorClick,
                        onTextClick   = { onNoteClick(navigateId) },
                        maxLines      = maxLines,
                        overflow      = overflow,
                        modifier      = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (isEmbedded) 0.dp else Spacing.medium)
                            .padding(bottom = Spacing.micro),
                    )
                    i = j
                }
                is Segment.Image -> {
                    // Collect consecutive images, suppress if og candidate present
                    var j = i
                    while (j < model.segments.size && model.segments[j] is Segment.Image) j++
                    if (!suppressImages) {
                        val images = model.segments.subList(i, j)
                            .filterIsInstance<Segment.Image>()
                        EventMediaGrid(
                            images              = images,
                            imageDimensionCache = imageDimensionCache,
                            modifier            = Modifier
                                .padding(horizontal = if (isEmbedded) 0.dp else Spacing.medium)
                                .padding(bottom = Spacing.small),
                        )
                    }
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
                        isFullscreen     = isFullscreen,
                        onOpenFullscreen = onOpenFullscreen,
                        exoPlayer        = if (showVideo) exoPlayer else null,
                        isMuted          = isMuted,
                        onToggleMute     = onToggleMute,
                        thumbnailCache   = thumbnailCache,
                        modifier         = Modifier
                            .padding(horizontal = if (isEmbedded) 0.dp else Spacing.medium)
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
                        lookupEvent     = lookupEvent,
                        lookupProfile   = lookupProfile,
                        lookupModel     = lookupModel,
                        fetchOgMetadata = fetchOgMetadata,
                        imageDimensionCache = imageDimensionCache,
                        nestDepth       = nestDepth,
                        modifier        = Modifier
                            .padding(horizontal = if (isEmbedded) 0.dp else Spacing.medium)
                            .padding(bottom = Spacing.small),
                    )
                    i++
                }
                is Segment.QuoteAddress -> {
                    val seg = model.segments[i] as Segment.QuoteAddress
                    AddressChip(
                        segment       = seg,
                        onNoteClick   = onNoteClick,
                        lookupProfile = lookupProfile,
                        modifier      = Modifier
                            .padding(horizontal = if (isEmbedded) 0.dp else Spacing.medium)
                            .padding(bottom = Spacing.small),
                    )
                    i++
                }
                is Segment.YouTube -> {
                    val seg = model.segments[i] as Segment.YouTube
                    YouTubeCard(
                        segment  = seg,
                        modifier = Modifier.padding(
                            horizontal = if (isEmbedded) 0.dp else Spacing.medium,
                            vertical = Spacing.small,
                        ),
                    )
                    i++
                }
                is Segment.Link -> {
                    // Render OG preview only for the primary link (ogCandidate);
                    // additional links folded into OgSection's additionalLinks list.
                    val seg = model.segments[i] as Segment.Link
                    if (seg == ogCandidate) {
                        val additionalLinks = model.segments
                            .filterIsInstance<Segment.Link>()
                            .filter { it != ogCandidate }
                        OgSection(
                            ogCandidate     = seg,
                            additionalLinks = additionalLinks,
                            fetchOgMetadata = fetchOgMetadata,
                            modifier        = Modifier
                                .padding(horizontal = if (isEmbedded) 0.dp else Spacing.medium)
                                .padding(bottom = Spacing.small),
                        )
                    }
                    // Non-primary links are folded into OgSection's additionalLinks
                    // and don't render twice.
                    i++
                }
            }
        }

        // "Show more" toggle — placed after content if applicable (not in embedded quotes)
        if (isLong) {
            Text(
                text     = if (expanded) "Show less" else "Show more",
                color    = Cyan,
                fontSize = AppType.bodySmall,
                modifier = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.micro)
                    .clickable { expanded = !expanded },
            )
        }
    }
}
