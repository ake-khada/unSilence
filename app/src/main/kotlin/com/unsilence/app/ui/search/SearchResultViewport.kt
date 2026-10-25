package com.unsilence.app.ui.search

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Only compact LazyListState positions and one boolean enter saved state. */
internal class SearchResultViewport(
    val listState: LazyListState,
    private val followFirst: MutableState<Boolean>,
) {
    private var previousHead: String? = null

    val scrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y != 0f && source == NestedScrollSource.UserInput) retainPosition()
            return Offset.Zero
        }
    }

    fun retainPosition() { followFirst.value = false }

    fun onHead(head: String?) {
        if (head != previousHead) {
            previousHead = head
            // Override LazyColumn's stable-key anchoring only during initial
            // progressive results. Once read/scrolled/covered, retain its anchor.
            if (head != null && followFirst.value) listState.requestScrollToItem(0)
        }
    }
}

@Composable
internal fun rememberSearchResultViewport(query: String, firstKey: String?): SearchResultViewport = key(query) {
    val list = rememberLazyListState()
    val followFirst = rememberSaveable { mutableStateOf(true) }
    val viewport = remember(list, followFirst) { SearchResultViewport(list, followFirst) }
    SideEffect { viewport.onHead(firstKey) }
    LaunchedEffect(viewport) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (scrolling) viewport.retainPosition()
        }
    }
    LifecycleResumeEffect(viewport) {
        onPauseOrDispose { viewport.retainPosition() }
    }
    viewport
}
