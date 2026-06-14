package com.unsilence.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.model.Segment
import com.unsilence.app.ui.theme.AppType
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Renders pre-parsed segments as rich text with inline @mention links,
 * clickable URLs, and tappable #hashtag pills.
 *
 * Walks [Segment.Text], [Segment.MentionPubkey], [Segment.Link], and
 * [Segment.Hashtag] in order, building an AnnotatedString. Links render as
 * cyan clickable text. Hashtags render as BrandDeep tappable text.
 *
 * Non-text segments (Image, Video, YouTube, QuoteEvent, QuoteAddress)
 * are silently skipped — they render in their own composables.
 */
@Composable
internal fun InlineText(
    segments: List<Segment>,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    onAuthorClick: (String) -> Unit,
    onHashtagClick: (String) -> Unit = {},
    onTextClick: () -> Unit,
    customEmojis: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    onTextLayoutResult: ((TextLayoutResult) -> Unit)? = null,
    textColor: Color? = null, // null = default onSurface; blockquotes pass TextSecondary
) {
    // Extract text-renderable segments only
    val textSegments = remember(segments) {
        segments.filter { it is Segment.Text || it is Segment.MentionPubkey || it is Segment.Link || it is Segment.Hashtag }
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

    val hasHashtags = remember(textSegments) {
        textSegments.any { it is Segment.Hashtag }
    }

    // No mentions AND no links AND no hashtags AND no custom emoji — plain Text (fast path)
    if (mentionPubkeys.isEmpty() && !hasLinks && !hasHashtags && customEmojis.isEmpty()) {
        val plainText = remember(textSegments) {
            textSegments.joinToString("") { (it as Segment.Text).text }
        }
        Text(
            text      = plainText,
            color     = textColor ?: MaterialTheme.colorScheme.onSurface,
            fontSize  = AppType.bodyLarge,
            lineHeight = 22.sp,
            maxLines  = maxLines,
            overflow  = overflow,
            textAlign = textAlign,
            onTextLayout = onTextLayoutResult ?: {},
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

    val annotatedText = remember(textSegments, profileMap, onAuthorClick, onHashtagClick, customEmojis) {
        buildAnnotatedString {
            for (segment in textSegments) {
                when (segment) {
                    is Segment.Text -> {
                        if (customEmojis.isEmpty()) {
                            append(segment.text)
                        } else {
                            appendTextWithEmoji(segment.text, customEmojis)
                        }
                    }
                    is Segment.MentionPubkey -> {
                        val profile = profileMap[segment.pubkeyHex]
                        val npubFallback = runCatching {
                            NPub.create(segment.pubkeyHex).take(16) + "…"
                        }.getOrDefault("${segment.pubkeyHex.take(8)}…")
                        val displayName = profile?.displayName?.takeIf { it.isNotBlank() }
                            ?: profile?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
                            ?: npubFallback

                        appendMentionSpan(segment.pubkeyHex, displayName, onAuthorClick)
                    }
                    is Segment.Link -> {
                        appendLinkSpan(segment.url)
                    }
                    is Segment.Hashtag -> {
                        appendHashtagSpan(segment.tag, onHashtagClick)
                    }
                    else -> { /* skip non-text segments */ }
                }
            }
        }
    }

    val emojiInlineContent = remember(customEmojis) {
        if (customEmojis.isEmpty()) emptyMap()
        else customEmojis.mapValues { (_, url) ->
            InlineTextContent(
                placeholder = Placeholder(
                    width = 1.2.em,
                    height = 1.2.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                ),
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    Text(
        text         = annotatedText,
        inlineContent = emojiInlineContent,
        color        = textColor ?: MaterialTheme.colorScheme.onSurface,
        fontSize     = AppType.bodyLarge,
        lineHeight   = 22.sp,
        maxLines     = maxLines,
        overflow     = overflow,
        textAlign    = textAlign,
        onTextLayout = onTextLayoutResult ?: {},
        modifier     = modifier.clickable { onTextClick() },
    )
}

/**
 * Appends [text] to the receiver, substituting `:shortcode:` substrings with
 * inline content placeholders when the shortcode exists in [emojis].
 * Scans structurally for `:` pairs and checks against the emoji map directly —
 * no regex charset restrictions, handles spaces/hyphens/dots in shortcodes.
 * Unmatched colon patterns pass through as plain text.
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendTextWithEmoji(
    text: String,
    emojis: Map<String, String>,
) {
    var cursor = 0
    while (cursor < text.length) {
        val openColon = text.indexOf(':', cursor)
        if (openColon == -1 || openColon + 2 >= text.length) break
        val closeColon = text.indexOf(':', openColon + 1)
        if (closeColon == -1) break
        val shortcode = text.substring(openColon + 1, closeColon)
        if (shortcode.isNotEmpty() && shortcode in emojis) {
            if (openColon > cursor) append(text.substring(cursor, openColon))
            appendInlineContent(shortcode, ":$shortcode:")
            cursor = closeColon + 1
        } else {
            // Not a known emoji — emit up to and including the opening colon, keep scanning
            append(text.substring(cursor, openColon + 1))
            cursor = openColon + 1
        }
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}
