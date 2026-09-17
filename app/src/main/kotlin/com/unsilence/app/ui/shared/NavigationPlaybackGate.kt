package com.unsilence.app.ui.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * NavDisplay caps both transition scenes at STARTED, including predictive previews.
 * Fail closed until attached; also read lifecycle truth at the point of playback,
 * so a queued effect cannot use the previous composition's RESUMED permission.
 */
internal class NavigationPlaybackGate(
    private val lifecycle: Lifecycle,
    private val onPermissionChanged: (Boolean) -> Unit,
) : LifecycleEventObserver {
    private var attached = false
    private var resumed by mutableStateOf(false)

    val canPlay: Boolean
        get() = resumed && attached && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    fun attach() {
        attached = true
        lifecycle.addObserver(this)
        updatePermission()
    }

    fun dispose() {
        attached = false
        lifecycle.removeObserver(this)
        updatePermission()
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        updatePermission()
    }

    private fun updatePermission() {
        val allowed = attached && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (resumed == allowed) return
        resumed = allowed
        onPermissionChanged(allowed)
    }
}
