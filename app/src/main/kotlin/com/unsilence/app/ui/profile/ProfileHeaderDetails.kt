package com.unsilence.app.ui.profile

import android.os.Build
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.feed.NostrRichText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.unsilence.app.ui.feed.toCompactSats
import com.unsilence.app.ui.theme.Text3
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.theme.AppTextStyles
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap

/** Copy-only metadata: no payment, verification, or network operation. */
@Composable
internal fun ProfileLightningAddress(address: String?, modifier: Modifier = Modifier) {
    if (address.isNullOrBlank()) return
    val clipboard = LocalClipboardManager.current
    val showSnackbar = LocalShowSnackbar.current
    Row(
        modifier = modifier.padding(horizontal = Spacing.large)
            .semantics { contentDescription = "Copy Lightning address: $address" }
            .clickable(role = Role.Button, onClickLabel = "Copy Lightning address") {
                clipboard.setText(AnnotatedString(address))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    showSnackbar("Lightning address copied")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.ElectricBolt, null, tint = Zap, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.micro))
        Text(
            text = address,
            style = AppTextStyles.bodySmall.copy(textDirection = TextDirection.Ltr),
            color = TextSecondary,
            maxLines = 1,
            // Single-line start ellipsis keeps the domain before sacrificing the local part.
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(Spacing.micro))
        Icon(Icons.Outlined.ContentCopy, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
    }
}

/** Known follow membership is social context, not an identity-verification badge. */
@Composable
internal fun ProfileDisplayName(
    name: String,
    isFollowing: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        // Balance the decorative marker so the name itself stays on the centerline.
        if (isFollowing) Spacer(Modifier.width(Spacing.micro + 16.dp))
        Text(
            name, color = Color.White, style = AppTextStyles.heading, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (isFollowing) {
            Spacer(Modifier.width(Spacing.micro))
            Icon(Icons.Default.Check, contentDescription = "Following", tint = TextSecondary,
                modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun ProfileSocialContext(
    verifiedFollowers: Long?,
    relationship: String?,
    modifier: Modifier = Modifier,
) {
    val label = listOfNotNull(
        verifiedFollowers?.let { "${it.toCompactSats()} verified in your grapevine" },
        relationship,
    ).joinToString(" | ")
    if (label.isEmpty()) return
    Text(label, color = Text3, style = AppTextStyles.caption,
        textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth())
}

/** Explicit removal confirmation belongs to this menu visit, not a separate dialog. */
@Composable
internal fun ProfileActionsMenu(
    subject: String?,
    state: ProfileFollowState,
    loading: Boolean,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onSetFollowing: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var confirmingUnfollow by remember(subject, state.isFollowing, expanded) { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest,
        modifier = Modifier.background(Color.Black)) {
        DropdownMenuItem(
            text = {
                Text(
                    when {
                        loading -> "Updating…"
                        state.isFollowing == null -> "Follow status loading"
                        state.isFollowing -> "Unfollow"
                        else -> state.actionLabel.orEmpty()
                    },
                    style = AppTextStyles.body,
                    color = TextSecondary,
                )
            },
            leadingIcon = {
                Icon(
                    if (state.isFollowing == true) Icons.Filled.PersonRemove else Icons.Filled.PersonAdd,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = {
                if (confirmingUnfollow) {
                    IconButton(
                        enabled = subject != null && !loading && state.isFollowing == true,
                        onClick = {
                            confirmingUnfollow = false
                            onDismissRequest()
                            onSetFollowing(false)
                        },
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Confirm unfollow",
                            tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                } else {
                    // Keep the popup width stable when confirmation appears.
                    Spacer(Modifier.size(48.dp))
                }
            },
            enabled = subject != null && !loading && state.isFollowing != null,
            onClick = {
                if (state.isFollowing == true) {
                    confirmingUnfollow = !confirmingUnfollow
                } else {
                    onDismissRequest()
                    onSetFollowing(true)
                }
            },
        )
        content()
    }

}

/** One spacing rhythm, including optional rows, shared by both profile variants. */
@Composable
internal fun ProfileHeaderStack(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** Full-width banner, centered vertically; request sizing stays with the caller. */
@Composable
internal fun ProfileBannerImage(model: ImageRequest, modifier: Modifier = Modifier) {
    // FillWidth otherwise makes Coil choose FILL decoding. Retain the previous FIT
    // decode size while drawing the resulting image across the viewport width.
    val request = remember(model) { model.newBuilder().scale(Scale.FIT).build() }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        alignment = Alignment.Center,
        modifier = modifier.background(MaterialTheme.colorScheme.background),
    )
}

/** Shared profile biography; the page owns scrolling and mention/navigation behavior. */
@Composable
internal fun ProfileBio(
    content: String,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    onAuthorClick: (String) -> Unit,
    onHashtagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NostrRichText(
        content = content,
        kind = 0,
        lookupProfile = lookupProfile,
        onAuthorClick = onAuthorClick,
        onHashtagClick = onHashtagClick,
        onTextClick = {},
        maxLines = Int.MAX_VALUE,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.medium),
    )
}
