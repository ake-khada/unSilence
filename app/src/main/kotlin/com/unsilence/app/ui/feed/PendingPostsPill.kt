package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Spacing
import kotlinx.coroutines.flow.StateFlow

/** Count changes only; no layout/scroll animation state is read in composition. */
@Composable
internal fun PendingPostsPill(
    pendingCount: StateFlow<Int>,
    atTop: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count by pendingCount.collectAsStateWithLifecycle()
    if (!showPendingPosts(count, atTop)) return
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = "$count new posts. Show newest posts" }
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$count new ↑",
            color = Black,
            modifier = Modifier
                .background(Brand, RoundedCornerShape(20.dp))
                .padding(horizontal = Spacing.large, vertical = Spacing.small),
        )
    }
}

internal fun showPendingPosts(count: Int, atTop: Boolean): Boolean = count > 0 && !atTop
