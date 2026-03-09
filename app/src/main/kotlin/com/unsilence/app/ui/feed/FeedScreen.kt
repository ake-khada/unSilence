package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

@Composable
fun FeedScreen(
    scrollToTopTrigger: Int = 0,
    onNoteClick: (String) -> Unit = {},
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) listState.animateScrollToItem(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        when {
            state.loading -> {
                CircularProgressIndicator(
                    color    = Cyan,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            state.events.isEmpty() -> {
                Text(
                    text       = "No posts yet.\nConnect to a relay to load the feed.",
                    color      = TextSecondary,
                    fontSize   = 15.sp,
                    textAlign  = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier   = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = Spacing.xl),
                )
            }

            else -> {
                LazyColumn(
                    state    = listState,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(
                        items = state.events,
                        key   = { it.id },
                    ) { row ->
                        NoteCard(row = row, onNoteClick = onNoteClick)
                    }
                }

                // Auto-scroll: keyed on hasNewTopPost so it re-fires the moment a new
                // top post arrives. snapshotFlow on firstVisibleItemIndex can't work here
                // because LazyColumn preserves scroll position on prepend — the index
                // shifts from 0 to 1 when an item inserts above, so "index == 0" never
                // fires. Keying LaunchedEffect on the flag itself sidesteps that entirely.
                LaunchedEffect(viewModel.hasNewTopPost) {
                    if (viewModel.hasNewTopPost && listState.firstVisibleItemIndex <= 2) {
                        listState.scrollToItem(0)
                        viewModel.clearNewTopPost()
                    }
                }

                // Pagination: separate observer so it's not tangled with scroll-to-top.
                LaunchedEffect(Unit) {
                    snapshotFlow { listState.firstVisibleItemIndex }
                        .collect { index ->
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0 && index > total * 0.5) {
                                viewModel.loadMore()
                            }
                        }
                }
            }
        }
    }
}
