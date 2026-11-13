package com.unsilence.app.ui.thread

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.MainActivity
import com.unsilence.app.UnsilenceApp
import com.unsilence.app.data.memory.DisplayWindowRetention
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.RelayConnectionFactory
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.navigation.*
import com.unsilence.app.ui.onboarding.SessionContent
import com.unsilence.app.ui.profile.UserProfileScreen
import com.unsilence.app.ui.theme.UnsilenceTheme
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.UnknownHostException

/** Production shared action ownership, real screens/navigation, and local admission pressure. */
@RunWith(AndroidJUnit4::class)
class ThreadWindowNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun field(instance: Any, name: String) =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Test fun sharedActionsDoNotReplaceTheCoveredThreadsWindow() = exercise(retireCovered = false)

    @Test fun retiredThreadWindowReactivatesInTheFirstIncomingComposition() = exercise(retireCovered = true)

    @Test fun predictiveBackCancellationAndCompletionProtectTheIncomingThread() =
        exercise(retireCovered = true, predictiveBack = true)

    private class BackInput : NavigationEventInput() {
        fun start() = dispatchOnBackStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT))
        fun progress(value: Float = 0.5f) = dispatchOnBackProgressed(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = value))
        fun cancel() = dispatchOnBackCancelled()
        fun complete() = dispatchOnBackCompleted()
    }

    private fun exercise(retireCovered: Boolean, predictiveBack: Boolean = false) {
        val rootId = "1".repeat(64)
        val replies = (2..4).map { it.toString().repeat(64) }
        val profileAuthor = "8".repeat(64)
        val app = compose.activity.application as UnsilenceApp
        val factory = field(app.relayPool, "relayConnectionFactory").get(app.relayPool) as RelayConnectionFactory
        val clientField = field(factory, "relayClient")
        val originalClient = clientField.get(factory) as OkHttpClient
        clientField.set(factory, originalClient.newBuilder().dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                throw UnknownHostException("Offline navigation fixture")
        }).build())
        lateinit var store: MemoryEventStore
        lateinit var navigator: AppNavigator
        var previousOwner: String? = null
        var pressureOnMount = false
        var incomingChecks = 0
        var nextId = 100
        val lostAtMount = mutableListOf<String>()
        val threadOwners = mutableSetOf<ThreadViewModel>()
        val actionOwners = mutableSetOf<NoteActionsViewModel>()
        val backInput = BackInput()
        val dispatcher = (compose.activity as NavigationEventDispatcherOwner).navigationEventDispatcher
        val now = System.currentTimeMillis() / 1000
        fun event(id: String, pubkey: String, text: String) = EventDto(
            id = id, pubkey = pubkey, createdAt = now, kind = 1,
            tags = emptyList(), content = text, sig = "0".repeat(128),
        ).toNostrEvent("")
        fun pressure(count: Int) {
            store.insertBatch(List(count) {
                event((nextId++).toString(16).padStart(64, '0'), "9".repeat(64), "Unrelated content")
            })
        }
        try {
            compose.runOnUiThread {
                dispatcher.addInput(backInput)
                assertNull("Requires the account-free isolated emulator", compose.activity.keyManager.getPublicKeyHex())
                val dependency = ViewModelProvider(compose.activity)[ThreadViewModel::class.java]
                store = field(dependency, "memoryEventStore").get(dependency) as MemoryEventStore
                previousOwner = store.ownPubkey
                store.ownPubkey = "a".repeat(64)
                assertTrue(store.insert(event(rootId, "5".repeat(64), "Window fixture root")))
                replies.forEachIndexed { i, id ->
                    assertTrue(store.insert(event(id, (i + 5).toString().repeat(64), "Window fixture reply $i")
                        .copy(rootId = rootId, replyToId = rootId, tags = listOf(listOf("e", rootId, "", "root")))))
                }
                assertTrue(store.insert(event("b".repeat(64), profileAuthor, "Profile fixture post")))
                assertTrue(store.insert(event("c".repeat(64), profileAuthor, "{\"name\":\"Fixture profile\"}").copy(kind = 0)))
                compose.activity.setContent {
                    UnsilenceTheme {
                        SessionContent("window-fixture") {
                            // Exactly as AppNavigation: one actions VM above the NavEntry stores.
                            val actions: NoteActionsViewModel = hiltViewModel(key = "shared-fixture-actions")
                            val nav = rememberAppNavigator("window-fixture")
                            SideEffect { navigator = nav; actionOwners += actions }
                            AppNavDisplay(nav) { entry ->
                                when (val destination = entry.destination) {
                                    is AppDestination.Thread -> {
                                        val vm: ThreadViewModel = hiltViewModel(key = "window-fixture-thread")
                                        Box(Modifier.testTag("fixture-thread")) {
                                            ThreadScreen(onArticleClick = { _, _ -> }, eventId = destination.eventId,
                                                entryId = entry.id, onDismiss = nav::pop, viewModel = vm,
                                                actionsViewModel = actions)
                                        }
                                        SideEffect {
                                            threadOwners += vm
                                            if (pressureOnMount) {
                                                pressureOnMount = false
                                                incomingChecks++
                                                val active = field(vm, "screenActive").get(vm) as kotlinx.coroutines.flow.StateFlow<*>
                                                assertEquals("Protection must precede resumed fetch work", false, active.value)
                                                pressure(5_200)
                                                lostAtMount += replies.filter { store.getNostrEvent(it) == null }
                                            }
                                        }
                                    }
                                    is AppDestination.Profile -> UserProfileScreen(
                                        onArticleClick = {}, pubkey = destination.pubkey, entryId = entry.id,
                                        onDismiss = nav::pop, actionsViewModel = actions)
                                    else -> Text("Window fixture home")
                                }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.runOnIdle { navigator.push(AppDestination.Thread(rootId)) }
            fun replyNode(i: Int) = compose.onNode(hasText("Window fixture reply $i") and
                hasAnyAncestor(hasTestTag("fixture-thread")))
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Window fixture reply 2") and hasAnyAncestor(hasTestTag("fixture-thread")))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            repeat(2) { cycle ->
                compose.runOnIdle { navigator.push(AppDestination.Profile(profileAuthor)) }
                compose.waitForIdle()
                compose.waitUntil(5_000) { "b".repeat(64) in store.displayPriorities() }
                if (!retireCovered) {
                    pressure(5_200)
                    replies.forEach { id -> assertNotNull("Covered thread reply was evicted: $id", store.getNostrEvent(id)) }
                    assertEquals(3, store.replyCount(rootId))
                } else {
                    // Retire the soft lease without removing bodies; expose the transition gap independently.
                    val windows = field(store, "displayWindows").get(store) as DisplayWindowRetention
                    windows.retireOldestCovered()
                    replies.forEach { id -> assertNotNull(store.getNostrEvent(id)) }
                }
                compose.mainClock.autoAdvance = false
                if (predictiveBack && cycle == 0) {
                    compose.runOnIdle { pressureOnMount = true; backInput.start(); backInput.progress() }
                    repeat(32) { compose.mainClock.advanceTimeByFrame() }
                    assertFalse("Predictive Back must compose its incoming thread", pressureOnMount)
                    assertTrue(lostAtMount.isEmpty())
                    assertFalse("Outgoing profile must be covered during predictive Back",
                        "b".repeat(64) in store.displayPriorities())
                    compose.runOnIdle { backInput.cancel() }
                    repeat(32) { compose.mainClock.advanceTimeByFrame() }
                    compose.runOnIdle {
                        assertTrue((navigator.backStack.last() as AppEntry).destination is AppDestination.Profile)
                        assertTrue("Cancelled thread is covered, not active", replies.none { it in store.displayPriorities() })
                        assertTrue("Cancellation restores the profile window", "b".repeat(64) in store.displayPriorities())
                        (field(store, "displayWindows").get(store) as DisplayWindowRetention).retireOldestCovered()
                    }
                }
                compose.runOnIdle {
                    pressureOnMount = true
                    if (predictiveBack && cycle == 1) { backInput.start(); backInput.progress() }
                    else navigator.pop()
                }
                repeat(32) {
                    compose.mainClock.advanceTimeByFrame()
                    if (!pressureOnMount) {
                        assertTrue("Visible replies lost before RESUMED: $lostAtMount", lostAtMount.isEmpty())
                        assertFalse("Outgoing profile must not share incoming protection",
                            "b".repeat(64) in store.displayPriorities())
                        replies.forEach { id -> assertNotNull(store.getNostrEvent(id)) }
                        repeat(3) { replyNode(it).assertExists() }
                    }
                }
                if (predictiveBack && cycle == 1) {
                    compose.runOnIdle { backInput.progress(1f) }
                    repeat(3) { compose.mainClock.advanceTimeByFrame() }
                    assertTrue("Completed preview remains protected until committed", replies.all { it in store.displayPriorities() })
                    compose.runOnIdle { backInput.complete() }
                }
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                repeat(3) { replyNode(it).assertIsDisplayed() }
                assertEquals(3, store.replyCount(rootId))
            }
            assertEquals(if (predictiveBack) 3 else 2, incomingChecks)
            assertEquals(1, threadOwners.size)
            assertEquals(1, actionOwners.size)
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { compose.activity.setContent { Text("Fixture complete") } }
            compose.waitForIdle()
            compose.runOnUiThread { dispatcher.removeInput(backInput) }
            app.relayPool.disconnectAll()
            clientField.set(factory, originalClient)
            store.clear()
            store.ownPubkey = previousOwner
        }
    }
}
