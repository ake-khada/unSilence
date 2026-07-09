package com.unsilence.app.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.unsilence.app.data.media.SaveMediaKind
import com.unsilence.app.data.model.Segment
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

private val MediaPlaceholder = Surface1

/**
 * Image display for pre-parsed [Segment.Image] list from [EventModel.media.images].
 *
 * Layout: 1=full-width single image, 2+=horizontal pager carousel with dot indicators.
 * Tapping an image opens a fullscreen pager dialog.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun EventMediaGrid(
    images: List<Segment.Image>,
    imageDimensionCache: ImageDimensionCache? = null,
    onImageClick: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return

    var fullscreenIndex by remember { mutableIntStateOf(-1) }
    val imageUrls = remember(images) { images.map { it.url } }

    if (images.size == 1) {
        EventMediaImage(
            image = images[0],
            onImageClick = {
                if (onImageClick != null) onImageClick(0) else fullscreenIndex = 0
            },
            imageDimensionCache = imageDimensionCache,
            modifier = modifier,
        )
    } else {
        // Carousel frame locked to first image's aspect ratio
        val firstImage = images[0]
        val firstImeta = firstImage.imetaAspect
        val firstCached = imageDimensionCache?.getCached(firstImage.url)
        var frameAspect by remember(images) {
            mutableFloatStateOf(feedImageAspectRatio(firstImeta ?: firstCached))
        }
        var frameResolved by remember(images) {
            mutableStateOf(firstImeta != null || firstCached != null)
        }

        LaunchedEffect(firstImage.url) {
            if (frameResolved) return@LaunchedEffect
            // Do not issue a separate range request for images merely passing
            // through composition during a fling. Coil may also finish during
            // this dwell and make the probe unnecessary.
            delay(200)
            if (frameResolved) return@LaunchedEffect
            val ratio = imageDimensionCache?.resolve(firstImage.url) ?: return@LaunchedEffect
            if (!frameResolved) {
                frameAspect = feedImageAspectRatio(ratio)
                frameResolved = true
            }
        }

        val pagerState = rememberPagerState(pageCount = { images.size })

        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(frameAspect, matchHeightConstraintsFirst = false)
                    .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
                    .background(MediaPlaceholder),
            ) { page ->
                var hasError by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            if (onImageClick != null) onImageClick(page) else fullscreenIndex = page
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = rememberFullWidthImageRequest(
                            images[page].url,
                            aspectRatio = frameAspect,
                        ),
                        contentDescription = null,
                        contentScale       = ContentScale.Fit,
                        modifier           = Modifier.fillMaxSize(),
                        onSuccess = { state ->
                            if (!frameResolved && page == 0) {
                                val w = state.result.image.width
                                val h = state.result.image.height
                                if (w > 0 && h > 0) {
                                    val ratio = w.toFloat() / h
                                    imageDimensionCache?.put(firstImage.url, ratio)
                                    frameAspect = feedImageAspectRatio(ratio)
                                    frameResolved = true
                                }
                            }
                        },
                        onError = { hasError = true },
                    )
                    if (hasError) {
                        Icon(
                            imageVector        = Icons.Filled.BrokenImage,
                            contentDescription = "Image failed to load",
                            tint               = TextSecondary,
                            modifier           = Modifier.size(32.dp),
                        )
                    }
                }
            }

            // Dot indicators
            Row(
                modifier = Modifier.padding(top = Spacing.small),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(images.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = if (pagerState.currentPage == index) Brand
                                    else Brand.copy(alpha = 0.3f),
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }
    }

    // Fullscreen image viewer
    if (fullscreenIndex >= 0 && imageUrls.isNotEmpty()) {
        FullScreenImageDialog(
            imageUrls    = imageUrls,
            initialIndex = fullscreenIndex.coerceAtMost(imageUrls.lastIndex),
            onDismiss    = { fullscreenIndex = -1 },
        )
    }
}

/**
 * Single image cell — aspect ratio from imeta (pre-parsed in [Segment.Image.imetaAspect])
 * or pre-fetched [ImageDimensionCache], resolves once from the decoded bitmap and locks.
 * ContentScale.Fit within the true-ratio container — no crop, no letterbox (except during
 * the brief pre-resolve window when the default 4:3 container may not match).
 */
@Composable
internal fun EventMediaImage(
    image: Segment.Image,
    onImageClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageDimensionCache: ImageDimensionCache? = null,
) {
    val imetaKnown = image.imetaAspect != null
    val cachedRatio = imageDimensionCache?.getCached(image.url)
    val initialAspect = image.imetaAspect ?: cachedRatio
    var displayAspect by remember(image.url) {
        mutableFloatStateOf(feedImageAspectRatio(initialAspect))
    }
    var hasBeenResolved by remember(image.url) {
        mutableStateOf(imetaKnown || cachedRatio != null)
    }

    // Active resolution: lightweight header fetch (~100-300ms) settles the container
    // while shimmer is still showing — far faster than waiting for the full bitmap decode.
    LaunchedEffect(image.url) {
        if (hasBeenResolved) return@LaunchedEffect
        delay(200)
        if (hasBeenResolved) return@LaunchedEffect
        val ratio = imageDimensionCache?.resolve(image.url) ?: return@LaunchedEffect
        if (!hasBeenResolved) {
            displayAspect = feedImageAspectRatio(ratio)
            hasBeenResolved = true
        }
    }

    var hasError by remember(image.url) { mutableStateOf(false) }

    val imageModifier = modifier
        .fillMaxWidth()
        .aspectRatio(displayAspect, matchHeightConstraintsFirst = false)
        .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
        .background(MediaPlaceholder)
        .clickable { onImageClick() }

    Box(modifier = imageModifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model              = rememberFullWidthImageRequest(image.url, aspectRatio = displayAspect),
            contentDescription = null,
            contentScale       = ContentScale.Fit,
            modifier           = Modifier.fillMaxSize(),
            onSuccess          = { state ->
                val w = state.result.image.width
                val h = state.result.image.height
                if (w > 0 && h > 0) {
                    imageDimensionCache?.put(image.url, w.toFloat() / h)
                    if (!hasBeenResolved) {
                        displayAspect = feedImageAspectRatio(w.toFloat() / h)
                        hasBeenResolved = true
                    }
                }
            },
            onError = { hasError = true },
        )
        if (hasError) {
            Icon(
                imageVector        = Icons.Filled.BrokenImage,
                contentDescription = "Image failed to load",
                tint               = TextSecondary,
                modifier           = Modifier.size(32.dp),
            )
        }
    }
}

/** Full-screen image viewer dialog with zoomable horizontal pager pages. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FullScreenImageDialog(
    imageUrls: List<String>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val pagerState = rememberPagerState(
                initialPage = initialIndex,
                pageCount   = { imageUrls.size },
            )
            val saveController = rememberMediaSaveController(SaveMediaKind.IMAGE)
            val zoomFractions = remember(imageUrls) { mutableStateMapOf<Int, Float>() }
            val currentZoomFraction = zoomFractions[pagerState.currentPage] ?: 0f
            val isZoomed = currentZoomFraction > 0.01f
            var chromeVisible by remember { mutableStateOf(true) }
            val showChrome = chromeVisible && !isZoomed

            LaunchedEffect(pagerState.currentPage) {
                chromeVisible = true
            }

            HorizontalPager(
                state             = pagerState,
                userScrollEnabled = !isZoomed,
                modifier          = Modifier.fillMaxSize(),
            ) { page ->
                ZoomableImagePage(
                    url = imageUrls[page],
                    onTap = { chromeVisible = !chromeVisible },
                    onZoomFractionChanged = { zoomFractions[page] = it },
                )
            }

            if (showChrome) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(96.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.70f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector        = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint               = Color.White,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text       = "${pagerState.currentPage + 1} / ${imageUrls.size}",
                        color      = Color.White,
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    imageUrls.getOrNull(pagerState.currentPage)?.let { currentUrl ->
                        MediaDownloadButton(
                            url        = currentUrl,
                            controller = saveController,
                        )
                    }
                }
            }

            saveController.message?.let { message ->
                MediaSaveStatusPill(
                    message = message,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun ZoomableImagePage(
    url: String,
    onTap: () -> Unit,
    onZoomFractionChanged: (Float) -> Unit,
) {
    val zoomableState = rememberZoomableState()
    val imageState = rememberZoomableImageState(zoomableState)

    LaunchedEffect(zoomableState) {
        snapshotFlow { zoomableState.zoomFraction ?: 0f }
            .distinctUntilChanged()
            .collect { onZoomFractionChanged(it) }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZoomableAsyncImage(
            model              = url,
            contentDescription = null,
            state              = imageState,
            contentScale       = ContentScale.Fit,
            onClick            = { onTap() },
            modifier           = Modifier.fillMaxSize(),
        )
    }
}
