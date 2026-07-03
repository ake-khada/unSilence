package com.unsilence.app.ui.common

import androidx.compose.runtime.compositionLocalOf

/** Current login-session key. Use it to keep Hilt ViewModels account-scoped. */
val LocalAppSessionKey = compositionLocalOf { "anonymous" }
