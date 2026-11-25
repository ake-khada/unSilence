package com.unsilence.app.ui.profile

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.unsilence.app.MainActivity
import com.unsilence.app.data.DEFAULT_WOT_PROVIDER_PUBKEY
import com.unsilence.app.data.memory.*
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class WotCacheDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun offlineProfileDisplaysLastConfirmedAgeWithoutTrustingStaleScore() {
        val activity = ui.activity
        check(Build.HARDWARE in listOf("ranchu", "goldfish") && activity.packageName.endsWith(".dev"))
        assertNull(activity.keyManager.getPublicKeyHex())
        val vm = ui.runOnIdle { ViewModelProvider(activity)[UserProfileViewModel::class.java] }
        val store = UserProfileViewModel::class.java.getDeclaredField("memoryEventStore")
            .apply { isAccessible = true }.get(vm) as MemoryEventStore
        val previous = store.ownPubkey
        val owner = "1".repeat(64)
        val author = "2".repeat(64)
        val provider = DEFAULT_WOT_PROVIDER_PUBKEY
        val disk = WotScoreCache(File(activity.cacheDir, UUID.randomUUID().toString()))
        try {
            store.ownPubkey = owner
            val score = WotAssertionEntity(author, provider, 47, confirmedAt = System.currentTimeMillis() - 18 * 3_600_000L)
            disk.write(WotScoreSnapshot(owner, provider, listOf(score)))
            store.restoreWotScores(requireNotNull(disk.read()))
            ui.runOnUiThread {
                activity.setContent { UnsilenceTheme {
                    UserProfileScreen(onArticleClick = {}, pubkey = author, entryId = "cache-test", onDismiss = {}, viewModel = vm)
                } }
            }
            ui.waitUntil(10_000) { ui.onAllNodesWithText("47").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("47").performClick()
            ui.onNodeWithText("Cached · last confirmed 18h ago", substring = true).assertIsDisplayed()
            assertEquals(WotLookup.Pending, store.wotFor(author))

            // Keep the screen open across the hard cutoff; no navigation or relay event can refresh it.
            ui.runOnIdle {
                store.clearWotAssertions()
                val expiring = score.copy(confirmedAt = System.currentTimeMillis() - WOT_DISPLAY_WINDOW_MS + 4_000)
                store.restoreWotScores(WotScoreSnapshot(owner, provider, listOf(expiring)))
            }
            ui.waitUntil(3_000) {
                ui.onAllNodesWithText("Cached · last confirmed 47h ago", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            ui.waitUntil(8_000) { ui.onAllNodesWithText("47").fetchSemanticsNodes().isEmpty() }
            assertEquals(WotLookup.Pending, store.displayWotFor(author))
        } finally {
            ui.activityRule.scenario.close()
            store.ownPubkey = previous
            disk.delete()
        }
    }
}
