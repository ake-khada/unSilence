package com.unsilence.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.data.model.markdown.MarkdownDocument
import com.unsilence.app.data.model.markdown.MdBlock
import com.unsilence.app.data.model.markdown.MdInline
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.ui.feed.appendHashtagSpan
import com.unsilence.app.ui.feed.appendLinkSpan
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary

/**
 * Renders a parsed [MarkdownDocument] (kind-30023 article body) as native Compose
 * blocks — the replacement for the old WebView body. Inline link / hashtag styling
 * is shared with the note path via [appendLinkSpan] / [appendHashtagSpan] so both
 * render identically. URLs open through the ambient UriHandler (platform browser),
 * matching the note path; hashtags route through [onHashtagClick].
 *
 * Eager (a plain [Column], not a LazyColumn) — the reader is a single vertical
 * scroll; LazyColumn + inline comments are phase 3.
 */
@Composable
internal fun MarkdownContent(
    document: MarkdownDocument,
    onHashtagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Drop a leading H1 whose text equals the screen title (avoids a duplicate header). */
    suppressLeadingTitle: String? = null,
) {
    val blocks = remember(document, suppressLeadingTitle) {
        val first = document.blocks.firstOrNull()
        if (suppressLeadingTitle != null &&
            first is MdBlock.Heading && first.level == 1 &&
            flattenMdInlines(first.inlines).trim().equals(suppressLeadingTitle.trim(), ignoreCase = true)
        ) {
            document.blocks.drop(1)
        } else {
            document.blocks
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        blocks.forEach { block ->
            MdBlockView(block, textColor = Color.White, onHashtagClick = onHashtagClick)
        }
    }
}

@Composable
private fun MdBlockView(
    block: MdBlock,
    textColor: Color,
    onHashtagClick: (String) -> Unit,
) {
    when (block) {
        is MdBlock.Heading -> {
            val (size, line) = when (block.level) {
                1    -> AppType.display to 30.sp
                2    -> AppType.heading to 24.sp
                3    -> AppType.subheading to 22.sp
                else -> AppType.body to 20.sp
            }
            InlineMd(
                inlines       = block.inlines,
                textColor     = Color.White,
                fontSize      = size,
                lineHeight    = line,
                fontWeight    = FontWeight.Bold,
                onHashtagClick = onHashtagClick,
            )
        }

        is MdBlock.Paragraph -> {
            InlineMd(
                inlines       = block.inlines,
                textColor     = textColor,
                fontSize      = AppType.bodyLarge,
                lineHeight    = 24.sp,
                onHashtagClick = onHashtagClick,
            )
        }

        is MdBlock.BlockQuote -> {
            val railColor = TextSecondary.copy(alpha = 0.5f)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(color = railColor, size = Size(3.dp.toPx(), size.height)) }
                    .padding(start = Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                block.blocks.forEach { inner ->
                    MdBlockView(inner, textColor = TextSecondary, onHashtagClick = onHashtagClick)
                }
            }
        }

        is MdBlock.ListBlock -> {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.micro)) {
                block.items.forEachIndexed { index, itemBlocks ->
                    Row {
                        Text(
                            text     = if (block.ordered) "${index + 1}. " else "•  ",
                            color    = textColor,
                            fontSize = AppType.bodyLarge,
                            lineHeight = 24.sp,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Spacing.micro),
                        ) {
                            itemBlocks.forEach { inner ->
                                MdBlockView(inner, textColor = textColor, onHashtagClick = onHashtagClick)
                            }
                        }
                    }
                }
            }
        }

        is MdBlock.CodeBlock -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
                    .background(Surface2)
                    .horizontalScroll(rememberScrollState())
                    .padding(Spacing.small),
            ) {
                Text(
                    text       = block.code,
                    color      = textColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = AppType.bodySmall,
                    lineHeight = 20.sp,
                )
            }
        }

        is MdBlock.Image -> {
            SubcomposeAsyncImage(
                model              = rememberFullWidthImageRequest(block.url),
                contentDescription = block.alt,
                contentScale       = ContentScale.FillWidth,
                modifier           = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
            )
        }

        is MdBlock.Table -> {
            MarkdownTable(
                table          = block.table,
                textColor      = textColor,
                onHashtagClick = onHashtagClick,
                modifier       = Modifier.fillMaxWidth(),
            )
        }

        MdBlock.HorizontalRule -> {
            HorizontalDivider(color = BorderFaint, thickness = 1.dp)
        }
    }
}

@Composable
private fun InlineMd(
    inlines: List<MdInline>,
    textColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    onHashtagClick: (String) -> Unit,
    fontWeight: FontWeight? = null,
) {
    val text = remember(inlines, onHashtagClick) {
        buildAnnotatedString { appendMdInlines(inlines, onHashtagClick) }
    }
    Text(
        text       = text,
        color      = textColor,
        fontSize   = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        modifier   = Modifier.fillMaxWidth(),
    )
}

// ── Inline model → AnnotatedString ───────────────────────────────────────────

/**
 * Appends [inlines] to the builder, recursing into nested spans. Links/hashtags
 * delegate to the shared [appendLinkSpan]/[appendHashtagSpan] primitives so the
 * article path matches the note path. Empty-url links (unresolved reference
 * links — 0.7.3 doesn't resolve definitions) render as plain styled text.
 */
internal fun AnnotatedString.Builder.appendMdInlines(
    inlines: List<MdInline>,
    onHashtagClick: (String) -> Unit,
) {
    for (inline in inlines) {
        when (inline) {
            is MdInline.Text -> append(inline.text)
            is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendMdInlines(inline.children, onHashtagClick)
            }
            is MdInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendMdInlines(inline.children, onHashtagClick)
            }
            is MdInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendMdInlines(inline.children, onHashtagClick)
            }
            is MdInline.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = Surface2),
            ) {
                append(inline.text)
            }
            is MdInline.Link -> {
                val label = flattenMdInlines(inline.children).ifEmpty { inline.url }
                if (inline.url.isBlank()) append(label) else appendLinkSpan(inline.url, label)
            }
            is MdInline.Hashtag -> appendHashtagSpan(inline.tag, onHashtagClick)
        }
    }
}

/** Flattens inline spans to their plain text (for table width + link labels). */
internal fun flattenMdInlines(inlines: List<MdInline>): String = buildString {
    for (inline in inlines) {
        when (inline) {
            is MdInline.Text -> append(inline.text)
            is MdInline.Strong -> append(flattenMdInlines(inline.children))
            is MdInline.Emphasis -> append(flattenMdInlines(inline.children))
            is MdInline.Strikethrough -> append(flattenMdInlines(inline.children))
            is MdInline.Code -> append(inline.text)
            is MdInline.Link -> append(flattenMdInlines(inline.children).ifEmpty { inline.url })
            is MdInline.Hashtag -> append("#${inline.tag}")
        }
    }
}
