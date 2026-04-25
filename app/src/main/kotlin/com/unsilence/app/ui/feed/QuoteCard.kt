package com.unsilence.app.ui.feed

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.relay.ImetaParser
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.ui.common.rememberSizedImageRequest
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.TextSecondary

// Regex patterns for extracting media from quoted content (duplicated inline —
// these are the same patterns from NoteCard.kt but scoped to quote rendering).
private val IMAGE_URL_RE = Regex(
    """https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?|https?://(?:image\.nostr\.build|i\.nostr\.build|nostr\.build|blossom\.primal\.net)/\S+|https?://\S+/xrpc/com\.atproto\.sync\.getBlob\?\S+""",
    RegexOption.IGNORE_CASE,
)
private val VIDEO_URL_RE = Regex(
    """https?://\S+\.(?:mp4|mov|webm|m3u8|m4v|avi)(?:\?\S*)?""",
    RegexOption.IGNORE_CASE,
)
private val NOSTR_URI_RE = Regex("nostr:[a-z0-9]+", RegexOption.IGNORE_CASE)
private val LINK_URL_RE = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

private val GET_BLOB_RE = Regex(
    """https?://\S+/xrpc/com\.atproto\.sync\.getBlob\?did=(did:[^&\s]+)&cid=([^&\s]+)""",
    RegexOption.IGNORE_CASE,
)
private fun rewriteBskyUrl(url: String): String {
    val m = GET_BLOB_RE.find(url) ?: return url
    return "https://cdn.bsky.app/img/feed_fullsize/plain/${m.groupValues[1]}/${m.groupValues[2]}@jpeg"
}

/** Single-phase embedded quote resolution data. */
private data class QuoteResolution(
    val event: EventEntity? = null,
    val author: UserEntity? = null,
)

/**
 * Tappable inline card for a quoted nostr event ([Segment.QuoteEvent]).
 *
 * Shows actual content when available: author, text, images, video placeholder,
 * OG preview, and nested quotes (one level deep).
 */
@Composable
internal fun QuoteCard(
    segment: Segment.QuoteEvent,
    onNoteClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit = {},
    lookupEvent: (suspend (String, List<String>) -> EventEntity?)? = null,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)? = null,
    modifier: Modifier = Modifier,
    nestDepth: Int = 0,
) {
    val quoteData by produceState(QuoteResolution(), segment.eventId, segment.hints) {
        val ev = lookupEvent?.invoke(segment.eventId, segment.hints) ?: return@produceState
        val auth = lookupProfile?.invoke(ev.pubkey)
        value = QuoteResolution(ev, auth)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable { onNoteClick(segment.eventId) }
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        val loadedEvent = quoteData.event
        val author = quoteData.author
        if (loadedEvent != null) {
            Column {
                // Header: avatar + name + timestamp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarImage(
                        pubkey   = loadedEvent.pubkey,
                        picture  = author?.picture,
                        modifier = Modifier.size(24.dp),
                        sizeDp   = 24.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text     = author?.displayName?.takeIf { it.isNotBlank() }
                            ?: author?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
                            ?: "${loadedEvent.pubkey.take(6)}…${loadedEvent.pubkey.takeLast(4)}",
                        color    = Color.White.copy(alpha = 0.7f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = AppType.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text     = relativeTime(loadedEvent.createdAt),
                        color    = TextSecondary,
                        fontSize = AppType.caption,
                    )
                }
                Spacer(Modifier.height(4.dp))

                // Extract media from quoted content
                val rawContent = loadedEvent.content
                val imetaMedia = remember(loadedEvent.tags) {
                    ImetaParser.parse(loadedEvent.tags)
                }
                val imetaImageUrls = remember(imetaMedia) {
                    imetaMedia.filter { it.mimeType?.startsWith("image") == true }
                        .map { it.url }.toSet()
                }
                val imetaVideoUrls = remember(imetaMedia) {
                    imetaMedia.filter { it.mimeType?.startsWith("video") == true }
                        .map { it.url }.toSet()
                }
                val quotedImages = remember(rawContent, imetaImageUrls) {
                    val regexImages = IMAGE_URL_RE.findAll(rawContent).map { it.value }.distinct().toList()
                    (imetaImageUrls + regexImages).distinct().map(::rewriteBskyUrl)
                }
                val quotedVideos = remember(rawContent, imetaVideoUrls) {
                    val regexVideos = VIDEO_URL_RE.findAll(rawContent).map { it.value }.distinct().toList()
                    (imetaVideoUrls + regexVideos).distinct().filter { it !in quotedImages }
                }

                // Clean text: strip nostr URIs and media URLs
                val cleanContent = remember(rawContent) {
                    rawContent
                        .let { NOSTR_URI_RE.replace(it, "") }
                        .let { IMAGE_URL_RE.replace(it, "") }
                        .let { VIDEO_URL_RE.replace(it, "") }
                        .trim()
                }
                val linkUrls = remember(rawContent) {
                    LINK_URL_RE.findAll(rawContent).map { it.value }
                        .filter { url -> url !in quotedImages && url !in quotedVideos }
                        .distinct().toList()
                }
                val displayText = remember(cleanContent) {
                    LINK_URL_RE.replace(cleanContent, "").trim()
                }
                if (displayText.isNotBlank()) {
                    // Use the old NostrRichText for quoted text — the quoted event
                    // doesn't have pre-parsed segments yet
                    NostrRichText(
                        content       = displayText,
                        lookupProfile = lookupProfile,
                        onAuthorClick = onAuthorClick,
                        onTextClick   = { onNoteClick(segment.eventId) },
                        maxLines      = 6,
                        overflow      = TextOverflow.Ellipsis,
                    )
                }

                // Images (suppress when link URLs present — OG preview shows hero image)
                if (quotedImages.isNotEmpty() && linkUrls.isEmpty()) {
                    val quoteDensity = LocalDensity.current
                    val quoteConfig = LocalConfiguration.current
                    val quoteCellWidthPx = with(quoteDensity) { (quoteConfig.screenWidthDp.dp / 2).roundToPx() }
                    val quoteCellHeightPx = with(quoteDensity) { 150.dp.roundToPx() }

                    Spacer(Modifier.height(6.dp))
                    if (quotedImages.size == 1) {
                        AsyncImage(
                            model              = rememberFullWidthImageRequest(quotedImages.first()),
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Surface1),
                        )
                    } else {
                        val gridImages = quotedImages.take(4)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (rowIdx in gridImages.indices step 2) {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    AsyncImage(
                                        model              = rememberSizedImageRequest(gridImages[rowIdx], quoteCellWidthPx, quoteCellHeightPx),
                                        contentDescription = null,
                                        contentScale       = ContentScale.Crop,
                                        modifier           = Modifier
                                            .weight(1f)
                                            .height(150.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                    )
                                    if (rowIdx + 1 < gridImages.size) {
                                        AsyncImage(
                                            model              = rememberSizedImageRequest(gridImages[rowIdx + 1], quoteCellWidthPx, quoteCellHeightPx),
                                            contentDescription = null,
                                            contentScale       = ContentScale.Crop,
                                            modifier           = Modifier
                                                .weight(1f)
                                                .height(150.dp)
                                                .clip(RoundedCornerShape(6.dp)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Video thumbnail placeholder (no autoplay in quotes)
                if (quotedVideos.isNotEmpty() && quotedImages.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Surface1),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.PlayArrow,
                            contentDescription = "Video",
                            tint               = Color.White.copy(alpha = 0.6f),
                            modifier           = Modifier.size(48.dp),
                        )
                    }
                }

                // OG link preview for first non-media URL
                if (linkUrls.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    OgPreviewCard(
                        url             = linkUrls.first(),
                        fetchOgMetadata = fetchOgMetadata,
                    )
                }

                // Nested quote: render one level deep
                if (nestDepth < 1) {
                    val nestedQuotes = remember(loadedEvent.content) {
                        val nostrUriRegex = Regex("nostr:n(?:ote|event)1[a-z0-9]+", RegexOption.IGNORE_CASE)
                        nostrUriRegex.findAll(loadedEvent.content)
                            .mapNotNull { m ->
                                runCatching {
                                    val entity = com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
                                        .uriToRoute(m.value)?.entity
                                    when (entity) {
                                        is com.vitorpamplona.quartz.nip19Bech32.entities.NEvent ->
                                            Segment.QuoteEvent(entity.hex, entity.relay.map { it.url })
                                        is com.vitorpamplona.quartz.nip19Bech32.entities.NNote ->
                                            Segment.QuoteEvent(entity.hex, emptyList())
                                        else -> null
                                    }
                                }.getOrNull()
                            }.toList()
                    }
                    nestedQuotes.forEach { nestedSeg ->
                        Spacer(Modifier.height(4.dp))
                        QuoteCard(
                            segment         = nestedSeg,
                            onNoteClick     = onNoteClick,
                            onAuthorClick   = onAuthorClick,
                            lookupEvent     = lookupEvent,
                            lookupProfile   = lookupProfile,
                            fetchOgMetadata = fetchOgMetadata,
                            nestDepth       = nestDepth + 1,
                        )
                    }
                }
            }
        } else {
            // Loading skeleton
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Surface1))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.width(100.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(Surface1))
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(2.dp)).background(Surface1))
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(14.dp).clip(RoundedCornerShape(2.dp)).background(Surface1))
            }
        }
    }
}
