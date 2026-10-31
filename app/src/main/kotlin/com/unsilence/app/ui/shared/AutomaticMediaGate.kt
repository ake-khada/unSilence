package com.unsilence.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.unsilence.app.data.media.MediaLoadPermissions
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.theme.AppTextStyles
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.TextSecondary

val LocalMediaLoadPermissions = compositionLocalOf { MediaLoadPermissions() }

/** Explicit consent is local to this composed content and account session. */
@Composable
internal fun AutomaticMediaGate(
    contentKey: Any,
    preview: Boolean = false,
    label: String? = null,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
    content: @Composable () -> Unit,
) {
    val permissions = LocalMediaLoadPermissions.current
    var requested by remember(contentKey, LocalAppSessionKey.current) { mutableStateOf(false) }
    val shown = rememberMediaAdmission(contentKey, if (preview) permissions.previews else permissions.images)
    if (requested || shown) {
        // An explicitly loaded preview owns its thumbnail, including direct-image previews.
        CompositionLocalProvider(LocalMediaLoadPermissions provides permissions.copy(
            images = true,
            previews = if (preview) true else permissions.previews,
        ), content = content)
    } else {
        Box(modifier.fillMaxWidth().aspectRatio(aspectRatio).background(Surface1).clickable { requested = true }, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(Spacing.large), horizontalAlignment = Alignment.CenterHorizontally) {
                if (label != null) Text(label, style = AppTextStyles.bodySmall.copy(textDirection = TextDirection.Ltr),
                    color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (preview) "Load link preview" else "Load image", style = AppTextStyles.body, color = TextSecondary)
            }
        }
    }
}

/** Admission, not revocation: retain already displayed media through a policy downgrade. */
@Composable
internal fun rememberMediaAdmission(contentKey: Any, allowedNow: Boolean): Boolean {
    var admitted by remember(contentKey, LocalAppSessionKey.current) { mutableStateOf(allowedNow) }
    SideEffect { if (allowedNow && !admitted) admitted = true }
    return admitted || allowedNow
}
