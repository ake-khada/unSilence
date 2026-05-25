package com.unsilence.app.ui.common

import androidx.compose.runtime.compositionLocalOf
import com.unsilence.app.data.wallet.ZapPreferences

val LocalZapPreferences = compositionLocalOf { ZapPreferences.DEFAULT }
