package com.unsilence.app.data.relay

import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RelayConnectionDeliveryTest {
    @Test fun `thousand frame burst reaches late consumer intact and ordered`() = runBlocking {
        Fixture().use { f ->
            val frames = List(1_000) { """["EVENT","sub",{"n":$it}]""" }
            f.start(frames)
            // Wait for capacity backpressure or completion before allowing any consumption.
            // The dropping implementation deterministically retains only its first 64 frames.
            f.awaitFinishedOrBlocked()
            val received = mutableListOf<String>()
            withTimeoutOrNull(2_000) { repeat(frames.size) { received += f.connection.messages.receive() } }
            assertEquals("all frames must arrive, including the tail", frames.size, received.size)
            assertEquals(frames, received)
            f.assertReaderFinished()
        }
    }

    @Test fun `consumer cancellation releases an actually blocked reader`() = runBlocking {
        Fixture().use { f ->
            f.start()
            f.assertReaderBlocked()
            val received = withTimeout(2_000) { f.connection.messages.consumeAsFlow().take(10).toList() }
            assertEquals(List(10) { "frame-$it" }, received)
            f.assertReaderFinished()
        }
    }

    @Test fun `client close releases full inbox with no consumer`() = terminalRelease(RelayState.DISCONNECTED) {
        it.connection.close()
        it.connection.close()
    }

    @Test fun `onFailure releases full inbox with no consumer`() = terminalRelease(RelayState.FAILED) {
        it.socket.listener.onFailure(it.socket, IOException("synthetic failure"), null)
    }

    @Test fun `onClosed releases full inbox with no consumer`() = terminalRelease(RelayState.DISCONNECTED) {
        it.socket.listener.onClosed(it.socket, 1000, "remote close")
    }

    @Test fun `reader interruption terminates connection and propagates interrupt`() {
        Fixture().use { f ->
            f.start()
            f.assertReaderBlocked()
            f.interruptAndAssertTerminated()
        }
    }

    @Test fun `invalid URL setup rejection closes inbox without creating a socket`() = runBlocking {
        var created = false
        val connection = RelayConnection("not a websocket URL", WebSocket.Factory { _, _ ->
            created = true
            error("invalid URL must be rejected before factory")
        })
        try {
            connection.connect()
            assertEquals(RelayState.FAILED, connection.state.value)
            assertFalse(created)
            assertTrue(withTimeout(1_000) { connection.messages.receiveCatching() }.isClosed)
        } finally {
            connection.close()
            connection.messages.cancel()
        }
    }

    @Test fun `frame size cap still accepts boundary and rejects oversized frame`() = runBlocking {
        Fixture().use { f ->
            val boundary = "x".repeat(512 * 1024)
            f.socket.listener.onMessage(f.socket, boundary)
            assertEquals(boundary, f.connection.messages.receive())
            f.socket.listener.onMessage(f.socket, boundary + "x")
            assertEquals(1009, f.socket.lastCloseCode)
            assertTrue(f.connection.messages.tryReceive().isFailure)
        }
    }

    @Test fun `close during handshake cancels rather than queuing a close frame`() {
        Fixture(open = false).use { f ->
            f.connection.close()
            assertEquals(1, f.socket.cancelCalls)
            assertNull(f.socket.lastCloseCode)
        }
    }

    private fun terminalRelease(expected: RelayState, terminate: (Fixture) -> Unit) {
        Fixture().use { f ->
            f.start()
            f.assertReaderBlocked()
            terminate(f)
            f.assertReaderFinished()
            assertEquals(expected, f.connection.state.value)
        }
    }

    private class Fixture(open: Boolean = true) : AutoCloseable {
        lateinit var socket: CapturedSocket
        val connection = RelayConnection("wss://burst.example", WebSocket.Factory { request, listener ->
            CapturedSocket(request, listener).also { socket = it }
        })
        private var reader: Thread? = null
        private val completed = AtomicInteger()
        private val failure = AtomicReference<Throwable?>()
        private val interrupted = AtomicBoolean()
        init {
            connection.connect()
            if (open) socket.listener.onOpen(socket, Response.Builder().request(socket.request())
                .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        }
        fun start(frames: List<String> = List(1_000) { "frame-$it" }) {
            reader = thread(name = "relay-delivery-test-reader", isDaemon = true) {
                try {
                    frames.forEach {
                        socket.listener.onMessage(socket, it)
                        completed.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    interrupted.set(Thread.currentThread().isInterrupted)
                    failure.set(t)
                }
            }
        }
        fun awaitFinishedOrBlocked() {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                val r = requireNotNull(reader)
                if (!r.isAlive || (completed.get() > 0 && isCapacityWait(r))) return
                Thread.sleep(2)
            }
            fail("reader neither finished nor reached capacity wait")
        }
        fun assertReaderBlocked() {
            awaitFinishedOrBlocked()
            val r = requireNotNull(reader)
            assertTrue("reader must still be blocked before termination", r.isAlive)
            assertEquals("bounded inbox holds exactly 64 frames", 64, completed.get())
            assertTrue("reader must be parked, not merely entering the capacity path", isCapacityWait(r))
        }
        private fun isCapacityWait(r: Thread): Boolean =
            (r.state == Thread.State.WAITING || r.state == Thread.State.TIMED_WAITING) &&
                r.stackTrace.any { it.className == "kotlinx.coroutines.BlockingCoroutine" && it.methodName == "joinBlocking" } &&
                r.stackTrace.any { it.className == RelayConnection::class.java.name && it.methodName == "awaitInboxCapacity" }

        fun interruptAndAssertTerminated() {
            requireNotNull(reader).interrupt()
            reader!!.join(2_000)
            assertFalse(reader!!.isAlive)
            assertTrue(failure.get() is InterruptedException)
            assertTrue("interrupt flag restored before propagating", interrupted.get())
            assertEquals(RelayState.DISCONNECTED, connection.state.value)
        }
        fun assertReaderFinished() {
            requireNotNull(reader).join(2_000)
            assertFalse("terminal action must release reader without a consumer", reader!!.isAlive)
            assertNull("reader callback failed", failure.get())
        }
        override fun close() {
            // Always release even on assertion failure; daemon is a last-resort guard.
            connection.close()
            connection.messages.cancel()
            reader?.join(2_000)
            if (reader?.isAlive == true) { reader?.interrupt(); reader?.join(2_000) }
            check(reader?.isAlive != true) { "reader leaked after cleanup" }
        }
    }

    private class CapturedSocket(private val original: Request, val listener: WebSocketListener) : WebSocket {
        var cancelCalls = 0
        var lastCloseCode: Int? = null
        override fun request() = original
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?): Boolean { lastCloseCode = code; return true }
        override fun cancel() { cancelCalls++ }
    }
}
