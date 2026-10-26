package com.unsilence.app.data.relay

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual loopback WebSockets, not listener-only simulation. No external relay or account. */
class RelayConnectionSocketTest {
    @Test fun `slow consumer receives sustained burst and application controls in order`() = runBlocking {
        // Shortened keep-alive interval exercises a delayed pong within this bounded test.
        SocketFixture(pingMs = 5_000).use { f ->
            val started = System.nanoTime()
            val frames = buildList {
                repeat(1_000) { n ->
                    add("""["EVENT","sub",{"n":$n,"content":"${"x".repeat(256)}"}]""")
                    if (n % 100 == 99) {
                        add("""["EOSE","page-$n"]""")
                        add("""["OK","event-$n",true,""]""")
                        add("""["AUTH","challenge-$n"]""")
                        add("""["CLOSED","other-$n","complete"]""")
                    }
                }
            }
            frames.forEach { assertTrue(f.serverSocket.send(it)) }
            f.assertReaderHeld()
            // OkHttp has no public ping listener. Read its resolved 5.3.2 counters;
            // ping/pong frames themselves are sent by normal scheduled socket code.
            await("server receives scheduled client ping", 7_000) {
                f.counter(f.serverSocket, "receivedPingCount") == 1
            }
            assertEquals(1, f.counter(f.clientSocket, "sentPingCount"))
            assertEquals("held reader cannot process returned pong yet", 0,
                f.counter(f.clientSocket, "receivedPongCount"))
            val received = mutableListOf<String>()
            withTimeout(15_000) {
                repeat(frames.size) {
                    received += f.connection.messages.receive()
                    delay(3) // Deliberately slower than the socket producer for the whole page.
                }
            }
            assertEquals(frames, received)
            await("pong after capacity recovers") { f.counter(f.clientSocket, "receivedPongCount") >= 1 }
            assertNull(f.failure.get())
            f.connection.close()
            assertEquals(1000, f.serverClosed.get(5, TimeUnit.SECONDS))
            assertEquals(1000, f.clientClosed.get(5, TimeUnit.SECONDS))
            println("SOCKET burst: events=1000 appControls=40 ordered=1040 lost=0 " +
                "clientPing=1 clientPong=1 pingIntervalMs=5000 close=1000 failures=0 elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        }
    }

    @Test fun `oversized frame closes real socket with 1009 and preserves accepted frames`() = runBlocking {
        SocketFixture().use { f ->
            assertTrue(f.serverSocket.send("accepted before oversized frame"))
            assertTrue(f.serverSocket.send("x".repeat(512 * 1024 + 1)))
            assertEquals("accepted before oversized frame", withTimeout(5_000) { f.connection.messages.receive() })
            assertTrue(withTimeout(5_000) { f.connection.messages.receiveCatching() }.isClosed)
            assertFalse("inbox exhaustion cannot leave a healthy pooled socket", f.connection.isConnected)
            assertEquals(1009, f.serverClosed.get(5, TimeUnit.SECONDS))
            assertEquals(1009, f.clientClosed.get(5, TimeUnit.SECONDS))
            assertNull(f.failure.get())
            println("SOCKET oversized: accepted=1 rejectedChars=524289 close=1009 failures=0")
        }
    }

    @Test fun `client close completes real close handshake while inbox is full and unconsumed`() {
        SocketFixture().use { f ->
            repeat(400) { assertTrue(f.serverSocket.send("frame-$it")) }
            f.assertReaderHeld()
            f.connection.close()
            assertEquals(1000, f.serverClosed.get(5, TimeUnit.SECONDS))
            assertEquals(1000, f.clientClosed.get(5, TimeUnit.SECONDS))
            assertEquals(RelayState.DISCONNECTED, f.connection.state.value)
            assertNull(f.failure.get())
            println("SOCKET client-close: fullInbox=true consumer=false close=1000 failures=0")
        }
    }

    @Test fun `remote close waits behind held reader then completes after consumption resumes`() = runBlocking {
        SocketFixture().use { f ->
            val frames = List(400) { "frame-$it" }
            frames.forEach { assertTrue(f.serverSocket.send(it)) }
            f.assertReaderHeld()
            assertTrue(f.serverSocket.close(1000, "server done"))
            Thread.sleep(150)
            assertFalse("reader cannot see close behind blocked message", f.clientClosing.isDone)
            withTimeout(5_000) { frames.forEach { assertEquals(it, f.connection.messages.receive()) } }
            assertEquals(1000, f.clientClosing.get(5, TimeUnit.SECONDS))
            // Existing RelayConnection does not respond in onClosing; the owner closes it.
            // Preserve that lifecycle and do not claim remote close bypasses a held reader.
            f.connection.close()
            assertEquals(1000, f.serverClosed.get(5, TimeUnit.SECONDS))
            assertEquals(1000, f.clientClosed.get(5, TimeUnit.SECONDS))
            assertNull(f.failure.get())
            println("SOCKET remote-close: delayedWhileHeld=true delivered=400 close=1000 failures=0")
        }
    }

    @Test fun `ping timeout releases blocked real reader without a consumer`() {
        // Accelerated failure test only. Production's interval is 25 seconds.
        SocketFixture(pingMs = 200).use { f ->
            val started = System.nanoTime()
            repeat(400) { assertTrue(f.serverSocket.send("frame-$it")) }
            f.assertReaderHeld()
            await("ping timeout must fail connection", 5_000) { f.connection.state.value == RelayState.FAILED }
            await("failure must release reader callback") { f.completed.get() > 64 }
            val failure = requireNotNull(f.failure.get())
            assertTrue(failure.toString(), failure is java.net.SocketTimeoutException)
            assertTrue(failure.message.orEmpty().contains("pong"))
            println("SOCKET ping-timeout: intervalMs=200 fullInbox=true consumer=false " +
                "released=true elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        }
    }

    private class SocketFixture(pingMs: Long = 25_000) : AutoCloseable {
        private val server = MockWebServer()
        private val client = OkHttpClient.Builder().pingInterval(pingMs, TimeUnit.MILLISECONDS).build()
        private val serverReady = CompletableFuture<WebSocket>()
        private val clientReady = CompletableFuture<WebSocket>()
        val serverClosed = CompletableFuture<Int>()
        val clientClosed = CompletableFuture<Int>()
        val clientClosing = CompletableFuture<Int>()
        val failure = AtomicReference<Throwable?>()
        val completed = AtomicInteger()
        private val reader = AtomicReference<Thread?>()
        private val entered = AtomicInteger()
        val connection: RelayConnection
        val serverSocket: WebSocket get() = serverReady.get(5, TimeUnit.SECONDS)
        val clientSocket: WebSocket get() = clientReady.get(5, TimeUnit.SECONDS)

        init {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { serverReady.complete(webSocket) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { serverClosed.complete(code) }
            }))
            server.start()
            val factory = WebSocket.Factory { request: Request, delegate: WebSocketListener ->
                client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        delegate.onOpen(webSocket, response)
                        clientReady.complete(webSocket)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        reader.set(Thread.currentThread())
                        entered.incrementAndGet()
                        delegate.onMessage(webSocket, text)
                        completed.incrementAndGet()
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        failure.set(t)
                        delegate.onFailure(webSocket, t, response)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        delegate.onClosing(webSocket, code, reason)
                        clientClosing.complete(code)
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        delegate.onClosed(webSocket, code, reason)
                        clientClosed.complete(code)
                    }
                })
            }
            connection = RelayConnection(server.url("/").toString(), factory)
            // Constructor failure also closes resources: use{} cannot clean up a failed constructor.
            try {
                connection.connect()
                clientReady.get(5, TimeUnit.SECONDS)
                serverReady.get(5, TimeUnit.SECONDS)
            } catch (t: Throwable) { close(); throw t }
        }

        fun assertReaderHeld() {
            await("reader must enter 65th callback") { entered.get() >= 65 }
            await("reader must block inside RelayConnection") {
                val r = reader.get()
                r != null && (r.state == Thread.State.WAITING || r.state == Thread.State.TIMED_WAITING) &&
                    r.stackTrace.any { it.className == "kotlinx.coroutines.BlockingCoroutine" && it.methodName == "joinBlocking" } &&
                    r.stackTrace.any { it.className == RelayConnection::class.java.name && it.methodName == "awaitInboxCapacity" }
            }
            assertEquals(64, completed.get())
            assertTrue(reader.get()!!.isAlive)
        }

        fun counter(socket: WebSocket, method: String) = socket.javaClass.getMethod(method).invoke(socket) as Int

        override fun close() {
            connection.close()
            connection.messages.cancel()
            clientReady.getNow(null)?.cancel()
            // The accepted server-side RealWebSocket has no client Call to cancel.
            // MockWebServer.shutdown closes its accepted sockets and worker pool.
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.shutdown()
            check(client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)) { "client worker leaked" }
        }
    }

    private companion object {
        fun await(description: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < end) {
                if (condition()) return
                Thread.sleep(2)
            }
            assertTrue(description, condition())
        }
    }
}
