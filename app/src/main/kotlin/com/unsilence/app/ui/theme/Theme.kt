package com.unsilence.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Golden ratio spacing system: 360dp / φ^n
object Spacing {
    val micro  =  5.dp   // φ⁹ — icon gaps, inline elements
    val small  =  8.dp   // φ⁸ — card gaps, corner radius, vertical padding
    val medium = 12.dp   // φ⁷ — side padding, horizontal card padding
    val large  = 20.dp   // φ⁶ — icon sizes
    val xl     = 32.dp   // φ⁵ — avatars
    val xxl    = 52.dp   // φ⁴ — bar heights
}

// Typography scale — named sizes replacing ad-hoc sp values
object AppType {
    val zapBadge    =  8.sp  // zap sats overlay inside avatars
    val caption     = 11.sp  // section labels, NIP-05 domains, tiny metadata
    val footnote    = 12.sp  // timestamps, secondary info
    val bodySmall   = 13.sp  // compact body: author names, action counts
    val body        = 14.sp  // standard body text, buttons, summaries
    val bodyLarge   = 15.sp  // input fields, emphasized body
    val subheading  = 16.sp  // card titles, section headers
    val heading     = 18.sp  // profile display names
    val title       = 22.sp  // article reader titles
    val display     = 24.sp  // onboarding hero text
}

object Sizing {
    val avatar            = 32.dp
    val actionIcon        = 20.dp
    val navIcon           = 20.dp   // φ⁶ — top bar action icons
    val topBarHeight      = 52.dp
    val bottomNavHeight   = 52.dp
    val mediaCornerRadius = 8.dp
}

private val UnsilenceColorScheme = darkColorScheme(
    primary          = Brand,
    onPrimary        = Black,
    primaryContainer = BrandDeep,
    secondary        = BrandDeep,
    onSecondary      = Black,
    background       = Black,
    onBackground     = White,
    surface          = Surface1,
    onSurface        = White,
    surfaceVariant   = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    // Map M3 container roles to our depth tokens so stock components (dialogs,
    // bottom sheets, menus, cards) render deliberate near-black depth instead of
    // M3's default gray baseline — and aren't accidental pure black either.
    surfaceContainerLowest  = Surface0,
    surfaceContainerLow     = Surface1,
    surfaceContainer        = Surface1,
    surfaceContainerHigh    = Surface2,
    surfaceContainerHighest = BgElev3,
    surfaceDim              = Black,
    surfaceBright           = Surface2,
    error            = Color(0xFFCF6679),
)

@Composable
fun UnsilenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UnsilenceColorScheme,
        content = content,
    )
}
