package com.unsilence.app.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.layoutBounds
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

private val LocalAvatarViewport = compositionLocalOf<LayoutBoundsHolder?> { null }

/**
 * Observe availability once per list, not its changing rectangle in every avatar's
 * composition. An unwired/temporarily detached holder must fall back to the window:
 * onVisibilityChanged otherwise waits indefinitely for a supplied holder's bounds.
 * Pass null at a dialog/sheet's content root: relative bounds from a parent window
 * cannot be compared with that window's avatars. Its own lists may supply new bounds.
 */
@Composable
internal fun ProvideAvatarViewport(
    bounds: LayoutBoundsHolder?,
    content: @Composable () -> Unit,
) {
    val populated by remember(bounds) {
        derivedStateOf(structuralEqualityPolicy()) { bounds?.bounds != null }
    }
    CompositionLocalProvider(LocalAvatarViewport provides bounds.takeIf { populated }, content = content)
}

/**
 * A normal LazyColumn with one stable viewport for its avatars. Measure the list
 * itself, never animated bar offsets/padding. Overlay occlusion is intentionally
 * not tracked; covering an avatar with the sliding header does not move this viewport.
 */
@Composable
internal fun AvatarLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: LazyListScope.() -> Unit,
) {
    val bounds = remember { LayoutBoundsHolder() }
    ProvideAvatarViewport(bounds) {
        LazyColumn(
            modifier = modifier.layoutBounds(bounds),
            state = state,
            contentPadding = contentPadding,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}

/** Any nonzero overlap is visible. The callback runs only when eligibility flips. */
@Composable
internal fun Modifier.avatarVisibility(onChanged: (Boolean) -> Unit): Modifier =
    onVisibilityChanged(
        minDurationMs = 0,
        minFractionVisible = 0f,
        viewportBounds = LocalAvatarViewport.current,
        callback = onChanged,
    )

/**
 * Preserve the request, visible animation and static painter. Only lend playback
 * ownership while this image's own bounds are visible and its lifecycle is RESUMED.
 * Outside an avatar-aware list, visibility is relative to the current window.
 */
@Composable
internal fun AvatarAsyncImage(
    model: ImageRequest,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    imageLoader: ImageLoader = SingletonImageLoader.get(LocalPlatformContext.current),
    onLoading: ((AsyncImagePainter.State.Loading) -> Unit)? = null,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null,
) {
    val visible = remember { mutableStateOf(false) }
    val onVisibilityChanged = remember { { value: Boolean -> visible.value = value } }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier.avatarVisibility(onVisibilityChanged),
        contentScale = contentScale,
        imageLoader = imageLoader,
        transform = rememberAvatarAnimationTransform(visible.value),
        onState = { state ->
            when (state) {
                is AsyncImagePainter.State.Loading -> onLoading?.invoke(state)
                is AsyncImagePainter.State.Success -> onSuccess?.invoke(state)
                is AsyncImagePainter.State.Error -> onError?.invoke(state)
                else -> Unit
            }
        },
    )
}
