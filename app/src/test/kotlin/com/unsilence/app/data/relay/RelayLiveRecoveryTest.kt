package com.unsilence.app.data.relay

import android.content.ContentResolver
import android.content.ContextWrapper
import com.unsilence.app.data.auth.*
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import dagger.Lazy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.Dispatcher as ServerDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real loopback CLOSED frames, production pool and Subscription replay. */
class RelayLiveRecoveryTest {
    private class Fixture(
        val beforeReplayHandoff: (() -> Unit)? = null,
        val afterReplayHandoff: (() -> Unit)? = null,
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Pair<String, JsonArray>>()
        val closes = CopyOnWriteArrayList<Pair<String, String>>()
        val sockets = ConcurrentHashMap<String, WebSocket>()
        val server = MockWebServer().apply {
            dispatcher = object : ServerDispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val relay = requireNotNull(request.getHeader("X-Fixture-Relay"))
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            sockets[relay] = webSocket
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val arr = NostrJson.parseToJsonElement(text).jsonArray
                            val id = arr[1].jsonPrimitive.content
                            when (arr[0].jsonPrimitive.content) {
                                "REQ" -> {
                                    requests += relay to arr
                                }
                                "CLOSE" -> closes += relay to id
                            }
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
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
            Lazy {
                object : ActiveSubsSource by subscription {
                    override fun onRateLimitedClosed(url: String, subId: String) {
                        beforeReplayHandoff?.invoke()
                        subscription.onRateLimitedClosed(url, subId)
                        afterReplayHandoff?.invoke()
                    }
                }
            }, monitor, Nip11Fetcher(client, Lazy { store }),
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

    private fun cooldown(f: Fixture, url: String, durationMs: Long) {
        RelayPool::class.java.getDeclaredMethod("markRelayRateLimited", String::class.java)
            .apply { isAccessible = true }.invoke(f.pool, url)
        val limiter = (field(f.pool, "rateLimiters") as Map<*, *>)[url]!!
        (field(limiter, "cooldownUntil") as java.util.concurrent.atomic.AtomicLong)
            .set(System.nanoTime() / 1_000_000 + durationMs)
    }

    private suspend fun subscribe(f: Fixture, url: String, provider: ((String) -> NostrFilter)? = null): Subscription.Handle {
        val handle = f.subscription.subscribe(listOf(url), NostrFilter(kinds = listOf(1), limit = 20), {},
            replayFilterProvider = provider)
        f.until { f.requests.any { it.first == url } }
        return handle
    }
    private fun id(f: Fixture, url: String) = f.requests.last { it.first == url }.second[1].jsonPrimitive.content
    private fun closed(f: Fixture, url: String, id: String, reason: String = "rate-limited: slow down") {
        check(f.sockets.getValue(url).send("""["CLOSED","$id","$reason"]"""))
    }

    @Test fun `rate limited live request replays after cooldown with latest filter and same ID`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://live.test"
            var head = 100L
            val evaluated = CopyOnWriteArrayList<String>()
            subscribe(f, url) { evaluated += it; NostrFilter(kinds = listOf(1), limit = 20, since = head) }
            val original = f.requests.single().second
            cooldown(f, url, 500)
            closed(f, url, id(f, url))
            f.until { connection(f, url).requests.request(id(f, url)) == null }
            delay(100)
            assertEquals(1, f.requests.size)
            assertTrue(evaluated.isEmpty())
            head = 200L
            withTimeout(3_000) { f.until { f.requests.size == 2 } }
            val replay = f.requests.last().second
            assertEquals(original[1], replay[1])
            assertEquals(NostrFilter(kinds = listOf(1), limit = 20, since = 200).toWireJsonObject(), replay[2])
            assertEquals(listOf(url), evaluated)
            delay(150)
            assertEquals(2, f.requests.size)
        }
    }

    @Test fun `multiple live refusals coalesce into one relay replay cycle`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://coalesce.test"
            subscribe(f, url); subscribe(f, url)
            f.until { f.requests.size == 2 }
            cooldown(f, url, 400)
            f.requests.toList().forEach { closed(f, url, it.second[1].jsonPrimitive.content) }
            withTimeout(3_000) { f.until { f.requests.size == 4 } }
            delay(250)
            assertEquals(4, f.requests.size)
            assertTrue(f.requests.groupBy { it.second[1] }.values.all { it.size == 2 })
        }
    }

    @Test fun `disposed live subscription is not revived at cooldown end`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://dispose.test"
            val handle = subscribe(f, url)
            val id = id(f, url)
            cooldown(f, url, 300)
            closed(f, url, id)
            f.until { connection(f, url).requests.request(id) == null }
            handle.close()
            delay(600)
            assertEquals(1, f.requests.size)
            assertTrue(f.subscription.activeSubIds().isEmpty())
        }
    }

    @Test fun `one shot rate rejection never replays an unrelated live subscription`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://oneshot.test"
            subscribe(f, url)
            f.pool.sendOneShotBatch(listOf(url), listOf("""["REQ","hint-batch-one",{"kinds":[1],"limit":1}]"""), listOf("hint-batch-one"))
            f.until { f.requests.size == 2 }
            cooldown(f, url, 300)
            closed(f, url, "hint-batch-one")
            delay(600)
            assertEquals(2, f.requests.size)
        }
    }

    @Test fun `background pause cancels wake and replacement recovers after remaining cooldown`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://background.test"
            subscribe(f, url)
            val old = connection(f, url)
            cooldown(f, url, 1_000)
            closed(f, url, id(f, url))
            f.until { old.requests.request(id(f, url)) == null }
            f.subscription.pauseAll()
            f.pool.suspendSocketsForBackground()
            delay(100)
            assertEquals(1, f.requests.size)
            f.subscription.resumeAll() // Cannot send while background transport is suspended.
            f.pool.resumeSocketsForForeground()
            f.until { connection(f, url) !== old && connection(f, url).isConnected }
            withTimeout(4_000) { f.until { f.requests.size == 2 } }
            delay(200)
            assertEquals(2, f.requests.size)
        }
    }

    @Test fun `teardown cancels cooldown replay`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://teardown.test"
            subscribe(f, url)
            cooldown(f, url, 300)
            closed(f, url, id(f, url))
            f.until { connection(f, url).requests.request(id(f, url)) == null }
            f.pool.disconnectAll()
            assertTrue((field(f.subscription, "deferredReplays") as Map<*, *>).isEmpty())
            delay(600)
            assertEquals(1, f.requests.size)
        }
    }
    @Test fun `teardown cancels an already armed cooldown replay`() = runBlocking {
        val armed = CountDownLatch(1)
        Fixture(afterReplayHandoff = { armed.countDown() }).use { f ->
            val url = "wss://armed-teardown.test"
            subscribe(f, url)
            cooldown(f, url, 300)
            closed(f, url, id(f, url))
            assertTrue(withContext(Dispatchers.IO) { armed.await(5, TimeUnit.SECONDS) })
            assertEquals(1, (field(f.subscription, "deferredReplays") as Map<*, *>).size)
            f.pool.disconnectAll()
            assertTrue((field(f.subscription, "deferredReplays") as Map<*, *>).isEmpty())
            delay(600)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun `teardown cannot be overtaken by an admitted cooldown handoff`() = runBlocking {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val returned = CountDownLatch(1)
        Fixture(
            beforeReplayHandoff = {
                entered.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
            },
            afterReplayHandoff = { returned.countDown() },
        ).use { f ->
            val url = "wss://teardown-handoff.test"
            subscribe(f, url)
            cooldown(f, url, 10_000)
            closed(f, url, id(f, url))
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val finished = CompletableDeferred<Unit>()
            val teardown = Thread {
                try {
                    f.pool.disconnectAll()
                    finished.complete(Unit)
                } catch (error: Throwable) { finished.completeExceptionally(error) }
            }.apply { isDaemon = true; start() }
            try {
                // Either teardown completes (the old race), or it blocks on the
                // socket lock held by the admitted handoff (the required ordering).
                f.until { finished.isCompleted || teardown.state == Thread.State.BLOCKED }
                proceed.countDown()
                withTimeout(5_000) { finished.await() }
                assertTrue(withContext(Dispatchers.IO) { returned.await(5, TimeUnit.SECONDS) })
                assertTrue("no timer may be armed after teardown", (field(f.subscription, "deferredReplays") as Map<*, *>).isEmpty())
                assertTrue(f.pool.connectedRelayUrls().isEmpty())
                assertEquals(1, f.requests.size)
            } finally {
                proceed.countDown()
                withContext(Dispatchers.IO) { teardown.join(5_000) }
            }
        }
    }

    @Test fun `real refusal sets deadline before scheduling and emits no generic reconnect`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://deadline.test"
            subscribe(f, url)
            val broadcasts = AtomicInteger()
            val observer = f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.pool.onRelayReconnected.collect { broadcasts.incrementAndGet() }
            }
            assertEquals(0L, f.pool.rateLimitRemainingMs(url))
            closed(f, url, id(f, url))
            f.until { f.pool.isRateLimited(url) }
            delay(150)
            assertTrue(f.pool.rateLimitRemainingMs(url) in 28_000L..30_000L)
            assertEquals(1, f.requests.size)
            assertEquals(0, broadcasts.get())
            assertTrue((field(f.subscription, "deferredReplays") as Map<*, *>).containsKey(url))
            f.subscription.pauseAll()
            assertTrue((field(f.subscription, "deferredReplays") as Map<*, *>).isEmpty())
            observer.cancel()
        }
    }

    @Test fun `wake rechecks extended cooldown and sends only once afterward`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://extended.test"
            subscribe(f, url)
            cooldown(f, url, 300)
            closed(f, url, id(f, url))
            f.until { connection(f, url).requests.request(id(f, url)) == null }
            delay(100)
            cooldown(f, url, 600)
            delay(300)
            assertEquals(1, f.requests.size)
            withTimeout(3_000) { f.until { f.requests.size == 2 } }
            delay(200)
            assertEquals(2, f.requests.size)
        }
    }

    @Test fun `auth and capability gates still prevent deferred replay`() = runBlocking {
        for (gate in listOf("auth", "restricted")) Fixture().use { f ->
            val url = "wss://gated.test"
            subscribe(f, url)
            cooldown(f, url, 300)
            closed(f, url, id(f, url))
            f.until { connection(f, url).requests.request(id(f, url)) == null }
            if (gate == "auth") {
                @Suppress("UNCHECKED_CAST")
                (field(f.pool, "authUnavailableRelays") as MutableSet<String>).add(url)
            } else f.capabilities.learnFromClosed(url, "restricted: fixture")
            delay(650)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun `non replayable subscription is never recovered as live`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://history.test"
            f.subscription.subscribe(listOf(url), NostrFilter(kinds = listOf(1), since = 10, until = 20), {}, replayable = false)
            f.until { f.requests.size == 1 }
            cooldown(f, url, 300)
            closed(f, url, id(f, url))
            delay(600)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun `recovered live stream delivers verified events and only genuine EOSE certifies completion`() = runBlocking {
        Fixture().use { f ->
            val url = "wss://delivery.test"
            val completions = CopyOnWriteArrayList<RelayCompletionReason>()
            val events = CopyOnWriteArrayList<String>()
            val cycles = AtomicInteger()
            f.subscription.subscribe(listOf(url), NostrFilter(kinds = listOf(1)),
                onevent = { events += it.id }, onReplayCycleStarted = { cycles.incrementAndGet() },
                onRelayCompletion = { completions += it.reason })
            f.until { f.requests.size == 1 }
            cooldown(f, url, 300)
            closed(f, url, id(f, url))
            withTimeout(3_000) { f.until { f.requests.size == 2 } }
            assertEquals(listOf(RelayCompletionReason.Closed), completions)
            assertEquals(1, cycles.get())
            val crypto = ZapCryptoTestFixture()
            val event = crypto.signedEvent(crypto.sender, 1, emptyList(), "recovered stream")
            f.sockets.getValue(url).send("""["EVENT","${id(f, url)}",${NostrJson.encodeToString(event)}]""")
            f.sockets.getValue(url).send("""["EOSE","${id(f, url)}"]""")
            f.until { events.size == 1 && RelayCompletionReason.Genuine in completions }
            assertEquals(listOf(event.id), events)
            assertEquals(listOf(RelayCompletionReason.Closed, RelayCompletionReason.Genuine), completions)
        }
    }

}
