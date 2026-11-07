package com.unsilence.app.ui.profile

import android.os.Build
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** Confirmation lives outside the popup so closing the menu cannot dispose it. */
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
    var confirmingUnfollow by remember(subject, state.isFollowing) { mutableStateOf(false) }
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
            enabled = subject != null && !loading && state.isFollowing != null,
            onClick = {
                onDismissRequest()
                if (state.isFollowing == true) confirmingUnfollow = true else onSetFollowing(true)
            },
        )
        content()
    }
    if (confirmingUnfollow) {
        AlertDialog(
            onDismissRequest = { confirmingUnfollow = false },
            title = { Text("Unfollow?") },
            text = { Text("Remove this account from your follow list?") },
            confirmButton = {
                TextButton(enabled = !loading, onClick = {
                    confirmingUnfollow = false
                    onSetFollowing(false)
                }) { Text("Unfollow") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnfollow = false }) { Text("Cancel") }
            },
        )
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

/** Whole banner, centered in its existing viewport; request sizing stays with the caller. */
@Composable
internal fun ProfileBannerImage(model: Any, modifier: Modifier = Modifier) {
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        alignment = Alignment.Center,
        modifier = modifier.background(MaterialTheme.colorScheme.background),
    )
}
