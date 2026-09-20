package com.unsilence.app.ui.navigation

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.domain.model.GlobalFeedLens
import com.unsilence.app.ui.notifications.NotifFilter
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Account-free geometry/semantics tests. Human gestures remain the final UX gate. */
@RunWith(AndroidJUnit4::class)
class HeaderControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completeTextAndJunctionGroupRemainsCentered() {
        val sample = mutableStateOf(0)
        var densityScale = 1f
        compose.setContent {
            densityScale = LocalDensity.current.density
            UnsilenceTheme {
                HeaderFrame(Brand, false, false,
                    start = { Box(Modifier.size(52.dp)) },
                    center = {
                        when (sample.value) {
                            0 -> FeedSourcePill("Following", {})
                            1 -> FeedSourcePill("primus.nostr1.com", {})
                            2 -> FeedSourcePill("trending.relays.land", {})
                            5 -> FeedSourcePill("Global", {}, lens = GlobalFeedLens.TRUSTED)
                            6 -> FeedSourcePill("Global", {}, lens = GlobalFeedLens.RAW)
                            else -> NotificationFilterPill(
                                if (sample.value == 3) NotifFilter.Global else NotifFilter.Following,
                                false, false, {},
                            )
                        }
                    },
                    end = {},
                    modifier = Modifier.width(360.dp).background(Color.Black).testTag("header"),
                )
            }
        }
        repeat(7) { index ->
            compose.runOnIdle { sample.value = index }
            val description = if (index in 3..4) "Notification filter" else "Feed source"
            val pill = compose.onNodeWithContentDescription(description)
            val bounds = pill.fetchSemanticsNode().boundsInRoot
            val header = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
            assertEquals(header.center.x, bounds.center.x, 1f)
            assertEquals(header.center.y, bounds.center.y, 1f)
            val pixels = pill.captureToImage().toPixelMap()
            // Owner's clarified contract: center the entire visible text+icon group,
            // not the text alone. Include secondary icons/shield, exclude dim border.
            val contentColumns = (0 until pixels.width).filter { x ->
                (0 until pixels.height).any { y ->
                    val pixel = pixels[x, y]
                    val brightest = maxOf(pixel.red, pixel.green, pixel.blue)
                    val darkest = minOf(pixel.red, pixel.green, pixel.blue)
                    // The orange border over its tinted fill can exceed .5f. Count
                    // neutral text/icon ink or bright shield ink, not that outline.
                    pixel.alpha > .9f &&
                        ((brightest - darkest < .05f && brightest > .5f) || brightest > .75f)
                }
            }
            assertTrue("No visible contents for sample $index", contentColumns.isNotEmpty())
            val textColumns = (0 until pixels.width).filter { x ->
                (0 until pixels.height).any { y ->
                    val pixel = pixels[x, y]
                    pixel.alpha > .9f && minOf(pixel.red, pixel.green, pixel.blue) > .8f
                }
            }
            assertTrue("No visible label for sample $index", textColumns.isNotEmpty())
            val leading = contentColumns.first()
            val trailing = pixels.width - 1 - contentColumns.last()
            Log.i("HeaderGeometryTest", "sample=$index leading=$leading trailing=$trailing density=$densityScale")
            assertTrue("Sample $index: content gaps leading=$leading trailing=$trailing density=$densityScale",
                abs(leading - trailing) <= densityScale + 1f)
            assertTrue("Sample $index: outline leaked into ink bounds", minOf(leading, trailing) >= 9 * densityScale)
            // The icon must still be visible and contained in that right-hand space.
            val iconStart = textColumns.last() + (2 * densityScale).toInt()
            val iconEnd = pixels.width - (8 * densityScale).toInt()
            assertTrue("Missing trailing icon for sample $index",
                (iconStart until iconEnd).any { x ->
                    (0 until pixels.height).any { y ->
                        val pixel = pixels[x, y]
                        pixel.alpha > .9f &&
                            maxOf(pixel.red, pixel.green, pixel.blue) in .35f.. .8f
                    }
                })
        }
    }

    @Test fun onlyTheGlobalShieldCarriesLensColorAndTheLabelBackingIsBlack() {
        val lens = mutableStateOf(GlobalFeedLens.TRUSTED)
        val line = mutableStateOf(Brand)
        var clicks = 0
        var densityScale = 1f
        compose.setContent {
            densityScale = LocalDensity.current.density
            UnsilenceTheme {
                HeaderFrame(line.value, false, false,
                    start = { Box(Modifier.size(52.dp)) },
                    center = { FeedSourcePill("Global", { clicks++ }, lens = lens.value) },
                    end = {},
                    modifier = Modifier.width(360.dp).background(Color.Black).testTag("header"),
                )
            }
        }
        val pill = compose.onNodeWithContentDescription("Feed source")
        val bounds = pill.fetchSemanticsNode().boundsInRoot
        for (mode in GlobalFeedLens.entries) {
            compose.runOnIdle { lens.value = mode }
            pill.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "Global, ${globalLensDescription(mode)}"))
            assertEquals(bounds, pill.fetchSemanticsNode().boundsInRoot)
            val accent = globalLensAccent(mode)
            val fill = Color.Black
            repeat(2) {
                val pixels = pill.captureToImage().toPixelMap()
                for (x in listOf(1, 8)) {
                    // The backing is black, never a lens-tinted container.
                    val inside = pixels[x, pixels.height / 2]
                    assertEquals(1f, inside.alpha, .01f)
                    assertEquals(fill.red, inside.red, .01f)
                    assertEquals(fill.green, inside.green, .01f)
                    assertEquals(fill.blue, inside.blue, .01f)
                }
                // All colored pixels must belong to the shield, not a glow,
                // outline, label, chevron or line connection.
                for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
                    val p = pixels[x, y]
                    if (maxOf(p.red, p.green, p.blue) - minOf(p.red, p.green, p.blue) > .1f) {
                        assertTrue("Lens color escaped shield: x=$x y=$y", x < 22 * densityScale)
                    }
                }
                // Opaque shield ink within the leading slot.
                assertTrue("Missing $mode shield", ((10 * densityScale).toInt() until (21 * densityScale).toInt()).any { x ->
                    (0 until pixels.height).any { y ->
                        val pixel = pixels[x, y]
                        abs(pixel.red - accent.red) < .03f &&
                            abs(pixel.green - accent.green) < .03f && abs(pixel.blue - accent.blue) < .03f
                    }
                })
                compose.runOnIdle { line.value = Color.Magenta }
            }
            pill.performClick()
            assertEquals(mode, lens.value) // The capsule opens the sheet; it must not toggle filtering.
        }
        assertEquals(2, clicks)
    }

    @Test fun globalSelectionAndLensToggleHaveIndependentActionsAndAccessibleState() {
        val lens = mutableStateOf(GlobalFeedLens.TRUSTED)
        val selected = mutableStateOf(false)
        var selections = 0
        var lensChanges = 0
        compose.setContent {
            UnsilenceTheme {
                GlobalFeedSelectorRow(selected.value, lens.value,
                    onSelect = { selections++; selected.value = true },
                    onLensChanged = { lensChanges++; lens.value = it },
                    modifier = Modifier.width(360.dp),
                )
            }
        }
        val mode = compose.onNodeWithContentDescription("Global mode")
        val source = compose.onNodeWithText("Global")
        for (isSelected in listOf(false, true)) {
            compose.runOnIdle { selected.value = isSelected }
            repeat(2) { index ->
                val expected = if (index == 0) GlobalFeedLens.RAW else GlobalFeedLens.TRUSTED
                assertEquals("Switch Global to ${globalLensDescription(expected)}",
                    mode.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
                mode.performClick()
                mode.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                    globalLensDescription(expected)))
                assertEquals(0, selections)
                assertEquals(isSelected, selected.value)
                assertEquals(expected, lens.value)
            }
        }
        assertEquals(4, lensChanges)
        source.performClick()
        assertEquals(1, selections)
        assertEquals(4, lensChanges)
        assertEquals(GlobalFeedLens.TRUSTED, lens.value)
    }

    @Test fun globalSheetTargetsStaySeparateAtLargeFontSizesOnNarrowScreens() {
        val lens = mutableStateOf(GlobalFeedLens.TRUSTED)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                UnsilenceTheme {
                    GlobalFeedSelectorRow(true, lens.value, {}, { lens.value = it },
                        Modifier.width(320.dp).testTag("row"))
                }
            }
        }
        repeat(2) {
            val row = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
            val source = compose.onNodeWithText("Global").fetchSemanticsNode().boundsInRoot
            val mode = compose.onNodeWithContentDescription("Global mode")
            val toggle = mode.fetchSemanticsNode().boundsInRoot
            val scale = row.width / 320f
            assertTrue(source.left >= row.left && toggle.right <= row.right)
            assertTrue(source.right <= toggle.left)
            assertTrue(source.height + 1 >= 48 * scale)
            assertTrue(toggle.height + 1 >= 48 * scale)
            assertTrue(source.top >= row.top && source.bottom <= row.bottom)
            assertTrue(toggle.top >= row.top && toggle.bottom <= row.bottom)
            mode.performClick()
        }
    }

    @Test fun globalModeIsPlainTextWithOnlyItsShieldColoredAndAFullTouchTarget() {
        val lens = mutableStateOf(GlobalFeedLens.TRUSTED)
        var densityScale = 1f
        compose.setContent {
            densityScale = LocalDensity.current.density
            UnsilenceTheme {
                GlobalFeedSelectorRow(false, lens.value, {}, { lens.value = it },
                    Modifier.width(360.dp).background(Color.Black))
            }
        }
        val mode = compose.onNodeWithContentDescription("Global mode")
        repeat(2) {
            val target = mode.fetchSemanticsNode().boundsInRoot
            assertTrue(target.height + 1f >= 48f * densityScale)
            assertTrue(target.width + 1f >= 48f * densityScale)
            val pixels = mode.captureToImage().toPixelMap()
            // The unselected row is black; only the shield may have colored ink.
            val coloredRows = (0 until pixels.height).filter { y ->
                (0 until pixels.width).any { x ->
                    val p = pixels[x, y]
                    maxOf(p.red, p.green, p.blue) - minOf(p.red, p.green, p.blue) > .04f
                }
            }
            assertTrue(coloredRows.isNotEmpty())
            val coloredHeight = coloredRows.last() - coloredRows.first() + 1
            assertTrue("Colored container remains", coloredHeight <= 14f * densityScale + 2f)
            for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
                val p = pixels[x, y]
                if (maxOf(p.red, p.green, p.blue) - minOf(p.red, p.green, p.blue) > .04f) {
                    assertTrue("Mode label/backing was tinted", x < 23 * densityScale)
                }
            }
            assertTrue(coloredRows.first() >= 9f * densityScale)
            assertTrue(pixels.height - 1 - coloredRows.last() >= 9f * densityScale)
            mode.performClick()
        }
    }

    @Test fun selectorJunctionFlipsItsPointWithoutMovingTheLineAnchors() {
        val expanded = mutableStateOf(false)
        compose.setContent {
            HeaderJunction(HeaderJunctionKind.SELECTOR, expanded.value, true,
                Modifier.background(Color.Black).testTag("junction"))
        }
        val junction = compose.onNodeWithTag("junction")
        val bounds = junction.fetchSemanticsNode().boundsInRoot
        val closed = junction.captureToImage().toPixelMap()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { expanded.value = true }
        compose.mainClock.advanceTimeBy(80)
        assertEquals(bounds, junction.fetchSemanticsNode().boundsInRoot)
        compose.mainClock.advanceTimeBy(200)
        val opened = junction.captureToImage().toPixelMap()
        for (pixels in listOf(closed, opened)) {
            val center = pixels.height / 2
            for (x in listOf(0, pixels.width - 1)) {
                assertTrue("Line anchor moved", (center - 1..center + 1).any { y -> pixels[x, y].red > .2f })
            }
        }
        fun tipY(pixels: androidx.compose.ui.graphics.PixelMap): Float {
            val rows = (0 until pixels.height).filter { y -> pixels[pixels.width / 2, y].red > .2f }
            assertTrue(rows.isNotEmpty())
            return rows.average().toFloat()
        }
        assertTrue(tipY(closed) > closed.height / 2f + 2)
        assertTrue(tipY(opened) < opened.height / 2f - 2)
        assertEquals(bounds, junction.fetchSemanticsNode().boundsInRoot)
        compose.mainClock.autoAdvance = true
    }

    @Test fun junctionConnectionReachesTheLineForBothNotificationLabelsAndFeed() {
        val sample = mutableStateOf(0)
        var scale = 1f
        compose.setContent {
            scale = LocalDensity.current.density
            UnsilenceTheme {
                Box(Modifier.width(360.dp).background(Color.Black)) {
                    if (sample.value == 0) {
                        FeedSourcePill("Following", {})
                    } else {
                        NotificationFilterPill(
                            if (sample.value == 1) NotifFilter.Global else NotifFilter.Following,
                            false, false, {},
                        )
                    }
                }
            }
        }
        repeat(3) { index ->
            compose.runOnIdle { sample.value = index }
            val pixels = compose.onNodeWithContentDescription(
                if (index == 0) "Feed source" else "Notification filter",
            ).captureToImage().toPixelMap()
            // No persistent top/bottom border or capsule fill.
            for (x in 0 until pixels.width) {
                assertEquals(0f, pixels[x, (10 * scale).toInt()].red, .01f)
                assertEquals(0f, pixels[x, pixels.height - 1 - (10 * scale).toInt()].red, .01f)
            }
            // The final connecting stroke must reach the right edge with no gap.
            for (x in pixels.width - (9 * scale).toInt() until pixels.width) {
                val center = pixels.height / 2
                assertTrue("Broken connection in sample $index at x=$x",
                    (center - 2..center + 2).any { y -> pixels[x, y].red > .04f })
            }
        }
    }

    @Test fun crossfadeKeepsGeometryAndExposesOnlyTheCurrentSelection() {
        val filter = mutableStateOf(NotifFilter.Global)
        compose.setContent {
            UnsilenceTheme {
                NotificationHeader(filter.value, false, true, {}, Modifier.width(360.dp).testTag("header"))
            }
        }
        val pill = compose.onNodeWithContentDescription("Notification filter")
        val bounds = pill.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { filter.value = NotifFilter.Following }
        compose.mainClock.advanceTimeBy(48)
        pill.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Following"))
        assertEquals(bounds, pill.fetchSemanticsNode().boundsInRoot)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(bounds, pill.fetchSemanticsNode().boundsInRoot)
        compose.mainClock.autoAdvance = true
    }

    @Test fun notificationCapsuleSwitchesOncePerClickWithoutMovingAndDismissesItsHint() {
        val filter = mutableStateOf(NotifFilter.Global)
        var clicks = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            UnsilenceTheme {
                NotificationHeader(filter.value, true, false, {
                    clicks++
                    filter.value = filter.value.next()
                }, Modifier.width(360.dp).testTag("header"))
            }
        }
        compose.onNodeWithText("Tap to switch Global / Following").assertIsDisplayed()
        val pill = compose.onNodeWithContentDescription("Notification filter")
        val bounds = pill.fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        assertEquals(header.center.x, bounds.center.x, 1f)
        assertEquals(header.center.y, bounds.center.y, 1f)
        repeat(4) { index ->
            assertEquals("Switch to ${filter.value.next().name}", pill.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
            pill.performClick()
            pill.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, filter.value.name))
            assertEquals(index + 1, clicks)
            assertEquals(bounds, pill.fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithText("Tap to switch Global / Following").assertDoesNotExist()
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Tap to switch Global / Following").assertDoesNotExist()
    }

    @Test fun homeAndNotificationsShareTheSameCenterLineAndCapsulesMaskTheAccent() {
        val notifications = mutableStateOf(false)
        compose.setContent {
            UnsilenceTheme {
                Box(Modifier.width(360.dp).background(Color.Black)) {
                    if (notifications.value) {
                        NotificationHeader(NotifFilter.Global, false, false, {}, Modifier.testTag("header"))
                    } else {
                        HeaderFrame(Brand, false, false,
                            start = { Box(Modifier.size(52.dp)) },
                            center = { FeedSourcePill("Following", {}) },
                            end = { Box(Modifier.size(32.dp)) },
                            modifier = Modifier.testTag("header"),
                        )
                    }
                }
            }
        }
        val home = compose.onNodeWithContentDescription("Feed source").fetchSemanticsNode().boundsInRoot
        val before = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        assertEquals(before.center.x, home.center.x, 1f)
        assertEquals(before.center.y, home.center.y, 1f)
        // The label's left clearance masks the center line with opaque black,
        // not the cyan line beneath it. The hint and animation cannot alter this layer.
        val pixels = compose.onNodeWithContentDescription("Feed source").captureToImage().toPixelMap()
        val inside = pixels[8, pixels.height / 2]
        assertEquals(inside.red, inside.green, .01f)
        assertEquals(inside.red, inside.blue, .01f)
        assertEquals(0f, inside.red, .01f)
        compose.runOnIdle { notifications.value = true }
        val after = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val notification = compose.onNodeWithContentDescription("Notification filter").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        assertEquals(home.center.y, notification.center.y, 1f)
        assertEquals(home.center.x, notification.center.x, 1f)
    }

    @Test fun relayNamesExpandPastTheOldCapAndRemainBoundedAtLargeFontSizes() {
        val largeFont = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeFont.value) 2f else 1f)) {
                UnsilenceTheme {
                    Box(Modifier.width(320.dp)) {
                        HeaderFrame(Brand, false, false,
                            start = { Box(Modifier.size(52.dp).testTag("leading")) },
                            center = { FeedSourcePill("relay.long-example.net", {}) },
                            end = { Box(Modifier.size(32.dp).testTag("trailing")) },
                            modifier = Modifier.testTag("header"),
                        )
                    }
                }
            }
        }
        repeat(2) {
            val pill = compose.onNodeWithContentDescription("Feed source").fetchSemanticsNode().boundsInRoot
            val header = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
            val leading = compose.onNodeWithTag("leading").fetchSemanticsNode().boundsInRoot
            val trailing = compose.onNodeWithTag("trailing").fetchSemanticsNode().boundsInRoot
            val scale = header.width / 320f
            assertTrue(pill.width > 118f * scale)
            // Dp dimensions are rounded independently to physical pixels.
            assertTrue("Touch target $pill; scale=$scale", pill.height + 1f >= 48f * scale)
            assertTrue(pill.left > leading.right)
            assertTrue(pill.right < trailing.left)
            assertTrue(pill.top >= header.top && pill.bottom <= header.bottom)
            assertEquals(header.center.x, pill.center.x, 1f)
            assertEquals(header.center.y, pill.center.y, 1f)
            compose.runOnIdle { largeFont.value = true }
        }
    }
}
