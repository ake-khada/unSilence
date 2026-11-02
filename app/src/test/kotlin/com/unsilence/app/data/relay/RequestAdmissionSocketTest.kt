package com.unsilence.app.data.relay

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.auth.SignatureVerifier
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Real loopback sockets and production thread payloads; no external relay/account. */
class RequestAdmissionSocketTest {
    @Test fun `thread one shot validates pooled and standalone socket deliveries`() = runBlocking {
        val crypto = ZapCryptoTestFixture()
        val root = "a".repeat(64)
        val reply = crypto.signedEvent(crypto.sender, 1, listOf(listOf("e", root)), "reply")
        val unrelated = crypto.signedEvent(crypto.sender, 1, listOf(listOf("e", "b".repeat(64))), "unrelated")
        for (pooled in listOf(true, false)) {
            val server = MockWebServer()
            val client = OkHttpClient()
            val request = threadRequestPayloads(root, "fixture")[1]
            val subId = extractSubscriptionIdFromRaw(request)!!
            val closed = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text == request) {
                        webSocket.send("""["EVENT","$subId",${NostrJson.encodeToString(unrelated)}]""")
                        webSocket.send("""["EVENT","$subId",${NostrJson.encodeToString(reply)}]""")
                        webSocket.send("""["EOSE","$subId"]""")
                    }
                }
            }))
            server.start()
            val url = server.url("/").toString().replaceFirst("http:", "ws:")
            val registry = RelayConnectionRegistry(Any()) { RelayConnection(it, client) }
            val connection = if (pooled) registry.acquire(url, { true }, { true })!!.connection
                else RelayConnection(url, client).also { it.connect() }
            val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
            val processor = EventProcessor(store, SignatureVerifier())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            processor.setTestScope(scope)
            try {
                connection.awaitConnected()
                assertTrue(connection.send(request))
                val results = withTimeout(5_000) {
                    List(3) { processor.process(connection.messages.receive(), connection) }
                }
                assertEquals(listOf(false, true, true), results)
                assertEquals(pooled, registry.connections[url] === connection)
                processor.drainForTest()
                assertNull(store.getNostrEvent(unrelated.id))
                assertNotNull(store.getNostrEvent(reply.id))
                assertTrue(connection.send("""["CLOSE","$subId"]"""))
                assertFalse(processor.process("""["EVENT","$subId",${NostrJson.encodeToString(reply)}]""", connection))
            } finally {
                connection.close()
                withTimeout(5_000) { closed.await() }
                processor.stop()
                scope.cancel()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
                server.shutdown()
            }
        }
    }
}
