package com.unsilence.app.data.relay

import android.content.ContentResolver
import android.content.ContextWrapper
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.auth.SignatureVerifier
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import dagger.Lazy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real RelayPool ephemeral dispatch and admission, with all HTTP confined to loopback. */
class ThreadInboxSocketTest {
    @Test fun `unpooled inbox outside Following allowlist delivers only matching replies and releases socket`() = runBlocking { inboxCase("success") }
    @Test fun `blocked inbox never opens a socket`() = runBlocking { inboxCase("blocked") }
    @Test fun `background suspension prevents inbox socket creation`() = runBlocking { inboxCase("suspended") }
    @Test fun `leaving thread cancels the pending ephemeral reply and closes its socket`() = runBlocking { inboxCase("cancel") }
    @Test fun `CLOSED releases the socket without certifying reply completion`() = runBlocking { inboxCase("closed") }

    private suspend fun inboxCase(mode: String) = coroutineScope {
        val crypto = ZapCryptoTestFixture()
        val root = "a".repeat(64)
        val reply = crypto.signedEvent(crypto.sender, 1, listOf(listOf("e", root)), "reply")
        val unrelated = crypto.signedEvent(crypto.sender, 1, listOf(listOf("e", "b".repeat(64))), "unrelated")
        val url = "wss://inbox.test"
        val received = CopyOnWriteArrayList<String>()
        val closed = CompletableDeferred<Unit>()
        val requested = CompletableDeferred<Unit>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                val arr = NostrJson.parseToJsonElement(text).jsonArray
                if (arr[0].jsonPrimitive.content == "REQ") {
                    val sub = arr[1].jsonPrimitive.content
                    requested.complete(Unit)
                    if (mode == "cancel") return
                    if (mode == "closed") {
                        webSocket.send("""["CLOSED","$sub","blocked: test fixture"]""")
                        return
                    }
                    webSocket.send("""["EVENT","$sub",${NostrJson.encodeToString(unrelated)}]""")
                    webSocket.send("""["EVENT","$sub",${NostrJson.encodeToString(reply)}]""")
                    webSocket.send("""["EOSE","$sub"]""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
        }))
        server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.host == "inbox.test") { "Unexpected network destination" }
            chain.proceed(chain.request().newBuilder().url(server.url("/")).build())
        }.build()
        val context = object : ContextWrapper(null) {}
        val monitor = NetworkMonitor(context)
        val capabilities = RelayCapabilitiesStore(context, monitor)
        val keys = KeyManager(context)
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        processor.setTestScope(processingScope)
        val pool = RelayPool(
            RelayConnectionFactory(client, capabilities, RelayTrafficMetrics()), processor,
            Lazy { error("Thread reply must not load preferences or AUTH") },
            SigningManager(keys, object : ContentResolver(context) {}), keys,
            Lazy { store }, capabilities,
            Lazy { object : ActiveSubsSource {
                override fun activeRelayUrls() = emptySet<String>()
                override fun activeSubIds() = emptySet<String>()
            } }, monitor, Nip11Fetcher(client, Lazy { store }),
        )
        try {
            pool.setOutboxAllowlist(setOf("wss://following.test"))
            pool.activeSingleRelayFeedUrl = url
            if (mode == "blocked") pool.onBlockedRelaysChanged(setOf(url))
            if (mode == "suspended") pool.suspendSocketsForBackground()
            val completions = CopyOnWriteArrayList<Pair<String, String>>()
            val fetch = launch { pool.fetchThread(listOf(url), root) { query, relay -> completions += query to relay } }
            withTimeout(5_000) {
                if (mode == "cancel") { requested.await(); fetch.cancel() }
                fetch.join()
            }
            if (mode == "blocked" || mode == "suspended") {
                assertEquals(0, server.requestCount)
                assertTrue(received.isEmpty())
                assertTrue(completions.isEmpty())
                return@coroutineScope
            }
            assertTrue("unpooled inbox never received the reply REQ", received.any { it.startsWith("[\"REQ\",\"thread-replies-") })
            withTimeout(5_000) { closed.await() }
            assertTrue("reply lookup must not retain a pooled connection", pool.connectionStates.first().isEmpty())
            processor.drainForTest()
            if (mode == "closed" || mode == "cancel") {
                assertTrue(completions.isEmpty())
                assertNull(store.getNostrEvent(reply.id))
                return@coroutineScope
            }
            assertNull(store.getNostrEvent(unrelated.id))
            assertNotNull(store.getNostrEvent(reply.id))
            val rows = withTimeout(5_000) { store.threadFeedRowFlow(root).first { rows -> rows.any { it.id == reply.id } } }
            assertTrue(rows.any { it.id == reply.id })
            assertEquals(listOf("thread-replies" to url), completions.toList())
            assertEquals(1, received.count { it.startsWith("[\"REQ\"") })
        } finally {
            pool.disconnectAll()
            cancelScope(pool, "scope")
            cancelScope(capabilities, "persistenceScope")
            processor.stop()
            processingScope.cancel()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    private fun cancelScope(owner: Any, name: String) {
        val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        (field.get(owner) as CoroutineScope).cancel()
    }
}
