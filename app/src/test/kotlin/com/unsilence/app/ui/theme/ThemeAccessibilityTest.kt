package com.unsilence.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeAccessibilityTest {
    private fun contrastRatio(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun `lowest emphasis text clears AA contrast on app surfaces`() {
        for (background in listOf(Black, Surface1, Surface2)) {
            assertTrue(contrastRatio(Text3, background) >= 4.5f)
            assertTrue(contrastRatio(Text4, background) >= 4.5f)
        }
    }

    @Test
    fun `material typography uses app scale`() {
        assertEquals(AppType.body, UnsilenceTypography.bodyMedium.fontSize)
        assertEquals(AppType.subheading, UnsilenceTypography.titleMedium.fontSize)
        assertEquals(AppType.caption, UnsilenceTypography.labelSmall.fontSize)
    }
}
