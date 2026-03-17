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
import com.unsilence.app.data.relay.ImetaParser
import com.unsilence.app.ui.feed.SharedPlayerHolder
import com.unsilence.app.ui.feed.VIDEO_URL_REGEX
import com.unsilence.app.ui.feed.extractVideoUrl
import kotlin.math.abs
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
@OptIn(kotlinx.coroutines.FlowPreview::class)
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

    // Playback transitions: swap media source on active note change
    val activeVideoUrl = remember(scope.activeVideoNoteId, events) {
        scope.activeVideoNoteId?.let { noteId ->
            events.firstOrNull { it.id == noteId }?.let { extractVideoUrl(it) }
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

    // Precompute which notes have video
    val noteIdsWithVideo = remember(events) {
        events.filter { row ->
            row.kind != 30023 &&
                (ImetaParser.videos(row.tags).isNotEmpty() ||
                    VIDEO_URL_REGEX.containsMatchIn(row.content))
        }.map { it.id }.toSet()
    }

    // Active video detection via scroll position
    val noteIdsRef = rememberUpdatedState(noteIdsWithVideo)
    val showFullscreenRef = rememberUpdatedState(scope.showFullscreenVideo)
    val activeRef = rememberUpdatedState(scope.activeVideoNoteId)
    LaunchedEffect(Unit) {
        snapshotFlow { listState.layoutInfo }
            .map { layoutInfo ->
                if (showFullscreenRef.value) return@map activeRef.value
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
