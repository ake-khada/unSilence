package com.unsilence.app.ui.common

import androidx.compose.runtime.compositionLocalOf

/** App-wide relay-detail navigation used by shared surfaces such as post actions. */
val LocalOpenRelayDetail = compositionLocalOf<(String) -> Unit> { {} }
