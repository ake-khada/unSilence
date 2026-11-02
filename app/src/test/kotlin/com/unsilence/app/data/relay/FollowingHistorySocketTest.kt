package com.unsilence.app.data.relay

import android.content.ContentResolver
import android.content.ContextWrapper
import com.unsilence.app.data.auth.*
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import dagger.Lazy
import com.unsilence.app.domain.model.FeedFilter
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.Dispatcher as ServerDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Full pooled/temporary transport, socket admission, crypto and timeline delivery on loopback. */
class FollowingHistorySocketTest {
    private class Fixture : AutoCloseable {
        val remote = ConcurrentHashMap<String, List<EventDto>>()
        val held = ConcurrentHashMap.newKeySet<String>()
        val closed = ConcurrentHashMap.newKeySet<String>()
        val requests = CopyOnWriteArrayList<Pair<String, JsonArray>>()
        val closes = CopyOnWriteArrayList<Pair<String, String>>()
        val sockets = ConcurrentHashMap<String, WebSocket>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : ServerDispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val relay = requireNotNull(request.getHeader("X-Fixture-Relay"))
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            sockets[relay] = webSocket
                            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val arr = NostrJson.parseToJsonElement(text).jsonArray
                            val id = arr[1].jsonPrimitive.content
                            when (arr[0].jsonPrimitive.content) {
                                "REQ" -> {
                                    requests += relay to arr
                                    if (relay !in held) {
                                        remote[relay].orEmpty().forEach { webSocket.send("""["EVENT","$id",${NostrJson.encodeToString(it)}]""") }
                                        webSocket.send(if (relay in closed) """["CLOSED","$id","restricted: fixture"]""" else """["EOSE","$id"]""")
                                    }
                                }
                                "CLOSE" -> closes += relay to id
                            }
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { active.decrementAndGet() }
                    })
                }
            }
            start()
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host.endsWith(".test")) { "External network forbidden" }
            chain.proceed(request.newBuilder().header("X-Fixture-Relay", "wss://${request.url.host}").url(server.url("/")).build())
        }.build()
        val context = object : ContextWrapper(null) {}
        val monitor = NetworkMonitor(context)
        val capabilities = RelayCapabilitiesStore(context, monitor)
        val keys = KeyManager(context)
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var subscription: Subscription
        val pool = RelayPool(
            RelayConnectionFactory(client, capabilities, RelayTrafficMetrics()), processor,
            Lazy { error("No AUTH or preference discovery in fixture") },
            SigningManager(keys, object : ContentResolver(context) {}), keys, Lazy { store }, capabilities,
            Lazy { subscription }, monitor, Nip11Fetcher(client, Lazy { store }),
        )
        val service: TimelineService
        init {
            runBlocking { processor.setTestScope(scope) }
            subscription = Subscription(pool, processor, pool, capabilities)
            service = TimelineService(subscription, StubEventLoader())
        }
        suspend fun until(predicate: () -> Boolean) = withTimeout(10_000) {
            while (!predicate()) delay(10)
        }
        override fun close() {
            subscription.resetForTest()
            pool.disconnectAll()
            for ((owner, field) in listOf(pool to "scope", capabilities to "persistenceScope", subscription to "reconnectScope")) {
                (owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(owner) as CoroutineScope).cancel()
            }
            processor.stop(); scope.cancel()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); server.shutdown()
        }
    }

    @Test fun `eleven real author relays deliver while ten live subscriptions remain untouched`() = runBlocking {
        Fixture().use { f ->
            val crypto = ZapCryptoTestFixture()
            val identities = (1..11).map { crypto.identity("%02x".format(it)) }
            val writes = identities.mapIndexed { i, identity -> identity.pubkey to listOf("wss://r$i.test") }.toMap()
            for ((i, identity) in identities.withIndex()) f.remote["wss://r$i.test"] = listOf(
                crypto.signedEvent(identity, 1, emptyList(), "history", createdAt = 100 + i.toLong()))
            val resolver = OutboxRelayResolver(object : RelayMetadataSource {
                override fun writeRelaysFor(pubkey: String) = writes[pubkey].orEmpty()
            })
            val live = resolver.resolveFollowing(writes.keys, emptyList(), emptySet(), OutboxRelayResolver.Config(listOf(1), 300))
            val routing = resolver.resolveFollowingHistory(writes.keys, live, emptySet())
            val liveUrls = live.flatMap { it.urls }.toSet()
            assertEquals(10, liveUrls.size)
            val displayed = ConcurrentHashMap<String, NostrEvent>()
            fun merge(events: List<NostrEvent>) { events.forEach { displayed[it.id] = it } }
            val handle = f.service.subscribeTimeline(live, { events, _ -> merge(events) }, { merge(listOf(it)) })
            try {
                f.until { displayed.size == 10 }
                val oldIds = f.subscription.activeSubIds().toSet()
                val omittedUrl = routing.candidates.values.single().single()
                f.held += omittedUrl
                val plan = FollowingHistoryPlan(FollowingHistoryScope("owner", routing.writes, FeedFilter()),
                    routing.candidates, NostrFilter(kinds = listOf(1), limit = 300), System.currentTimeMillis() / 1000)
                val history = launch { f.service.catchUpFollowing(plan, f.pool::fetchFollowingHistoryPage, ::merge) }
                f.until { f.requests.any { it.first == omittedUrl } }
                assertEquals(oldIds, f.subscription.activeSubIds())
                assertEquals(liveUrls, f.subscription.activeRelayUrls())
                val liveUrl = liveUrls.first()
                val identity = identities.single { writes.getValue(it.pubkey).single() == liveUrl }
                val latest = crypto.signedEvent(identity, 1, emptyList(), "still live", System.currentTimeMillis() / 1000 + 1)
                val liveId = f.requests.first { it.first == liveUrl }.second[1].jsonPrimitive.content
                f.sockets.getValue(liveUrl).send("""["EVENT","$liveId",${NostrJson.encodeToString(latest)}]""")
                f.until { displayed.containsKey(latest.id) }
                val id = f.requests.last { it.first == omittedUrl }.second[1].jsonPrimitive.content
                f.remote.getValue(omittedUrl).forEach { f.sockets.getValue(omittedUrl).send("""["EVENT","$id",${NostrJson.encodeToString(it)}]""") }
                f.sockets.getValue(omittedUrl).send("""["EOSE","$id"]""")
                withTimeout(10_000) { history.join() }
                assertEquals(identities.map { it.pubkey }.toSet(), displayed.values.map { it.pubkey }.toSet())
                assertEquals(oldIds, f.subscription.activeSubIds())
                assertTrue(f.closes.none { it.first in liveUrls })
                assertEquals(11, f.peak.get()) // Ten live plus ONE shared-budget temporary connection.
                assertEquals(11, f.requests.size)
                plan.run({ _, _ -> error("stationary feed re-armed") }, {})
            } finally { handle.close() }
        }
    }

    @Test fun `exact page buffers all accepted events before EOSE without waiting for MES`() = runBlocking {
        Fixture().use { f ->
            val crypto = ZapCryptoTestFixture()
            val url = "wss://page.test"
            val accepted = (1..3).map { crypto.signedEvent(crypto.sender, 1, emptyList(), "event-$it", it.toLong()) }
            val wrong = crypto.signedEvent(crypto.recipient, 1, emptyList(), "wrong author", 1)
            val future = crypto.signedEvent(crypto.sender, 1, emptyList(), "future", System.currentTimeMillis() / 1000 + 120)
            f.remote[url] = accepted + accepted + wrong + future
            val page = f.pool.fetchFollowingHistoryPage(url, NostrFilter(kinds = listOf(1), authors = listOf(crypto.sender.pubkey), limit = 10))
            assertEquals(OneShotOutcome.EOSE, page.outcome)
            assertEquals(accepted.map { it.id }.toSet(), page.events.map { it.id }.toSet())
            assertNull(f.store.getNostrEvent(wrong.id))
            f.remote["wss://empty.test"] = emptyList()
            val empty = f.pool.fetchFollowingHistoryPage("wss://empty.test", NostrFilter(kinds = listOf(1), authors = listOf(crypto.sender.pubkey)))
            assertEquals(OneShotOutcome.EOSE, empty.outcome); assertTrue(empty.events.isEmpty())
            f.remote["wss://bounded.test"] = accepted
            val bounded = f.pool.fetchFollowingHistoryPage("wss://bounded.test", NostrFilter(kinds = listOf(1), authors = listOf(crypto.sender.pubkey), limit = 2))
            assertEquals(2, bounded.events.size)
        }
    }

    @Test fun `pooled history EOSE closes and releases its slot without consuming another candidate`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pooled.test"
            val crypto = ZapCryptoTestFixture()
            val events = (1..3).map { crypto.signedEvent(crypto.sender, 1, emptyList(), "pooled-$it", it.toLong()) }
            f.remote[url] = events
            f.pool.connect(listOf(url))
            f.until { url in f.pool.connectedRelayUrls() }
            val before = f.pool.connectionDebugSnapshot().getValue(url).oneShotCount
            val page = f.pool.fetchFollowingHistoryPage(url, NostrFilter(authors = listOf(crypto.sender.pubkey), kinds = listOf(1)))
            assertEquals(OneShotOutcome.EOSE, page.outcome)
            assertEquals(events.map { it.id }.toSet(), page.events.map { it.id }.toSet())
            f.until { f.closes.any { it.first == url } }
            assertEquals(before, f.pool.connectionDebugSnapshot().getValue(url).oneShotCount)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun `CLOSED retains verified partial history and active cancellation releases temporary transport`() = runBlocking {
        Fixture().use { f ->
            val crypto = ZapCryptoTestFixture()
            val url = "wss://partial.test"
            val event = crypto.signedEvent(crypto.sender, 1, emptyList(), "partial", 1)
            f.remote[url] = listOf(event); f.closed += url
            val page = f.pool.fetchFollowingHistoryPage(url, NostrFilter(authors = listOf(crypto.sender.pubkey)))
            assertEquals(OneShotOutcome.CLOSED, page.outcome)
            assertEquals(listOf(event.id), page.events.map { it.id })
            val pending = "wss://pending.test"; f.held += pending
            val job = launch { f.pool.fetchFollowingHistoryPage(pending, NostrFilter(kinds = listOf(1))) }
            f.until { f.requests.any { it.first == pending } }
            job.cancelAndJoin()
            f.until { f.active.get() == 0 }
            assertFalse(f.pool.connectionDebugSnapshot().values.any { it.oneShotCount != 0 })
        }
    }

    @Test fun `thread one shots share four permits and cancelled queued history never sends`() = runBlocking {
        Fixture().use { f ->
            val threadUrls = (1..4).map { "wss://thread$it.test" }
            f.held.addAll(threadUrls)
            val threads = threadUrls.map { url -> launch { f.pool.fetchThread(listOf(url), "a".repeat(64)) } }
            try {
                f.until { f.requests.count { it.first in threadUrls } == 4 }
                val history = launch { f.pool.fetchFollowingHistoryPage("wss://queued.test", NostrFilter(kinds = listOf(1), authors = listOf("a".repeat(64)))) }
                delay(100)
                assertEquals(4, f.active.get())
                assertTrue(f.requests.none { it.first == "wss://queued.test" })
                history.cancelAndJoin()
                threads.forEach { it.cancelAndJoin() }
                f.until { f.active.get() == 0 }
                val page = withTimeout(5_000) { f.pool.fetchFollowingHistoryPage("wss://after.test", NostrFilter(kinds = listOf(1))) }
                assertEquals(OneShotOutcome.EOSE, page.outcome)
                assertTrue(f.requests.none { it.first == "wss://queued.test" })
                assertTrue(f.peak.get() <= MAX_EPHEMERAL_CONNECTIONS)
            } finally { threads.forEach { it.cancelAndJoin() } }
        }
    }
}
