package com.unsilence.app.ui.feed

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.ui.shared.rememberFeedScrollState
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Account-free saved-state correctness fixtures, not gesture/performance certification. */
@RunWith(AndroidJUnit4::class)
class FeedReturnAnchorTest {
    @get:Rule val compose = createComposeRule()
    private val original = (0 until 60).map { row("note-$it") }
    private val rows = mutableStateOf(original)
    private val visible = mutableStateOf(true)
    private val ready = mutableStateOf(true)
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope

    @Test fun returnWithPrependsKeepsReadingNoteAndOffset() {
        start()
        coverAndReturn(listOf(row("new-1"), row("new-2")) + original)
        assertAnchor("note-20", 23)
    }

    @Test fun removedAnchorReturnsToNearestSavedSuccessorAfterPrepends() {
        start()
        coverAndReturn(listOf(row("new-1"), row("new-2")) + original.filter { it.id != "note-20" })
        assertAnchor("note-21", 23)
    }

    @Test fun missingSuccessorUsesEquallyNearPredecessorBeforeDistantSuccessor() {
        start()
        coverAndReturn(listOf(row("new-1"), row("new-2")) +
            original.filter { it.id !in setOf("note-20", "note-21") })
        assertAnchor("note-19", 23)
    }

    @Test fun pendingAnchorSurvivesAnotherSaveWhileRowsAreEmpty() {
        val restoration = start()
        coverAndReturn(emptyList(), settled = false)
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            rows.value = listOf(row("new")) + original
            ready.value = true
        }
        assertAnchor("note-20", 23)
    }

    @Test fun savedNeighborsSurviveEmptyLoadingAndAnotherRecreation() {
        val restoration = start()
        coverAndReturn(emptyList(), settled = false)
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            rows.value = listOf(row("new-1"), row("new-2")) + original.filter { it.id != "note-20" }
            ready.value = true
        }
        assertAnchor("note-21", 23)
    }

    @Test fun newRowsDoNotReplaceLiveListStateOrReplayConsumedRestore() {
        start()
        coverAndReturn(original)
        val returned = list
        compose.runOnIdle { scope.launch { list.scrollToItem(30, 41) } }
        compose.waitForIdle()
        compose.runOnIdle { rows.value = listOf(row("new-1"), row("new-2")) + original }
        assertAnchor("note-30", 41)
        compose.runOnIdle { assertSame(returned, list) }
    }

    @Test fun missingWholeNeighborhoodUsesClampedIndexOnce() {
        start()
        coverAndReturn((0 until 60).map { row("replacement-$it") })
        assertAnchor("replacement-20", 23)
        compose.runOnIdle { scope.launch { list.scrollToItem(30, 41) } }
        compose.waitForIdle()
        compose.runOnIdle { rows.value = original + rows.value }
        assertAnchor("replacement-30", 41)
    }

    @Test fun explicitScrollCancelsDelayedReturn() {
        start()
        coverAndReturn(original, settled = false)
        val release = CompletableDeferred<Unit>()
        compose.runOnIdle {
            scope.launch { list.scroll(MutatePriority.UserInput) { scrollBy(120f); release.await() } }
        }
        compose.waitForIdle()
        compose.runOnIdle { release.complete(Unit) }
        compose.waitForIdle()
        var expectedOffset = 0
        compose.runOnIdle { expectedOffset = list.firstVisibleItemScrollOffset; ready.value = true }
        assertAnchor("note-20", expectedOffset)
    }

    private fun start(): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            UnsilenceTheme {
                val saved = rememberSaveableStateHolder()
                if (visible.value) saved.SaveableStateProvider("feed-entry") {
                    val state = rememberFeedScrollState(rows.value, ready.value)
                    val compositionScope = rememberCoroutineScope()
                    SideEffect { list = state; scope = compositionScope }
                    LazyColumn(Modifier.fillMaxSize(), state = state) {
                        items(rows.value, key = { it.id }) { Spacer(Modifier.height(180.dp)) }
                    }
                }
            }
        }
        compose.runOnIdle { scope.launch { list.scrollToItem(20, 23) } }
        compose.waitForIdle()
        return restoration
    }

    private fun coverAndReturn(updated: List<FeedRow>, settled: Boolean = true) {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { rows.value = updated; ready.value = settled; visible.value = true }
        compose.waitForIdle()
    }

    private fun assertAnchor(id: String, offset: Int) {
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(id, rows.value[list.firstVisibleItemIndex].id)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
        }
    }

    private fun row(id: String) = FeedRow(
        id, "author", 1, "", 0L, "[]", "", null, null, false, null,
        0L, null, null, null, null, 0, 0, 0, 0,
    )
}
