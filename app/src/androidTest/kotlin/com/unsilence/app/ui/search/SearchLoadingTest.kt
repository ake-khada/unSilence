package com.unsilence.app.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline production indicator/collector coverage; not a full relay-backed SearchScreen. */
@RunWith(AndroidJUnit4::class)
class SearchLoadingTest {
    @get:Rule val compose = createComposeRule()
    private val spinner = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    @Test fun rapidKeystrokesWhileLoadingDoNotLatchTheIndicatorAfterResults() {
        val state = mutableStateOf(SearchUiState())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UnsilenceTheme {
                Column(Modifier.fillMaxWidth().testTag("header")) {
                    BasicTextField(state.value.query, onValueChange = {
                        // Same synchronous debounce-loading transition as search().
                        state.value = SearchUiState(query = it, loading = it.length >= 3)
                    }, modifier = Modifier.testTag("query"))
                    SearchLoadingLine(state.value.loading)
                }
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("query").performTextInput("nos")
        compose.mainClock.advanceTimeBy(32)
        compose.onAllNodes(spinner).assertCountEquals(1)
        compose.onNodeWithTag("query").performTextInput("t")
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("query").performTextInput("r")
        compose.mainClock.advanceTimeBy(32)
        compose.onAllNodes(spinner).assertCountEquals(1)
        val bounds = compose.onNodeWithTag("header").getUnclippedBoundsInRoot()
        compose.runOnUiThread { state.value = state.value.copy(loading = false) }
        compose.mainClock.advanceTimeBy(64)
        compose.onAllNodes(spinner).assertCountEquals(0)
        assertEquals(bounds, compose.onNodeWithTag("header").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("query").performTextInput("x")
        compose.mainClock.advanceTimeBy(32)
        compose.onAllNodes(spinner).assertCountEquals(1)
        compose.onNodeWithTag("query").performTextClearance()
        compose.mainClock.advanceTimeBy(32)
        compose.onAllNodes(spinner).assertCountEquals(0)
    }

    @Test fun silentSearchEndsItsIndicatorAndShowsEmptyStateWithoutAnotherProjection() {
        val loading = mutableStateOf(true)
        val rows = MutableSharedFlow<List<String>>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UnsilenceTheme {
                LaunchedEffect(Unit) {
                    rows.collectWithInitialSearchLoading(true, { true }, { it.isNotEmpty() }, {
                        loading.value = it
                    }, {})
                }
                Column {
                    SearchLoadingLine(loading.value)
                    if (!loading.value) Text("No results")
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onAllNodes(spinner).assertCountEquals(1)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onAllNodes(spinner).assertCountEquals(0)
        compose.onNodeWithText("No results").assertIsDisplayed()
    }
}
