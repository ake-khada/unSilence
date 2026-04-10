package com.unsilence.app.ui.common

import androidx.compose.runtime.compositionLocalOf

/** App-wide snackbar trigger. Provided at AppNavigation level. */
val LocalShowSnackbar = compositionLocalOf<(String) -> Unit> { {} }
