package com.unsilence.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Size

/**
 * Build an ImageRequest with an explicit pixel-size resolver. Coil will decode
 * the source bitmap at these dimensions instead of full resolution, which is
 * the primary fix for bitmap memory pressure on image-heavy feeds.
 *
 * Remembered across recompositions per (url, width, height). Must be called
 * from a @Composable scope.
 */
@Composable
fun rememberSizedImageRequest(
    url: String?,
    widthPx: Int,
    heightPx: Int,
): ImageRequest {
    val context = LocalContext.current
    return remember(url, widthPx, heightPx) {
        ImageRequest.Builder(context)
            .data(url)
            .size(Size(Dimension.Pixels(widthPx), Dimension.Pixels(heightPx)))
            .build()
    }
}

/**
 * Square image request sized from a Dp value (typically for avatars).
 * Converts dp to pixels using the current LocalDensity.
 */
@Composable
fun rememberAvatarImageRequest(
    url: String?,
    sizeDp: Dp,
): ImageRequest {
    val density = LocalDensity.current
    val px = with(density) { sizeDp.roundToPx() }
    return rememberSizedImageRequest(url, px, px)
}

/**
 * Full-width image request sized from the current screen width (typically for
 * feed media, banners, and OG previews). Height is computed from aspectRatio
 * with a sane fallback for unknown aspect ratios.
 */
@Composable
fun rememberFullWidthImageRequest(
    url: String?,
    aspectRatio: Float = 16f / 9f,
): ImageRequest {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = with(density) { config.screenWidthDp.dp.roundToPx() }
    val safeAspect = if (aspectRatio > 0f) aspectRatio else (16f / 9f)
    val heightPx = (widthPx / safeAspect).toInt().coerceIn(100, 4000)
    return rememberSizedImageRequest(url, widthPx, heightPx)
}
