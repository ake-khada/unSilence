package com.unsilence.app.ui.shared

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing

/**
 * One third of the usable feed window, with a readable minimum in small windows.
 * Both app bars are reserved even when off-screen: their scroll animation must
 * never resize a placeholder. Embedded cards use 90% of that same base, not a
 * separate fixed size or a reduction compounded at each nesting level.
 * No hidden content is measured to choose this size.
 */
internal fun sensitiveContentMinimumHeight(
    windowHeight: Dp,
    systemTopInset: Dp,
    systemBottomInset: Dp,
    compact: Boolean,
): Dp {
    val usableHeight = windowHeight - systemTopInset - systemBottomInset -
        Sizing.feedTopBarHeight - Sizing.bottomNavHeight
    val normalMinimum = usableHeight / 3f
    val relativeMinimum = if (compact) normalMinimum * 0.9f else normalMinimum
    return relativeMinimum.coerceAtLeast(96.dp)
}

internal val CardRole.usesCompactSensitivePlaceholder: Boolean
    get() = this == CardRole.Embedded || this == CardRole.EmbeddedArticle

/** Ordinary posts and resolved reposts share gutters; embedded containers own theirs. */
internal fun Modifier.sensitiveContentPlaceholderPadding(role: CardRole): Modifier =
    if (role.usesCompactSensitivePlaceholder) this
    else padding(horizontal = Spacing.medium, vertical = Spacing.small)
