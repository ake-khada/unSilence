package com.unsilence.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.ui.theme.PressTint
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel/phase assertions only; does not substitute for human gesture feel. */
@RunWith(AndroidJUnit4::class)
class DrawFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingLogoScaleDrawsWithoutRecomposingOrResizingItsParent() {
        val scale = mutableFloatStateOf(1f)
        var compositions = 0
        var sizes = 0
        compose.setContent {
            UnsilenceTheme {
                SideEffect { compositions++ }
                LogoMark(
                    modifier = Modifier.testTag("logo").background(Color.Black)
                        .onSizeChanged { sizes++ },
                    sizeDp = 200.dp, static = true, color = Color.White,
                    barHeightScale = { scale.floatValue },
                )
            }
        }
        fun barPixel(): Float {
            val pixels = compose.onNodeWithTag("logo").captureToImage().toPixelMap()
            // Within the tallest bar horizontally; only its extended height
            // covers this point after scale changes from 1.0 to 1.6.
            return pixels[(pixels.width * .61f).toInt(), (pixels.height * .23f).toInt()].red
        }
        assertTrue(barPixel() < .01f)
        val before = compose.runOnIdle { compositions to sizes }
        compose.runOnIdle { scale.floatValue = 1.6f }
        assertTrue(barPixel() > .99f)
        compose.runOnIdle { assertEquals(before, compositions to sizes) }
    }

    @Test fun pressTintIsBoundedAndClearsOnCancelAndRelease() {
        val interactions = MutableInteractionSource()
        compose.setContent {
            UnsilenceTheme {
                Box(Modifier.testTag("press").size(80.dp).background(Color.Black)
                    .indication(interactions, PressTint))
            }
        }
        fun center(): Color {
            val pixels = compose.onNodeWithTag("press").captureToImage().toPixelMap()
            return pixels[pixels.width / 2, pixels.height / 2]
        }
        assertTrue(center().red < .01f)
        val cancelled = PressInteraction.Press(Offset.Zero)
        compose.runOnIdle { assertTrue(interactions.tryEmit(cancelled)) }
        val tint = center()
        assertEquals(.08f, tint.red, .01f)
        assertEquals(tint.red, tint.green, .01f)
        assertEquals(tint.red, tint.blue, .01f)
        compose.runOnIdle { assertTrue(interactions.tryEmit(PressInteraction.Cancel(cancelled))) }
        assertTrue(center().red < .01f)
        val released = PressInteraction.Press(Offset.Zero)
        compose.runOnIdle { assertTrue(interactions.tryEmit(released)) }
        assertTrue(center().red > .05f)
        compose.runOnIdle { assertTrue(interactions.tryEmit(PressInteraction.Release(released))) }
        assertTrue(center().red < .01f)
    }
}
