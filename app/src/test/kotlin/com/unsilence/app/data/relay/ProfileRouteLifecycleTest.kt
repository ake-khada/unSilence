package com.unsilence.app.data.relay

import com.unsilence.app.ui.shared.collectLatestWhileActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** The shared route/lifecycle pipeline used by both profile ViewModels, with real subscriptions. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRouteLifecycleTest {
    @Test fun `late metadata closes fallback before replacing and hide releases all subscriptions`() = runTest {
        val transport = FakeRelayTransport()
        val subscription = Subscription(transport, FakeTapRegistration(), FakeReconnectSource(), FakeRelayCapabilitiesStore())
        val service = TimelineService(subscription, StubEventLoader()).apply { subscribeDispatcher = Dispatchers.Unconfined }
        val active = MutableStateFlow(false)
        val author = MutableStateFlow("a".repeat(64))
        val group = MutableStateFlow(1)
        val firstWrites = MutableStateFlow(emptyList<String>())
        val secondWrites = MutableStateFlow(listOf("wss://second.test"))
        val routes = profileRelayRoutes(author, group) { if (it == "a".repeat(64)) firstWrites else secondWrites }
        val job = launch {
            routes.collectLatestWhileActive(active) { route ->
                val handle = service.subscribeTimeline(
                    listOf(SubRequest(route.urls, NostrFilter(kinds = listOf(route.group), authors = listOf(route.pubkey), limit = 300))),
                    onEvents = { _, _ -> },
                )
                try { awaitCancellation() } finally { handle.close() }
            }
        }
        runCurrent()
        assertTrue(subscription.activeSubIds().isEmpty())
        active.value = true; runCurrent()
        assertEquals(GLOBAL_RELAY_URLS.toSet(), subscription.activeRelayUrls())
        val fallbackIds = subscription.activeSubIds().toSet()
        firstWrites.value = listOf("wss://author.test")
        runCurrent(); advanceTimeBy(1_999); runCurrent()
        assertEquals(fallbackIds, subscription.activeSubIds())
        advanceTimeBy(1); runCurrent()
        assertEquals(setOf("wss://author.test"), subscription.activeRelayUrls())
        assertTrue(fallbackIds.intersect(subscription.activeSubIds()).isEmpty())
        val afterReroute = transport.sends.size
        firstWrites.value = listOf("wss://other.test")
        runCurrent(); advanceTimeBy(1_000)
        firstWrites.value = listOf("wss://author.test")
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(afterReroute, transport.sends.size)
        active.value = false; runCurrent()
        assertTrue(subscription.activeSubIds().isEmpty())
        assertEquals(0, firstWrites.subscriptionCount.value)
        val hidden = transport.sends.size
        firstWrites.value = listOf("wss://late.test")
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(hidden, transport.sends.size)
        active.value = true; runCurrent()
        assertEquals(setOf("wss://late.test"), subscription.activeRelayUrls())
        author.value = "b".repeat(64); runCurrent()
        assertEquals(setOf("wss://second.test"), subscription.activeRelayUrls())
        val beforeGroup = subscription.activeSubIds().toSet()
        group.value = 30023; runCurrent()
        assertTrue(beforeGroup.intersect(subscription.activeSubIds()).isEmpty())
        job.cancel(); runCurrent()
        assertTrue(subscription.activeSubIds().isEmpty())
    }
}
