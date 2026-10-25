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
import java.net.URI
import java.net.URISyntaxException

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

/**
 * Shared secondary-cyan URL span. Raw URL labels are compacted only for display;
 * authored labels and the original [LinkAnnotation.Url] destination stay intact.
 */
internal fun AnnotatedString.Builder.appendLinkSpan(url: String, text: String = url) {
    withLink(
        LinkAnnotation.Url(
            url = url,
            styles = TextLinkStyles(
                style = SpanStyle(
                    color          = BrandDeep,
                    // Second, non-color affordance so links aren't color-only.
                    fontWeight     = FontWeight.Medium,
                    textDecoration = TextDecoration.None,
                ),
            ),
        ),
    ) {
        append(if (text == url) compactLinkLabel(url) else text)
    }
}

private const val LINK_PATH_LABEL_LIMIT = 24

/** Never apply a length limit to the authority: the actual host must remain visible. */
private fun compactLinkLabel(url: String): String {
    val parsed = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return url
    }
    if ((!parsed.scheme.equals("https", ignoreCase = true) &&
            !parsed.scheme.equals("http", ignoreCase = true)) || parsed.host == null
    ) return url
    // Validate the host before removing credentials. Keep all subdomains, www,
    // explicit ports and IPv6 brackets exactly as written; never show userinfo
    // in place of the destination host. Ambiguous/unsupported input stays raw.
    val authority = parsed.rawAuthority?.substringAfterLast('@') ?: return url
    val path = parsed.rawPath.orEmpty()
    val clippedPath = if (path.length > LINK_PATH_LABEL_LIMIT) {
        var end = LINK_PATH_LABEL_LIMIT
        val slash = path.lastIndexOf('/', end - 1)
        if (slash > 0) end = slash + 1
        // Avoid displaying half a percent escape or a broken surrogate pair.
        val percent = path.lastIndexOf('%', end - 1)
        if (percent >= 0 && end - percent < 3) end = percent
        if (end > 0 && path[end - 1].isHighSurrogate()) end--
        path.take(end) + "…"
    } else path
    val omittedTail = parsed.rawQuery != null || parsed.rawFragment != null
    return authority + clippedPath + if (omittedTail && path.length <= LINK_PATH_LABEL_LIMIT) "…" else ""
}

/** BrandDeep tappable `#tag`; [onTap] receives the bare tag (no `#`). */
internal fun AnnotatedString.Builder.appendHashtagSpan(tag: String, onTap: (String) -> Unit) {
    withLink(
        LinkAnnotation.Clickable(
            tag = "hashtag:$tag",
            styles = TextLinkStyles(
                style = SpanStyle(
                    color          = BrandDeep,
                    // Second, non-color affordance so hashtags aren't color-only.
                    fontWeight     = FontWeight.Medium,
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
