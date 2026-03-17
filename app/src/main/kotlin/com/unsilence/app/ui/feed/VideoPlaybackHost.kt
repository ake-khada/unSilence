package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.relay.ImetaParser
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// ── State holder ────────────────────────────────────────────────────────────

/**
 * All mutable state for a single video-overlay surface.
 * One instance per screen, created via [rememberVideoPlaybackState].
 */
class VideoPlaybackState(
    val holder: SharedPlayerHolder,
    val ownerId: String,
) {
    val exoPlayer: ExoPlayer get() = holder.player

    var activeVideoNoteId by mutableStateOf<String?>(null)
    var isMuted by mutableStateOf(true)
    var showFullscreenVideo by mutableStateOf(false)
    var preFullscreenMuted by mutableStateOf(true)

    // Overlay positioning — updated by onGloballyPositioned callbacks
    var feedBoxCoords: LayoutCoordinates? = null
    var overlayOffset by mutableStateOf(Offset.Zero)
    var overlayWidth by mutableStateOf(0)
    var overlayHeight by mutableStateOf(0)
    var overlayVisible by mutableStateOf(false)

    /** Returns a positioning callback for NoteCard, or null if this row is not the active video. */
    fun onVideoPositionedFor(noteId: String): ((LayoutCoordinates) -> Unit)? {
        if (noteId != activeVideoNoteId) return null
        return { coords ->
            val box = feedBoxCoords
            if (box != null && coords.isAttached && box.isAttached) {
                val pos = box.localPositionOf(coords, Offset.Zero)
                overlayOffset = pos
                overlayWidth = coords.size.width
                overlayHeight = coords.size.height
                overlayVisible = true
            }
        }
    }

    fun openFullscreen(noteId: String) {
        activeVideoNoteId = noteId
        preFullscreenMuted = isMuted
        isMuted = false
        showFullscreenVideo = true
    }

    fun closeFullscreen() {
        showFullscreenVideo = false
        isMuted = preFullscreenMuted
    }
}

// ── Composable entry points ─────────────────────────────────────────────────

/**
 * Creates and remembers a [VideoPlaybackState], wiring lifecycle and cleanup effects.
 */
@Composable
fun rememberVideoPlaybackState(
    holder: SharedPlayerHolder,
    ownerId: String,
): VideoPlaybackState {
    val state = remember { VideoPlaybackState(holder, ownerId) }

    DisposableEffect(Unit) { onDispose { holder.releaseOwnership(ownerId) } }

    // Clear overlay when active video changes
    LaunchedEffect(state.activeVideoNoteId) {
        if (state.activeVideoNoteId == null) state.overlayVisible = false
    }

    // Lifecycle: pause on background, resume on foreground
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE  -> state.exoPlayer.playWhenReady = false
                Lifecycle.Event.ON_RESUME -> if (state.activeVideoNoteId != null) state.exoPlayer.playWhenReady = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Mute sync
    LaunchedEffect(state.isMuted) {
        state.exoPlayer.volume = if (state.isMuted) 0f else 1f
    }

    return state
}

/**
 * Drives media-item and play/stop transitions when the active video URL changes.
 */
@Composable
fun VideoPlaybackTransitions(
    state: VideoPlaybackState,
    events: List<FeedRow>,
) {
    val activeVideoUrl = remember(state.activeVideoNoteId, events) {
        state.activeVideoNoteId?.let { noteId ->
            events.firstOrNull { it.id == noteId }?.let { extractVideoUrl(it) }
        }
    }

    LaunchedEffect(activeVideoUrl) {
        if (activeVideoUrl != null) {
            state.holder.claim(state.ownerId)
            val currentUrl = state.exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
            if (currentUrl != activeVideoUrl) {
                state.exoPlayer.setMediaItem(MediaItem.fromUri(activeVideoUrl))
                state.exoPlayer.prepare()
            }
            state.exoPlayer.playWhenReady = true
        } else {
            if (state.holder.isOwner(state.ownerId)) {
                state.exoPlayer.stop()
            }
        }
    }
}

/**
 * Detects which video note is closest to the viewport centre and sets it as active.
 */
@Composable
fun ActiveVideoDetection(
    state: VideoPlaybackState,
    listState: LazyListState,
    noteIdsWithVideo: Set<String>,
) {
    val noteIdsRef = rememberUpdatedState(noteIdsWithVideo)
    val showFullscreenRef = rememberUpdatedState(state.showFullscreenVideo)

    LaunchedEffect(Unit) {
        snapshotFlow { listState.layoutInfo }
            .map { layoutInfo ->
                if (showFullscreenRef.value) return@map state.activeVideoNoteId
                val currentIds = noteIdsRef.value
                val viewportCenter = (layoutInfo.viewportStartOffset +
                    layoutInfo.viewportEndOffset) / 2
                layoutInfo.visibleItemsInfo
                    .filter { (it.key as? String) in currentIds }
                    .minByOrNull {
                        val itemCenter = it.offset + it.size / 2
                        abs(itemCenter - viewportCenter)
                    }
                    ?.key as? String
            }
            .debounce(300)
            .distinctUntilChanged()
            .collect { newActiveId ->
                if (state.activeVideoNoteId != newActiveId) {
                    state.activeVideoNoteId = newActiveId
                    if (newActiveId == null) {
                        state.exoPlayer.playWhenReady = false
                    }
                }
            }
    }
}

/**
 * Pre-computes which note IDs contain playable video for scroll detection.
 */
@Composable
fun rememberNoteIdsWithVideo(events: List<FeedRow>): Set<String> = remember(events) {
    events.filter { row ->
        row.kind != 30023 &&
        (ImetaParser.videos(row.tags).isNotEmpty() ||
            VIDEO_URL_REGEX.containsMatchIn(row.content))
    }.map { it.id }.toSet()
}

// ── Overlay rendering ───────────────────────────────────────────────────────

/**
 * Renders the single PlayerView overlay positioned over the active video cell,
 * plus a mute toggle button. Must be called inside a Box with clipToBounds().
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun BoxScope.VideoOverlay(state: VideoPlaybackState) {
    if (!state.overlayVisible || state.activeVideoNoteId == null || state.overlayWidth <= 0) return

    val density = LocalDensity.current

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = state.exoPlayer
                useController = false
                setEnableComposeSurfaceSyncWorkaround(true)
            }
        },
        update = { view -> view.player = state.exoPlayer },
        modifier = Modifier
            .offset { IntOffset(state.overlayOffset.x.roundToInt(), state.overlayOffset.y.roundToInt()) }
            .requiredWidth(with(density) { state.overlayWidth.toDp() })
            .requiredHeight(with(density) { state.overlayHeight.toDp() }),
    )

    IconButton(
        onClick = { state.isMuted = !state.isMuted },
        modifier = Modifier
            .offset {
                IntOffset(
                    (state.overlayOffset.x + state.overlayWidth - with(density) { 44.dp.toPx() }).roundToInt(),
                    (state.overlayOffset.y + with(density) { 8.dp.toPx() }).roundToInt(),
                )
            }
            .size(36.dp)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape),
    ) {
        Icon(
            imageVector = if (state.isMuted) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = if (state.isMuted) "Unmute" else "Mute",
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}
