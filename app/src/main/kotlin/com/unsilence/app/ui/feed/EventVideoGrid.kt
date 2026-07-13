package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Surface1

private val MediaPlaceholder = Surface1

/**
 * Video grid for pre-parsed [Segment.Video] list from [EventModel.media.videos].
 *
 * Layout: 1=full (autoplay), 2=side-by-side, 3=1+2, 4+=2x2 with +N overlay.
 * Primary cell renders InlineVideoPlayer when active, VideoPreviewCard otherwise.
 * Secondary cells are always static thumbnails.
 */
@Composable
internal fun EventVideoGrid(
    videos: List<Segment.Video>,
    isActiveVideo: Boolean,
    isFullscreen: Boolean,
    onOpenFullscreen: () -> Unit,
    exoPlayer: ExoPlayer?,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    thumbnailCache: VideoThumbnailCache? = null,
    // URL bound to the shared player for the active row. When non-null, the
    // primary cell attaches the player ONLY if its own model.videoUrl matches —
    // so a row with both its own video and a quoted video drives only the one
    // the active URL points at. Null = caller didn't supply it: fall back to
    // grid-level isActiveVideo (preserves existing behavior).
    activeVideoUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    if (videos.isEmpty()) return

    val count = videos.size

    @Composable
    fun PrimaryVideoCell(video: Segment.Video, cellModifier: Modifier = Modifier, forceSquare: Boolean = false) {
        val model = video.model
        // Attach the shared player only when this cell's video is the one bound
        // to the active URL (or when no URL was supplied — legacy fallback).
        val urlMatchesActive = activeVideoUrl == null || model.videoUrl == activeVideoUrl
        if (isActiveVideo && urlMatchesActive && exoPlayer != null) {
            InlineVideoPlayer(
                model            = model,
                exoPlayer        = exoPlayer,
                isMuted          = isMuted,
                onToggleMute     = onToggleMute,
                onOpenFullscreen = onOpenFullscreen,
                forceSquare      = forceSquare,
                thumbnailCache   = thumbnailCache,
                isFullscreen     = isFullscreen,
                modifier         = cellModifier,
            )
        } else {
            VideoPreviewCard(
                model            = model,
                onOpenFullscreen = onOpenFullscreen,
                forceSquare      = forceSquare,
                thumbnailCache   = thumbnailCache,
                modifier         = cellModifier,
            )
        }
    }

    @Composable
    fun SecondaryVideoCell(video: Segment.Video, cellModifier: Modifier = Modifier, forceSquare: Boolean = false) {
        EventVideoThumbnailCell(
            model          = video.model,
            thumbnailCache = thumbnailCache,
            onPlay         = onOpenFullscreen,
            modifier       = cellModifier,
            forceSquare    = forceSquare,
        )
    }

    when {
        count == 1 -> {
            PrimaryVideoCell(video = videos[0], cellModifier = modifier)
        }
        count == 2 -> {
            Row(
                modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PrimaryVideoCell(
                    video = videos[0],
                    cellModifier = Modifier.weight(1f),
                    forceSquare = true,
                )
                SecondaryVideoCell(
                    video = videos[1],
                    cellModifier = Modifier.weight(1f),
                    forceSquare = true,
                )
            }
        }
        count == 3 -> {
            Column(
                modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PrimaryVideoCell(
                    video = videos[0],
                    cellModifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SecondaryVideoCell(
                        video = videos[1],
                        cellModifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                    SecondaryVideoCell(
                        video = videos[2],
                        cellModifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                }
            }
        }
        else -> {
            val gridVideos = videos.take(4)
            val overflow = count - 4
            Column(
                modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PrimaryVideoCell(
                        video = gridVideos[0],
                        cellModifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                    SecondaryVideoCell(
                        video = gridVideos[1],
                        cellModifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SecondaryVideoCell(
                        video = gridVideos[2],
                        cellModifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        SecondaryVideoCell(
                            video = gridVideos[3],
                            cellModifier = Modifier.fillMaxWidth(),
                            forceSquare = true,
                        )
                        if (overflow > 0) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .clickable { onOpenFullscreen() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text       = "+$overflow",
                                    color      = Color.White,
                                    fontSize   = AppType.display,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Static video thumbnail cell — poster/first-frame with play button overlay.
 * Uses the pre-computed [VideoRenderModel] from the segment.
 */
@Composable
private fun EventVideoThumbnailCell(
    model: VideoRenderModel,
    thumbnailCache: VideoThumbnailCache? = null,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    forceSquare: Boolean = false,
) {
    val imetaAspect = model.imetaAspectRatio
    val cachedRatio = thumbnailCache?.resolvedAspectRatios?.get(model.videoUrl)
    val initialAspect = when {
        !forceSquare && cachedRatio != null -> feedVideoAspectRatio(cachedRatio)
        imetaAspect != null -> feedVideoAspectRatio(imetaAspect, forceSquare)
        else -> feedVideoAspectRatio(model.aspectRatio, forceSquare)
    }
    var displayAspect by remember(model.videoUrl, forceSquare) { mutableStateOf(initialAspect) }
    var hasBeenResolved by remember(model.videoUrl, forceSquare) {
        mutableStateOf(forceSquare || cachedRatio != null)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(displayAspect, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(MediaPlaceholder)
            .clickable { onPlay() },
        contentAlignment = Alignment.Center,
    ) {
        VideoThumbnailImage(
            model = model,
            thumbnailCache = thumbnailCache,
            modifier = Modifier.matchParentSize(),
            requestAspectRatio = displayAspect,
            // A square mosaic is the sole card-surface crop exception.
            contentScale = if (forceSquare) ContentScale.Crop else ContentScale.Fit,
            onAspectRatioResolved = if (!hasBeenResolved && !forceSquare) {
                { ratio ->
                    if (!hasBeenResolved) {
                        val resolvedAspect = feedVideoAspectRatio(ratio)
                        if (shouldCorrectVideoAspectRatio(displayAspect, resolvedAspect)) {
                            displayAspect = resolvedAspect
                        }
                        hasBeenResolved = true
                    }
                }
            } else null,
        )
        Icon(
            imageVector        = Icons.Filled.PlayArrow,
            contentDescription = "Play video",
            tint               = Color.White.copy(alpha = 0.85f),
            modifier           = Modifier.size(52.dp),
        )
    }
}
