package com.unsilence.app.ui.feed

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArticleViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deferredBodyParsePreservesReadingOffset() = restoredAnchor(index = 0, offset = 800)
    @Test fun deferredCommentsPreserveCommentAnchor() = restoredAnchor(index = 12, offset = 21)

    private fun restoredAnchor(index: Int, offset: Int) {
        val ready = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
        restoration.setContent {
            UnsilenceTheme {
                val state = rememberLazyListState()
                val compositionScope = rememberCoroutineScope()
                SideEffect { listState = state; scope = compositionScope }
                ArticleViewport(ready.value, Modifier.fillMaxSize()) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                        item(key = "article") { Spacer(Modifier.height(5_000.dp)) }
                        items(40, key = { "comment-$it" }) { Spacer(Modifier.height(120.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { scope.launch { listState.scrollToItem(index, offset) } }
        compose.waitForIdle()
        compose.runOnIdle { ready.value = false }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.runOnIdle { ready.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(index, listState.firstVisibleItemIndex)
            assertEquals(offset, listState.firstVisibleItemScrollOffset)
        }
    }
}
