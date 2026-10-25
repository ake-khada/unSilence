package com.unsilence.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unsilence.app.ui.common.scrollAwayHeader
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand

/** The VM already includes the debounce window; never latch a second loading flag here. */
@Composable
internal fun SearchLoadingLine(loading: Boolean) {
    if (loading) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Brand,
            trackColor = Color.Transparent,
        )
    } else {
        Spacer(Modifier.height(2.dp))
    }
}

/** The result viewport never moves; only the header overlay translates. */
@Composable
internal fun SearchControlsLayout(
    controlsVisible: Boolean,
    header: @Composable () -> Unit,
    content: @Composable (staticTopPadding: Dp) -> Unit,
) {
    var headerHeight by remember { mutableIntStateOf(0) }
    val topPadding = with(LocalDensity.current) { headerHeight.toDp() }
    Box(Modifier.fillMaxSize().background(Black).clipToBounds()) {
        content(topPadding)
        Box(Modifier.fillMaxWidth()
            .onSizeChanged { headerHeight = it.height }
            .scrollAwayHeader(controlsVisible)
            .background(Black)) { header() }
    }
}
