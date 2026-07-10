package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.unsilence.app.data.model.Segment
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Surface1

private val MediaPlaceholder = Surface1

internal fun youtubeThumbnailUrls(videoId: String): List<String> = listOf(
    "https://i.ytimg.com/vi_webp/$videoId/hqdefault.webp",
    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
    "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
)

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
    val thumbnailUrls = remember(segment.videoId) { youtubeThumbnailUrls(segment.videoId) }
    var thumbnailIndex by remember(segment.videoId) { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(MediaPlaceholder)
            .semantics { contentDescription = "Open YouTube video" }
            .clickable {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    segment.url.toUri(),
                )
                runCatching { context.startActivity(intent) }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model              = rememberFullWidthImageRequest(thumbnailUrls[thumbnailIndex], aspectRatio = 16f / 9f),
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.matchParentSize(),
            onError            = {
                if (thumbnailIndex < thumbnailUrls.lastIndex) thumbnailIndex++
            },
        )

        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(38.dp),
            )
        }

        Text(
            text = "YouTube",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(4.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}
