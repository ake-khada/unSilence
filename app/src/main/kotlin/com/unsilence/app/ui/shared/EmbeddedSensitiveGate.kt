package com.unsilence.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary

/**
 * NIP-36 sensitive-content gate for EMBEDDED targets (quoted notes, reposted
 * targets, thread parents). The top-level feed/card path gates the wrapper via
 * the FeedRow flag + EventCard; embedded targets are separately-resolved events
 * whose own [sensitive] flag must be honored here, or quoted/reposted NSFW
 * renders unguarded inside a non-sensitive wrapper.
 *
 *  - SHOW (or not sensitive): render [content] as-is.
 *  - HIDE: do NOT compose [content]; show a compact placeholder.
 *  - BLUR: blur [content] with tap-to-reveal.
 *
 * Note: like the top-level card blur, BLUR uses Modifier.blur — an actively
 * playing video SurfaceView won't blur (it shows a poster until active). HIDE is
 * the strong guarantee (content never composed).
 */
@Composable
fun EmbeddedSensitiveGate(
    mode: SensitiveContentMode,
    sensitive: Boolean,
    reason: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!sensitive || mode == SensitiveContentMode.SHOW) {
        content()
        return
    }
    when (mode) {
        SensitiveContentMode.HIDE -> SensitiveContentHiddenCard(reason, modifier)
        SensitiveContentMode.BLUR -> {
            var revealed by remember { mutableStateOf(false) }
            Box(modifier) {
                Box(modifier = if (!revealed) Modifier.blur(16.dp) else Modifier) {
                    content()
                }
                if (!revealed) {
                    Column(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { revealed = true }
                            .padding(Spacing.medium),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = reason?.takeIf { it.isNotBlank() } ?: "Sensitive content",
                            fontSize = AppType.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary,
                        )
                        Spacer(Modifier.height(Spacing.micro))
                        Text(
                            text = "Tap to reveal",
                            fontSize = AppType.caption,
                            color = TextSecondary.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
        SensitiveContentMode.SHOW -> content()
    }
}

/** Compact "Sensitive content hidden" row — used for HIDE mode by the embedded
 *  gate and by EventCard for top-level posts on non-feed surfaces. */
@Composable
fun SensitiveContentHiddenCard(reason: String?, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariant)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Icon(
            imageVector = Icons.Outlined.VisibilityOff,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.small))
        Text(
            text = reason?.takeIf { it.isNotBlank() }?.let { "Sensitive content hidden — $it" }
                ?: "Sensitive content hidden",
            color = TextSecondary,
            fontSize = AppType.footnote,
            fontWeight = FontWeight.Medium,
        )
    }
}
