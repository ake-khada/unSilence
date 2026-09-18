package com.unsilence.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Shared geometry; surface-specific color/emphasis stays at the call site. */
object AppTextStyles {
    val caption = TextStyle(fontSize = AppType.caption, lineHeight = 16.sp)
    val footnote = TextStyle(fontSize = AppType.footnote, lineHeight = 16.sp)
    val bodySmall = TextStyle(fontSize = AppType.bodySmall, lineHeight = 18.sp)
    val body = TextStyle(fontSize = AppType.body, lineHeight = 20.sp)
    val bodyLarge = TextStyle(fontSize = AppType.bodyLarge, lineHeight = 22.sp)
    val subheading = TextStyle(fontSize = AppType.subheading, lineHeight = 24.sp)
    val heading = TextStyle(fontSize = AppType.heading, lineHeight = 26.sp)
    val title = TextStyle(fontSize = AppType.title, lineHeight = 30.sp)

    internal val author = body.copy(fontWeight = FontWeight.SemiBold)
    internal val metadata = caption.copy(fontSize = 10.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
}
