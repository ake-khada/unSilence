package com.unsilence.app.ui.feed

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.ui.shared.NostrAddressDisplay
import com.unsilence.app.ui.shared.NostrAddressPresentation
import com.unsilence.app.ui.shared.SelfDeclaredNostrAddressText
import com.unsilence.app.ui.shared.WotFeedMetaTimestamp
import com.unsilence.app.ui.shared.selfDeclaredNostrAddressPresentation
import kotlinx.coroutines.flow.StateFlow
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

/**
 * Avatar + display name + self-declared Nostr address + timestamp row.
 *
 * Tapping the avatar+name zone navigates to the author's profile.
 * Timestamp is a separate click zone that navigates to the note.
 */
@Composable
internal fun AuthorHeader(
    pubkey: String,
    picture: String?,
    displayName: String?,
    nip05: String?,
    createdAt: Long,
    onAuthorClick: (String) -> Unit,
    onNoteClick: () -> Unit,
    modifier: Modifier = Modifier,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    profileFlow: ((String) -> StateFlow<UserEntity?>)? = null,
    wotLookup: ((String) -> WotLookup?)? = null,
    feedWotDisplayMode: FeedWotDisplayMode = FeedWotDisplayMode.NUMBERS,
    repostSourcePubkey: String? = null,
    repostSourceProfile: UserEntity? = null,
    repostSourceCreatedAt: Long? = null,
) {
    val authorLabel = displayName
        ?: "${pubkey.take(6)}…${pubkey.takeLast(4)}"
    val nostrAddress = remember(nip05) {
        selfDeclaredNostrAddressPresentation(nip05, NostrAddressDisplay.DOMAIN)
    }
    val reposterPubkey = repostSourcePubkey?.takeIf { it.isNotBlank() && it != pubkey }
    val reposterLabel = reposterPubkey?.let { key ->
        repostSourceProfile?.displayName?.takeIf { it.isNotBlank() }
            ?: repostSourceProfile?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
            ?: "${key.take(6)}…${key.takeLast(4)}"
    }

    if (reposterPubkey != null && reposterLabel != null) {
        RepostAuthorHeader(
            pubkey = pubkey,
            picture = picture,
            authorLabel = authorLabel,
            nostrAddress = nostrAddress,
            reposterPubkey = reposterPubkey,
            reposterLabel = reposterLabel,
            reposterPicture = repostSourceProfile?.picture,
            createdAt = repostSourceCreatedAt ?: createdAt,
            onAuthorClick = onAuthorClick,
            onNoteClick = onNoteClick,
            modifier = modifier,
            lookupProfile = lookupProfile,
            profileFlow = profileFlow,
            wotLookup = wotLookup,
            feedWotDisplayMode = feedWotDisplayMode,
        )
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar + name are one clickable zone → opens author's profile
        Row(
            modifier          = Modifier
                .weight(1f)
                .clickable { onAuthorClick(pubkey) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarImage(
                pubkey        = pubkey,
                picture       = picture,
                modifier      = Modifier.size(Sizing.avatar),
                lookupProfile = lookupProfile,
                profileFlow   = profileFlow,
            )
            Spacer(Modifier.width(Spacing.small))
            Row(
                modifier          = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text       = authorLabel,
                    color      = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = AppType.body,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.weight(if (nostrAddress != null) 0.45f else 1f, fill = false),
                )
                if (nostrAddress != null) {
                    Spacer(Modifier.width(4.dp))
                    SelfDeclaredNostrAddressText(
                        pubkey = pubkey,
                        presentation = nostrAddress,
                        fontSize = AppType.caption,
                        modifier = Modifier.weight(0.55f, fill = false),
                    )
                }
            }
        }
        Spacer(Modifier.width(Spacing.micro))
        WotFeedMetaTimestamp(
            lookup = wotLookup?.invoke(pubkey),
            mode = feedWotDisplayMode,
            timestamp = relativeTime(createdAt),
            modifier = Modifier.clickable { onNoteClick() },
        )
    }
}

@Composable
private fun RepostAuthorHeader(
    pubkey: String,
    picture: String?,
    authorLabel: String,
    nostrAddress: NostrAddressPresentation?,
    reposterPubkey: String,
    reposterLabel: String,
    reposterPicture: String?,
    createdAt: Long,
    onAuthorClick: (String) -> Unit,
    onNoteClick: () -> Unit,
    modifier: Modifier = Modifier,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    profileFlow: ((String) -> StateFlow<UserEntity?>)? = null,
    wotLookup: ((String) -> WotLookup?)? = null,
    feedWotDisplayMode: FeedWotDisplayMode = FeedWotDisplayMode.NUMBERS,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Top,
        ) {
            RepostCompositeAvatar(
                authorPubkey = pubkey,
                authorPicture = picture,
                reposterPubkey = reposterPubkey,
                reposterPicture = reposterPicture,
                reposterLabel = reposterLabel,
                lookupProfile = lookupProfile,
                profileFlow = profileFlow,
                onAuthorClick = { onAuthorClick(pubkey) },
                onReposterClick = { onAuthorClick(reposterPubkey) },
            )
            Spacer(Modifier.width(Spacing.small))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(Sizing.avatar),
            ) {
                Text(
                    text = "↻ $reposterLabel",
                    color = TextSecondary,
                    fontSize = 9.5.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(y = (-Spacing.micro))
                        .clickable { onAuthorClick(reposterPubkey) },
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clickable { onAuthorClick(pubkey) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AuthorNameRow(
                        pubkey = pubkey,
                        authorLabel = authorLabel,
                        nostrAddress = nostrAddress,
                    )
                }
            }
        }
        Spacer(Modifier.width(Spacing.micro))
        WotFeedMetaTimestamp(
            lookup = wotLookup?.invoke(pubkey),
            mode = feedWotDisplayMode,
            timestamp = relativeTime(createdAt),
            modifier = Modifier.clickable { onNoteClick() },
        )
    }
}

@Composable
internal fun RepostCompositeAvatar(
    authorPubkey: String,
    authorPicture: String?,
    reposterPubkey: String,
    reposterPicture: String?,
    reposterLabel: String,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    profileFlow: ((String) -> StateFlow<UserEntity?>)? = null,
    onAuthorClick: () -> Unit,
    onReposterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(Sizing.avatar)
            .semantics { contentDescription = "Reposted by $reposterLabel" },
    ) {
        AvatarImage(
            pubkey = authorPubkey,
            picture = authorPicture,
            modifier = Modifier
                .size(Sizing.avatar)
                .clickable(onClick = onAuthorClick),
            lookupProfile = lookupProfile,
            profileFlow = profileFlow,
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 3.dp)
                .size(16.dp)
                .clip(CircleShape)
                .border(1.5f.dp, Black, CircleShape)
                .clickable(onClick = onReposterClick),
        ) {
            AvatarImage(
                pubkey = reposterPubkey,
                picture = reposterPicture,
                modifier = Modifier.size(16.dp),
                sizeDp = 16.dp,
                lookupProfile = lookupProfile,
                profileFlow = profileFlow,
            )
        }
    }
}

@Composable
private fun RowScope.AuthorNameRow(
    pubkey: String,
    authorLabel: String,
    nostrAddress: NostrAddressPresentation?,
) {
    Text(
        text       = authorLabel,
        color      = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        fontSize   = AppType.body,
        maxLines   = 1,
        overflow   = TextOverflow.Ellipsis,
        modifier   = Modifier.weight(if (nostrAddress != null) 0.45f else 1f, fill = false),
    )
    if (nostrAddress != null) {
        Spacer(Modifier.width(4.dp))
        SelfDeclaredNostrAddressText(
            pubkey = pubkey,
            presentation = nostrAddress,
            fontSize = AppType.caption,
            modifier = Modifier.weight(0.55f, fill = false),
        )
    }
}
