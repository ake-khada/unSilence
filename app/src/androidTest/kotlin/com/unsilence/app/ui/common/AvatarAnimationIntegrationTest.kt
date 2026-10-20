package com.unsilence.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.ScaleDrawable
import com.google.accompanist.drawablepainter.DrawablePainter
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Offline Coil/DrawablePainter ownership checks, not a human scrolling/performance substitute. */
@RunWith(AndroidJUnit4::class)
class AvatarAnimationIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private var loader: ImageLoader? = null

    @After fun shutDownLoader() { loader?.shutdown() }

    @Test fun actualCoilSuccessPainterIsGatedBeforeItCanStartWhileHidden() {
        val screen = Screen(visible = false)
        val drawable = RecordingAnimation()
        render(screen) { ScaleDrawable(drawable) }
        compose.waitUntil { screen.successes.get() == 1 }
        compose.runOnIdle {
            val input = requireNotNull(screen.input)
            assertTrue("Coil upgrade changed the painter expected by the avatar transform",
                input.painter is DrawablePainter)
            assertTrue((input.painter as DrawablePainter).drawable is Animatable)
            assertFalse(input.result.image.shareable)
            assertTrue(requireNotNull(screen.output).painter is VisibilityAwareAvatarPainter)
            assertEquals(0, drawable.starts)
            assertFalse(drawable.isRunning)
            assertNull((input.painter as DrawablePainter).drawable.callback)
            screen.visible.value = true
        }
        compose.runOnIdle {
            assertTrue(drawable.isRunning)
            assertEquals(1, drawable.starts)
            assertNotNull((requireNotNull(screen.input).painter as DrawablePainter).drawable.callback)
        }
    }

    @Test fun visibilityAndLifecycleEdgesReuseOneRequestAndStopItsDrawable() {
        val screen = Screen()
        val drawable = RecordingAnimation()
        render(screen) { ScaleDrawable(drawable) }
        compose.waitUntil { screen.successes.get() == 1 }
        compose.runOnIdle { assertTrue(drawable.isRunning); screen.visible.value = false }
        compose.runOnIdle { assertFalse(drawable.isRunning); screen.visible.value = true }
        compose.runOnIdle { assertTrue(drawable.isRunning); screen.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertFalse(drawable.isRunning); screen.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle {
            assertTrue(drawable.isRunning)
            assertEquals(3, drawable.starts)
            assertEquals(2, drawable.stops)
            assertEquals("Visibility must not rebuild/re-execute the request", 1, screen.requests.get())
            screen.composed.value = false
        }
        compose.runOnIdle {
            assertFalse(drawable.isRunning)
            assertEquals(3, drawable.stops)
            assertNull((requireNotNull(screen.input).painter as DrawablePainter).drawable.callback)
        }
    }

    @Test fun lateInactiveResultAndUrlReplacementDoNotLeaveAnOldAnimationRunning() {
        val screen = Screen()
        val first = RecordingAnimation()
        val second = RecordingAnimation()
        val arrival = CompletableDeferred<Unit>()
        render(screen) { key ->
            if (key == "first") {
                arrival.await()
                ScaleDrawable(first)
            } else ScaleDrawable(second)
        }
        compose.waitUntil { screen.requests.get() == 1 }
        compose.runOnIdle {
            screen.registry.currentState = Lifecycle.State.STARTED
            arrival.complete(Unit)
        }
        compose.waitUntil { screen.successes.get() == 1 }
        compose.runOnIdle {
            assertEquals(0, first.starts)
            screen.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle {
            assertTrue(first.isRunning)
            screen.key.value = "second"
        }
        compose.waitUntil { screen.successes.get() == 2 }
        compose.runOnIdle {
            assertFalse(first.isRunning)
            assertEquals(1, first.stops)
            assertTrue(second.isRunning)
            assertEquals(2, screen.requests.get())
        }
    }

    @Test fun disposalStopsAnimationEvenBeforeTheCrossfadeHasFinished() {
        val screen = Screen()
        val drawable = RecordingAnimation()
        compose.mainClock.autoAdvance = false
        render(screen, crossfadeMillis = 60_000) { ScaleDrawable(drawable) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil { screen.successes.get() == 1 }
        compose.runOnIdle {
            assertTrue(drawable.isRunning)
            screen.composed.value = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertFalse(drawable.isRunning)
            assertEquals(1, drawable.stops)
            assertNull((requireNotNull(screen.input).painter as DrawablePainter).drawable.callback)
        }
    }

    private fun render(
        screen: Screen,
        crossfadeMillis: Int = 120,
        result: suspend (String) -> Drawable,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imageLoader = ImageLoader.Builder(context).crossfade(crossfadeMillis).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    screen.requests.incrementAndGet()
                    return SuccessResult(
                        image = result(chain.request.data.toString()).asImage(),
                        request = chain.request,
                        dataSource = DataSource.DISK,
                    )
                }
            })
        }.build()
        loader = imageLoader
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screen) {
                if (screen.composed.value) {
                    val transform = rememberAvatarAnimationTransform(screen.visible.value)
                    AsyncImage(
                        model = rememberAvatarImageRequest(screen.key.value, 24.dp),
                        contentDescription = "Profile",
                        imageLoader = imageLoader,
                        modifier = Modifier.size(24.dp),
                        transform = { state ->
                            if (state is AsyncImagePainter.State.Success) screen.input = state
                            transform(state)
                        },
                        onState = { state ->
                            if (state is AsyncImagePainter.State.Success) {
                                screen.output = state
                                screen.successes.incrementAndGet()
                            }
                        },
                    )
                }
            }
        }
    }

    private class Screen(visible: Boolean = true) : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        val visible = mutableStateOf(visible)
        val composed = mutableStateOf(true)
        val key = mutableStateOf("first")
        val requests = AtomicInteger()
        val successes = AtomicInteger()
        @Volatile var input: AsyncImagePainter.State.Success? = null
        @Volatile var output: AsyncImagePainter.State.Success? = null
    }

    private class RecordingAnimation : Drawable(), Animatable {
        var starts = 0
        var stops = 0
        private var running = false
        override fun start() { starts++; running = true }
        override fun stop() { stops++; running = false }
        override fun isRunning() = running
        override fun getIntrinsicWidth() = 24
        override fun getIntrinsicHeight() = 24
        override fun draw(canvas: Canvas) { canvas.drawColor(Color.RED) }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
