package com.unsilence.app.data.relay

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
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

@OptIn(ExperimentalCoroutinesApi::class)
class RelayPoolInvariantsTest {
    @Test
    fun `cold start publishes connecting and connected without a lifecycle refresh`() = runTest {
        val fixture = RegistryFixture()
        fixture.registry.connectionStates.test {
            assertEquals(emptyMap<String, RelayState>(), awaitItem())

            fixture.acquire()
            assertEquals(mapOf(TEST_RELAY to RelayState.CONNECTING), awaitItem())

            fixture.sockets.open(0)
            assertEquals(mapOf(TEST_RELAY to RelayState.CONNECTED), awaitItem())
            assertEquals(1, fixture.sockets.created.size)
        }
    }

    @Test
    fun `opening console after sockets connect reads their current states`() = runTest {
        val fixture = RegistryFixture()
        fixture.acquire()
        fixture.sockets.open(0)

        fixture.registry.connectionStates.test {
            assertEquals(mapOf(TEST_RELAY to RelayState.CONNECTED), awaitItem())
            assertEquals(1, fixture.sockets.created.size)
        }
    }

    @Test
    fun `failure and remote close update status independently of other sockets`() = runTest {
        val fixture = RegistryFixture()
        val otherRelay = "wss://other.example"
        fixture.acquire()
        fixture.acquire(otherRelay)
        fixture.sockets.open(0)
        fixture.sockets.open(1)

        fixture.registry.connectionStates.test {
            assertEquals(
                mapOf(TEST_RELAY to RelayState.CONNECTED, otherRelay to RelayState.CONNECTED),
                awaitItem(),
            )
            fixture.sockets.fail(0)
            assertEquals(
                mapOf(TEST_RELAY to RelayState.FAILED, otherRelay to RelayState.CONNECTED),
                awaitItem(),
            )
            fixture.sockets.closeFromRelay(1)
            assertEquals(
                mapOf(TEST_RELAY to RelayState.FAILED, otherRelay to RelayState.DISCONNECTED),
                awaitItem(),
            )
        }
    }

    @Test
    fun `background close and foreground replacement remain observable`() = runTest {
        val fixture = RegistryFixture()
        val first = fixture.acquire()
        fixture.sockets.open(0)

        fixture.registry.connectionStates.test {
            assertEquals(mapOf(TEST_RELAY to RelayState.CONNECTED), awaitItem())
            first.close()
            assertEquals(mapOf(TEST_RELAY to RelayState.DISCONNECTED), awaitItem())

            val replacement = fixture.acquire()
            assertNotSame(first, replacement)
            assertEquals(mapOf(TEST_RELAY to RelayState.CONNECTING), awaitItem())
            fixture.sockets.open(1)
            assertEquals(mapOf(TEST_RELAY to RelayState.CONNECTED), awaitItem())

            // OkHttp can finish old callbacks after the replacement has opened.
            fixture.sockets.fail(0)
            fixture.sockets.closeFromRelay(0)
            runCurrent()
            expectNoEvents()
            assertSame(replacement, fixture.registry.connections[TEST_RELAY])
        }
    }

    @Test
    fun `removal cannot detach a replacement and removed callbacks stay absent`() = runTest {
        val fixture = RegistryFixture()
        val first = fixture.acquire()
        fixture.sockets.fail(0)
        val replacement = fixture.acquire()
        fixture.sockets.open(1)

        fixture.registry.connectionStates.test {
            assertEquals(mapOf(TEST_RELAY to RelayState.CONNECTED), awaitItem())
            assertNull(fixture.registry.remove(TEST_RELAY, first))
            runCurrent()
            expectNoEvents()

            assertSame(replacement, fixture.registry.remove(TEST_RELAY, replacement))
            assertTrue(fixture.registry.connections.isEmpty())
            replacement.close()
            assertEquals(emptyMap<String, RelayState>(), awaitItem())
            fixture.sockets.open(0)
            fixture.sockets.fail(1)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `unconditional removal publishes the remaining membership`() = runTest {
        val fixture = RegistryFixture()
        val first = fixture.acquire()
        val otherRelay = "wss://other.example"
        fixture.acquire(otherRelay)

        fixture.registry.connectionStates.test {
            assertEquals(2, awaitItem().size)
            assertSame(first, fixture.registry.remove(TEST_RELAY))
            assertEquals(mapOf(otherRelay to RelayState.CONNECTING), awaitItem())
            assertNull(fixture.registry.remove(TEST_RELAY))
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `teardown publishes empty before close and the next session starts fresh`() = runTest {
        val fixture = RegistryFixture()
        fixture.acquire()
        fixture.acquire("wss://other.example")
        fixture.sockets.open(0)
        fixture.sockets.open(1)

        fixture.registry.connectionStates.test {
            assertEquals(2, awaitItem().size)
            val detached = fixture.registry.clear()
            assertEquals(2, detached.size)
            assertTrue(fixture.registry.connections.isEmpty())
            assertEquals(emptyMap<String, RelayState>(), awaitItem())
            detached.forEach { it.close() }
            fixture.sockets.fail(0)
            runCurrent()
            expectNoEvents()

            fixture.acquire()
            assertEquals(mapOf(TEST_RELAY to RelayState.CONNECTING), awaitItem())
            fixture.sockets.open(2)
            assertEquals(mapOf(TEST_RELAY to RelayState.CONNECTED), awaitItem())
        }
    }

    @Test
    fun `resubscribing after unobserved changes reads current sockets without reconnecting`() = runTest {
        val fixture = RegistryFixture()
        fixture.acquire()
        fixture.sockets.open(0)
        fixture.registry.connectionStates.test {
            assertEquals(mapOf(TEST_RELAY to RelayState.CONNECTED), awaitItem())
        }

        fixture.sockets.fail(0)
        fixture.acquire()
        fixture.sockets.open(1)

        fixture.registry.connectionStates.test {
            assertEquals(mapOf(TEST_RELAY to RelayState.CONNECTED), awaitItem())
            assertEquals(2, fixture.sockets.created.size)
        }
    }

    @Test
    fun `reusing a healthy socket neither reconnects nor republishes identical status`() = runTest {
        val fixture = RegistryFixture()
        val connection = fixture.acquire()
        fixture.sockets.open(0)

        fixture.registry.connectionStates.test {
            assertEquals(mapOf(TEST_RELAY to RelayState.CONNECTED), awaitItem())
            assertSame(connection, fixture.acquire())
            runCurrent()
            expectNoEvents()
            assertEquals(1, fixture.sockets.created.size)
        }
    }

    @Test
    fun `ephemeral sockets never enter pooled status`() = runTest {
        val fixture = RegistryFixture()
        fixture.registry.connectionStates.test {
            assertEquals(emptyMap<String, RelayState>(), awaitItem())
            val ephemeral = RelayConnection("wss://ephemeral.example", fixture.sockets)
            ephemeral.connect()
            fixture.sockets.open(0)
            runCurrent()
            expectNoEvents()
            assertTrue(fixture.registry.connections.isEmpty())
            ephemeral.close()
        }
    }

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
        val registry = RelayConnectionRegistry(Any()) { url ->
            RelayConnection(url, sockets)
        }
        val connections = registry.connections
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
        val registry = RelayConnectionRegistry(Any()) { url ->
            RelayConnection(url, sockets)
        }
        val connections = registry.connections
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

    private class RegistryFixture {
        val sockets = CountingWebSocketFactory()
        val registry = RelayConnectionRegistry(Any()) { url -> RelayConnection(url, sockets) }

        fun acquire(url: String = TEST_RELAY): RelayConnection = registry.acquire(
            url,
            transportAllowed = { true },
            canCreateNew = { true },
        )!!.connection
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

        @Synchronized
        fun open(index: Int) {
            val socket = created[index]
            val response = Response.Builder()
                .request(socket.request())
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build()
            socket.listener.onOpen(socket, response)
        }

        @Synchronized
        fun closeFromRelay(index: Int) {
            val socket = created[index]
            socket.listener.onClosed(socket, 1000, "normal close")
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
