package com.unsilence.app.ui.common

import androidx.compose.runtime.compositionLocalOf

/** App-wide snackbar trigger. Scoped to the current account or onboarding session. */
val LocalShowSnackbar = compositionLocalOf<(String) -> Unit> { {} }
