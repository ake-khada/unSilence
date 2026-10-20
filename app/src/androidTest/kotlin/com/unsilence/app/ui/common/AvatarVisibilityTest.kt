package com.unsilence.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.layoutBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.ScaleDrawable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Offline layout/Coil tests. Programmatic geometry is not a scrolling performance measurement. */
@RunWith(AndroidJUnit4::class)
class AvatarVisibilityTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var loader: ImageLoader
    private val requests = AtomicInteger()
    private val successes = AtomicInteger()
    private val drawable = RecordingAnimation()
    private val screen = Screen()

    @After fun shutDownLoader() { if (::loader.isInitialized) loader.shutdown() }

    @Test fun retainedRowStopsWhenOnlyAvatarLeavesListAndResumesWithoutReload() {
        renderFixture()
        awaitRunning()
        // Still inside the window and the tall row, but above the smaller list viewport.
        moveAvatar(-24)
        compose.waitUntil { drawable.stops == 1 }
        assertFalse(drawable.isRunning)
        moveAvatar(0)
        awaitRunning()
        compose.runOnIdle {
            assertEquals(2, drawable.starts)
            assertEquals(1, requests.get())
            assertEquals(1, successes.get())
        }
    }

    @Test fun onePixelOverlapAnimatesButExactBoundaryDoesNot() {
        renderFixture()
        awaitRunning()
        compose.runOnIdle { screen.avatarY.intValue = -screen.avatarHeight + 1 }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(drawable.isRunning); assertEquals(1, drawable.starts) }
        compose.runOnIdle { screen.avatarY.intValue = -screen.avatarHeight }
        compose.waitUntil { drawable.stops == 1 }
        compose.runOnIdle { assertFalse(drawable.isRunning) }
    }

    @Test fun neverPopulatedHolderFallsBackToWindowInsteadOfFreezing() {
        screen.attachBounds.value = false
        renderFixture(initialAvatarDp = 100)
        awaitRunning() // Outside the 80dp list but inside the window.
        moveAvatar(-2000)
        compose.waitUntil { drawable.stops == 1 }
        moveAvatar(100)
        awaitRunning()
        compose.runOnIdle { assertEquals(1, requests.get()) }
    }

    @Test fun lateBoundsConstrainThenClearedBoundsRestoreWindowFallback() {
        screen.attachBounds.value = false
        renderFixture(initialAvatarDp = 100)
        awaitRunning()
        compose.runOnIdle { screen.attachBounds.value = true }
        compose.waitUntil { drawable.stops == 1 }
        compose.runOnIdle { assertFalse(drawable.isRunning); screen.attachBounds.value = false }
        awaitRunning()
        compose.runOnIdle { assertEquals(2, drawable.starts); assertEquals(1, requests.get()) }
    }

    @Test fun replacementHolderNeverBorrowsTheOldViewport() {
        renderFixture(initialAvatarDp = 100)
        compose.waitUntil { successes.get() == 1 }
        // Before a holder populates, the approved window fallback can briefly start
        // this window-visible image. Assert the settled constraint and balanced
        // starts/stops, not zero starts across that valid transient.
        compose.runOnIdle {
            assertFalse(drawable.isRunning)
            assertEquals(drawable.starts, drawable.stops)
            screen.holder.value = LayoutBoundsHolder()
            // The avatar would overlap the old viewport rectangle at this position,
            // but remains below the replacement viewport's bottom.
            screen.viewportY.intValue = 0
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(drawable.isRunning)
            assertEquals(drawable.starts, drawable.stops)
            assertEquals(1, requests.get())
            screen.attachBounds.value = false
        }
        awaitRunning()
        compose.runOnIdle { assertEquals(1, requests.get()) }
    }

    @Test fun movingStableViewportDoesNotRecomposeConsumersWhileAvailabilityIsUnchanged() {
        renderFixture()
        awaitRunning()
        val before = screen.consumerCompositions.get()
        repeat(12) { index ->
            compose.runOnIdle { screen.viewportY.intValue += index + 1 }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            assertEquals(before, screen.consumerCompositions.get())
            assertEquals(1, drawable.starts)
            assertEquals(0, drawable.stops)
        }
    }

    @Test fun slidingOverlayDoesNotMoveViewportOrToggleAnimation() {
        renderFixture()
        awaitRunning()
        val before = screen.consumerCompositions.get()
        val bounds = requireNotNull(screen.holder.value.bounds)
        repeat(12) { index ->
            compose.runOnIdle { screen.overlayY.intValue = -index * 8 }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            assertEquals(bounds.boundsInRoot, requireNotNull(screen.holder.value.bounds).boundsInRoot)
            assertEquals(before, screen.consumerCompositions.get())
            assertEquals(1, drawable.starts)
            assertEquals(0, drawable.stops)
        }
    }

    @Test fun windowFallbackStillHonorsLifecycleAndDisposal() {
        screen.attachBounds.value = false
        renderFixture()
        awaitRunning()
        compose.runOnIdle { screen.registry.currentState = Lifecycle.State.STARTED }
        compose.waitUntil { drawable.stops == 1 }
        compose.runOnIdle { screen.registry.currentState = Lifecycle.State.RESUMED }
        awaitRunning()
        compose.runOnIdle { screen.composed.value = false }
        compose.waitUntil { drawable.stops == 2 }
        compose.runOnIdle { assertFalse(drawable.isRunning) }
    }

    @Test fun lateResultWhileOutsideListNeverStartsUntilVisible() {
        val arrival = CompletableDeferred<Unit>()
        renderFixture(initialAvatarDp = -24, arrival = arrival)
        compose.waitUntil { requests.get() == 1 }
        compose.runOnIdle { arrival.complete(Unit) }
        compose.waitUntil { successes.get() == 1 }
        compose.runOnIdle { assertEquals(0, drawable.starts) }
        moveAvatar(0)
        awaitRunning()
        compose.runOnIdle { assertEquals(1, requests.get()) }
    }

    @Test fun actualLazyColumnRetainsTallRowButStopsItsAvatarThenReenters() {
        val state = LazyListState()
        render {
            AvatarLazyColumn(state = state, modifier = Modifier.size(200.dp, 80.dp)) {
                item(key = "tall") {
                    Box(Modifier.height(300.dp)) { TestAvatar() }
                }
                item(key = "next") { Box(Modifier.height(300.dp)) }
            }
        }
        awaitRunning()
        compose.runOnIdle { runBlocking { state.scrollToItem(0, screen.avatarHeight + 1) } }
        compose.waitUntil { drawable.stops == 1 }
        compose.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertFalse(drawable.isRunning)
            runBlocking { state.scrollToItem(1) }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(drawable.isRunning); runBlocking { state.scrollToItem(0) } }
        awaitRunning()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun sheetOpenedInsideListItemUsesItsOwnWindowNotTheListViewport() {
        val open = mutableStateOf(false)
        render {
            // Deliberately nowhere near the bottom sheet's avatar in root coordinates.
            AvatarLazyColumn(modifier = Modifier.size(200.dp, 40.dp)) {
                item {
                    Box(Modifier.size(24.dp))
                    if (open.value) {
                        ModalBottomSheet(
                            onDismissRequest = { open.value = false },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        ) {
                            ProvideAvatarViewport(bounds = null) {
                                Box(Modifier.size(200.dp, 160.dp)) { TestAvatar() }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { open.value = true }
        awaitRunning()
        compose.runOnIdle { assertEquals(1, requests.get()); open.value = false }
        compose.waitUntil { drawable.stops >= 1 }
        compose.runOnIdle { assertFalse(drawable.isRunning) }
    }

    @Test fun navEligibilityIsIndependentOfAHiddenListAvatar() {
        val nav = RecordingAnimation()
        val navVisible = mutableStateOf(true)
        render {
            AvatarLazyColumn(modifier = Modifier.size(200.dp, 80.dp)) {
                item { Box(Modifier.height(300.dp)) { TestAvatar(Modifier.offset(y = (-24).dp)) } }
            }
            // Keep the validated nav gate separate from list visibility, even if composed
            // under a viewport in a future navigation refactor.
            val navOwner = remember { AvatarAnimationOwner() }
            val painter = remember { VisibilityAwareAvatarPainter(
                com.google.accompanist.drawablepainter.DrawablePainter(nav), navOwner,
            ) }
            // Observe eligibility in composition, as the production nav transform
            // does. Reading state only in SideEffect never subscribes this fixture.
            val shown = navVisible.value
            SideEffect { navOwner.setResumed(true); navOwner.setVisible(shown) }
            androidx.compose.foundation.Image(painter = painter, contentDescription = null)
        }
        // Separate image loading from painter startup so an emulator timeout
        // identifies which condition failed; neither assertion is optional.
        compose.waitUntil(5_000) { successes.get() == 1 }
        compose.waitUntil(5_000) { nav.isRunning }
        compose.runOnIdle {
            assertFalse(drawable.isRunning)
            navVisible.value = false
        }
        compose.waitUntil(5_000) { !nav.isRunning }
    }

    private fun renderFixture(initialAvatarDp: Int = 0, arrival: CompletableDeferred<Unit>? = null) {
        render(arrival) {
            val density = LocalDensity.current
            remember {
                screen.density = density.density
                screen.avatarY.intValue = with(density) { initialAvatarDp.dp.roundToPx() }
                screen.viewportY.intValue = with(density) { 64.dp.roundToPx() }
                true
            }
            ProvideAvatarViewport(screen.holder.value) {
                ViewportConsumerProbe()
                Box(
                    Modifier.offset { IntOffset(0, screen.viewportY.intValue) }
                        .size(200.dp, 80.dp)
                        .then(if (screen.attachBounds.value) Modifier.layoutBounds(screen.holder.value) else Modifier),
                ) {
                    Box(Modifier.offset { IntOffset(0, screen.avatarY.intValue) }.height(300.dp)) {
                        if (screen.composed.value) TestAvatar()
                    }
                }
            }
            Box(Modifier.offset { IntOffset(0, screen.overlayY.intValue) }.size(200.dp, 40.dp))
        }
    }

    @Composable
    private fun ViewportConsumerProbe() {
        // Subscribe to the same local as the image, without its load/visibility
        // state changes. Count provider-driven composition separately from layout.
        val onChanged = remember { { _: Boolean -> Unit } }
        val visibility = Modifier.avatarVisibility(onChanged)
        SideEffect { screen.consumerCompositions.incrementAndGet() }
        Box(Modifier.size(1.dp).then(visibility))
    }

    @Composable
    private fun TestAvatar(modifier: Modifier = Modifier) {
        val density = LocalDensity.current
        SideEffect {
            screen.avatarHeight = with(density) { 24.dp.roundToPx() }
        }
        AvatarAsyncImage(
            model = rememberAvatarImageRequest("avatar", 24.dp),
            contentDescription = null,
            modifier = modifier.size(24.dp),
            imageLoader = loader,
            onSuccess = { successes.incrementAndGet() },
        )
    }

    private fun render(arrival: CompletableDeferred<Unit>? = null, content: @Composable () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        loader = ImageLoader.Builder(context).crossfade(false).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    requests.incrementAndGet()
                    arrival?.await()
                    return SuccessResult(
                        image = ScaleDrawable(drawable).asImage(),
                        request = chain.request,
                        dataSource = DataSource.DISK,
                    )
                }
            })
        }.build()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screen) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
            }
        }
    }

    private fun awaitRunning() = compose.waitUntil(5_000) { drawable.isRunning }
    private fun moveAvatar(dp: Int) {
        compose.runOnIdle { screen.avatarY.intValue = (dp * screen.density).toInt() }
        compose.waitForIdle()
    }

    private class Screen : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        val holder = mutableStateOf(LayoutBoundsHolder())
        val attachBounds = mutableStateOf(true)
        val composed = mutableStateOf(true)
        val avatarY = mutableIntStateOf(0)
        val viewportY = mutableIntStateOf(0)
        val overlayY = mutableIntStateOf(0)
        val consumerCompositions = AtomicInteger()
        var density = 1f
        var avatarHeight = 0
    }

    private class RecordingAnimation : Drawable(), Animatable {
        @Volatile var starts = 0
        @Volatile var stops = 0
        @Volatile private var running = false
        override fun start() { starts++; running = true }
        override fun stop() { stops++; running = false }
        override fun isRunning() = running
        override fun getIntrinsicWidth() = 24
        override fun getIntrinsicHeight() = 24
        override fun draw(canvas: Canvas) { canvas.drawColor(android.graphics.Color.RED) }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
