package com.unsilence.app.ui.shared

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.UnsilenceApp
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.TimelineEventLoader
import com.unsilence.app.data.relay.TimelineEventResolution
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.ui.navigation.AppDestination
import com.unsilence.app.ui.navigation.AppNavigator
import com.unsilence.app.ui.navigation.AppNavDisplay
import com.unsilence.app.ui.navigation.rememberAppNavigator
import com.unsilence.app.ui.onboarding.SessionContent
import com.unsilence.app.ui.theme.UnsilenceTheme
import javax.inject.Provider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Navigation3 ownership and lifecycle; offline MES fixtures, no network or navigation outside the app. */
@RunWith(AndroidJUnit4::class)
class CoveredWindowLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val timeline = TimelineService(
        androidx.test.core.app.ApplicationProvider.getApplicationContext<UnsilenceApp>().subscription,
        object : TimelineEventLoader {
            override suspend fun getEvents(ids: List<String>): TimelineEventResolution = error("Offline fixture")
        },
    )
    private val store = MemoryEventStore(object : MuteKeyProvider {}, Provider { timeline })
    private val owners = mutableMapOf<String, WindowOwner>()
    private val order = mutableListOf<String>()
    private val restoredBeforeReport = mutableMapOf<String, Boolean>()
    private val roots = (0..5).map { event("root-$it") }
    private lateinit var navigator: AppNavigator

    private class WindowOwner(store: MemoryEventStore) : ViewModel() {
        val reporter = DisplayWindowReporter(store)
        var cleared = false
        override fun onCleared() { reporter.close(); cleared = true }
    }

    private fun seed() {
        roots.forEach { root ->
            store.insert(root)
            repeat(3) { store.insert(event("${root.id}-reply-$it").copy(replyToId = root.id)) }
        }
    }

    @Composable private fun Surface(rootId: String) {
        val owner = viewModel<WindowOwner> { WindowOwner(store) }
        SideEffect { owners[rootId] = owner }
        ObserveDisplayWindowLifecycle(owner.reporter)
        LifecycleResumeEffect(owner, rootId) {
            order += "resume:$rootId"
            restoredBeforeReport[rootId] = rootId in store.displayPriorities()
            onPauseOrDispose { order += "pause:$rootId" }
        }
        val row = remember(rootId) { store.synthesizeFeedRow(roots.single { it.id == rootId }) }
        ResumedEffect(rootId) { owner.reporter.prepareWarmWindow(listOf(row), 0, 0, setOf(rootId), 0) }
        val counts by remember(rootId) { store.statsFlow(rootId) }.collectAsState(store.currentStatsSnapshot(rootId))
        Text("$rootId replies ${counts.replyCount}")
    }

    private fun launch(session: androidx.compose.runtime.State<String>, tab: androidx.compose.runtime.State<String>) {
        seed()
        compose.setContent {
            UnsilenceTheme {
                SessionContent(session.value) {
                    val nav = rememberAppNavigator(session.value)
                    SideEffect { navigator = nav }
                    AppNavDisplay(nav) { entry ->
                        Surface(when (val destination = entry.destination) {
                            is AppDestination.Thread -> destination.eventId
                            is AppDestination.Profile -> destination.pubkey
                            else -> tab.value
                        })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun push(destination: AppDestination) {
        compose.runOnIdle { navigator.push(destination) }
        compose.waitForIdle()
    }

    @Test fun twoStepBackKeepsBodiesAndRestoresProtectionBeforeHydration() {
        launch(mutableStateOf("offline-a"), mutableStateOf("root-0"))
        val feed = compose.runOnIdle { owners.getValue("root-0") }
        push(AppDestination.Thread("root-1"))
        val thread = compose.runOnIdle { owners.getValue("root-1") }
        push(AppDestination.Profile("root-2"))
        val profile = compose.runOnIdle { owners.getValue("root-2") }
        store.insertBatch((0 until 5_200).map { event("pressure-$it") })
        compose.runOnIdle {
            assertTrue(store.snapshotEvictionMetrics().admissionReplaced > 0)
            (0..2).forEach { assertEquals(3, store.replyCount("root-$it")) }
            order.clear()
            navigator.pop()
        }
        compose.waitForIdle()
        compose.onNodeWithText("root-1 replies 3").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("Profile pause observed", "pause:root-2" in order)
            assertTrue("Thread resume observed", "resume:root-1" in order)
            assertTrue(order.indexOf("pause:root-2") < order.indexOf("resume:root-1"))
            assertTrue(restoredBeforeReport.getValue("root-1"))
            assertSame(thread, owners["root-1"])
            assertTrue(profile.cleared)
            navigator.pop()
        }
        compose.waitForIdle()
        compose.onNodeWithText("root-0 replies 3").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(feed, owners["root-0"])
            assertTrue(restoredBeforeReport.getValue("root-0"))
            assertTrue(thread.cleared)
            assertFalse(feed.cleared)
        }
    }

    @Test fun fourthCoveredContextRetiresOldestAndSharedTabsReplaceOneContext() {
        val tab = mutableStateOf("root-0")
        launch(mutableStateOf("offline-a"), tab)
        val feed = compose.runOnIdle { owners.getValue("root-0") }
        (1..4).forEach { push(AppDestination.Thread("root-$it")) }
        store.insertBatch((0 until 5_200).map { event("pressure-$it") })
        compose.runOnIdle {
            assertEquals("Oldest covered context is reclaimable", 0, store.replyCount("root-0"))
            (1..4).forEach { assertEquals(3, store.replyCount("root-$it")) }
        }
        repeat(4) { compose.runOnIdle { navigator.pop() }; compose.waitForIdle() }
        compose.runOnIdle {
            assertSame(feed, owners["root-0"])
            assertFalse("Retired entry must reacquire on report", restoredBeforeReport.getValue("root-0"))
            assertEquals(mapOf("root-0" to 0), store.displayPriorities())
            tab.value = "root-5"
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(feed, owners["root-5"])
            assertEquals(mapOf("root-5" to 0), store.displayPriorities())
        }
    }

    @Test fun accountReplacementClosesCoveredOwnersAndOldHandlesCannotRepin() {
        val session = mutableStateOf("offline-a")
        launch(session, mutableStateOf("root-0"))
        val feed = compose.runOnIdle { owners.getValue("root-0") }
        push(AppDestination.Thread("root-1"))
        val thread = compose.runOnIdle { owners.getValue("root-1") }
        compose.runOnIdle { store.clearUserState(); session.value = "offline-b" }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(feed.cleared)
            assertTrue(thread.cleared)
            assertNotSame(feed, owners["root-0"])
            thread.reporter.setActive(true)
            thread.reporter.report(listOf(store.synthesizeFeedRow(roots[1])), setOf("root-1"))
            assertEquals(mapOf("root-0" to 0), store.displayPriorities())
        }
    }

    @Test fun backgroundKeepsBoundedBodiesAndResumeRestoresRolesBeforeTheWarmEffect() {
        val lifecycle = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        seed()
        compose.setContent {
            UnsilenceTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { Surface("root-0") }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        val resumes = compose.runOnIdle {
            assertTrue(store.displayPriorities().isEmpty())
            order.count { it == "resume:root-0" }
        }
        store.insertBatch((0 until 5_200).map { event("background-$it") })
        compose.runOnIdle {
            assertEquals(3, store.replyCount("root-0"))
            assertEquals("No hidden resume/hydration", resumes, order.count { it == "resume:root-0" })
            lifecycle.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        compose.onNodeWithText("root-0 replies 3").assertIsDisplayed()
        compose.runOnIdle { assertTrue(restoredBeforeReport.getValue("root-0")) }
    }

    private fun event(id: String) = NostrEvent(id, "unfollowed", 1, "fixture", 1L,
        emptyList(), "", "wss://relay.example", null, null, false, null, 1L, mutableSetOf("wss://relay.example"))
}
