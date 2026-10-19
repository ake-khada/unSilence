package com.unsilence.app.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.*
import com.unsilence.app.domain.model.FeedFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier
import javax.inject.Provider

/** Runs the real loadMore/batch/history/reset methods and TimelineService.
 * Android repositories/init are bypassed, as in KeyManagerCacheTest; the
 * production paging/row projection is driven explicitly, not Compose gestures. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedLoadMoreTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Fixture(initial: List<NostrEvent>, val accepted: (NostrEvent) -> Boolean = { true }) : AutoCloseable {
        val transport = FakeRelayTransport()
        val taps = FakeTapRegistration()
        val subscription = Subscription(transport, taps, FakeReconnectSource(), FakeRelayCapabilitiesStore())
        var waitForPayload: CompletableDeferred<Unit>? = null
        var loads = 0
        val stored = mutableMapOf<String, NostrEvent>()
        val service = TimelineService(subscription, object : TimelineEventLoader {
            override suspend fun getEvents(ids: List<String>): TimelineEventResolution {
                loads++
                waitForPayload?.await()
                return timelineEventResolution(ids, ids.mapNotNull(stored::get))
            }
        })
        val store = MemoryEventStore(object : MuteKeyProvider {}, Provider { service })
        val url = "wss://relay.example"
        val filter = NostrFilter(kinds = listOf(1), limit = 300)
        val key = service.generateTimelineKey(listOf(url), filter)
        val handle = object : TimelineService.TimelineHandle {
            override val timelineKey = key
            override fun close() = Unit
        }
        val timeline = MutableStateFlow(FeedTimeline(initial, FeedType.Following, FeedFilter()))
        val projection = MutableStateFlow(FeedPresentation())
        val loading = MutableStateFlow(false)
        val pending = MutableStateFlow(emptyList<NostrEvent>())
        val vm: FeedViewModel
        init {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            vm = unsafeClass.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, FeedViewModel::class.java) as FeedViewModel
            // Preserve the real ViewModel scope/closeables implementation.
            val base = object : ViewModel() {}
            ViewModel::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach {
                it.isAccessible = true
                it.set(vm, it.get(base))
            }
            mapOf("_timeline" to timeline, "presentation" to projection,
                "_isAtTop" to MutableStateFlow(false), "_isLoading" to MutableStateFlow(false),
                "_isLoadingMore" to loading, "_newEvents" to pending,
                "_liveArrivalIds" to MutableStateFlow(emptySet<String>()),
                "_feedType" to MutableStateFlow<FeedType>(FeedType.Following),
                "_filter" to MutableStateFlow(FeedFilter()),
                "_contentFilter" to MutableStateFlow(FeedContentFilter.NOTES_ONLY),
                "_refreshCounter" to MutableStateFlow(0), "_trustedHydrationFailed" to MutableStateFlow(false),
                "timelineService" to service, "memoryEventStore" to store,
                "currentHandle" to handle).forEach { (name, value) -> set(name, value) }
            cached(emptyList())
            project()
        }
        fun set(name: String, value: Any) = FeedViewModel::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(vm, value)
        fun cached(events: List<NostrEvent>) {
            events.forEach { stored[it.id] = it }
            service.restoreFromSnapshot(mapOf(key to TimelineService.Timeline(
                events.map { TimelineRef(it.id, it.createdAt) }, filter, listOf(url))))
        }
        fun project() {
            val state = timeline.value
            val page = state.paging.project(state.events.filter(accepted), projection.value.rows)
            projection.value = FeedPresentation(rows = page.events.map(store::synthesizeFeedRow),
                selection = FeedSelection(FeedType.Following.savedSource(), FeedFilter(), FeedContentFilter.NOTES_ONLY.value),
                restorationReady = true, paging = state.paging, nextBufferedEnd = page.nextBufferedEnd)
        }
        fun batch(events: List<NostrEvent>, since: Long? = null) {
            FeedViewModel::class.java.getDeclaredMethod("handleBatch", List::class.java,
                Boolean::class.javaPrimitiveType, Long::class.javaObjectType).apply { isAccessible = true }
                .invoke(vm, events, true, since)
            project()
        }
        fun history(events: List<NostrEvent>) {
            FeedViewModel::class.java.getDeclaredMethod("handleHistory", List::class.java)
                .apply { isAccessible = true }.invoke(vm, events)
            project()
        }
        val reqs get() = transport.sends.filter { it.msg.startsWith("[\"REQ\"") }
        override fun close() {
            vm.viewModelScope.cancel()
            subscription.resetForTest()
            service.clear()
        }
    }

    @Test fun `loadMore reveals buffered accepted rows past 500 without a fetch`() = runTest {
        Fixture(pagingEvents(0, 1000)).use { f ->
            assertEquals(500, f.projection.value.rows.size)
            f.vm.loadMore()
            runCurrent()
            f.project()
            assertEquals(600, f.projection.value.rows.size)
            assertEquals(pagingEvents(599, 1).single().id, f.projection.value.rows.last().id)
            assertEquals(0, f.loads)
            assertTrue(f.reqs.isEmpty())
            assertFalse(f.loading.value)
        }
    }

    @Test fun `cached older page reaches display and survives actual replay and onHistory callbacks`() = runTest {
        Fixture(pagingEvents(0, 1000)) { it.createdAt % 2L == 0L }.use { f ->
            val older = pagingEvents(1000, 100)
            f.cached(older)
            assertEquals(500, f.projection.value.rows.size)
            f.vm.loadMore()
            runCurrent()
            f.project()
            assertEquals(550, f.projection.value.rows.size)
            assertEquals(1100, f.timeline.value.events.size)
            val bottom = f.projection.value.rows.last().id
            f.batch(pagingEvents(-100, 100))
            f.history(pagingEvents(1200, 300))
            assertEquals(bottom, f.projection.value.rows.last().id)
            assertTrue(f.timeline.value.events.containsAll(older))
            assertEquals(1100, f.timeline.value.events.size)
            assertTrue(f.reqs.isEmpty())
        }
    }

    @Test fun `relay page returns strictly older rows to actual loadMore`() = runTest {
        Fixture(pagingEvents(0, 500)).use { f ->
            val boundary = f.timeline.value.events.last()
            val older = pagingEvents(500, 99)
            f.transport.onSend = { url, raw ->
                if (raw.startsWith("[\"REQ\"")) {
                    val req = NostrJson.parseToJsonElement(raw).jsonArray
                    assertEquals(boundary.createdAt, req[2].jsonObject.getValue("until").jsonPrimitive.long)
                    val sub = req[1].jsonPrimitive.content
                    for (event in listOf(boundary) + older) {
                        f.taps.registrations.forEach { it.onMessage(RelayTapMessage.VerifiedEvent(sub, event)) }
                    }
                    f.taps.fire("""["EOSE","$sub"]""", url)
                }
            }
            f.vm.loadMore()
            runCurrent()
            f.project()
            assertEquals(599, f.projection.value.rows.size)
            assertEquals(older.last().id, f.projection.value.rows.last().id)
            assertEquals(1, f.reqs.size)
            assertFalse(f.loading.value)
        }
    }

    @Test fun `one in flight page and returning top invalidates the old result without replacing handle`() = runTest {
        Fixture(pagingEvents(0, 500)).use { f ->
            f.cached(pagingEvents(500, 100))
            val gate = CompletableDeferred<Unit>()
            f.waitForPayload = gate
            f.vm.loadMore()
            f.vm.loadMore()
            runCurrent()
            assertTrue(f.loading.value)
            assertEquals(1, f.loads)
            f.vm.setAtTop(true)
            gate.complete(Unit)
            runCurrent()
            assertFalse(f.loading.value)
            assertEquals(500, f.timeline.value.events.size)
            assertEquals(0, f.timeline.value.paging.pages)
        }
    }

    @Test fun `reset projection fences stale buffered cursor and a content tab resets paging`() = runTest {
        Fixture(pagingEvents(0, 1000)).use { f ->
            f.vm.loadMore()
            f.project()
            assertEquals(1, f.timeline.value.paging.pages)
            f.vm.refresh()
            f.vm.loadMore() // old projection: must not regrow the just-reset window
            assertEquals(0, f.timeline.value.paging.pages)
            f.project()
            f.vm.loadMore()
            assertEquals(1, f.timeline.value.paging.pages)
            f.vm.setContentFilter(FeedContentFilter.REPLIES_ONLY)
            assertEquals(0, f.timeline.value.paging.pages)
            assertEquals(0, f.loads)
        }
    }

    @Test fun `hard reading bound cannot send more requests`() = runTest {
        Fixture(pagingEvents(0, 2500)).use { f ->
            f.timeline.value = f.timeline.value.copy(paging = FeedPagingWindow(FeedPagingWindow.MAX_PAGES,
                f.timeline.value.events.last().pageCursor()))
            f.project()
            repeat(5) { f.vm.loadMore() }
            runCurrent()
            assertEquals(2000, f.projection.value.rows.size)
            assertEquals(0, f.loads)
            assertTrue(f.reqs.isEmpty())
        }
    }

    @Test fun `replay cannot displace any reading row and next page still extends bottom`() = runTest {
        Fixture(pagingEvents(0, 1000)).use { f ->
            f.vm.loadMore()
            f.project()
            val reading = f.projection.value.rows
            f.batch(pagingEvents(-100, 100))
            assertEquals(reading, f.projection.value.rows)
            f.vm.loadMore()
            f.project()
            assertEquals(700, f.projection.value.rows.size)
            assertEquals(pagingEvents(699, 1).single().id, f.projection.value.rows.last().id)
            assertTrue(f.projection.value.rows.containsAll(reading))
            assertEquals(0, f.loads)
        }
    }

    @Test fun `scrolled live arrivals remain pending and are merged after returning top`() = runTest {
        Fixture(pagingEvents(0, 1000)).use { f ->
            f.vm.loadMore()
            f.project()
            val before = f.timeline.value.events
            val live = pagingEvents(-1, 1).single()
            FeedViewModel::class.java.getDeclaredMethod("handleNew", NostrEvent::class.java)
                .apply { isAccessible = true }.invoke(f.vm, live)
            assertEquals(before, f.timeline.value.events)
            assertEquals(listOf(live), f.pending.value)
            f.vm.setAtTop(true)
            assertEquals(live, f.timeline.value.events.first())
            assertEquals(1000, f.timeline.value.events.size)
            assertEquals(0, f.timeline.value.paging.pages)
            assertTrue(f.pending.value.isEmpty())
        }
    }

    @Test fun `deep activity save keeps only the reading neighborhood and rebuilds from cursor without requests`() = runTest {
        Fixture(pagingEvents(0, 2000)).use { f ->
            val original = f.timeline.value.events
            val window = FeedPagingWindow(FeedPagingWindow.MAX_PAGES, original.last().pageCursor())
            f.timeline.value = f.timeline.value.copy(paging = window)
            f.project()
            val current = SavedFeedSession("owner", FeedType.Following.savedSource(), isAtTop = false)
            val saved = decodeFeedSession(encodeFeedSession(feedSessionForSave(
                current, null, f.projection.value, firstVisibleIndex = 1400)), "owner")!!
            assertEquals(original.subList(1150, 1650).map { it.id }, saved.eventIds)
            assertEquals(window.through, saved.pagingThrough)
            assertEquals(window.pages, saved.pagingPages)

            // Newer arrivals exceed the old reading depth. The restore query
            // must seek from the saved cursor, not just take the newest rows.
            pagingEvents(-1000, 3500).forEach(f.store::insert)
            val relay = FeedType.SingleRelay(f.url, "Relay")
            val session = saved.copy(source = relay.savedSource())
            @Suppress("UNCHECKED_CAST")
            val rebuilt = FeedViewModel::class.java.getDeclaredMethod("loadCachedEvents",
                FeedType::class.java, FeedFilter::class.java, SavedFeedSession::class.java)
                .apply { isAccessible = true }.invoke(f.vm, relay, FeedFilter(), session) as List<NostrEvent>
            assertEquals(window.rawLimit, rebuilt.size)
            assertEquals(window.through, rebuilt.last().pageCursor())
            val restored = window.merge(saved.eventIds.mapNotNull(f.store::getNostrEvent), rebuilt)
            assertEquals(original.map { it.id }, window.project(restored).events.map { it.id })
            assertEquals(0, f.loads)
            assertTrue(f.reqs.isEmpty())
        }
    }

    @Test fun `activity save clamps neighborhood at either edge and keeps top saves at the head`() = runTest {
        Fixture(pagingEvents(0, 2000)).use { f ->
            f.timeline.value = f.timeline.value.copy(paging = FeedPagingWindow(
                FeedPagingWindow.MAX_PAGES, f.timeline.value.events.last().pageCursor()))
            f.project()
            val rows = f.projection.value.rows
            val current = SavedFeedSession("owner", FeedType.Following.savedSource(), isAtTop = false)
            assertEquals(rows.take(500).map { it.id },
                feedSessionForSave(current, null, f.projection.value, -10).eventIds)
            assertEquals(rows.takeLast(500).map { it.id },
                feedSessionForSave(current, null, f.projection.value, 9000).eventIds)
            assertEquals(rows.take(500).map { it.id },
                feedSessionForSave(current.copy(isAtTop = true), null, f.projection.value, 1400).eventIds)
        }
    }
}
