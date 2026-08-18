package com.unsilence.app.ui.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.theme.Spacing

@Composable
fun ComposePreviewCard(
    model: EventModel,
    ownPubkey: String,
    ownProfile: UserEntity?,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    lookupEvent: (suspend (String, List<String>) -> EventEntity?)?,
    lookupModel: ((String) -> EventModel?)?,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)?,
    hasCachedOgMetadata: ((String) -> Boolean)? = null,
    imageDimensionCache: ImageDimensionCache?,
    thumbnailCache: VideoThumbnailCache?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AuthorHeader(
            pubkey = ownPubkey,
            picture = ownProfile?.picture,
            displayName = ownProfile?.displayName?.takeIf { it.isNotBlank() }
                ?: ownProfile?.name?.takeIf { it.isNotBlank() },
            nip05 = ownProfile?.nip05,
            createdAt = System.currentTimeMillis() / 1000,
            onAuthorClick = {},
            onNoteClick = {},
        )
        Spacer(Modifier.height(Spacing.small))
        ContentFlow(
            model = model,
            role = CardRole.Feed,
            onNoteClick = {},
            onAuthorClick = {},
            lookupProfile = lookupProfile,
            lookupEvent = lookupEvent,
            lookupModel = lookupModel,
            fetchOgMetadata = fetchOgMetadata,
            hasCachedOgMetadata = hasCachedOgMetadata,
            imageDimensionCache = imageDimensionCache,
            thumbnailCache = thumbnailCache,
            exoPlayer = null,
            isActiveVideo = false,
            knownLightningAddress = ownProfile?.lud16,
        )
        model.poll?.let { poll ->
            PollCard(
                pollId = model.engagementId,
                pollAuthorPubkey = model.pubkey,
                pollCreatedAt = model.createdAt,
                poll = poll,
                sourceRelay = model.relayUrl,
                callbacks = null,
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
        }
    }
}
