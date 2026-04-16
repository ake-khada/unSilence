package com.unsilence.app.ui.feed

import android.util.Log
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.ui.common.rememberSizedImageRequest
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.Nip19FailureCache
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.ImetaParser
import com.unsilence.app.data.relay.ImetaMedia
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.data.relay.extractRepostAuthorPubkey
import com.unsilence.app.data.relay.extractRepostTargetId
import com.unsilence.app.data.relay.extractRepostTargetRelay
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.LocalShowSnackbar
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.unsilence.app.ui.shared.ThreadParentCard
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.ZapAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

internal val ActionTint = Color(0xFF555555)
private val MediaPlaceholder = Surface1

// Matches URLs ending in image extensions, or from known Nostr/Bluesky image hosts.
private val IMAGE_URL_REGEX = Regex(
    """https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?|https?://(?:image\.nostr\.build|i\.nostr\.build|nostr\.build|blossom\.primal\.net)/\S+|https?://\S+/xrpc/com\.atproto\.sync\.getBlob\?\S+""",
    RegexOption.IGNORE_CASE,
)

// Matches direct video file URLs only (no web pages like YouTube).
internal val VIDEO_URL_REGEX = Regex(
    """https?://\S+\.(?:mp4|mov|webm|m3u8|m4v|avi)(?:\?\S*)?""",
    RegexOption.IGNORE_CASE,
)

// Matches YouTube and YouTube Shorts URLs, capturing the video ID.
private val YOUTUBE_URL_REGEX = Regex(
    """https?://(?:www\.)?(?:youtube\.com/(?:watch\?v=|shorts/)|youtu\.be/)([A-Za-z0-9_-]{11})\S*""",
    RegexOption.IGNORE_CASE,
)

// Rewrites AT Protocol getBlob URLs to Bluesky CDN proxy (PDS blobs get gc'd, CDN caches persist).
private val GET_BLOB_REGEX = Regex(
    """https?://\S+/xrpc/com\.atproto\.sync\.getBlob\?did=(did:[^&\s]+)&cid=([^&\s]+)""",
    RegexOption.IGNORE_CASE,
)
private fun rewriteBskyUrl(url: String): String {
    val m = GET_BLOB_REGEX.find(url) ?: return url
    return "https://cdn.bsky.app/img/feed_fullsize/plain/${m.groupValues[1]}/${m.groupValues[2]}@jpeg"
}

// Matches any remaining http/https URL (applied after stripping image + video URLs).
private val LINK_URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

// Matches nostr: URIs (bech32-encoded entities).
private val NOSTR_URI_REGEX = Regex("nostr:[a-z0-9]+", RegexOption.IGNORE_CASE)

// Matches event-type nostr: URIs for stripping (note/nevent/naddr).
private val NOSTR_EVENT_URI_REGEX = Regex("nostr:n(?:ote|event|addr)1[a-z0-9]+", RegexOption.IGNORE_CASE)

// Matches profile-type nostr: URIs for inline mention rendering.
private val NOSTR_PROFILE_URI_REGEX = Regex("nostr:n(?:pub|profile)1[a-z0-9]+", RegexOption.IGNORE_CASE)

private sealed class NostrRef {
    data class EventRef(val eventId: String, val relayHints: List<String> = emptyList()) : NostrRef()
    data class ProfileRef(val pubkeyHex: String, val relayHints: List<String> = emptyList()) : NostrRef()
    data class AddressRef(val kind: Int, val author: String, val dTag: String, val relayHints: List<String> = emptyList()) : NostrRef()
}

private fun decodeNostrRef(uri: String): NostrRef? {
    if (Nip19FailureCache.isKnownBad(uri)) return null
    return runCatching {
        when (val entity = Nip19Parser.uriToRoute(uri)?.entity) {
            is NEvent   -> NostrRef.EventRef(entity.hex, entity.relay.map { it.url })
            is NNote    -> NostrRef.EventRef(entity.hex)
            is NAddress -> NostrRef.AddressRef(entity.kind, entity.author, entity.dTag, entity.relay.map { it.url })
            is NPub     -> NostrRef.ProfileRef(entity.hex)
            is NProfile -> NostrRef.ProfileRef(entity.hex, entity.relay.map { it.url })
            else        -> null
        }
    }.onFailure { Nip19FailureCache.markBad(uri) }.getOrNull()
}

/** Extract relay hints from q-tags: eventId → list of relay URLs. */
private fun extractQTagHints(tagsJson: String): Map<String, List<String>> {
    if (!tagsJson.contains("\"q\"")) return emptyMap()
    return try {
        val parsed = NostrJson.parseToJsonElement(tagsJson).jsonArray
        val result = mutableMapOf<String, MutableList<String>>()
        for (tag in parsed) {
            val arr = tag.jsonArray
            if (arr.getOrNull(0)?.jsonPrimitive?.content == "q") {
                val id = arr.getOrNull(1)?.jsonPrimitive?.content ?: continue
                val relay = arr.getOrNull(2)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: continue
                result.getOrPut(id) { mutableListOf() }.add(relay)
            }
        }
        result
    } catch (_: Exception) { emptyMap() }
}

private data class YouTubeEmbed(val url: String, val videoId: String)

private data class MediaExtraction(
    val imageUrls: List<String>,
    val videoUrls: List<String>,
    val youtubeEmbeds: List<YouTubeEmbed>,
    val linkUrls: List<String>,
    val textContent: String,
)

private fun isDirectVideoUrl(url: String): Boolean =
    url.contains(".mp4", ignoreCase = true) ||
    url.contains(".mov", ignoreCase = true) ||
    url.contains(".webm", ignoreCase = true) ||
    url.contains(".m3u8", ignoreCase = true) ||
    url.contains(".m4v", ignoreCase = true) ||
    url.contains(".avi", ignoreCase = true)

/** Cap portrait aspect ratios so images don't dominate the feed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    row: FeedRow,
    modifier: Modifier = Modifier,
    onNoteClick: (String) -> Unit = {},
    onAuthorClick: (pubkey: String) -> Unit = {},
    onReact: () -> Unit = {},
    onRepost: () -> Unit = {},
    onQuote: (String) -> Unit = {},
    onZap: (amountSats: Long) -> Unit = {},
    onSaveNwcUri: (String) -> Unit = {},
    hasReacted: Boolean = false,
    hasReposted: Boolean = false,
    hasZapped: Boolean = false,
    isNwcConfigured: Boolean = false,
    originalAuthorProfile: UserEntity? = null,
    exoPlayer: ExoPlayer? = null,
    isMuted: Boolean = true,
    onToggleMute: () -> Unit = {},
    isActiveVideo: Boolean = false,
    isFullscreen: Boolean = false,
    onOpenFullscreen: () -> Unit = {},
    videoRenderModels: List<VideoRenderModel> = emptyList(),
    thumbnailCache: VideoThumbnailCache? = null,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    lookupEvent: (suspend (String, List<String>) -> EventEntity?)? = null,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)? = null,
    isNewPost: Boolean = false,
    onNewPostAnimated: () -> Unit = {},
    parentEvent: EventEntity? = null,
    parentAuthor: UserEntity? = null,
    isZapLoading: Boolean = false,
    extraZapSats: Long = 0L,
    zapFlash: NoteActionsViewModel.ZapFlashState? = null,
) {
    // Subtle flash animation for newly arrived posts
    val flashAlpha = remember { Animatable(if (isNewPost) 1f else 0f) }
    LaunchedEffect(isNewPost) {
        if (isNewPost) {
            flashAlpha.snapTo(1f)
            flashAlpha.animateTo(0f, tween(durationMillis = 1000))
            onNewPostAnimated()
        }
    }

    var showRepostMenu    by remember { mutableStateOf(false) }
    var showConnectWallet by remember { mutableStateOf(false) }
    var showZapPicker     by remember { mutableStateOf(false) }
    var fullscreenImageIndex by remember { mutableIntStateOf(-1) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val showSnackbar = LocalShowSnackbar.current

    // ── Zap result: flash animation (failure snackbar handled at screen level) ──
    var zapFlashTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(zapFlash) {
        if (zapFlash != null && zapFlash.noteId == row.id && zapFlash.success) {
            zapFlashTrigger++
        }
    }

    // ── Kind 6 repost: parse embedded original event JSON ─────────────────────
    val boostedJson = if (row.kind == 6 && row.content.isNotBlank()) {
        runCatching { NostrJson.parseToJsonElement(row.content).jsonObject }.getOrNull()
    } else null

    // For kind 6 with no embedded JSON (bridges like mostr.pub), fetch the
    // referenced event via the e-tag so the card renders actual content.
    val repostTargetId = if (row.kind == 6) extractRepostTargetId(row.tags) else null
    val repostRelayHint = if (row.kind == 6) extractRepostTargetRelay(row.tags) else null
    val fetchedRepostEvent by produceState<EventEntity?>(null, row.id) {
        if (row.kind == 6 && boostedJson == null && repostTargetId != null && lookupEvent != null) {
            value = lookupEvent.invoke(repostTargetId, listOfNotNull(repostRelayHint))
        }
    }

    val effectivePubkey = boostedJson?.get("pubkey")?.jsonPrimitive?.content
        ?: fetchedRepostEvent?.pubkey
        ?: if (row.kind == 6) extractRepostAuthorPubkey(row.content, row.tags) ?: row.pubkey
        else row.pubkey
    val effectiveCreatedAt = boostedJson?.get("created_at")?.jsonPrimitive?.longOrNull
        ?: fetchedRepostEvent?.createdAt
        ?: row.createdAt
    val effectiveContent = boostedJson?.get("content")?.jsonPrimitive?.content
        ?: fetchedRepostEvent?.content
        ?: row.content

    // For bridge reposts without p-tag, resolve the original author from the fetched event
    val resolvedRepostAuthor by produceState<UserEntity?>(originalAuthorProfile, effectivePubkey, fetchedRepostEvent) {
        if (originalAuthorProfile != null || lookupProfile == null) { value = originalAuthorProfile; return@produceState }
        if (row.kind == 6 && (boostedJson != null || fetchedRepostEvent != null)) {
            value = lookupProfile.invoke(effectivePubkey)
        }
    }
    val repostProfile = originalAuthorProfile ?: resolvedRepostAuthor

    // ── DIAGNOSTIC: log when kind-6 repost target doesn't resolve ──────────
    LaunchedEffect(row.id, boostedJson, fetchedRepostEvent) {
        if (row.kind == 6 && boostedJson == null && fetchedRepostEvent == null && repostTargetId != null) {
            delay(6000) // Wait past lookupEvent's 5s timeout
            Log.w("CardHydrator", "Outbox final: refId=${repostTargetId.take(12)} exists=false " +
                "kind=null author=null relayUrl=null contentLen=0 " +
                "referencedBy=${row.id.take(12)} referencedByKind=6 phase=unresolved")
        } else if (row.kind == 6 && boostedJson == null && fetchedRepostEvent != null) {
            if (fetchedRepostEvent!!.content.isBlank()) {
                Log.w("CardHydrator", "Outbox final: refId=${repostTargetId?.take(12)} exists=true " +
                    "kind=${fetchedRepostEvent!!.kind} author=${fetchedRepostEvent!!.pubkey.take(12)} " +
                    "relayUrl=${fetchedRepostEvent!!.relayUrl} contentLen=0 " +
                    "referencedBy=${row.id.take(12)} referencedByKind=6 phase=fetched-empty")
            }
        }
    }

    // For kind-6 reposts, navigate to the referenced event, not the wrapper
    val navigateId = if (row.kind == 6) repostTargetId ?: row.id else row.id

    // ── NIP-19 nostr: URI extraction (strip before other URL processing) ──────
    // Merge relay hints from q-tags into nevent-derived EventRefs
    val qTagHints = remember(row.tags) { extractQTagHints(row.tags) }
    val nostrRefs = remember(effectiveContent, qTagHints) {
        NOSTR_URI_REGEX.findAll(effectiveContent)
            .mapNotNull { decodeNostrRef(it.value) }
            .map { ref ->
                if (ref is NostrRef.EventRef) {
                    val extra = qTagHints[ref.eventId].orEmpty()
                    if (extra.isNotEmpty()) ref.copy(relayHints = (ref.relayHints + extra).distinct())
                    else ref
                } else ref
            }
            .toList()
    }
    val contentNoNostr = NOSTR_EVENT_URI_REGEX.replace(effectiveContent, "").trim()

    // ── Media extraction: regex from content + imeta from tags ────────────────
    // For kind-6 reposts, use the ORIGINAL event's tags (from embedded JSON or
    // fetched event) since the repost wrapper tags rarely carry imeta.
    val effectiveTags = if (row.kind == 6) {
        boostedJson?.get("tags")?.toString()
            ?: fetchedRepostEvent?.tags
            ?: row.tags
    } else row.tags
    val imetaMedia = remember(effectiveTags) { ImetaParser.parse(effectiveTags) }
    val mediaExtraction = remember(row.id, contentNoNostr) {
        // 1. Extract YouTube URLs first (web pages, not playable files).
        val youtubeEmbeds = YOUTUBE_URL_REGEX.findAll(contentNoNostr).map { match ->
            YouTubeEmbed(url = match.value, videoId = match.groupValues[1])
        }.toList()
        val afterYoutube = YOUTUBE_URL_REGEX.replace(contentNoNostr, "")

        // 2. Extract direct video file URLs (e.g. .mp4) — only inline-playable files.
        val regexVideoUrls = VIDEO_URL_REGEX.findAll(afterYoutube).map { it.value }.toList()
        val imetaVideoUrls = imetaMedia
            .filter { it.mimeType?.startsWith("video/") == true && isDirectVideoUrl(it.url) }
            .map { it.url }
        val allVideoUrls   = (regexVideoUrls + imetaVideoUrls)
            .distinct()
            .filter(::isDirectVideoUrl)

        // 3. Extract image URLs from remaining content.
        val afterVideos    = VIDEO_URL_REGEX.replace(afterYoutube, "")
        val regexImageUrls = IMAGE_URL_REGEX.findAll(afterVideos).map { it.value }.toList()
        val imetaImageUrls = imetaMedia
            .filter { it.mimeType?.startsWith("image/") == true || (it.mimeType == null && !isDirectVideoUrl(it.url)) }
            .map { it.url }
        val allImageUrls   = (regexImageUrls + imetaImageUrls).distinct()
                                 .filter { it !in allVideoUrls }
                                 .map(::rewriteBskyUrl)

        val afterImages    = IMAGE_URL_REGEX.replace(afterVideos, "")
        val ytUrls         = youtubeEmbeds.map { it.url }.toSet()

        MediaExtraction(
            imageUrls      = allImageUrls,
            videoUrls      = allVideoUrls,
            youtubeEmbeds  = youtubeEmbeds,
            linkUrls       = LINK_URL_REGEX.findAll(afterImages).map { it.value }.distinct().take(3).toList()
                                 .filter { it !in allVideoUrls && it !in allImageUrls && it !in ytUrls },
            textContent    = LINK_URL_REGEX.replace(afterImages, "").trim(),
        )
    }
    val imageUrls      = mediaExtraction.imageUrls
    val videoUrls      = mediaExtraction.videoUrls
    val youtubeEmbeds  = mediaExtraction.youtubeEmbeds
    val linkUrls       = mediaExtraction.linkUrls
    val textContent    = mediaExtraction.textContent

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = flashAlpha.value * 0.05f))
            .clickable { onNoteClick(navigateId) },
    ) {

        // ── Boost header (kind 6 only) ─────────────────────────────────────────
        if (row.kind == 6) {
            val reposterLabel = row.displayName ?: "${row.pubkey.take(6)}…${row.pubkey.takeLast(4)}"
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable { onNoteClick(navigateId) }
                    .padding(horizontal = Spacing.medium)
                    .padding(top = Spacing.micro),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Repeat,
                    contentDescription = null,
                    tint               = TextSecondary,
                    modifier           = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(Spacing.micro))
                AvatarImage(
                    pubkey   = row.pubkey,
                    picture  = row.authorPicture,
                    modifier = Modifier.size(16.dp),
                    sizeDp   = 16.dp,
                )
                Spacer(Modifier.width(Spacing.micro))
                Text(
                    text     = "$reposterLabel boosted · ${relativeTime(row.createdAt)}",
                    color    = TextSecondary,
                    fontSize = AppType.footnote,
                )
            }
        }

        // ── Header row: avatar + name + timestamp ─────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar + name are one clickable zone → opens author's profile.
            // Timestamp sits outside so it doesn't trigger profile navigation.
            Row(
                modifier          = Modifier
                    .weight(1f)
                    .clickable { onAuthorClick(effectivePubkey) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarImage(
                    pubkey   = effectivePubkey,
                    picture  = if (boostedJson == null && fetchedRepostEvent == null) row.authorPicture
                               else repostProfile?.picture,
                    modifier = Modifier.size(Sizing.avatar),
                )
                Spacer(Modifier.width(Spacing.small))
                Row(
                    modifier          = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text       = if (boostedJson != null || fetchedRepostEvent != null) {
                            repostProfile?.displayName?.takeIf { it.isNotBlank() }
                                ?: repostProfile?.name?.takeIf { it.isNotBlank() }
                                ?: "${effectivePubkey.take(6)}…${effectivePubkey.takeLast(4)}"
                        } else {
                            row.displayName ?: "${row.pubkey.take(6)}…${row.pubkey.takeLast(4)}"
                        },
                        color      = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = AppType.body,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f, fill = false),
                    )
                    val nip05 = if (boostedJson == null && fetchedRepostEvent == null) row.authorNip05
                               else repostProfile?.nip05
                    if (!nip05.isNullOrBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector        = Icons.Filled.Verified,
                            contentDescription = "NIP-05 verified",
                            tint               = Cyan,
                            modifier           = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text     = nip05Domain(nip05),
                            color    = TextSecondary,
                            fontSize = AppType.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(Spacing.micro))
            Text(
                text     = relativeTime(effectiveCreatedAt),
                color    = TextSecondary,
                fontSize = AppType.footnote,
                modifier = Modifier.clickable { onNoteClick(navigateId) },
            )
        }

        // ── Embedded parent card (Conversations tab threading) ────────────────
        if (parentEvent != null) {
            ThreadParentCard(
                event = parentEvent,
                author = parentAuthor,
                onNoteClick = onNoteClick,
                modifier = Modifier.padding(bottom = Spacing.small),
            )
        }

        // ── Full-width text content (collapse if > 300 chars) ─────────────────
        if (textContent.isNotBlank()) {
            val isLong = textContent.length > 300
            var expanded by remember { mutableStateOf(false) }

            NostrRichText(
                content       = textContent,
                lookupProfile = lookupProfile,
                onAuthorClick = onAuthorClick,
                onTextClick   = { onNoteClick(navigateId) },
                maxLines      = if (isLong && !expanded) 8 else Int.MAX_VALUE,
                overflow      = if (isLong && !expanded) TextOverflow.Ellipsis else TextOverflow.Clip,
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.micro),
            )
            if (isLong) {
                Text(
                    text     = if (expanded) "Show less" else "Show more",
                    color    = Cyan,
                    fontSize = AppType.bodySmall,
                    modifier = Modifier
                        .padding(horizontal = Spacing.medium)
                        .padding(bottom = Spacing.micro)
                        .clickable { expanded = !expanded },
                )
            }
        }

        // ── NIP-19 embedded quotes (show right after text, before media) ────
        nostrRefs.filterIsInstance<NostrRef.EventRef>().forEach { ref ->
            EmbeddedQuoteCard(
                eventId         = ref.eventId,
                onNoteClick     = onNoteClick,
                lookupEvent     = lookupEvent,
                lookupProfile   = lookupProfile,
                fetchOgMetadata = fetchOgMetadata,
                relayHints      = ref.relayHints,
                modifier        = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }

        // ── NIP-19 naddr references (kind + author + d-tag addressable events) ──
        nostrRefs.filterIsInstance<NostrRef.AddressRef>().forEach { ref ->
            EmbeddedAddressCard(
                addrRef       = ref,
                onNoteClick   = onNoteClick,
                lookupProfile = lookupProfile,
                modifier      = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }

        // ── BUG #4 FIX: Media grid for multiple images ──────────────────────
        if (imageUrls.isNotEmpty()) {
            MediaGrid(
                imageUrls  = imageUrls,
                imetaMedia = imetaMedia,
                onImageClick = { url -> fullscreenImageIndex = imageUrls.indexOf(url).coerceAtLeast(0) },
                modifier   = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }

        // ── Video grid: same layout pattern as images ────────────────────
        if (videoUrls.isNotEmpty()) {
            VideoGrid(
                videoUrls         = videoUrls,
                imetaMedia        = imetaMedia,
                videoRenderModels = videoRenderModels,
                isActiveVideo     = isActiveVideo,
                isFullscreen      = isFullscreen,
                onOpenFullscreen  = onOpenFullscreen,
                exoPlayer         = exoPlayer,
                isMuted           = isMuted,
                onToggleMute      = onToggleMute,
                thumbnailCache    = thumbnailCache,
                modifier          = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }

        // ── YouTube embed cards ──────────────────────────────────────────────────
        youtubeEmbeds.forEach { yt ->
            YouTubeThumbnailCard(
                youtubeUrl  = yt.url,
                videoId     = yt.videoId,
                modifier    = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
        }

        // ── Link preview / chips for non-media URLs ─────────────────────────
        if (linkUrls.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            ) {
                // First link gets an OG preview card; rest stay as simple chips
                LinkPreviewCard(
                    url            = linkUrls.first(),
                    fetchOgMetadata = fetchOgMetadata,
                )
                linkUrls.drop(1).forEach { url -> LinkChip(url = url) }
            }
        }

        // ── Full-width action bar ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNoteClick(navigateId) }
                .padding(bottom = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ActionButton(
                    icon               = Icons.AutoMirrored.Filled.Chat,
                    count              = row.replyCount,
                    contentDescription = "Replies",
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box {
                    ActionButton(
                        icon               = Icons.Filled.Repeat,
                        count              = row.repostCount,
                        contentDescription = "Reposts",
                        highlighted        = hasReposted,
                        onClick            = { showRepostMenu = true },
                    )
                    DropdownMenu(
                        expanded         = showRepostMenu,
                        onDismissRequest = { showRepostMenu = false },
                        modifier         = Modifier.background(Black),
                    ) {
                        DropdownMenuItem(
                            text    = { Text("Boost", color = Color.White, fontSize = AppType.body) },
                            onClick = { onRepost(); showRepostMenu = false; showSnackbar("Boosted") },
                        )
                        DropdownMenuItem(
                            text    = { Text("Quote", color = Color.White, fontSize = AppType.body) },
                            onClick = { onQuote(row.id); showRepostMenu = false },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ActionButton(
                    icon               = Icons.Filled.Favorite,
                    count              = row.reactionCount,
                    contentDescription = "Reactions",
                    highlighted        = hasReacted,
                    onClick            = onReact,
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ZapButton(
                    sats          = row.zapTotalSats + extraZapSats,
                    hasZapped     = hasZapped,
                    isLoading     = isZapLoading,
                    flashTrigger  = zapFlashTrigger,
                    onTap         = {
                        if (isNwcConfigured) onZap(21L) else showConnectWallet = true
                    },
                    onLongPress   = {
                        if (isNwcConfigured) showZapPicker = true else showConnectWallet = true
                    },
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ActionButton(
                    icon               = Icons.Filled.Share,
                    count              = 0,
                    contentDescription = "Share",
                    onClick            = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_TEXT, "https://njump.me/${row.id}")
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, null))
                    },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
    }

    if (showConnectWallet) {
        ConnectWalletDialog(
            onConnect = { uri ->
                onSaveNwcUri(uri)
                showConnectWallet = false
            },
            onDismiss = { showConnectWallet = false },
        )
    }

    if (showZapPicker) {
        ZapAmountDialog(
            onZap = { amount ->
                onZap(amount)
                showZapPicker = false
            },
            onDismiss = { showZapPicker = false },
        )
    }

    // ── Fullscreen image viewer ────────────────────────────────────────────
    if (fullscreenImageIndex >= 0 && imageUrls.isNotEmpty()) {
        FullScreenImageDialog(
            imageUrls    = imageUrls,
            initialIndex = fullscreenImageIndex,
            onDismiss    = { fullscreenImageIndex = -1 },
        )
    }
}

// ── Sub-composables ────────────────────────────────────────────────────────────

/** Animated gradient placeholder shown while an image is loading. */
@Composable
private fun ShimmerBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmer-progress",
    )
    Box(modifier = modifier
        .height(200.dp)
        .background(lerp(Surface1, Surface2, progress))
    )
}

/**
 * IdentIcon always renders as the background/fallback.
 * AsyncImage overlays on top when a picture URL is available.
 * If the network load fails, the IdentIcon underneath remains visible.
 */
@Composable
internal fun AvatarImage(
    pubkey: String,
    picture: String?,
    modifier: Modifier = Modifier,
    sizeDp: Dp = Sizing.avatar,
) {
    Box(modifier = modifier.clip(CircleShape)) {
        IdentIcon(pubkey = pubkey, modifier = Modifier.fillMaxSize())
        if (!picture.isNullOrBlank()) {
            AsyncImage(
                model              = rememberAvatarImageRequest(picture, sizeDp),
                contentDescription = null,
                modifier           = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Single action bar button: vector icon + optional count. Turns Cyan when [highlighted]. */
@Composable
internal fun ActionButton(
    icon: ImageVector,
    count: Int,
    contentDescription: String,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val tint = if (highlighted) Cyan else ActionTint
    val rowModifier = if (onClick != null)
        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clickable(onClick = onClick)
    else
        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)

    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier              = rowModifier,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDescription,
            tint               = tint,
            modifier           = Modifier.size(Sizing.actionIcon),
        )
        if (count > 0) {
            Spacer(Modifier.width(Spacing.micro))
            Text(
                text     = formatCount(count),
                color    = tint,
                fontSize = AppType.footnote,
            )
        }
    }
}

/** Zap button: Amber when user has zapped, loading spinner during payment, energy pulse on success. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ZapButton(
    sats: Long,
    hasZapped: Boolean,
    isLoading: Boolean = false,
    flashTrigger: Int = 0,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    // ── Animation: punchy bounce + white→amber flash ──────────────────────
    val iconScale = remember { Animatable(1f) }
    val flashAlpha = remember { Animatable(0f) }

    LaunchedEffect(flashTrigger) {
        if (flashTrigger > 0) {
            coroutineScope {
                launch {
                    iconScale.snapTo(1.8f)
                    iconScale.animateTo(
                        1f,
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                    )
                }
                launch {
                    flashAlpha.snapTo(1f)
                    flashAlpha.animateTo(0f, tween(400))
                }
            }
        }
    }

    val baseTint = if (hasZapped) ZapAmber else ActionTint
    val tint = if (flashAlpha.value > 0f) {
        lerp(baseTint, Color.White, flashAlpha.value)
    } else baseTint

    Box(contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier              = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .combinedClickable(
                    onClick     = onTap,
                    onLongClick = onLongPress,
                ),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color       = Cyan,
                    modifier    = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector        = Icons.Filled.ElectricBolt,
                    contentDescription = "Zap",
                    tint               = tint,
                    modifier           = Modifier
                        .size(Sizing.actionIcon)
                        .graphicsLayer {
                            scaleX = iconScale.value
                            scaleY = iconScale.value
                        },
                )
            }
            if (sats > 0) {
                Spacer(Modifier.width(Spacing.micro))
                Text(
                    text     = sats.toCompactSats(),
                    color    = tint,
                    fontSize = AppType.footnote,
                )
            }
        }
    }
}

// ── BUG #4 FIX: Media grid composable ─────────────────────────────────────────

/** Single image cell — always constrained by aspect ratio (4:3 fallback, updated on load). */
@Composable
private fun MediaImage(
    url: String,
    imetaMedia: List<ImetaMedia>,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    forceSquare: Boolean = false,
) {
    val imetaAspect = imetaMedia
        .firstOrNull { it.url == url && it.width != null && it.height != null }
        ?.let { it.width!!.toFloat() / it.height!! }

    var displayAspect by remember(url, forceSquare) {
        mutableStateOf(feedImageAspectRatio(imetaAspect, forceSquare))
    }

    val imageModifier = modifier
        .fillMaxWidth()
        .aspectRatio(displayAspect, matchHeightConstraintsFirst = false)
        .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
        .background(MediaPlaceholder)
        .clickable { onImageClick(url) }

    SubcomposeAsyncImage(
        model              = rememberFullWidthImageRequest(url, aspectRatio = displayAspect),
        contentDescription = null,
        loading            = { ShimmerBox(modifier = Modifier.fillMaxSize()) },
        error              = {
            Box(
                modifier         = Modifier.fillMaxSize().background(MediaPlaceholder),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.BrokenImage,
                    contentDescription = "Image failed to load",
                    tint               = TextSecondary,
                    modifier           = Modifier.size(32.dp),
                )
            }
        },
        success = {
            // Coil 3: read painter intrinsic size to update container aspect ratio
            val size = painter.intrinsicSize
            if (!forceSquare && size.width > 0f && size.height > 0f) {
                val trueAspect = feedImageAspectRatio(size.width / size.height, false)
                LaunchedEffect(trueAspect) { displayAspect = trueAspect }
            }
            Image(
                painter            = painter,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        },
        modifier = imageModifier,
    )
}

/** Renders images in a grid: 1=full, 2=side-by-side, 3=1+2, 4=2x2, 5+=2x2 with +N overlay. */
@Composable
private fun MediaGrid(
    imageUrls: List<String>,
    imetaMedia: List<ImetaMedia>,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = imageUrls.size
    when {
        count == 1 -> {
            MediaImage(
                url = imageUrls[0],
                imetaMedia = imetaMedia,
                onImageClick = onImageClick,
                modifier = modifier,
            )
        }
        count == 2 -> {
            Row(
                modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MediaImage(
                    url = imageUrls[0],
                    imetaMedia = imetaMedia,
                    onImageClick = onImageClick,
                    modifier = Modifier.weight(1f),
                    forceSquare = true,
                )
                MediaImage(
                    url = imageUrls[1],
                    imetaMedia = imetaMedia,
                    onImageClick = onImageClick,
                    modifier = Modifier.weight(1f),
                    forceSquare = true,
                )
            }
        }
        count == 3 -> {
            Column(
                modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MediaImage(
                    url = imageUrls[0],
                    imetaMedia = imetaMedia,
                    onImageClick = onImageClick,
                    modifier = Modifier.fillMaxWidth(),
                    forceSquare = false,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MediaImage(
                        url = imageUrls[1],
                        imetaMedia = imetaMedia,
                        onImageClick = onImageClick,
                        modifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                    MediaImage(
                        url = imageUrls[2],
                        imetaMedia = imetaMedia,
                        onImageClick = onImageClick,
                        modifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                }
            }
        }
        else -> {
            // 4+ images: 2x2 grid
            val gridImages = imageUrls.take(4)
            val overflow = count - 4
            Column(
                modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MediaImage(
                        url = gridImages[0],
                        imetaMedia = imetaMedia,
                        onImageClick = onImageClick,
                        modifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                    MediaImage(
                        url = gridImages[1],
                        imetaMedia = imetaMedia,
                        onImageClick = onImageClick,
                        modifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MediaImage(
                        url = gridImages[2],
                        imetaMedia = imetaMedia,
                        onImageClick = onImageClick,
                        modifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                    // 4th image with optional +N overlay
                    Box(modifier = Modifier.weight(1f)) {
                        MediaImage(
                            url = gridImages[3],
                            imetaMedia = imetaMedia,
                            onImageClick = onImageClick,
                            modifier = Modifier.fillMaxWidth(),
                            forceSquare = true,
                        )
                        if (overflow > 0) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .clickable { onImageClick(imageUrls[4]) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text     = "+$overflow",
                                    color    = Color.White,
                                    fontSize = AppType.display,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Video grid composable ────────────────────────────────────────────────

/** Helper to resolve video aspect ratio and poster from imeta tags (fallback for non-modeled URLs). */
private data class VideoMeta(val aspectRatio: Float?, val posterUrl: String?)

private fun resolveVideoMeta(url: String, imetaMedia: List<ImetaMedia>): VideoMeta {
    val meta = imetaMedia.firstOrNull { it.url == url && it.width != null && it.height != null }
    val aspect = meta?.let { it.width!!.toFloat() / it.height!! }
    val poster = imetaMedia.firstOrNull { it.url == url }?.thumb
    return VideoMeta(aspect, poster)
}

/** Single video cell for the grid — thumbnail with play button. */
@Composable
private fun VideoGridCell(
    url: String,
    imetaMedia: List<ImetaMedia>,
    videoRenderModels: List<VideoRenderModel>,
    thumbnailCache: VideoThumbnailCache? = null,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    forceSquare: Boolean = false,
) {
    // Use pre-computed VideoRenderModel if available, otherwise fall back to imeta parse
    val model = videoRenderModels.firstOrNull { it.videoUrl == url }
    if (model != null) {
        VideoThumbnailCard(
            url            = url,
            onPlay         = onPlay,
            aspectRatio    = model.aspectRatio,
            posterUrl      = model.posterUrl,
            thumbnailCache = thumbnailCache,
            forceSquare    = forceSquare,
            modifier       = modifier,
        )
    } else {
        val (aspectRatio, posterUrl) = resolveVideoMeta(url, imetaMedia)
        VideoThumbnailCard(
            url            = url,
            onPlay         = onPlay,
            aspectRatio    = aspectRatio,
            posterUrl      = posterUrl,
            thumbnailCache = thumbnailCache,
            forceSquare    = forceSquare,
            modifier       = modifier,
        )
    }
}

/**
 * Renders videos in a grid: 1=full (autoplay), 2=side-by-side, 3=1+2, 4+=2x2 with +N overlay.
 *
 * The first video is the "primary" cell: when this card is the active video,
 * it renders [InlineVideoPlayer] (one AndroidView); otherwise it renders
 * [VideoPreviewCard] (pure Compose, no SurfaceView). All secondary cells
 * are always static thumbnails.
 */
@Composable
private fun VideoGrid(
    videoUrls: List<String>,
    imetaMedia: List<ImetaMedia>,
    videoRenderModels: List<VideoRenderModel>,
    isActiveVideo: Boolean,
    isFullscreen: Boolean,
    onOpenFullscreen: () -> Unit,
    exoPlayer: ExoPlayer?,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    thumbnailCache: VideoThumbnailCache? = null,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val count = videoUrls.size

    fun openVideo(url: String) {
        if (isDirectVideoUrl(url)) onOpenFullscreen()
        else runCatching { uriHandler.openUri(url) }
    }

    /**
     * Primary video cell: active → InlineVideoPlayer (one AndroidView),
     * inactive → VideoPreviewCard (pure Compose, zero SurfaceView).
     * When fullscreen is open, inline player detaches its surface so
     * fullscreen dialog has exclusive surface ownership.
     */
    @Composable
    fun PrimaryVideoCell(url: String, cellModifier: Modifier = Modifier, forceSquare: Boolean = false) {
        val model = videoRenderModels.firstOrNull { it.videoUrl == url }
        if (model != null && isDirectVideoUrl(url)) {
            if (isActiveVideo && exoPlayer != null) {
                InlineVideoPlayer(
                    model            = model,
                    exoPlayer        = exoPlayer,
                    isMuted          = isMuted,
                    onToggleMute     = onToggleMute,
                    onOpenFullscreen = onOpenFullscreen,
                    forceSquare      = forceSquare,
                    thumbnailCache   = thumbnailCache,
                    isFullscreen     = isFullscreen,
                    modifier         = cellModifier,
                )
            } else {
                VideoPreviewCard(
                    model            = model,
                    onOpenFullscreen = onOpenFullscreen,
                    forceSquare      = forceSquare,
                    thumbnailCache   = thumbnailCache,
                    modifier         = cellModifier,
                )
            }
        } else {
            // No VideoRenderModel or not a direct video URL — static thumbnail
            VideoGridCell(
                url              = url,
                imetaMedia       = imetaMedia,
                videoRenderModels = videoRenderModels,
                thumbnailCache   = thumbnailCache,
                onPlay           = { openVideo(url) },
                modifier         = cellModifier,
                forceSquare      = forceSquare,
            )
        }
    }

    when {
        count == 1 -> {
            PrimaryVideoCell(url = videoUrls[0], cellModifier = modifier)
        }
        count == 2 -> {
            Row(
                modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PrimaryVideoCell(
                    url = videoUrls[0],
                    cellModifier = Modifier.weight(1f),
                    forceSquare = true,
                )
                VideoGridCell(
                    url = videoUrls[1],
                    imetaMedia = imetaMedia,
                    videoRenderModels = videoRenderModels,
                    thumbnailCache = thumbnailCache,
                    onPlay = { openVideo(videoUrls[1]) },
                    modifier = Modifier.weight(1f),
                    forceSquare = true,
                )
            }
        }
        count == 3 -> {
            Column(
                modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PrimaryVideoCell(
                    url = videoUrls[0],
                    cellModifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    VideoGridCell(
                        url = videoUrls[1],
                        imetaMedia = imetaMedia,
                        videoRenderModels = videoRenderModels,
                        thumbnailCache = thumbnailCache,
                        onPlay = { openVideo(videoUrls[1]) },
                        modifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                    VideoGridCell(
                        url = videoUrls[2],
                        imetaMedia = imetaMedia,
                        videoRenderModels = videoRenderModels,
                        thumbnailCache = thumbnailCache,
                        onPlay = { openVideo(videoUrls[2]) },
                        modifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                }
            }
        }
        else -> {
            // 4+ videos: 2x2 grid with +N overlay on 4th
            val gridVideos = videoUrls.take(4)
            val overflow = count - 4
            Column(
                modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PrimaryVideoCell(
                        url = gridVideos[0],
                        cellModifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                    VideoGridCell(
                        url = gridVideos[1],
                        imetaMedia = imetaMedia,
                        videoRenderModels = videoRenderModels,
                        thumbnailCache = thumbnailCache,
                        onPlay = { openVideo(gridVideos[1]) },
                        modifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    VideoGridCell(
                        url = gridVideos[2],
                        imetaMedia = imetaMedia,
                        videoRenderModels = videoRenderModels,
                        thumbnailCache = thumbnailCache,
                        onPlay = { openVideo(gridVideos[2]) },
                        modifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        VideoGridCell(
                            url = gridVideos[3],
                            imetaMedia = imetaMedia,
                            videoRenderModels = videoRenderModels,
                            thumbnailCache = thumbnailCache,
                            onPlay = { openVideo(gridVideos[3]) },
                            modifier = Modifier.fillMaxWidth(),
                            forceSquare = true,
                        )
                        if (overflow > 0) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .clickable { openVideo(videoUrls[4]) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text       = "+$overflow",
                                    color      = Color.White,
                                    fontSize   = AppType.display,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── BUG #5 FIX: Portrait-aware video thumbnail ───────────────────────────────

/** Tap-to-play placeholder shown for detected video URLs. */
@Composable
private fun VideoThumbnailCard(
    url: String,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    aspectRatio: Float? = null,
    posterUrl: String? = null,
    thumbnailCache: VideoThumbnailCache? = null,
    forceSquare: Boolean = false,
) {
    val baseAspect = feedVideoAspectRatio(aspectRatio, forceSquare)
    var displayAspect by remember(url, forceSquare) { mutableStateOf(baseAspect) }

    Box(
        modifier          = modifier
            .fillMaxWidth()
            .aspectRatio(displayAspect, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(MediaPlaceholder)
            .clickable { onPlay() },
        contentAlignment  = Alignment.Center,
    ) {
        if (!posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = rememberFullWidthImageRequest(posterUrl, aspectRatio = displayAspect),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        } else if (thumbnailCache != null) {
            // No imeta poster — extract first frame via MediaMetadataRetriever (HTTP range requests)
            var thumbnail by remember(url) { mutableStateOf<VideoThumbnail?>(null) }
            LaunchedEffect(url) {
                thumbnailCache.getThumbnail(url)?.let {
                    thumbnail = it
                    if (!forceSquare) displayAspect = feedVideoAspectRatio(it.aspectRatio, false)
                }
            }
            if (thumbnail != null) {
                androidx.compose.foundation.Image(
                    bitmap = thumbnail!!.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.matchParentSize(),
                )
            }
            // While loading (thumbnail == null): dark placeholder shows through from parent Box
        }
        Icon(
            imageVector        = Icons.Filled.PlayArrow,
            contentDescription = "Play video",
            tint               = Color.White.copy(alpha = 0.85f),
            modifier           = Modifier.size(52.dp),
        )
    }
}

/** YouTube thumbnail card — shows hqdefault thumbnail with a play button overlay. */
@Composable
private fun YouTubeThumbnailCard(
    youtubeUrl: String,
    videoId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier          = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(MediaPlaceholder)
            .clickable {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(youtubeUrl),
                )
                runCatching { context.startActivity(intent) }
            },
        contentAlignment  = Alignment.Center,
    ) {
        AsyncImage(
            model              = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.matchParentSize(),
        )
    }
}

/** Full-screen video dialog reusing the shared ExoPlayer. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun FullScreenVideoDialog(
    exoPlayer: ExoPlayer,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Belt-and-suspenders: explicitly detach player when dialog leaves composition
            var dialogView by remember { mutableStateOf<PlayerView?>(null) }
            DisposableEffect(Unit) {
                onDispose { dialogView?.player = null }
            }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }.also { dialogView = it }
                },
                update = { view -> view.player = exoPlayer },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick  = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector        = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint               = Color.White,
                )
            }
        }
    }
}

/** Full-screen image viewer dialog. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FullScreenImageDialog(
    imageUrls: List<String>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        ) {
            val pagerState = rememberPagerState(
                initialPage = initialIndex,
                pageCount   = { imageUrls.size },
            )

            HorizontalPager(
                state    = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                SubcomposeAsyncImage(
                    model              = imageUrls[page],
                    contentDescription = null,
                    contentScale       = ContentScale.Fit,
                    loading = {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { /* consume click so background dismiss doesn't fire */ },
                )
            }

            // Dot indicators — only for multi-image posts
            if (imageUrls.size > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(imageUrls.size) { index ->
                        Box(
                            modifier = Modifier
                                .size(if (pagerState.currentPage == index) 8.dp else 6.dp)
                                .background(
                                    color = if (pagerState.currentPage == index) Color.White else Color.White.copy(alpha = 0.4f),
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }

            // Close button
            IconButton(
                onClick  = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector        = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint               = Color.White,
                )
            }
        }
    }
}

// ── BUG #3 FIX: Rich embedded quote card ────────────────────────────────────

/**
 * Tappable inline card for a quoted nostr event. Shows actual content when available.
 * [nestDepth] prevents infinite recursion: 0 = top-level quote, 1 = nested, 2+ = stop.
 */
@Composable
private fun EmbeddedQuoteCard(
    eventId: String,
    onNoteClick: (String) -> Unit,
    lookupEvent: (suspend (String, List<String>) -> EventEntity?)? = null,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)? = null,
    relayHints: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    nestDepth: Int = 0,
) {
    // Try to load the quoted event (Room first, then relay fetch via lookupEvent)
    val event by produceState<EventEntity?>(null, eventId) {
        if (lookupEvent != null) value = lookupEvent(eventId, relayHints)
    }
    val author by produceState<UserEntity?>(null, event?.pubkey) {
        val pk = event?.pubkey
        if (pk != null && lookupProfile != null) value = lookupProfile(pk)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable { onNoteClick(eventId) }
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        val loadedEvent = event
        if (loadedEvent != null) {
            // Rich quote: show author + truncated content
            Column {
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
                            ?: author?.name?.takeIf { it.isNotBlank() }
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

                // Extract media from quoted content: imeta tags + regex fallback
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
                    val regexImages = IMAGE_URL_REGEX.findAll(rawContent).map { it.value }.distinct().toList()
                    (imetaImageUrls + regexImages).distinct().map(::rewriteBskyUrl)
                }
                val quotedVideos = remember(rawContent, imetaVideoUrls) {
                    val regexVideos = VIDEO_URL_REGEX.findAll(rawContent).map { it.value }.distinct().toList()
                    (imetaVideoUrls + regexVideos).distinct().filter { it !in quotedImages }
                }

                // Strip nostr URIs and media URLs for cleaner text (keep link URLs)
                val cleanContent = remember(rawContent) {
                    rawContent
                        .let { NOSTR_URI_REGEX.replace(it, "") }
                        .let { IMAGE_URL_REGEX.replace(it, "") }
                        .let { VIDEO_URL_REGEX.replace(it, "") }
                        .trim()
                }
                // Extract link URLs for OG preview
                val linkUrls = remember(rawContent) {
                    LINK_URL_REGEX.findAll(rawContent).map { it.value }
                        .filter { url -> url !in quotedImages && url !in quotedVideos }
                        .distinct().toList()
                }
                val displayText = remember(cleanContent) {
                    LINK_URL_REGEX.replace(cleanContent, "").trim()
                }
                if (displayText.isNotBlank()) {
                    Text(
                        text     = displayText,
                        color    = Color.White.copy(alpha = 0.85f),
                        fontSize = AppType.body,
                        lineHeight = 20.sp,
                    )
                }

                // All images (grid for 2+, single full-width for 1)
                if (quotedImages.isNotEmpty()) {
                    val quoteDensity = LocalDensity.current
                    val quoteConfig = LocalConfiguration.current
                    val quoteWidthPx = with(quoteDensity) { quoteConfig.screenWidthDp.dp.roundToPx() }
                    val quoteMaxHeightPx = with(quoteDensity) { 300.dp.roundToPx() }
                    val quoteCellWidthPx = with(quoteDensity) { (quoteConfig.screenWidthDp.dp / 2).roundToPx() }
                    val quoteCellHeightPx = with(quoteDensity) { 150.dp.roundToPx() }

                    Spacer(Modifier.height(6.dp))
                    if (quotedImages.size == 1) {
                        AsyncImage(
                            model              = rememberSizedImageRequest(quotedImages.first(), quoteWidthPx, quoteMaxHeightPx),
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        // 2x2 grid for multiple images
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
                    LinkPreviewCard(
                        url             = linkUrls.first(),
                        fetchOgMetadata = fetchOgMetadata,
                    )
                }

                // Nested quote: render one level of quotes inside this quoted post
                if (nestDepth < 1) {
                    val nestedEventRefs = remember(loadedEvent.content) {
                        NOSTR_URI_REGEX.findAll(loadedEvent.content)
                            .mapNotNull { decodeNostrRef(it.value) }
                            .filterIsInstance<NostrRef.EventRef>()
                            .toList()
                    }
                    nestedEventRefs.forEach { ref ->
                        Spacer(Modifier.height(4.dp))
                        EmbeddedQuoteCard(
                            eventId         = ref.eventId,
                            onNoteClick     = onNoteClick,
                            lookupEvent     = lookupEvent,
                            lookupProfile   = lookupProfile,
                            fetchOgMetadata = fetchOgMetadata,
                            relayHints      = ref.relayHints,
                            nestDepth       = nestDepth + 1,
                        )
                    }
                }
            }
        } else {
            // Fallback: event unavailable (e.g. bridge post never propagated)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.Filled.FormatQuote,
                    contentDescription = null,
                    tint               = TextSecondary,
                    modifier           = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text     = "Quoted post unavailable",
                    color    = TextSecondary,
                    fontSize = AppType.bodySmall,
                )
            }
        }
    }
}

// ── Embedded naddr card ──────────────────────────────────────────────────────

/** Tappable inline card for an addressable event (kind + pubkey + d-tag). */
@Composable
private fun EmbeddedAddressCard(
    addrRef: NostrRef.AddressRef,
    onNoteClick: (String) -> Unit,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    modifier: Modifier = Modifier,
) {
    val author by produceState<UserEntity?>(null, addrRef.author) {
        if (lookupProfile != null) value = lookupProfile(addrRef.author)
    }

    val kindLabel = when (addrRef.kind) {
        30023 -> "Article"
        30024 -> "Draft"
        30078 -> "App Data"
        30311 -> "Live Event"
        else  -> "Event (kind ${addrRef.kind})"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImage(
                    pubkey   = addrRef.author,
                    picture  = author?.picture,
                    modifier = Modifier.size(24.dp),
                    sizeDp   = 24.dp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text     = author?.displayName?.takeIf { it.isNotBlank() }
                        ?: author?.name?.takeIf { it.isNotBlank() }
                        ?: "${addrRef.author.take(6)}…${addrRef.author.takeLast(4)}",
                    color    = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = AppType.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text     = kindLabel,
                color    = Cyan,
                fontSize = AppType.footnote,
                fontWeight = FontWeight.Medium,
            )
            if (addrRef.dTag.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text     = addrRef.dTag.replace("-", " "),
                    color    = Color.White.copy(alpha = 0.7f),
                    fontSize = AppType.body,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Inline @mention rendering ────────────────────────────────────────────────

/** Renders note text with nostr profile references as inline @displayName mentions. */
@Composable
internal fun NostrRichText(
    content: String,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    onAuthorClick: (String) -> Unit,
    onTextClick: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    // Parse mention positions and resolved pubkeys (stable for same content)
    val mentions = remember(content) {
        NOSTR_PROFILE_URI_REGEX.findAll(content).mapNotNull { m ->
            val ref = decodeNostrRef(m.value)
            if (ref is NostrRef.ProfileRef) Triple(m.range, m.value, ref.pubkeyHex) else null
        }.toList()
    }

    // No mentions — plain Text with click handler
    if (mentions.isEmpty()) {
        Text(
            text       = content,
            color      = MaterialTheme.colorScheme.onSurface,
            fontSize   = AppType.bodyLarge,
            lineHeight = 22.sp,
            maxLines   = maxLines,
            overflow   = overflow,
            textAlign  = textAlign,
            modifier   = modifier.clickable { onTextClick() },
        )
        return
    }

    // Resolve display names reactively
    var profileMap by remember(content) {
        mutableStateOf(emptyMap<String, UserEntity?>())
    }
    LaunchedEffect(mentions) {
        if (lookupProfile != null) {
            profileMap = mentions.associate { (_, _, hex) -> hex to lookupProfile(hex) }
        }
    }

    val annotatedText = buildAnnotatedString {
        var lastIndex = 0
        for ((range, raw, pubkeyHex) in mentions) {
            // Text before this mention
            if (range.first > lastIndex) {
                append(content.substring(lastIndex, range.first))
            }

            val profile = profileMap[pubkeyHex]
            val npubFallback = runCatching { NPub.create(pubkeyHex).take(16) + "…" }
                .getOrDefault("${pubkeyHex.take(8)}…")
            val displayName = profile?.displayName?.takeIf { it.isNotBlank() }
                ?: profile?.name?.takeIf { it.isNotBlank() }
                ?: npubFallback

            withLink(
                LinkAnnotation.Clickable(
                    tag = pubkeyHex,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color          = Cyan,
                            fontWeight     = FontWeight.Medium,
                            textDecoration = TextDecoration.None,
                        ),
                    ),
                    linkInteractionListener = { onAuthorClick(pubkeyHex) },
                ),
            ) {
                append("@$displayName")
            }

            lastIndex = range.last + 1
        }
        // Remaining text after last mention
        if (lastIndex < content.length) {
            append(content.substring(lastIndex))
        }
    }

    Text(
        text       = annotatedText,
        color      = MaterialTheme.colorScheme.onSurface,
        fontSize   = AppType.bodyLarge,
        lineHeight = 22.sp,
        maxLines   = maxLines,
        overflow   = overflow,
        textAlign  = textAlign,
        modifier   = modifier.clickable { onTextClick() },
    )
}

// ── BUG #2 FIX: Mention chip with display name lookup ───────────────────────

/** Inline mention chip for a nostr pubkey. Shows display name when available. */
@Composable
private fun MentionChip(
    pubkeyHex: String,
    onAuthorClick: (String) -> Unit,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
) {
    val profile by produceState<UserEntity?>(null, pubkeyHex) {
        if (lookupProfile != null) value = lookupProfile(pubkeyHex)
    }

    val npubFallback = remember(pubkeyHex) {
        val npub = runCatching { NPub.Companion.create(pubkeyHex) }.getOrNull()
        if (npub != null) "@${npub.take(16)}…" else "@${pubkeyHex.take(8)}…"
    }
    val displayText = profile?.displayName?.takeIf { it.isNotBlank() }
        ?: profile?.name?.takeIf { it.isNotBlank() }
        ?: npubFallback

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Surface1)
            .border(1.dp, Color(0xFF1A1A1A), RoundedCornerShape(20.dp))
            .clickable { onAuthorClick(pubkeyHex) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text     = if (displayText.startsWith("@")) displayText else "@$displayText",
            color    = Cyan,
            fontSize = AppType.footnote,
        )
    }
}

/** OpenGraph link preview card. Falls back to a simple domain chip if OG fetch fails. */
@Composable
private fun LinkPreviewCard(
    url: String,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val domain = remember(url) {
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
    }

    val og by produceState<OgMetadata?>(null, url) {
        if (fetchOgMetadata != null) value = fetchOgMetadata(url)
    }

    val loadedOg = og
    if (loadedOg != null && (loadedOg.title != null || loadedOg.imageUrl != null)) {
        // Rich preview card — image on top, text below (standard OG card layout)
        var imageLoadFailed by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
                .background(SurfaceVariant)
                .border(0.5.dp, Color(0xFF1A1A1A), RoundedCornerShape(Sizing.mediaCornerRadius))
                .clickable { runCatching { uriHandler.openUri(url) } },
        ) {
            if (!loadedOg.imageUrl.isNullOrBlank() && !imageLoadFailed) {
                val density = LocalDensity.current
                val config = LocalConfiguration.current
                val widthPx = with(density) { config.screenWidthDp.dp.roundToPx() }
                val heightPx = (widthPx * 3) / 4
                SubcomposeAsyncImage(
                    model              = rememberSizedImageRequest(loadedOg.imageUrl, widthPx, heightPx),
                    contentDescription = null,
                    contentScale       = ContentScale.Fit,
                    loading            = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f)
                                .background(MediaPlaceholder),
                        )
                    },
                    error              = { imageLoadFailed = true },
                    modifier           = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(
                            topStart = Sizing.mediaCornerRadius,
                            topEnd = Sizing.mediaCornerRadius,
                        ))
                        .background(MediaPlaceholder),
                )
            }
            Column(modifier = Modifier.padding(Spacing.small)) {
                if (!loadedOg.title.isNullOrBlank()) {
                    Text(
                        text       = loadedOg.title,
                        color      = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = AppType.bodySmall,
                        lineHeight = 17.sp,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                    )
                }
                if (!loadedOg.description.isNullOrBlank()) {
                    Text(
                        text     = loadedOg.description,
                        color    = TextSecondary,
                        fontSize = AppType.footnote,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text     = loadedOg.siteName ?: domain,
                    color    = TextSecondary,
                    fontSize = AppType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    } else {
        // Fallback: simple domain chip (also shown while loading)
        LinkChip(url = url)
    }
}

/** Clickable URL chip shown for non-media links in note content. */
@Composable
private fun LinkChip(url: String) {
    val uriHandler = LocalUriHandler.current
    val domain     = remember(url) {
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text     = domain,
            color    = Cyan,
            fontSize = AppType.footnote,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

internal val FeedRow.displayName: String?
    get() = authorDisplayName?.takeIf { it.isNotBlank() }
         ?: authorName?.takeIf { it.isNotBlank() }

internal val FeedRow.engagementId: String
    get() = if (kind == 6 && rootId != null) rootId!! else id

/**
 * Parse a NIP-05 address into (name, domain).
 * Handles: "name@domain.com", "_@domain.com", "domain.com" (bare domain → _@domain.com).
 */
internal fun parseNip05(nip05: String): Pair<String, String>? {
    val trimmed = nip05.trim()
    if (trimmed.isBlank()) return null
    val parts = trimmed.split("@")
    return when {
        parts.size == 2 && parts[1].contains(".") -> parts[0] to parts[1]
        parts.size == 1 && trimmed.contains(".")  -> "_" to trimmed
        else -> null
    }
}

/** "user@domain.com" → "domain.com"; "_@domain.com" → "domain.com"; "domain.com" → "domain.com". */
internal fun nip05Domain(nip05: String): String = parseNip05(nip05)?.second ?: nip05

internal fun relativeTime(createdAtSeconds: Long): String {
    val diffMs = System.currentTimeMillis() - createdAtSeconds * 1000L
    return when {
        diffMs < TimeUnit.MINUTES.toMillis(1) -> "now"
        diffMs < TimeUnit.HOURS.toMillis(1)   -> "${TimeUnit.MILLISECONDS.toMinutes(diffMs)}m"
        diffMs < TimeUnit.DAYS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toHours(diffMs)}h"
        diffMs < TimeUnit.DAYS.toMillis(7)    -> "${TimeUnit.MILLISECONDS.toDays(diffMs)}d"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(createdAtSeconds * 1000L))
    }
}

internal fun formatCount(n: Int): String = when {
    n < 1_000  -> "$n"
    n < 10_000 -> "%.1fk".format(n / 1_000f)
    else        -> "${n / 1_000}k"
}

/** Format sats compactly: 21000 → "21k", 1500000 → "1.5M" */
internal fun Long.toCompactSats(): String = when {
    this >= 1_000_000 -> {
        val s = "%.1fM".format(this / 1_000_000.0)
        if (s.endsWith(".0M")) s.dropLast(3) + "M" else s
    }
    this >= 1_000 -> {
        val s = "%.1fk".format(this / 1_000.0)
        if (s.endsWith(".0k")) s.dropLast(3) + "k" else s
    }
    else -> this.toString()
}
