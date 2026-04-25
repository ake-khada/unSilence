package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import com.unsilence.app.data.model.Segment
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Surface1

private val MediaPlaceholder = Surface1

/**
 * YouTube thumbnail card — shows hqdefault thumbnail at 16:9.
 * Tapping opens the YouTube URL in the system browser/app.
 */
@Composable
internal fun YouTubeCard(
    segment: Segment.YouTube,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(MediaPlaceholder)
            .clickable {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(segment.url),
                )
                runCatching { context.startActivity(intent) }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model              = "https://img.youtube.com/vi/${segment.videoId}/hqdefault.jpg",
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.matchParentSize(),
        )
    }
}
