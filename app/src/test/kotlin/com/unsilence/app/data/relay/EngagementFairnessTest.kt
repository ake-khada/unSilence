package com.unsilence.app.data.relay

import com.unsilence.app.data.relay.CardHydrator.EngagementTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngagementFairnessTest {
    private fun id(n: Int) = n.toString(16).padStart(64, '0')
    private fun event(n: Int, kind: Int, target: String, time: Long) = EventDto(
        id = id(n), pubkey = id(9_999), createdAt = time, kind = kind,
        tags = listOf(listOf("e", target)), content = "fixture", sig = "0".repeat(128),
    )
    private fun request(ids: List<String>, coords: List<String> = emptyList()) =
        NostrJson.parseToJsonElement(buildBatchedEngagementReq("eng-fixture", ids, coords)).jsonArray

    /** The same dataset defeats both a merged filter and a single shared reply filter. */
    private fun crowdedHistory(targets: List<String>): List<EventDto> =
        targets.flatMapIndexed { index, target ->
            List(500) { n -> event(50_000 + index * 500 + n, listOf(6, 16, 7, 9735)[n % 4],
                target, 20_000L + index * 500 + n) }
        } +
            targets.flatMapIndexed { index, target ->
                List(300) { n -> event(1_000 + index * 300 + n, if (n % 2 == 0) 1 else 1111,
                    target, (if (index == 0) 10_000L else 1_000L) + n) }
            }

    private fun assertFairHistory(size: Int) {
        val targets = (1..size).map { id(10_000 + it) }
        val history = crowdedHistory(targets)
        val wire = request(targets)
        val result = engagementHistory(wire, history)
        assertEquals("eng-fixture", wire[1].jsonPrimitive.content)
        for (target in targets) {
            val thread = NostrJson.parseToJsonElement(buildThreadRepliesReq("thread-fixture", target)).jsonArray
            assertEquals("Dedicated retrieval finds replies hidden by newer engagement", 200,
                engagementHistory(thread, history).size)
            assertEquals("Each post reserves reply matches despite a busier neighbour", 250 / size,
                result.count { it.kind in listOf(1, 1111) && it.tags.single()[1] == target })
            assertEquals("Every card also retains its non-reply allowance", 250 / size,
                result.count { it.kind in listOf(6, 16, 7, 9735) && it.tags.single()[1] == target })
        }
        assertEquals(size * (250 / size), result.count { it.kind in listOf(6, 16, 7, 9735) })
        assertTrue(listOf(6, 16, 7, 9735).all { kind -> result.any { it.kind == kind } })
        assertEquals(2 * size * (250 / size), result.size)
        assertTrue(result.size <= 500)
        assertEquals(result.size, result.map { it.id }.distinct().size)
    }

    @Test fun `one target reserves replies beside busy engagement`() = assertFairHistory(1)
    @Test fun `two targets reserve replies independently`() = assertFairHistory(2)
    @Test fun `three targets floor their shares without exceeding budget`() = assertFairHistory(3)
    @Test fun `four targets reserve replies independently`() = assertFairHistory(4)
    @Test fun `five targets cannot crowd each others replies`() = assertFairHistory(5)

    @Test fun `duplicate targets cannot consume another reservation`() {
        val targets = listOf(id(10_001), id(10_002))
        assertEquals(request(targets), request(listOf(targets[0], targets[0], targets[1])))
    }

    @Test fun `reply-only and non-reply-only history cannot borrow the other allowance`() {
        val targets = listOf(id(10_001))
        val history = crowdedHistory(targets)
        for (kinds in listOf(setOf(1, 1111), setOf(6, 16, 7, 9735))) {
            assertEquals(250, engagementHistory(request(targets), history.filter { it.kind in kinds }).size)
        }
    }

    @Test fun `per-card non-reply share returns quiet engagement without borrowing idle reply capacity`() {
        val busy = id(10_001)
        val quiet = id(10_002)
        val history = List(300) { event(it + 1, 7, busy, 20_000L + it) } +
            event(501, 7, quiet, 10_000) + event(502, 6, quiet, 10_001) +
            event(503, 9735, quiet, 10_002) +
            List(200) { event(1_000 + it, 1, quiet, 1_000L + it) }
        // The accepted baseline's merged direct filter, retained as a comparison
        // fixture. This is a reservation tradeoff, not evidence of local deletion.
        val legacy = NostrJson.parseToJsonElement(
            """["REQ","legacy",{"kinds":[1,1111,6,16,7,9735],"#e":["$busy","$quiet"],"limit":500}]""",
        ).jsonArray
        val before = engagementHistory(legacy, history)
        val after = engagementHistory(request(listOf(busy, quiet)), history)
        assertEquals(3, before.count { it.kind != 1 && it.tags.single()[1] == quiet })
        assertEquals(3, after.count { it.kind != 1 && it.tags.single()[1] == quiet })
        assertEquals(197, before.count { it.kind == 1 && it.tags.single()[1] == quiet })
        assertEquals(125, after.count { it.kind == 1 && it.tags.single()[1] == quiet })
        assertEquals(500, before.size)
        assertEquals(253, after.size) // The unused busy target's reply share is not reassigned.
    }

    @Test fun `root and parent matches overlap without inflating the event union`() {
        val targets = listOf(id(10_001), id(10_002))
        val history = List(150) { n ->
            event(n + 1, 1, targets[0], 1_000).copy(tags = listOf(
                listOf("e", targets[0], "", "root"), listOf("e", targets[1], "", "reply")))
        }.reversed()
        val result = engagementHistory(request(targets), history)
        assertEquals(125, result.size)
        assertEquals((1..125).map(::id), result.map { it.id }) // lexical tie-break, not fixture input order
    }

    @Test fun `coordinate filters retain their kinds and separate historical allowances`() {
        val coord = "30023:${id(9_999)}:fixture"
        val wire = request(listOf(id(10_001)), listOf(coord))
        val coordinateFilters = wire.drop(2).map { it.jsonObject }.filter { "#a" in it || "#A" in it }
        assertEquals(listOf(
            """{"kinds":[7,9735],"#a":["$coord"],"limit":500}""",
            """{"kinds":[7,9735,1111],"#A":["$coord"],"limit":500}""",
        ), coordinateFilters.map { it.toString() })
        val history = List(600) { n -> event(n + 1, 7, id(10_001), n.toLong()).copy(tags = listOf(listOf("a", coord))) } +
            List(600) { n -> event(1_000 + n, 1111, id(10_001), n.toLong()).copy(tags = listOf(listOf("A", coord))) }
        val result = engagementHistory(wire, history)
        assertEquals(1_000, result.size) // The direct 500 allowance is not a whole-REQ cap.
        assertEquals(result, engagementHistory(request(emptyList(), listOf(coord)), history))
        assertTrue(engagementHistory(request(emptyList()), history).isEmpty())
    }

    @Test fun `oversized direct chunk fails explicitly instead of dropping targets`() {
        assertThrows(IllegalArgumentException::class.java) { request((1..6).map(::id)) }
    }

    @Test fun `one EOSE completes all partitioned filters without extra jobs or destinations`() = runTest {
        val targets = (1..5).map { EngagementTarget(id(10_000 + it), null, id(9_999), 1_000) }
        val history = crowdedHistory(targets.map { it.id })
        val urls = listOf("wss://first.test", "wss://second.test")
        val outcomes = OneShotOutcomes()
        val requests = mutableListOf<Pair<String, String>>()
        val cleaned = mutableListOf<String>()
        val coordinator = EngagementFetchCoordinator(fetchRequest = { subId, relays, json, timeout, _ ->
            outcomes.fetch(subId, relays, timeout, {
                val wire = NostrJson.parseToJsonElement(json).jsonArray
                assertEquals(subId, wire[1].jsonPrimitive.content)
                val result = engagementHistory(wire, history)
                assertEquals(250, result.count { it.kind in listOf(1, 1111) })
                for (url in relays) {
                    requests += url to subId
                    outcomes.record(subId, url, OneShotOutcome.EOSE)
                }
                relays.toSet()
            }, { cleaned += subId })
        }, nowMs = { 2_000_000_000_000L + testScheduler.currentTime })
        val batches = urls.map { it to targets.map(EngagementTarget::id) }
        coordinator.fetch(targets, batches)
        coordinator.fetch(targets, batches)
        assertEquals(urls, requests.map { it.first })
        assertEquals(2, requests.map { it.second }.distinct().size)
        assertEquals(requests.map { it.second }.toSet(), cleaned.toSet())
        assertTrue(targets.none { coordinator.needsFetch(it) })
    }

    @Test fun `multi-filter refusal keeps bounded retry instead of recording successful freshness`() = runTest {
        val target = EngagementTarget(id(10_001), null, id(9_999), 1_000)
        val urls = listOf("wss://first.test", "wss://second.test")
        val requests = mutableListOf<Pair<String, String>>()
        var rejectSecond = true
        val coordinator = EngagementFetchCoordinator(fetchRequest = { subId, relays, json, _, _ ->
            assertEquals(2, NostrJson.parseToJsonElement(json).jsonArray.size - 2)
            relays.associateWith { url ->
                requests += url to subId
                if (rejectSecond && url == urls[1]) OneShotOutcome.CLOSED else OneShotOutcome.EOSE
            }
        }, nowMs = { 2_000_000_000_000L + testScheduler.currentTime })
        val batches = urls.map { it to listOf(target.id) }
        coordinator.fetch(listOf(target), batches)
        coordinator.fetch(listOf(target), batches)
        assertEquals(2, requests.size)
        advanceTimeBy(29_999)
        assertFalse(coordinator.needsFetch(target))
        advanceTimeBy(1)
        assertTrue(coordinator.needsFetch(target))
        rejectSecond = false
        coordinator.fetch(listOf(target), batches)
        assertEquals(listOf(urls[0], urls[1], urls[1]), requests.map { it.first })
        assertEquals(3, requests.map { it.second }.distinct().size)
        advanceTimeBy(600_000)
        assertFalse(coordinator.needsFetch(target))
    }
}
