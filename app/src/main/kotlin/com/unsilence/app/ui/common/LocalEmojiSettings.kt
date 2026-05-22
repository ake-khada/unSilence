package com.unsilence.app.ui.common

import androidx.compose.runtime.compositionLocalOf

/** App-wide trigger to open Settings → Custom Emojis. Provided at AppNavigation level. */
val LocalOpenEmojiSettings = compositionLocalOf<() -> Unit> { {} }
