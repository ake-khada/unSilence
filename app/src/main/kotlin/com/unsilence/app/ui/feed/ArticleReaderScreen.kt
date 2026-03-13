package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun ArticleReaderScreen(row: FeedRow, onDismiss: () -> Unit) {
    val title  = articleTagValue(row.tags, "title")
    val image  = articleTagValue(row.tags, "image")

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Black),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // ── Top bar ────────────────────────────────────────────────────
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector        = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint               = androidx.compose.ui.graphics.Color.White,
                            modifier           = Modifier.size(22.dp),
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                // ── Banner image ───────────────────────────────────────────────
                if (!image.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model              = image,
                        contentDescription = null,
                        contentScale       = ContentScale.FillWidth,
                        modifier           = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 200.dp)
                            .padding(Spacing.medium)
                            .clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
                    )
                }

                // ── Title ──────────────────────────────────────────────────────
                if (!title.isNullOrBlank()) {
                    Text(
                        text       = title,
                        color      = androidx.compose.ui.graphics.Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 22.sp,
                        lineHeight = 30.sp,
                        modifier   = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium)
                            .padding(bottom = Spacing.small),
                    )
                }

                HorizontalDivider(
                    color    = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.medium),
                )

                Spacer(Modifier.height(Spacing.medium))

                // ── Body content (full markdown via multiplatform-markdown-renderer) ──
                Markdown(
                    content          = row.content,
                    imageTransformer = Coil3ImageTransformerImpl,
                    colors           = markdownColor(
                        text                 = Color.White,
                        codeBackground       = Color(0xFF1A1A1A),
                        inlineCodeBackground = Color(0xFF1A1A1A),
                        dividerColor         = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    typography       = markdownTypography(
                        h1        = MaterialTheme.typography.headlineLarge.copy(color = Color.White),
                        h2        = MaterialTheme.typography.headlineMedium.copy(color = Color.White),
                        h3        = MaterialTheme.typography.headlineSmall.copy(color = Color.White),
                        paragraph = MaterialTheme.typography.bodyLarge.copy(color = Color.White, lineHeight = 24.sp),
                        code      = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace),
                        textLink  = TextLinkStyles(style = SpanStyle(color = Cyan)),
                    ),
                    modifier         = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium)
                        .padding(bottom = Spacing.xl),
                )
            }
        }
    }
}

private fun articleTagValue(tagsJson: String, key: String): String? = runCatching {
    Json.parseToJsonElement(tagsJson).jsonArray
        .firstOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == key
        }
        ?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
}.getOrNull()
