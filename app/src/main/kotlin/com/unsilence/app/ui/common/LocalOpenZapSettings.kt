package com.unsilence.app.ui.common

import androidx.compose.runtime.compositionLocalOf

/** App-wide trigger to open Zap settings. Provided at AppNavigation level. */
val LocalOpenZapSettings = compositionLocalOf<() -> Unit> { {} }
