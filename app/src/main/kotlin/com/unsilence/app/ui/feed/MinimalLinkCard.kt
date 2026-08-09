package com.unsilence.app.ui.feed

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

internal enum class MinimalLinkIconStage {
    APPLE_TOUCH_ICON,
    FAVICON,
    GENERIC;

    fun next(): MinimalLinkIconStage = when (this) {
        APPLE_TOUCH_ICON -> FAVICON
        FAVICON, GENERIC -> GENERIC
    }
}

/**
 * First-party-only icon policy for OG-less link cards. A missing site icon is a
 * presentation failure, not permission to disclose the viewed domain to a proxy.
 */
internal fun minimalLinkIconUrl(
    host: String,
    stage: MinimalLinkIconStage,
    loadFavicon: Boolean = true,
): String? {
    if (!loadFavicon || host.isBlank()) return null
    return when (stage) {
        MinimalLinkIconStage.APPLE_TOUCH_ICON -> "https://$host/apple-touch-icon.png"
        MinimalLinkIconStage.FAVICON -> "https://$host/favicon.ico"
        MinimalLinkIconStage.GENERIC -> null
    }
}

@Composable
fun MinimalLinkCard(
    url: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loadFavicon: Boolean = true,
) {
    val host = remember(url) {
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
    }
    // Stay first-party even when a site's WAF blocks direct icon paths; a generic
    // icon is preferable to disclosing every viewed domain to a proxy.
    var iconStage by remember(url) { mutableStateOf(MinimalLinkIconStage.APPLE_TOUCH_ICON) }
    val iconUrl = remember(host, iconStage, loadFavicon) {
        minimalLinkIconUrl(host, iconStage, loadFavicon)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        if (iconUrl != null) {
            AsyncImage(
                model = rememberAvatarImageRequest(iconUrl, 40.dp),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp)),
                onError = { iconStage = iconStage.next() },
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.width(Spacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = host,
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.SemiBold,
                fontSize = AppType.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = url,
                color = TextSecondary,
                fontSize = AppType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
