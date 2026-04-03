package com.unsilence.app.ui.shared

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.data.model.buildVideoRenderModels
import com.unsilence.app.ui.feed.SharedPlayerHolder
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Consolidated video playback state for a scrolling list of events.
 *
 * Replaces the ~80 identical lines previously copy-pasted across
 * FeedScreen, ProfileScreen, and UserProfileScreen.
 */
@Stable
class VideoPlaybackScope(
    val exoPlayer: ExoPlayer,
    private val holder: SharedPlayerHolder,
    private val ownerId: String,
) {
    var activeVideoNoteId by mutableStateOf<String?>(null)
        internal set
    var isMuted by mutableStateOf(true)
    var showFullscreenVideo by mutableStateOf(false)
        internal set
    var preFullscreenMuted by mutableStateOf(true)
        internal set

    /** Pre-computed video render models keyed by event ID. */
    var videoRenderModels by mutableStateOf<Map<String, List<VideoRenderModel>>>(emptyMap())
        internal set

    fun isActiveVideo(noteId: String): Boolean = noteId == activeVideoNoteId

    fun toggleMute() { isMuted = !isMuted }

    fun openFullscreen(noteId: String) {
        activeVideoNoteId = noteId
        preFullscreenMuted = isMuted
        isMuted = false
        showFullscreenVideo = true
    }

    fun dismissFullscreen() {
        showFullscreenVideo = false
        isMuted = preFullscreenMuted
    }
}

/**
 * Creates and wires a [VideoPlaybackScope] with lifecycle, mute sync,
 * playback transitions, and active-video detection — all the plumbing
 * that was previously duplicated per screen.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun rememberVideoPlaybackScope(
    ownerId: String,
    holder: SharedPlayerHolder,
    events: List<FeedRow>,
    listState: LazyListState,
): VideoPlaybackScope {
    val exoPlayer = holder.player
    val scope = remember(ownerId) { VideoPlaybackScope(exoPlayer, holder, ownerId) }

    // Release ownership on disposal
    DisposableEffect(ownerId) { onDispose { holder.releaseOwnership(ownerId) } }

    // Lifecycle pause/resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.playWhenReady = false
                Lifecycle.Event.ON_RESUME -> if (scope.activeVideoNoteId != null) exoPlayer.playWhenReady = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Mute sync
    LaunchedEffect(scope.isMuted) {
        exoPlayer.volume = if (scope.isMuted) 0f else 1f
    }

    // Precompute VideoRenderModels for all events (moved from NoteCard composable)
    val renderModelsMap = remember(events) {
        events
            .filter { it.kind != 30023 }
            .mapNotNull { row ->
                val models = buildVideoRenderModels(row)
                if (models.isNotEmpty()) row.id to models else null
            }
            .toMap()
    }
    scope.videoRenderModels = renderModelsMap

    val noteIdsWithVideo = remember(renderModelsMap) { renderModelsMap.keys }

    // Playback transitions: swap media source on active note change
    val activeVideoUrl = remember(scope.activeVideoNoteId, renderModelsMap) {
        scope.activeVideoNoteId?.let { noteId ->
            renderModelsMap[noteId]?.firstOrNull()?.videoUrl
        }
    }

    LaunchedEffect(activeVideoUrl) {
        if (activeVideoUrl != null) {
            holder.claim(ownerId)
            val currentUrl = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
            if (currentUrl != activeVideoUrl) {
                exoPlayer.setMediaItem(MediaItem.fromUri(activeVideoUrl))
                exoPlayer.prepare()
            }
            exoPlayer.playWhenReady = true
        } else {
            if (holder.isOwner(ownerId)) {
                exoPlayer.stop()
            }
        }
    }

    // Active video detection via scroll position with visibility thresholds.
    // Activate at >=60% visible; deactivate below 35% (hysteresis prevents oscillation).
    val noteIdsRef = rememberUpdatedState(noteIdsWithVideo)
    val showFullscreenRef = rememberUpdatedState(scope.showFullscreenVideo)
    val activeRef = rememberUpdatedState(scope.activeVideoNoteId)
    LaunchedEffect(Unit) {
        snapshotFlow { listState.layoutInfo }
            .map { layoutInfo ->
                if (showFullscreenRef.value) return@map activeRef.value
                val currentIds = noteIdsRef.value
                val viewportStart = layoutInfo.viewportStartOffset
                val viewportEnd = layoutInfo.viewportEndOffset
                val viewportCenter = (viewportStart + viewportEnd) / 2

                val videoItems = layoutInfo.visibleItemsInfo
                    .filter { (it.key as? String) in currentIds }

                fun visibilityFraction(item: androidx.compose.foundation.lazy.LazyListItemInfo): Float {
                    val visibleTop = maxOf(item.offset, viewportStart)
                    val visibleBottom = minOf(item.offset + item.size, viewportEnd)
                    return if (item.size > 0) maxOf(0, visibleBottom - visibleTop).toFloat() / item.size else 0f
                }

                val currentActive = activeRef.value

                // Check if current active video is still above deactivation threshold (35%)
                val currentActiveItem = videoItems.firstOrNull { (it.key as? String) == currentActive }
                val currentStillVisible = currentActiveItem != null && visibilityFraction(currentActiveItem) >= 0.35f

                // Find best activation candidate (>=60% visible, closest to center)
                val candidate = videoItems
                    .filter { visibilityFraction(it) >= 0.6f }
                    .minByOrNull { abs(it.offset + it.size / 2 - viewportCenter) }
                    ?.key as? String

                when {
                    candidate != null -> candidate
                    currentStillVisible -> currentActive
                    else -> null
                }
            }
            .debounce(500)  // require 500ms stability before activation
            .distinctUntilChanged()
            .flatMapLatest { candidate ->
                if (candidate == null && activeRef.value != null) {
                    // Delay nullification — avoid surface churn during quick scroll
                    flow {
                        delay(1000)
                        emit(null as String?)
                    }
                } else {
                    flowOf(candidate)
                }
            }
            .distinctUntilChanged()
            .collect { newActiveId ->
                if (scope.activeVideoNoteId != newActiveId) {
                    scope.activeVideoNoteId = newActiveId
                    if (newActiveId == null) {
                        exoPlayer.playWhenReady = false
                    }
                }
            }
    }

    return scope
}
