package com.unsilence.app.data.relay

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.auth.SignatureVerifier
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

/** Signed, local fixtures exercise the actual processor -> taps/MES boundary. */
class RequestAdmissionTest {
    @Test fun `signed wrong-author delivery cannot reach subscriber page or memory`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        processor.setTestScope(backgroundScope)
        val socket = AdmissionSocket()
        val connection = socket.connection()
        val transport = object : RelayTransport by FakeRelayTransport() {
            override fun sendToRelay(url: String, msg: String) = connection.send(msg)
        }
        val subscription = Subscription(transport, processor, FakeReconnectSource(), FakeRelayCapabilitiesStore())
        val crypto = ZapCryptoTestFixture()
        val event = crypto.signedEvent(crypto.attacker, 1, emptyList(), "unrequested author")
        var deliveries = 0
        val pages = mutableListOf<RelayCompletion>()
        val handle = subscription.subscribe(listOf(connection.url), NostrFilter(authors = listOf(crypto.sender.pubkey)),
            { deliveries++ }, onRelayCompletion = pages::add)
        val subId = extractSubscriptionIdFromRaw(socket.sent.first())!!
        try {
            processor.process(wire(subId, event), connection)
            processor.process("""["EOSE","$subId"]""", connection)
            processor.drainForTest()
            assertEquals("unrequested author must not reach the subscriber", 0, deliveries)
            assertEquals(0, pages.single().count)
            assertNull(store.getNostrEvent(event.id))
        } finally { handle.close(); subscription.resetForTest(); connection.close(); processor.stop() }
    }

    @Test fun `cached verified event cannot learn a relay from a mismatched request`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        processor.setTestScope(backgroundScope)
        val first = AdmissionSocket().connection("wss://first.example")
        val second = AdmissionSocket().connection("wss://second.example")
        val crypto = ZapCryptoTestFixture()
        val event = crypto.signedEvent(crypto.sender, 1, emptyList(), "requested once")
        first.send("""["REQ","a",{"kinds":[1]}]""")
        second.send("""["REQ","b",{"kinds":[7]}]""")
        try {
            processor.process(wire("a", event), first)
            processor.drainForTest()
            processor.process(wire("b", event), second)
            processor.drainForTest()
            assertEquals(setOf(first.url), store.getNostrEvent(event.id)!!.relaysSeen)
        } finally { first.close(); second.close(); processor.stop() }
    }

    @Test fun `closed or never-sent subscription cannot admit a signed event`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        processor.setTestScope(backgroundScope)
        val connection = AdmissionSocket().connection()
        val crypto = ZapCryptoTestFixture()
        val event = crypto.signedEvent(crypto.sender, 1, emptyList(), "late")
        connection.send("""["REQ","closed",{}]""")
        connection.send("""["CLOSE","closed"]""")
        try {
            processor.process(wire("closed", event), connection)
            processor.process(wire("unknown", event), connection)
            processor.drainForTest()
            assertNull(store.getNostrEvent(event.id))
        } finally { connection.close(); processor.stop() }
    }

    @Test fun `rejection does not poison later matching subscriptions or canonical cache`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        var verifications = 0
        val processor = EventProcessor(store, object : SignatureVerifier() {
            override fun verify(event: com.unsilence.app.data.memory.NostrEvent): Boolean {
                verifications++
                return super.verify(event)
            }
        })
        processor.setTestScope(backgroundScope)
        val connection = AdmissionSocket().connection()
        val crypto = ZapCryptoTestFixture()
        val event = crypto.signedEvent(crypto.sender, 1, emptyList(), "overlapping delivery")
        val delivered = mutableListOf<String>()
        processor.registerTap { if (it is RelayTapMessage.VerifiedEvent) delivered += it.subscriptionId }
        connection.send("""["REQ","wrong",{"kinds":[7]}]""")
        connection.send("""["REQ","first",{"ids":["${event.id}"]}]""")
        connection.send("""["REQ","second",{"authors":["${event.pubkey}"],"kinds":[1]}]""")
        try {
            assertFalse(processor.process(wire("wrong", event), connection))
            assertTrue(processor.process(wire("first", event), connection))
            assertFalse(processor.process(wire("wrong", event), connection))
            assertTrue(processor.process(wire("second", event), connection))
            processor.drainForTest()
            assertEquals(listOf("first", "second"), delivered)
            assertEquals(1, verifications)
            assertNotNull(store.getNostrEvent(event.id))
        } finally { connection.close(); processor.stop() }
    }

    @Test fun `reconnected same-url socket owns its own current replay filter`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        processor.setTestScope(backgroundScope)
        val old = AdmissionSocket().connection()
        val replacement = AdmissionSocket().connection(old.url)
        val crypto = ZapCryptoTestFixture()
        val early = crypto.signedEvent(crypto.sender, 1, emptyList(), "old window", createdAt = 100)
        val latest = crypto.signedEvent(crypto.sender, 1, emptyList(), "new window", createdAt = 200)
        old.send("""["REQ","s",{"since":100}]""")
        old.close()
        replacement.send("""["REQ","s",{"since":200}]""")
        try {
            assertFalse(processor.process(wire("s", early), old))
            assertFalse(processor.process(wire("s", latest), old))
            assertFalse(processor.process("""["EOSE","s"]""", old))
            assertFalse(processor.process(wire("s", early), replacement))
            assertTrue(processor.process(wire("s", latest), replacement))
            processor.drainForTest()
            assertNull(store.getNostrEvent(early.id))
            assertNotNull(store.getNostrEvent(latest.id))
        } finally { old.close(); replacement.close(); processor.stop() }
    }

    @Test fun `closed controls revoke admission and auth resend restores it`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        processor.setTestScope(backgroundScope)
        val socket = AdmissionSocket()
        val connection = socket.connection()
        val controls = mutableListOf<String>()
        processor.registerTap { if (it is RelayTapMessage.Control) controls += it.raw }
        val req = """["REQ","s",{"kinds":[1]}]"""
        connection.send(req)
        try {
            assertFalse(processor.process("""["EOSE","unknown"]""", connection))
            assertFalse(processor.process("""["CLOSED","unknown","auth-required"]""", connection))
            assertTrue(controls.isEmpty())
            assertTrue(processor.process("""["CLOSED","s","auth-required"]""", connection))
            assertNull(connection.requests.request("s"))
            socket.accept = false
            assertFalse(connection.send(req))
            assertFalse(processor.process("""["EOSE","s"]""", connection))
            socket.accept = true
            assertTrue(connection.send(req))
            assertTrue(processor.process("""["EOSE","s"]""", connection))
            assertEquals(2, controls.size)
        } finally { connection.close(); processor.stop() }
    }

    @Test fun `cancellation during signature verification cannot mutate memory or taps`() = runTest {
        val connection = AdmissionSocket().connection()
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, object : SignatureVerifier() {
            override fun verify(event: com.unsilence.app.data.memory.NostrEvent): Boolean {
                val valid = super.verify(event)
                connection.send("""["CLOSE","s"]""")
                return valid
            }
        })
        processor.setTestScope(backgroundScope)
        val crypto = ZapCryptoTestFixture()
        val event = crypto.signedEvent(crypto.sender, 1, emptyList(), "verification race")
        var deliveries = 0
        processor.registerTap { deliveries++ }
        connection.send("""["REQ","s",{}]""")
        try {
            assertFalse(processor.process(wire("s", event), connection))
            processor.drainForTest()
            assertEquals(0, deliveries)
            assertNull(store.getNostrEvent(event.id))
        } finally { connection.close(); processor.stop() }
    }

    @Test fun `direct collector enforces sent filter and subscription before returning event`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        processor.setTestScope(backgroundScope)
        val connection = AdmissionSocket().connection()
        val crypto = ZapCryptoTestFixture()
        val event = crypto.signedEvent(crypto.sender, 1, emptyList(), "direct path")
        connection.send("""["REQ","s",{"kinds":[7]}]""")
        try {
            assertNull(processor.parseAndVerify(wire("s", event), connection))
            connection.send("""["REQ","s",{"kinds":[1]}]""")
            assertNull(processor.parseAndVerify(wire("unknown", event), connection))
            assertEquals(event.id, processor.parseAndVerify(wire("s", event), connection)!!.id)
            assertNull("collector does not insert into MES", store.getNostrEvent(event.id))
        } finally { connection.close(); processor.stop() }
    }

    @Test fun `repost admission matches outer author without rejecting signed embedded target`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        processor.setTestScope(backgroundScope)
        val connection = AdmissionSocket().connection()
        val crypto = ZapCryptoTestFixture()
        val target = crypto.signedEvent(crypto.recipient, 1, emptyList(), "target")
        val wrapper = crypto.signedEvent(crypto.sender, 6, listOf(listOf("e", target.id)), NostrJson.encodeToString(target))
        connection.send("""["REQ","s",{"authors":["${wrapper.pubkey}"],"kinds":[6]}]""")
        try {
            assertTrue(processor.process(wire("s", wrapper), connection))
            processor.drainForTest()
            assertNotNull(store.getNostrEvent(wrapper.id))
        } finally { connection.close(); processor.stop() }
    }

    @Test fun `actual per-relay replay filters replace initial filter and failed attempts do not authorize frames`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        processor.setTestScope(backgroundScope)
        val firstSocket = AdmissionSocket()
        val secondSocket = AdmissionSocket()
        val first = firstSocket.connection("wss://first.example")
        val second = secondSocket.connection("wss://second.example")
        val connections = mapOf(first.url to first, second.url to second)
        val transport = object : RelayTransport by FakeRelayTransport() {
            override fun sendToRelay(url: String, msg: String) = connections.getValue(url).send(msg)
        }
        val subscription = Subscription(transport, processor, FakeReconnectSource(), FakeRelayCapabilitiesStore())
        var firstHead = 200L
        val handle = subscription.subscribe(connections.keys.toList(), NostrFilter(kinds = listOf(1), since = 100), {},
            replayFilterProvider = { NostrFilter(kinds = listOf(1), since = if (it == first.url) firstHead else 300) })
        val subId = extractSubscriptionIdFromRaw(firstSocket.sent.first())!!
        val crypto = ZapCryptoTestFixture()
        val early = crypto.signedEvent(crypto.sender, 1, emptyList(), "early", createdAt = 150)
        val middle = crypto.signedEvent(crypto.sender, 1, emptyList(), "middle", createdAt = 250)
        val latest = crypto.signedEvent(crypto.sender, 1, emptyList(), "latest", createdAt = 400)
        try {
            subscription.pauseAll()
            subscription.resumeAll()
            assertFalse(processor.process(wire(subId, early), first))
            assertTrue(processor.process(wire(subId, middle), first))
            assertFalse(processor.process(wire(subId, middle), second))
            assertTrue(processor.process(wire(subId, latest), second))
            subscription.pauseAll()
            firstHead = 300
            firstSocket.accept = false
            subscription.resumeAll()
            assertFalse(processor.process(wire(subId, latest), first))
            firstHead = 400
            firstSocket.accept = true
            subscription.resumeRelay(first.url, nowMs = 10_000)
            assertFalse(processor.process(wire(subId, middle), first))
            assertTrue(processor.process(wire(subId, latest), first))
            assertTrue(firstSocket.sent.filter { it.startsWith("[\"REQ\"") }
                .all { extractSubscriptionIdFromRaw(it) == subId })
        } finally { handle.close(); subscription.resetForTest(); first.close(); second.close(); processor.stop() }
    }

    @Test fun `remote termination retains mapped ownership until buffered frames drain`() = runTest {
        val socket = AdmissionSocket()
        val registry = RelayConnectionRegistry(Any(), socket::connection)
        val connection = registry.acquire("wss://relay.example", { true }, { true })!!.connection
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        processor.setTestScope(backgroundScope)
        val crypto = ZapCryptoTestFixture()
        val event = crypto.signedEvent(crypto.sender, 1, emptyList(), "buffered before close")
        connection.send("""["REQ","s",{"kinds":[1]}]""")
        socket.listener.onMessage(socket, wire("s", event))
        socket.listener.onMessage(socket, """["EOSE","s"]""")
        socket.listener.onClosed(socket, 1000, "done")
        try {
            assertSame(connection, registry.connections[connection.url])
            assertTrue(processor.process(connection.messages.receive(), connection))
            assertTrue(processor.process(connection.messages.receive(), connection))
            assertTrue(connection.messages.receiveCatching().isClosed)
            processor.drainForTest()
            assertNotNull(store.getNostrEvent(event.id))
            connection.requests.clear()
            assertNull(connection.requests.request("s"))
        } finally { connection.close(); processor.stop() }
    }

    @Test fun `already-admitted novel and cached deliveries finish when their tap closes the request`() = runTest {
        val crypto = ZapCryptoTestFixture()
        val event = crypto.signedEvent(crypto.sender, 1, emptyList(), "admitted")
        for (cached in listOf(false, true)) {
            val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
            val processor = EventProcessor(store, SignatureVerifier())
            processor.setTestScope(backgroundScope)
            val first = AdmissionSocket().connection("wss://first.example")
            val second = AdmissionSocket().connection("wss://second.example")
            first.send("""["REQ","s",{}]""")
            second.send("""["REQ","s",{}]""")
            try {
                if (cached) {
                    processor.process(wire("s", event), first)
                    processor.drainForTest()
                }
                var deliveries = 0
                processor.registerTap {
                    if (it is RelayTapMessage.VerifiedEvent) {
                        deliveries++
                        second.send("""["CLOSE","s"]""")
                    }
                }
                assertTrue(processor.process(wire("s", event), second))
                processor.drainForTest()
                assertTrue(store.getNostrEvent(event.id)!!.relaysSeen.contains(second.url))
                assertFalse(processor.process(wire("s", event), second))
                assertEquals(1, deliveries)
            } finally { first.close(); second.close(); processor.stop() }
        }
    }

    @Test fun `directory diagnostics exclude valid but unrequested events`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        processor.setTestScope(backgroundScope)
        val connection = AdmissionSocket().connection()
        val crypto = ZapCryptoTestFixture()
        val wrong = crypto.signedEvent(crypto.attacker, 30166, emptyList(), "wrong monitor")
        val valid = crypto.signedEvent(crypto.sender, 30166, emptyList(), "known monitor")
        connection.send("""["REQ","directory",{"kinds":[30166],"authors":["${valid.pubkey}"]}]""")
        var invalid = 0
        try {
            assertNull(processor.parseAndVerify(wire("directory", wrong), connection) { invalid++ })
            assertNull(processor.parseAndVerify(wire("unknown", valid), connection) { invalid++ })
            assertEquals(0, invalid)
            assertNotNull(processor.parseAndVerify(wire("directory", valid), connection) { invalid++ })
            assertEquals(0, invalid)
            assertNull(processor.parseAndVerify(wire("directory", valid.copy(content = "tampered")), connection) { invalid++ })
            assertEquals(1, invalid)
        } finally { connection.close(); processor.stop() }
    }

    @Test fun `replacement during remote drain revokes old socket even for frames matching new filter`() = runTest {
        val sockets = mutableListOf<AdmissionSocket>()
        val registry = RelayConnectionRegistry(Any()) { url -> AdmissionSocket().also(sockets::add).connection(url) }
        val old = registry.acquire("wss://relay.example", { true }, { true })!!.connection
        val socket = sockets.single()
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        processor.setTestScope(backgroundScope)
        val crypto = ZapCryptoTestFixture()
        val early = crypto.signedEvent(crypto.sender, 1, emptyList(), "early", createdAt = 100)
        val latest = crypto.signedEvent(crypto.sender, 1, emptyList(), "latest", createdAt = 200)
        old.send("""["REQ","s",{"since":100}]""")
        socket.listener.onMessage(socket, wire("s", early))
        socket.listener.onMessage(socket, wire("s", latest))
        socket.listener.onMessage(socket, """["EOSE","s"]""")
        socket.listener.onClosed(socket, 1000, "done")
        assertSame(old, registry.connections[old.url])
        assertTrue(processor.process(old.messages.receive(), old))
        val replacement = registry.acquire(old.url, { true }, { true })!!.connection
        replacement.send("""["REQ","s",{"since":200}]""")
        try {
            assertSame(replacement, registry.connections[old.url])
            assertFalse(processor.process(old.messages.receive(), old))
            assertFalse(processor.process(old.messages.receive(), old))
            assertTrue(processor.process(wire("s", latest), replacement))
            processor.drainForTest()
            assertNotNull(store.getNostrEvent(early.id))
            assertNotNull(store.getNostrEvent(latest.id))
        } finally { old.close(); replacement.close(); processor.stop() }
    }

    @Test fun `Following and search subscriptions register the production serialized filters`() = runTest {
        val socket = AdmissionSocket()
        val connection = socket.connection()
        val transport = object : RelayTransport by FakeRelayTransport() {
            override fun sendToRelay(url: String, msg: String) = connection.send(msg)
        }
        val subscription = Subscription(transport, FakeTapRegistration(), FakeReconnectSource(), FakeRelayCapabilitiesStore())
        val author = "b".repeat(64)
        val resolver = OutboxRelayResolver(object : RelayMetadataSource {
            override fun writeRelaysFor(pubkey: String) = listOf(connection.url)
        })
        val config = OutboxRelayResolver.Config(kinds = listOf(1, 6, 16), since = 100, limit = 300)
        assertTrue(resolver.resolveFollowing(emptySet(), listOf(connection.url), emptySet(), config).isEmpty())
        val following = resolver.resolveFollowing(setOf(author), listOf(connection.url), emptySet(), config).single().filter
        val filters = listOf(following, NostrFilter(kinds = listOf(1, 30023), search = "relay-defined query", limit = 50),
            NostrFilter(kinds = listOf(1, 6, 7, 9735), tags = mapOf("p" to listOf(author)), since = 100),
            NostrFilter(ids = listOf("a".repeat(64)), kinds = listOf(1), limit = 1),
            NostrFilter(kinds = listOf(39089), limit = 10),
            NostrFilter(kinds = listOf(30166), authors = listOf(author), until = 500, limit = 500))
        val event = EventDto("a".repeat(64), author, 1, "not a local search substring", 200,
            listOf(listOf("p", author)), "0".repeat(128)).toNostrEvent(connection.url)
        try {
            filters.forEachIndexed { index, filter ->
                val handle = subscription.subscribe(listOf(connection.url), filter, {})
                val request = connection.requests.request(extractSubscriptionIdFromRaw(socket.sent.last())!!)!!
                assertTrue(request.matches(event.copy(kind = if (index == 4) 39089 else if (index == 5) 30166 else 1)))
                handle.close()
                assertFalse(connection.requests.isCurrent(request))
            }
        } finally { subscription.resetForTest(); connection.close() }
    }

    private fun wire(subId: String, event: EventDto) = """["EVENT","$subId",${NostrJson.encodeToString(event)}]"""
}

internal class AdmissionSocket : WebSocket {
    val sent = mutableListOf<String>()
    var accept = true
    lateinit var listener: WebSocketListener
    fun connection(url: String = "wss://relay.example"): RelayConnection =
        RelayConnection(url, object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                this@AdmissionSocket.listener = listener
                return this@AdmissionSocket
            }
        }).also { it.connect() }
    override fun request() = Request.Builder().url("https://relay.example").build()
    override fun queueSize() = 0L
    override fun send(text: String): Boolean = accept.also { if (it) sent += text }
    override fun send(bytes: ByteString) = accept
    override fun close(code: Int, reason: String?) = true
    override fun cancel() = Unit
}
