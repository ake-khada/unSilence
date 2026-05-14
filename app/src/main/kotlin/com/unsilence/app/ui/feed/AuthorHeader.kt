package com.unsilence.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

/**
 * Avatar + display name + NIP-05 badge + timestamp row.
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
) {
    val authorLabel = displayName
        ?: "${pubkey.take(6)}…${pubkey.takeLast(4)}"

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
                    modifier   = Modifier.weight(1f, fill = false),
                )
                if (!nip05.isNullOrBlank() && parseNip05(nip05) != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector        = Icons.Filled.Verified,
                        contentDescription = "NIP-05 verified",
                        tint               = Brand,
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
            text     = relativeTime(createdAt),
            color    = TextSecondary,
            fontSize = AppType.footnote,
            modifier = Modifier.clickable { onNoteClick() },
        )
    }
}
