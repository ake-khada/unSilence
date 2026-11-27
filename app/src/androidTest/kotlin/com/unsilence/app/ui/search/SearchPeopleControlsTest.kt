package com.unsilence.app.ui.search

import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.unsilence.app.MainActivity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotScoreSnapshot
import com.unsilence.app.ui.navigation.DeepLinkTarget
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchPeopleControlsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun clearHasDedicatedTouchSpaceAndKeepsTheFieldFocused() {
        ui.runOnUiThread {
            ui.activity.setContent { UnsilenceTheme {
                SearchScreen(onArticleClick = {}, controlsVisible = true, onShowControls = {})
            } }
        }
        val field = ui.onNode(hasSetTextAction())
        val clear = ui.onNodeWithContentDescription("Clear search")
        for (right in listOf(false, true)) {
            field.performClick().performTextInput("alice")
            val bounds = clear.getUnclippedBoundsInRoot()
            assertTrue("Clear must reserve 48 dp in both dimensions: $bounds",
                bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
            clear.performTouchInput { click(if (right) bottomRight - Offset(2f, 2f) else topLeft + Offset(2f, 2f)) }
            clear.assertDoesNotExist()
            field.assertIsFocused()
        }
    }

    @Test fun thresholdShieldAndExactLookupStayAvailableAcrossTabs() {
        val activity = ui.activity
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && activity.keyManager.getPublicKeyHex() == null)
        val vm = ui.runOnIdle { ViewModelProvider(activity)[SearchViewModel::class.java] }
        val store = SearchViewModel::class.java.getDeclaredField("memoryEventStore")
            .apply { isAccessible = true }.get(vm) as MemoryEventStore
        val owner = "1".repeat(64)
        val author = "2".repeat(64)
        val provider = store.activeWotProvider().providerPubkey
        val oldOwner = store.ownPubkey
        val prefs = activity.relayPreferencesStore
        val initial = runBlocking { prefs.wotScoreFilter.filterNotNull().first() }
        try {
            store.ownPubkey = owner
            store.restoreWotScores(WotScoreSnapshot(owner, provider, listOf(
                WotAssertionEntity(author, provider, 12, confirmedAt = System.currentTimeMillis()),
            )))
            runBlocking { prefs.setMinimumWotScore(25) }
            ui.runOnUiThread {
                activity.setContent { UnsilenceTheme {
                    SearchScreen(onArticleClick = {}, controlsVisible = true, onShowControls = {}, viewModel = vm)
                } }
            }
            val shield = ui.onNodeWithContentDescription("People filter, minimum WoT score 25")
            shield.assertIsDisplayed()
            val field = ui.onNode(hasSetTextAction())
            field.performTextInput(author.hexToByteArray().toNpub())
            val clear = ui.onNodeWithContentDescription("Clear search")
            val icon = shield.getUnclippedBoundsInRoot()
            assertTrue(icon.right <= clear.getUnclippedBoundsInRoot().left)
            ui.onNodeWithText("WoT score 12 · Your minimum is 25").assertIsDisplayed()
            ui.onNodeWithText("People").performClick()
            ui.onNodeWithText("Open profile").performClick()
            assertEquals(DeepLinkTarget.Profile(author), activity.deepLinkRouter.pendingTarget.value)
            activity.deepLinkRouter.consume(DeepLinkTarget.Profile(author))
            clear.performClick()
            field.performTextInput("3".repeat(64))
            ui.onNodeWithText("No current WoT score", substring = true).assertIsDisplayed()
            runBlocking { prefs.setMinimumWotScore(0) }
            ui.waitUntil { ui.onAllNodesWithContentDescription("People filter, minimum WoT score 25").fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithText("Open profile").assertDoesNotExist()
        } finally {
            ui.activityRule.scenario.close()
            store.ownPubkey = oldOwner
            runBlocking {
                prefs.setMinimumWotScore(initial.minimum)
                if (!initial.enabled) prefs.setMinimumWotScore(0)
            }
        }
    }
}
