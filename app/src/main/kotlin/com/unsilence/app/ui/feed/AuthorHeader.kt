package com.unsilence.app.ui.feed

import com.unsilence.app.ui.theme.AppTextStyles
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.unsilence.app.ui.shared.rememberCardWot
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.ui.shared.NostrAddressDisplay
import com.unsilence.app.ui.shared.NostrAddressPresentation
import com.unsilence.app.ui.shared.SelfDeclaredNostrAddressText
import com.unsilence.app.ui.shared.WotFeedMetaTimestamp
import com.unsilence.app.ui.shared.selfDeclaredNostrAddressPresentation
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

private val RepostAttributionStyle = AppTextStyles.footnote.copy(lineHeight = 14.sp)

/**
 * Avatar + display name + self-declared Nostr address + timestamp row.
 *
 * Tapping the avatar+name zone navigates to the author's profile.
 * Timestamp is a separate click zone that navigates to the note; null omits it.
 */
@Composable
internal fun AuthorHeader(
    pubkey: String,
    picture: String?,
    displayName: String?,
    nip05: String?,
    createdAt: Long?,
    onAuthorClick: (String) -> Unit,
    onNoteClick: () -> Unit,
    modifier: Modifier = Modifier,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    wotLookup: ((String) -> WotLookup?)? = null,
    feedWotDisplayMode: FeedWotDisplayMode = FeedWotDisplayMode.NUMBERS,
    repostSourcePubkey: String? = null,
    repostSourceProfile: UserEntity? = null,
    repostSourceCreatedAt: Long? = null,
    referenceRepost: Boolean = false,
) {
    val authorLabel = displayName
        ?: "${pubkey.take(6)}…${pubkey.takeLast(4)}"
    val nostrAddress = remember(nip05) {
        selfDeclaredNostrAddressPresentation(nip05, NostrAddressDisplay.DOMAIN)
    }
    val reposterPubkey = repostSourcePubkey?.takeIf { it.isNotBlank() && !referenceRepost }
    val reposterLabel = reposterPubkey?.let { key ->
        if (key == pubkey) authorLabel else repostSourceProfile?.displayName?.takeIf { it.isNotBlank() }
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
            createdAt = repostSourceCreatedAt ?: createdAt,
            onAuthorClick = onAuthorClick,
            onNoteClick = onNoteClick,
            modifier = modifier,
            lookupProfile = lookupProfile,
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
            HeaderAvatar(
                reposted      = referenceRepost,
                pubkey        = pubkey,
                picture       = picture,
                modifier      = Modifier.size(Sizing.avatar),
                lookupProfile = lookupProfile,
            )
            Spacer(Modifier.width(Spacing.small))
            if (referenceRepost) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = "Reposted",
                        color = TextSecondary,
                        style = RepostAttributionStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AuthorNameRow(
                            pubkey = pubkey,
                            authorLabel = authorLabel,
                            nostrAddress = nostrAddress,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.weight(1f),
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
            lookup = rememberCardWot(pubkey, wotLookup),
            mode = feedWotDisplayMode,
            createdAt = createdAt,
            timestampFontSize = AppType.footnote,
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
    createdAt: Long?,
    onAuthorClick: (String) -> Unit,
    onNoteClick: () -> Unit,
    modifier: Modifier = Modifier,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    wotLookup: ((String) -> WotLookup?)? = null,
    feedWotDisplayMode: FeedWotDisplayMode = FeedWotDisplayMode.NUMBERS,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        HeaderAvatar(
            reposted = true,
            pubkey = pubkey,
            picture = picture,
            modifier = Modifier.align(Alignment.Bottom).size(Sizing.avatar)
                .semantics { contentDescription = "Open profile for $authorLabel" }
                .clickable(role = Role.Button) { onAuthorClick(pubkey) },
            lookupProfile = lookupProfile,
        )
        Spacer(Modifier.width(Spacing.small))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = if (reposterPubkey == pubkey) "Reposted" else "$reposterLabel reposted",
                color = TextSecondary,
                style = RepostAttributionStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "Reposted by $reposterLabel" }
                    .clickable(role = Role.Button) { onAuthorClick(reposterPubkey) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).clickable { onAuthorClick(pubkey) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AuthorNameRow(pubkey, authorLabel, nostrAddress)
                }
                Spacer(Modifier.width(Spacing.micro))
                WotFeedMetaTimestamp(
                    lookup = rememberCardWot(pubkey, wotLookup),
                    mode = feedWotDisplayMode,
                    createdAt = createdAt,
                    timestampFontSize = AppType.footnote,
                    modifier = Modifier.clickable(onClick = onNoteClick),
                )
            }
        }
    }
}

/** A decorative repost marker; the existing avatar owns any profile click. */
@Composable
private fun HeaderAvatar(
    pubkey: String,
    picture: String?,
    reposted: Boolean,
    modifier: Modifier,
    lookupProfile: (suspend (String) -> UserEntity?)?,
) {
    if (!reposted) {
        AvatarImage(pubkey, picture, modifier, lookupProfile = lookupProfile)
        return
    }
    Box(modifier) {
        AvatarImage(pubkey, picture, Modifier.size(Sizing.avatar), lookupProfile = lookupProfile)
        Box(
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 3.dp).size(16.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Repeat, contentDescription = null,
                tint = TextSecondary, modifier = Modifier.size(11.dp))
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
        style = AppTextStyles.author,
        maxLines   = 1,
        overflow   = TextOverflow.Ellipsis,
        modifier   = Modifier.weight(if (nostrAddress != null) 0.45f else 1f, fill = false).alignByBaseline(),
    )
    if (nostrAddress != null) {
        Spacer(Modifier.width(4.dp))
        SelfDeclaredNostrAddressText(
            pubkey = pubkey,
            presentation = nostrAddress,
            fontSize = AppType.footnote,
            modifier = Modifier.weight(0.55f, fill = false).alignByBaseline(),
        )
    }
}
