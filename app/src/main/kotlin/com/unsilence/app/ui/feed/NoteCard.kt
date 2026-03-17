package com.unsilence.app.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.graphics.lerp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.ImetaParser
import com.unsilence.app.data.relay.ImetaMedia
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.data.relay.extractRepostAuthorPubkey
import com.unsilence.app.data.relay.extractRepostTargetId
import com.unsilence.app.ui.common.IdentIcon
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.ZapAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

internal val ActionTint = Color(0xFF555555)
private val MediaPlaceholder = Color(0xFF1A1A1A)

// Matches URLs ending in image extensions, or from known Nostr image hosts.
private val IMAGE_URL_REGEX = Regex(
    """https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?|https?://(?:image\.nostr\.build|i\.nostr\.build|nostr\.build|blossom\.primal\.net)/\S+""",
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

// Matches any remaining http/https URL (applied after stripping image + video URLs).
private val LINK_URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

// Matches nostr: URIs (bech32-encoded entities).
private val NOSTR_URI_REGEX = Regex("nostr:[a-z0-9]+", RegexOption.IGNORE_CASE)

private sealed class NostrRef {
    data class EventRef(val eventId: String) : NostrRef()
    data class ProfileRef(val pubkeyHex: String) : NostrRef()
}

private fun decodeNostrRef(uri: String): NostrRef? = runCatching {
    when (val entity = Nip19Parser.uriToRoute(uri)?.entity) {
        is NEvent -> NostrRef.EventRef(entity.hex)
        is NNote  -> NostrRef.EventRef(entity.hex)
        is NPub   -> NostrRef.ProfileRef(entity.hex)
        else      -> null
    }
}.getOrNull()

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
private fun effectiveAspectRatio(raw: Float): Float =
    if (raw >= 1f) raw else maxOf(raw, 2f / 3f)

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
    onOpenFullscreen: () -> Unit = {},
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    lookupEvent: (suspend (String) -> EventEntity?)? = null,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)? = null,
    isNewPost: Boolean = false,
    onNewPostAnimated: () -> Unit = {},
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
    var fullscreenImageUrl by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

    // ── Kind 6 repost: parse embedded original event JSON ─────────────────────
    val boostedJson = if (row.kind == 6 && row.content.isNotBlank()) {
        runCatching { NostrJson.parseToJsonElement(row.content).jsonObject }.getOrNull()
    } else null

    val effectivePubkey = boostedJson?.get("pubkey")?.jsonPrimitive?.content
        ?: if (row.kind == 6) extractRepostAuthorPubkey(row.content, row.tags) ?: row.pubkey
        else row.pubkey
    val effectiveCreatedAt = boostedJson?.get("created_at")?.jsonPrimitive?.longOrNull ?: row.createdAt
    val effectiveContent   = boostedJson?.get("content")?.jsonPrimitive?.content ?: row.content

    // For kind-6 reposts, navigate to the referenced event, not the wrapper
    val navigateId = if (row.kind == 6) extractRepostTargetId(row.tags) ?: row.id else row.id

    // ── NIP-19 nostr: URI extraction (strip before other URL processing) ──────
    val nostrRefs = NOSTR_URI_REGEX.findAll(effectiveContent)
        .mapNotNull { decodeNostrRef(it.value) }
        .toList()
    val contentNoNostr = NOSTR_URI_REGEX.replace(effectiveContent, "").trim()

    // ── Media extraction: regex from content + imeta from tags ────────────────
    // Wrapped in remember to avoid re-parsing JSON on every recomposition.
    val imetaMedia = remember(row.tags) { ImetaParser.parse(row.tags) }
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
        val imetaImageUrls = imetaMedia.filter { it.mimeType?.startsWith("image/") == true }.map { it.url }
        val allImageUrls   = (regexImageUrls + imetaImageUrls).distinct()
                                 .filter { it !in allVideoUrls }

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
            .background(Color(0xFF1A1A1A).copy(alpha = flashAlpha.value))
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
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Repeat,
                    contentDescription = null,
                    tint               = TextSecondary,
                    modifier           = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                AvatarImage(
                    pubkey   = row.pubkey,
                    picture  = row.authorPicture,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text     = "$reposterLabel boosted",
                    color    = TextSecondary,
                    fontSize = 12.sp,
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
                    picture  = if (boostedJson == null) row.authorPicture else originalAuthorProfile?.picture,
                    modifier = Modifier.size(Sizing.avatar),
                )
                Spacer(Modifier.width(Spacing.small))
                Row(
                    modifier          = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text       = if (boostedJson != null) {
                            originalAuthorProfile?.displayName?.takeIf { it.isNotBlank() }
                                ?: originalAuthorProfile?.name?.takeIf { it.isNotBlank() }
                                ?: "${effectivePubkey.take(6)}…${effectivePubkey.takeLast(4)}"
                        } else {
                            row.displayName ?: "${row.pubkey.take(6)}…${row.pubkey.takeLast(4)}"
                        },
                        color      = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 14.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f, fill = false),
                    )
                    if (boostedJson == null && !row.authorNip05.isNullOrBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector        = Icons.Filled.Verified,
                            contentDescription = "NIP-05 verified",
                            tint               = Cyan,
                            modifier           = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text     = nip05Domain(row.authorNip05),
                            color    = TextSecondary,
                            fontSize = 11.sp,
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
                fontSize = 12.sp,
                modifier = Modifier.clickable { onNoteClick(navigateId) },
            )
        }

        // ── Full-width text content (collapse if > 300 chars) ─────────────────
        if (textContent.isNotBlank()) {
            val isLong = textContent.length > 300
            var expanded by remember { mutableStateOf(false) }

            Text(
                text       = textContent,
                color      = MaterialTheme.colorScheme.onSurface,
                fontSize   = 15.sp,
                lineHeight = 22.sp,
                maxLines   = if (isLong && !expanded) 8 else Int.MAX_VALUE,
                overflow   = if (isLong && !expanded) TextOverflow.Ellipsis else TextOverflow.Clip,
                modifier   = Modifier
                    .fillMaxWidth()
                    .clickable { onNoteClick(navigateId) }
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = if (isLong) 2.dp else 4.dp),
            )
            if (isLong) {
                Text(
                    text     = if (expanded) "Show less" else "Show more",
                    color    = Cyan,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(horizontal = Spacing.medium)
                        .padding(bottom = 4.dp)
                        .clickable { expanded = !expanded },
                )
            }
        }

        // ── BUG #4 FIX: Media grid for multiple images ──────────────────────
        if (imageUrls.isNotEmpty()) {
            MediaGrid(
                imageUrls  = imageUrls,
                imetaMedia = imetaMedia,
                onImageClick = { url -> fullscreenImageUrl = url },
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
                isActiveVideo     = isActiveVideo,
                onOpenFullscreen  = onOpenFullscreen,
                exoPlayer         = exoPlayer,
                isMuted           = isMuted,
                onToggleMute      = onToggleMute,
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

        // ── NIP-19 embedded quotes (Bug #3: show actual content) ─────────────
        nostrRefs.filterIsInstance<NostrRef.EventRef>().forEach { ref ->
            EmbeddedQuoteCard(
                eventId     = ref.eventId,
                onNoteClick = onNoteClick,
                lookupEvent = lookupEvent,
                lookupProfile = lookupProfile,
                modifier    = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }

        // ── NIP-19 mention chips (Bug #2: show display names) ────────────────
        val profileRefs = nostrRefs.filterIsInstance<NostrRef.ProfileRef>()
        if (profileRefs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                profileRefs.forEach { ref ->
                    MentionChip(
                        pubkeyHex     = ref.pubkeyHex,
                        onAuthorClick = onAuthorClick,
                        lookupProfile = lookupProfile,
                    )
                }
            }
        }

        // ── Full-width action bar ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNoteClick(navigateId) }
                .padding(horizontal = Spacing.medium)
                .padding(bottom = Spacing.small),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            ActionButton(
                icon               = Icons.AutoMirrored.Filled.Chat,
                count              = row.replyCount,
                contentDescription = "Replies",
            )
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
                        text    = { Text("Boost", color = Color.White, fontSize = 14.sp) },
                        onClick = { onRepost(); showRepostMenu = false },
                    )
                    DropdownMenuItem(
                        text    = { Text("Quote", color = Color.White, fontSize = 14.sp) },
                        onClick = { onQuote(row.id); showRepostMenu = false },
                    )
                }
            }
            ActionButton(
                icon               = Icons.Filled.Favorite,
                count              = row.reactionCount,
                contentDescription = "Reactions",
                highlighted        = hasReacted,
                onClick            = onReact,
            )
            ZapButton(
                sats          = row.zapTotalSats,
                hasZapped     = hasZapped,
                onTap         = {
                    if (isNwcConfigured) onZap(1_000L) else showConnectWallet = true
                },
                onLongPress   = {
                    if (isNwcConfigured) showZapPicker = true else showConnectWallet = true
                },
            )
            ActionButton(
                icon               = Icons.Filled.Share,
                count              = 0,
                contentDescription = "Share",
            )
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
    fullscreenImageUrl?.let { url ->
        FullScreenImageDialog(
            imageUrl  = url,
            onDismiss = { fullscreenImageUrl = null },
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
        .background(lerp(Color(0xFF1A1A1A), Color(0xFF2A2A2A), progress))
    )
}

/**
 * IdentIcon always renders as the background/fallback.
 * AsyncImage overlays on top when a picture URL is available.
 * If the network load fails, the IdentIcon underneath remains visible.
 */
@Composable
private fun AvatarImage(pubkey: String, picture: String?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(CircleShape)) {
        IdentIcon(pubkey = pubkey, modifier = Modifier.fillMaxSize())
        if (!picture.isNullOrBlank()) {
            AsyncImage(
                model              = picture,
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
        Modifier.defaultMinSize(minWidth = 48.dp).clickable(onClick = onClick)
    else
        Modifier.defaultMinSize(minWidth = 48.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = rowModifier,
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
                fontSize = 12.sp,
            )
        }
    }
}

/** Zap button: Amber when sats > 0 or user has zapped, supports single tap and long-press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ZapButton(
    sats: Long,
    hasZapped: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val tint = if (sats > 0 || hasZapped) ZapAmber else ActionTint
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier
            .defaultMinSize(minWidth = 48.dp)
            .combinedClickable(
                onClick     = onTap,
                onLongClick = onLongPress,
            ),
    ) {
        Icon(
            imageVector        = Icons.Filled.ElectricBolt,
            contentDescription = "Zap",
            tint               = tint,
            modifier           = Modifier.size(Sizing.actionIcon),
        )
        if (sats > 0) {
            Spacer(Modifier.width(Spacing.micro))
            Text(
                text     = sats.toCompactSats(),
                color    = tint,
                fontSize = 12.sp,
            )
        }
    }
}

// ── BUG #4 FIX: Media grid composable ─────────────────────────────────────────

/** Single image cell with proper portrait aspect ratio handling (Bug #5). */
@Composable
private fun MediaImage(
    url: String,
    imetaMedia: List<ImetaMedia>,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    forceSquare: Boolean = false,
) {
    val imgAspect = imetaMedia
        .firstOrNull { it.url == url && it.width != null && it.height != null }
        ?.let { it.width!!.toFloat() / it.height!! }
        ?: 4f / 3f

    val displayAspect = if (forceSquare) 1f else effectiveAspectRatio(imgAspect)

    SubcomposeAsyncImage(
        model              = url,
        contentDescription = null,
        contentScale       = ContentScale.Crop,
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
        modifier           = modifier
            .fillMaxWidth()
            .aspectRatio(displayAspect, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(MediaPlaceholder)
            .clickable { onImageClick(url) },
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
                                    fontSize = 24.sp,
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

/** Helper to resolve video aspect ratio and poster from imeta tags. */
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
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    forceSquare: Boolean = false,
) {
    val (aspectRatio, posterUrl) = resolveVideoMeta(url, imetaMedia)
    VideoThumbnailCard(
        url         = url,
        onPlay      = onPlay,
        aspectRatio = aspectRatio,
        posterUrl   = posterUrl,
        forceSquare = forceSquare,
        modifier    = modifier,
    )
}

/** Renders videos in a grid: 1=full (autoplay), 2=side-by-side, 3=1+2, 4+=2x2 with +N overlay. */
@Composable
private fun VideoGrid(
    videoUrls: List<String>,
    imetaMedia: List<ImetaMedia>,
    isActiveVideo: Boolean,
    onOpenFullscreen: () -> Unit,
    exoPlayer: ExoPlayer?,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val count = videoUrls.size

    fun openVideo(url: String) {
        if (isDirectVideoUrl(url)) onOpenFullscreen()
        else runCatching { uriHandler.openUri(url) }
    }

    /** Renders the first video as inline autoplay thumbnail when eligible, otherwise as static thumbnail. */
    @Composable
    fun ActiveVideoCell(url: String, cellModifier: Modifier = Modifier, forceSquare: Boolean = false) {
        if (isDirectVideoUrl(url) && exoPlayer != null) {
            val (aspectRatio, posterUrl) = resolveVideoMeta(url, imetaMedia)
            InlineAutoPlayVideo(
                exoPlayer        = exoPlayer,
                videoUrl         = url,
                aspectRatio      = aspectRatio,
                isMuted          = isMuted,
                onToggleMute     = onToggleMute,
                onOpenFullscreen = onOpenFullscreen,
                isActive         = isActiveVideo,
                thumbnailUrl     = posterUrl,
                forceSquare      = forceSquare,
                modifier         = cellModifier,
            )
        } else {
            VideoGridCell(
                url         = url,
                imetaMedia  = imetaMedia,
                onPlay      = { openVideo(url) },
                modifier    = cellModifier,
                forceSquare = forceSquare,
            )
        }
    }

    when {
        count == 1 -> {
            ActiveVideoCell(url = videoUrls[0], cellModifier = modifier)
        }
        count == 2 -> {
            Row(
                modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ActiveVideoCell(
                    url = videoUrls[0],
                    cellModifier = Modifier.weight(1f),
                    forceSquare = true,
                )
                VideoGridCell(
                    url = videoUrls[1],
                    imetaMedia = imetaMedia,
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
                ActiveVideoCell(
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
                        onPlay = { openVideo(videoUrls[1]) },
                        modifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                    VideoGridCell(
                        url = videoUrls[2],
                        imetaMedia = imetaMedia,
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
                    ActiveVideoCell(
                        url = gridVideos[0],
                        cellModifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                    VideoGridCell(
                        url = gridVideos[1],
                        imetaMedia = imetaMedia,
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
                        onPlay = { openVideo(gridVideos[2]) },
                        modifier = Modifier.weight(1f),
                        forceSquare = true,
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        VideoGridCell(
                            url = gridVideos[3],
                            imetaMedia = imetaMedia,
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
                                    fontSize   = 24.sp,
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
    forceSquare: Boolean = false,
) {
    val displayAspect = feedVideoAspectRatio(aspectRatio, forceSquare)

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
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            // Static placeholder — no remote video frame extraction in lists.
            // VideoFrameDecoder downloads the video to extract a frame, which
            // is too expensive during scroll and causes overheating.
            Box(
                modifier = Modifier.matchParentSize().background(MediaPlaceholder),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(36.dp),
                )
            }
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
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
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
private fun FullScreenImageDialog(
    imageUrl: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier         = Modifier.fillMaxSize().background(Color.Black).clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            SubcomposeAsyncImage(
                model              = imageUrl,
                contentDescription = null,
                contentScale       = ContentScale.Fit,
                modifier           = Modifier.fillMaxSize(),
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

// ── BUG #3 FIX: Rich embedded quote card ────────────────────────────────────

/**
 * Tappable inline card for a quoted nostr event. Shows actual content when available.
 * [nestDepth] prevents infinite recursion: 0 = top-level quote, 1 = nested, 2+ = stop.
 */
@Composable
private fun EmbeddedQuoteCard(
    eventId: String,
    onNoteClick: (String) -> Unit,
    lookupEvent: (suspend (String) -> EventEntity?)? = null,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    modifier: Modifier = Modifier,
    nestDepth: Int = 0,
) {
    // Try to load the quoted event (Room first, then relay fetch via lookupEvent)
    val event by produceState<EventEntity?>(null, eventId) {
        if (lookupEvent != null) value = lookupEvent(eventId)
    }
    val author by produceState<UserEntity?>(null, event?.pubkey) {
        val pk = event?.pubkey
        if (pk != null && lookupProfile != null) value = lookupProfile(pk)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (nestDepth == 0) Color(0xFF0A0A0A) else Color(0xFF0D0D0D))
            .border(0.5.dp, if (nestDepth == 0) Color(0xFF2A2A2A) else Color(0xFF333333), RoundedCornerShape(8.dp))
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
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text     = author?.displayName?.takeIf { it.isNotBlank() }
                            ?: author?.name?.takeIf { it.isNotBlank() }
                            ?: "${loadedEvent.pubkey.take(6)}…${loadedEvent.pubkey.takeLast(4)}",
                        color    = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text     = relativeTime(loadedEvent.createdAt),
                        color    = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Strip nostr URIs and URLs from quoted content for cleaner display
                val cleanContent = LINK_URL_REGEX.replace(
                    NOSTR_URI_REGEX.replace(loadedEvent.content, ""),
                    ""
                ).trim()
                if (cleanContent.isNotBlank()) {
                    Text(
                        text     = cleanContent,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
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
                            eventId       = ref.eventId,
                            onNoteClick   = onNoteClick,
                            lookupEvent   = lookupEvent,
                            lookupProfile = lookupProfile,
                            nestDepth     = nestDepth + 1,
                        )
                    }
                }
            }
        } else {
            // Fallback: minimal quote placeholder
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.Filled.FormatQuote,
                    contentDescription = null,
                    tint               = TextSecondary,
                    modifier           = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text     = "Quoted post",
                    color    = TextSecondary,
                    fontSize = 13.sp,
                )
            }
        }
    }
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
            .background(Color(0xFF1A1A1A))
            .border(1.dp, Color(0xFF333333), RoundedCornerShape(20.dp))
            .clickable { onAuthorClick(pubkeyHex) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text     = if (displayText.startsWith("@")) displayText else "@$displayText",
            color    = Cyan,
            fontSize = 12.sp,
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
        // Rich preview card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
                .background(Color(0xFF0D0D0D))
                .border(0.5.dp, Color(0xFF333333), RoundedCornerShape(Sizing.mediaCornerRadius))
                .clickable { runCatching { uriHandler.openUri(url) } }
                .padding(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!loadedOg.imageUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model              = loadedOg.imageUrl,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    loading            = {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(MediaPlaceholder),
                        )
                    },
                    error              = { /* Hide broken thumbnail silently */ },
                    modifier           = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MediaPlaceholder),
                )
                Spacer(Modifier.width(Spacing.small))
            }
            Column(modifier = Modifier.weight(1f)) {
                if (!loadedOg.title.isNullOrBlank()) {
                    Text(
                        text       = loadedOg.title,
                        color      = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 13.sp,
                        lineHeight = 17.sp,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                    )
                }
                if (!loadedOg.description.isNullOrBlank()) {
                    Text(
                        text     = loadedOg.description,
                        color    = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text     = loadedOg.siteName ?: domain,
                    color    = TextSecondary,
                    fontSize = 11.sp,
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
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private val FeedRow.displayName: String?
    get() = authorDisplayName?.takeIf { it.isNotBlank() }
         ?: authorName?.takeIf { it.isNotBlank() }

internal val FeedRow.engagementId: String
    get() = if (kind == 6 && rootId != null) rootId!! else id

/** "user@domain.com" → "domain.com"; identity-free "_@domain.com" → "domain.com". */
private fun nip05Domain(nip05: String): String = nip05.substringAfter("@", nip05)

private fun relativeTime(createdAtSeconds: Long): String {
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
