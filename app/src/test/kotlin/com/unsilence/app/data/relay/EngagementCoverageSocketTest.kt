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

/** Real pooled transport and signed reply fixtures; no external relay contact. */
class EngagementCoverageSocketTest {
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
        init {
            runBlocking { processor.setTestScope(scope) }
            subscription = Subscription(pool, processor, pool, capabilities)
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


    @Test fun `ordinary background requests retain active feed exclusion while explicit source queries deliver`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://source.test"
            val root = "a".repeat(64)
            val crypto = ZapCryptoTestFixture()
            val reply = crypto.signedEvent(crypto.sender, 1, listOf(listOf("e", root)), "reply")
            f.remote[url] = listOf(reply)
            f.pool.activeSingleRelayFeedUrl = url
            f.pool.connect(listOf(url))
            f.until { url in f.pool.connectedRelayUrls() }
            val skippedId = "eng-background"
            val skipped = f.pool.fetchOneShotOutcomes(skippedId, listOf(url),
                buildBatchedEngagementReq(skippedId, listOf(root)), 1_000)
            assertEquals(OneShotOutcome.SKIPPED, skipped[url])
            assertTrue(f.requests.isEmpty())
            // Existing opt-in transport already serves this source safely.
            val sourceId = "eng-source"
            val eose = CompletableDeferred<Unit>()
            f.pool.oneShotEoseCallbacks[sourceId] = eose
            try {
                f.pool.sendOneShotBatch(listOf(url), listOf(buildBatchedEngagementReq(sourceId, listOf(root))),
                    listOf(sourceId), includeActiveFeedRelay = true)
                withTimeout(5_000) { eose.await() }
                f.processor.drainForTest()
                assertEquals(1, f.store.currentStatsSnapshot(root).replyCount)
                assertEquals(1, f.requests.size)
                f.until { f.closes.any { it.second == sourceId } }
                assertEquals(0, f.pool.connectionDebugSnapshot().getValue(url).oneShotCount)
            } finally { f.pool.cleanupOneShotSub(sourceId) }
        }
    }

    @Test fun `public source engagement uses active relay and fresh coverage prevents repeat`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://source.test"
            val root = "a".repeat(64)
            val crypto = ZapCryptoTestFixture()
            val reply = crypto.signedEvent(crypto.sender, 1, listOf(listOf("e", root)), "source reply")
            f.remote[url] = listOf(reply)
            f.pool.activeSingleRelayFeedUrl = url
            val target = CardHydrator.EngagementTarget(root, null, crypto.sender.pubkey, reply.createdAt,
                sourceRelays = listOf(url), observedRelays = observedEngagementRelays(root, root, url, emptyList()))
            val coordinator = EngagementFetchCoordinator(f.pool::fetchOneShotOutcomes)
            coordinator.fetch(listOf(target), listOf(url to listOf(root)))
            f.processor.drainForTest()
            assertEquals(1, f.store.currentStatsSnapshot(root).replyCount)
            assertFalse(coordinator.needsFetch(target))
            coordinator.fetch(listOf(target), listOf(url to listOf(root)))
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun `eager profile completion cannot suppress the viewport source query`() = runBlocking {
        Fixture().use { f ->
            val source = "wss://source.test"
            val preferred = (1..4).map { "wss://preferred$it.test" }
            val root = "a".repeat(64)
            val crypto = ZapCryptoTestFixture()
            val eagerReply = crypto.signedEvent(crypto.sender, 1, listOf(listOf("e", root)), "eager")
            val sourceReply = crypto.signedEvent(crypto.sender, 1, listOf(listOf("e", root)), "source")
            preferred.forEach { f.remote[it] = listOf(eagerReply) }
            f.remote[source] = listOf(sourceReply)
            f.pool.activeSingleRelayFeedUrl = source
            val target = CardHydrator.EngagementTarget(root, null, crypto.sender.pubkey, eagerReply.createdAt,
                sourceRelays = listOf(source), observedRelays = setOf(source))
            val coordinator = EngagementFetchCoordinator(f.pool::fetchOneShotOutcomes)
            // Actual eager-profile selection, builder and completion transport.
            // The viewport coordinator is independent, as in CardHydrator.
            val eagerTargets = selectProfileEngagementRelays(preferred, listOf(listOf(source)))
            assertEquals(preferred, eagerTargets)
            f.pool.connect(eagerTargets)
            f.until { f.pool.connectedRelayUrls().containsAll(preferred) }
            val id = "prof-eng-eager"
            val completed = CompletableDeferred<Unit>()
            f.pool.oneShotEoseCallbacks[id] = completed
            try {
                f.pool.sendOneShotBatch(eagerTargets, listOf(buildBatchedEngagementReq(id, listOf(root))), listOf(id))
                withTimeout(5_000) { completed.await() }
            } finally { f.pool.cleanupOneShotSub(id) }
            f.processor.drainForTest()
            assertEquals(1, f.store.currentStatsSnapshot(root).replyCount)
            assertTrue(coordinator.needsFetch(target))
            coordinator.fetch(listOf(target), listOf(source to listOf(root)))
            f.processor.drainForTest()
            assertEquals(2, f.store.currentStatsSnapshot(root).replyCount)
            assertEquals(1, f.requests.count { it.first == source })
        }
    }

    @Test fun `source exception never admits hint only or own backfill requests`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://source.test"
            f.pool.activeSingleRelayFeedUrl = url
            val id = "a".repeat(64)
            val hint = CardHydrator.EngagementTarget(id, null, "b".repeat(64), 1, sourceRelays = listOf(url))
            val coordinator = EngagementFetchCoordinator(f.pool::fetchOneShotOutcomes)
            coordinator.fetch(listOf(hint), listOf(url to listOf(id)))
            coordinator.fetch(listOf(hint.copy(observedRelays = setOf(url))), listOf(url to listOf(id)), "b".repeat(64))
            assertTrue(f.requests.isEmpty())
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun `blocked and background source requests still cannot open a socket`() = runBlocking {
        for (suspended in listOf(false, true)) Fixture().use { f ->
            val url = "wss://source.test"
            f.pool.activeSingleRelayFeedUrl = url
            if (suspended) f.pool.suspendSocketsForBackground() else f.pool.onBlockedRelaysChanged(setOf(url))
            val root = "a".repeat(64)
            val target = CardHydrator.EngagementTarget(root, null, "b".repeat(64), 1, observedRelays = setOf(url))
            val coordinator = EngagementFetchCoordinator(f.pool::fetchOneShotOutcomes)
            coordinator.fetch(listOf(target), listOf(url to listOf(root)))
            assertEquals(0, f.server.requestCount)
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun `public profile engagement EOSE completes and releases pooled slot`() = runBlocking { profileEose("prof-eng-") }
    @Test fun `own profile engagement EOSE completes and releases pooled slot`() = runBlocking { profileEose("prof-own-eng-") }

    private suspend fun profileEose(prefix: String) {
        Fixture().use { f ->
            val url = "wss://profile.test"
            f.pool.connect(listOf(url))
            f.until { url in f.pool.connectedRelayUrls() }
            val subId = prefix + "fixture"
            val eose = CompletableDeferred<Unit>()
            f.pool.oneShotEoseCallbacks[subId] = eose
            try {
                // ProfilePipeline uses these exact builders and legacy completion.
                val req = if (prefix == "prof-eng-") buildBatchedEngagementReq(subId, listOf("a".repeat(64)))
                    else buildOwnEngagementReq(subId, "b".repeat(64), listOf("a".repeat(64)))
                f.pool.sendOneShotBatch(listOf(url), listOf(req), listOf(subId))
                assertTrue("Real profile EOSE must complete before the network watchdog",
                    withTimeoutOrNull(1_000) { eose.await(); true } == true)
                f.until { f.closes.any { it.second == subId } }
                assertEquals(0, f.pool.connectionDebugSnapshot().getValue(url).oneShotCount)
            } finally { f.pool.cleanupOneShotSub(subId) }
        }
    }
}
