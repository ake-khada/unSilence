package com.unsilence.app.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.ui.common.AvatarLazyColumn
import com.unsilence.app.ui.navigation.AppDestination
import com.unsilence.app.ui.navigation.AppNavDisplay
import com.unsilence.app.ui.navigation.AppNavigator
import com.unsilence.app.ui.navigation.rememberAppNavigator
import com.unsilence.app.ui.shared.replyListItems
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production focus state/index calculation and AvatarLazyColumn, with offline rows.
 * Mirrors ThreadScreen's separate loading/content lists; does not instantiate its ViewModels
 * or EventCards. Observe drawn layouts, not just composition or the eventual idle position.
 */
@RunWith(AndroidJUnit4::class)
class ThreadFocusPositionTest {
    @get:Rule val compose = createComposeRule()
    private val root = FeedRow(
        "root", "author", 1, "Root", 1L, "[]", "", null, null,
        false, null, 0L, null, null, null, null, 0, 0, 0, 0,
    )
    private fun reply(index: Int) = DepthRow(
        root.copy(id = "reply-$index", content = "Reply $index", rootId = root.id, replyToId = root.id), 1,
    )
    private fun ready(count: Int = 30, focus: Int = 15) = ThreadUiState(
        focusedNote = root, replies = (0 until count).map(::reply),
        loading = false, focusedReplyId = "reply-$focus",
    )
    private val projection = mutableStateOf(ThreadUiState())
    private lateinit var list: LazyListState
    private data class Drawn(val first: Int, val offset: Int, val fullyVisibleKeys: List<Any>)
    private val draws = mutableListOf<Drawn>()

    @Composable private fun Content() {
        val state = projection.value
        val items = remember(state.replies) { replyListItems(state.replies, emptySet()) }
        val position = rememberThreadListState("entry", state, items)
        SideEffect { list = position }
        if (state.loading) {
            // As in ThreadScreen, the skeleton never measures the content list state.
            LazyColumn(Modifier.height(240.dp)) { item { Text("Loading", Modifier.height(64.dp)) } }
        } else {
            AvatarLazyColumn(
                state = position,
                modifier = Modifier.height(240.dp).fillMaxWidth().testTag("thread-list").drawWithContent {
                    drawContent()
                    val layout = position.layoutInfo
                    draws += Drawn(position.firstVisibleItemIndex, position.firstVisibleItemScrollOffset,
                        layout.visibleItemsInfo.filter {
                            it.offset >= layout.viewportStartOffset &&
                                it.offset + it.size <= layout.viewportEndOffset
                        }.map { it.key })
                },
            ) {
                state.focusedNote?.let { item(key = it.id) { Text(it.content, Modifier.height(64.dp)) } }
                if (state.replies.isNotEmpty()) {
                    item { Text("Replies", Modifier.height(32.dp)) }
                    items(items, key = { it.key }) { item ->
                        Text(item.key, Modifier.height(64.dp))
                    }
                }
            }
        }
    }

    private fun frames(count: Int = 5) {
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun start(state: ThreadUiState) {
        projection.value = state
        compose.mainClock.autoAdvance = false
        compose.setContent { UnsilenceTheme { Content() } }
        frames()
    }

    private fun assertFirstFocus(expectedIndex: Int, focus: String) = compose.runOnIdle {
        assertTrue("Must observe an actual content draw", draws.isNotEmpty())
        assertEquals("First content draw must already use the focus position", expectedIndex, draws.first().first)
        assertTrue("Focused reply must be fully visible on the first draw", focus in draws.first().fullyVisibleKeys)
    }

    @Test fun readyDeepReplyIsPositionedOnItsFirstContentDraw() {
        start(ready())
        assertFirstFocus(17, "reply-15")
    }

    @Test fun loadingToReadyNeverDrawsTheRootBeforeTheFocus() {
        start(ThreadUiState())
        compose.runOnUiThread { projection.value = ready() }
        frames()
        assertFirstFocus(17, "reply-15")
    }

    @Test fun shortThreadUsesItsExistingBottomClampOnTheFirstDraw() {
        start(ready(count = 7, focus = 5))
        compose.runOnIdle {
            assertTrue(draws.isNotEmpty())
            val first = draws.first()
            assertTrue("Focus fits in the clamped viewport", "reply-5" in first.fullyVisibleKeys)
            assertTrue("Short tail clamps before the requested index", first.first < 7)
            assertEquals("No second position after the first draw", first.first, list.firstVisibleItemIndex)
            assertEquals(first.offset, list.firstVisibleItemScrollOffset)
        }
    }

    @Test fun collapsedClustersCountAsRenderedItemsWhenLocatingFocus() {
        start(ready().let { state -> state.copy(replies = state.replies.mapIndexed { i, row ->
            if (i in 0..4) row.copy(spamClusterId = "cluster") else row
        }) })
        // Five replies become one cluster: 2 leading items + reply15's projected index11.
        assertFirstFocus(13, "reply-15")
    }

    @Test fun absentFocusDoesNotBlockContentAndLateFocusPositionsOnlyOnce() {
        start(ready(count = 5, focus = 15))
        compose.runOnIdle {
            assertTrue(draws.isNotEmpty())
            assertEquals(0, list.firstVisibleItemIndex)
        }
        compose.runOnUiThread { draws.clear(); projection.value = ready() }
        frames()
        assertFirstFocus(17, "reply-15")
        compose.onNodeWithTag("thread-list").performScrollToIndex(6)
        compose.runOnUiThread { projection.value = ready(count = 35) }
        frames()
        compose.runOnIdle { assertEquals(6, list.firstVisibleItemIndex) }
    }

    @Test fun hiddenFocusDoesNotWaitOrRevealItsCluster() {
        start(ready().let { state -> state.copy(replies = state.replies.map {
            if (it.row.id == "reply-15") it.copy(spamClusterId = "hidden") else it
        }) })
        compose.runOnIdle {
            assertTrue(draws.isNotEmpty())
            assertEquals(0, list.firstVisibleItemIndex)
        }
    }

    @Test fun ordinaryRootKeepsItsInitialTopPosition() {
        start(ready().copy(focusedReplyId = null))
        compose.runOnIdle {
            assertTrue(draws.isNotEmpty())
            assertEquals(0, draws.first().first)
            assertTrue(root.id in draws.first().fullyVisibleKeys)
        }
    }

    @Test fun firstRepliesDoNotMoveTheOpenRoot() {
        start(ready(count = 0).copy(focusedReplyId = null))
        compose.runOnUiThread { draws.clear(); projection.value = ready().copy(focusedReplyId = null) }
        frames()
        compose.runOnIdle {
            assertTrue(draws.isNotEmpty())
            draws.forEach {
                assertEquals(0, it.first)
                assertEquals(0, it.offset)
                assertEquals(root.id, it.fullyVisibleKeys.first())
            }
        }
    }

    @Test fun earlierRepliesKeepTheReadingKeyAndOffsetOnEveryDraw() {
        start(ready().copy(focusedReplyId = null))
        compose.onNodeWithTag("thread-list").performScrollToIndex(12)
        val before = compose.runOnIdle {
            list.layoutInfo.visibleItemsInfo.first().let { it.key to it.offset }
        }
        compose.runOnUiThread {
            draws.clear()
            projection.value = projection.value.copy(
                replies = (-3 until 0).map(::reply) + projection.value.replies,
            )
        }
        frames()
        compose.runOnIdle {
            assertTrue(draws.isNotEmpty())
            draws.forEach {
                assertEquals(before.first, it.fullyVisibleKeys.first())
                assertEquals(-before.second, it.offset)
            }
        }
    }

    @Test fun arrivingFocusDoesNotTakeOverAfterTheReaderScrolls() {
        start(ready(count = 14, focus = 20))
        compose.onNodeWithTag("thread-list").performTouchInput { swipeUp(durationMillis = 300) }
        compose.mainClock.advanceTimeBy(2_000)
        frames()
        val before = compose.runOnIdle {
            assertTrue("Fixture must move the reader away from the top", list.firstVisibleItemIndex > 0)
            list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        }
        compose.runOnUiThread { draws.clear(); projection.value = ready(count = 35, focus = 20) }
        frames()
        compose.runOnIdle {
            assertTrue(draws.isNotEmpty())
            draws.forEach {
                assertEquals("Late focus must not override the reader's position", before.first, it.first)
                assertEquals(before.second, it.offset)
            }
        }
    }

    @Test fun savedThreadPositionDoesNotReapplyFocus() {
        projection.value = ready()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { UnsilenceTheme { Content() } }
        compose.onNodeWithTag("thread-list").performScrollToIndex(6)
        val anchor = compose.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(anchor, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        }
    }

    @Test fun cancelledPendingFocusStaysCancelledAfterStateRestoration() {
        projection.value = ready(count = 14, focus = 20)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { UnsilenceTheme { Content() } }
        compose.onNodeWithTag("thread-list").performTouchInput { swipeUp(durationMillis = 300) }
        compose.waitForIdle()
        val anchor = compose.runOnIdle {
            assertTrue(list.firstVisibleItemIndex > 0)
            list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { draws.clear(); projection.value = ready(count = 35, focus = 20) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(draws.isNotEmpty())
            draws.forEach {
                assertEquals(anchor.first, it.first)
                assertEquals(anchor.second, it.offset)
            }
        }
    }

    @Test fun coveringAndReturningToThreadKeepsWhereTheReaderScrolled() {
        projection.value = ready()
        lateinit var navigator: AppNavigator
        compose.setContent {
            UnsilenceTheme {
                val nav = rememberAppNavigator("offline-focus")
                SideEffect { navigator = nav }
                AppNavDisplay(nav) { entry ->
                    if (entry.destination is AppDestination.Profile) Box { Text("Profile") }
                    else Content()
                }
            }
        }
        compose.onNodeWithTag("thread-list").performScrollToIndex(6)
        val anchor = compose.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        compose.runOnIdle { navigator.push(AppDestination.Profile("author")) }
        compose.waitForIdle()
        compose.runOnIdle { navigator.pop() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(anchor, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        }
    }
}
