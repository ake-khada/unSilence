package com.unsilence.app.ui.thread

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.ui.navigation.AppDestination
import com.unsilence.app.ui.navigation.AppNavDisplay
import com.unsilence.app.ui.navigation.AppNavigator
import com.unsilence.app.ui.navigation.rememberAppNavigator
import com.unsilence.app.ui.shared.collectCardDataAsState
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real presentation/collection and Navigation3 boundaries with offline rows.
 * This measures first-frame correctness, not network latency or gesture performance.
 */
@RunWith(AndroidJUnit4::class)
class ThreadFirstPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val root = FeedRow(
        "root", "author", 1, "Cached quoted post", 1L, "[]", "", null, null,
        false, null, 0L, null, null, null, null, 0, 0, 0, 0,
    )
    private val states = MutableStateFlow(ThreadUiState())
    private val screen = ScreenLifecycle()
    private var resumed = 0
    private val frames = mutableListOf<ThreadUiState>()

    @Composable private fun Content(row: FeedRow? = root) {
        val flow = remember(row) { threadPresentation("root", states, { row }) }
        val state by flow.collectCardDataAsState()
        LifecycleResumeEffect(Unit) {
            resumed++
            // Mirror loadThread publishing a provisional loading state at resume.
            if (states.value.loading) states.value = ThreadUiState(loading = true, focusedNote = row)
            onPauseOrDispose { }
        }
        SideEffect { frames += state }
        Text(if (state.loading) "Loading thread" else state.focusedNote?.content ?: "No root")
    }

    @Test fun cachedCardIsPresentBeforeTheResumeEffectAndFirstCollector() {
        screen.registry.currentState = Lifecycle.State.CREATED
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides screen) { Content() } }
        compose.onNodeWithText(root.content).assertIsDisplayed()
        compose.onNodeWithText("Loading thread").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, resumed)
            assertEquals(0, states.subscriptionCount.value)
            screen.registry.currentState = Lifecycle.State.STARTED
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, resumed)
            assertTrue(frames.none { it.loading })
            screen.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle { assertEquals(1, resumed) }
    }

    @Test fun navigationEntranceNeverPaintsTheLoadingStateForTheCachedRoot() {
        lateinit var navigator: AppNavigator
        compose.setContent {
            UnsilenceTheme {
                val nav = rememberAppNavigator("offline-fixture")
                SideEffect { navigator = nav }
                AppNavDisplay(nav) { entry ->
                    if (entry.destination is AppDestination.Thread) Content() else Text("Profile")
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { navigator.push(AppDestination.Thread(root.id)) }
        repeat(22) { compose.mainClock.advanceTimeByFrame() }
        compose.runOnIdle {
            assertTrue(frames.isNotEmpty())
            assertTrue("Even entrance frames must have the cached card", frames.none { it.loading })
            assertEquals(1, resumed)
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(root.content).assertIsDisplayed()
        compose.runOnIdle { navigator.pop() }
        compose.onNodeWithText("Profile").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, states.subscriptionCount.value) }
    }

    @Test fun uncachedAndReplyDestinationsKeepTheirLoadingPresentation() {
        val cached = mutableStateOf<FeedRow?>(null)
        screen.registry.currentState = Lifecycle.State.CREATED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screen) { Content(cached.value) }
        }
        compose.onNodeWithText("Loading thread").assertIsDisplayed()
        compose.runOnIdle { cached.value = root.copy(rootId = "actual-root", replyToId = "parent") }
        compose.onNodeWithText("Loading thread").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, resumed) }
    }

    @Test fun settledStateOverridesSeedAndIsRetainedWhileCovered() {
        screen.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides screen) { Content() } }
        compose.onNodeWithText(root.content).assertIsDisplayed()
        val projected = root.copy(content = "Resolved thread")
        compose.runOnIdle { states.value = ThreadUiState(focusedNote = projected, loading = false) }
        compose.onNodeWithText(projected.content).assertIsDisplayed()
        compose.runOnIdle { screen.registry.currentState = Lifecycle.State.CREATED }
        compose.onNodeWithText(projected.content).assertIsDisplayed()
        compose.runOnIdle { screen.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText(projected.content).assertIsDisplayed()
        compose.runOnIdle { states.value = ThreadUiState(loading = false) }
        compose.onNodeWithText("No root").assertIsDisplayed()
        compose.onNodeWithText(root.content).assertDoesNotExist()
    }

    @Test fun quoteOnlyLocalRootIsPreparedWhileStartedWithoutResumingRequests() {
        val prepared = java.util.concurrent.atomic.AtomicReference<FeedRow?>(null)
        val flow = threadPresentation("root", states, { prepared.get() }) {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            prepared.set(root)
        }
        screen.registry.currentState = Lifecycle.State.STARTED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screen) {
                val state by flow.collectCardDataAsState()
                LifecycleResumeEffect(Unit) {
                    resumed++
                    onPauseOrDispose { }
                }
                Text(if (state.loading) "Loading thread" else state.focusedNote!!.content)
            }
        }
        compose.waitUntil { prepared.get() != null }
        compose.onNodeWithText(root.content).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, resumed) }
    }

    private class ScreenLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
