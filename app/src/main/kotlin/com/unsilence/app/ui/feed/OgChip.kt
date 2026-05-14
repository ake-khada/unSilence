package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.common.rememberSizedImageRequest
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary

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
            value = fetchOgMetadata(url)
            ogLoaded = true
        }
    }

    val loadedOg = og
    if (loadedOg != null && (loadedOg.title != null || loadedOg.imageUrl != null)) {
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
                val density = LocalDensity.current
                val config = LocalConfiguration.current
                val widthPx = with(density) { config.screenWidthDp.dp.roundToPx() }
                val heightPx = (widthPx * 9) / 16
                SubcomposeAsyncImage(
                    model              = rememberSizedImageRequest(loadedOg.imageUrl, widthPx, heightPx),
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    error              = { imageLoadFailed = true },
                    modifier           = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(
                            topStart = Sizing.mediaCornerRadius,
                            topEnd = Sizing.mediaCornerRadius,
                        ))
                        .background(Surface1),
                )
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
    } else if (!ogLoaded) {
        // Loading state — fixed height placeholder matching rich preview card
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
                .background(SurfaceVariant)
                .border(0.5.dp, BorderFaint, RoundedCornerShape(Sizing.mediaCornerRadius))
                .clickable { runCatching { uriHandler.openUri(url) } },
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Surface1))
            Column(modifier = Modifier.padding(Spacing.small)) {
                Box(Modifier.fillMaxWidth(0.8f).height(14.dp).clip(RoundedCornerShape(2.dp)).background(Surface1))
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(2.dp)).background(Surface1))
            }
        }
    } else if (showMinimalFallback) {
        // OG fetch returned nothing useful — minimal link card with favicon + domain
        MinimalLinkCard(
            url = url,
            onClick = { runCatching { uriHandler.openUri(url) } },
        )
    }
    // else: showMinimalFallback=false and OG empty → render nothing (URL already inline)
}
