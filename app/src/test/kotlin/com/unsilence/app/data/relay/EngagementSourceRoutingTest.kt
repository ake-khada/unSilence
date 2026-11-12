package com.unsilence.app.data.relay

import com.unsilence.app.data.relay.CardHydrator.EngagementTarget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EngagementSourceRoutingTest {
    private val source = "wss://source.test"
    private val other = "wss://other.test"

    @Test fun `only actual target provenance opts in not a repost wrapper or declared hint`() {
        assertEquals(setOf(source), observedEngagementRelays("note", "note", "$source/", emptyList()))
        assertTrue(observedEngagementRelays("wrapper", "target", source, emptyList()).isEmpty())
        assertEquals(setOf(other), observedEngagementRelays("wrapper", "target", source, listOf(other)))
        assertEquals(listOf(other), engagementRelayTargets(listOf(source, other, source), setOf(other), source))
        assertEquals(listOf(source, other), engagementRelayTargets(listOf("$source/", other), setOf(source), "$source/"))
        assertEquals(listOf(source, other), engagementRelayTargets(listOf(source, other), emptySet(), null))
    }

    @Test fun `mixed chunks exclude unrelated targets from source query without widening any limit`() = runTest {
        val seen = EngagementTarget("seen", null, "author", 1, observedRelays = setOf(source))
        val unseen = EngagementTarget("unseen", null, "author", 1, sourceRelays = listOf(source))
        val targets = listOf(seen, unseen)
        val routes = targets.associate { it.id to engagementRelayTargets(listOf(source, other), it.observedRelays, source) }
        val batches = coalesceByRelay(routes, 12, ENGAGEMENT_BATCH_CHUNK)
        val sent = mutableMapOf<String, Pair<List<String>, Boolean>>()
        val coordinator = EngagementFetchCoordinator({ _, relays, json, _, allowSource ->
            val filters = NostrJson.parseToJsonElement(json).jsonArray.drop(2).map { it.jsonObject }
            assertEquals(500, filters.sumOf { it.getValue("limit").jsonPrimitive.int })
            assertEquals(listOf(1, 1111, 6, 16, 7, 9735),
                filters.flatMap { it.getValue("kinds").jsonArray.map { it.jsonPrimitive.int } }.distinct())
            sent[relays.single()] = filters.flatMap { it.getValue("#e").jsonArray.map { it.jsonPrimitive.content } }
                .distinct() to allowSource
            relays.associateWith { OneShotOutcome.EOSE }
        })
        coordinator.fetch(targets, batches)
        assertEquals(listOf("seen") to true, sent[source])
        assertEquals(listOf("seen", "unseen") to false, sent[other])
    }

    @Test fun `late active relay switch cannot opt in unrelated planned targets`() = runTest {
        val target = EngagementTarget("target", null, "author", 1, observedRelays = setOf(source))
        val routes = engagementRelayTargets(listOf(source, other), target.observedRelays, source)
        val coordinator = EngagementFetchCoordinator({ _, relays, _, _, allowSource ->
            // The feed changed AFTER routing: transport evaluates its current relay.
            val actual = oneShotRelayTargets(relays, other, allowSource)
            if (relays.single() == other) assertTrue(actual.isEmpty())
            else assertEquals(listOf(source), actual)
            relays.associateWith { if (it in actual) OneShotOutcome.EOSE else OneShotOutcome.SKIPPED }
        })
        coordinator.fetch(listOf(target), routes.map { it to listOf(target.id) })
    }
}
