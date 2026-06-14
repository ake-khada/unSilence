package com.unsilence.app.ui.feed

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.BrandDeep

/**
 * Shared inline-span primitives for rich-text rendering. These are the single
 * source of truth for how a link / hashtag / mention looks and behaves so the
 * note path ([InlineText] over [com.unsilence.app.data.model.Segment]) and the
 * article path ([com.unsilence.app.ui.markdown.MarkdownContent] over
 * [com.unsilence.app.data.model.markdown.MdInline]) render identically — no
 * second inline renderer drifting out of sync.
 *
 * Each is an [AnnotatedString.Builder] extension so a block builder can append a
 * styled, tappable run mid-string.
 */

/** Cyan clickable URL. [text] defaults to the raw url (note path appends the url itself). */
internal fun AnnotatedString.Builder.appendLinkSpan(url: String, text: String = url) {
    withLink(
        LinkAnnotation.Url(
            url = url,
            styles = TextLinkStyles(
                style = SpanStyle(
                    color          = Brand,
                    textDecoration = TextDecoration.None,
                ),
            ),
        ),
    ) {
        append(text)
    }
}

/** BrandDeep tappable `#tag`; [onTap] receives the bare tag (no `#`). */
internal fun AnnotatedString.Builder.appendHashtagSpan(tag: String, onTap: (String) -> Unit) {
    withLink(
        LinkAnnotation.Clickable(
            tag = "hashtag:$tag",
            styles = TextLinkStyles(
                style = SpanStyle(
                    color          = BrandDeep,
                    textDecoration = TextDecoration.None,
                ),
            ),
            linkInteractionListener = { onTap(tag) },
        ),
    ) {
        append("#$tag")
    }
}

/** Cyan medium-weight `@name` mention; [onTap] receives the pubkey hex. */
internal fun AnnotatedString.Builder.appendMentionSpan(
    pubkeyHex: String,
    displayName: String,
    onTap: (String) -> Unit,
) {
    withLink(
        LinkAnnotation.Clickable(
            tag = pubkeyHex,
            styles = TextLinkStyles(
                style = SpanStyle(
                    color          = Brand,
                    fontWeight     = FontWeight.Medium,
                    textDecoration = TextDecoration.None,
                ),
            ),
            linkInteractionListener = { onTap(pubkeyHex) },
        ),
    ) {
        append("@$displayName")
    }
}
