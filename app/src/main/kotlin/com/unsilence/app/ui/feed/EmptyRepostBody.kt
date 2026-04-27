package com.unsilence.app.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.shared.CardRole

private data class EmptyRepostState(
    val event: EventEntity? = null,
    val model: EventModel? = null,
    val loading: Boolean = true,
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
            value = EmptyRepostState(loading = false)
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
        else -> {
            // Resolution failed — render nothing (matches pre-fix behavior, no UX regression)
        }
    }
}
