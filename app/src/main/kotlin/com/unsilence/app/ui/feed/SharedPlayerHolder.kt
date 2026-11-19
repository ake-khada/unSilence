package com.unsilence.app.ui.feed

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.data.relay.NetworkMonitor
import com.unsilence.app.data.relay.RelayPreferencesStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

private const val TAG = "SharedPlayerHolder"
private const val IMMERSIVE_PRELOAD_DURATION_MS = 3_000L
private const val NO_PRELOAD_INDEX = -1

@Singleton
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SharedPlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDataSourceFactory: CacheDataSource.Factory,
    private val networkMonitor: NetworkMonitor,
    relayPreferencesStore: RelayPreferencesStore,
) {
    private var _player: ExoPlayer? = null
    private var _preloadManager: DefaultPreloadManager? = null
    // Written by UI ownership transitions and read by Media3 on its playback thread.
    @Volatile private var _currentOwner: String? = null
    private var policyStoppedUrl: String? = null
    private var preloadCenterIndex = NO_PRELOAD_INDEX
    private val immersivePreloadEntries = linkedMapOf<String, PreloadEntry>()
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val autoplayBlocked = combine(relayPreferencesStore.automaticMediaPreferences, networkMonitor.conditions) { preferences, conditions ->
        preferences == null || videoAutoplayBlocked(preferences.video, conditions)
    }.stateIn(
        networkScope,
        SharingStarted.Eagerly,
        relayPreferencesStore.automaticMediaPreferences.value?.let {
            videoAutoplayBlocked(it.video, networkMonitor.currentConditions)
        } ?: true,
    )
    val canAutoplay get() = !autoplayBlocked.value
    private var networkJob: Job? = null
    private val networkPolicy = VideoNetworkPolicy(
        stopUnrequested = ::stopForPolicy,
        clearPreloads = ::clearImmersivePreloads,
    )

    private fun stopForPolicy() {
        val player = _player ?: return
        if (player.playbackState != Player.STATE_IDLE) policyStoppedUrl = currentUrl
        player.playWhenReady = false
        player.stop() // Keeps the item and position for the next permitted claim.
    }

    /** Do not turn unrelated player errors into automatic retries in Always mode. */
    fun recoverPolicyStop(): Boolean {
        val player = _player ?: return false
        val recover = shouldPrepareAfterPolicyStop(
            player.playbackState == Player.STATE_IDLE, policyStoppedUrl, currentUrl,
        )
        policyStoppedUrl = null
        if (recover) player.prepare()
        return recover
    }

    fun clearPolicyStop() { policyStoppedUrl = null }

    private val loadControl = object : DefaultLoadControl(
        DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
        /* minBufferMs = */ 5_000,
        /* maxBufferMs = */ 30_000,
        /* bufferForPlaybackMs = */ 500,
        /* bufferForPlaybackAfterRebufferMs = */ 1_000,
        DEFAULT_TARGET_BUFFER_BYTES,
        DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
        DEFAULT_BACK_BUFFER_DURATION_MS,
        DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
    ) {
        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
            _currentOwner != null && super.shouldContinueLoading(parameters)
    }

    private val mediaSourceFactory = DefaultMediaSourceFactory(mediaDataSourceFactory)

    val player: ExoPlayer
        get() {
            if (_player == null) {
                val targetControl = TargetPreloadStatusControl<Int> { index ->
                    if (canAutoplay && index == preloadCenterIndex + 1) {
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
                networkJob = networkScope.launch {
                    autoplayBlocked.collect { networkPolicy.networkChanged(it) }
                }
            }
            return _player!!
        }

    /** The URL currently loaded in the player (null if no media item). */
    val currentUrl: String?
        get() = _player?.currentMediaItem?.localConfiguration?.uri?.toString()

    /** True when the player has a loaded media item but no active owner. */
    val isRetained: Boolean
        get() = _currentOwner == null && _player?.currentMediaItem != null

    fun claim(ownerId: String, explicitlyRequested: Boolean = false): ExoPlayer {
        _currentOwner = ownerId
        networkPolicy.claimed(explicitlyRequested)
        return player
    }

    /** Retain playable media at its position and suspend further loading until another claim. */
    fun releaseOwnership(ownerId: String) {
        if (_currentOwner != ownerId) return
        val player = _player
        player?.playWhenReady = false
        _currentOwner = null
        networkPolicy.released()
        // Media3 can report a stuck player if left BUFFERING below 500 ms without loading.
        if (player?.playbackState == Player.STATE_BUFFERING && player.totalBufferedDuration < 500L) {
            stopForPolicy()
        }
        Log.d(TAG, "Released ownership ($ownerId)")
    }

    fun isOwner(ownerId: String): Boolean = _currentOwner == ownerId

    /**
     * Switch the shared player to an immersive item, reusing Media3's prepared
     * source when this item was the one permitted successor.
     */
    fun setImmersiveMediaItem(eventId: String, video: VideoRenderModel) {
        val exoPlayer = player
        clearPolicyStop()
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
        if (enabled && canAutoplay) {
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
        policyStoppedUrl = null
        _player?.clearMediaItems()
        clearImmersivePreloads()
        _currentOwner = null
        networkPolicy.released()
        Log.d(TAG, "Codec released — $reason")
    }

    /**
     * Full teardown: stop + clear media items + release the ExoPlayer instance.
     * Called on logout and severe memory pressure.
     */
    fun release() {
        networkJob?.cancel()
        networkJob = null
        _player?.stop()
        policyStoppedUrl = null
        _player?.clearMediaItems()
        _player?.release()
        _preloadManager?.release()
        _player = null
        _preloadManager = null
        _currentOwner = null
        preloadCenterIndex = NO_PRELOAD_INDEX
        immersivePreloadEntries.clear()
        networkPolicy.released()
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
