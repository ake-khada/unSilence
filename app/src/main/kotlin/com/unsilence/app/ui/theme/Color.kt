package com.unsilence.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Brand + accents ─────────────────────────────────────────────────────
val Brand     = Color(0xFF00E5FF)
val BrandDeep = Color(0xFF00B8D4)
val BrandSoft = Brand.copy(alpha = 0.14f)   // chips, hover wash
val BrandGlow = Brand.copy(alpha = 0.32f)   // shadows

// ── Neutrals ────────────────────────────────────────────────────────────
val Black = Color(0xFF000000)
val White = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF888888)
val SurfaceVariant = Color(0xFF080808)
// Small text must remain readable on the app's black/surface backgrounds.
// Text3 (#848484) is 5.62:1 on black and 4.93:1 on Surface2; Text4 (#808080)
// is 5.32:1 and 4.67:1 respectively. Both clear WCAG AA's 4.5:1 threshold on
// normal card/sheet surfaces. Hairlines use the dedicated Border* tokens below
// instead of borrowing a text color.
val Text3 = Color(0xFF848484)    // tertiary / metadata
val Text4 = Color(0xFF808080)    // disabled / lowest-emphasis text

// ── Semantic ────────────────────────────────────────────────────────────
val Zap  = Color(0xFFFFAB00)     // sats / warnings
val Mint = Color(0xFF3DDC97)     // healthy / mutuals / success
val Like = Color(0xFFFF3870)     // hearts / errors
val Warn = Color(0xFFFF7849)

// ── Surface depth system (AMOLED) ───────────────────────────────────────
/** Screen backgrounds, nav bars — true black for AMOLED pixel-off. */
val Surface0 = Color(0xFF000000)
/** Cards, containers, input fields — barely visible lift. */
val Surface1 = Color(0xFF0A0A0A)
/** Elevated: modals, sheets, shimmer highlights — subtle depth. */
val Surface2 = Color(0xFF141414)
/** Deep-elevated: nested modals, stacked overlays. */
val BgElev3 = Color(0xFF1E1E1E)

// ── Borders ─────────────────────────────────────────────────────────────
val BorderFaint   = Color(0x0DFFFFFF)   // ~5% white — hairline separators
val BorderSubtle  = Color(0x14FFFFFF)   // ~8% white — input outlines at rest
val BorderDefault = Color(0x1FFFFFFF)   // ~12% white — focused inputs, outlined buttons

// ── Aliases (migration shims — old name → new token) ────────────────────
val BgBase  = Black
val BgElev1 = Surface1
val BgElev2 = Surface2
val Text1   = White
val Text2   = TextSecondary
