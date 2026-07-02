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
import androidx.compose.runtime.mutableIntStateOf
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
    // 0 = Google favicon API (bypasses WAF), 1 = direct apple-touch-icon, 2 = direct favicon, 3 = generic icon
    var iconStage by remember(url) { mutableIntStateOf(0) }
    val iconUrl = remember(host, iconStage, loadFavicon) {
        if (!loadFavicon) {
            null
        } else {
            when (iconStage) {
                0 -> "https://www.google.com/s2/favicons?domain=$host&sz=128"
                1 -> "https://$host/apple-touch-icon.png"
                2 -> "https://$host/favicon.ico"
                else -> null
            }
        }
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
                onError = { iconStage++ },
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
