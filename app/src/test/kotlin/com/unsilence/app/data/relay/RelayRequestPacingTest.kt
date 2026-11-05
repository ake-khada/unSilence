package com.unsilence.app.data.relay

import android.content.ContentResolver
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import com.unsilence.app.data.auth.*
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import dagger.Lazy
import com.unsilence.app.domain.model.FeedFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.Dispatcher as ServerDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Exercises production pooled admission/draining with held loopback requests. */
class RelayRequestPacingTest {
    private class Fixture(
        private val bridgeLoopback: Boolean = false,
        private val handshakeGate: CountDownLatch? = null,
        private val stallFirstUpgrade: Boolean = false,
    ) : AutoCloseable {
        val remote = ConcurrentHashMap<String, List<EventDto>>()
        val held = ConcurrentHashMap.newKeySet<String>()
        val closed = ConcurrentHashMap.newKeySet<String>()
        val requests = CopyOnWriteArrayList<Pair<String, JsonArray>>()
        val closes = CopyOnWriteArrayList<Pair<String, String>>()
        val sockets = ConcurrentHashMap<String, WebSocket>()
        val opens = ConcurrentHashMap<String, AtomicInteger>()
        val attempts = ConcurrentHashMap<String, AtomicInteger>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : ServerDispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val relay = requireNotNull(request.getHeader("X-Fixture-Relay"))
                    val attempt = attempts.computeIfAbsent(relay) { AtomicInteger() }.incrementAndGet()
                    if (stallFirstUpgrade && attempt == 1) {
                        return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    check(handshakeGate?.await(5, TimeUnit.SECONDS) != false)
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            sockets[relay] = webSocket
                            opens.computeIfAbsent(relay) { AtomicInteger() }.incrementAndGet()
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
        val client = OkHttpClient.Builder()
            .readTimeout(if (stallFirstUpgrade) 0 else 10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host.endsWith(".test") || (bridgeLoopback && isBridgeFallbackRelay("wss://${request.url.host}"))) { "External network forbidden" }
            chain.proceed(request.newBuilder().header("X-Fixture-Relay", "wss://${request.url.host}").url(server.url("/")).build())
        }.build()
        val context = object : ContextWrapper(null) {}
        val monitor = NetworkMonitor(context)
        val capabilities = RelayCapabilitiesStore(context, monitor)
        val keys = KeyManager(context)
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preferences = RelayPreferencesStore(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }, scope)
        lateinit var subscription: Subscription
        val pool = RelayPool(
            RelayConnectionFactory(client, capabilities, RelayTrafficMetrics()), processor,
            Lazy { preferences },
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

    private fun field(owner: Any, name: String): Any =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)!!

    private fun connection(f: Fixture, url: String): RelayConnection =
        (field(field(f.pool, "connectionRegistry"), "pooled") as Map<*, *>)[url] as RelayConnection

    @Test fun `stalled upgrades recover all pooled entry paths and replay a buffered live request once`() = runBlocking {
        Fixture(stallFirstUpgrade = true).use { f ->
            val urls = listOf("connect", "await", "lookup", "reconnect").map { "wss://$it.test" }
            urls.forEach { f.pool.addPurpose(it, ConnectionPurpose.PERSISTENT) }
            f.processor.start()
            val crypto = ZapCryptoTestFixture()
            val event = crypto.signedEvent(crypto.sender, 1, emptyList(), "recovered live frame")
            f.remote[urls[0]] = listOf(event)
            f.pool.connect(listOf(urls[0]))
            val awaiting = async { f.pool.connectAndAwait(listOf(urls[1]), 100, false, RelayRequestClass.GENERAL) }
            val lookup = async {
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<RelayConnection?> { continuation ->
                    RelayPool::class.java.getDeclaredMethod("getOrCreateConnection", String::class.java,
                        RelayRequestClass::class.java, kotlin.coroutines.Continuation::class.java)
                        .apply { isAccessible = true }
                        .invoke(f.pool, urls[2], RelayRequestClass.GENERAL, continuation)
                }
            }
            RelayPool::class.java.getDeclaredMethod("reconnectWithBackoff", String::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(f.pool, urls[3], 0)
            f.until { f.attempts.size == 4 }
            val original = urls.associateWith { connection(f, it) }
            assertEquals(0, awaiting.await())
            // A second caller shares the same attempt; cancellation must not close it.
            val joined = launch { f.pool.connectAndAwait(listOf(urls[0]), 30_000, false, RelayRequestClass.GENERAL) }
            yield()
            joined.cancelAndJoin()
            assertSame(original.getValue(urls[0]), connection(f, urls[0]))
            assertEquals(RelayState.CONNECTING, connection(f, urls[0]).state.value)
            val delivered = CopyOnWriteArrayList<String>()
            val eose = CompletableDeferred<Unit>()
            val handle = f.subscription.subscribe(listOf(urls[0]), NostrFilter(kinds = listOf(1)),
                onevent = { delivered += it.id }, oneose = { if (it) eose.complete(Unit) })
            try {
                assertNull(lookup.await()) // Its five-second caller wait leaves the shared socket intact.
                val liveId = f.subscription.activeSubIds().single()
                assertNotNull(original.getValue(urls[0]).requests.request(liveId))
                assertTrue(f.requests.isEmpty()) // Accepted locally, never reached the silent server.
                sendDirect(f, urls[1], "hint-batch-stalled")
                assertEquals(1, count(f, urls[1]).get())
                withTimeout(20_000) {
                    f.pool.connectionStates.first { states -> urls.all { states[it] == RelayState.CONNECTED } }
                    eose.await()
                    f.until { delivered.isNotEmpty() }
                }
                urls.forEach { url ->
                    assertNotSame(original.getValue(url), connection(f, url))
                    assertEquals(2, f.attempts.getValue(url).get())
                    assertEquals(1, f.opens.getValue(url).get())
                }
                assertEquals(0, (field(f.pool, "relayOneShotCount") as Map<*, *>)[urls[1]].let { (it as? AtomicInteger)?.get() ?: 0 })
                assertNull(connection(f, urls[1]).requests.request("hint-batch-stalled"))
                assertEquals(listOf(event.id), delivered)
                assertEquals(listOf(liveId), f.requests.map { it.second[1].jsonPrimitive.content })
                // Ordered barrier: duplicate replay would have arrived ahead of this frame.
                assertTrue(f.pool.sendToRelay(urls[0], """["REQ","feed-barrier",{"kinds":[1]}]"""))
                f.until { f.requests.size >= 2 }
                assertEquals(1, f.requests.count { it.second[1].jsonPrimitive.content == liveId })
            } finally { handle.close(); awaiting.cancelAndJoin(); lookup.cancelAndJoin() }
        }
    }

    @Test fun `finite request waiting for a pooled upgrade keeps its shorter owner deadline`() = runBlocking {
        Fixture(stallFirstUpgrade = true).use { f ->
            val url = "wss://finite-upgrade.test"
            f.pool.connect(listOf(url))
            f.until { f.attempts.containsKey(url) }
            val result = withTimeout(3_000) { f.pool.fetchOneShotOutcomes("hint-batch-upgrade", listOf(url),
                """["REQ","hint-batch-upgrade",{"kinds":[1]}]""", 30_000) }
            assertEquals(mapOf(url to OneShotOutcome.TIMEOUT), result)
            assertEquals(RelayState.CONNECTING, connection(f, url).state.value)
            assertTrue(f.requests.isEmpty())
            assertEquals(0, (field(f.pool, "relayOneShotCount") as Map<*, *>)[url].let { (it as? AtomicInteger)?.get() ?: 0 })
        }
    }

    @Test fun `background and account teardown cancel pending upgrades without new attempts`() = runBlocking {
        Fixture(stallFirstUpgrade = true).use { f ->
            val url = "wss://background-upgrade.test"
            f.pool.addPurpose(url, ConnectionPurpose.PERSISTENT)
            f.pool.connect(listOf(url))
            f.until { f.attempts.containsKey(url) }
            val original = connection(f, url)
            f.pool.suspendSocketsForBackground()
            assertFalse(original.isConnected)
            f.pool.disconnectAll()
            f.until { f.client.dispatcher.runningCallsCount() == 0 }
            assertTrue((field(f.pool, "reconnecting") as Map<*, *>).isEmpty())
            assertTrue((field(f.pool, "reconnectAttempts") as Map<*, *>).isEmpty())
            assertTrue((field(f.pool, "pendingReconnect") as Set<*>).isEmpty())
            assertTrue((field(field(f.pool, "connectionRegistry"), "pooled") as Map<*, *>).isEmpty())
            assertTrue(f.requests.isEmpty())
            assertEquals(1, f.attempts.getValue(url).get())
        }
    }

    private fun release(f: Fixture, url: String, id: String) {
        RelayPool::class.java.getDeclaredMethod("handleEose", RelayConnection::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(f.pool, connection(f, url), """["EOSE","$id"]""")
    }

    private fun limiter(f: Fixture, url: String): Any = (field(f.pool, "rateLimiters") as Map<*, *>)[url]!!
    private fun freezeRefill(f: Fixture, url: String) {
        (field(limiter(f, url), "lastRefill") as java.util.concurrent.atomic.AtomicLong)
            .set((System.nanoTime() / 1_000_000) + 60_000)
    }
    private suspend fun send(f: Fixture, url: String, id: String) {
        f.pool.sendOneShotBatch(listOf(url), listOf("""["REQ","$id",{"kinds":[1],"limit":1}]"""), listOf(id))
    }
    private suspend fun prepare(f: Fixture, url: String) {
        f.held += url
        f.pool.connect(listOf(url))
        withTimeout(5_000) { f.pool.connectionStates.first { it[url] == RelayState.CONNECTED } }
    }

    private suspend fun profilePhase(
        f: Fixture, url: String, authors: List<String>,
        prefix: String = "hint-profiles", urls: List<String> = listOf(url),
    ): Unit =
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
            RelayPool::class.java.getDeclaredMethod(
                "fetchProfilePhase", List::class.java, List::class.java, String::class.java,
                Boolean::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java,
            ).apply { isAccessible = true }.invoke(f.pool, authors, urls, prefix, true, continuation)
        }

    @Test fun `author fallback phase settles both pooled relays on wire EOSE`() = runBlocking {
        pooledProfilePhase("prof-fb")
    }

    @Test fun `bridge profile phase settles both pooled relays on wire EOSE`() = runBlocking {
        pooledProfilePhase("bridge-profiles")
    }

    @Test fun `fallback phase releases a CLOSED target without replaying it`() = runBlocking {
        pooledProfilePhase("prof-fb", lastClosed = true)
    }

    private suspend fun pooledProfilePhase(prefix: String, lastClosed: Boolean = false) = coroutineScope {
        Fixture().use { f ->
            val urls = listOf("wss://profile-a.test", "wss://profile-b.test")
            urls.forEach { prepare(f, it) }
            val authors = listOf("a".repeat(64))
            val phase = async { profilePhase(f, urls.first(), authors, prefix, urls) }
            try {
                f.until { f.requests.size == 2 }
                val id = f.requests.first().second[1].jsonPrimitive.content
                assertTrue(id.startsWith("$prefix-"))
                assertEquals(urls.toSet(), f.requests.map { it.first }.toSet())
                f.requests.forEach { (_, req) ->
                    assertEquals(id, req[1].jsonPrimitive.content)
                    assertEquals(buildJsonObject {
                        put("kinds", buildJsonArray { add(0); add(10002) })
                        put("authors", buildJsonArray { add(authors.single()) })
                    }, req[2])
                }
                assertTrue(f.sockets.getValue(urls[0]).send("""["EOSE","$id"]"""))
                withTimeout(2_000) { f.until { (urls[0] to id) in f.closes } }
                assertFalse("the silent relay still owns this phase", phase.isCompleted)
                assertEquals(0, count(f, urls[0]).get())
                assertEquals(1, count(f, urls[1]).get())
                val terminal = if (lastClosed) """["CLOSED","$id","blocked: fixture"]"""
                    else """["EOSE","$id"]"""
                assertTrue(f.sockets.getValue(urls[1]).send(terminal))
                withTimeout(2_000) { phase.await() }
                // A later frame received by each server is a wire-order barrier for
                // CLOSEs enqueued by the EOSE handler and the phase's finally cleanup.
                urls.forEach { url ->
                    assertTrue(f.pool.sendToRelay(url, """["REQ","feed-barrier",{"kinds":[1]}]"""))
                }
                f.until { f.requests.count { it.second[1].jsonPrimitive.content == "feed-barrier" } == 2 }
                urls.forEach { url ->
                    assertEquals(1, f.closes.count { it == url to id })
                    assertEquals(0, count(f, url).get())
                }
                assertFalse(f.pool.oneShotEoseCallbacks.containsKey(id))
                assertEquals("no closed profile request is replayed", 2,
                    f.requests.count { it.second[1].jsonPrimitive.content == id })
            } finally { phase.cancelAndJoin() }
        }
    }

    @Test fun `indexer profile relay facts complete on pooled wire EOSE`() = runBlocking {
        pooledProfileFacts(indexer = true)
    }

    @Test fun `bridge profile relay facts complete on pooled wire EOSE`() = runBlocking {
        pooledProfileFacts(indexer = false)
    }

    @Test fun `indexer relay facts CLOSED does not certify successful coverage`() = runBlocking {
        pooledProfileFacts(indexer = true, closed = true)
    }

    @Test fun `bridge relay facts CLOSED does not certify successful coverage`() = runBlocking {
        pooledProfileFacts(indexer = false, closed = true)
    }

    private suspend fun pooledProfileFacts(indexer: Boolean, closed: Boolean = false) = coroutineScope {
        Fixture(bridgeLoopback = true).use { f ->
            val url = BRIDGE_FALLBACK_RELAY_URLS.single()
            prepare(f, url)
            // Using the bridge as the explicit indexer prevents a second miss
            // phase, while the empty configuration exercises the real bridge path.
            if (indexer) {
                f.preferences.setIndexerUrls(listOf(url))
                f.preferences.indexerRelayUrlsFlow().first { it == listOf(url) }
            }
            val author = "a".repeat(64)
            val lookup = async { f.pool.fetchProfileRelayFacts(author) }
            try {
                f.until { f.requests.size == 1 }
                val (target, req) = f.requests.single()
                val id = req[1].jsonPrimitive.content
                assertEquals(url, target)
                assertTrue(id.startsWith(if (indexer) "profile-relays-" else "bridge-profile-relays-"))
                assertEquals(buildJsonObject {
                    put("kinds", buildJsonArray { add(10002); add(10006); add(10007) })
                    put("authors", buildJsonArray { add(author) })
                    put("limit", 3)
                }, req[2])
                val terminal = if (closed) """["CLOSED","$id","blocked: fixture"]"""
                    else """["EOSE","$id"]"""
                assertTrue(f.sockets.getValue(url).send(terminal))
                assertEquals("CLOSED is completion, not successful coverage", !closed,
                    withTimeout(2_000) { lookup.await() })
                f.until { (url to id) in f.closes }
                assertEquals(0, count(f, url).get())
                assertFalse(f.pool.oneShotEoseCallbacks.containsKey(id))
                assertEquals(1, f.requests.size)
                assertEquals(1, f.opens.getValue(url).get())
            } finally { lookup.cancelAndJoin() }
        }
    }

    @Test fun `relay facts require EOSE from every selected relay`() = runBlocking {
        mixedProfileFacts(lastClosed = false)
    }

    @Test fun `partial relay facts EOSE plus CLOSED is not successful coverage`() = runBlocking {
        mixedProfileFacts(lastClosed = true)
    }

    private suspend fun mixedProfileFacts(lastClosed: Boolean) = coroutineScope {
        Fixture(bridgeLoopback = true).use { f ->
            val urls = listOf(BRIDGE_FALLBACK_RELAY_URLS.single(), "wss://facts-second.test")
            urls.forEach { prepare(f, it) }
            f.preferences.setIndexerUrls(urls)
            f.preferences.indexerRelayUrlsFlow().first { it.toSet() == urls.toSet() }
            val lookup = async { f.pool.fetchProfileRelayFacts("a".repeat(64)) }
            try {
                f.until { f.requests.size == 2 }
                val id = f.requests.first().second[1].jsonPrimitive.content
                assertTrue(f.sockets.getValue(urls[0]).send("""["EOSE","$id"]"""))
                f.until { (urls[0] to id) in f.closes }
                assertFalse(lookup.isCompleted)
                val terminal = if (lastClosed) """["CLOSED","$id","blocked: fixture"]"""
                    else """["EOSE","$id"]"""
                assertTrue(f.sockets.getValue(urls[1]).send(terminal))
                assertEquals(!lastClosed, withTimeout(2_000) { lookup.await() })
                f.until { (urls[1] to id) in f.closes }
                urls.forEach { assertEquals(0, count(f, it).get()) }
            } finally { lookup.cancelAndJoin() }
        }
    }

    @Test fun `skipped relay facts do not certify successful coverage`() = runBlocking {
        Fixture(bridgeLoopback = true).use { f ->
            val url = BRIDGE_FALLBACK_RELAY_URLS.single()
            f.preferences.setIndexerUrls(listOf(url))
            f.preferences.indexerRelayUrlsFlow().first { it == listOf(url) }
            f.pool.suspendSocketsForBackground()
            assertFalse(withTimeout(2_000) { f.pool.fetchProfileRelayFacts("a".repeat(64)) })
            assertTrue(f.requests.isEmpty())
            assertTrue(f.opens.isEmpty())
        }
    }

    @Test fun `relay facts pipeline retries CLOSED and caches only successful coverage`() = runBlocking {
        Fixture(bridgeLoopback = true).use { f ->
            val url = BRIDGE_FALLBACK_RELAY_URLS.single()
            prepare(f, url)
            f.preferences.setIndexerUrls(listOf(url))
            f.preferences.indexerRelayUrlsFlow().first { it == listOf(url) }
            val resolver = ProfileResolver(f.store, Lazy { f.pool })
            val pipeline = ProfilePipeline(f.pool, f.store,
                com.unsilence.app.data.repository.UserRepository(f.store, resolver),
                f.preferences, f.capabilities, f.monitor, PrimalCacheClient(f.client))
            val author = "a".repeat(64)
            try {
                for (reject in listOf(true, false)) {
                    val before = f.requests.size
                    val lookup = async { pipeline.fetchProfileRelayFacts(author) }
                    try {
                        withTimeout(2_000) { f.until { f.requests.size == before + 1 } }
                        val id = f.requests.last().second[1].jsonPrimitive.content
                        assertTrue(f.sockets.getValue(url).send(if (reject)
                            """["CLOSED","$id","blocked: fixture"]""" else """["EOSE","$id"]"""))
                        assertEquals(!reject, withTimeout(2_000) { lookup.await() })
                    } finally { lookup.cancelAndJoin() }
                }
                assertTrue(withTimeout(2_000) { pipeline.fetchProfileRelayFacts(author) })
                assertEquals("successful coverage is reused without a third REQ", 2, f.requests.size)
            } finally {
                (field(pipeline, "pipelineScope") as CoroutineScope).cancel()
                (field(resolver, "scope") as CoroutineScope).cancel()
            }
        }
    }

    @Test fun `deliberately excluded feed relay is not a failed facts target`() = runBlocking {
        Fixture(bridgeLoopback = true).use { f ->
            val queried = BRIDGE_FALLBACK_RELAY_URLS.single()
            val excluded = "wss://active-feed.test"
            prepare(f, queried)
            f.pool.activeSingleRelayFeedUrl = excluded
            f.preferences.setIndexerUrls(listOf(queried, excluded))
            f.preferences.indexerRelayUrlsFlow().first { it.size == 2 }
            val lookup = async { f.pool.fetchProfileRelayFacts("a".repeat(64)) }
            try {
                f.until { f.requests.size == 1 }
                val (url, req) = f.requests.single()
                assertEquals(queried, url)
                assertTrue(f.sockets.getValue(url).send("""["EOSE",${req[1]}]"""))
                assertTrue(withTimeout(2_000) { lookup.await() })
                assertEquals(setOf(queried), f.opens.keys)
            } finally { lookup.cancelAndJoin() }
        }
    }

    @Test fun `queued then silent relay facts retain the overall deadline`() = runBlocking {
        Fixture(bridgeLoopback = true).use { f ->
            val url = BRIDGE_FALLBACK_RELAY_URLS.single()
            exhaust(f, url)
            f.preferences.setIndexerUrls(listOf(url))
            f.preferences.indexerRelayUrlsFlow().first { it == listOf(url) }
            withTimeout(10_000) {
                val lookup = async { f.pool.fetchProfileRelayFacts("a".repeat(64)) }
                try {
                    f.until { queued(f, url).size == 1 }
                    delay(3_000) // Spend part of the phase budget waiting for a token.
                    tokens(f, url).set(1)
                    drain(f, url)
                    f.until { f.requests.size == 6 }
                    assertFalse(lookup.await()) // No response after dispatch.
                    assertTrue(queued(f, url).isEmpty())
                    assertEquals(5, count(f, url).get())
                } finally { lookup.cancelAndJoin() }
            }
        }
    }

    @Test fun `silent relay facts expire without successful coverage`() = runBlocking {
        Fixture(bridgeLoopback = true).use { f ->
            val url = BRIDGE_FALLBACK_RELAY_URLS.single()
            prepare(f, url)
            f.preferences.setIndexerUrls(listOf(url))
            f.preferences.indexerRelayUrlsFlow().first { it == listOf(url) }
            val lookup = async { f.pool.fetchProfileRelayFacts("a".repeat(64)) }
            try {
                f.until { f.requests.size == 1 }
                val id = f.requests.single().second[1].jsonPrimitive.content
                assertFalse(withTimeout(10_000) { lookup.await() })
                f.until { (url to id) in f.closes }
                assertEquals(0, count(f, url).get())
            } finally { lookup.cancelAndJoin() }
        }
    }

    @Test fun `cancelled relay facts remove queued work before tokens become available`() = runBlocking {
        Fixture(bridgeLoopback = true).use { f ->
            val url = BRIDGE_FALLBACK_RELAY_URLS.single()
            exhaust(f, url)
            f.preferences.setIndexerUrls(listOf(url))
            f.preferences.indexerRelayUrlsFlow().first { it == listOf(url) }
            val lookup = async { f.pool.fetchProfileRelayFacts("a".repeat(64)) }
            f.until { queued(f, url).size == 1 }
            lookup.cancelAndJoin()
            assertTrue(queued(f, url).isEmpty())
            tokens(f, url).set(1)
            drain(f, url)
            assertEquals(1, tokens(f, url).get())
            assertEquals(5, f.requests.size)
            assertEquals(5, count(f, url).get())
        }
    }

    @Test fun `cancelled bridge profile phase cannot dispatch queued siblings`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://bridge-cancel.test"
            exhaust(f, url)
            val phase = async { profilePhase(f, url,
                (1..201).map { it.toString(16).padStart(64, '0') }, "bridge-profiles") }
            f.until { queued(f, url).size == 3 }
            phase.cancelAndJoin()
            assertTrue(queued(f, url).isEmpty())
            assertTrue(f.pool.oneShotEoseCallbacks.keys.none { it.startsWith("bridge-profiles-") })
            assertEquals(5, f.requests.size)
            assertEquals(5, count(f, url).get())
        }
    }

    @Test fun `profile CLOSED remains a distinct outcome with no live replay`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://profile-closed.test"
            prepare(f, url)
            val id = "bridge-profile-relays-fixture"
            val lookup = async { f.pool.fetchOneShotOutcomes(id, listOf(url),
                """["REQ","$id",{"kinds":[10002,10006,10007],"authors":["${"a".repeat(64)}"],"limit":3}]""", 5_000) }
            try {
                f.until { f.requests.size == 1 }
                assertTrue(f.sockets.getValue(url).send("""["CLOSED","$id","blocked: fixture"]"""))
                assertEquals(mapOf(url to OneShotOutcome.CLOSED), withTimeout(2_000) { lookup.await() })
                f.until { (url to id) in f.closes }
                assertEquals(0, count(f, url).get())
                assertEquals(1, f.requests.size)
            } finally { lookup.cancelAndJoin() }
        }
    }

    @Test fun `persistent EOSE stays open and page EOSE remains owned by pagination`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://profile-controls.test"
            prepare(f, url)
            val liveEose = CompletableDeferred<Unit>()
            val live = f.subscription.subscribe(listOf(url), NostrFilter(kinds = listOf(1)),
                onevent = {}, oneose = { if (it) liveEose.complete(Unit) })
            try {
                f.until { f.requests.size == 1 }
                val liveId = f.requests.single().second[1].jsonPrimitive.content
                assertTrue(f.sockets.getValue(url).send("""["EOSE","$liveId"]"""))
                withTimeout(2_000) { liveEose.await() }
                val page = async { f.pool.fetchPaginatedEvents(listOf(url),
                    buildJsonObject { put("kinds", buildJsonArray { add(10002) }) },
                    subIdPrefix = "profile-relays", maxPages = 1) }
                f.until { f.requests.size == 2 }
                val pageId = f.requests.last().second[1].jsonPrimitive.content
                assertTrue(f.sockets.getValue(url).send("""["EOSE","$pageId"]"""))
                assertEquals(1, withTimeout(2_000) { page.await() }.single().totalPages)
                f.until { (url to pageId) in f.closes }
                assertEquals(1, f.closes.count { it == url to pageId })
                assertFalse((url to liveId) in f.closes)
                assertTrue(liveId in f.subscription.activeSubIds())
            } finally { live.close() }
        }
    }

    @Test fun `overlapping reference hints reach a shared unpooled relay in one request`() = runBlocking {
        Fixture().use { f ->
            val shared = "wss://shared-ref.test"
            val a = "a".repeat(64)
            val b = "b".repeat(64)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                f.pool.fetchEventById(a, listOf(shared, "wss://left-ref.test"))
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                f.pool.fetchEventById(b, listOf(shared, "wss://right-ref.test"))
            }
            try {
                f.until { f.requests.any { it.first == shared } }
                val req = f.requests.first { it.first == shared }.second
                assertEquals(setOf(a, b), req[2].jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content }.toSet())
                withTimeout(3_000) { awaitAll(first, second) }
                val pairs = f.requests.flatMap { (url, frame) -> frame[2].jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content to url } }
                assertEquals(setOf(a to shared, a to "wss://left-ref.test", b to shared, b to "wss://right-ref.test"), pairs.toSet())
                assertEquals(4, pairs.size)
                assertEquals(3, f.requests.size)
            } finally { first.cancel(); second.cancel() }
        }
    }

    @Test fun `ready reference finishes before an unrelated held relay completes`() = runBlocking {
        Fixture().use { f ->
            val slow = "wss://slow-ref.test"
            val ready = "wss://ready-ref.test"
            prepare(f, slow)
            prepare(f, ready)
            f.held.remove(ready)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                f.pool.fetchEventById("a".repeat(64), listOf(slow))
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                f.pool.fetchEventById("b".repeat(64), listOf(ready))
            }
            try {
                withTimeout(2_000) { second.await() }
                assertFalse("unrelated slow relay still has not completed", first.isCompleted)
                val slowReq = f.requests.single { it.first == slow }.second
                release(f, slow, slowReq[1].jsonPrimitive.content)
                withTimeout(2_000) { first.await() }
            } finally { first.cancel(); second.cancel() }
        }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private suspend fun referenceWindow(f: Fixture, batches: List<EventIdFetchBatch>): Unit =
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
            RelayPool::class.java.getDeclaredMethod(
                "fetchCoalescedEventReferences", List::class.java, kotlin.jvm.functions.Function2::class.java,
                kotlin.coroutines.Continuation::class.java,
            ).apply { isAccessible = true }.invoke(f.pool, batches, { _: Int, _: Throwable? -> Unit }, continuation)
        }

    @Test fun `reference group awaits every destination before completing`() = runBlocking {
        Fixture().use { f ->
            val ready = "wss://ready-all.test"
            val held = "wss://held-all.test"
            prepare(f, ready); prepare(f, held)
            f.held.remove(ready)
            val lookup = async { f.pool.fetchEventById("a".repeat(64), listOf(ready, held)) }
            try {
                f.until { f.requests.size == 2 }
                delay(350) // The ready relay has EOSE and the processor settle interval has elapsed.
                assertFalse("one relay's EOSE must not finish the original group", lookup.isCompleted)
                release(f, held, f.requests.single { it.first == held }.second[1].jsonPrimitive.content)
                withTimeout(2_000) { lookup.await() }
            } finally { lookup.cancel() }
        }
    }

    @Test fun `merged reference relay chunks stay bounded and use one unpooled socket`() = runBlocking {
        Fixture().use { f ->
            val shared = "wss://many-shared.test"
            val expected = (1..120).associate { n ->
                n.toString(16).padStart(64, '0') to listOf(shared, "wss://group-${(n - 1) / 40}.test")
            }
            val lookups = expected.map { (id, hints) ->
                async(start = CoroutineStart.UNDISPATCHED) { f.pool.fetchEventById(id, hints) }
            }
            try {
                withTimeout(5_000) { lookups.awaitAll() }
                val sharedRequests = f.requests.filter { it.first == shared }
                assertEquals(3, sharedRequests.size)
                assertEquals(1, f.opens[shared]?.get())
                val actual = f.requests.flatMap { (url, frame) ->
                    val filter = frame[2].jsonObject
                    assertEquals(EVENT_REFERENCE_FETCH_KINDS.toSet(), filter.getValue("kinds").jsonArray.map { it.jsonPrimitive.int }.toSet())
                    val ids = filter.getValue("ids").jsonArray
                    assertTrue(ids.size in 1..MAX_EVENT_IDS_PER_REQ)
                    ids.map { it.jsonPrimitive.content to url }
                }
                assertEquals(expected.flatMap { (id, urls) -> urls.map { id to it } }.toSet(), actual.toSet())
                assertEquals(240, actual.size)
                assertEquals(6, f.requests.size)
                assertTrue("existing four-connection budget", f.peak.get() <= 4)
            } finally { lookups.forEach { it.cancel() } }
        }
    }

    @Test fun `cancelled reference window removes every queued chunk before releasing a slot`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://reference-cancel.test"
            exhaust(f, url)
            tokens(f, url).set(1)
            val batches = (0..2).map { group ->
                val blocked = "wss://blocked-$group.test"
                f.pool.onBlockedRelaysChanged(f.pool.getBlockedUrls() + blocked)
                EventIdFetchBatch((1..40).map { (group * 40 + it).toString(16).padStart(64, '0') }, listOf(url, blocked))
            }
            val window = async { referenceWindow(f, batches) }
            f.until { f.requests.size == 6 && queued(f, url).size == 2 }
            send(f, url, "hint-batch-unrelated")
            tokens(f, url).set(1)
            window.cancelAndJoin()
            f.until { f.requests.size == 7 }
            assertEquals("hint-batch-unrelated", f.requests.last().second[1].jsonPrimitive.content)
            assertTrue(queued(f, url).isEmpty())
            assertTrue(f.pool.oneShotEoseCallbacks.isEmpty())
            assertEquals(6, count(f, url).get()) // Five held requests plus the unrelated request.
        }
    }

    @Test fun `filtered hints fall back once and await fallback EOSE without a second admission`() = runBlocking {
        Fixture().use { f ->
            val blocked = "wss://blocked-hint.test"
            val fallback = "wss://fallback-ref.test"
            val id = "a".repeat(64)
            prepare(f, fallback)
            f.pool.onBlockedRelaysChanged(setOf(blocked))
            val lookup = async { f.pool.fetchEventById(id, listOf(blocked)) }
            try {
                f.until { f.requests.size == 1 }
                val monitors = field(f.pool, "eventFetchInFlight") as Map<*, *>
                val originalMonitor = requireNotNull(monitors[id])
                assertEquals(fallback, f.requests.single().first)
                assertEquals(listOf(id), f.requests.single().second[2].jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
                delay(350)
                assertFalse(lookup.isCompleted)
                release(f, fallback, f.requests.single().second[1].jsonPrimitive.content)
                withTimeout(2_000) { lookup.await() }
                assertSame(originalMonitor, monitors[id])
                assertEquals(1, f.requests.size)
                assertTrue((field(f.pool, "bridgeEventFetchAttempted") as Map<*, *>).isEmpty())
            } finally { lookup.cancel() }
        }
    }

    @Test fun `hinted IDs do not carry fallback only IDs onto the active feed relay`() = runBlocking {
        Fixture().use { f ->
            val feed = "wss://active-ref.test"
            val other = "wss://other-ref.test"
            val blocked = "wss://blocked-ref.test"
            prepare(f, feed); prepare(f, other)
            f.held.clear()
            f.pool.activeSingleRelayFeedUrl = feed
            f.pool.onBlockedRelaysChanged(setOf(blocked))
            val a = "a".repeat(64); val b = "b".repeat(64)
            val first = async(start = CoroutineStart.UNDISPATCHED) { f.pool.fetchEventById(a, listOf(feed)) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { f.pool.fetchEventById(b, listOf(blocked)) }
            withTimeout(3_000) { awaitAll(first, second) }
            val pairs = f.requests.flatMap { (url, frame) -> frame[2].jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content to url } }
            assertEquals(setOf(a to feed, b to other), pairs.toSet())
            assertEquals(2, pairs.size)
        }
    }

    @Test fun `ordinary duplicate skips dispatch while explicit retry retains the existing monitor`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://dedup-ref.test"
            prepare(f, url); f.held.clear()
            val id = "a".repeat(64)
            f.pool.fetchEventById(id, listOf(url))
            val monitors = field(f.pool, "eventFetchInFlight") as Map<*, *>
            val originalMonitor = requireNotNull(monitors[id])
            f.pool.fetchEventById(id, listOf(url))
            assertEquals(1, f.requests.size)
            assertSame(originalMonitor, monitors[id])
            f.pool.fetchEventById(id, listOf(url), bypassDedup = true)
            assertEquals(2, f.requests.size)
            assertSame(originalMonitor, monitors[id])
        }
    }

    @Test fun `negative cached ordinary lookup stays suppressed but timeline repair delivers signed event`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://repair-ref.test"
            f.processor.start()
            val crypto = ZapCryptoTestFixture()
            val event = crypto.signedEvent(crypto.sender, 1, emptyList(), "reference repair control")
            f.remote[url] = listOf(event)
            prepare(f, url); f.held.clear()
            f.pool.markEventUnresolved(event.id)
            f.pool.fetchEventById(event.id, listOf(url))
            f.pool.fetchEventById(event.id, listOf(url), bypassDedup = true)
            assertTrue(f.requests.isEmpty())
            assertTrue((field(f.pool, "eventFetchInFlight") as Map<*, *>).isEmpty())
            withTimeout(3_000) { f.pool.fetchTimelineEventsByIds(listOf(event.id), listOf(url)) }
            assertEquals(event.content, f.store.getNostrEvent(event.id)?.content)
            assertEquals(1, f.requests.size)
            assertFalse(f.pool.isEventUnresolved(event.id))
        }
    }

    @Test fun `held ephemeral reference chunks are tracked until completion or cancellation`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://tracked-ephemeral.test"
            f.held += url
            for ((pass, cancel) in listOf(false, true).withIndex()) {
                val batches = (0..1).map { group ->
                    EventIdFetchBatch((1..35).map { (pass * 100 + group * 35 + it).toString(16).padStart(64, '0') }, listOf(url))
                }
                val baseline = f.pool.activeOneShotCount()
                val window = async { referenceWindow(f, batches) }
                try {
                    f.until { f.requests.size == (pass + 1) * 2 }
                    assertEquals(baseline + 2, f.pool.activeOneShotCount())
                    if (cancel) window.cancelAndJoin() else {
                        f.requests.takeLast(2).forEach { (_, frame) ->
                            f.sockets.getValue(url).send("""["EOSE",${frame[1]}]""")
                        }
                        withTimeout(2_000) { window.await() }
                    }
                    assertEquals(baseline, f.pool.activeOneShotCount())
                    assertTrue(f.pool.oneShotEoseCallbacks.isEmpty())
                } finally { window.cancelAndJoin() }
            }
        }
    }

    @Test fun `reference completion waits for all chunks on its relay`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://chunk-completion.test"
            prepare(f, url)
            val batches = (0..2).map { group ->
                EventIdFetchBatch((1..40).map { (group * 40 + it).toString(16).padStart(64, '0') }, listOf(url))
            }
            val window = async { referenceWindow(f, batches) }
            try {
                f.until { f.requests.size == 3 }
                val subIds = f.requests.map { it.second[1].jsonPrimitive.content }
                subIds.take(2).forEach { release(f, url, it) }
                delay(350)
                assertFalse(window.isCompleted)
                release(f, url, subIds.last())
                withTimeout(2_000) { window.await() }
                assertTrue(f.pool.oneShotEoseCallbacks.isEmpty())
            } finally { window.cancel() }
        }
    }

    @Test fun `empty hint miss keeps the scheduled single bridge attempt`() = runBlocking {
        Fixture(bridgeLoopback = true).use { f ->
            val url = "wss://broadcast-miss.test"
            prepare(f, url)
            val id = "a".repeat(64)
            withTimeout(1_000) { f.pool.fetchEventById(id, emptyList()) }
            f.until { f.requests.any { isBridgeFallbackRelay(it.first) } }
            val bridgeRequests = f.requests.filter { isBridgeFallbackRelay(it.first) }
            assertEquals(1, bridgeRequests.size)
            assertEquals(listOf(id), bridgeRequests.single().second[2].jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(setOf(id), (field(f.pool, "bridgeEventFetchAttempted") as Map<*, *>).keys)
            assertEquals(1, (field(f.pool, "eventFetchInFlight") as Map<*, *>).size)
        }
    }

    @Test fun `empty hints retain immediate broadcast completion without awaiting relay EOSE`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://empty-ref.test"
            prepare(f, url)
            withTimeout(1_000) { f.pool.fetchEventById("a".repeat(64), emptyList()) }
            f.until { f.requests.size == 1 }
            assertTrue(f.requests.single().second[1].jsonPrimitive.content.startsWith("batch-events-"))
            assertTrue(f.pool.oneShotEoseCallbacks.isEmpty())
            assertEquals(1, (field(f.pool, "eventFetchInFlight") as Map<*, *>).size)
        }
    }

    @Test fun `cancelling queued profile chunks spends available token only on unrelated work`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://profile-cancel-token.test"
            exhaust(f, url)
            val phase = async { profilePhase(f, url, (1..201).map { it.toString(16).padStart(64, '0') }) }
            f.until { queued(f, url).size == 3 }
            send(f, url, "hint-batch-unrelated")
            tokens(f, url).set(1)
            phase.cancelAndJoin()
            f.until { f.requests.size == 6 }
            assertEquals("hint-batch-unrelated", f.requests.last().second[1].jsonPrimitive.content)
            assertTrue(queued(f, url).isEmpty())
            assertTrue(f.pool.oneShotEoseCallbacks.keys.none { it.startsWith("hint-profiles-") })
        }
    }

    @Test fun `cancelling inflight profile chunk cannot flush its queued siblings`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://profile-cancel-mixed.test"
            exhaust(f, url)
            tokens(f, url).set(1)
            val phase = async { profilePhase(f, url, (1..201).map { it.toString(16).padStart(64, '0') }) }
            f.until { f.requests.size == 6 && queued(f, url).size == 2 }
            tokens(f, url).set(1)
            phase.cancelAndJoin()
            assertEquals("cancelled sibling must not spend a token", 1, tokens(f, url).get())
            assertTrue(queued(f, url).isEmpty())
            assertTrue(f.pool.oneShotEoseCallbacks.keys.none { it.startsWith("hint-profiles-") })
            assertEquals("only the original five plus one started chunk", 6, f.requests.size)
            assertEquals("started chunk releases its slot", 5, count(f, url).get())
        }
    }

    @Test fun `profile phase awaits every chunk and preserves metadata kinds`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://profile-phase.test"
            prepare(f, url)
            val authors = (1..201).map { it.toString(16).padStart(64, '0') }
            val phase = async { profilePhase(f, url, authors) }
            f.until { f.requests.size == 3 }
            f.requests.forEach { (_, req) ->
                assertEquals(listOf(0, 10002), req[2].jsonObject.getValue("kinds").jsonArray.map { it.jsonPrimitive.int })
                assertTrue(req[2].jsonObject.getValue("authors").jsonArray.size <= 100)
            }
            val ids = f.requests.map { it.second[1].jsonPrimitive.content }
            release(f, url, ids[0]); release(f, url, ids[1])
            assertTrue("remaining chunk still owns the phase", phase.isActive)
            release(f, url, ids[2])
            withTimeout(2_000) { phase.await() }
            assertTrue(ids.none(f.pool.oneShotEoseCallbacks::containsKey))
        }
    }

    @Test fun `cancelled profile phase removes all queued chunks and callbacks`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://profile-cancel.test"
            exhaust(f, url)
            val phase = async { profilePhase(f, url, (1..201).map { it.toString(16).padStart(64, '0') }) }
            f.until { queued(f, url).size == 3 }
            phase.cancelAndJoin()
            assertTrue(queued(f, url).isEmpty())
            assertTrue(f.pool.oneShotEoseCallbacks.keys.none { it.startsWith("hint-profiles-") })
            assertEquals(5, f.requests.size)
        }
    }

    @Test fun `profile hints sharing an unpooled relay send all authors once on one socket`() = runBlocking {
        Fixture().use { f ->
            val shared = "wss://shared.test"
            val a = "a".repeat(64)
            val b = "b".repeat(64)
            f.pool.fetchProfilesFromHints(mapOf(
                a to listOf(shared, "wss://a.test"),
                b to listOf(shared, "wss://b.test"),
            ))
            f.until { f.requests.any { it.first == shared } }
            val sharedReq = f.requests.first { it.first == shared }.second
            assertEquals(setOf(a, b), sharedReq[2].jsonObject.getValue("authors").jsonArray.map { it.jsonPrimitive.content }.toSet())
            f.until { f.requests.map { it.first }.toSet().size == 3 && f.active.get() == 0 }
            assertEquals(3, f.requests.size)
            assertEquals(listOf(0), sharedReq[2].jsonObject.getValue("kinds").jsonArray.map { it.jsonPrimitive.int })
            val pairs = f.requests.flatMap { (url, req) -> req[2].jsonObject.getValue("authors").jsonArray.map { it.jsonPrimitive.content to url } }
            assertEquals(setOf(a to shared, a to "wss://a.test", b to shared, b to "wss://b.test"), pairs.toSet())
            assertEquals(4, pairs.size)
        }
    }

    @Test fun `large hinted profile pass preserves every author in bounded requests on one ephemeral socket`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://large-profile.test"
            val authors = (1..201).map { it.toString(16).padStart(64, '0') }
            f.pool.fetchProfilesFromHints(authors.associateWith { listOf(url) })
            f.until { f.requests.isNotEmpty() }
            assertTrue("existing 100-author batch bound applies to each wire filter",
                f.requests.all { it.second[2].jsonObject.getValue("authors").jsonArray.size <= 100 })
            f.until { f.requests.size == 3 && f.active.get() == 0 }
            assertEquals(listOf(100, 100, 1), f.requests.map { it.second[2].jsonObject.getValue("authors").jsonArray.size })
            assertEquals(authors, f.requests.flatMap { it.second[2].jsonObject.getValue("authors").jsonArray.map { value -> value.jsonPrimitive.content } })
            assertEquals(3, f.requests.map { it.second[1].jsonPrimitive.content }.distinct().size)
            assertEquals(1, f.peak.get())
        }
    }

    @Test fun `EOSE drain cannot spend tokens that queued requests never acquired`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pacing.test"
            prepare(f, url)
            send(f, url, "hint-batch-0")
            freezeRefill(f, url)
            repeat(7) { send(f, url, "hint-batch-${it + 1}") }
            f.until { f.requests.size >= 5 }
            assertEquals(5, f.requests.size)
            release(f, url, "hint-batch-0")
            delay(150) // Reader has time to observe any incorrectly drained requests.
            assertEquals("EOSE must not bypass the exhausted token bucket", 5, f.requests.size)
        }
    }

    @Test fun `token refill wakes queued work with no remaining in flight request`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pacing.test"
            prepare(f, url)
            send(f, url, "hint-batch-0")
            freezeRefill(f, url)
            repeat(4) { send(f, url, "hint-batch-${it + 1}") }
            f.until { f.requests.size == 5 }
            repeat(5) { release(f, url, "hint-batch-$it") }
            (field(limiter(f, url), "lastRefill") as java.util.concurrent.atomic.AtomicLong).set((System.nanoTime() / 1_000_000))
            send(f, url, "hint-batch-later")
            withTimeout(2_500) { f.until { f.requests.size == 6 } }
            assertEquals("hint-batch-later", f.requests.last().second[1].jsonPrimitive.content)
        }
    }

    @Test fun `cooldown end wakes queued work without another EOSE`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pacing.test"
            prepare(f, url)
            send(f, url, "hint-batch-first")
            f.until { f.requests.size == 1 }
            release(f, url, "hint-batch-first")
            (field(limiter(f, url), "cooldownUntil") as java.util.concurrent.atomic.AtomicLong).set((System.nanoTime() / 1_000_000) + 250)
            send(f, url, "hint-batch-after-cooldown")
            withTimeout(2_000) { f.until { f.requests.size == 2 } }
        }
    }

    private fun drain(f: Fixture, url: String) {
        RelayPool::class.java.getDeclaredMethod("flushRelayQueue", RelayConnection::class.java)
            .apply { isAccessible = true }.invoke(f.pool, connection(f, url))
    }
    private fun tokens(f: Fixture, url: String) = field(limiter(f, url), "tokens") as AtomicInteger
    private fun count(f: Fixture, url: String) = (field(f.pool, "relayOneShotCount") as Map<*, *>)[url] as AtomicInteger
    private fun queued(f: Fixture, url: String) =
        ((field(f.pool, "relayReqQueue") as Map<*, *>)[url] as? Collection<*>)?.toList().orEmpty()
    private fun wakes(f: Fixture) = field(f.pool, "relayQueueWakes") as Map<*, *>
    private suspend fun exhaust(f: Fixture, url: String) {
        prepare(f, url)
        send(f, url, "hint-batch-0")
        freezeRefill(f, url)
        repeat(4) { send(f, url, "hint-batch-${it + 1}") }
        f.until { f.requests.count { it.first == url } == 5 }
    }

    @Test fun `refilled token goes to older eligible work before a new arrival`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pacing.test"
            exhaust(f, url)
            send(f, url, "hint-batch-older")
            tokens(f, url).set(1)
            send(f, url, "hint-batch-newer")
            f.until { f.requests.size == 6 }
            assertEquals("hint-batch-older", f.requests.last().second[1].jsonPrimitive.content)
            assertEquals(1, queued(f, url).size)
            tokens(f, url).set(1)
            drain(f, url)
            f.until { f.requests.size == 7 }
            assertEquals("hint-batch-newer", f.requests.last().second[1].jsonPrimitive.content)
            assertTrue(wakes(f).isEmpty())
        }
    }

    @Test fun `queued prefetch respects reserved slots without blocking foreground work`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pacing.test"
            prepare(f, url)
            send(f, url, "hint-batch-seed")
            freezeRefill(f, url)
            f.until { f.requests.size == 1 }
            count(f, url).set(8) // Eight held one-shots already occupy the prefetch allowance.
            send(f, url, "prefetch-waiting")
            send(f, url, "hint-batch-foreground")
            f.until { f.requests.size == 2 }
            assertEquals("hint-batch-foreground", f.requests.last().second[1].jsonPrimitive.content)
            drain(f, url)
            assertEquals(1, queued(f, url).size)
            assertEquals(9, count(f, url).get())
            assertEquals(3, tokens(f, url).get()) // No debit for cap-blocked prefetch.
            count(f, url).set(7)
            drain(f, url)
            f.until { f.requests.size == 3 }
            assertEquals("prefetch-waiting", f.requests.last().second[1].jsonPrimitive.content)
            assertEquals(8, count(f, url).get())
        }
    }

    @Test fun `capability skip purges queued work without consuming a token`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pacing.test"
            exhaust(f, url)
            send(f, url, "hint-batch-skipped")
            f.capabilities.markStructurallyInvalid(url)
            tokens(f, url).set(1)
            drain(f, url)
            assertTrue(queued(f, url).isEmpty())
            assertTrue(wakes(f).isEmpty())
            assertEquals(1, tokens(f, url).get())
            assertEquals(5, f.requests.size)
        }
    }

    @Test fun `unowned queued lookup expires using existing lookup lifetime even at full cap`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pacing.test"
            exhaust(f, url)
            count(f, url).set(10)
            send(f, url, "hint-batch-expired")
            val entry = queued(f, url).single()!!
            entry.javaClass.getDeclaredField("queuedAtNanos").apply { isAccessible = true }
                .setLong(entry, System.nanoTime() - 31_000_000_000L)
            tokens(f, url).set(1)
            drain(f, url)
            assertTrue(queued(f, url).isEmpty())
            assertTrue(wakes(f).isEmpty())
            assertEquals(1, tokens(f, url).get())
            assertEquals(5, f.requests.size)
        }
    }

    @Test fun `owned cancellation removes queue and wake without spending token or slot`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pacing.test"
            exhaust(f, url)
            val id = "eng-cancelled"
            val pending = async { f.pool.fetchOneShotOutcomes(id, listOf(url), """["REQ","$id",{"kinds":[1]}]""", 5_000) }
            f.until { queued(f, url).size == 1 }
            val entry = queued(f, url).single()!!
            entry.javaClass.getDeclaredField("queuedAtNanos").apply { isAccessible = true }
                .setLong(entry, System.nanoTime() - 31_000_000_000L)
            drain(f, url)
            assertEquals("Owner cleanup, not the legacy age ceiling, controls its request", 1, queued(f, url).size)
            pending.cancelAndJoin()
            assertTrue(queued(f, url).isEmpty())
            assertTrue(wakes(f).isEmpty())
            assertEquals(5, count(f, url).get())
            assertEquals(0, tokens(f, url).get())
            tokens(f, url).set(1)
            drain(f, url)
            assertEquals(5, f.requests.size)
        }
    }

    @Test fun `one relay waiting for tokens does not delay another relay`() = runBlocking {
        Fixture().use { f ->
            val slow = "wss://pacing.test"
            val ready = "wss://other.test"
            exhaust(f, slow)
            send(f, slow, "hint-batch-waiting")
            prepare(f, ready)
            send(f, ready, "hint-batch-independent")
            f.until { f.requests.any { it.first == ready } }
            assertEquals(1, queued(f, slow).size)
            assertEquals(5, f.requests.count { it.first == slow })
        }
    }

    private fun sendDirect(f: Fixture, url: String, id: String) {
        RelayPool::class.java.getDeclaredMethod(
            "sendOneShotToRelay", RelayConnection::class.java, String::class.java,
            RelayRequestClass::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(
            f.pool, connection(f, url), """["REQ","$id",{"kinds":[1],"limit":1}]""",
            RelayRequestClass.GENERAL, false,
        )
    }

    /** Hold the real resume interval before reconnectAll's asynchronous replacements. */
    private fun <T> beforeReplacement(f: Fixture, action: () -> T): T = synchronized(field(f.pool, "socketLifecycleLock")) {
        f.pool.suspendSocketsForBackground()
        val suspended = field(f.pool, "socketTransportSuspended") as AtomicBoolean
        suspended.set(false)
        try { action() } finally { suspended.set(true) }
    }

    @Test fun `resumed closed mapped socket cannot spend queued tokens before replacement`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://resume-queue.test"
            exhaust(f, url)
            send(f, url, "hint-batch-stale")
            f.pool.addPurpose(url, ConnectionPurpose.PERSISTENT)
            val old = connection(f, url)
            beforeReplacement(f) {
                assertSame(old, connection(f, url))
                assertEquals(RelayState.DISCONNECTED, old.state.value)
                tokens(f, url).set(2)
                val originalQueue = queued(f, url)
                drain(f, url)
                assertEquals("closed transport must not consume a token", 2, tokens(f, url).get())
                assertEquals(originalQueue, queued(f, url))
                assertEquals(5, count(f, url).get())
                assertEquals(5, f.requests.size)
                assertTrue("legacy expiry still has a wake", wakes(f).containsKey(url))
            }
            f.pool.resumeSocketsForForeground()
            f.until { connection(f, url) !== old && connection(f, url).isConnected }
            assertTrue(queued(f, url).isEmpty())
            tokens(f, url).set(1)
            send(f, url, "hint-batch-replacement")
            f.until { f.requests.size == 6 }
            assertEquals("hint-batch-replacement", f.requests.last().second[1].jsonPrimitive.content)
            assertFalse(f.requests.any { it.second[1].jsonPrimitive.content == "hint-batch-stale" })
        }
    }

    @Test fun `terminal direct lookup is skipped without tokens slots or queue`() = runBlocking {
        for (terminal in listOf(RelayState.DISCONNECTED, RelayState.FAILED)) {
            Fixture().use { f ->
                val url = "wss://terminal-direct.test"
                exhaust(f, url)
                val id = "hint-batch-terminal"
                val outcomes = (field(f.pool, "oneShotOutcomes") as OneShotOutcomes).fetch(
                    id, listOf(url), 1_000,
                    dispatch = {
                        beforeReplacement(f) {
                            // The socket is really closed; only its terminal classification differs.
                            @Suppress("UNCHECKED_CAST")
                            (field(connection(f, url), "_state") as MutableStateFlow<RelayState>).value = terminal
                            tokens(f, url).set(2)
                            sendDirect(f, url, id)
                            assertEquals(2, tokens(f, url).get())
                            assertEquals(5, count(f, url).get())
                            assertTrue(queued(f, url).isEmpty())
                            assertNull(connection(f, url).requests.request(id))
                        }
                        setOf(url)
                    }, cleanup = {},
                )
                assertEquals(mapOf(url to OneShotOutcome.SKIPPED), outcomes)
                assertEquals(5, f.requests.size)
            }
        }
    }

    @Test fun `terminal queue still expires legacy requests without spending tokens`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://terminal-expiry.test"
            exhaust(f, url)
            send(f, url, "hint-batch-expired")
            val entry = queued(f, url).single()!!
            entry.javaClass.getDeclaredField("queuedAtNanos").apply { isAccessible = true }
                .setLong(entry, System.nanoTime() - 31_000_000_000L)
            beforeReplacement(f) {
                tokens(f, url).set(1)
                drain(f, url)
                assertTrue(queued(f, url).isEmpty())
                assertTrue(wakes(f).isEmpty())
                assertEquals(1, tokens(f, url).get())
            }
            assertEquals(5, f.requests.size)
        }
    }

    @Test fun `connecting transport preserves direct and drained pre handshake requests`() = runBlocking {
        val handshake = CountDownLatch(1)
        Fixture(handshakeGate = handshake).use { f ->
            val url = "wss://connecting-queue.test"
            f.held += url
            try {
                f.pool.connect(listOf(url))
                assertEquals(RelayState.CONNECTING, connection(f, url).state.value)
                sendDirect(f, url, "hint-batch-direct")
                freezeRefill(f, url)
                tokens(f, url).set(0)
                sendDirect(f, url, "hint-batch-queued")
                assertEquals(1, queued(f, url).size)
                tokens(f, url).set(1)
                drain(f, url)
                assertTrue(queued(f, url).isEmpty())
                assertEquals(2, count(f, url).get())
                assertEquals(0, tokens(f, url).get())
                assertNotNull(connection(f, url).requests.request("hint-batch-direct"))
                assertNotNull(connection(f, url).requests.request("hint-batch-queued"))
                assertTrue(f.requests.isEmpty())
                handshake.countDown()
                f.until { f.requests.size == 2 }
                assertEquals(listOf("hint-batch-direct", "hint-batch-queued"), f.requests.map { it.second[1].jsonPrimitive.content })
            } finally { handshake.countDown() }
        }
    }

    @Test fun `background cancels wake and resumed replacement drops old queued work`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pacing.test"
            exhaust(f, url)
            send(f, url, "hint-batch-stale")
            val old = connection(f, url)
            val wake = field(wakes(f)[url]!!, "job") as Job
            f.pool.suspendSocketsForBackground()
            assertTrue(wake.isCancelled)
            assertTrue(wakes(f).isEmpty())
            delay(1_100)
            assertEquals(5, f.requests.size)
            f.pool.resumeSocketsForForeground()
            f.until { connection(f, url) !== old && connection(f, url).isConnected }
            assertTrue(queued(f, url).isEmpty())
            tokens(f, url).set(1)
            send(f, url, "hint-batch-current")
            f.until { f.requests.size == 6 }
            assertEquals("hint-batch-current", f.requests.last().second[1].jsonPrimitive.content)
            assertFalse(f.requests.any { it.second[1].jsonPrimitive.content == "hint-batch-stale" })
        }
    }

    @Test fun `disconnect all releases pending wake and queue`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pacing.test"
            exhaust(f, url)
            send(f, url, "hint-batch-stale")
            val wake = field(wakes(f)[url]!!, "job") as Job
            f.pool.disconnectAll()
            assertTrue(wake.isCancelled)
            assertTrue(wakes(f).isEmpty())
            assertTrue((field(f.pool, "relayReqQueue") as Map<*, *>).isEmpty())
        }
    }

    @Test fun `failed queued send does not create a token retry loop`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pacing.test"
            exhaust(f, url)
            send(f, url, "hint-batch-failing")
            val conn = connection(f, url)
            val wsField = conn.javaClass.getDeclaredField("ws").apply { isAccessible = true }
            val original = wsField.get(conn) as WebSocket
            val attempts = AtomicInteger()
            wsField.set(conn, object : WebSocket by original {
                override fun send(text: String): Boolean {
                    if (text.startsWith("[\"REQ\"")) { attempts.incrementAndGet(); return false }
                    return original.send(text)
                }
            })
            tokens(f, url).set(2)
            drain(f, url)
            assertEquals(1, attempts.get())
            assertEquals(5, count(f, url).get())
            assertEquals(1, queued(f, url).size)
            val due = field(wakes(f)[url]!!, "dueAtNanos") as Long
            assertTrue("Only the legacy expiry wake remains", due - System.nanoTime() > 20_000_000_000L)
            delay(1_100)
            assertEquals(1, attempts.get())
            assertEquals(1, tokens(f, url).get())
        }
    }

    @Test fun `cooldown wake does not wait for refill when tokens remain`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pacing.test"
            prepare(f, url)
            send(f, url, "hint-batch-first")
            freezeRefill(f, url)
            (field(limiter(f, url), "cooldownUntil") as java.util.concurrent.atomic.AtomicLong).set((System.nanoTime() / 1_000_000) + 250)
            val wait = RelayPool::class.java.getDeclaredMethod("nextRelayTokenDelayMs", String::class.java)
                .apply { isAccessible = true }.invoke(f.pool, url) as Long
            assertTrue("Available tokens need only wait for cooldown", wait in 1..250)
        }
    }

    @Test fun `limiter refill and cooldown use elapsed time independent of wall clock`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://pacing.test"
            prepare(f, url)
            val before = System.nanoTime() / 1_000_000
            send(f, url, "hint-batch-first")
            val refill = (field(limiter(f, url), "lastRefill") as java.util.concurrent.atomic.AtomicLong).get()
            assertTrue("Refill must use elapsed time, not adjustable wall time", refill in before..System.nanoTime() / 1_000_000)
            RelayPool::class.java.getDeclaredMethod("markRelayRateLimited", String::class.java)
                .apply { isAccessible = true }.invoke(f.pool, url)
            val cooldown = (field(limiter(f, url), "cooldownUntil") as java.util.concurrent.atomic.AtomicLong).get()
            assertTrue(cooldown in (before + 30_000)..(System.nanoTime() / 1_000_000 + 30_000))
            assertTrue(f.pool.isRateLimited(url))
        }
    }
}
