package com.unsilence.app.ui.profile

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.unsilence.app.MainActivity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.ui.onboarding.RootViewModel
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MuteOwnerScreenTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun openFiltersDropsPreviousAccountDuringLogoutAndShowsTheNextOwner() {
        val activity = ui.activity
        check(Build.HARDWARE in listOf("ranchu", "goldfish") && activity.packageName.endsWith(".dev"))
        val keys = activity.keyManager
        assertNull(keys.getPublicKeyHex())
        val root = ui.runOnIdle { ViewModelProvider(activity)[RootViewModel::class.java] }
        val filters = ui.runOnIdle { ViewModelProvider(activity)[FiltersViewModel::class.java] }
        val store = FiltersViewModel::class.java.getDeclaredField("memoryEventStore").apply { isAccessible = true }
            .get(filters) as MemoryEventStore
        val previous = store.ownPubkey
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val one = "1".repeat(64)
        val two = "2".repeat(64)
        fun show(label: String) {
            ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText(label))
            ui.onNodeWithText(label).assertIsDisplayed()
        }
        try {
            keys.saveAmberLogin(a)
            store.ownPubkey = a
            store.updateMuteListPrivateTags(a, setOf(one), emptySet(), emptySet(), emptySet())
            ui.runOnUiThread {
                activity.setContent { UnsilenceTheme { FiltersScreen(onDismiss = {}, onOpenProfile = {}, viewModel = filters) } }
            }
            show("11111111…")
            ui.runOnIdle { root.logout() }
            ui.waitUntil(10_000) { keys.getPublicKeyHex() == null && !root.isLoggingOut }
            show("No muted users")
            keys.saveAmberLogin(b)
            store.updateMuteListPrivateTags(b, setOf(two), emptySet(), emptySet(), emptySet())
            store.ownPubkey = b
            ui.waitUntil(5_000) { filters.muteList.value?.privatePubkeys == setOf(two) }
            show("22222222…")
            ui.onNodeWithText("11111111…").assertDoesNotExist()
        } finally {
            ui.activityRule.scenario.close()
            store.ownPubkey = previous
            if (keys.getPublicKeyHex() in setOf(a, b)) keys.clear()
            store.updateMuteListPrivateTags(b, emptySet(), emptySet(), emptySet(), emptySet())
        }
    }
}
