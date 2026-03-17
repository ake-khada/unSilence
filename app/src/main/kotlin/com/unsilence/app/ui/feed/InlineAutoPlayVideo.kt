package com.unsilence.app.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.unsilence.app.ui.theme.Sizing

private val MediaPlaceholder = Color(0xFF1A1A1A)

/**
 * Inline video cell — poster is ALWAYS rendered, PlayerView fades in on top
 * when [isActive]. Same container box for both states: zero dimension jump.
 *
 * Uses [feedVideoAspectRatio] for container sizing so thumbnail and player
 * share identical geometry. PlayerView uses RESIZE_MODE_FIT with a transparent
 * shutter so the poster shows through until the first video frame arrives.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun InlineAutoPlayVideo(
    exoPlayer: ExoPlayer,
    videoUrl: String,
    aspectRatio: Float?,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onOpenFullscreen: () -> Unit,
    isActive: Boolean,
    thumbnailUrl: String? = null,
    forceSquare: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val displayAspect = feedVideoAspectRatio(aspectRatio, forceSquare)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(displayAspect)
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(MediaPlaceholder)
            .clickable { onOpenFullscreen() },
        contentAlignment = Alignment.Center,
    ) {
        // Poster ALWAYS rendered — same box, same ContentScale.
        // When no poster URL, use a cheap static placeholder instead of
        // VideoFrameDecoder which would download the video just to extract
        // a frame — far too expensive in a scrolling list.
        if (!thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            // Static placeholder — no remote video frame extraction
            Box(
                modifier = Modifier.matchParentSize().background(MediaPlaceholder),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(48.dp),
                )
            }
        }

        // Player fades in on top — same box, zero jump.
        // NO key(videoUrl) — that was destroying and recreating the
        // SurfaceView on every URL change, causing severe surface churn
        // (surfaceCreated/surfaceDestroyed spam). Instead the PlayerView
        // instance stays stable and the media source is swapped via
        // ExoPlayer.setMediaItem in the playback scope.
        AnimatedVisibility(
            visible = isActive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
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
                modifier = Modifier.matchParentSize(),
            )
        }

        // Play icon when inactive
        if (!isActive) {
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

        // Mute toggle when active
        if (isActive) {
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
}
