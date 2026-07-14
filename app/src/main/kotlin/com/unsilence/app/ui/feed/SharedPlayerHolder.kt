package com.unsilence.app.ui.feed

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.di.MediaClient
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SharedPlayerHolder"
private const val IMMERSIVE_PRELOAD_DURATION_MS = 3_000L
private const val NO_PRELOAD_INDEX = -1

@Singleton
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SharedPlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    @MediaClient private val mediaClient: OkHttpClient,
) {
    private var _player: ExoPlayer? = null
    private var _preloadManager: DefaultPreloadManager? = null
    private var _currentOwner: String? = null
    private var preloadCenterIndex = NO_PRELOAD_INDEX
    private val immersivePreloadEntries = linkedMapOf<String, PreloadEntry>()

    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            5_000,   // minBufferMs
            30_000,  // maxBufferMs
            500,     // bufferForPlaybackMs — start playing after 500ms (default 2500)
            1_000,   // bufferForPlaybackAfterRebufferMs
        )
        .build()

    private val mediaSourceFactory = DefaultMediaSourceFactory(
        OkHttpDataSource.Factory(mediaClient)
    )

    val player: ExoPlayer
        get() {
            if (_player == null) {
                val targetControl = TargetPreloadStatusControl<Int> { index ->
                    if (index == preloadCenterIndex + 1) {
                        DefaultPreloadManager.Status(
                            DefaultPreloadManager.Status.STAGE_LOADED_FOR_DURATION_MS,
                            IMMERSIVE_PRELOAD_DURATION_MS,
                        )
                    } else {
                        null
                    }
                }
                val preloadBuilder = DefaultPreloadManager.Builder(context, targetControl)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(mediaSourceFactory)
                _player = preloadBuilder.buildExoPlayer().apply {
                    volume = 0f
                    repeatMode = ExoPlayer.REPEAT_MODE_ALL
                }
                _preloadManager = preloadBuilder.build()
            }
            return _player!!
        }

    /** The URL currently loaded in the player (null if no media item). */
    val currentUrl: String?
        get() = _player?.currentMediaItem?.localConfiguration?.uri?.toString()

    /** True when the player has a loaded media item but no active owner. */
    val isRetained: Boolean
        get() = _currentOwner == null && _player?.currentMediaItem != null

    fun claim(ownerId: String): ExoPlayer {
        _currentOwner = ownerId
        return player
    }

    /**
     * Release ownership without tearing down the player. Sets playWhenReady=false
     * to pause playback, but keeps the codec and media item alive for the rest of
     * the foreground session. Codec teardown is lifecycle-driven only
     * (app background, memory pressure, logout).
     */
    fun releaseOwnership(ownerId: String) {
        if (_currentOwner == ownerId) {
            _player?.playWhenReady = false
            _currentOwner = null
            Log.d(TAG, "Released ownership ($ownerId) — codec retained for session")
        }
    }

    fun isOwner(ownerId: String): Boolean = _currentOwner == ownerId

    /**
     * Switch the shared player to an immersive item, reusing Media3's prepared
     * source when this item was the one permitted successor.
     */
    fun setImmersiveMediaItem(eventId: String, video: VideoRenderModel) {
        val exoPlayer = player
        val mediaItem = immersivePreloadEntries[eventId]?.mediaItem
            ?: buildMediaItem(eventId, video)
        val preloadedSource = _preloadManager?.getMediaSource(mediaItem)
        if (preloadedSource != null) {
            exoPlayer.setMediaSource(preloadedSource)
        } else {
            exoPlayer.setMediaItem(mediaItem)
        }
        exoPlayer.prepare()
    }

    /**
     * Keep only the playing item and one successor registered. The target
     * control returns a preload budget only for that successor, so no second
     * off-screen item can consume network, allocator, or decoder resources.
     */
    fun updateImmersivePreload(
        currentIndex: Int,
        currentEventId: String,
        currentVideo: VideoRenderModel,
        nextIndex: Int?,
        nextEventId: String?,
        nextVideo: VideoRenderModel?,
        enabled: Boolean,
    ) {
        player // Ensure the paired player/preload manager has been built.
        preloadCenterIndex = currentIndex

        val desired = linkedMapOf<String, PreloadEntry>()
        if (enabled) {
            desired[currentEventId] = PreloadEntry(
                mediaItem = buildMediaItem(currentEventId, currentVideo),
                index = currentIndex,
            )
            if (nextIndex != null && nextEventId != null && nextVideo != null) {
                desired[nextEventId] = PreloadEntry(
                    mediaItem = buildMediaItem(nextEventId, nextVideo),
                    index = nextIndex,
                )
            }
        }

        val manager = _preloadManager ?: return
        immersivePreloadEntries.keys
            .filter { it !in desired }
            .forEach { eventId ->
                immersivePreloadEntries.remove(eventId)?.let { manager.remove(it.mediaItem) }
            }
        desired.forEach { (eventId, entry) ->
            val existing = immersivePreloadEntries[eventId]
            if (existing != entry) {
                existing?.let { manager.remove(it.mediaItem) }
                manager.add(entry.mediaItem, entry.index)
                immersivePreloadEntries[eventId] = entry
            }
        }
        manager.setCurrentPlayingIndex(currentIndex)
        manager.invalidate()
    }

    fun clearImmersivePreloads() {
        preloadCenterIndex = NO_PRELOAD_INDEX
        immersivePreloadEntries.clear()
        _preloadManager?.reset()
    }

    /**
     * Lifecycle-driven codec teardown: stop playback and clear media items,
     * freeing the hardware codec. Keeps the ExoPlayer instance for fast
     * re-use on return to foreground.
     */
    fun releaseForLifecycle(reason: String) {
        _player?.stop()
        _player?.clearMediaItems()
        clearImmersivePreloads()
        _currentOwner = null
        Log.d(TAG, "Codec released — $reason")
    }

    /**
     * Full teardown: stop + clear media items + release the ExoPlayer instance.
     * Called on logout and severe memory pressure.
     */
    fun release() {
        _player?.stop()
        _player?.clearMediaItems()
        _player?.release()
        _preloadManager?.release()
        _player = null
        _preloadManager = null
        _currentOwner = null
        preloadCenterIndex = NO_PRELOAD_INDEX
        immersivePreloadEntries.clear()
    }

    private fun buildMediaItem(eventId: String, video: VideoRenderModel): MediaItem =
        MediaItem.Builder()
            .setMediaId(eventId)
            .setUri(video.videoUrl)
            .apply { video.mimeType?.takeIf { it.isNotBlank() }?.let(::setMimeType) }
            .build()

    private data class PreloadEntry(
        val mediaItem: MediaItem,
        val index: Int,
    )
}
