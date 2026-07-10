package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.StateFlow

private data class EmptyRepostState(
    val event: EventEntity? = null,
    val model: EventModel? = null,
    val loading: Boolean = true,
    val unresolved: Boolean = false,
)

@Composable
fun EmptyRepostBody(
    targetId: String,
    relayHints: List<String>,
    targetAuthorPubkey: String?,
    proxyUrl: String?,
    lookupEventWithAuthor: suspend (String, List<String>, String?) -> EventEntity?,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    profileFlow: ((String) -> StateFlow<UserEntity?>)? = null,
    lookupModel: ((String) -> EventModel?)?,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)?,
    hasCachedOgMetadata: ((String) -> Boolean)? = null,
    imageDimensionCache: ImageDimensionCache?,
    onNoteClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    // Video env — forwarded so a poster-less reposted video gets the MMR
    // first-frame (thumbnailCache) instead of a dark placeholder, and so the
    // target video can attach the shared player when this row is active.
    thumbnailCache: VideoThumbnailCache? = null,
    exoPlayer: ExoPlayer? = null,
    isActiveVideo: Boolean = false,
    activeVideoUrl: String? = null,
    isFullscreen: Boolean = false,
    onOpenFullscreen: () -> Unit = {},
    isMuted: Boolean = true,
    onToggleMute: () -> Unit = {},
    onVideoModelsResolved: ((List<VideoRenderModel>) -> Unit)? = null,
    sensitiveMode: com.unsilence.app.data.memory.SensitiveContentMode =
        com.unsilence.app.data.memory.SensitiveContentMode.SHOW,
    wotLookup: ((String) -> WotLookup?)? = null,
    feedWotDisplayMode: FeedWotDisplayMode = FeedWotDisplayMode.NUMBERS,
    onWotSubjectsVisible: (Collection<String>) -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    val state by produceState(EmptyRepostState(), targetId) {
        val ev = lookupEventWithAuthor(targetId, relayHints, targetAuthorPubkey)
        if (ev != null) {
            val cachedModel = lookupModel?.invoke(ev.id)
            val model = cachedModel ?: runCatching {
                ContentParser.parse(
                    id = ev.id,
                    pubkey = ev.pubkey,
                    kind = ev.kind,
                    content = ev.content,
                    tagsJson = ev.tags,
                    createdAt = ev.createdAt,
                    relayUrl = ev.relayUrl,
                    replyToId = ev.replyToId,
                    rootId = ev.rootId,
                    hasContentWarning = ev.hasContentWarning,
                    contentWarningReason = ev.contentWarningReason,
                )
            }.getOrNull()
            value = EmptyRepostState(event = ev, model = model, loading = false)
        } else {
            value = EmptyRepostState(loading = false, unresolved = true)
        }
    }

    when {
        state.loading -> {
            // Minimal loading hint — invisible while resolving. No bordered box.
            Box(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        state.event != null && state.model != null -> {
            // Render inline using ContentFlow — same pipeline as native posts.
            // No bordered box because the whole card IS the quote.
            // NIP-36: gate on the reposted TARGET's own content-warning.
            com.unsilence.app.ui.shared.EmbeddedSensitiveGate(
                mode = sensitiveMode,
                sensitive = state.event!!.hasContentWarning,
                reason = state.event!!.contentWarningReason,
            ) {
                ContentFlow(
                    model               = state.model!!,
                    role                = CardRole.Embedded,
                    onNoteClick         = onNoteClick,
                    onAuthorClick       = onAuthorClick,
                    lookupProfile       = lookupProfile,
                    profileFlow         = profileFlow,
                    lookupEvent         = { id, h -> lookupEventWithAuthor(id, h, null) },
                    lookupModel         = lookupModel,
                    fetchOgMetadata     = fetchOgMetadata,
                    hasCachedOgMetadata = hasCachedOgMetadata,
                    imageDimensionCache = imageDimensionCache,
                    thumbnailCache      = thumbnailCache,
                    exoPlayer           = exoPlayer,
                    isActiveVideo       = isActiveVideo,
                    activeVideoUrl      = activeVideoUrl,
                    isFullscreen        = isFullscreen,
                    onOpenFullscreen    = onOpenFullscreen,
                    isMuted             = isMuted,
                    onToggleMute        = onToggleMute,
                    onVideoModelsResolved = onVideoModelsResolved,
                    wotLookup           = wotLookup,
                    feedWotDisplayMode  = feedWotDisplayMode,
                    onWotSubjectsVisible = onWotSubjectsVisible,
                    nestDepth           = 0,
                )
            }
        }
        state.unresolved -> {
            // Terminal failure — tappable fallback row. Card must always have height.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceVariant, RoundedCornerShape(8.dp))
                    .clickable {
                        if (proxyUrl != null) {
                            runCatching { uriHandler.openUri(proxyUrl) }
                        } else {
                            onNoteClick(targetId)
                        }
                    }
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            ) {
                Text(
                    text = if (proxyUrl != null) "Open original post" else "Reposted note unavailable",
                    color = TextSecondary,
                    fontSize = AppType.footnote,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = "Tap to open",
                    tint = TextSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        else -> {
            // Event resolved but model parse failed — show raw content
            val ev = state.event
            if (ev != null && ev.content.isNotBlank()) {
                NostrRichText(
                    content = ev.content,
                    lookupProfile = lookupProfile,
                    onAuthorClick = onAuthorClick,
                    onTextClick = { onNoteClick(targetId) },
                    maxLines = 6,
                )
            }
        }
    }
}
