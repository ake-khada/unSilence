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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.ImetaParser
import com.unsilence.app.data.relay.extractRepostAuthorPubkey
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

private val ActionTint = Color(0xFF555555)
private val MediaPlaceholder = Color(0xFF1A1A1A)

// Matches URLs ending in image extensions, or from known Nostr image hosts.
private val IMAGE_URL_REGEX = Regex(
    """https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?|https?://(?:image\.nostr\.build|i\.nostr\.build|nostr\.build|blossom\.primal\.net)/\S+""",
    RegexOption.IGNORE_CASE,
)

// Matches direct video URLs (.mp4 etc.) and known video platforms.
internal val VIDEO_URL_REGEX = Regex(
    """https?://\S+\.(?:mp4|mov|webm|m3u8)(?:\?\S*)?|https?://(?:www\.)?(?:youtube\.com/watch\S*|youtu\.be/\S+|streamable\.com/\S+)""",
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

private data class MediaExtraction(
    val imageUrls: List<String>,
    val videoUrls: List<String>,
    val linkUrls: List<String>,
    val textContent: String,
)

private fun isDirectVideoUrl(url: String): Boolean =
    url.contains(".mp4", ignoreCase = true) ||
    url.contains(".mov", ignoreCase = true) ||
    url.contains(".webm", ignoreCase = true) ||
    url.contains(".m3u8", ignoreCase = true)

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
    isActiveVideo: Boolean = false,
    isMuted: Boolean = true,
    onToggleMute: () -> Unit = {},
    onOpenFullscreen: () -> Unit = {},
) {
    var showRepostMenu    by remember { mutableStateOf(false) }
    var showConnectWallet by remember { mutableStateOf(false) }
    var showZapPicker     by remember { mutableStateOf(false) }
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

    // ── NIP-19 nostr: URI extraction (strip before other URL processing) ──────
    val nostrRefs = NOSTR_URI_REGEX.findAll(effectiveContent)
        .mapNotNull { decodeNostrRef(it.value) }
        .toList()
    val contentNoNostr = NOSTR_URI_REGEX.replace(effectiveContent, "").trim()

    // ── Media extraction: regex from content + imeta from tags ────────────────
    // Wrapped in remember to avoid re-parsing JSON on every recomposition.
    val imetaMedia = remember(row.tags) { ImetaParser.parse(row.tags) }
    val mediaExtraction = remember(row.id, contentNoNostr) {
        val regexImageUrls = IMAGE_URL_REGEX.findAll(contentNoNostr).map { it.value }.toList()
        val imetaImageUrls = imetaMedia.filter { it.mimeType?.startsWith("image/") == true }.map { it.url }

        val afterImages    = IMAGE_URL_REGEX.replace(contentNoNostr, "")
        val regexVideoUrls = VIDEO_URL_REGEX.findAll(afterImages).map { it.value }.toList()
        val imetaVideoUrls = imetaMedia.filter { it.mimeType?.startsWith("video/") == true }.map { it.url }

        val afterVideos    = VIDEO_URL_REGEX.replace(afterImages, "")

        val allVideoUrls = (regexVideoUrls + imetaVideoUrls).distinct()

        MediaExtraction(
            imageUrls   = (regexImageUrls + imetaImageUrls).distinct()
                              .filter { it !in allVideoUrls },
            videoUrls   = allVideoUrls,
            linkUrls    = LINK_URL_REGEX.findAll(afterVideos).map { it.value }.distinct().take(3).toList()
                              .filter { it !in allVideoUrls },
            textContent = LINK_URL_REGEX.replace(afterVideos, "").trim(),
        )
    }
    val imageUrls   = mediaExtraction.imageUrls
    val videoUrls   = mediaExtraction.videoUrls
    val linkUrls    = mediaExtraction.linkUrls
    val textContent = mediaExtraction.textContent

    Column(modifier = modifier.fillMaxWidth().clickable { onNoteClick(row.id) }) {

        // ── Boost header (kind 6 only) ─────────────────────────────────────────
        if (row.kind == 6) {
            val reposterLabel = row.displayName ?: "${row.pubkey.take(6)}…${row.pubkey.takeLast(4)}"
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
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
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = if (isLong) 2.dp else Spacing.small),
            )
            if (isLong) {
                Text(
                    text     = if (expanded) "Show less" else "Show more",
                    color    = Cyan,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(horizontal = Spacing.medium)
                        .padding(bottom = Spacing.small)
                        .clickable { expanded = !expanded },
                )
            }
        }

        // ── First inline image only; overflow count shown as muted label ───────
        imageUrls.firstOrNull()?.let { url ->
            SubcomposeAsyncImage(
                model              = url,
                contentDescription = null,
                contentScale       = ContentScale.FillWidth,
                loading            = { ShimmerBox(modifier = Modifier.fillMaxSize()) },
                modifier           = Modifier
                    .fillMaxWidth()
                    .then(
                        imetaMedia.firstOrNull { it.url == url && it.width != null && it.height != null }
                            ?.let { Modifier.aspectRatio(it.width!!.toFloat() / it.height!!, matchHeightConstraintsFirst = false) }
                            ?: Modifier.defaultMinSize(minHeight = 200.dp)
                    )
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = if (imageUrls.size > 1) 2.dp else Spacing.small)
                    .clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
            )
        }
        if (imageUrls.size > 1) {
            Text(
                text     = "+${imageUrls.size - 1} more",
                color    = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }

        // ── First video URL: thumbnail with play button ────────────────────────
        videoUrls.firstOrNull()?.let { url ->
            val videoMeta = imetaMedia.firstOrNull {
                it.url == url && it.width != null && it.height != null
            }
            val posterUrl = imetaMedia.firstOrNull { it.url == url }?.thumb

            if (isActiveVideo && exoPlayer != null && isDirectVideoUrl(url)) {
                InlineAutoPlayVideo(
                    exoPlayer       = exoPlayer,
                    videoUrl        = url,
                    aspectRatio     = if (videoMeta != null) videoMeta.width!!.toFloat() / videoMeta.height!! else null,
                    isMuted         = isMuted,
                    onToggleMute    = onToggleMute,
                    onOpenFullscreen = onOpenFullscreen,
                    modifier        = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                )
            } else {
                VideoThumbnailCard(
                    url         = url,
                    onPlay      = {
                        if (isDirectVideoUrl(url)) {
                            onOpenFullscreen()
                        } else {
                            runCatching { uriHandler.openUri(url) }
                        }
                    },
                    aspectRatio = if (videoMeta != null) videoMeta.width!!.toFloat() / videoMeta.height!! else null,
                    posterUrl   = posterUrl,
                    modifier    = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                )
            }
        }

        // ── Link chips for non-media URLs ──────────────────────────────────────
        if (linkUrls.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            ) {
                linkUrls.forEach { url -> LinkChip(url = url) }
            }
        }

        // ── NIP-19 embedded quotes ─────────────────────────────────────────────
        nostrRefs.filterIsInstance<NostrRef.EventRef>().forEach { ref ->
            EmbeddedQuoteCard(
                eventId     = ref.eventId,
                onNoteClick = onNoteClick,
                modifier    = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }

        // ── NIP-19 mention chips ───────────────────────────────────────────────
        val profileRefs = nostrRefs.filterIsInstance<NostrRef.ProfileRef>()
        if (profileRefs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                profileRefs.forEach { ref ->
                    MentionChip(pubkeyHex = ref.pubkeyHex, onAuthorClick = onAuthorClick)
                }
            }
        }

        // ── Full-width action bar ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                count         = row.zapCount,
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
private fun ActionButton(
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

/** Zap button: Amber when zapped, supports single tap and long-press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZapButton(
    count: Int,
    hasZapped: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val tint = if (hasZapped) ZapAmber else ActionTint
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

/** Tap-to-play placeholder shown for detected video URLs. */
@Composable
private fun VideoThumbnailCard(
    url: String,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    aspectRatio: Float? = null,
    posterUrl: String? = null,
) {
    Box(
        modifier          = modifier
            .fillMaxWidth()
            .then(
                if (aspectRatio != null && aspectRatio > 0f)
                    Modifier.aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
                        .defaultMinSize(minHeight = 120.dp)
                        .heightIn(max = 300.dp)
                else Modifier.height(180.dp)
            )
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(Color(0xFF1A1A1A))
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
        }
        Icon(
            imageVector        = Icons.Filled.PlayArrow,
            contentDescription = "Play video",
            tint               = Color.White.copy(alpha = 0.85f),
            modifier           = Modifier.size(52.dp),
        )
    }
}

/** Tappable inline card for a quoted nostr event. Opens the thread on tap. */
@Composable
private fun EmbeddedQuoteCard(
    eventId: String,
    onNoteClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF111111))
            .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
            .clickable { onNoteClick(eventId) }
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
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

/** Inline mention chip for a nostr pubkey. Tapping opens the user's profile. */
@Composable
private fun MentionChip(
    pubkeyHex: String,
    onAuthorClick: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1A1A1A))
            .border(1.dp, Color(0xFF333333), RoundedCornerShape(20.dp))
            .clickable { onAuthorClick(pubkeyHex) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text     = "@${pubkeyHex.take(8)}…",
            color    = Cyan,
            fontSize = 12.sp,
        )
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

private fun formatCount(n: Int): String = when {
    n < 1_000  -> "$n"
    n < 10_000 -> "%.1fk".format(n / 1_000f)
    else        -> "${n / 1_000}k"
}
