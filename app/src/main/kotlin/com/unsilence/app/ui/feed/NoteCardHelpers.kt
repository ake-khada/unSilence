package com.unsilence.app.ui.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.relay.Nip19FailureCache
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Zap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ── Constants ────────────────────────────────────────────────────────────────

internal val ActionTint = Text3

// Matches URLs ending in image extensions, or from known Nostr/Bluesky image hosts.
internal val IMAGE_URL_REGEX = Regex(
    """https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?|https?://(?:image\.nostr\.build|i\.nostr\.build|nostr\.build|blossom\.primal\.net)/\S+|https?://\S+/xrpc/com\.atproto\.sync\.getBlob\?\S+""",
    RegexOption.IGNORE_CASE,
)

// Matches direct video file URLs only (no web pages like YouTube).
internal val VIDEO_URL_REGEX = Regex(
    """https?://\S+\.(?:mp4|mov|webm|m3u8|m4v|avi)(?:\?\S*)?""",
    RegexOption.IGNORE_CASE,
)

// Matches any remaining http/https URL (applied after stripping image + video URLs).
internal val LINK_URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

// Matches profile-type nostr: URIs for inline mention rendering.
private val NOSTR_PROFILE_URI_REGEX = Regex("nostr:n(?:pub|profile)1[a-z0-9]+", RegexOption.IGNORE_CASE)

private val HEX_PUBKEY_REGEX = Regex("^[0-9a-fA-F]{64}$")

// ── Extension properties ───────────��─────────────────────────────────────────

internal val FeedRow.displayName: String?
    get() = authorDisplayName?.takeIf { it.isNotBlank() }
         ?: authorName?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }

internal val FeedRow.engagementId: String
    get() = if ((kind == 6 || kind == 16) && rootId != null) rootId!! else id

// ── Pure helper functions ────────────────────────────────────────────────────

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

/** True if [s] is a 64-char hex string (i.e. a raw pubkey, not a human name). */
internal fun looksLikeHexPubkey(s: String): Boolean = HEX_PUBKEY_REGEX.matches(s)

// Formatters are immutable and thread-safe. Cache one per locale so a runtime
// locale change does not leave feed timestamps stuck in the process-start locale.
private val MONTH_DAY_FORMATS = ConcurrentHashMap<Locale, DateTimeFormatter>()

private fun monthDayFormat(): DateTimeFormatter {
    val locale = Locale.getDefault()
    return MONTH_DAY_FORMATS.computeIfAbsent(locale) {
        DateTimeFormatter.ofPattern("MMM d", it)
    }
}

internal fun relativeTime(createdAtSeconds: Long): String {
    val diffMs = System.currentTimeMillis() - createdAtSeconds * 1000L
    return when {
        diffMs < TimeUnit.MINUTES.toMillis(1) -> "now"
        diffMs < TimeUnit.HOURS.toMillis(1)   -> "${TimeUnit.MILLISECONDS.toMinutes(diffMs)}m"
        diffMs < TimeUnit.DAYS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toHours(diffMs)}h"
        diffMs < TimeUnit.DAYS.toMillis(7)    -> "${TimeUnit.MILLISECONDS.toDays(diffMs)}d"
        else -> monthDayFormat().format(
            Instant.ofEpochMilli(createdAtSeconds * 1000L).atZone(ZoneId.systemDefault()),
        )
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

// ── Nostr URI decoding (used by NostrRichText) ──────────────────────────────

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

// ── Composable helpers ─────────────��─────────────────────────────────────────

@Composable
internal fun AvatarImage(
    pubkey: String,
    picture: String?,
    modifier: Modifier = Modifier,
    sizeDp: Dp = Sizing.avatar,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    profileFlow: ((String) -> StateFlow<UserEntity?>)? = null,
) {
    var lookedUpProfile by remember(pubkey) { mutableStateOf<UserEntity?>(null) }

    // Observe the profile reactively — when MES receives the kind-0,
    // _profileSignal bumps and this re-emits the updated UserEntity.
    val liveProfile = profileFlow?.invoke(pubkey)
        ?.collectAsStateWithLifecycle()?.value

    val effectivePicture = liveProfile?.picture?.takeIf { it.isNotBlank() }
        ?: picture?.takeIf { it.isNotBlank() }
        ?: lookedUpProfile?.picture?.takeIf { it.isNotBlank() }

    // Trigger profile fetch when picture is missing — debounced to avoid
    // thundering-herd on initial feed render where many avatars are null.
    if (effectivePicture.isNullOrBlank() && lookupProfile != null) {
        LaunchedEffect(pubkey) {
            delay(800)
            lookedUpProfile = lookupProfile(pubkey)
        }
    }

    Box(modifier = modifier.clip(CircleShape)) {
        IdentIcon(pubkey = pubkey, modifier = Modifier.fillMaxSize())
        if (!effectivePicture.isNullOrBlank()) {
            AsyncImage(
                model              = rememberAvatarImageRequest(effectivePicture, sizeDp),
                contentDescription = null,
                modifier           = Modifier.fillMaxSize(),
            )
        }
    }
}

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
            profileMap = coroutineScope {
                mentions.map { (_, _, hex) ->
                    async { hex to lookupProfile(hex) }
                }.awaitAll().toMap()
            }
        }
    }

    val annotatedText = remember(content, mentions, profileMap, onAuthorClick) {
        buildAnnotatedString {
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
                    ?: profile?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
                    ?: npubFallback

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

// ── Action bar primitives (used by ArticleReaderScreen) ─────────────────────

/** Single action bar button: vector icon + optional count. Turns [highlightColor] when [highlighted]. */
@Composable
internal fun ActionButton(
    icon: ImageVector,
    count: Int,
    contentDescription: String,
    highlighted: Boolean = false,
    highlightColor: Color = Brand,
    onClick: (() -> Unit)? = null,
) {
    val tint = if (highlighted) highlightColor else ActionTint
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

    val baseTint = if (hasZapped) Zap else ActionTint
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
                    color       = Brand,
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

// ── Video fullscreen dialog ──────────────────────────────────────────────────

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
