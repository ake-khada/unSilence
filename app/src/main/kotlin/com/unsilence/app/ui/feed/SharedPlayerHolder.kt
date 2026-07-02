package com.unsilence.app.ui.feed

import android.content.Context
import android.util.Log
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.unsilence.app.di.MediaClient
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SharedPlayerHolder"

@Singleton
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SharedPlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    @MediaClient private val mediaClient: OkHttpClient,
) {
    private var _player: ExoPlayer? = null
    private var _currentOwner: String? = null

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
                _player = ExoPlayer.Builder(context)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build().apply {
                        volume = 0f
                        repeatMode = ExoPlayer.REPEAT_MODE_ALL
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
     * Lifecycle-driven codec teardown: stop playback and clear media items,
     * freeing the hardware codec. Keeps the ExoPlayer instance for fast
     * re-use on return to foreground.
     */
    fun releaseForLifecycle(reason: String) {
        _player?.stop()
        _player?.clearMediaItems()
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
        _player = null
        _currentOwner = null
    }
}
