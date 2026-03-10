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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
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

                // ── Body content (simple markdown: ## headers, **bold**) ────────
                Text(
                    text       = parseMarkdown(row.content),
                    color      = MaterialTheme.colorScheme.onSurface,
                    fontSize   = 15.sp,
                    lineHeight = 24.sp,
                    modifier   = Modifier
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

/**
 * Minimal markdown → AnnotatedString renderer.
 *  - Lines starting with "## " → bold 18sp (header)
 *  - **text** spans → bold (any nesting level)
 */
private fun parseMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.split("\n")
    lines.forEachIndexed { lineIdx, rawLine ->
        if (rawLine.startsWith("## ")) {
            // Whole header line: bold 18sp; strip any ** markers inside it
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)) {
                append(rawLine.removePrefix("## ").replace("**", ""))
            }
        } else {
            // Split on ** delimiters; odd-indexed segments are bold
            val parts = rawLine.split("**")
            parts.forEachIndexed { partIdx, part ->
                if (partIdx % 2 == 1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
                } else {
                    append(part)
                }
            }
        }
        if (lineIdx < lines.lastIndex) append("\n")
    }
}
