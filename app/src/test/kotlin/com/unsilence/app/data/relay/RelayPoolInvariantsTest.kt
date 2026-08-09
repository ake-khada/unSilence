package com.unsilence.app.data.relay

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RelayPoolInvariantsTest {
    @Test
    fun `reference fetch kinds retain addressable NIP-71 targets`() {
        assertTrue(16 in EVENT_REFERENCE_FETCH_KINDS)
        assertTrue(34235 in EVENT_REFERENCE_FETCH_KINDS)
        assertTrue(34236 in EVENT_REFERENCE_FETCH_KINDS)
        assertTrue(1111 in EVENT_REFERENCE_FETCH_KINDS)
    }

    @Test
    fun `concurrent reconnects to same relay become one WebSocket`() {
        val sockets = CountingWebSocketFactory()
        val connections = ConcurrentHashMap<String, RelayConnection>()
        val registry = RelayConnectionRegistry(connections, Any()) { url ->
            RelayConnection(url, sockets)
        }
        val ready = CountDownLatch(10)
        val start = CountDownLatch(1)
        val done = CountDownLatch(10)
        val failure = AtomicReference<Throwable?>()
        val claims = ConcurrentLinkedQueue<RelayConnectionClaim>()

        repeat(10) {
            Thread {
                try {
                    ready.countDown()
                    start.await()
                    registry.acquire(
                        url = TEST_RELAY,
                        transportAllowed = { true },
                        canCreateNew = { true },
                    )?.let(claims::add)
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }.start()
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertEquals(10, claims.size)
        assertEquals(1, claims.count { it.installed })
        assertEquals(1, connections.size)
        assertEquals(1, sockets.created.size)
        val winner = connections.getValue(TEST_RELAY)
        claims.forEach { assertSame(winner, it.connection) }
        assertEquals(0, sockets.created.single().closeCalls.get())
    }

    @Test
    fun `stale replacement closes old socket and resets one-shot ownership`() {
        val sockets = CountingWebSocketFactory()
        val connections = ConcurrentHashMap<String, RelayConnection>()
        val registry = RelayConnectionRegistry(connections, Any()) { url ->
            RelayConnection(url, sockets)
        }
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
        val owners = ConcurrentHashMap<RelayOneShotOwnerKey, RelayConnection>()
        val reset: (String, RelayConnection?) -> Unit = { url, _ ->
            resetRelayOneShotOwners(url, owners)
            resetRelayOneShotBookkeeping(url, counts, queues)
        }

        val first = registry.acquire(
            TEST_RELAY,
            transportAllowed = { true },
            canCreateNew = { true },
            beforeInstall = reset,
        )!!.connection
        sockets.fail(0)
        counts[TEST_RELAY] = AtomicInteger(10)
        queues[TEST_RELAY] = ConcurrentLinkedQueue<String>().apply { add("queued") }
        owners[RelayOneShotOwnerKey("old-sub", TEST_RELAY)] = first

        val replacement = registry.acquire(
            TEST_RELAY,
            transportAllowed = { true },
            canCreateNew = { true },
            beforeInstall = reset,
        )!!

        assertTrue(replacement.installed)
        assertNotSame(first, replacement.connection)
        assertSame(replacement.connection, connections[TEST_RELAY])
        assertEquals(2, sockets.created.size)
        assertEquals(1, sockets.created.first().closeCalls.get())
        assertFalse(counts.containsKey(TEST_RELAY))
        assertFalse(queues.containsKey(TEST_RELAY))
        assertTrue(owners.isEmpty())
    }

    @Test
    fun `late cleanup from replaced socket cannot claim replacement ownership`() {
        val oldSocket = Any()
        val replacementSocket = Any()
        val owners = ConcurrentHashMap<RelayOneShotOwnerKey, Any>()
        val key = RelayOneShotOwnerKey("same-sub", TEST_RELAY)

        owners[key] = oldSocket
        resetRelayOneShotOwners(TEST_RELAY, owners)
        owners[key] = replacementSocket

        assertNull(
            takeRelayOneShotOwner(
                subId = key.subId,
                url = key.url,
                sourceOwner = oldSocket,
                owners = owners,
            ),
        )
        assertSame(replacementSocket, owners[key])
        assertSame(
            replacementSocket,
            takeRelayOneShotOwner(
                subId = key.subId,
                url = key.url,
                sourceOwner = replacementSocket,
                owners = owners,
            ),
        )
        assertTrue(owners.isEmpty())
    }

    @Test
    fun `active subscription protects a purpose-less connection from idle release`() {
        val old = 1_000L
        val now = old + RelayPool.IDLE_EVICTION_THRESHOLD_MS + 1L

        assertFalse(
            shouldReleaseRelayConnection(
                purposes = emptySet(),
                hasActiveSubscription = true,
                lastActivityMs = old,
                nowMs = now,
            ),
        )
        assertFalse(
            shouldReleaseRelayConnection(
                purposes = setOf(ConnectionPurpose.PERSISTENT),
                hasActiveSubscription = false,
                lastActivityMs = old,
                nowMs = now,
            ),
        )
        assertTrue(
            shouldReleaseRelayConnection(
                purposes = emptySet(),
                hasActiveSubscription = false,
                lastActivityMs = old,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `force eviction prefers browse and never selects a live subscription`() {
        val selected = selectForceEvictionCandidate(
            listOf(
                RelayEvictionCandidate("wss://live.example", emptySet(), true, 1L),
                RelayEvictionCandidate("wss://persistent.example", setOf(ConnectionPurpose.PERSISTENT), false, 2L),
                RelayEvictionCandidate("wss://feed.example", setOf(ConnectionPurpose.FEED_SUB), false, 2L),
                RelayEvictionCandidate("wss://none.example", emptySet(), false, 3L),
                RelayEvictionCandidate("wss://browse.example", setOf(ConnectionPurpose.BROWSE), false, 4L),
            ),
        )

        assertEquals("wss://browse.example", selected?.url)
        assertNull(
            selectForceEvictionCandidate(
                listOf(
                    RelayEvictionCandidate("wss://live.example", emptySet(), true, 1L),
                    RelayEvictionCandidate("wss://persistent.example", setOf(ConnectionPurpose.PERSISTENT), false, 2L),
                    RelayEvictionCandidate("wss://feed.example", setOf(ConnectionPurpose.FEED_SUB), false, 3L),
                ),
            ),
        )
    }

    @Test
    fun `force eviction at capacity opens one slot while normal work stays capped`() {
        var size = 50
        var evictionCalls = 0
        val evict = {
            evictionCalls++
            size--
            true
        }

        assertFalse(
            ensureRelayPoolCapacity(
                currentSize = { size },
                cap = 50,
                forceEvict = false,
                evictMostIdle = evict,
            ),
        )
        assertEquals(0, evictionCalls)
        assertTrue(
            ensureRelayPoolCapacity(
                currentSize = { size },
                cap = 50,
                forceEvict = true,
                evictMostIdle = evict,
            ),
        )
        assertEquals(49, size)
        assertEquals(1, evictionCalls)
    }

    private class CountingWebSocketFactory : WebSocket.Factory {
        val created = mutableListOf<FakeWebSocket>()

        @Synchronized
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeWebSocket(request, listener).also(created::add)

        @Synchronized
        fun fail(index: Int) {
            val socket = created[index]
            socket.listener.onFailure(socket, IOException("synthetic failure"), null)
        }
    }

    private class FakeWebSocket(
        private val originalRequest: Request,
        val listener: WebSocketListener,
    ) : WebSocket {
        val closeCalls = AtomicInteger(0)

        override fun request(): Request = originalRequest
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean {
            closeCalls.incrementAndGet()
            return true
        }
        override fun cancel() = Unit
    }

    private companion object {
        const val TEST_RELAY = "wss://atomic.example"
    }
}
