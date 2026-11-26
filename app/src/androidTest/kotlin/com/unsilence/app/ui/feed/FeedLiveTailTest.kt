package com.unsilence.app.ui.feed

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.hilt.navigation.compose.hiltViewModel
import com.unsilence.app.MainActivity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.ui.navigation.AppNavigation
import com.unsilence.app.ui.onboarding.SessionContent
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FeedLiveTailTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val owner = "7".repeat(64)
    private val session = "feed-live-tail"
    private lateinit var feed: FeedViewModel
    private lateinit var memory: MemoryEventStore
    private val clock = System.currentTimeMillis() / 1000 - 1000
    private val list get() = ui.onNode(
        hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    )

    private fun scrollPosition() =
        list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun note(number: Int) = EventDto(
        id = "%064x".format(number), pubkey = owner, createdAt = clock + number, kind = 1,
        tags = emptyList(), content = "Arrival fixture note $number", sig = "0".repeat(128),
    ).toNostrEvent("")

    private fun deliver(number: Int) {
        val event = note(number)
        memory.insert(event)
        feedCallback("handleNew", NostrEvent::class.java, event)
    }

    private fun feedCallback(method: String, type: Class<*>, value: Any) {
        FeedViewModel::class.java.getDeclaredMethod(method, type)
            .apply { isAccessible = true }.invoke(feed, value)
    }

    private fun assertAtTopWith(number: Int) {
        val head = note(number)
        ui.waitUntil(12_000) { feed.presentation.value.rows.firstOrNull()?.id == head.id }
        ui.waitForIdle()
        assertEquals("Newest row should be at the viewport top", 0f, scrollPosition(), 0f)
        ui.onNodeWithText(head.content).assertIsDisplayed()
        assertTrue(feed.isAtTop.value)
        assertEquals(0, feed.pendingCount.value)
    }

    @Before fun openFollowingFeed() {
        val activity = ui.activity
        check(Build.HARDWARE in listOf("ranchu", "goldfish") && activity.packageName.endsWith(".dev"))
        check(activity.keyManager.getPublicKeyHex() == null)
        ui.runOnUiThread {
            activity.setContent {
                UnsilenceTheme {
                    SessionContent(session) {
                        val vm: FeedViewModel = hiltViewModel(key = "feed-$session")
                        SideEffect { feed = vm }
                        AppNavigation(owner, session, onLogout = {})
                    }
                }
            }
        }
        ui.waitForIdle()
        ui.runOnIdle {
            memory = FeedViewModel::class.java.getDeclaredField("memoryEventStore")
                .apply { isAccessible = true }.get(feed) as MemoryEventStore
            feed.setFeedType(FeedType.Following)
        }
        ui.waitUntil(12_000) {
            val projection = feed.presentation.value
            projection.restorationReady && projection.selection?.source == FeedType.Following.savedSource()
        }
        ui.runOnIdle {
            val rows = (48 downTo 1).map(::note)
            memory.insertBatch(rows)
            feedCallback("handleHistory", List::class.java, rows)
        }
        assertAtTopWith(48)
    }

    private fun readAndBuffer() {
        list.performTouchInput { swipe(center, center.copy(y = height * 0.23f), durationMillis = 460) }
        ui.waitForIdle()
        val anchor = scrollPosition()
        assertTrue("Drag should leave live tail", anchor > 0f && !feed.isAtTop.value)
        ui.runOnIdle {
            deliver(49)
            deliver(49) // Duplicate relay delivery
            deliver(50)
            deliver(47) // Already in the visible timeline
        }
        ui.waitUntil(12_000) { feed.pendingCount.value == 2 }
        ui.onNodeWithContentDescription("2 new posts. Show newest posts").assertIsDisplayed()
        assertEquals(anchor, scrollPosition(), 0f)
        assertEquals(note(48).id, feed.presentation.value.rows.first().id)
    }

    @Test fun delayedRowProjectionKeepsTheNewLiveHeadAtTheTop() {
        val next = note(49)
        // The live marker and the Default-dispatcher row projection reach Compose independently.
        @Suppress("UNCHECKED_CAST")
        val markers = FeedViewModel::class.java.getDeclaredField("_liveArrivalIds")
            .apply { isAccessible = true }.get(feed) as MutableStateFlow<Set<String>>
        ui.runOnIdle { markers.value = setOf(next.id) }
        ui.waitForIdle()
        ui.runOnIdle { deliver(49) }
        assertAtTopWith(49)
    }

    @Test fun pillReturnsToNewestAndKeepsTailing() {
        readAndBuffer()
        ui.onNodeWithContentDescription("2 new posts. Show newest posts").performClick()
        assertAtTopWith(50)
        ui.runOnIdle { deliver(51) }
        assertAtTopWith(51)
    }

    @Test fun homeConsumesPendingAndKeepsTailing() {
        readAndBuffer()
        ui.onNodeWithContentDescription("Home").performClick()
        assertAtTopWith(50)
        ui.runOnIdle { deliver(51) }
        assertAtTopWith(51)
    }
}
