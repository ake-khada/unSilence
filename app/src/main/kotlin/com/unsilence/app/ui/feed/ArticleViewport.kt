package com.unsilence.app.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Do not spend a restored LazyListState anchor against a short loading placeholder. */
@Composable
internal fun ArticleViewport(
    ready: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (ready) content() else {
        Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
