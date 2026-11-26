package com.unsilence.app.ui.drafts

import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.memory.UserEntity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.MainActivity
import com.unsilence.app.data.drafts.*
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.ui.compose.ComposeScreen
import com.unsilence.app.ui.feed.FeedScreen
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.navigation.*
import com.unsilence.app.ui.onboarding.SessionContent
import com.unsilence.app.ui.theme.UnsilenceTheme
import com.unsilence.app.ui.thread.ThreadScreen
import com.unsilence.app.ui.thread.ThreadViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class InlineDraftJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private var focusedAtDismiss: Boolean? = null

    @Test fun feedAndThreadCardsResumeTheExactDraftAndReturnToTheirOrigin() {
        val activity = ui.activity
        check(Build.HARDWARE in listOf("ranchu", "goldfish") && activity.packageName.endsWith(".dev"))
        val keys = activity.keyManager
        assertNull(keys.getPublicKeyHex())
        val owner = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val parent = "6".repeat(64)
        val reply = Draft("reply:$parent", listOf(DraftBlock.Text("Inline reply")), false, 2,
            DraftContext.Reply(parent), cursor = DraftCursor(0, 2, 5))
        val standalone = Draft("new", listOf(DraftBlock.Text("Standalone draft")), false, 1, DraftContext.New)
        var storage: DraftStore? = null
        var events: MemoryEventStore? = null
        var previousOwner: String? = null
        lateinit var nav: AppNavigator
        try {
            keys.saveAmberLogin(owner)
            val drafts = ui.runOnIdle { ViewModelProvider(activity)[DraftsViewModel::class.java] }
            val store = DraftsViewModel::class.java.getDeclaredField("draftStore").let {
                it.isAccessible = true
                it.get(drafts) as DraftStore
            }
            storage = store
            runBlocking {
                store.save(owner, reply).await().getOrThrow()
                store.save(owner, standalone).await().getOrThrow()
            }
            ui.runOnUiThread {
                val thread = ViewModelProvider(activity)[ThreadViewModel::class.java]
                val memory = ThreadViewModel::class.java.getDeclaredField("memoryEventStore").let {
                    it.isAccessible = true
                    it.get(thread) as MemoryEventStore
                }
                events = memory
                previousOwner = memory.ownPubkey
                memory.ownPubkey = owner
                memory.insert(EventDto(id = parent, pubkey = owner, createdAt = System.currentTimeMillis() / 1000, kind = 1,
                    tags = emptyList(), content = "Original parent", sig = "0".repeat(128)).toNostrEvent(""))
                activity.setContent {
                    UnsilenceTheme {
                        SessionContent(owner) {
                            val navigator = rememberAppNavigator(owner)
                            SideEffect { nav = navigator }
                            val saved by drafts.drafts.collectAsStateWithLifecycle()
                            val actions: NoteActionsViewModel = hiltViewModel()
                            val resume: (String, String) -> Unit = { key, origin ->
                                navigator.push(AppDestination.ResumeDraft(key, origin))
                            }
                            AppNavDisplay(navigator) { entry ->
                                when (val dest = entry.destination) {
                                    AppDestination.Tabs -> FeedScreen(onArticleClick = {}, drafts = saved,
                                        onResumeDraft = resume, onHideDraft = drafts::hideFromFeed,
                                        onShowDrafts = { navigator.push(AppDestination.Drafts) })
                                    is AppDestination.Thread -> ThreadScreen(onArticleClick = { _, _ -> },
                                        eventId = parent, entryId = entry.id, onDismiss = navigator::pop,
                                        drafts = saved, onResumeDraft = resume, actionsViewModel = actions)
                                    is AppDestination.Compose -> ObserveEditorExit(navigator) { dismiss ->
                                        ComposeScreen(onDismiss = dismiss, navigationOwnsBack = true)
                                    }
                                    is AppDestination.ResumeDraft -> ObserveEditorExit(navigator) { dismiss ->
                                        ResumeDraftScreen(dest.key, origin = dest.origin, onDismiss = dismiss, actions = actions)
                                    }
                                    AppDestination.Drafts -> DraftsScreen(onDismiss = navigator::pop, onResume = {})
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }

            ui.onNodeWithText("Standalone draft").performClick()
            ui.onNode(hasSetTextAction()).assertTextEquals("Standalone draft")
            ui.onNodeWithContentDescription("Close").performClick()
            ui.onNodeWithText("All drafts").performClick()
            ui.onNodeWithText("Standalone draft").assertIsDisplayed()
            ui.runOnIdle { nav.pop() }
            ui.onNodeWithContentDescription("Hide from feed").performClick()
            ui.onNodeWithText("Standalone draft").assertDoesNotExist()
            assertNotNull(store.draft(owner, standalone.key))

            ui.runOnIdle { nav.push(AppDestination.Compose()) }
            ui.onNode(hasSetTextAction()).performTextReplacement("Another new post")
            saveFromSheetAndCheckReturn("Another new post")
            assertEquals("Standalone draft", store.draft(owner, standalone.key)?.previewText)
            assertEquals(2, store.draftsFor(owner).count { it.isStandalone })

            ui.runOnIdle { nav.push(AppDestination.Thread(parent)) }
            ui.waitUntil(10_000) { ui.onAllNodesWithText("Inline reply").fetchSemanticsNodes().isNotEmpty() }
            val editor = ui.onNode(hasSetTextAction())
            ui.mainClock.autoAdvance = false
            try {
                ui.onNodeWithText("Inline reply").performClick()
                // Measure from the incoming layout. Dispatching a tap also schedules navigation.
                ui.waitUntil(2_000) {
                    ui.mainClock.advanceTimeByFrame()
                    ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
                }
                ui.mainClock.advanceTimeBy(144)
                editor.assertIsNotFocused()
                assertFalse(keyboardVisible())
                // The keyboard request belongs near the end of the 220 ms card transition.
                ui.mainClock.advanceTimeBy(80)
                editor.assertIsFocused()
                assertEquals(TextRange(2, 5), editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            } finally {
                ui.mainClock.autoAdvance = true
            }
            ui.waitUntil(5_000) { keyboardVisible() }
            editor.assertIsFocused()
            assertEquals(TextRange(2, 5), editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            editor.performTextReplacement("Updated inline reply")
            saveFromSheetAndCheckReturn("Updated inline reply")
            assertEquals(AppDestination.Thread(parent), ui.runOnIdle { (nav.backStack.last() as AppEntry).destination })

            ui.onNodeWithText("Updated inline reply").performClick()
            editor.assertTextEquals("Updated inline reply")
            editor.performTextClearance()
            ui.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            ui.onNodeWithText("Delete draft?").assertIsDisplayed()
            ui.onNodeWithText("Continue editing").performClick()
            assertEquals("", editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertNotNull(store.draft(owner, reply.key))

            editor.performTextReplacement("Unsaved edit")
            ui.onNodeWithContentDescription("Delete draft").performClick()
            ui.onNodeWithText("Continue editing").performClick()
            editor.assertTextEquals("Unsaved edit")
            editor.performTextClearance()
            ui.waitUntil(5_000) { keyboardVisible() }
            ui.onNodeWithContentDescription("Close").performClick()
            confirmAndCheckKeyboardStaysHidden("Delete draft") {
                ui.waitUntil(10_000) { store.draft(owner, reply.key) == null }
                ui.onNodeWithText("Updated inline reply").assertDoesNotExist()
                assertEquals(AppDestination.Thread(parent), ui.runOnIdle { (nav.backStack.last() as AppEntry).destination })
            }
            ui.runOnIdle { nav.push(AppDestination.Drafts) }
            ui.onNodeWithText("Another new post").assertIsDisplayed()
            ui.onNodeWithText("Updated inline reply").assertDoesNotExist()

            ui.runOnIdle {
                nav.pop() // Drafts to thread
                nav.pop() // Thread to feed
                nav.push(AppDestination.Compose())
            }
            editor.performTextReplacement("Saved in this editor")
            ui.waitUntil(5_000) { keyboardVisible() }
            ui.onNodeWithContentDescription("Save draft").performClick()
            ui.waitUntil(5_000) { store.draftsFor(owner).any { it.previewText == "Saved in this editor" } }
            editor.assertIsNotFocused()
            ui.waitUntil(5_000) { !keyboardVisible() }
            editor.performTouchInput { click() }
            ui.waitUntil(5_000) { keyboardVisible() }
            editor.assertTextEquals("Saved in this editor")
            ui.onNodeWithContentDescription("Delete draft").performClick()
            confirmAndCheckKeyboardStaysHidden("Delete draft") {
                ui.waitUntil(10_000) { store.draftsFor(owner).size == 2 }
                ui.onNodeWithText("Another new post").assertIsDisplayed()
            }

            ui.onNodeWithText("Another new post").performClick()
            editor.performTextReplacement("Edited post")
            ui.waitUntil(5_000) { keyboardVisible() }
            ui.onNodeWithContentDescription("Close").performClick()
            ui.onNodeWithText("Close composer?").assertIsDisplayed()
            confirmAndCheckKeyboardStaysHidden("Delete draft") {
                ui.waitUntil(10_000) { store.draftsFor(owner).size == 1 }
                assertEquals(AppDestination.Tabs, ui.runOnIdle { (nav.backStack.last() as AppEntry).destination })
            }

            ui.runOnIdle { nav.push(AppDestination.Compose()) }
            editor.performTextReplacement("Temporary unsaved post")
            ui.waitUntil(5_000) { keyboardVisible() }
            ui.onNodeWithContentDescription("Close").performClick()
            confirmAndCheckKeyboardStaysHidden("Discard") {
                assertEquals(AppDestination.Tabs, ui.runOnIdle { (nav.backStack.last() as AppEntry).destination })
                assertEquals(1, store.draftsFor(owner).size)
            }
            assertNotNull(store.draft(owner, standalone.key))
        } finally {
            ui.activityRule.scenario.close()
            storage?.let { store -> runBlocking {
                store.draftsFor(owner).forEach { store.delete(owner, it.key).await().getOrThrow() }
            } }
            events?.ownPubkey = previousOwner
            if (keys.getPublicKeyHex() == owner) keys.clear()
        }
    }

    @Composable
    private fun ObserveEditorExit(navigator: AppNavigator, editor: @Composable (() -> Unit) -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Box(Modifier.onFocusChanged { focused = it.hasFocus }.focusGroup(), propagateMinConstraints = true) {
            editor {
                focusedAtDismiss = focused
                navigator.pop()
            }
        }
    }

    private fun keyboardVisible() = ui.runOnUiThread {
        ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun saveFromSheetAndCheckReturn(expectedText: String) {
        ui.waitUntil(5_000) { keyboardVisible() }
        ui.onNodeWithContentDescription("Close").performClick()
        confirmAndCheckKeyboardStaysHidden("Save draft") { ui.onNodeWithText(expectedText).assertIsDisplayed() }
    }

    private fun confirmAndCheckKeyboardStaysHidden(action: String, checkReturn: () -> Unit) {
        ui.onNodeWithText(action).assertIsDisplayed()
        ui.waitUntil(5_000) { !keyboardVisible() }

        // Sample the real IME throughout the return. Checking only its final state misses a one-frame reopen.
        val decor = ui.activity.window.decorView
        var reopened = false
        val sample = object : Runnable {
            override fun run() {
                if (ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                    reopened = true
                }
                decor.postOnAnimation(this)
            }
        }
        ui.runOnUiThread {
            focusedAtDismiss = null
            decor.postOnAnimation(sample)
        }
        try {
            ui.onNodeWithText(action).performClick()
            checkReturn()
            val end = SystemClock.uptimeMillis() + 500
            ui.waitUntil(2_000) { SystemClock.uptimeMillis() >= end }
            ui.runOnUiThread {
                assertFalse("Keyboard reopened after $action", reopened)
                assertEquals("Editor retained focus at navigation after $action", false, focusedAtDismiss)
            }
        } finally {
            ui.runOnUiThread { decor.removeCallbacks(sample) }
        }
    }

    @Test fun nestedDraftDrawsEveryReplyGuide() {
        ui.runOnUiThread {
            ui.activity.setContent {
                UnsilenceTheme {
                    Column(Modifier.background(Color.Black).testTag("draft-row")) {
                        val draft = Draft("reply:parent", listOf(DraftBlock.Text("Test reply")), false, 1,
                            DraftContext.Reply("parent"))
                        InlineReplyDrafts(listOf(draft), null, { error("No author") }, "parent", 2) { _, _ -> }
                    }
                }
            }
        }
        val pixels = ui.onNodeWithTag("draft-row").captureToImage().toPixelMap()
        for (level in 1..2) {
            val x = with(ui.density) { (8 * level).dp.toPx().toInt() }
            for (y in listOf(2, pixels.height / 2, pixels.height - 5)) {
                assertTrue("Missing guide at depth $level, y=$y", maxOf(pixels[x, y].red, pixels[x - 1, y].red) > 0.025f)
            }
        }
    }

    @Test fun draftNameAndAddressUseThePublishedCardLine() {
        val pubkey = "1".repeat(64)
        ui.runOnUiThread {
            ui.activity.setContent {
                UnsilenceTheme {
                    DraftAuthorHeader(pubkey, UserEntity(pubkey, displayName = "Card author", nip05 = "card@example.com"),
                        showDraftMark = true)
                }
            }
        }
        val name = ui.onNodeWithText("Card author", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val address = ui.onNodeWithContentDescription("Nostr address: card@example.com", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(address.left >= name.right && address.center.y in name.top..name.bottom)
    }

}
