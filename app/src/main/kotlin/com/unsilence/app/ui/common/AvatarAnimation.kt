package com.unsilence.app.ui.common

import android.graphics.drawable.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImagePainter
import com.google.accompanist.drawablepainter.DrawablePainter

/**
 * Keep an avatar's request and painter while hidden, but lend its animated drawable
 * playback ownership only while shown and RESUMED. Re-entry restarts the loop.
 *
 * [visible] must be a discrete eligibility signal, not an animated offset. Nothing
 * here polls geometry, requests frames, or writes Compose state during scrolling.
 * The transform runs before Coil remembers the result, so a late hidden result
 * cannot auto-start. Static images and the existing draw/crossfade path are unchanged.
 */
@Composable
internal fun rememberAvatarAnimationTransform(
    visible: Boolean,
): (AsyncImagePainter.State) -> AsyncImagePainter.State {
    val owner = remember { AvatarAnimationOwner() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(owner, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            owner.setResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        owner.setResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            owner.setResumed(false)
        }
    }
    SideEffect { owner.setVisible(visible) }
    DisposableEffect(owner) {
        // Also covers disposal during Coil's crossfade, whose outer painter is
        // not itself a RememberObserver.
        onDispose { owner.dispose() }
    }
    return remember(owner) {
        { state ->
            if (state is AsyncImagePainter.State.Success) {
                val painter = gateAvatarPainter(state.painter, owner)
                if (painter === state.painter) state else state.copy(painter = painter)
            } else state
        }
    }
}

/** Coil's success-painter type is contract-tested so upgrades cannot silently bypass this. */
internal fun gateAvatarPainter(painter: Painter, owner: AvatarAnimationOwner): Painter =
    if (painter is DrawablePainter && painter.drawable is Animatable) {
        VisibilityAwareAvatarPainter(painter, owner)
    } else painter

/** One image instance, main-thread confined. Repeated eligibility is a no-op. */
internal class AvatarAnimationOwner {
    private var visible = false
    private var resumed = false
    private var disposed = false
    private var delegate: RememberObserver? = null
    private var running = false

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        reconcile()
    }

    fun setResumed(value: Boolean) {
        if (resumed == value) return
        resumed = value
        reconcile()
    }

    fun attach(next: RememberObserver) {
        if (disposed || delegate === next) return
        delegate?.let(::detach)
        delegate = next
        reconcile()
    }

    fun detach(previous: RememberObserver) {
        if (delegate !== previous) return
        if (running) {
            running = false
            previous.onForgotten()
        }
        delegate = null
    }

    fun dispose() {
        disposed = true
        delegate?.let(::detach)
    }

    private fun reconcile() {
        val next = delegate ?: return
        val shouldRun = visible && resumed && !disposed
        if (running == shouldRun) return
        running = shouldRun
        // DrawablePainter owns callback registration, visibility and start/stop.
        if (shouldRun) next.onRemembered() else next.onForgotten()
    }
}

/** Delegate rendering (including Android-version scaling) without auto-starting. */
internal class VisibilityAwareAvatarPainter(
    private val delegate: Painter,
    private val owner: AvatarAnimationOwner,
) : Painter(), RememberObserver {
    private val observer = delegate as RememberObserver
    private var alpha = 1f
    private var colorFilter: ColorFilter? = null

    override val intrinsicSize get() = delegate.intrinsicSize
    override fun DrawScope.onDraw() {
        with(delegate) { draw(size, alpha, colorFilter) }
    }
    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }
    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }
    override fun onRemembered() = owner.attach(observer)
    override fun onForgotten() = owner.detach(observer)
    override fun onAbandoned() = onForgotten()
}
