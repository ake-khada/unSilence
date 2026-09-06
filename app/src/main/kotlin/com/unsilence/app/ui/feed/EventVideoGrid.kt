package com.unsilence.app.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1

private val MediaPlaceholder = Surface1

/**
 * Video display for a pre-parsed [Segment.Video] list from [EventModel.media.videos].
 *
 * One video keeps the canonical autoplay card. Multiple videos use the same
 * dominance-gated horizontal pager as image carousels, with one shared player
 * attached only to the selected page.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun EventVideoGrid(
    videos: List<Segment.Video>,
    isActiveVideo: Boolean,
    activeVideoUrl: String?,
    isFullscreen: Boolean,
    selectedVideoUrl: String?,
    onVideoSelected: ((VideoRenderModel) -> Unit)?,
    onOpenFullscreen: (VideoRenderModel) -> Unit,
    exoPlayer: ExoPlayer?,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    thumbnailCache: VideoThumbnailCache? = null,
    modifier: Modifier = Modifier,
) {
    if (videos.isEmpty()) return

    @Composable
    fun VideoCell(
        video: Segment.Video,
        cellModifier: Modifier = Modifier,
        pagerGestureModifier: Modifier? = null,
    ) {
        val model = video.model
        // A multi-video pager may compose adjacent pages. Only the selected URL
        // may attach the singleton player; a missing URL safely shows previews.
        if (isActiveVideo && model.videoUrl == activeVideoUrl && exoPlayer != null) {
            InlineVideoPlayer(
                model            = model,
                exoPlayer        = exoPlayer,
                isMuted          = isMuted,
                onToggleMute     = onToggleMute,
                onOpenFullscreen = { onOpenFullscreen(model) },
                thumbnailCache   = thumbnailCache,
                isFullscreen     = isFullscreen,
                modifier         = cellModifier,
                pagerGestureModifier = pagerGestureModifier,
            )
        } else {
            VideoPreviewCard(
                model            = model,
                onOpenFullscreen = { onOpenFullscreen(model) },
                thumbnailCache   = thumbnailCache,
                modifier         = cellModifier,
                pagerGestureModifier = pagerGestureModifier,
            )
        }
    }

    if (videos.size == 1) {
        VideoCell(video = videos[0], cellModifier = modifier)
        return
    }

    val initialPage = videos
        .indexOfFirst { it.model.videoUrl == selectedVideoUrl }
        .coerceAtLeast(0)
    val frameModel = videos.first().model
    val frameAspect = remember(videos, thumbnailCache) {
        feedVideoAspectRatio(
            thumbnailCache?.resolvedAspectRatios?.get(frameModel.videoUrl)
                ?: frameModel.imetaAspectRatio
                ?: frameModel.aspectRatio,
        )
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { videos.size },
    )
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapPositionalThreshold = MEDIA_PAGER_SNAP_POSITIONAL_THRESHOLD,
    )
    val currentOnVideoSelected by rememberUpdatedState(onVideoSelected)
    val settledPage = pagerState.settledPage
    var lastSettledPage by remember(pagerState) { mutableIntStateOf(settledPage) }

    LaunchedEffect(settledPage, videos) {
        if (settledPage != lastSettledPage) {
            lastSettledPage = settledPage
            videos.getOrNull(settledPage)?.model?.let { currentOnVideoSelected?.invoke(it) }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalPager(
            state = pagerState,
            flingBehavior = flingBehavior,
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(frameAspect, matchHeightConstraintsFirst = false)
                .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
                .background(MediaPlaceholder),
        ) { page ->
            VideoCell(
                video = videos[page],
                cellModifier = Modifier.fillMaxSize(),
                pagerGestureModifier = Modifier.mediaPagerGestures(
                    pagerState = pagerState,
                    flingBehavior = flingBehavior,
                ),
            )
        }

        Row(
            modifier = Modifier.padding(top = Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(videos.size) { index ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            color = if (pagerState.currentPage == index) Brand
                                else Brand.copy(alpha = 0.3f),
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}
