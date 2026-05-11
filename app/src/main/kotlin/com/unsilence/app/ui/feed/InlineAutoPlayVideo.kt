package com.unsilence.app.ui.feed

import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Surface1

private val MediaPlaceholder = Surface1

/**
 * Composable that shows a video thumbnail: imeta poster if available,
 * otherwise fetches the first frame via [VideoThumbnailCache] (MediaMetadataRetriever
 * with HTTP range requests — lightweight). Shows dark placeholder at correct
 * aspect ratio while loading.
 *
 * When a thumbnail bitmap is fetched, its native aspect ratio is reported
 * via [onAspectRatioResolved] so the parent container can resize.
 */
@Composable
internal fun VideoThumbnailImage(
    model: VideoRenderModel,
    thumbnailCache: VideoThumbnailCache?,
    modifier: Modifier = Modifier,
    onAspectRatioResolved: ((Float) -> Unit)? = null,
) {
    if (!model.posterUrl.isNullOrBlank()) {
        AsyncImage(
            model = rememberFullWidthImageRequest(model.posterUrl),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier,
            onSuccess = { state ->
                val w = state.result.image.width
                val h = state.result.image.height
                if (w > 0 && h > 0) {
                    onAspectRatioResolved?.invoke(w.toFloat() / h)
                }
            },
        )
    } else if (thumbnailCache != null) {
        // Track visibility for LRU eviction protection
        DisposableEffect(model.videoUrl) {
            thumbnailCache.markVisible(model.videoUrl)
            onDispose { thumbnailCache.markNotVisible(model.videoUrl) }
        }
        // Seed from cache synchronously — no blank frame on recomposition
        var thumbnail by remember(model.videoUrl) {
            mutableStateOf(thumbnailCache.getCached(model.videoUrl))
        }
        LaunchedEffect(model.videoUrl) {
            if (thumbnail != null) return@LaunchedEffect // already have it
            thumbnailCache.getThumbnail(model.videoUrl)?.let {
                thumbnail = it
                onAspectRatioResolved?.invoke(it.aspectRatio)
            }
        }
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier,
            )
        }
        // While loading (thumbnail == null): dark placeholder shows through from parent Box
    }
}

/**
 * Pure Compose video preview — poster image or first-frame thumbnail at the
 * correct aspect ratio with a centered play icon. No AndroidView, no
 * SurfaceView, no player. Used for ALL inactive video cards.
 *
 * Poster fallback chain: imeta thumb → MediaMetadataRetriever first-frame → dark placeholder.
 * When the thumbnail bitmap arrives, its native aspect ratio overrides the container.
 */
@Composable
fun VideoPreviewCard(
    model: VideoRenderModel,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    forceSquare: Boolean = false,
    thumbnailCache: VideoThumbnailCache? = null,
) {
    // Use imeta dimensions as authoritative aspect ratio when available.
    // Only fall back to bitmap/cache aspect ratio if imeta has no dimensions.
    val imetaKnown = model.widthPx != null && model.heightPx != null && model.heightPx > 0
    val cachedRatio = thumbnailCache?.resolvedAspectRatios?.get(model.videoUrl)
    val initialAspect = when {
        imetaKnown -> feedVideoAspectRatio(model.widthPx!!.toFloat() / model.heightPx!!, forceSquare)
        !forceSquare && cachedRatio != null -> feedVideoAspectRatio(cachedRatio, false)
        else -> feedVideoAspectRatio(model.aspectRatio, forceSquare)
    }
    var displayAspect by remember(model.videoUrl, forceSquare) { mutableStateOf(initialAspect) }
    // Track whether ratio has been resolved from a real source (imeta or MMR).
    // Allow ONE update from default → resolved, then lock permanently.
    var hasBeenResolved by remember(model.videoUrl, forceSquare) {
        mutableStateOf(imetaKnown || cachedRatio != null)
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
        VideoThumbnailImage(
            model = model,
            thumbnailCache = thumbnailCache,
            modifier = Modifier.matchParentSize(),
            onAspectRatioResolved = if (!hasBeenResolved && !forceSquare) {
                { ratio ->
                    displayAspect = feedVideoAspectRatio(ratio, false)
                    hasBeenResolved = true
                }
            } else null,
        )

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
 *
 * Reads [VideoThumbnailCache.resolvedAspectRatios] so its container matches
 * the preview card's container exactly — zero jump on activation.
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
    thumbnailCache: VideoThumbnailCache? = null,
    isFullscreen: Boolean = false,
) {
    // Use imeta dimensions as authoritative aspect ratio when available.
    // Only fall back to bitmap/cache aspect ratio if imeta has no dimensions.
    val imetaKnown = model.widthPx != null && model.heightPx != null && model.heightPx > 0
    val resolvedRatio = thumbnailCache?.resolvedAspectRatios?.get(model.videoUrl)
    val baseAspect = when {
        imetaKnown -> feedVideoAspectRatio(model.widthPx!!.toFloat() / model.heightPx!!, forceSquare)
        !forceSquare && resolvedRatio != null -> feedVideoAspectRatio(resolvedRatio, false)
        else -> feedVideoAspectRatio(model.aspectRatio, forceSquare)
    }
    var displayAspect by remember(model.videoUrl, forceSquare) { mutableStateOf(baseAspect) }
    // Track whether ratio has been resolved from a real source (imeta or MMR).
    // Allow ONE update from default → resolved, then lock permanently.
    var hasBeenResolved by remember(model.videoUrl, forceSquare) {
        mutableStateOf(imetaKnown || resolvedRatio != null)
    }

    var isFirstFrameRendered by remember { mutableStateOf(false) }

    // Reset first-frame flag when the video URL changes
    LaunchedEffect(model.videoUrl) { isFirstFrameRendered = false }

    // Listen for first rendered frame + actual video dimensions
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isFirstFrameRendered = true
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = videoSize.width.toFloat() / videoSize.height
                    thumbnailCache?.resolvedAspectRatios?.put(model.videoUrl, ratio)
                    // Allow ONE update from default → resolved for unresolved videos
                    if (!hasBeenResolved && !forceSquare) {
                        displayAspect = feedVideoAspectRatio(ratio, false)
                        hasBeenResolved = true
                    }
                }
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
            VideoThumbnailImage(
                model = model,
                thumbnailCache = thumbnailCache,
                modifier = Modifier.matchParentSize(),
                // Layout locked — no resize after first compose
                onAspectRatioResolved = null,
            )
        }

        // Stateless factory — player attached via update lambda.
        // When fullscreen is open, player detaches from inline surface so
        // fullscreen dialog's PlayerView has exclusive surface ownership.
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { view ->
                view.player = if (!isFullscreen) exoPlayer else null
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                view.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            },
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (isFirstFrameRendered) 1f else 0f),
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
