package com.unsilence.app.ui.shared

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.ProfileResolver
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.ui.navigation.AppDestination
import com.unsilence.app.ui.navigation.AppEntry
import com.unsilence.app.ui.navigation.AppNavDisplay
import com.unsilence.app.ui.navigation.AppNavigator
import com.unsilence.app.ui.navigation.rememberAppNavigator
import com.unsilence.app.ui.onboarding.SessionContent
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Counts real MES subscriptions in an offline fixture, not real-account heap retention. */
@RunWith(AndroidJUnit4::class)
class CardSubscriptionLifetimeTest {
    @get:Rule val compose = createComposeRule()
    private val store = MemoryEventStore(object : MuteKeyProvider {}, javax.inject.Provider {
        error("This fixture must not open a timeline")
    })
    private val resolver = ProfileResolver(store, dagger.Lazy<RelayPool> {
        error("This fixture must not fetch from relays")
    })
    private val users = UserRepository(store, resolver)
    private val profiles get() = (store.profileSignalFlow as MutableStateFlow<Long>).subscriptionCount.value

    // Test-only access: no diagnostic API or counter work in the shipped store.
    private val stats get() = (MemoryEventStore::class.java.getDeclaredField("_statsInvalidations")
        .apply { isAccessible = true }.get(store) as MutableSharedFlow<*>).subscriptionCount.value

    private class Cards(val data: TimelineCardData) : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    @After fun closeResolver() { resolver.clear() }

    @Composable private fun Card(id: String, data: TimelineCardData) {
        val profile = data.profileFlow("author-$id")
        val count = data.statsFlow("note-$id")
        val user by profile.collectAsStateWithLifecycle(initialValue = profile.value)
        val engagement by count.collectAsStateWithLifecycle(initialValue = count.value)
        Text("${user?.name.orEmpty()} ${engagement.replyCount}")
    }

    @Test fun fiftyNavigationCyclesReleaseRealCardSubscriptionsAndEntryStores() {
        lateinit var navigator: AppNavigator
        val entries = mutableMapOf<String, Cards>()
        compose.setContent {
            UnsilenceTheme {
                SessionContent("fixture") {
                    val nav = rememberAppNavigator("fixture")
                    SideEffect { navigator = nav }
                    AppNavDisplay(nav) { entry ->
                        val cards = viewModel { Cards(TimelineCardData(users, store)) }
                        SideEffect { entries[entry.id] = cards }
                        repeat(4) { Card("${entry.id}-$it", cards.data) }
                    }
                }
            }
        }
        awaitCounts(4)
        repeat(50) { visit ->
            val child = compose.runOnIdle {
                navigator.push(AppDestination.Profile("fixture-$visit"))
                (navigator.backStack.last() as AppEntry).id
            }
            awaitCounts(4)
            compose.runOnIdle { navigator.pop() }
            awaitCounts(4)
            compose.runOnIdle { assertTrue("Entry $visit was not cleared", entries.remove(child)!!.cleared) }
        }
        assertEquals(1, entries.size)
    }

    @Test fun cachedAndEvictedHandlesDoNotSubscribeUntilCollected() {
        val data = TimelineCardData(users, store)
        val heldProfile = data.profileFlow("held")
        val heldStats = data.statsFlow("held")
        repeat(1_200) { data.profileFlow("author-$it"); data.statsFlow("note-$it") }
        assertEquals(0, profiles)
        assertEquals(0, stats)
        val visible = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            if (visible.value) {
                val user by heldProfile.collectAsStateWithLifecycle(initialValue = heldProfile.value)
                val engagement by heldStats.collectAsStateWithLifecycle(initialValue = heldStats.value)
                Text("${user?.name.orEmpty()} ${engagement.replyCount}")
            }
        }
        awaitCounts(1)
        compose.runOnIdle { visible.value = false }
        awaitCounts(0)
        compose.runOnIdle { visible.value = true }
        awaitCounts(1)
    }

    private fun awaitCounts(expected: Int) {
        compose.waitForIdle()
        compose.waitUntil(5_000) { profiles == expected && stats == expected }
        assertEquals(expected, profiles)
        assertEquals(expected, stats)
    }
}
