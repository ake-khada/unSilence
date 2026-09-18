package com.unsilence.app.ui.shared

import com.unsilence.app.ui.theme.AppTextStyles
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary

/**
 * NIP-36 gate shared by event cards and embedded targets. Callers supply their
 * own trusted warning flag; revealing a wrapper must not reveal a sensitive quote.
 *
 *  - SHOW (or not sensitive): render [content] as-is.
 *  - HIDE: do NOT compose [content]; show a compact placeholder.
 *  - BLUR (legacy persisted name): opaque placeholder with tap-to-reveal.
 *
 * Content stays out of composition until allowed, on every Android version.
 * Consent belongs to this content identity and mode, not a recycled card slot.
 * Tap-to-reveal keeps its minimum footprint after reveal; taller content can grow.
 */
@Composable
fun SensitiveContentGate(
    contentKey: String,
    mode: SensitiveContentMode,
    sensitive: Boolean,
    reason: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    var revealed by remember(contentKey, mode, sensitive) { mutableStateOf(false) }
    val minimumHeight = if (sensitive && mode == SensitiveContentMode.BLUR) {
        sensitiveContentMinimumHeight(compact)
    } else {
        0.dp
    }
    when (sensitiveContentVisibility(mode, sensitive, revealed)) {
        SensitiveContentVisibility.VISIBLE -> {
            if (minimumHeight > 0.dp) {
                // Do not squeeze short revealed notes back into a tiny row.
                // Column also preserves source order for slots with several roots.
                Column(Modifier.fillMaxWidth().heightIn(min = minimumHeight)) { content() }
            } else {
                content()
            }
        }
        SensitiveContentVisibility.HIDDEN -> SensitiveContentHiddenCard(reason, modifier)
        SensitiveContentVisibility.REVEALABLE -> SensitiveContentRevealCard(
            reason = reason,
            onReveal = { revealed = true },
            // Apply the minimum outside caller padding so reveal retains the
            // same total footprint instead of losing the padding's height.
            modifier = Modifier.heightIn(min = minimumHeight).then(modifier),
            compact = compact,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun sensitiveContentMinimumHeight(compact: Boolean): Dp {
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val systemBars = WindowInsets.systemBarsIgnoringVisibility
    return with(density) {
        sensitiveContentMinimumHeight(
            windowHeight = windowHeight,
            systemTopInset = systemBars.getTop(this).toDp(),
            systemBottomInset = systemBars.getBottom(this).toDp(),
            compact = compact,
        )
    }
}

/** Opaque, accessible replacement for the content, never an overlay over live media. */
@Composable
internal fun SensitiveContentRevealCard(
    reason: String?,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
            .background(Surface1)
            .clickable(role = Role.Button, onClickLabel = "Reveal sensitive content", onClick = onReveal)
            .padding(if (compact) Spacing.medium else Spacing.large),
    ) {
        Icon(
            imageVector = Icons.Outlined.VisibilityOff,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(if (compact) 20.dp else 24.dp),
        )
        Text(
            text = reason?.takeIf { it.isNotBlank() } ?: "Sensitive content",
            color = TextSecondary,
            fontSize = if (compact) AppType.bodySmall else AppType.body,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Tap to reveal",
            color = Brand,
            fontSize = if (compact) AppType.footnote else AppType.bodySmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

/** Compact "Sensitive content hidden" row — used for HIDE mode by the embedded
 *  gate and by EventCard for top-level posts on non-feed surfaces. */
@Composable
fun SensitiveContentHiddenCard(reason: String?, modifier: Modifier = Modifier) {
    ContentHiddenCard(
        text = reason?.takeIf { it.isNotBlank() }?.let { "Sensitive content hidden — $it" }
            ?: "Sensitive content hidden",
        modifier = modifier,
    )
}

/** Structure-preserving placeholder for a muted reply or comment. */
@Composable
fun MutedContentHiddenCard(modifier: Modifier = Modifier) {
    ContentHiddenCard(text = "Muted content hidden", modifier = modifier)
}

/** Reversible summary for a high-confidence coordinated-spam reply cluster. */
@Composable
fun LikelySpamClusterCard(
    replyCount: Int,
    revealed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariant)
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Icon(
            imageVector = Icons.Outlined.VisibilityOff,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Likely coordinated spam",
                color = TextSecondary,
                style = AppTextStyles.footnote,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "$replyCount ${if (replyCount == 1) "reply" else "replies"} " +
                    if (revealed) "shown" else "hidden",
                color = TextSecondary.copy(alpha = 0.7f),
                style = AppTextStyles.caption,
            )
        }
        Spacer(Modifier.width(Spacing.small))
        Text(
            text = if (revealed) "Hide" else "Show",
            color = Brand,
            style = AppTextStyles.footnote,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ContentHiddenCard(text: String, modifier: Modifier) {
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
            text = text,
            color = TextSecondary,
            style = AppTextStyles.footnote,
            fontWeight = FontWeight.Medium,
        )
    }
}
