package com.unsilence.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
 * in source order using the Phase 2 primitives.
 *
 * Rendering order:
 * 1. Text content (InlineText — segments walked in order)
 * 2. Embedded quotes (QuoteCard for Segment.QuoteEvent)
 * 3. Embedded addresses (AddressChip for Segment.QuoteAddress)
 * 4. Images (EventMediaGrid — suppressed when linkUrls present)
 * 5. Videos (EventVideoGrid)
 * 6. YouTube embeds (YouTubeCard)
 * 7. Links (OgSection — first URL as rich preview, rest as chips)
 *
 * This matches the exact rendering order of NoteCard.kt.
 */
@Composable
internal fun ContentFlow(
    model: EventModel,
    role: CardRole,
    onNoteClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    lookupEvent: (suspend (String, List<String>) -> EventEntity?)?,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)?,
    imageDimensionCache: ImageDimensionCache?,
    // Video playback params
    isActiveVideo: Boolean = false,
    isFullscreen: Boolean = false,
    onOpenFullscreen: () -> Unit = {},
    exoPlayer: ExoPlayer? = null,
    isMuted: Boolean = true,
    onToggleMute: () -> Unit = {},
    thumbnailCache: VideoThumbnailCache? = null,
    modifier: Modifier = Modifier,
) {
    val navigateId = model.navigateId

    // Separate text-renderable segments from media/quote segments
    val hasTextContent = remember(model.segments) {
        model.segments.any { it is Segment.Text || it is Segment.MentionPubkey }
    }
    val textSegments = remember(model.segments) {
        model.segments.filter { it is Segment.Text || it is Segment.MentionPubkey }
    }
    val quoteEvents = remember(model.segments) {
        model.segments.filterIsInstance<Segment.QuoteEvent>()
    }
    val quoteAddresses = remember(model.segments) {
        model.segments.filterIsInstance<Segment.QuoteAddress>()
    }

    // Determine text length for collapse logic
    val textLength = remember(textSegments) {
        textSegments.sumOf {
            when (it) {
                is Segment.Text -> it.text.length
                else -> 20 // estimate for mention chips
            }
        }
    }
    val isLong = textLength > 300
    var expanded by remember { mutableStateOf(false) }

    // Check if we have link URLs (suppresses inline images, same as NoteCard)
    val hasLinks = model.media.ogCandidate != null

    // Video enabled only in Feed/Profile roles
    val showVideo = role == CardRole.Feed || role == CardRole.Profile

    Column(modifier = modifier) {
        // 1. Text content
        if (hasTextContent) {
            InlineText(
                segments      = textSegments,
                lookupProfile = lookupProfile,
                onAuthorClick = onAuthorClick,
                onTextClick   = { onNoteClick(navigateId) },
                maxLines      = if (isLong && !expanded) 8 else Int.MAX_VALUE,
                overflow      = if (isLong && !expanded) TextOverflow.Ellipsis else TextOverflow.Clip,
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.micro),
            )
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

        // 2. Embedded quotes
        quoteEvents.forEach { seg ->
            QuoteCard(
                segment         = seg,
                onNoteClick     = onNoteClick,
                onAuthorClick   = onAuthorClick,
                lookupEvent     = lookupEvent,
                lookupProfile   = lookupProfile,
                fetchOgMetadata = fetchOgMetadata,
                modifier        = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }

        // 3. Embedded addresses
        quoteAddresses.forEach { seg ->
            AddressChip(
                segment       = seg,
                onNoteClick   = onNoteClick,
                lookupProfile = lookupProfile,
                modifier      = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }

        // 4. Images (suppress when links present — OG preview shows hero image)
        if (model.media.images.isNotEmpty() && !hasLinks) {
            EventMediaGrid(
                images              = model.media.images,
                imageDimensionCache = imageDimensionCache,
                modifier            = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }

        // 5. Videos
        if (model.media.videos.isNotEmpty()) {
            EventVideoGrid(
                videos           = model.media.videos,
                isActiveVideo    = if (showVideo) isActiveVideo else false,
                isFullscreen     = isFullscreen,
                onOpenFullscreen = onOpenFullscreen,
                exoPlayer        = if (showVideo) exoPlayer else null,
                isMuted          = isMuted,
                onToggleMute     = onToggleMute,
                thumbnailCache   = thumbnailCache,
                modifier         = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }

        // 6. YouTube embeds
        model.media.youtubes.forEach { yt ->
            YouTubeCard(
                segment  = yt,
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
        }

        // 7. Link previews
        if (hasLinks || model.segments.any { it is Segment.Link }) {
            val additionalLinks = remember(model.segments) {
                model.segments.filterIsInstance<Segment.Link>()
                    .filter { it != model.media.ogCandidate }
            }
            OgSection(
                ogCandidate     = model.media.ogCandidate,
                additionalLinks = additionalLinks,
                fetchOgMetadata = fetchOgMetadata,
                modifier        = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }
    }
}
