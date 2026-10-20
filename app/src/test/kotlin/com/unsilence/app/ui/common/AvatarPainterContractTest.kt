package com.unsilence.app.ui.common

import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.FilterQuality
import coil3.asImage
import coil3.compose.asPainter
import com.google.accompanist.drawablepainter.DrawablePainter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Coil conversion code, with inert Android drawable/context stubs (no decode/render claim). */
class AvatarPainterContractTest {
    @Test fun `Coil animated success conversion produces the painter our gate expects`() {
        val image = AnimatedFixture().asImage()
        assertFalse("Animated results must not share playback ownership through the memory cache", image.shareable)
        val painter = image.asPainter(context(), FilterQuality.Low)
        assertTrue("Review the visibility gate when upgrading Coil's success-painter path",
            painter is DrawablePainter)
        assertTrue((painter as DrawablePainter).drawable is Animatable)
        assertTrue(gateAvatarPainter(painter, AvatarAnimationOwner()) is VisibilityAwareAvatarPainter)
    }

    @Test fun `static drawable success remains the exact same painter`() {
        val painter = StaticFixture().asImage().asPainter(context(), FilterQuality.Low)
        assertSame(painter, gateAvatarPainter(painter, AvatarAnimationOwner()))
    }

    @Suppress("DEPRECATION")
    private fun context() = object : ContextWrapper(null) {
        private val resources = Resources(null, null, null)
        override fun getResources() = resources
    }

    private open class StaticFixture : Drawable() {
        override fun mutate(): Drawable = this
        override fun getIntrinsicWidth() = 24
        override fun getIntrinsicHeight() = 24
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class AnimatedFixture : StaticFixture(), Animatable {
        override fun start() = Unit
        override fun stop() = Unit
        override fun isRunning() = false
    }
}
