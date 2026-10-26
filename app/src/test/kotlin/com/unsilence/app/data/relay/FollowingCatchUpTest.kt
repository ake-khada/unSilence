package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.auth.MuteKeyProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import javax.inject.Provider

/** Real service/subscription/merge path. Transport and verified ingress are
 * deterministic; display mirrors the feed's merge, not Compose/device gestures. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FollowingCatchUpTest {
    @get:Rule val temp = TemporaryFolder()
    private val a = "wss://a.example"
    private val b = "wss://b.example"

    private inner class Fixture(val test: TestScope, val limit: Int = 3) : AutoCloseable {
        val transport = FakeRelayTransport()
        val taps = FakeTapRegistration()
        val loader = FakeEventLoader()
        val capabilities = FakeRelayCapabilitiesStore()
        val subscription = Subscription(transport, taps, FakeReconnectSource(), capabilities).apply {
            watchdogDispatcher = StandardTestDispatcher(test.testScheduler)
            elapsedRealtime = { test.testScheduler.currentTime + 1 }
        }
        val service = TimelineService(subscription, loader).apply {
            subscribeDispatcher = StandardTestDispatcher(test.testScheduler)
        }
        val remote = mutableMapOf<String, List<NostrEvent>>()
        val modes = mutableMapOf<String, String>()
        val handles = mutableListOf<TimelineService.TimelineHandle>()
        var displayed = emptyList<NostrEvent>()
        val live = mutableListOf<NostrEvent>()
        var historyCallbacks = 0
        init {
            transport.onSend = { url, raw ->
                if (raw.startsWith("[\"REQ\"")) {
                    val req = NostrJson.parseToJsonElement(raw).jsonArray
                    val filter = req[2].jsonObject
                    val since = filter["since"]?.jsonPrimitive?.longOrNull ?: Long.MIN_VALUE
                    val until = filter["until"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE
                    val n = filter["limit"]?.jsonPrimitive?.longOrNull?.toInt() ?: 300
                    val page = remote[url].orEmpty().filter { it.createdAt in since..until }
                        .sortedWith(TimelineMerge.EVENT_ORDER).take(n)
                    if (modes[url] != "silent") deliver(url, req, page, modes[url] ?: "EOSE")
                }
            }
        }
        fun event(time: Long, url: String = a): NostrEvent = EventDto(
            id = time.toString(16).padStart(63, '0') + if (url == a) "a" else "b",
            pubkey = (if (url == a) "a" else "b").repeat(64), createdAt = time,
            kind = 1, tags = emptyList(), content = "time=$time", sig = "c".repeat(128),
        ).toNostrEvent(url)
        fun request(url: String) = SubRequest(listOf(url), NostrFilter(
            kinds = listOf(1), authors = listOf((if (normalizeRelayUrl(url) == a) "a" else "b").repeat(64)), limit = limit,
        ))
        suspend fun open(urls: List<String> = listOf(a)): TimelineService.TimelineHandle {
            val handle = service.subscribeTimeline(
                urls.map(::request),
                onEvents = { batch, _ -> displayed = TimelineMerge.merge(displayed, batch) },
                onNew = { live += it; displayed = TimelineMerge.merge(displayed, listOf(it)) },
                seedHead = displayed.firstOrNull()?.createdAt,
                onHistory = { historyCallbacks++; displayed = TimelineMerge.merge(displayed, it) },
            )
            handles += handle
            test.runCurrent()
            return handle
        }
        fun reqs(url: String = a): List<JsonArray> = transport.sends.filter {
            it.url == url && it.msg.startsWith("[\"REQ\"")
        }.map { NostrJson.parseToJsonElement(it.msg).jsonArray }
        fun deliver(url: String, req: JsonArray, page: List<NostrEvent>, end: String = "EOSE") {
            val id = req[1].jsonPrimitive.content
            for (e in page) {
                loader.put(e)
                taps.registrations.forEach { it.onMessage(RelayTapMessage.VerifiedEvent(id, e.copy(relayUrl = url))) }
            }
            if (end == "EOSE") taps.fire("""["EOSE","$id"]""", url)
            if (end == "CLOSED") taps.fire("""["CLOSED","$id","error: temporarily unavailable"]""", url)
        }
        fun cached(url: String = a) = service.timelineStateForTest(service.generateTimelineKey(listOf(url), request(url).filter))!!
        fun pending(url: String = a) = cached(url).coverage.getValue(url).pending
        fun store() = MemoryEventStore(object : MuteKeyProvider {}, Provider { service })
        override fun close() { handles.forEach { it.close() }; subscription.resetForTest(); service.clear() }
    }

    @Test fun `non genuine pages cannot become a replay cursor`() = runTest {
        for (mode in listOf("CLOSED", "no-eose", "silent")) {
            Fixture(this).use { f ->
                f.remote[a] = listOf(f.event(200), f.event(100))
                f.modes[a] = mode
                f.open()
                if (mode == "no-eose") { advanceTimeBy(2501); runCurrent() }
                if (mode == "silent") { advanceTimeBy(30_001); runCurrent() }
                assertNull(f.cached().coverage.getValue(a).head)
                f.modes[a] = "silent"
                f.subscription.pauseAll(); f.subscription.resumeAll()
                assertNull(f.reqs().last()[2].jsonObject["since"])
                assertNull(f.cached().coverage.getValue(a).head)
            }
        }
    }

    @Test fun `V19 binary restore stays unknown on replay until genuine completion`() = runTest {
        Fixture(this).use { source ->
            source.remote[a] = listOf(source.event(200), source.event(100))
            source.open()
            val store = source.store()
            source.remote[a]!!.forEach { store.insert(it) }
            val file = temp.newFile()
            file.outputStream().use { store.saveSnapshotBinary(it, 19) }
            Fixture(this).use { restored ->
                DataInputStream(file.inputStream()).use { restored.store().restoreSnapshotBinary(it) }
                restored.remote[a] = source.remote[a]!!
                restored.remote[a]!!.forEach { restored.loader.put(it) }
                restored.modes[a] = "silent"
                restored.open()
                restored.subscription.pauseAll(); restored.subscription.resumeAll()
                assertNull(restored.reqs().last()[2].jsonObject["since"])
                assertNull(restored.cached().coverage.getValue(a).head)
                restored.deliver(a, restored.reqs().last(), restored.remote[a]!!)
                assertEquals(200L, restored.cached().coverage.getValue(a).head)
                restored.subscription.pauseAll(); restored.subscription.resumeAll()
                assertEquals(200L, restored.reqs().last()[2].jsonObject["since"]?.jsonPrimitive?.longOrNull)
            }
        }
    }

    @Test fun `genuine EOSE replay uses exact head and live arrivals cannot advance it`() = runTest {
        Fixture(this).use { f ->
            f.remote[a] = listOf(f.event(200), f.event(100))
            f.open()
            assertEquals(200L, f.cached().coverage.getValue(a).head)
            val live = f.event(System.currentTimeMillis() / 1000 + 30)
            f.deliver(a, f.reqs().first(), listOf(live), "no-eose")
            assertEquals(listOf(live.id), f.live.map { it.id })
            f.subscription.pauseAll()
            f.subscription.resumeAll()
            assertEquals(200L, f.reqs().last()[2].jsonObject["since"]?.jsonPrimitive?.longOrNull)
            assertEquals(200L, f.cached().coverage.getValue(a).head)
            assertEquals(setOf(live.id, f.event(200).id, f.event(100).id), f.displayed.map { it.id }.toSet())
        }
    }

    @Test fun `cold seed gap is filled before paging below yesterday without dropping seed`() = runTest {
        Fixture(this).use { f ->
            f.displayed = listOf(f.event(100))
            f.remote[a] = listOf(1000L, 900, 800, 700, 600, 200, 100).map { f.event(it) }
            f.open()
            advanceUntilIdle()
            assertEquals(f.remote[a]!!.map { it.id }, f.displayed.map { it.id })
            assertEquals(f.displayed.map { it.id }, f.cached().refs.map { it.id })
            assertFalse(f.pending())
            assertEquals(listOf(null, 800L, 600L), f.reqs().map { it[2].jsonObject["until"]?.jsonPrimitive?.longOrNull })
        }
    }

    @Test fun `mixed overnight replay full 300 and short page recover cache and display`() = runTest {
        Fixture(this, 300).use { f ->
            f.remote[a] = listOf(f.event(200), f.event(100))
            f.remote[b] = listOf(f.event(150, b), f.event(50, b))
            f.open(listOf(a, b))
            f.subscription.pauseAll()
            f.remote[a] = (10_000L downTo 9_650L).map { f.event(it) } + f.remote[a]!!
            f.remote[b] = listOf(f.event(700, b)) + f.remote[b]!!
            f.subscription.resumeAll()
            advanceUntilIdle()
            val expected = (f.remote[a]!! + f.remote[b]!!).map { it.id }.toSet()
            assertEquals(expected, f.displayed.map { it.id }.toSet())
            assertEquals(f.remote[a]!!.map { it.id }, f.cached(a).refs.map { it.id })
            assertEquals(f.remote[b]!!.map { it.id }, f.cached(b).refs.map { it.id })
            assertFalse(f.pending(a)); assertFalse(f.pending(b))
            assertEquals(3, f.reqs(a).size) // initial, replay, one catch-up
            assertEquals(2, f.reqs(b).size) // no extra short-page request
            assertTrue(f.live.isEmpty()) // old-cutoff history took replay, not live path
        }
    }

    @Test fun `different relay page depths cannot supply one oldest cursor`() = runTest {
        Fixture(this).use { f ->
            f.remote[a] = (10L downTo 5L).map { f.event(it) }
            f.remote[b] = listOf(f.event(1, b))
            f.open(listOf(a, b))
            advanceUntilIdle()
            assertEquals(7, f.displayed.size)
            assertFalse(f.pending(a)); assertFalse(f.pending(b))
            assertEquals(1, f.reqs(b).size)
            assertTrue(f.reqs(a).drop(1).all { it[2].jsonObject["authors"] == f.reqs(a)[0][2].jsonObject["authors"] })
        }
    }

    @Test fun `genuine short pages do not fetch again`() = runTest {
        Fixture(this, 300).use { f ->
            f.displayed = listOf(f.event(1))
            f.remote[a] = (1000L downTo 702L).map { f.event(it) }
            f.open(); advanceUntilIdle()
            assertEquals(1, f.reqs().size)
            assertFalse(f.pending())
            assertTrue(f.displayed.any { it.createdAt == 1L })
        }
    }

    @Test fun `short CLOSED retries the same range and recovers omitted middle`() = runTest {
        Fixture(this, 300).use { f ->
            f.displayed = listOf(f.event(100))
            f.remote[a] = listOf(f.event(1000), f.event(900))
            f.modes[a] = "CLOSED"
            f.open()
            assertTrue(f.pending())
            f.remote[a] = f.remote[a]!! + f.event(700)
            f.modes.remove(a)
            advanceUntilIdle()
            assertEquals(2, f.reqs().size)
            assertNull(f.reqs().last()[2].jsonObject["until"])
            assertTrue(f.displayed.any { it.createdAt == 700L })
            assertFalse(f.pending())
        }
    }

    @Test fun `short watchdog page is not complete coverage`() = runTest {
        Fixture(this, 300).use { f ->
            f.displayed = listOf(f.event(100))
            f.remote[a] = listOf(f.event(1000), f.event(900))
            f.modes[a] = "no-eose"
            f.open()
            advanceTimeBy(2501); runCurrent()
            assertTrue(f.pending())
            f.remote[a] = f.remote[a]!! + f.event(700)
            f.modes.remove(a)
            advanceUntilIdle()
            assertFalse(f.pending())
            assertTrue(f.displayed.any { it.createdAt == 700L })
            assertEquals(2, f.reqs().size)
        }
    }

    @Test fun `automatic budget is three pages and manual scrolling adds one bounded page`() = runTest {
        Fixture(this).use { f ->
            f.displayed = listOf(f.event(1))
            f.remote[a] = (15L downTo 1L).map { f.event(it) }
            val handle = f.open()
            advanceUntilIdle()
            assertEquals(4, f.reqs().size)
            assertTrue(f.pending())
            val load = async { f.service.fetchOlderTimeline(handle.timelineKey, 1, 100) }
            advanceUntilIdle(); load.await()
            assertEquals(6, f.reqs().size) // independent older request plus one gap page
            val gap = f.reqs().last { it[2].jsonObject["since"] != null }
            assertEquals(7L, gap[2].jsonObject["until"]!!.jsonPrimitive.longOrNull)
            assertTrue(f.pending())
            assertTrue(f.displayed.any { it.createdAt == 1L })
        }
    }

    @Test fun `rate limited catch up waits for a later manual action without requests`() = runTest {
        Fixture(this).use { f ->
            f.displayed = listOf(f.event(1))
            f.remote[a] = (6L downTo 1L).map { f.event(it) }
            val handle = f.open()
            f.transport.rateLimitedUrls += a
            advanceUntilIdle()
            assertEquals(1, f.reqs().size)
            assertTrue(f.pending())
            f.transport.rateLimitedUrls.clear()
            val load = async { f.service.fetchOlderTimeline(handle.timelineKey, 1, 100) }
            advanceUntilIdle(); load.await()
            assertEquals(3, f.reqs().size) // independent older request plus one gap page
            assertTrue(f.pending())
        }
    }

    @Test fun `metadata resubscribe does not reset the per relay automatic budget`() = runTest {
        Fixture(this).use { f ->
            f.displayed = listOf(f.event(1))
            f.remote[a] = (30L downTo 1L).map { f.event(it) }
            val handle = f.open(); advanceUntilIdle()
            assertEquals(4, f.reqs().size)
            handle.close()
            f.open(); advanceUntilIdle()
            assertEquals(5, f.reqs().size) // only the ordinary subscription
            f.subscription.pauseAll(); f.subscription.resumeAll(); advanceUntilIdle()
            assertEquals(9, f.reqs().size) // new replay plus three bounded pages
        }
    }

    @Test fun `catch up concurrency is limited to two even with ten relay pages pending`() = runTest {
        Fixture(this).use { f ->
            val urls = (0 until 10).map { "wss://relay$it.example" }
            f.displayed = listOf(f.event(1))
            urls.forEach { url -> f.remote[url] = (10L downTo 5L).map { f.event(it, url) } }
            f.open(urls)
            urls.forEach { f.modes[it] = "silent" }
            advanceTimeBy(1001); runCurrent()
            val catchUps = urls.sumOf { f.reqs(it).size - 1 }
            assertEquals(2, catchUps)
        }
    }

    @Test fun `full same second page stops without skipping tied events or retry spinning`() = runTest {
        Fixture(this).use { f ->
            f.displayed = listOf(f.event(1))
            f.remote[a] = (0..2).map { i -> f.event(10).copy(id = i.toString().padStart(64, '0')) }
            val handle = f.open(); advanceUntilIdle()
            assertEquals(2, f.reqs().size)
            assertTrue(f.pending())
            assertEquals(10L, f.cached().coverage.getValue(a).until)
            val load = async { f.service.fetchOlderTimeline(handle.timelineKey, 1, 100) }
            advanceUntilIdle(); load.await()
            assertTrue(f.pending())
            assertEquals(4, f.reqs().size) // one gap attempt, then ordinary older paging
        }
    }

    @Test fun `snapshot payload truncation invalidates head and retains short page recovery`() = runTest {
        Fixture(this, 300).use { source ->
            source.remote[a] = listOf(1000L, 900, 700, 200).map { source.event(it) }
            source.open()
            val kept = source.remote[a]!!.first()
            val entries = source.service.snapshotData(setOf(kept.id))
            assertNull(entries.values.single().coverage.getValue(a).head)
            Fixture(this, 300).use { restored ->
                restored.service.restoreFromSnapshot(entries)
                restored.loader.put(kept)
                restored.displayed = listOf(restored.event(200))
                restored.remote[a] = source.remote[a]!!
                restored.open(); advanceUntilIdle()
                assertNull(restored.reqs().first()[2].jsonObject["since"])
                assertEquals(source.remote[a]!!.map { it.id }, restored.displayed.map { it.id })
            }
        }
    }

    @Test fun `single socket reconnect does not invalidate other relays confirmed coverage`() = runTest {
        Fixture(this).use { f ->
            val filter = NostrFilter(kinds = listOf(1), limit = 3)
            f.remote[a] = listOf(f.event(200))
            f.remote[b] = listOf(f.event(100, b))
            val handle = f.service.subscribeTimeline(
                listOf(SubRequest(listOf(a, b), filter)), onEvents = { _, _ -> },
            )
            f.handles += handle
            runCurrent()
            val key = f.service.generateTimelineKey(listOf(a, b), filter)
            val before = f.service.timelineStateForTest(key)!!.coverage.getValue(b)
            assertFalse(before.pending)
            f.remote[a] = listOf(f.event(700)) + f.remote[a]!!
            f.subscription.resumeRelay(a, 1000)
            advanceUntilIdle()
            assertEquals(before, f.service.timelineStateForTest(key)!!.coverage.getValue(b))
            assertEquals(1, f.reqs(b).size)
            assertEquals(2, f.reqs(a).size)
        }
    }

    @Test fun `live tail never advances confirmed coverage including service snapshot restore`() = runTest {
        Fixture(this, 300).use { f ->
            f.remote[a] = listOf(f.event(200), f.event(100))
            val first = f.open()
            val live = f.event(System.currentTimeMillis() / 1000 + 30)
            f.deliver(a, f.reqs().last(), listOf(live), "no-eose")
            assertEquals(200L, f.cached().coverage.getValue(a).head)
            val snapshot = f.service.snapshotData(setOf(live.id) + f.remote[a]!!.map { it.id })
            first.close(); f.service.clear(); f.service.restoreFromSnapshot(snapshot)
            f.open()
            assertEquals(200L, f.reqs().last()[2].jsonObject["since"]!!.jsonPrimitive.longOrNull)
        }
    }

    @Test fun `closing a handle cancels pending catch up and cannot deliver into replacement`() = runTest {
        Fixture(this).use { f ->
            f.displayed = listOf(f.event(1))
            f.remote[a] = (10L downTo 1L).map { f.event(it) }
            val handle = f.open()
            handle.close(); f.service.clear()
            advanceUntilIdle()
            assertEquals(1, f.reqs().size)
            assertEquals(0, f.historyCallbacks)
        }
    }

    @Test fun `permanently unavailable relay does not block older history from a healthy relay`() = runTest {
        Fixture(this).use { f ->
            f.displayed = listOf(f.event(100))
            f.remote[a] = listOf(f.event(1000), f.event(900), f.event(800))
            f.remote[b] = listOf(f.event(200, b), f.event(100, b))
            val handle = f.open(listOf(a, b))
            f.capabilities.markSkipped(a)
            advanceUntilIdle()
            assertTrue(f.pending(a))
            f.remote[b] = f.remote[b]!! + f.event(50, b)
            val load = async { f.service.fetchOlderTimeline(handle.timelineKey, 100, 100) }
            advanceUntilIdle()
            assertTrue(load.await().any { it.createdAt == 50L })
            assertTrue(f.pending(a))
            assertEquals(1, f.reqs(a).size)
        }
    }

    @Test fun `V20 pending range survives binary restore and V19 resumes at cached head`() = runTest {
        for (version in listOf(19, 20)) {
            Fixture(this).use { source ->
                source.remote[a] = listOf(source.event(200), source.event(100))
                source.open()
                source.subscription.pauseAll()
                source.remote[a] = (1000L downTo 980L).map { source.event(it) } + source.remote[a]!!
                source.subscription.resumeAll()
                advanceUntilIdle()
                assertTrue(source.pending())
                val saved = source.cached()
                val store = source.store()
                source.remote[a]!!.filter { e -> saved.refs.any { it.id == e.id } }.forEach { store.insert(it) }
                val file = temp.newFile()
                file.outputStream().use { store.saveSnapshotBinary(it, version) }
                assertEquals(version, ByteBuffer.wrap(file.readBytes()).getInt(4))
                Fixture(this).use { restored ->
                    val restoredStore = restored.store()
                    DataInputStream(file.inputStream()).use { restoredStore.restoreSnapshotBinary(it) }
                    assertEquals(saved.refs, restored.cached().refs)
                    if (version == 20) assertEquals(saved.coverage, restored.cached().coverage)
                    else assertTrue(restored.cached().coverage.isEmpty())
                    restored.remote[a] = source.remote[a]!!
                    restored.remote[a]!!.forEach { restored.loader.put(it) }
                    restored.open()
                    val since = restored.reqs().first()[2].jsonObject["since"]?.jsonPrimitive?.longOrNull
                    assertTrue("restored head must not skip the unresolved interval", since == null || since <= 200L)
                    advanceUntilIdle()
                    if (version == 19) {
                        assertEquals("legacy restore does not refetch below the old head", 1, restored.reqs().size)
                        assertFalse(restored.pending())
                    } else {
                        assertTrue(restored.reqs().size > 1)
                        assertEquals(200L, restored.cached().coverage.getValue(a).floor)
                    }
                }
            }
        }
    }

    @Test fun `pending slow gap cannot block older paging and later merges through history`() = runTest {
        Fixture(this).use { f ->
            f.displayed = listOf(f.event(1))
            f.remote[a] = (15L downTo 1L).map { f.event(it) }
            val handle = f.open(); advanceUntilIdle()
            assertTrue(f.pending())
            assertEquals(4, f.reqs().size)
            val historyBefore = f.historyCallbacks
            f.remote[a] = f.remote[a]!! + f.event(0)
            val respond = f.transport.onSend
            f.transport.onSend = { url, raw ->
                val isGap = raw.startsWith("[\"REQ\"") &&
                    NostrJson.parseToJsonElement(raw).jsonArray[2].jsonObject["since"] != null
                if (!isGap) respond(url, raw) // hold the gap, answer ordinary older paging
            }
            val load = async { f.service.fetchOlderTimeline(handle.timelineKey, 1, 100) }
            runCurrent()
            assertTrue("older paging must not join the pending gap", load.isCompleted)
            assertEquals(listOf(0L), load.await().map { it.createdAt })
            advanceTimeBy(1001); runCurrent()
            val gap = f.reqs().last { it[2].jsonObject["since"] != null }
            f.deliver(a, gap, listOf(7L, 6, 5).map { f.event(it) })
            runCurrent()
            assertEquals(historyBefore + 1, f.historyCallbacks)
            assertTrue(f.displayed.any { it.createdAt == 5L })
            assertTrue(f.pending()) // only one background page per gesture
            assertEquals(6, f.reqs().size) // four automatic + older page + gap page
        }
    }

    @Test fun `successful gap page merges in background while older page extends the bottom`() = runTest {
        Fixture(this).use { f ->
            val anchor = f.event(1)
            f.displayed = listOf(anchor)
            f.remote[a] = (15L downTo 1L).map { f.event(it) }
            val handle = f.open(); advanceUntilIdle()
            val historyBefore = f.historyCallbacks
            f.remote[a] = f.remote[a]!! + f.event(0)
            val load = async { f.service.fetchOlderTimeline(handle.timelineKey, anchor.createdAt, 100) }
            advanceUntilIdle()
            val older = load.await()
            assertEquals(listOf(0L), older.map { it.createdAt })
            f.displayed = TimelineMerge.merge(f.displayed, older, capTail = false)
            assertEquals(0L, f.displayed.last().createdAt)
            assertTrue(f.displayed.any { it.id == anchor.id })
            assertTrue(f.displayed.any { it.createdAt == 5L })
            assertEquals(historyBefore + 1, f.historyCallbacks)
        }
    }

    @Test fun `closing handle cancels a manual background page after older paging returns`() = runTest {
        Fixture(this).use { f ->
            f.displayed = listOf(f.event(1))
            f.remote[a] = (15L downTo 1L).map { f.event(it) }
            val handle = f.open(); advanceUntilIdle()
            val respond = f.transport.onSend
            f.transport.onSend = { url, raw ->
                val isGap = raw.startsWith("[\"REQ\"") &&
                    NostrJson.parseToJsonElement(raw).jsonArray[2].jsonObject["since"] != null
                if (!isGap) respond(url, raw)
            }
            val load = async { f.service.fetchOlderTimeline(handle.timelineKey, 1, 100) }
            runCurrent()
            assertTrue(load.isCompleted)
            advanceTimeBy(1001); runCurrent()
            val gap = f.reqs().last { it[2].jsonObject["since"] != null }
            val historyBefore = f.historyCallbacks
            handle.close()
            runCurrent()
            f.deliver(a, gap, listOf(f.event(5)))
            advanceUntilIdle()
            assertEquals(historyBefore, f.historyCallbacks)
            assertTrue(f.transport.sends.any {
                it.msg == """["CLOSE",${gap[1]}]"""
            })
        }
    }

    @Test fun `legacy coverage catches up only to newest seed or cached head`() = runTest {
        for ((seedHead, cachedHead) in listOf(1000L to null, 100L to 1000L, 1000L to 900L)) {
            Fixture(this).use { f ->
                f.displayed = listOf(f.event(seedHead), f.event(1))
                if (cachedHead != null) {
                    val events = listOf(f.event(cachedHead), f.event(1))
                    events.forEach { f.loader.put(it) }
                    val request = f.request(a)
                    val key = f.service.generateTimelineKey(request.urls, request.filter)
                    f.service.restoreFromSnapshot(mapOf(key to TimelineService.Timeline(
                        refs = events.map { TimelineRef(it.id, it.createdAt) },
                        filter = request.filter, urls = request.urls,
                    )))
                }
                f.remote[a] = (1300L downTo 100L step 100).map { f.event(it) }
                f.open(); advanceUntilIdle()
                assertEquals(2, f.reqs().size)
                assertEquals(1000L, f.reqs().last()[2].jsonObject["since"]!!.jsonPrimitive.longOrNull)
                assertFalse(f.pending())
                assertTrue(f.displayed.any { it.createdAt == 1L }) // keep old seed
                assertFalse(f.displayed.any { it.createdAt == 800L }) // old unknown holes are out of scope
            }
        }
    }

    @Test fun `older page never returns at or above until even if relay ignores filter`() = runTest {
        Fixture(this).use { f ->
            val handle = f.open()
            f.transport.onSend = { url, raw ->
                if (raw.startsWith("[\"REQ\"")) f.deliver(url,
                    NostrJson.parseToJsonElement(raw).jsonArray,
                    listOf(110L, 100, 90).map { f.event(it) },
                )
            }
            val load = async { f.service.fetchOlderTimeline(handle.timelineKey, 100, 100) }
            runCurrent()
            assertEquals(listOf(90L), load.await().map { it.createdAt })
        }
    }

    @Test fun `non normalized URL round trips with normalized coverage and invalid metadata falls back`() = runTest {
        Fixture(this).use { source ->
            source.remote[a] = listOf(source.event(200), source.event(100))
            source.open(listOf(" wss://a.example/ "))
            val saved = source.cached()
            assertEquals(listOf(a), saved.urls)
            assertEquals(setOf(a), saved.coverage.keys)
            val store = source.store()
            source.remote[a]!!.forEach { store.insert(it) }
            val file = temp.newFile()
            file.outputStream().use { store.saveSnapshotBinary(it) }
            val bytes = file.readBytes()
            assertEquals(20, ByteBuffer.wrap(bytes).getInt(4))
            Fixture(this).use { restored ->
                DataInputStream(ByteArrayInputStream(bytes)).use { restored.store().restoreSnapshotBinary(it) }
                assertEquals(saved, restored.cached())
            }
            // Corrupt only the coverage URL (same length), not the timeline URLs
            // or record framing. The payload/cache must still restore.
            val needle = a.toByteArray()
            val offset = (0..bytes.size - needle.size).last { i -> needle.indices.all { bytes[i + it] == needle[it] } }
            bytes[offset + 6] = 'x'.code.toByte()
            Fixture(this).use { restored ->
                val target = restored.store()
                DataInputStream(ByteArrayInputStream(bytes)).use { target.restoreSnapshotBinary(it) }
                assertEquals(saved.refs, restored.cached().refs)
                assertTrue(restored.cached().coverage.isEmpty())
            }
        }
    }
}
