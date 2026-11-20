package com.unsilence.app.ui.drafts

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unsilence.app.MainActivity
import com.unsilence.app.ui.navigation.AppDestination
import com.unsilence.app.ui.navigation.AppNavDisplay
import com.unsilence.app.ui.navigation.AppNavigator
import com.unsilence.app.ui.navigation.rememberAppNavigator
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DraftTransitionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private lateinit var navigator: AppNavigator

    private fun showCard(cardTop: Dp) {
        ui.runOnUiThread {
            ui.activity.setContent {
                val nav = rememberAppNavigator("motion-test")
                SideEffect { navigator = nav }
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("motion")) {
                    AppNavDisplay(nav) { entry ->
                        val isEditor = entry.destination is AppDestination.ResumeDraft
                        Box(Modifier.fillMaxWidth().padding(top = if (isEditor) 96.dp else cardTop)) {
                            Box(Modifier.fillMaxWidth().height(if (isEditor) 360.dp else 72.dp)
                                .draftBounds("draft").background(Color.Black)) {
                                // A band marks the first line in both layouts.
                                Box(Modifier.fillMaxWidth().padding(top = 36.dp).height(12.dp).background(Color.Red))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun firstLineStaysAtTheTopAsTheEditorExpandsAndCollapses() {
        showCard(96.dp)
        val surface = ui.onNodeWithTag("motion")
        val initial = surface.captureToImage().toPixelMap()
        val x = initial.width / 2
        val first = (0 until initial.height).first { initial[x, it].red > 0.8f }
        val center = first + with(ui.density) { 6.dp.roundToPx() }
        fun checkFrames() {
            // Sample the first line during resizing, before and after the destination takes over.
            for (step in listOf(64L, 48L, 48L, 64L, 64L, 80L)) {
                ui.mainClock.advanceTimeBy(step)
                val pixel = surface.captureToImage().toPixelMap()[x, center]
                assertTrue("First line moved away from y=$center during the transition (red=${pixel.red})",
                    pixel.red > 0.1f)
            }
            ui.mainClock.advanceTimeBy(500)
        }
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnIdle { navigator.push(AppDestination.ResumeDraft("test", "draft")) }
            checkFrames()
            ui.runOnIdle { navigator.pop() }
            checkFrames()
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

    @Test fun firstLineKeepsMovingThroughTheMiddleAndSettlesOnTime() {
        showCard(272.dp)
        val surface = ui.onNodeWithTag("motion")
        fun lineY(): Int {
            val pixels = surface.captureToImage().toPixelMap()
            return (0 until pixels.height).first { pixels[pixels.width / 2, it].red > 0.12f }
        }

        val start = lineY()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnIdle { navigator.push(AppDestination.ResumeDraft("test", "draft")) }
            ui.mainClock.advanceTimeBy(80)
            val early = lineY()
            ui.mainClock.advanceTimeBy(48)
            val middle = lineY()
            ui.mainClock.advanceTimeBy(48)
            val late = lineY()
            ui.mainClock.advanceTimeBy(80)
            val atEnd = lineY()
            ui.mainClock.advanceTimeBy(500)
            val settled = lineY()
            assertTrue("Expected continuous upward motion: $start, $early, $middle, $late",
                start > early && early > middle && middle > late)
            assertTrue("Text was still moving after the original opening duration: $atEnd vs $settled",
                abs(atEnd - settled) <= 2)
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }
}
