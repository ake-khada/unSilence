package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary

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
    lookupEventWithAuthor: suspend (String, List<String>, String?) -> EventEntity?,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    lookupModel: ((String) -> EventModel?)?,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)?,
    imageDimensionCache: ImageDimensionCache?,
    onNoteClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
) {
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
            ContentFlow(
                model               = state.model!!,
                role                = CardRole.Embedded,
                onNoteClick         = onNoteClick,
                onAuthorClick       = onAuthorClick,
                lookupProfile       = lookupProfile,
                lookupEvent         = { id, h -> lookupEventWithAuthor(id, h, null) },
                lookupModel         = lookupModel,
                fetchOgMetadata     = fetchOgMetadata,
                imageDimensionCache = imageDimensionCache,
                nestDepth           = 0,
            )
        }
        state.unresolved -> {
            // Terminal failure — tappable fallback row. Card must always have height.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            ) {
                Text(
                    text = "Reposted note unavailable",
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
