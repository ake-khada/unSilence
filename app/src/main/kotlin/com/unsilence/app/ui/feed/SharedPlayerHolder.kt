package com.unsilence.app.ui.feed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SharedPlayerHolder"

/** How long to keep the player+codec alive after the last owner releases. */
private const val RETENTION_TIMEOUT_MS = 15_000L

@Singleton
class SharedPlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var _player: ExoPlayer? = null
    private var _currentOwner: String? = null

    /**
     * True when the player has a loaded media item and an allocated codec,
     * but no active owner. The retention timer is running — if no claim()
     * arrives within [RETENTION_TIMEOUT_MS], full teardown fires.
     */
    private var _retained: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var _retentionRunnable: Runnable? = null

    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            5_000,   // minBufferMs
            30_000,  // maxBufferMs
            500,     // bufferForPlaybackMs — start playing after 500ms (default 2500)
            1_000,   // bufferForPlaybackAfterRebufferMs
        )
        .build()

    val player: ExoPlayer
        get() {
            if (_player == null) {
                _player = ExoPlayer.Builder(context)
                    .setLoadControl(loadControl)
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

    /** True when the player is retained (codec alive, no active owner). */
    val isRetained: Boolean get() = _retained

    fun claim(ownerId: String): ExoPlayer {
        cancelRetentionTimer()
        _currentOwner = ownerId
        _retained = false
        return player
    }

    /**
     * Release ownership without tearing down the player. Sets playWhenReady=false
     * to pause playback, but keeps the codec and media item alive. Starts the
     * retention timer — if no claim() arrives within [RETENTION_TIMEOUT_MS],
     * full teardown fires.
     */
    fun releaseOwnership(ownerId: String) {
        if (_currentOwner == ownerId) {
            _player?.playWhenReady = false
            _currentOwner = null
            _retained = true
            startRetentionTimer()
            Log.d(TAG, "Released ownership ($ownerId) — retained, timer started")
        }
    }

    fun isOwner(ownerId: String): Boolean = _currentOwner == ownerId

    /**
     * Full teardown: stop + clear media items + release the ExoPlayer instance.
     * Called on logout and when the retention timer expires.
     */
    fun release() {
        cancelRetentionTimer()
        _player?.stop()
        _player?.clearMediaItems()
        _player?.release()
        _player = null
        _currentOwner = null
        _retained = false
    }

    /**
     * Evict the retained media item without releasing the ExoPlayer instance.
     * Called when the retention timer expires — frees the codec but keeps the
     * player ready for the next video.
     */
    private fun evictRetained() {
        _player?.stop()
        _player?.clearMediaItems()
        _retained = false
        Log.d(TAG, "Retention timeout — evicted codec and media item")
    }

    private fun startRetentionTimer() {
        cancelRetentionTimer()
        val runnable = Runnable {
            if (_retained && _currentOwner == null) {
                evictRetained()
            }
        }
        _retentionRunnable = runnable
        mainHandler.postDelayed(runnable, RETENTION_TIMEOUT_MS)
    }

    private fun cancelRetentionTimer() {
        _retentionRunnable?.let { mainHandler.removeCallbacks(it) }
        _retentionRunnable = null
    }
}
