package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.RelayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayRoutingUpdatesTest {
    @Test fun `same size followed write migration changes revision`() = runTest {
        val lists = MutableStateFlow(mapOf("alice" to RelayList(emptyList(), listOf("wss://a.test"))))
        val follows = MutableStateFlow(setOf("alice"))
        val revisions = mutableListOf<Int>()
        val job = backgroundScope.launch { followingRelayMetadataRevision(lists, follows).collect { revisions += it } }
        runCurrent(); advanceTimeBy(RELAY_METADATA_DEBOUNCE_MS); runCurrent()
        val before = revisions.last()
        lists.value = mapOf("alice" to RelayList(emptyList(), listOf("wss://b.test")))
        runCurrent(); advanceTimeBy(RELAY_METADATA_DEBOUNCE_MS); runCurrent()
        assertNotEquals("A to B must invalidate the active route", before, revisions.last())
        job.cancel()
    }

    @Test fun `unrelated authors cannot advance Following routing revision`() = runTest {
        val lists = MutableStateFlow(emptyMap<String, RelayList>())
        val follows = MutableStateFlow(setOf("alice"))
        val revisions = mutableListOf<Int>()
        backgroundScope.launch { followingRelayMetadataRevision(lists, follows).collect { revisions += it } }
        runCurrent(); advanceTimeBy(RELAY_METADATA_DEBOUNCE_MS); runCurrent()
        val before = revisions.last()
        lists.value = mapOf("stranger" to RelayList(emptyList(), listOf("wss://other.test")))
        runCurrent(); advanceTimeBy(RELAY_METADATA_DEBOUNCE_MS); runCurrent()
        assertEquals(before, revisions.last())
    }

    @Test fun `late profile write list replaces fallback without tab or identity change`() = runTest {
        val writes = MutableStateFlow(emptyList<String>())
        val routes = mutableListOf<ProfileRelayRoute<String>>()
        backgroundScope.launch { profileRelayRoutes(MutableStateFlow("alice"), MutableStateFlow("notes")) { writes }.collect { routes += it } }
        runCurrent()
        assertEquals(GLOBAL_RELAY_URLS, routes.single().urls)
        writes.value = listOf("wss://alice.test")
        runCurrent(); advanceTimeBy(RELAY_METADATA_DEBOUNCE_MS); runCurrent()
        assertEquals(listOf("wss://alice.test"), routes.last().urls)
    }
    @Test fun `settled content suppresses reorders read changes and rapid reverts`() = runTest {
        val a = RelayList(emptyList(), listOf("wss://a.test"))
        val lists = MutableStateFlow(mapOf("alice" to a))
        val revisions = mutableListOf<Int>()
        backgroundScope.launch { followingRelayMetadataRevision(lists, MutableStateFlow(setOf("alice"))).collect { revisions += it } }
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        val before = revisions.toList()
        lists.value = mapOf("alice" to RelayList(listOf("wss://read.test"), listOf("wss://a.test/", "wss://a.test")))
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(before, revisions)
        lists.value = mapOf("alice" to RelayList(emptyList(), listOf("wss://b.test")))
        runCurrent(); advanceTimeBy(1_000)
        lists.value = mapOf("alice" to a)
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals("rapid revert must not restart", before, revisions)
        lists.value = mapOf("alice" to RelayList(emptyList(), listOf("wss://b.test")))
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        lists.value = mapOf("alice" to a)
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(before.size + 2, revisions.size)
        lists.value = emptyMap()
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals("removing an existing route must reroute", before.size + 3, revisions.size)
    }

    @Test fun `cold start arrival waves retain coarse coverage thresholds`() = runTest {
        val authors = (0 until 300).map { "author-$it" }
        val lists = MutableStateFlow(emptyMap<String, RelayList>())
        val revisions = mutableListOf<Int>()
        backgroundScope.launch { followingRelayMetadataRevision(lists, MutableStateFlow(authors.toSet())).collect { revisions += it } }
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        for (count in listOf(60, 120, 180, 240, 300)) {
            lists.value = authors.take(count).associateWith { RelayList(emptyList(), listOf("wss://a.test")) }
            runCurrent(); advanceTimeBy(3_000); runCurrent()
        }
        assertEquals(listOf(0, 1, 2, 3, 4), revisions)
    }

    @Test fun `profile rapid revert does not cancel current route and group switches are immediate`() = runTest {
        val writes = MutableStateFlow(listOf("wss://a.test"))
        val groups = MutableStateFlow("notes")
        val routes = mutableListOf<ProfileRelayRoute<String>>()
        backgroundScope.launch { profileRelayRoutes(MutableStateFlow("alice"), groups) { writes }.collect { routes += it } }
        runCurrent()
        assertEquals(1, routes.size)
        writes.value = listOf("wss://b.test")
        runCurrent(); advanceTimeBy(1_000)
        writes.value = listOf("wss://a.test")
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(1, routes.size)
        groups.value = "longform"
        runCurrent()
        assertEquals("longform", routes.last().group)
        assertEquals(2, routes.size)
    }

}
