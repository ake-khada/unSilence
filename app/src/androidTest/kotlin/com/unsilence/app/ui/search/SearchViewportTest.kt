package com.unsilence.app.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.ui.navigation.AppDestination
import com.unsilence.app.ui.navigation.AppEntry
import com.unsilence.app.ui.navigation.AppNavDisplay
import com.unsilence.app.ui.navigation.AppNavigator
import com.unsilence.app.ui.navigation.rememberAppNavigator
import com.unsilence.app.ui.theme.UnsilenceTheme
import com.unsilence.app.ui.common.scrollAwayHeader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline rendered production-state tests; phone gesture feel still needs owner validation. */
@RunWith(AndroidJUnit4::class)
class SearchViewportTest {
    @get:Rule val compose = createComposeRule()
    private val query = mutableStateOf("first")
    private val rows = mutableStateOf((50..149).map(Int::toString))
    private val tab = mutableIntStateOf(0)
    private lateinit var active: SearchResultViewport

    @Composable private fun Results() {
        val notes = rememberSearchResultViewport(query.value, rows.value.firstOrNull())
        val people = rememberSearchResultViewport(query.value, rows.value.firstOrNull())
        val viewport = if (tab.intValue == 0) notes else people
        SideEffect { active = viewport }
        LazyColumn(Modifier.fillMaxSize().testTag("results").nestedScroll(viewport.scrollConnection),
            state = viewport.listState) {
            items(rows.value, key = { it }) { id -> Text(id, Modifier.fillMaxWidth().height(50.dp)) }
        }
    }

    @Test fun progressivePrependsKeepTheInitialNewestResultVisible() {
        compose.setContent { Results() }
        compose.runOnIdle { rows.value = (0..149).map(Int::toString) }
        compose.runOnIdle {
            assertEquals(0, active.listState.firstVisibleItemIndex)
            assertEquals("0", active.listState.layoutInfo.visibleItemsInfo.first().key)
        }
    }

    @Test fun newQueryStartsAtTopInsteadOfReusingTheOldCursor() {
        compose.setContent { Results() }
        compose.onNodeWithTag("results").performScrollToIndex(30)
        compose.runOnIdle { query.value = "second" }
        compose.runOnIdle { assertEquals(0, active.listState.firstVisibleItemIndex) }
    }

    @Test fun userScrollKeepsItsKeyAndOffsetWhenNewerResultsArrive() {
        compose.setContent { Results() }
        compose.onNodeWithTag("results").performTouchInput { swipeUp(durationMillis = 500) }
        val anchor = compose.runOnIdle {
            assertTrue(active.listState.firstVisibleItemIndex > 0)
            active.listState.layoutInfo.visibleItemsInfo.first().key to active.listState.firstVisibleItemScrollOffset
        }
        compose.runOnIdle { rows.value = (0..149).map(Int::toString) }
        compose.runOnIdle {
            assertEquals(anchor.first, active.listState.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(anchor.second, active.listState.firstVisibleItemScrollOffset)
        }
    }

    @Test fun resultTabsKeepIndependentPositionsAndResetTogetherForANewQuery() {
        compose.setContent { Results() }
        compose.onNodeWithTag("results").performScrollToIndex(30)
        compose.runOnIdle { tab.intValue = 1 }
        compose.runOnIdle { assertEquals(0, active.listState.firstVisibleItemIndex) }
        compose.onNodeWithTag("results").performScrollToIndex(12)
        compose.runOnIdle { tab.intValue = 0 }
        compose.runOnIdle { assertEquals(30, active.listState.firstVisibleItemIndex) }
        compose.runOnIdle { query.value = "new" }
        compose.runOnIdle { assertEquals(0, active.listState.firstVisibleItemIndex) }
        compose.runOnIdle { tab.intValue = 1 }
        compose.runOnIdle { assertEquals(0, active.listState.firstVisibleItemIndex) }
    }

    @Test fun savedViewportRestoresWithoutSavingRowsOrFollowingNewPrepends() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Results() }
        compose.onNodeWithTag("results").performTouchInput { swipeUp(durationMillis = 500) }
        val anchor = compose.runOnIdle {
            active.listState.firstVisibleItemIndex to active.listState.firstVisibleItemScrollOffset
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(anchor.first, active.listState.firstVisibleItemIndex)
            assertEquals(anchor.second, active.listState.firstVisibleItemScrollOffset)
        }
        val key = compose.runOnIdle { active.listState.layoutInfo.visibleItemsInfo.first().key }
        compose.runOnIdle { rows.value = (0..149).map(Int::toString) }
        compose.runOnIdle { assertEquals(key, active.listState.layoutInfo.visibleItemsInfo.first().key) }
    }

    @Test fun topicAndThreadEntriesReturnToThePriorSearchTabAndViewport() {
        lateinit var navigator: AppNavigator
        var displayedTab = -1
        var selectedTabSetter: ((Int) -> Unit)? = null
        compose.setContent {
            UnsilenceTheme {
                val nav = rememberAppNavigator("test")
                SideEffect { navigator = nav }
                AppNavDisplay(nav) { entry ->
                    if (entry.destination == AppDestination.Tabs) {
                        var selected by rememberSaveable { mutableIntStateOf(0) }
                        SideEffect { displayedTab = selected; selectedTabSetter = { selected = it } }
                        Results()
                    } else Text("child", Modifier.testTag("child"))
                }
            }
        }
        compose.onNodeWithTag("results").performScrollToIndex(30)
        compose.runOnIdle {
            selectedTabSetter!!(2)
            navigator.push(AppDestination.Search("#topic"))
        }
        compose.onNodeWithTag("child").assertIsDisplayed()
        compose.runOnIdle { navigator.push(AppDestination.Thread("note")) }
        compose.runOnIdle { navigator.pop() }
        compose.runOnIdle {
            assertTrue((navigator.backStack.last() as AppEntry).destination is AppDestination.Search)
            navigator.pop()
        }
        compose.onNodeWithTag("results").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, displayedTab)
            assertEquals(30, active.listState.firstVisibleItemIndex)
        }
    }

    @Test fun scrollAwayControlsNeverMoveTheResultViewportOrItsReadingPosition() {
        val visible = mutableStateOf(true)
        compose.setContent {
            UnsilenceTheme {
                SearchControlsLayout(visible.value, header = {
                    Box(Modifier.fillMaxWidth().height(80.dp).testTag("controls")) { Text("Search") }
                }) { top ->
                    val viewport = rememberSearchResultViewport("query", rows.value.firstOrNull())
                    SideEffect { active = viewport }
                    LazyColumn(Modifier.fillMaxSize().testTag("results"), state = viewport.listState,
                        contentPadding = PaddingValues(top = top)) {
                        items(rows.value, key = { it }) { Text(it, Modifier.height(50.dp)) }
                    }
                }
            }
        }
        compose.onNodeWithTag("results").performScrollToIndex(20)
        val bounds = compose.onNodeWithTag("results").getUnclippedBoundsInRoot()
        val anchor = compose.runOnIdle { active.listState.firstVisibleItemIndex to active.listState.firstVisibleItemScrollOffset }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { visible.value = false }
        compose.mainClock.advanceTimeBy(128)
        assertEquals(bounds, compose.onNodeWithTag("results").getUnclippedBoundsInRoot())
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("controls").assertIsNotDisplayed()
        compose.runOnIdle {
            assertEquals(anchor.first, active.listState.firstVisibleItemIndex)
            assertEquals(anchor.second, active.listState.firstVisibleItemScrollOffset)
        }
        compose.runOnUiThread { visible.value = true }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("controls").assertIsDisplayed()
        assertEquals(bounds, compose.onNodeWithTag("results").getUnclippedBoundsInRoot())
    }

    @Test fun discoveryLoadingAndRetryStayBelowTheOverlayControls() {
        val discovery = mutableStateOf(SearchDiscoveryState(
            refreshing = true, peopleStatus = DiscoveryStatus.Loading,
            hashtagStatus = DiscoveryStatus.Loading,
        ))
        compose.mainClock.autoAdvance = false // Existing shimmer intentionally loops.
        compose.setContent {
            UnsilenceTheme {
                SearchControlsLayout(true, header = {
                    Box(Modifier.fillMaxWidth().height(80.dp).testTag("controls"))
                }) { top ->
                    SearchDiscoveryContent(discovery.value, staticBottomPadding = 0.dp,
                        staticTopPadding = top, onRetry = {}, onHashtagClick = {}, onUserClick = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(64)
        val bottom = compose.onNodeWithTag("controls").getUnclippedBoundsInRoot().bottom
        assertTrue(compose.onNodeWithContentDescription("Loading suggestions")
            .getUnclippedBoundsInRoot().top >= bottom)
        compose.runOnUiThread { discovery.value = SearchDiscoveryState(
            peopleStatus = DiscoveryStatus.Unavailable, hashtagStatus = DiscoveryStatus.Unavailable,
        ) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("Retry").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Suggestions unavailable").getUnclippedBoundsInRoot().top >= bottom)
    }

    @Test fun reducedMotionMovesControlsWithoutTheSlide() {
        val visible = mutableStateOf(true)
        compose.setContent {
            Box(Modifier.fillMaxSize().clipToBounds()) {
                Box(Modifier.scrollAwayHeader(visible.value, motionEnabled = false)
                    .fillMaxWidth().height(80.dp).testTag("controls"))
            }
        }
        compose.onNodeWithTag("controls").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { visible.value = false }
        compose.mainClock.advanceTimeBy(48) // Commit the state and zero-duration target, not a 250ms slide.
        compose.onNodeWithTag("controls").assertIsNotDisplayed()
        compose.runOnUiThread { visible.value = true }
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("controls").assertIsDisplayed()
    }
}
