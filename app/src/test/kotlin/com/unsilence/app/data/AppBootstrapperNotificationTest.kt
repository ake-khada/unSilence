package com.unsilence.app.data

import com.unsilence.app.data.memory.RelayList
import com.unsilence.app.data.relay.ConnectionPurpose
import com.unsilence.app.data.relay.NotificationInboxRouting
import com.unsilence.app.data.relay.RelayConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppBootstrapperNotificationTest {
    @Test fun `bootstrap connects persistent read-only inbox before subscribing`() = runTest {
        Fixture().use { f ->
            assertTrue(f.connections.isEmpty())
            val steps = mutableListOf<String>()
            bootstrapNotificationInbox(f.declared, { true },
                addPurpose = { url, purpose ->
                    assertEquals(INBOX, url)
                    assertEquals(ConnectionPurpose.PERSISTENT, purpose)
                    steps += "purpose"
                },
                connect = { urls, timeout ->
                    assertEquals(listOf(INBOX), urls)
                    assertEquals(5_000L, timeout)
                    steps += "connect"
                    f.connect()
                },
                subscribe = { steps += "subscribe"; f.routing.subscribe("account", REQUEST) },
            )
            assertEquals(listOf("purpose", "connect", "subscribe"), steps)
            assertEquals(listOf(REQUEST), f.socket.sent)
            val connection = f.connections.getValue(INBOX)
            backgroundScope.launch { f.routing.replayWhenConnected(connection) { f.connections[INBOX] === connection } }
            runCurrent()
            f.open()
            runCurrent()
            assertEquals("already queued request must not be duplicated at onOpen", listOf(REQUEST), f.socket.sent)
        }
    }

    @Test fun `read-only inbox not registered at subscribe time is subscribed on later first connect`() = runTest {
        Fixture().use { f ->
            bootstrapNotificationInbox(f.declared, { true }, { _, _ -> },
                connect = { _, _ -> /* Capability gate or pool admission has deferred it. */ },
                subscribe = { assertEquals(0, f.routing.subscribe("account", REQUEST)) },
            )
            assertTrue(f.connections.isEmpty())
            val connection = f.connect()
            backgroundScope.launch { f.routing.replayWhenConnected(connection) { f.connections[INBOX] === connection } }
            runCurrent()
            assertTrue(f.socket.sent.isEmpty())
            f.open()
            runCurrent()
            assertEquals(listOf(REQUEST), f.socket.sent)
            f.routing.replay(INBOX, connection)
            assertEquals("one request per socket identity", listOf(REQUEST), f.socket.sent)
        }
    }

    @Test fun `account teardown fences a late notification connection`() = runTest {
        Fixture().use { f ->
            f.routing.subscribe("account", REQUEST)
            val connection = f.connect()
            backgroundScope.launch { f.routing.replayWhenConnected(connection) { f.connections[INBOX] === connection } }
            runCurrent()
            f.routing.close()
            f.open()
            runCurrent()
            assertTrue(f.socket.sent.isEmpty())
        }
    }

    @Test fun `stale bootstrap cannot subscribe after the connection wait`() = runTest {
        var current = true
        var subscribed = false
        bootstrapNotificationInbox(RelayList(listOf(INBOX), emptyList()), { current }, { _, _ -> },
            connect = { _, _ -> current = false }, subscribe = { subscribed = true })
        assertFalse(subscribed)
    }

    private class Fixture : AutoCloseable {
        val declared = RelayList(listOf("wss://inbox.example/", "inbox.example"), emptyList())
        val connections = mutableMapOf<String, RelayConnection>()
        lateinit var socket: Socket
        val routing = NotificationInboxRouting({ declared }, { connections.keys },
            { connections.filterValues { it.isConnected }.keys.toList() }, { emptyList() }) { url, request ->
            connections[url]?.takeIf { it.send(request) }
        }
        fun connect(): RelayConnection = RelayConnection(INBOX, WebSocket.Factory { request, listener ->
            Socket(request, listener).also { socket = it }
        }).also { it.connect(); connections[INBOX] = it }
        fun open() = socket.listener.onOpen(socket, Response.Builder().request(socket.request())
            .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        override fun close() { routing.close(); connections.values.forEach { it.close(); it.messages.cancel() } }
    }
    private class Socket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<String>()
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }
    private companion object {
        const val INBOX = "wss://inbox.example"
        const val REQUEST = """["REQ","own-notif-live",{"kinds":[1,6,7,9735],"#p":["account"],"since":1234}]"""
    }
}
