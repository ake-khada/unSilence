package com.unsilence.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.unsilence.app.ui.theme.Black

/**
 * Full-screen loading state: line-to-wave animation on true black.
 *
 * Call sites: FeedScreen's Crossfade loading branch and RootScreen's
 * isLoggingOut transition. The caller owns the exit fade; keep this
 * composable zero-arg.
 */
@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
        contentAlignment = Alignment.Center,
    ) {
        LineToWaveLoading(Modifier.fillMaxWidth(0.55f))
    }
}
