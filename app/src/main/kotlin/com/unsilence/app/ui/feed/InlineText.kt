package com.unsilence.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.sp
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.model.Segment
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Brand
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Renders pre-parsed segments as rich text with inline @mention links and
 * clickable URLs.
 *
 * Walks [Segment.Text], [Segment.MentionPubkey], and [Segment.Link] in order,
 * building an AnnotatedString. Links render as cyan clickable text that opens
 * the URL via the system handler.
 *
 * Non-text segments (Image, Video, YouTube, QuoteEvent, QuoteAddress)
 * are silently skipped — they render in their own composables.
 */
@Composable
internal fun InlineText(
    segments: List<Segment>,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    onAuthorClick: (String) -> Unit,
    onTextClick: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    onTextLayoutResult: ((TextLayoutResult) -> Unit)? = null,
) {
    // Extract text-renderable segments only
    val textSegments = remember(segments) {
        segments.filter { it is Segment.Text || it is Segment.MentionPubkey || it is Segment.Link }
    }

    // No text content at all — skip rendering
    if (textSegments.isEmpty()) return

    // Extract unique mention pubkeys for batch resolution
    val mentionPubkeys = remember(textSegments) {
        textSegments.filterIsInstance<Segment.MentionPubkey>().map { it.pubkeyHex }.distinct()
    }

    val hasLinks = remember(textSegments) {
        textSegments.any { it is Segment.Link }
    }

    // No mentions AND no links — plain Text with click handler (fast path)
    if (mentionPubkeys.isEmpty() && !hasLinks) {
        val plainText = remember(textSegments) {
            textSegments.joinToString("") { (it as Segment.Text).text }
        }
        Text(
            text      = plainText,
            color     = MaterialTheme.colorScheme.onSurface,
            fontSize  = AppType.bodyLarge,
            lineHeight = 22.sp,
            maxLines  = maxLines,
            overflow  = overflow,
            textAlign = textAlign,
            modifier  = modifier.clickable { onTextClick() },
        )
        return
    }

    // Resolve display names reactively
    var profileMap by remember(mentionPubkeys) {
        mutableStateOf(emptyMap<String, UserEntity?>())
    }
    LaunchedEffect(mentionPubkeys) {
        if (lookupProfile != null && mentionPubkeys.isNotEmpty()) {
            profileMap = coroutineScope {
                mentionPubkeys.map { hex ->
                    async { hex to lookupProfile(hex) }
                }.awaitAll().toMap()
            }
        }
    }

    val annotatedText = remember(textSegments, profileMap, onAuthorClick) {
        buildAnnotatedString {
            for (segment in textSegments) {
                when (segment) {
                    is Segment.Text -> append(segment.text)
                    is Segment.MentionPubkey -> {
                        val profile = profileMap[segment.pubkeyHex]
                        val npubFallback = runCatching {
                            NPub.create(segment.pubkeyHex).take(16) + "…"
                        }.getOrDefault("${segment.pubkeyHex.take(8)}…")
                        val displayName = profile?.displayName?.takeIf { it.isNotBlank() }
                            ?: profile?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
                            ?: npubFallback

                        withLink(
                            LinkAnnotation.Clickable(
                                tag = segment.pubkeyHex,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color          = Brand,
                                        fontWeight     = FontWeight.Medium,
                                        textDecoration = TextDecoration.None,
                                    ),
                                ),
                                linkInteractionListener = { onAuthorClick(segment.pubkeyHex) },
                            ),
                        ) {
                            append("@$displayName")
                        }
                    }
                    is Segment.Link -> {
                        withLink(
                            LinkAnnotation.Url(
                                url = segment.url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color          = Brand,
                                        textDecoration = TextDecoration.None,
                                    ),
                                ),
                            ),
                        ) {
                            append(segment.url)
                        }
                    }
                    else -> { /* skip non-text segments */ }
                }
            }
        }
    }

    Text(
        text         = annotatedText,
        color        = MaterialTheme.colorScheme.onSurface,
        fontSize     = AppType.bodyLarge,
        lineHeight   = 22.sp,
        maxLines     = maxLines,
        overflow     = overflow,
        textAlign    = textAlign,
        onTextLayout = onTextLayoutResult ?: {},
        modifier     = modifier.clickable { onTextClick() },
    )
}
