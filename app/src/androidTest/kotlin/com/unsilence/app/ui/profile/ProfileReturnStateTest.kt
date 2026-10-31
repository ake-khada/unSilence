package com.unsilence.app.ui.profile

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.ui.navigation.AppDestination
import com.unsilence.app.ui.navigation.AppNavDisplay
import com.unsilence.app.ui.navigation.AppNavigator
import com.unsilence.app.ui.navigation.rememberAppNavigator
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the production collection boundary with the two real save-state owners.
 * Account-free rows replace profile content; this is not a phone gesture/performance test.
 */
@RunWith(AndroidJUnit4::class)
class ProfileReturnStateTest {
    @get:Rule val compose = createComposeRule()
    private val posts = MutableStateFlow((0 until 60).map { row("note-$it") })
    private val ownProfileVisible = mutableStateOf(true)
    private val screen = ScreenLifecycle()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private lateinit var navigator: AppNavigator

    @Test fun retainedRowsArePresentBeforeTheLifecycleCollectorStarts() {
        screen.registry.currentState = Lifecycle.State.CREATED
        compose.setContent { ProfileList() }
        compose.runOnIdle { assertEquals(63, list.layoutInfo.totalItemsCount) }
    }

    @Test fun ownProfileReturnsFromAnotherBottomTabAtTheSameRowAndOffset() {
        startOwnProfile()
        scrollDown()
        compose.runOnIdle { ownProfileVisible.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            screen.registry.currentState = Lifecycle.State.CREATED
            ownProfileVisible.value = true
        }
        assertAnchor()
        compose.runOnIdle { screen.registry.currentState = Lifecycle.State.RESUMED }
        assertAnchor()
    }

    @Test fun ownProfileReturnsFromAThreadAtTheSameRowAndOffset() {
        startOwnProfile()
        scrollDown()
        visitThreadAndReturn()
        assertAnchor()
    }

    @Test fun otherProfileReturnsFromAThreadAtTheSameRowAndOffset() {
        startOwnProfile()
        compose.runOnIdle { navigator.push(AppDestination.Profile("fixture-author")) }
        compose.waitForIdle()
        scrollDown()
        visitThreadAndReturn()
        assertAnchor()
    }

    @Test fun recreationRestoresPositionBeforeCollectionResumes() {
        val restoration = startOwnProfile()
        scrollDown()
        compose.runOnIdle { screen.registry.currentState = Lifecycle.State.CREATED }
        restoration.emulateSavedInstanceStateRestore()
        assertAnchor()
        compose.runOnIdle { screen.registry.currentState = Lifecycle.State.RESUMED }
        assertAnchor()
    }

    @Test fun newProfileVisitStartsAtTopInsteadOfInheritingThePreviousVisit() {
        startOwnProfile()
        scrollDown()
        compose.runOnIdle { navigator.push(AppDestination.Profile("another-author")) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
        compose.runOnIdle { navigator.pop() }
        assertAnchor()
    }

    private fun startOwnProfile(): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            UnsilenceTheme {
                val nav = rememberAppNavigator("fixture-session")
                SideEffect { navigator = nav }
                AppNavDisplay(nav) { entry ->
                    when (entry.destination) {
                        AppDestination.Tabs -> {
                            val tabs = rememberSaveableStateHolder()
                            if (ownProfileVisible.value) tabs.SaveableStateProvider("profile") {
                                ProfileList()
                            }
                        }
                        is AppDestination.Profile -> ProfileList()
                        else -> Spacer(Modifier.fillMaxSize())
                    }
                }
            }
        }
        return restoration
    }

    @Composable private fun ProfileList() {
        CompositionLocalProvider(LocalLifecycleOwner provides screen) {
            val rows by posts.collectProfilePostsAsState()
            val state = rememberLazyListState()
            val compositionScope = rememberCoroutineScope()
            SideEffect { list = state; scope = compositionScope }
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                // The real profiles measure three headers even when their post list is empty.
                items(3) { Spacer(Modifier.height(120.dp)) }
                items(rows, key = { it.id }) { Spacer(Modifier.height(180.dp)) }
            }
        }
    }

    private fun scrollDown() {
        compose.runOnIdle { scope.launch { list.scrollToItem(23, 29) } }
        assertAnchor()
    }

    private fun visitThreadAndReturn() {
        compose.runOnIdle { navigator.push(AppDestination.Thread("fixture-note")) }
        compose.waitForIdle()
        compose.runOnIdle {
            // Make the first returning layout deterministic: collection starts only after
            // that layout. Retained StateFlow rows must seed it synchronously.
            screen.registry.currentState = Lifecycle.State.CREATED
            navigator.pop()
        }
        assertAnchor()
        compose.runOnIdle { screen.registry.currentState = Lifecycle.State.RESUMED }
    }

    private fun assertAnchor() {
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("note-20", list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(29, list.firstVisibleItemScrollOffset)
        }
    }

    private class ScreenLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun row(id: String) = FeedRow(
        id, "author", 1, "", 0L, "[]", "", null, null, false, null,
        0L, null, null, null, null, 0, 0, 0, 0,
    )
}
