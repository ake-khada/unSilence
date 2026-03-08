package com.unsilence.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Golden ratio spacing system: 360dp / φ^n
object Spacing {
    val micro  =  5.dp   // φ⁹ — icon gaps, inline elements
    val small  =  8.dp   // φ⁸ — card gaps, corner radius, vertical padding
    val medium = 12.dp   // φ⁷ — side padding, horizontal card padding
    val large  = 20.dp   // φ⁶ — icon sizes
    val xl     = 32.dp   // φ⁵ — avatars
    val xxl    = 52.dp   // φ⁴ — bar heights
}

object Sizing {
    val avatar           = 32.dp
    val actionIcon       = 16.dp
    val navIcon          = 20.dp   // φ⁶ — bottom nav icons
    val topBarHeight     = 52.dp
    val bottomNavHeight  = 52.dp
    val mediaCornerRadius = 8.dp
}

private val UnsilenceColorScheme = darkColorScheme(
    primary          = Cyan,
    onPrimary        = Black,
    primaryContainer = CyanDark,
    secondary        = CyanDark,
    onSecondary      = Black,
    background       = Black,
    onBackground     = White,
    surface          = Black,
    onSurface        = White,
    surfaceVariant   = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error            = Color(0xFFCF6679),
)

@Composable
fun UnsilenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UnsilenceColorScheme,
        content = content,
    )
}
