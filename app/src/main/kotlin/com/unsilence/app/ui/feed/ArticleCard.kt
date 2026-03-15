package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val ArticleCardBackground = Color(0xFF0D0D0D)

/** Parses the first value for [key] from a NIP-23 tags JSON string. */
private fun tagValue(tagsJson: String, key: String): String? = runCatching {
    Json.parseToJsonElement(tagsJson).jsonArray
        .firstOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == key
        }
        ?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
}.getOrNull()

@Composable
fun ArticleCard(
    row: FeedRow,
    onClick: () -> Unit,
) {
    val title   = tagValue(row.tags, "title")
    val summary = tagValue(row.tags, "summary")
        ?: row.content.take(150).replace('\n', ' ').ifBlank { null }
    val image   = tagValue(row.tags, "image")

    val authorLabel = row.displayName ?: "${row.pubkey.take(6)}…${row.pubkey.takeLast(4)}"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small)
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(ArticleCardBackground)
            .clickable { onClick() },
    ) {
        // ── Banner image (16:9) ──────────────────────────────────────────────
        if (!image.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model              = image,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(
                        topStart = Sizing.mediaCornerRadius,
                        topEnd   = Sizing.mediaCornerRadius,
                    )),
            )
        }

        // ── Author row ─────────────────────────────────────────────────────────
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(Sizing.avatar).clip(CircleShape)) {
                IdentIcon(pubkey = row.pubkey, modifier = Modifier.fillMaxSize())
                if (!row.authorPicture.isNullOrBlank()) {
                    AsyncImage(
                        model              = row.authorPicture,
                        contentDescription = null,
                        modifier           = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(Spacing.small))
            Text(
                text       = authorLabel,
                color      = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 13.sp,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f),
            )
            Text(
                text     = articleRelativeTime(row.createdAt),
                color    = TextSecondary,
                fontSize = 12.sp,
            )
        }

        // ── Article title ──────────────────────────────────────────────────────
        if (!title.isNullOrBlank()) {
            Text(
                text       = title,
                color      = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize   = 16.sp,
                lineHeight = 22.sp,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = 4.dp),
            )
        }

        // ── Article summary ────────────────────────────────────────────────────
        if (!summary.isNullOrBlank()) {
            Text(
                text     = summary,
                color    = TextSecondary,
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }
    }
}

private val FeedRow.displayName: String?
    get() = authorDisplayName?.takeIf { it.isNotBlank() }
         ?: authorName?.takeIf { it.isNotBlank() }

private fun articleRelativeTime(createdAtSeconds: Long): String {
    val diffMs = System.currentTimeMillis() - createdAtSeconds * 1000L
    return when {
        diffMs < TimeUnit.MINUTES.toMillis(1) -> "now"
        diffMs < TimeUnit.HOURS.toMillis(1)   -> "${TimeUnit.MILLISECONDS.toMinutes(diffMs)}m"
        diffMs < TimeUnit.DAYS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toHours(diffMs)}h"
        diffMs < TimeUnit.DAYS.toMillis(7)    -> "${TimeUnit.MILLISECONDS.toDays(diffMs)}d"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(createdAtSeconds * 1000L))
    }
}
