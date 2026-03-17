package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import com.unsilence.app.ui.theme.Sizing

private val MediaPlaceholder = Color(0xFF1A1A1A)

/**
 * Thumbnail placeholder for video cells. Never creates a PlayerView — the
 * parent screen renders a single overlay PlayerView on top of the active cell.
 *
 * When [isActive], reports its layout position via [onPositioned] so the
 * overlay can track this cell, and hides the play icon (the overlay covers it).
 */
@Composable
fun InlineAutoPlayVideo(
    videoUrl: String,
    aspectRatio: Float?,
    isActive: Boolean,
    onOpenFullscreen: () -> Unit,
    thumbnailUrl: String? = null,
    onPositioned: ((LayoutCoordinates) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val rawAspect = if (aspectRatio != null && aspectRatio > 0f) aspectRatio else 16f / 9f
    // Cap portrait videos so they don't dominate the feed (max height = 1.5x width)
    val displayAspect = if (rawAspect >= 1f) rawAspect else maxOf(rawAspect, 2f / 3f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(
                ratio = displayAspect,
                matchHeightConstraintsFirst = false,
            )
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(MediaPlaceholder)
            .clickable { onOpenFullscreen() }
            .then(
                if (onPositioned != null) Modifier.onGloballyPositioned(onPositioned)
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Always render thumbnail — the overlay PlayerView draws on top when active
        if (!thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            // Fallback: extract first frame from video URL via Coil VideoFrameDecoder
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(videoUrl)
                    .decoderFactory(VideoFrameDecoder.Factory())
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }

        // Play icon — hidden when active (overlay covers it)
        if (!isActive) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}
