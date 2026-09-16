package com.unsilence.app.ui.shared

import com.unsilence.app.data.memory.SensitiveContentMode

internal enum class SensitiveContentVisibility {
    VISIBLE,
    HIDDEN,
    REVEALABLE,
}

/** No platform-dependent preview: protected text, posters and players stay uncomposed. */
internal fun sensitiveContentVisibility(
    mode: SensitiveContentMode,
    sensitive: Boolean,
    revealed: Boolean,
): SensitiveContentVisibility = when {
    !sensitive || mode == SensitiveContentMode.SHOW -> SensitiveContentVisibility.VISIBLE
    mode == SensitiveContentMode.HIDE -> SensitiveContentVisibility.HIDDEN
    revealed -> SensitiveContentVisibility.VISIBLE
    else -> SensitiveContentVisibility.REVEALABLE
}
