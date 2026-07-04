package com.unsilence.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.StateFlow

/**
 * Kind-6 repost header: "X boosted · Nh ago" with mini avatar.
 *
 * Shows the wrapper's author (sourcePubkey) and timestamp (sourceCreatedAt).
 * Tapping navigates to the boosted note.
 */
@Composable
internal fun RepostHeader(
    sourcePubkey: String,
    sourceCreatedAt: Long,
    sourceProfile: UserEntity?,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    profileFlow: ((String) -> StateFlow<UserEntity?>)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reposterLabel = sourceProfile?.displayName?.takeIf { it.isNotBlank() }
        ?: sourceProfile?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
        ?: "${sourcePubkey.take(6)}…${sourcePubkey.takeLast(4)}"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = Spacing.medium)
            .padding(top = Spacing.micro),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.Repeat,
            contentDescription = null,
            tint               = TextSecondary,
            modifier           = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Spacing.micro))
        AvatarImage(
            pubkey        = sourcePubkey,
            picture       = sourceProfile?.picture,
            modifier      = Modifier.size(16.dp),
            sizeDp        = 16.dp,
            lookupProfile = lookupProfile,
            profileFlow   = profileFlow,
        )
        Spacer(Modifier.width(Spacing.micro))
        Text(
            text     = "$reposterLabel boosted · ${relativeTime(sourceCreatedAt)}",
            color    = TextSecondary,
            fontSize = AppType.footnote,
        )
    }
}
