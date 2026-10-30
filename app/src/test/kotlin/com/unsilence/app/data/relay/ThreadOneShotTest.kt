package com.unsilence.app.data.relay

import com.unsilence.app.ui.shared.collectLatestWhileActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Real request construction, completion and cancellation; transport alone is substituted. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadOneShotTest {
    private val relay = "wss://relay.test"

    @Test
    fun `leaving during dispatch releases only the visit request`() = runTest {
        val active = mutableSetOf("unrelated")
        val outcomes = OneShotOutcomes()
        val visit = launch {
            awaitThreadOneShot(outcomes, "visit", listOf(relay),
                dispatch = { active += "visit"; awaitCancellation() },
                cleanup = { active -= "visit" })
        }
        runCurrent()
        visit.cancel()
        visit.join()
        assertEquals(setOf("unrelated"), active)
        assertFalse(outcomes.contains("visit"))
    }

    @Test
    fun `leaving while waiting for EOSE releases the visit request`() = runTest {
        var cleaned = 0
        val outcomes = OneShotOutcomes()
        val visit = launch {
            awaitThreadOneShot(outcomes, "visit", listOf(relay), { setOf(relay) }, { cleaned++ })
        }
        runCurrent()
        visit.cancel()
        visit.join()
        assertEquals(1, cleaned)
        assertFalse(outcomes.contains("visit"))
    }

    @Test
    fun `only real EOSE certifies completion and every outcome releases tracking`() = runTest {
        for (outcome in OneShotOutcome.entries.filter { it != OneShotOutcome.CANCELLED }) {
            val outcomes = OneShotOutcomes()
            var cleaned = 0
            val result = async {
                awaitThreadOneShot(outcomes, "visit", listOf(relay), { setOf(relay) }, { cleaned++ })
            }
            runCurrent()
            outcomes.record("visit", relay, outcome)
            assertEquals(outcome == OneShotOutcome.EOSE, result.await())
            assertEquals(1, cleaned)
            assertFalse(outcomes.contains("visit"))
        }
    }

    @Test
    fun `silent relay times out without certifying completion`() = runTest {
        var cleaned = 0
        assertFalse(awaitThreadOneShot(OneShotOutcomes(), "visit", listOf(relay), { setOf(relay) }, { cleaned++ }))
        assertEquals(1, cleaned)
        assertEquals(8_000L, testScheduler.currentTime)
    }

    @Test
    fun `dispatch failure releases tracking and remains retryable`() = runTest {
        var cleaned = 0
        assertFalse(awaitThreadOneShot(OneShotOutcomes(), "visit", listOf(relay), { error("send failed") }, { cleaned++ }))
        assertEquals(1, cleaned)
    }

    @Test
    fun `request coverage and per-kind limits remain separate`() {
        val requests = threadRequestPayloads("root", "visit").map { NostrJson.parseToJsonElement(it).jsonArray }
        assertEquals(listOf("thread-event-visit", "thread-replies-visit", "thread-reactions-visit", "thread-zaps-visit"),
            requests.map { it[1].jsonPrimitive.content })
        assertEquals(listOf(3, 4, 3, 3), requests.map { it.size })
        assertEquals(setOf("ids"), requests[0][2].jsonObject.keys)
        assertEquals(listOf("root"), requests[0][2].jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
        val replies = requests[1].drop(2).map { it.jsonObject }
        assertEquals(listOf("1", "1111"), replies[0].getValue("kinds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("1111"), replies[1].getValue("kinds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("#e", "#E"), replies.map { filter -> filter.keys.single { it.startsWith("#") } })
        assertEquals(listOf("200", "200"), replies.map { it.getValue("limit").jsonPrimitive.content })
        assertEquals(listOf("7", "9735"), requests.drop(2).map { it[2].jsonObject.getValue("kinds").jsonArray.single().jsonPrimitive.content })
        assertTrue(requests.drop(2).all { it[2].jsonObject.getValue("limit").jsonPrimitive.content == "100" })
        assertTrue(requests.drop(2).all { "#E" !in it[2].jsonObject })
    }


    @Test
    fun `EOSE then hide and return sends nothing while unfinished queries retry once`() = runTest {
        for (completedCount in listOf(0, 2, 4)) {
            val active = MutableStateFlow(true)
            val root = MutableStateFlow<String?>("root")
            val completed = HashSet<Pair<String, String>>()
            val outcomes = OneShotOutcomes()
            val sent = mutableListOf<String>()
            val closed = mutableListOf<String>()
            var generation = 0
            val rows = root.flatMapLatest { id ->
                if (id == null) emptyFlow() else channelFlow {
                    launch {
                        fetchThreadRequests(id, (++generation).toString(), listOf(relay), completed,
                            fetch = { subId, targets, req, onEose ->
                                awaitThreadOneShot(outcomes, subId, targets,
                                    { sent += req; targets.toSet() }, { closed += subId }, onEose)
                            },
                            onCompleted = { query, url -> completed += query to url })
                    }
                    send(id)
                    awaitCancellation()
                }
            }
            val observer = launch { rows.collectLatestWhileActive(active) {} }
            runCurrent()
            fun ids() = sent.map { NostrJson.parseToJsonElement(it).jsonArray[1].jsonPrimitive.content }
            val firstIds = ids()
            assertEquals(4, sent.size)
            firstIds.take(completedCount).forEach { outcomes.record(it, relay, OneShotOutcome.EOSE) }
            runCurrent()
            assertEquals(completedCount, completed.size)
            active.value = false
            runCurrent()
            assertEquals(firstIds.toSet(), closed.toSet())
            active.value = true
            runCurrent()
            assertEquals(8 - completedCount, sent.size)
            val retryIds = ids().drop(4)
            assertTrue(retryIds.toSet().intersect(firstIds.toSet()).isEmpty())
            // Old callbacks cannot complete the newer visit's subscriptions.
            firstIds.forEach { outcomes.record(it, relay, OneShotOutcome.EOSE) }
            runCurrent()
            assertTrue(retryIds.all(outcomes::contains))
            retryIds.forEach { outcomes.record(it, relay, OneShotOutcome.EOSE) }
            runCurrent()
            assertEquals(4, completed.size)
            active.value = false
            runCurrent()
            active.value = true
            runCurrent()
            assertEquals(8 - completedCount, sent.size)
            completed.clear()
            root.value = "replacement"
            runCurrent()
            assertEquals(12 - completedCount, sent.size)
            root.value = null
            runCurrent()
            assertEquals(ids().toSet(), closed.toSet())
            root.value = "last"
            runCurrent()
            observer.cancel()
            observer.join()
            assertEquals(ids().toSet(), closed.toSet())
            assertEquals(closed.size, closed.distinct().size)
        }
    }

    @Test
    fun `rejecting uppercase reply filter leaves reactions and receipts active`() = runTest {
        val outcomes = OneShotOutcomes()
        val completed = HashSet<Pair<String, String>>()
        val cleaned = mutableListOf<String>()
        val visit = launch {
            fetchThreadRequests("root", "one", listOf(relay), completed,
                fetch = { subId, targets, _, onEose -> awaitThreadOneShot(outcomes, subId, targets, { targets.toSet() }, { cleaned += subId }, onEose) },
                onCompleted = { query, url -> completed += query to url })
        }
        runCurrent()
        outcomes.record("thread-replies-one", relay, OneShotOutcome.CLOSED)
        runCurrent()
        assertEquals(listOf("thread-replies-one"), cleaned)
        assertTrue(completed.isEmpty())
        assertTrue(outcomes.contains("thread-reactions-one"))
        assertTrue(outcomes.contains("thread-zaps-one"))
        visit.cancel()
        visit.join()
        assertEquals(4, cleaned.size)
    }

    @Test
    fun `cancelled queued request never dispatches after capacity returns`() = runTest {
        val capacity = CompletableDeferred<Unit>()
        var sends = 0
        var cleaned = 0
        val visit = launch {
            awaitThreadOneShot(OneShotOutcomes(), "visit", listOf(relay),
                { capacity.await(); sends++; setOf(relay) }, { cleaned++ })
        }
        runCurrent()
        visit.cancel()
        visit.join()
        capacity.complete(Unit)
        runCurrent()
        assertEquals(0, sends)
        assertEquals(1, cleaned)
    }

    @Test
    fun `cancelled owner cannot dispatch while cleanup is still waiting to run`() = runTest {
        val outcomes = OneShotOutcomes()
        var cleanups = 0
        val visit = launch {
            awaitThreadOneShot(outcomes, "thread-event-visit", listOf(relay), { setOf(relay) }, { cleanups++ })
        }
        runCurrent()
        assertTrue(outcomes.isActive("thread-event-visit"))
        visit.cancel()
        // The queue can drain from another cleanup before this coroutine's finally runs.
        assertEquals(0, cleanups)
        assertFalse(outcomes.isActive("thread-event-visit"))
        runCurrent()
        assertEquals(1, cleanups)
        assertFalse(outcomes.contains("thread-event-visit"))
        assertFalse(isOwnedThreadRequest("engagement-shared"))
        assertFalse(isOwnedThreadRequest("feed-live"))
    }


    @Test
    fun `two EOSE relays are not queried again when a third times out or visit closes`() = runTest {
        for (cancelBeforeTimeout in listOf(false, true)) {
            val targets = listOf(relay, "wss://second.test", "wss://slow.test")
            val outcomes = OneShotOutcomes()
            val completed = HashSet<Pair<String, String>>()
            val sends = mutableListOf<Pair<String, String>>()
            suspend fun fetch(token: String) = fetchThreadRequests("root", token, targets, completed,
                fetch = { subId, pending, _, onEose ->
                    awaitThreadOneShot(outcomes, subId, pending,
                        { pending.forEach { sends += subId to it }; pending.toSet() }, {}, onEose)
                },
                onCompleted = { query, url -> completed += query to url })
            val first = launch { fetch("first") }
            runCurrent()
            assertEquals(12, sends.size)
            sends.filter { it.second != targets.last() }.forEach { (id, url) -> outcomes.record(id, url, OneShotOutcome.EOSE) }
            runCurrent()
            assertEquals(8, completed.size)
            if (cancelBeforeTimeout) first.cancel()
            first.join()
            val retry = launch { fetch("retry") }
            runCurrent()
            assertEquals(16, sends.size)
            assertEquals(setOf(targets.last()), sends.drop(12).map { it.second }.toSet())
            assertEquals(4, sends.drop(12).map { it.first }.distinct().size)
            retry.cancel()
            retry.join()
        }
    }

}
