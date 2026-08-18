package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.common.rememberWidthImageRequest
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * OpenGraph link preview card. Fetches OG metadata and renders a rich card
 * (image + title + description + domain).
 *
 * When [showMinimalFallback] is true (default), falls back to [MinimalLinkCard]
 * if OG fetch returns nothing useful. When false, renders nothing on empty OG
 * (caller already shows the URL as inline text, so the fallback is redundant).
 */
@Composable
internal fun OgPreviewCard(
    url: String,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)? = null,
    hasCachedOgMetadata: ((String) -> Boolean)? = null,
    imageDimensionCache: ImageDimensionCache? = null,
    onDirectImageClick: (() -> Unit)? = null,
    showMinimalFallback: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val domain = remember(url) {
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
    }

    var ogLoaded by remember(url) { mutableStateOf(fetchOgMetadata == null) }
    val og by produceState<OgMetadata?>(null, url) {
        if (fetchOgMetadata != null) {
            // Avoid starting HTML requests for cards that only flash through
            // composition during a fling. If the warm lane already fetched or
            // negative-cached this URL, skip the dwell so viewport entry is a
            // cache read instead of a guaranteed 250ms placeholder.
            if (hasCachedOgMetadata?.invoke(url) != true) {
                delay(250)
            }
            value = fetchOgMetadata(url)
            ogLoaded = true
        }
    }

    val loadedOg = og
    val directImageUrl = loadedOg
        ?.takeIf { it.isDirectImage }
        ?.imageUrl
        ?.takeIf { it.isNotBlank() }
    if (directImageUrl != null) {
        val image = remember(directImageUrl) {
            Segment.Image(url = directImageUrl, imetaAspect = null)
        }
        EventMediaGrid(
            images = listOf(image),
            imageDimensionCache = imageDimensionCache,
            onImageClick = onDirectImageClick?.let { click -> { _: Int -> click() } },
            modifier = modifier,
        )
    } else if (loadedOg != null && (loadedOg.title != null || loadedOg.imageUrl != null)) {
        var imageLoadFailed by remember { mutableStateOf(false) }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
                .background(SurfaceVariant)
                .border(0.5.dp, BorderFaint, RoundedCornerShape(Sizing.mediaCornerRadius))
                .clickable { runCatching { uriHandler.openUri(url) } },
        ) {
            if (!loadedOg.imageUrl.isNullOrBlank() && !imageLoadFailed) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(
                            topStart = Sizing.mediaCornerRadius,
                            topEnd = Sizing.mediaCornerRadius,
                        ))
                        .background(Surface1),
                ) {
                    AsyncImage(
                        model              = rememberWidthImageRequest(loadedOg.imageUrl, maxWidth, 16f / 9f),
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        onError            = { imageLoadFailed = true },
                        modifier           = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(modifier = Modifier.padding(Spacing.small)) {
                if (!loadedOg.title.isNullOrBlank()) {
                    Text(
                        text       = loadedOg.title,
                        color      = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = AppType.bodySmall,
                        lineHeight = 17.sp,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                    )
                }
                if (!loadedOg.description.isNullOrBlank()) {
                    Text(
                        text     = loadedOg.description,
                        color    = TextSecondary,
                        fontSize = AppType.footnote,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text     = loadedOg.siteName ?: domain,
                    color    = TextSecondary,
                    fontSize = AppType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    } else if (!ogLoaded && showMinimalFallback) {
        // Cold OG fetch: keep the card useful and compact instead of reserving
        // a viewport-visible 16:9 blank skeleton. The warm lane should make
        // most viewport entries rich; when it misses, this fallback avoids the
        // dark "loading card" regression and avoids an extra favicon request.
        MinimalLinkCard(
            url = url,
            onClick = { runCatching { uriHandler.openUri(url) } },
            loadFavicon = false,
            modifier = modifier,
        )
    } else if (showMinimalFallback) {
        // OG fetch returned nothing useful — minimal link card with favicon + domain
        MinimalLinkCard(
            url = url,
            onClick = { runCatching { uriHandler.openUri(url) } },
        )
    }
    // else: showMinimalFallback=false and OG empty → render nothing (URL already inline)
}
