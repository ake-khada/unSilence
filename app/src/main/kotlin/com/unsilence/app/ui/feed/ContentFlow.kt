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

    // Compute total text length for collapse logic (Text + Links contribute)
    val textLength = remember(model.segments) {
        model.segments.sumOf {
            when (it) {
                is Segment.Text -> it.text.length
                is Segment.MentionPubkey -> 20  // chip estimate
                is Segment.Link -> it.url.length
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

    val hPad = if (isEmbedded) 0.dp else Spacing.medium

    Column(modifier = modifier) {
        var i = 0
        var ogCardsRendered = 0
        while (i < model.segments.size) {
            when (model.segments[i]) {
                is Segment.Text, is Segment.MentionPubkey, is Segment.Link -> {
                    // Collect consecutive text/mention/link run
                    var j = i
                    while (j < model.segments.size &&
                        (model.segments[j] is Segment.Text ||
                            model.segments[j] is Segment.MentionPubkey ||
                            model.segments[j] is Segment.Link)) j++
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

                    InlineText(
                        segments      = runForInline,
                        lookupProfile = lookupProfile,
                        onAuthorClick = onAuthorClick,
                        onTextClick   = { onNoteClick(navigateId) },
                        maxLines      = maxLines,
                        overflow      = overflow,
                        modifier      = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = hPad)
                            .padding(bottom = Spacing.micro),
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
                    // Collect consecutive images — always render
                    var j = i
                    while (j < model.segments.size && model.segments[j] is Segment.Image) j++
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
                        lookupEvent     = lookupEvent,
                        lookupProfile   = lookupProfile,
                        lookupModel     = lookupModel,
                        fetchOgMetadata = fetchOgMetadata,
                        imageDimensionCache = imageDimensionCache,
                        nestDepth       = nestDepth,
                        modifier        = Modifier
                            .padding(horizontal = hPad)
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
                            .padding(horizontal = hPad)
                            .padding(bottom = Spacing.small),
                    )
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
