package com.unsilence.app.ui.navigation

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production row with local labels and callbacks; no relay or account access. */
@RunWith(AndroidJUnit4::class)
class FavoriteFeedSelectorRowTest {
    @get:Rule val compose = createComposeRule()
    private var selectedCount = 0
    private var removedCount = 0

    @Test fun removalHasFullTargetWithoutGrowingNormalRow() {
        render()
        val button = compose.onNodeWithContentDescription("Remove favorite").getUnclippedBoundsInRoot()
        assertEquals(48f, (button.right - button.left).value, .5f)
        assertEquals(48f, (button.bottom - button.top).value, .5f)
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        assertEquals(52f, (row.bottom - row.top).value, .5f) // Existing 22sp line + 26dp padding + 4dp outer gap.
        assertEquals(50f, compose.onNodeWithText("relay.example", useUnmergedTree = true).getUnclippedBoundsInRoot().left.value, .5f)
        assertEquals(323f, ((button.left + button.right) / 2).value, .5f)
    }

    @Test fun removalEdgeDoesNotSelectFeed() {
        render()
        compose.onNodeWithContentDescription("Remove favorite").performTouchInput { click(Offset(width / 6f, center.y)) }
        compose.runOnIdle { assertEquals(1, removedCount); assertEquals(0, selectedCount) }
        compose.onNodeWithTag("row").assertIsDisplayed()
    }

    @Test fun labelSelectsWithoutRemoving() {
        render()
        compose.onNodeWithText("relay.example", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, selectedCount); assertEquals(0, removedCount) }
    }

    @Test fun selectedLongLabelAtLargeFontKeepsSeparateRemoval() {
        render(selected = true, fontScale = 1.6f, label = "a-very-long-relay-name.example", width = 280)
        compose.onNodeWithContentDescription("Selected").assertIsDisplayed()
        val button = compose.onNodeWithContentDescription("Remove favorite").getUnclippedBoundsInRoot()
        val label = compose.onNodeWithText("a-very-long-relay-name.example", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(48f, (button.right - button.left).value, .5f)
        assertTrue(label.right <= button.left)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("a-very-long-relay-name.example", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
        compose.onNodeWithContentDescription("Latency unknown; connection status unknown", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove favorite").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, removedCount); assertEquals(0, selectedCount) }
        compose.onNodeWithTag("row").assertIsDisplayed()
    }

    @Test fun unselectedNarrowLongLabelKeepsRemovalVisible() {
        render(fontScale = 1.6f, label = "a-very-long-relay-name.example", width = 280)
        compose.onNodeWithContentDescription("Selected").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove favorite").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("a-very-long-relay-name.example", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
        compose.onNodeWithContentDescription("Remove favorite").performTouchInput {
            click(Offset(width / 6f, center.y))
        }
        compose.runOnIdle { assertEquals(1, removedCount); assertEquals(0, selectedCount) }
    }

    private fun render(selected: Boolean = false, fontScale: Float = 1f, label: String = "relay.example", width: Int = 360) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                UnsilenceTheme {
                    FavoriteFeedSelectorRow(label, selected, null,
                        onSelect = { selectedCount++ }, onRemove = { removedCount++ },
                        modifier = Modifier.width(width.dp).testTag("row"))
                }
            }
        }
    }
}
