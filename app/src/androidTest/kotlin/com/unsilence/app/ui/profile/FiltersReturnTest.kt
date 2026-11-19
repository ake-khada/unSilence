package com.unsilence.app.ui.profile

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import com.unsilence.app.MainActivity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.ui.navigation.AppDestination
import com.unsilence.app.ui.navigation.AppNavDisplay
import com.unsilence.app.ui.navigation.AppNavigator
import com.unsilence.app.ui.navigation.rememberAppNavigator
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class FiltersReturnTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun backFromSelectedProfileRetainsSearchAndPrivateMutes() {
        val activity = compose.activity
        assertNull("Requires the account-free emulator", activity.keyManager.getPublicKeyHex())
        val filters = compose.runOnIdle { ViewModelProvider(activity)[FiltersViewModel::class.java] }
        val field = FiltersViewModel::class.java.getDeclaredField("memoryEventStore").apply { isAccessible = true }
        val store = field.get(filters) as MemoryEventStore
        val previous = store.ownPubkey
        val owner = "f".repeat(64)
        val selected = "7".repeat(64)
        val muted = setOf(selected, "8".repeat(64))
        val original = store.getMuteList(owner)
        lateinit var navigator: AppNavigator
        try {
            store.ownPubkey = owner
            store.updateMuteListPrivateTags(owner, muted, emptySet(), emptySet(), emptySet())
            compose.runOnUiThread {
                activity.setContent {
                    UnsilenceTheme {
                        val nav = rememberAppNavigator("muted-row")
                        SideEffect { navigator = nav }
                        AppNavDisplay(nav) { entry ->
                            when (val destination = entry.destination) {
                                AppDestination.Tabs -> FiltersScreen(
                                    onDismiss = nav::pop,
                                    onOpenProfile = { nav.push(AppDestination.Profile(it)) },
                                    navigationOwnsBack = true,
                                    viewModel = filters,
                                )
                                is AppDestination.Profile -> Text("Selected ${destination.pubkey}")
                                else -> Unit
                            }
                        }
                    }
                }
            }
            val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            list.performScrollToNode(hasSetTextAction())
            compose.onNode(hasSetTextAction()).performTextReplacement("7777")
            list.performScrollToNode(hasText("77777777…"))
            compose.onNodeWithText("77777777…").performClick()
            compose.onNodeWithText("Selected $selected").assertIsDisplayed()
            compose.runOnIdle { navigator.pop() }
            list.performScrollToNode(hasSetTextAction())
            compose.onNode(hasSetTextAction()).assertTextEquals("7777")
            assertEquals(muted, store.getMuteList(owner)?.privatePubkeys)
        } finally {
            compose.activityRule.scenario.close()
            store.ownPubkey = previous
            store.updateMuteListPrivateTags(owner, original?.privatePubkeys.orEmpty(), original?.privateHashtags.orEmpty(),
                original?.privateWords.orEmpty(), original?.privateEventIds.orEmpty())
        }
    }
}
