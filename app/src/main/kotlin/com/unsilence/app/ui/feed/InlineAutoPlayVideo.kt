package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Size
import coil3.video.VideoFrameDecoder
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.ui.theme.Sizing

private val MediaPlaceholder = Color(0xFF1A1A1A)

/**
 * Build a Coil [ImageRequest] that extracts the first video frame as a thumbnail.
 * Uses [VideoFrameDecoder] with a capped decode size to limit bandwidth.
 * Only used when imeta provides no poster URL.
 */
@Composable
internal fun videoFrameRequest(videoUrl: String): ImageRequest =
    ImageRequest.Builder(LocalContext.current)
        .data(videoUrl)
        .decoderFactory(VideoFrameDecoder.Factory())
        .size(Size(Dimension(480), Dimension(270)))   // cap decode resolution
        .memoryCacheKey("vframe:$videoUrl")
        .build()

/**
 * Pure Compose video preview — poster image or first-frame thumbnail at the
 * correct aspect ratio with a centered play icon. No AndroidView, no
 * SurfaceView, no player. Used for ALL inactive video cards.
 *
 * Poster fallback chain: imeta thumb → VideoFrameDecoder first-frame → dark placeholder.
 */
@Composable
fun VideoPreviewCard(
    model: VideoRenderModel,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    forceSquare: Boolean = false,
) {
    val displayAspect = feedVideoAspectRatio(model.aspectRatio, forceSquare)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(displayAspect)
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(MediaPlaceholder)
            .clickable { onOpenFullscreen() },
        contentAlignment = Alignment.Center,
    ) {
        if (!model.posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = model.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            // No imeta poster — extract first frame via VideoFrameDecoder
            AsyncImage(
                model = videoFrameRequest(model.videoUrl),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        }

        // Play icon overlay
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/**
 * Live video playback composable — rendered ONLY for the ONE active video
 * in the feed. Contains a single AndroidView(PlayerView) that is reused
 * across video activations via media source swaps (no SurfaceView churn).
 *
 * Poster is shown underneath until the first video frame renders, then
 * the player covers it — zero black flash, zero resize.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun InlineVideoPlayer(
    model: VideoRenderModel,
    exoPlayer: ExoPlayer,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    forceSquare: Boolean = false,
) {
    val displayAspect = feedVideoAspectRatio(model.aspectRatio, forceSquare)
    var isFirstFrameRendered by remember { mutableStateOf(false) }

    // Reset first-frame flag when the video URL changes
    LaunchedEffect(model.videoUrl) { isFirstFrameRendered = false }

    // Listen for first rendered frame to hide poster
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isFirstFrameRendered = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(displayAspect)
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(MediaPlaceholder)
            .clickable { onOpenFullscreen() },
        contentAlignment = Alignment.Center,
    ) {
        // Poster underneath — visible until first frame renders
        if (!isFirstFrameRendered) {
            if (!model.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = model.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                // No imeta poster — extract first frame via VideoFrameDecoder
                AsyncImage(
                    model = videoFrameRequest(model.videoUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }

        // Stable AndroidView — created once, player swapped via update lambda.
        // NO key(videoUrl) — media source is swapped in VideoPlaybackScope.
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { view ->
                view.player = exoPlayer
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                view.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Mute toggle
        IconButton(
            onClick = onToggleMute,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(36.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape),
        ) {
            Icon(
                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff
                    else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
