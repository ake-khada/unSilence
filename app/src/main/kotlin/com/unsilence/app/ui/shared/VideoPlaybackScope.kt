package com.unsilence.app.ui.shared

import android.util.Log
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
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.data.model.buildVideoRenderModels
import com.unsilence.app.ui.feed.SharedPlayerHolder
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
    companion object {
        /** A candidate must hold ≥60% visibility for this long before activation.
         *  After this delay, scroll must have settled before activation fires. */
        internal const val ACTIVATION_CONFIRMATION_MS = 400L
        /** After scroll stops, wait this long before activating to avoid
         *  triggering during momentary scroll pauses. */
        internal const val SCROLL_SETTLE_MS = 250L
        /** After visible item count changes, freeze detection for this long. */
        internal const val LAYOUT_SHIFT_COOLDOWN_MS = 500L
        /** Block A→B→A bounce-back transitions within this window. */
        internal const val OSCILLATION_BLOCK_MS = 3000L
    }

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

    // Detector state — written only from the detection coroutine (main thread)
    internal var lastActiveTransitionAt: Long = 0L
    internal var previousActiveId: String? = null
    internal var lastVisibleItemCount: Int = -1
    internal var lastLayoutShiftAt: Long = 0L

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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun rememberVideoPlaybackScope(
    ownerId: String,
    holder: SharedPlayerHolder,
    events: List<FeedRow>,
    listState: LazyListState,
    videoModelProvider: ((String) -> List<VideoRenderModel>)? = null,
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

    // Read pre-computed VideoRenderModels from MES sidecar cache (populated at insert time).
    // Falls back to buildVideoRenderModels(row) for events not in cache (e.g. optimistic inserts).
    val renderModelsMap = remember(events) {
        events
            .filter { it.kind != 30023 }
            .mapNotNull { row ->
                val models = videoModelProvider?.invoke(row.id)?.takeIf { it.isNotEmpty() }
                    ?: buildVideoRenderModels(row)
                if (models.isNotEmpty()) row.id to models else null
            }
            .toMap()
    }
    scope.videoRenderModels = renderModelsMap

    val noteIdsWithVideo = remember(renderModelsMap) { renderModelsMap.keys }

    // Playback transitions: swap media source on active note change.
    // B2 contract:
    //   Deactivation: playWhenReady=false (retain codec+media). No stop(), no clearMediaItems().
    //   Reactivation same URL: playWhenReady=true. No prepare(), no codec realloc.
    //   Reactivation different URL: stop()+clearMediaItems(), then setMediaItem()+prepare().
    val activeVideoUrl = remember(scope.activeVideoNoteId, renderModelsMap) {
        scope.activeVideoNoteId?.let { noteId ->
            renderModelsMap[noteId]?.firstOrNull()?.videoUrl
        }
    }

    LaunchedEffect(activeVideoUrl) {
        if (activeVideoUrl != null) {
            val retainedUrl = holder.currentUrl
            holder.claim(ownerId)
            if (retainedUrl == activeVideoUrl && holder.isRetained.not()) {
                // Same URL, player was already claimed (not retained) — just ensure playing
                exoPlayer.playWhenReady = true
            } else if (retainedUrl == activeVideoUrl) {
                // Same URL, player was retained (codec alive) — resume without re-prepare
                exoPlayer.playWhenReady = true
            } else {
                // Different URL — full media swap (the one path where codec realloc is correct)
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.setMediaItem(MediaItem.fromUri(activeVideoUrl))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        } else {
            if (holder.isOwner(ownerId)) {
                // B2: retain codec — just release ownership (sets playWhenReady=false,
                // starts 15s retention timer)
                holder.releaseOwnership(ownerId)
            }
        }
    }

    // Active video detection via scroll position with visibility thresholds.
    // Activate at >=60% visible; deactivate below 35% (hysteresis prevents oscillation).
    //
    // Three layers of flap protection:
    //   1. Layout shift cooldown: for 500ms after visible item count changes,
    //      the detector returns the current active (no change). Catches
    //      hydration-induced reflow without blocking normal scroll.
    //   2. Confirmation: a candidate must hold for 250ms continuous before
    //      promotion. flatMapLatest cancels stale candidates automatically.
    //   3. Oscillation detection: in collect, block A→B→A bounce-back
    //      transitions within OSCILLATION_BLOCK_MS. Targets the specific
    //      pathology (13 alternating transitions in 12s) without suppressing
    //      normal sequential transitions A→B→C.
    val noteIdsRef = rememberUpdatedState(noteIdsWithVideo)
    val showFullscreenRef = rememberUpdatedState(scope.showFullscreenVideo)
    val activeRef = rememberUpdatedState(scope.activeVideoNoteId)
    LaunchedEffect(Unit) {
        snapshotFlow { listState.layoutInfo }
            .map { layoutInfo ->
                // Fullscreen freeze
                if (showFullscreenRef.value) return@map activeRef.value

                // Layout shift cooldown — only while stationary. During scroll,
                // visible item count changes are expected (items of different
                // heights enter/leave viewport). While stationary, count changes
                // indicate hydration-induced reflow — the actual flap trigger.
                val now = System.currentTimeMillis()
                val visibleCount = layoutInfo.visibleItemsInfo.size
                if (!listState.isScrollInProgress) {
                    if (visibleCount != scope.lastVisibleItemCount && scope.lastVisibleItemCount >= 0) {
                        scope.lastLayoutShiftAt = now
                    }
                    if (now - scope.lastLayoutShiftAt < VideoPlaybackScope.LAYOUT_SHIFT_COOLDOWN_MS) {
                        scope.lastVisibleItemCount = visibleCount
                        return@map activeRef.value
                    }
                }
                scope.lastVisibleItemCount = visibleCount

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
            .distinctUntilChanged()
            .flatMapLatest { candidate ->
                flow {
                    val current = activeRef.value

                    // Null deactivation or no change: immediate pass-through
                    if (candidate == null || candidate == current) {
                        emit(candidate)
                        return@flow
                    }

                    // Confirmation window — candidate must hold for the
                    // confirmation period. If a different candidate arrives,
                    // flatMapLatest cancels this coroutine automatically.
                    delay(VideoPlaybackScope.ACTIVATION_CONFIRMATION_MS)

                    // Wait for scroll to fully settle — don't activate during
                    // momentary scroll pauses. The user must be stationary for
                    // SCROLL_SETTLE_MS after the last scroll gesture.
                    do {
                        while (listState.isScrollInProgress) {
                            delay(50)
                        }
                        delay(VideoPlaybackScope.SCROLL_SETTLE_MS)
                    } while (listState.isScrollInProgress)

                    emit(candidate)
                }
            }
            .distinctUntilChanged()
            .collect { newActiveId ->
                if (scope.activeVideoNoteId != newActiveId) {
                    val now = System.currentTimeMillis()

                    // Oscillation detection: block A→B→A bounce-back within
                    // OSCILLATION_BLOCK_MS. This targets layout-shift-induced
                    // flap (13 alternations in 12s) without blocking normal
                    // sequential transitions or legitimate scroll-back.
                    if (newActiveId != null && newActiveId == scope.previousActiveId) {
                        val elapsed = now - scope.lastActiveTransitionAt
                        if (elapsed < VideoPlaybackScope.OSCILLATION_BLOCK_MS) {
                            return@collect
                        }
                    }

                    val oldId = scope.activeVideoNoteId
                    Log.d("VideoScope", "Active video: ${oldId?.take(8) ?: "none"} → ${newActiveId?.take(8) ?: "none"}")
                    scope.previousActiveId = oldId
                    scope.activeVideoNoteId = newActiveId
                    scope.lastActiveTransitionAt = now
                    if (newActiveId == null) {
                        exoPlayer.playWhenReady = false
                    }
                }
            }
    }

    return scope
}
