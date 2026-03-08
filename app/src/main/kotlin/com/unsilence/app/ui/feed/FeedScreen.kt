package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

@Composable
fun FeedScreen(viewModel: FeedViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val statusBarHeight = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp()
    }

    // fillMaxSize + Black background draws behind the transparent status bar on API 35+.
    // statusBarsPadding() / statusBarHeight on content keeps cards below the clock/icons.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        when {
            state.loading -> {
                CircularProgressIndicator(
                    color    = Cyan,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .statusBarsPadding(),
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
                        .statusBarsPadding()
                        .padding(horizontal = Spacing.xl),
                )
            }

            else -> {
                LazyColumn(
                    state          = listState,
                    modifier       = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(top = statusBarHeight),
                ) {
                    items(
                        items = state.events,
                        key   = { it.id },
                    ) { row ->
                        NoteCard(row = row)
                    }
                }
            }
        }
    }
}
