package com.unsilence.app.ui.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.ui.navigation.AppDestination
import com.unsilence.app.ui.navigation.AppNavDisplay
import com.unsilence.app.ui.navigation.AppNavigator
import com.unsilence.app.ui.navigation.rememberAppNavigator
import com.unsilence.app.ui.theme.UnsilenceTheme
import javax.inject.Provider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline production projection, lifecycle, Navigation3 and MES boundaries.
 * Uses text bodies in place of network-backed cards; does not claim full-screen or gesture coverage.
 */
@RunWith(AndroidJUnit4::class)
class ReplyDisplayLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val timeline = com.unsilence.app.data.relay.TimelineService(
        androidx.test.core.app.ApplicationProvider.getApplicationContext<com.unsilence.app.UnsilenceApp>().subscription,
        object : com.unsilence.app.data.relay.TimelineEventLoader {
            override suspend fun getEvents(ids: List<String>): com.unsilence.app.data.relay.TimelineEventResolution =
                error("Offline fixture must not load events")
        },
    )
    private val store = MemoryEventStore(object : MuteKeyProvider {}, Provider { timeline })
    private val reporter = DisplayWindowReporter(store)

    @Composable private fun Entry(id: String) {
        val row = remember(id) { event(id).also(store::insert).let(store::synthesizeFeedRow) }
        ObserveDisplayWindowLifecycle(reporter)
        ResumedEffect(id) { reporter.prepareWarmWindow(listOf(row), 0, 0, setOf(id), 0) }
        Text(id)
    }

    @Test fun realNavigationKeepsTheCurrentSurfaceProtectedAfterPushPopAndReplacement() {
        lateinit var navigator: AppNavigator
        compose.setContent {
            UnsilenceTheme {
                val nav = rememberAppNavigator("offline-window")
                SideEffect { navigator = nav }
                AppNavDisplay(nav) { Entry(it.id) }
            }
        }
        fun assertCurrent() {
            compose.waitForIdle()
            compose.runOnIdle {
                val current = navigator.backStack.last().let { it as com.unsilence.app.ui.navigation.AppEntry }.id
                assertEquals("Only the settled surface owns protection", setOf(current), store.displayPriorities().keys)
            }
        }
        assertCurrent()
        // Thread→thread, thread→profile, Back, search→thread,
        // then the same replacement used when a thread resolves into an article.
        listOf(
            AppDestination.Thread("one"), AppDestination.Thread("quote"), AppDestination.Profile("author"),
        ).forEach { destination ->
            compose.runOnIdle { navigator.push(destination) }
            assertCurrent()
        }
        repeat(2) { compose.runOnIdle { navigator.pop() }; assertCurrent() }
        compose.runOnIdle { navigator.push(AppDestination.Search("fixture")) }
        assertCurrent()
        compose.runOnIdle { navigator.push(AppDestination.Thread("article", openArticleOnLoad = true)) }
        assertCurrent()
        compose.runOnIdle { navigator.replaceAboveTabs(AppDestination.Article("article")) }
        assertCurrent()
        compose.runOnIdle { navigator.popToTabs() }
        assertCurrent()
    }

    @Test fun revealedBodiesAndArticleContextShareTheRenderedProjectionAndReleaseOnPause() {
        val lifecycle = FixtureLifecycle()
        val article = event("article").copy(kind = 30023).also(store::insert).let(store::synthesizeFeedRow)
        val reply = event("revealed").also(store::insert).let(store::synthesizeFeedRow)
        val hidden = event("hidden").also(store::insert).let(store::synthesizeFeedRow)
        val revealed = mutableStateOf(setOf("opened"))
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                val items = replyListItems(
                    listOf(ModeratedReplyRow(reply, 0, spamClusterId = "opened"),
                        ModeratedReplyRow(hidden, 0, spamClusterId = "closed")), revealed.value,
                )
                val rows = displayedReplyRows(items)
                ObserveDisplayWindowLifecycle(reporter)
                ResumedEffect(rows) {
                    reporter.prepareWarmWindow(rows, 0, rows.lastIndex,
                        setOf(article.id, reply.id), 0, contextRows = listOf(article))
                }
                Column {
                    Text(article.id)
                    items.forEach { item ->
                        if (item is ReplyListItem.Reply && !item.depthRow.muted) Text(item.depthRow.row.id)
                    }
                }
            }
        }
        compose.onNodeWithText("revealed").assertIsDisplayed()
        compose.onNodeWithText("hidden").assertDoesNotExist()
        compose.runOnIdle {
            store.evictOldContentEventsForTest(mapOf(1 to 0, 30023 to 0))
            assertNotNull(store.getNostrEvent(reply.id))
            assertNotNull(store.getNostrEvent(article.id))
            assertNull(store.getNostrEvent(hidden.id))
            revealed.value = emptySet()
        }
        compose.onNodeWithText("revealed").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(setOf(article.id), store.displayPriorities().keys)
            lifecycle.registry.currentState = Lifecycle.State.CREATED
        }
        compose.runOnIdle { assertTrue(store.displayPriorities().isEmpty()) }
        compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(setOf(article.id), store.displayPriorities().keys) }
    }

    @Test fun revealRestartsTheWarmLaneOnceWhileStaticRecompositionDoesNot() {
        val reply = store.synthesizeFeedRow(event("revealed"))
        val hidden = store.synthesizeFeedRow(event("hidden"))
        val reveal = mutableStateOf(emptySet<String>())
        val unrelated = mutableStateOf(0)
        val calls = mutableListOf<List<String>>()
        compose.setContent {
            val items = replyListItems(
                listOf(ModeratedReplyRow(reply, 0, spamClusterId = "opened"),
                    ModeratedReplyRow(hidden, 0, spamClusterId = "closed")), reveal.value,
            )
            val rows = displayedReplyRows(items)
            // Same production lifecycle/key boundary used by both screen warm lanes.
            // This counts invocations, not network requests or bytes.
            ResumedEffect(rows) { calls += rows.map { it.id } }
            Text("Unrelated state ${unrelated.value}")
        }
        compose.runOnIdle { assertEquals(listOf(emptyList<String>()), calls); reveal.value = setOf("opened") }
        compose.runOnIdle { assertEquals(listOf(emptyList(), listOf("revealed")), calls) }
        repeat(3) {
            compose.runOnIdle { unrelated.value++ }
            compose.runOnIdle { assertEquals(2, calls.size) }
        }
        compose.runOnIdle { reveal.value = emptySet() }
        compose.runOnIdle { assertEquals(listOf(emptyList(), listOf("revealed"), emptyList()), calls) }
    }

    private class FixtureLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle = registry
    }

    private fun event(id: String) = NostrEvent(
        id = id, pubkey = "unfollowed", kind = 1, content = "fixture", createdAt = 1L,
        tags = emptyList(), sig = "", relayUrl = "wss://relay.example", replyToId = null, rootId = null,
        hasContentWarning = false, contentWarningReason = null, firstSeenAt = 1L,
        relaysSeen = mutableSetOf("wss://relay.example"),
    )
}
