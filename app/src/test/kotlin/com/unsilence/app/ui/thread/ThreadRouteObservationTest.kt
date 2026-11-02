package com.unsilence.app.ui.thread

import com.unsilence.app.data.relay.OneShotOutcome
import com.unsilence.app.data.relay.OneShotOutcomes
import com.unsilence.app.data.relay.awaitThreadOneShot
import com.unsilence.app.data.relay.fetchThreadRequests
import com.unsilence.app.ui.shared.collectLatestWhileActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import com.unsilence.app.data.relay.toNostrEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadRouteObservationTest {
    private val write = "wss://write.test"
    private val inbox = "wss://inbox.test"

    @Test fun `same root route refinement cancels pending work preserves EOSE and resumes latest route`() = runTest {
        val old = ThreadRelayRoute("root", listOf(write), listOf(write))
        val refined = old.copy(replyRelays = listOf(inbox, write))
        val route = MutableStateFlow<ThreadRelayRoute?>(old)
        val feed = MutableStateFlow(0L)
        val active = MutableStateFlow(true)
        val outcomes = OneShotOutcomes()
        val completed = HashSet<Pair<String, String>>()
        val sent = mutableListOf<Pair<String, List<String>>>()
        val cleaned = mutableListOf<String>()
        var token = 0
        val observer = launch {
            route.withThreadCoordinate(feed, { null }).flatMapLatest { (current, _) ->
                flow {
                    fetchThreadRequests(current.rootId, "${++token}", current.compatibilityRelays, completed,
                        fetch = { subId, targets, _, onEose ->
                            sent += subId to targets
                            awaitThreadOneShot(outcomes, subId, targets, { targets.toSet() }, { cleaned += subId }, onEose)
                        },
                        onCompleted = { query, relay -> completed += query to relay },
                        replyRelays = current.replyRelays,
                    )
                    emit(Unit)
                }
            }.collectLatestWhileActive(active) {}
        }
        runCurrent()
        outcomes.record("thread-event-1", write, OneShotOutcome.EOSE)
        outcomes.record("thread-replies-1", write, OneShotOutcome.EOSE)
        runCurrent()
        assertEquals(setOf("thread-event" to write, "thread-replies" to write), completed)
        route.value = refined
        runCurrent()
        assertEquals(4, cleaned.size)
        assertFalse(outcomes.contains("thread-reactions-1"))
        assertEquals(listOf(inbox), sent.single { it.first == "thread-replies-2" }.second)
        assertFalse(sent.any { it.first == "thread-event-2" })
        assertEquals(listOf(write), sent.single { it.first == "thread-zaps-2" }.second)
        // A late completion from the retired request cannot certify its replacement.
        outcomes.record("thread-reactions-1", write, OneShotOutcome.EOSE)
        runCurrent()
        assertFalse("thread-reactions" to write in completed)
        feed.value++
        runCurrent()
        assertEquals(2, token)
        active.value = false
        runCurrent()
        assertFalse(outcomes.contains("thread-replies-2"))
        val hiddenRoute = refined.copy(replyRelays = listOf("wss://new-inbox.test", inbox, write))
        route.value = hiddenRoute
        runCurrent()
        assertEquals(2, token)
        active.value = true
        runCurrent()
        assertEquals(listOf("wss://new-inbox.test", inbox), sent.single { it.first == "thread-replies-3" }.second)
        assertFalse(sent.any { it.first == "thread-event-3" })
        observer.cancel()
        observer.join()
        assertEquals(sent.map { it.first }.toSet(), cleaned.toSet())
    }

    @Test fun `MES root arriving after unresolved walk refreshes once and preserves EOSE and displayed replies`() = runTest {
        for (arrivesWhileHidden in listOf(false, true)) {
            val crypto = com.unsilence.app.data.zap.ZapCryptoTestFixture()
            val root = crypto.signedEvent(crypto.sender, 1, emptyList(), "root")
            val reply = crypto.signedEvent(crypto.recipient, 1, listOf(listOf("e", root.id)), "visible reply")
            val store = com.unsilence.app.data.memory.MemoryEventStore(
                object : com.unsilence.app.data.auth.MuteKeyProvider {},
                com.unsilence.app.data.relay.stubTimelineServiceProvider(),
            )
            store.insert(crypto.signedEvent(crypto.sender, 10002,
                listOf(listOf("r", write, "write"), listOf("r", inbox, "read")), "").toNostrEvent(write))
            store.insert(reply.toNostrEvent(write))
            assertEquals(root.id, walkThreadAncestors(reply.id, store::getEventEntity, { _, _ -> }))
            assertNull(store.getEventEntity(root.id))
            val visibleBefore = store.threadFeedRowFlow(root.id).first().single { it.id == reply.id }
            val resolver = com.unsilence.app.data.relay.OutboxRelayResolver(store)
            fun currentRoute() = resolveThreadRelayRoute(root.id, reply.id, store::getEventEntity,
                store::lookupWriteRelaysFor, resolver, listOf(write), emptySet(), emptyList())
            val route = MutableStateFlow<ThreadRelayRoute?>(currentRoute())
            val active = MutableStateFlow(true)
            val observed = mutableListOf<ThreadRelayRoute>()
            val sent = mutableListOf<Pair<String, List<String>>>()
            val completed = HashSet<Pair<String, String>>()
            val outcomes = OneShotOutcomes()
            var token = 0
            var refreshes = 0
            var missingChecks = 0
            val observer = launch {
                route.withThreadCoordinate(store.feedSignalFlow, store::articleCoordForEvent,
                    rootIsKnown = { missingChecks++; store.getEventEntity(it) != null },
                    refreshMissingRoot = { refreshes++; currentRoute() },
                ).flatMapLatest { (value, _) ->
                    flow {
                        observed += value
                        fetchThreadRequests(value.rootId, "${++token}", value.compatibilityRelays, completed,
                            fetch = { sub, targets, _, onEose ->
                                sent += sub to targets
                                awaitThreadOneShot(outcomes, sub, targets, { targets.toSet() }, {}, onEose)
                            }, onCompleted = { query, relay -> completed += query to relay },
                            replyRelays = value.replyRelays)
                        emit(Unit)
                    }
                }.collectLatestWhileActive(active) {}
            }
            runCurrent()
            assertEquals(listOf(write), observed.single().replyRelays)
            outcomes.record("thread-event-1", write, OneShotOutcome.EOSE)
            outcomes.record("thread-replies-1", write, OneShotOutcome.EOSE)
            runCurrent()
            assertEquals(0, refreshes)
            if (arrivesWhileHidden) { active.value = false; runCurrent() }
            // The walk is finished. Only MES insert/signaling, never manual route assignment.
            store.insert(root.toNostrEvent(write))
            runCurrent()
            if (arrivesWhileHidden) {
                assertEquals(0, refreshes)
                active.value = true
                runCurrent()
            }
            assertEquals(1, refreshes)
            assertEquals(2, observed.size)
            assertEquals(crypto.sender.pubkey, route.value?.rootAuthor)
            assertEquals(listOf(inbox, write), observed.last().replyRelays)
            assertEquals(listOf(inbox), sent.single { it.first == "thread-replies-2" }.second)
            assertFalse(sent.any { it.first == "thread-event-2" })
            assertEquals(setOf("thread-event" to write, "thread-replies" to write), completed)
            val visibleAfter = store.threadFeedRowFlow(root.id).first().single { it.id == reply.id }
            assertEquals(visibleBefore.id, visibleAfter.id)
            assertEquals(visibleBefore.content, visibleAfter.content)
            val checksAtResolution = missingChecks
            repeat(3) { index ->
                store.insert(crypto.signedEvent(crypto.recipient, 1, emptyList(), "unrelated $index").toNostrEvent(write))
                runCurrent()
            }
            assertEquals(checksAtResolution, missingChecks)
            assertEquals(2, observed.size)
            assertEquals(1, refreshes)
            active.value = false
            runCurrent()
            active.value = true
            runCurrent()
            assertEquals(listOf(inbox, write), observed.last().replyRelays)
            assertEquals(1, refreshes)
            observer.cancel()
            observer.join()
        }
    }

    @Test fun `only reply targets change and all four payloads retain their wire contract`() = runTest {
        val sent = linkedMapOf<String, Pair<List<String>, String>>()
        fetchThreadRequests("root", "visit", listOf(write), emptySet(),
            fetch = { id, targets, req, _ -> sent[id] = targets to req }, onCompleted = { _, _ -> },
            replyRelays = listOf(inbox, write))
        val expected = listOf(
            """["REQ","thread-event-visit",{"ids":["root"]}]""",
            """["REQ","thread-replies-visit",{"kinds":[1,1111],"#e":["root"],"limit":200},{"kinds":[1111],"#E":["root"],"limit":200}]""",
            """["REQ","thread-reactions-visit",{"kinds":[7],"#e":["root"],"limit":100}]""",
            """["REQ","thread-zaps-visit",{"kinds":[9735],"#e":["root"],"limit":100}]""",
        )
        assertEquals(expected, sent.values.map { it.second })
        assertEquals(listOf(listOf(write), listOf(inbox, write), listOf(write), listOf(write)), sent.values.map { it.first })
    }
}
