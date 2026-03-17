package com.unsilence.app.ui.feed

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharedPlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var _player: ExoPlayer? = null
    private var _currentOwner: String? = null

    val player: ExoPlayer
        get() {
            if (_player == null) {
                _player = ExoPlayer.Builder(context).build().apply {
                    volume = 0f
                    repeatMode = ExoPlayer.REPEAT_MODE_ALL
                }
            }
            return _player!!
        }

    fun claim(ownerId: String): ExoPlayer {
        _currentOwner = ownerId
        return player
    }

    fun releaseOwnership(ownerId: String) {
        if (_currentOwner == ownerId) {
            _currentOwner = null
        }
    }

    fun isOwner(ownerId: String): Boolean = _currentOwner == ownerId

    fun release() {
        _player?.stop()
        _player?.clearMediaItems()
        _player?.release()
        _player = null
        _currentOwner = null
    }
}
