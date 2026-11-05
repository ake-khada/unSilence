package com.unsilence.app.data.relay

import android.content.ContextWrapper
import com.unsilence.app.di.AppModule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Real upgrades using the production factory; all traffic stays on loopback. */
class RelayHandshakeTest {
    private class Fixture : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val base = AppModule.provideOkHttpClient()
        private val context = object : ContextWrapper(null) {}
        val capabilities = RelayCapabilitiesStore(context, NetworkMonitor(context))
        val factory = RelayConnectionFactory(base, capabilities, RelayTrafficMetrics())
        private val connections = mutableListOf<RelayConnection>()
        fun connection(): RelayConnection = factory.create(server.url("/").newBuilder().host("127.0.0.1").build().toString()).also {
            connections += it
        }
        override fun close() {
            connections.forEach { it.close() }
            base.dispatcher.cancelAll()
            base.connectionPool.evictAll()
            base.dispatcher.executorService.shutdownNow()
            server.shutdown()
            (RelayCapabilitiesStore::class.java.getDeclaredField("persistenceScope")
                .apply { isAccessible = true }.get(capabilities) as CoroutineScope).cancel()
        }
    }

    @Test fun `native deadline isolates silent healthy and queued sockets`() = runBlocking<Unit> {
        // Independent servers/clients run concurrently to exercise the shipping
        // deadline without paying three consecutive wall-clock waits in the gate.
        listOf(async { silentUpgrade() }, async { healthyIdle() }, async { queuedUpgrade() }).awaitAll()
    }

    private suspend fun silentUpgrade() {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val connection = f.connection()
            connection.connect()
            assertNotNull(f.server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(18_000) { connection.state.first { it == RelayState.FAILED } }
            assertTrue(withTimeout(1_000) { connection.messages.receiveCatching() }.isClosed)
            assertFalse(connection.isConnected)
            withTimeout(1_000) { while (f.capabilities.get(connection.url) == null) delay(10) }
            val health = requireNotNull(f.capabilities.get(connection.url))
            assertEquals(SkipReason.UNKNOWN_FAILURE.name, health.lastReason)
            assertEquals(1, health.strikes)
            assertEquals(1, health.consecutiveFailures)
            assertEquals(0, health.deadFailCount)
        }
    }

    private suspend fun healthyIdle() {
        Fixture().use { f ->
            val socket = CompletableFuture<WebSocket>()
            f.server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket.complete(webSocket) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            }))
            val connection = f.connection()
            connection.connect()
            connection.awaitConnected()
            delay(16_000)
            assertTrue(connection.isConnected)
            assertTrue(socket.get(1, TimeUnit.SECONDS).send("after upgrade deadline"))
            assertEquals("after upgrade deadline", withTimeout(2_000) { connection.messages.receive() })
        }
    }

    private suspend fun queuedUpgrade() {
        Fixture().use { f ->
            f.base.dispatcher.maxRequests = 1
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val http = f.base.newCall(Request.Builder().url(f.server.url("/held-http")).build())
            http.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
            assertNotNull(f.server.takeRequest(3, TimeUnit.SECONDS))
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val connection = f.connection()
            connection.connect()
            assertEquals(1, f.base.dispatcher.queuedCallsCount())
            delay(16_000)
            assertFalse("base HTTP call must keep its original unlimited deadline", http.isCanceled())
            assertEquals(RelayState.CONNECTING, connection.state.value)
            assertEquals("relay factory shares the global dispatcher", 1, f.server.requestCount)
            http.cancel()
            assertNotNull(f.server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(18_000) { connection.state.first { it == RelayState.FAILED } }
        }
    }

    @Test fun `relay factory does not change image media or base client policies`() {
        Fixture().use { f ->
            val transport = RelayConnection::class.java.getDeclaredField("webSocketFactory").apply { isAccessible = true }
            val relayClient = transport.get(f.connection()) as OkHttpClient
            assertSame("one derived client per factory", relayClient, transport.get(f.connection()))
            assertEquals(15_000, relayClient.callTimeoutMillis)
            assertSame(f.base.dispatcher, relayClient.dispatcher)
            assertSame(f.base.connectionPool, relayClient.connectionPool)
            assertEquals(f.base.pingIntervalMillis, relayClient.pingIntervalMillis)
            val image = AppModule.provideImageClient(f.base)
            val media = AppModule.provideMediaClient(f.base)
            assertEquals(0, f.base.callTimeoutMillis)
            assertEquals(0, image.callTimeoutMillis)
            assertEquals(0, media.callTimeoutMillis)
            assertEquals(0, f.base.readTimeoutMillis)
            assertEquals(15_000, image.readTimeoutMillis)
            assertEquals(30_000, media.readTimeoutMillis)
            assertSame(f.base.connectionPool, image.connectionPool)
            assertSame(f.base.connectionPool, media.connectionPool)
        }
    }
}
