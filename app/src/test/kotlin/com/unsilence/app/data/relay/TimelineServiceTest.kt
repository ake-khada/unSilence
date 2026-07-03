package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class TimelineServiceTest {

    private lateinit var transport: FakeRelayTransport
    private lateinit var tapRegistry: FakeTapRegistration
    private lateinit var eventLoader: FakeEventLoader
    private lateinit var subscription: Subscription
    private lateinit var service: TimelineService

    @Before
    fun setUp() {
        transport = FakeRelayTransport()
        tapRegistry = FakeTapRegistration()
        eventLoader = FakeEventLoader()
        subscription = Subscription(
            transport,
            tapRegistry,
            FakeReconnectSource(),
            FakeRelayCapabilitiesStore(),
            FakeSignatureVerifier(),
        )
        service = TimelineService(subscription, eventLoader)
        service.subscribeDispatcher = Dispatchers.Unconfined
    }

    @Test
    fun `single subRequest pre-EOSE buffers events sorted desc by createdAt`() = runTest {
        val emitted = CopyOnWriteArrayList<Pair<List<NostrEvent>, Boolean>>()
        val subRequest = SubRequest(
            urls = listOf("wss://a.example"),
            filter = NostrFilter(kinds = listOf(1), limit = 10),
        )
        service.subscribeTimeline(
            subRequests = listOf(subRequest),
            onEvents = { events, eosed -> emitted.add(events to eosed) },
        )
        val subId = transport.lastReqSubId()
        tapRegistry.fire(eventMessage(subId, id = "a".repeat(64), createdAt = 100), "wss://a.example")
        tapRegistry.fire(eventMessage(subId, id = "b".repeat(64), createdAt = 200), "wss://a.example")
        tapRegistry.fire(eventMessage(subId, id = "c".repeat(64), createdAt = 150), "wss://a.example")
        tapRegistry.fire("""["EOSE","$subId"]""", "wss://a.example")
        val (lastEvents, lastEosed) = emitted.last()
        assertTrue("should be eosed", lastEosed)
        assertEquals(3, lastEvents.size)
        assertEquals(200L, lastEvents[0].createdAt)
        assertEquals(150L, lastEvents[1].createdAt)
        assertEquals(100L, lastEvents[2].createdAt)
    }

    @Test
    fun `post-EOSE event newer than eosedAt fires onNew`() = runTest {
        val news = CopyOnWriteArrayList<NostrEvent>()
        service.subscribeTimeline(
            subRequests = listOf(SubRequest(listOf("wss://a.example"), NostrFilter(kinds = listOf(1), limit = 10))),
            onEvents = { _, _ -> },
            onNew = { news.add(it) },
        )
        val subId = transport.lastReqSubId()
        tapRegistry.fire(eventMessage(subId, id = "a".repeat(64), createdAt = 100), "wss://a.example")
        tapRegistry.fire("""["EOSE","$subId"]""", "wss://a.example")
        // eosedAt is set to System.currentTimeMillis()/1000 — 30s in the future is past eosedAt
        // but within the 60s poisoned-since grace window in Subscription.parseEvent()
        tapRegistry.fire(eventMessage(subId, id = "b".repeat(64), createdAt = System.currentTimeMillis() / 1000 + 30), "wss://a.example")
        assertEquals(1, news.size)
        assertEquals("b".repeat(64), news[0].id)
    }

    @Test
    fun `post-EOSE event older than eosedAt is dropped`() = runTest {
        val news = CopyOnWriteArrayList<NostrEvent>()
        service.subscribeTimeline(
            subRequests = listOf(SubRequest(listOf("wss://a.example"), NostrFilter(kinds = listOf(1)))),
            onEvents = { _, _ -> },
            onNew = { news.add(it) },
        )
        val subId = transport.lastReqSubId()
        tapRegistry.fire("""["EOSE","$subId"]""", "wss://a.example")
        tapRegistry.fire(eventMessage(subId, id = "a".repeat(64), createdAt = 1L), "wss://a.example")
        assertEquals(0, news.size)
    }

    @Test
    fun `multi subRequest emits on each per-sub update`() = runTest {
        val emissions = CopyOnWriteArrayList<Pair<Int, Boolean>>()
        val subRequests = listOf("a", "b", "c", "d").map {
            SubRequest(urls = listOf("wss://$it"), filter = NostrFilter(kinds = listOf(1), limit = 100))
        }
        service.subscribeTimeline(
            subRequests = subRequests,
            onEvents = { events, eosed -> emissions.add(events.size to eosed) },
        )
        val reqSubIds = transport.sends
            .filter { it.msg.startsWith("[\"REQ\"") }
            .map { extractSubId(it.msg) }
        assertEquals(4, reqSubIds.size)
        // First sub gets an event + EOSE — emits immediately (no threshold gate)
        tapRegistry.fire(eventMessage(reqSubIds[0], id = "a".repeat(64), createdAt = 100), "wss://a.example")
        tapRegistry.fire("""["EOSE","${reqSubIds[0]}"]""", "wss://a.example")
        assertTrue("should emit on first per-sub EOSE with events", emissions.isNotEmpty())
        assertEquals("merged has 1 event", 1, emissions.last().first)
        assertTrue("not all subs done yet", !emissions.last().second)
        // Complete remaining (no events — merged still non-empty from sub a)
        tapRegistry.fire("""["EOSE","${reqSubIds[1]}"]""", "wss://b.example")
        tapRegistry.fire("""["EOSE","${reqSubIds[2]}"]""", "wss://c.example")
        tapRegistry.fire("""["EOSE","${reqSubIds[3]}"]""", "wss://d.example")
        assertTrue("final emission should be eosed=true", emissions.last().second)
    }

    @Test
    fun `cache hit on second subscribe injects since`() = runTest {
        val sr = SubRequest(listOf("wss://a.example"), NostrFilter(kinds = listOf(1), limit = 10))
        val handle1 = service.subscribeTimeline(
            subRequests = listOf(sr),
            onEvents = { _, _ -> },
        )
        val subId1 = transport.lastReqSubId()
        eventLoader.put(makeEvent("a".repeat(64), 100))
        tapRegistry.fire(eventMessage(subId1, id = "a".repeat(64), createdAt = 100), "wss://a.example")
        tapRegistry.fire("""["EOSE","$subId1"]""", "wss://a.example")
        handle1.close()
        transport.sends.clear()
        service.subscribeTimeline(
            subRequests = listOf(sr),
            onEvents = { _, _ -> },
        )
        val secondReq = transport.sends.first { it.msg.startsWith("[\"REQ\"") }.msg
        assertTrue("second REQ should contain since=101: $secondReq", secondReq.contains("\"since\":101"))
    }

    @Test
    fun `mergeTimelines merges sorted lists deduped`() {
        val a = listOf(makeEvent("a".repeat(64), 200), makeEvent("c".repeat(64), 100))
        val b = listOf(makeEvent("b".repeat(64), 150), makeEvent("c".repeat(64), 100))
        val merged = mergeTimelines(listOf(a, b))
        assertEquals(3, merged.size)
        assertEquals(200L, merged[0].createdAt)
        assertEquals(150L, merged[1].createdAt)
        assertEquals(100L, merged[2].createdAt)
    }

    @Test
    fun `mergeTimelines respects limit`() {
        val a = listOf(makeEvent("a".repeat(64), 300), makeEvent("b".repeat(64), 200))
        val b = listOf(makeEvent("c".repeat(64), 250))
        val merged = mergeTimelines(listOf(a, b), limit = 2)
        assertEquals(2, merged.size)
        assertEquals(300L, merged[0].createdAt)
        assertEquals(250L, merged[1].createdAt)
    }

    @Test
    fun `Handle close fires CLOSE for each underlying sub`() = runTest {
        val handle = service.subscribeTimeline(
            subRequests = listOf(
                SubRequest(listOf("wss://a.example"), NostrFilter()),
                SubRequest(listOf("wss://b.example"), NostrFilter()),
            ),
            onEvents = { _, _ -> },
        )
        val sendsBeforeClose = transport.sends.size
        handle.close()
        val newSends = transport.sends.drop(sendsBeforeClose)
        assertTrue("at least 2 CLOSE messages", newSends.count { it.msg.startsWith("[\"CLOSE\"") } >= 2)
    }

    @Test
    fun `timeline cache is populated after EOSE`() = runTest {
        val sr = SubRequest(listOf("wss://a.example"), NostrFilter(limit = 10))
        service.subscribeTimeline(
            subRequests = listOf(sr),
            onEvents = { _, _ -> },
        )
        val subId = transport.lastReqSubId()
        tapRegistry.fire(eventMessage(subId, "a".repeat(64), 100), "wss://a.example")
        tapRegistry.fire(eventMessage(subId, "b".repeat(64), 200), "wss://a.example")
        tapRegistry.fire(eventMessage(subId, "c".repeat(64), 300), "wss://a.example")
        tapRegistry.fire("""["EOSE","$subId"]""", "wss://a.example")
        val refs = service.timelineForTest(
            service.generateTimelineKey(sr.urls, sr.filter)
        )
        assertTrue("cache populated", refs != null)
        assertEquals(3, refs!!.size)
        assertEquals(300L, refs[0].createdAt)
        assertEquals(200L, refs[1].createdAt)
        assertEquals(100L, refs[2].createdAt)
    }

    @Test
    fun `loadMoreTimeline returns events older than until`() = runTest {
        val sr = SubRequest(listOf("wss://a.example"), NostrFilter(limit = 10))
        val handle = service.subscribeTimeline(
            subRequests = listOf(sr),
            onEvents = { _, _ -> },
        )
        val subId = transport.lastReqSubId()
        listOf(100L, 200L, 300L, 400L, 500L).forEachIndexed { i, ts ->
            val id = "e$i".padEnd(64, '0').take(64)
            eventLoader.put(makeEvent(id, ts))
            tapRegistry.fire(eventMessage(subId, id, ts), "wss://a.example")
        }
        tapRegistry.fire("""["EOSE","$subId"]""", "wss://a.example")
        val older = service.loadMoreTimeline(handle.timelineKey, until = 300L, limit = 10)
        assertEquals(2, older.size)
        assertTrue(older.all { it.createdAt < 300L })
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun makeEvent(id: String, createdAt: Long): NostrEvent {
        val padded = id.padEnd(64, '0').take(64)
        return NostrEvent(
            id = padded,
            pubkey = "p".repeat(64),
            kind = 1,
            createdAt = createdAt,
            content = "",
            tags = emptyList(),
            tagsJson = "[]",
            sig = "s".repeat(128),
            relayUrl = "wss://test.example",
            replyToId = null,
            rootId = null,
            hasContentWarning = false,
            contentWarningReason = null,
            firstSeenAt = System.currentTimeMillis(),
            relaysSeen = ConcurrentHashMap.newKeySet(),
        )
    }

    private fun eventMessage(subId: String, id: String, createdAt: Long): String {
        val paddedId = id.padEnd(64, '0').take(64)
        return """["EVENT","$subId",{"id":"$paddedId","pubkey":"${"p".repeat(64)}","kind":1,"created_at":$createdAt,"content":"","tags":[],"sig":"${"c".repeat(128)}"}]"""
    }

    private fun extractSubId(reqMsg: String): String {
        val firstQuote = reqMsg.indexOf('"', 1)
        val secondQuote = reqMsg.indexOf('"', firstQuote + 1)
        val thirdQuote = reqMsg.indexOf('"', secondQuote + 1)
        val fourthQuote = reqMsg.indexOf('"', thirdQuote + 1)
        return reqMsg.substring(thirdQuote + 1, fourthQuote)
    }

    private fun FakeRelayTransport.lastReqSubId(): String {
        return extractSubId(this.sends.last { it.msg.startsWith("[\"REQ\"") }.msg)
    }
}

class FakeEventLoader : TimelineEventLoader {
    private val store = ConcurrentHashMap<String, NostrEvent>()

    fun put(evt: NostrEvent) { store[evt.id] = evt }

    override suspend fun getEvents(ids: List<String>): List<NostrEvent> =
        ids.mapNotNull { store[it] }
            .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
}
