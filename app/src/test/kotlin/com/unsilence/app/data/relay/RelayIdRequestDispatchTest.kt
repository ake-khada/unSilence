package com.unsilence.app.data.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayIdRequestDispatchTest {
    private val relay = "wss://shared.example"
    private val owner = Any()
    private val policy = RelayIdRequestBackoff(nowMs = { 1_000L })
    private val wire = mutableListOf<String>()
    private val feed = """["REQ","feed-test",{"authors":["author"],"kinds":[1],"limit":300}]"""
    private val search = """["REQ","search-notes-test",{"search":"ids and quotes","kinds":[1]}]"""
    private val engagement = """["REQ","prefetch-engagement",{"#e":["target"],"kinds":[7,9735]}]"""
    private fun idReq(subId: String) = """["REQ","$subId",{"ids":["target"],"kinds":[1]}]"""
    private fun send(req: String, socket: Any = owner) = policy.send(socket, relay, req) { wire += req; true }

    private fun backOff() {
        repeat(3) { index ->
            val id = "rejected-$index"
            assertNull(send(idReq(id)))
            policy.onClosed(owner, id, "")
        }
        wire.clear()
    }

    @Test fun `feed and search continue on the same relay while ID requests are paused`() {
        backOff()
        assertFalse(policy.shouldSkip(relay, feed))
        assertFalse(policy.shouldSkip(relay, search))
        assertNull(send(feed))
        assertNull(send(search))
        assertEquals(OneShotOutcome.SKIPPED, send(idReq("skipped")))
        assertEquals(listOf(feed, search), wire)
        policy.onEose(owner, "feed-test")
        policy.onEose(owner, "search-notes-test")
        assertTrue("Non-ID success cannot clear ID rejection history", policy.shouldSkip(relay))
    }

    @Test fun `feed search and engagement empty CLOSED do not count as ID rejection`() {
        repeat(10) {
            send(feed)
            send(search)
            send(engagement)
            assertFalse(policy.onClosed(owner, "feed-test", ""))
            assertFalse(policy.onClosed(owner, "search-notes-test", ""))
            assertFalse(policy.onClosed(owner, "prefetch-engagement", ""))
        }
        assertFalse(policy.shouldSkip(relay))
        assertNull(send(idReq("still-eligible")))
    }

    @Test fun `thread engagement and NIP22 replies stay live while only root ID lookup pauses`() {
        backOff()
        val replies = buildThreadRepliesReq("thread-replies-test", "root")
        assertNull(send(replies))
        assertNull(send(engagement))
        assertEquals(OneShotOutcome.SKIPPED, send(idReq("thread-event-test")))
        assertEquals(listOf(replies, engagement), wire)
    }

    @Test fun `classify by filter rather than prefixed subscription names`() {
        assertEquals("search-looking-name", idLookupSubId(idReq("search-looking-name")))
        assertNull(idLookupSubId(engagement))
        assertNull(idLookupSubId(feed))
        assertNull(idLookupSubId(search))
        assertEquals("parents", idLookupSubId(buildCommentParentsReq("parents", listOf("comment"))))
    }

    @Test fun `an OR REQ containing a non-ID filter is not suppressed wholesale`() {
        backOff()
        val mixed = """["REQ","mixed",{"ids":["id"]},{"search":"hello","kinds":[1]}]"""
        assertNull(idLookupSubId(mixed))
        assertFalse(policy.shouldSkip(relay, mixed))
        assertNull(send(mixed))
        policy.onClosed(owner, "mixed", "")
        assertTrue(policy.shouldSkip(relay))
        assertEquals(listOf(mixed), wire)
    }

    @Test fun `multiple ID-only OR filters are all subject to backoff`() {
        backOff()
        val ids = """["REQ","ids-or",{"ids":["a"],"kinds":[1]},{"ids":["b"],"kinds":[1111]}]"""
        assertEquals("ids-or", idLookupSubId(ids))
        assertEquals(OneShotOutcome.SKIPPED, send(ids))
        assertTrue(wire.isEmpty())
    }

    @Test fun `search with explicit IDs remains search and is not suppressed`() {
        backOff()
        val filteredSearch = """["REQ","search",{"ids":["id"],"search":"term","kinds":[1]}]"""
        assertNull(idLookupSubId(filteredSearch))
        assertNull(send(filteredSearch))
        assertEquals(listOf(filteredSearch), wire)
    }

    @Test fun `malformed and non-REQ frames cannot acquire ID failure ownership`() {
        for (req in listOf(
            "not json", """["REQ","x",{"ids":[]}]""", """["REQ","x",{"ids":null}]""",
            """["REQ","x",{"ids":[1]}]""", """["REQ","x",{"ids":[""]}]""",
            """["EVENT",{"ids":["x"]}]""", """["REQ","x",{"#e":["id"]}]""",
        )) assertNull(req, idLookupSubId(req))
    }

    @Test fun `final send rechecks requests admitted before a queue wait`() {
        val queued = idReq("queued-before-backoff")
        assertFalse(policy.shouldSkip(relay, queued))
        backOff()
        assertEquals(OneShotOutcome.SKIPPED, send(queued))
        assertNull(send(search))
        assertEquals(listOf(search), wire)
        assertFalse("An unsent request is not a fourth strike", policy.onClosed(owner, "queued-before-backoff", ""))
    }

    @Test fun `pooled and ephemeral owners share relay backoff but not subscription ownership`() {
        val ephemeral = Any()
        assertNull(send(idReq("e1"), ephemeral))
        assertNull(send(idReq("p1")))
        policy.onClosed(ephemeral, "e1", "")
        policy.onClosed(owner, "p1", "")
        assertNull(send(idReq("e2"), ephemeral))
        assertTrue(policy.onClosed(ephemeral, "e2", ""))
        assertEquals(OneShotOutcome.SKIPPED, send(idReq("p2")))
        assertEquals(OneShotOutcome.SKIPPED, send(idReq("e3"), ephemeral))
        assertNull(send(search, ephemeral))
        policy.forgetOwner(ephemeral)
        assertTrue("Closing an ephemeral socket is not request recovery", policy.shouldSkip(relay))
    }

    @Test fun `synthetic terminal outcomes finish waiters but never reset ID failures`() = runTest {
        backOff()
        val outcomes = OneShotOutcomes()
        for (ending in listOf(OneShotOutcome.CLOSED, OneShotOutcome.SKIPPED, OneShotOutcome.FAILED)) {
            val subId = "synthetic-${ending.name}"
            val fetch = async {
                outcomes.fetch(
                    subId, listOf(relay), 500,
                    dispatch = { admitted ->
                        admitted(relay)
                        outcomes.record(subId, relay, ending)
                        setOf(relay)
                    },
                    cleanup = { policy.forgetSubscription(subId) },
                )
            }
            runCurrent()
            assertEquals(ending, fetch.await()[relay])
            assertTrue(policy.shouldSkip(relay))
        }
        // A watchdog/deadline is local completion, not an invocation of onEose.
        val timedOut = outcomes.fetch(
            "watchdog", listOf(relay), 500,
            dispatch = { setOf(relay) }, cleanup = { policy.forgetSubscription("watchdog") },
        )
        assertEquals(OneShotOutcome.TIMEOUT, timedOut[relay])
        assertTrue(policy.shouldSkip(relay))
    }

    @Test fun `all backed-off targets complete without spending the response timeout`() = runTest {
        backOff()
        val outcomes = OneShotOutcomes()
        val start = testScheduler.currentTime
        val result = outcomes.fetch(
            "suppressed", listOf(relay), 8_000,
            dispatch = {
                if (policy.shouldSkip(relay, idReq("suppressed"))) emptySet() else setOf(relay)
            },
            cleanup = { policy.forgetSubscription("suppressed") },
        )
        assertEquals(OneShotOutcome.SKIPPED, result[relay])
        assertEquals(start, testScheduler.currentTime)
        assertTrue(wire.isEmpty())
    }
}
