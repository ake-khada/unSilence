package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for the Subscription primitive.
 *
 * Strategy: real Subscription with hand-rolled fakes for RelayTransport
 * and TapRegistration. Tests inject incoming messages by calling the
 * registered tap directly via FakeTapRegistration.fire(...).
 *
 * No mockk, no real RelayPool, no real network.
 */
class SubscriptionTest {

    private lateinit var transport: FakeRelayTransport
    private lateinit var tapRegistry: FakeTapRegistration
    private lateinit var subscription: Subscription

    @Before
    fun setUp() {
        transport = FakeRelayTransport()
        tapRegistry = FakeTapRegistration()
        subscription = Subscription(
            transport,
            tapRegistry,
            FakeReconnectSource(),
            FakeRelayCapabilitiesStore(),
            FakeSignatureVerifier(),
        )
    }

    @Test
    fun `subscribe sends REQ to all relays with correct shape`() = runTest {
        val urls = listOf("wss://a.example", "wss://b.example")
        val filter = NostrFilter(kinds = listOf(1), limit = 10)
        subscription.subscribe(urls, filter, onevent = {})
        assertEquals(2, transport.sends.size)
        for (sent in transport.sends) {
            assertTrue("Should start with [\"REQ\"", sent.msg.startsWith("[\"REQ\""))
            assertTrue("Should contain kinds:[1]", sent.msg.contains("\"kinds\":[1]"))
            assertTrue("Should contain limit:10", sent.msg.contains("\"limit\":10"))
        }
        assertEquals(setOf("wss://a.example", "wss://b.example"), transport.sends.map { it.url }.toSet())
    }

    @Test
    fun `subscribe registers single tap on first call`() = runTest {
        assertEquals(0, tapRegistry.registrations.size)
        subscription.subscribe(listOf("wss://a.example"), NostrFilter(), onevent = {})
        assertEquals(1, tapRegistry.registrations.size)
        // Second subscribe doesn't register again
        subscription.subscribe(listOf("wss://b.example"), NostrFilter(), onevent = {})
        assertEquals(1, tapRegistry.registrations.size)
    }

    @Test
    fun `onevent fires for matching subId`() = runTest {
        val received = CopyOnWriteArrayList<NostrEvent>()
        subscription.subscribe(
            urls = listOf("wss://a.example"),
            filter = NostrFilter(kinds = listOf(1)),
            onevent = { received.add(it) },
        )
        val subId = transport.sends.first().subId()
        val evtJson = sampleEventJson(id = "a".repeat(64), kind = 1)
        tapRegistry.fire("""["EVENT","$subId",$evtJson]""", "wss://a.example")
        assertEquals(1, received.size)
        assertEquals("a".repeat(64), received[0].id)
    }

    @Test
    fun `onevent does not fire when signature verification fails`() = runTest {
        subscription = Subscription(
            transport,
            tapRegistry,
            FakeReconnectSource(),
            FakeRelayCapabilitiesStore(),
            FakeSignatureVerifier(result = false),
        )
        val received = CopyOnWriteArrayList<NostrEvent>()
        subscription.subscribe(
            urls = listOf("wss://a.example"),
            filter = NostrFilter(kinds = listOf(1)),
            onevent = { received.add(it) },
        )
        val subId = transport.sends.first().subId()
        val evtJson = sampleEventJson(id = "a".repeat(64), kind = 1)
        tapRegistry.fire("""["EVENT","$subId",$evtJson]""", "wss://a.example")
        assertEquals(0, received.size)
    }

    @Test
    fun `onevent does not fire for other subIds`() = runTest {
        val received = AtomicInteger(0)
        subscription.subscribe(
            urls = listOf("wss://a.example"),
            filter = NostrFilter(),
            onevent = { received.incrementAndGet() },
        )
        val evtJson = sampleEventJson(id = "a".repeat(64))
        tapRegistry.fire("""["EVENT","sub-other-1234",$evtJson]""", "wss://a.example")
        assertEquals(0, received.get())
    }

    @Test
    fun `onevent dedups across relays via knownIds`() = runTest {
        val received = CopyOnWriteArrayList<NostrEvent>()
        subscription.subscribe(
            urls = listOf("wss://a.example", "wss://b.example"),
            filter = NostrFilter(),
            onevent = { received.add(it) },
        )
        val subId = transport.sends.first().subId()
        val evt = sampleEventJson(id = "a".repeat(64))
        tapRegistry.fire("""["EVENT","$subId",$evt]""", "wss://a.example")
        tapRegistry.fire("""["EVENT","$subId",$evt]""", "wss://b.example")
        assertEquals(1, received.size)
    }

    @Test
    fun `oneose fires per relay with correct allEosed flag`() = runTest {
        val eoseEvents = CopyOnWriteArrayList<Boolean>()
        subscription.subscribe(
            urls = listOf("wss://a.example", "wss://b.example"),
            filter = NostrFilter(),
            onevent = {},
            oneose = { allEosed -> eoseEvents.add(allEosed) },
        )
        val subId = transport.sends.first().subId()
        tapRegistry.fire("""["EOSE","$subId"]""", "wss://a.example")
        tapRegistry.fire("""["EOSE","$subId"]""", "wss://b.example")
        assertEquals(2, eoseEvents.size)
        assertEquals(false, eoseEvents[0])  // 1 of 2
        assertEquals(true, eoseEvents[1])   // 2 of 2
    }

    @Test
    fun `repeated EOSE from same relay is idempotent`() = runTest {
        val eoseEvents = CopyOnWriteArrayList<Boolean>()
        subscription.subscribe(
            urls = listOf("wss://a.example"),
            filter = NostrFilter(),
            onevent = {},
            oneose = { eoseEvents.add(it) },
        )
        val subId = transport.sends.first().subId()
        tapRegistry.fire("""["EOSE","$subId"]""", "wss://a.example")
        tapRegistry.fire("""["EOSE","$subId"]""", "wss://a.example")
        assertEquals(1, eoseEvents.size)
        assertEquals(true, eoseEvents[0])
    }

    @Test
    fun `onclose fires with reason`() = runTest {
        val closes = CopyOnWriteArrayList<Pair<String, String>>()
        subscription.subscribe(
            urls = listOf("wss://a.example"),
            filter = NostrFilter(),
            onevent = {},
            onclose = { url, reason -> closes.add(url to reason) },
        )
        val subId = transport.sends.first().subId()
        tapRegistry.fire("""["CLOSED","$subId","auth-required: pubkey not whitelisted"]""", "wss://a.example")
        assertEquals(1, closes.size)
        assertEquals("wss://a.example", closes[0].first)
        assertEquals("auth-required: pubkey not whitelisted", closes[0].second)
    }

    @Test
    fun `onclose triggers implicit EOSE so allEosed completes`() = runTest {
        val eoseEvents = CopyOnWriteArrayList<Boolean>()
        subscription.subscribe(
            urls = listOf("wss://a.example", "wss://b.example"),
            filter = NostrFilter(),
            onevent = {},
            oneose = { eoseEvents.add(it) },
            onclose = { _, _ -> },
        )
        val subId = transport.sends.first().subId()
        tapRegistry.fire("""["EOSE","$subId"]""", "wss://a.example")
        tapRegistry.fire("""["CLOSED","$subId","relay maintenance"]""", "wss://b.example")
        assertEquals(2, eoseEvents.size)
        assertTrue("Last eose should report allEosed=true", eoseEvents.last())
    }

    @Test
    fun `Handle close sends CLOSE to each relay`() = runTest {
        val handle = subscription.subscribe(
            urls = listOf("wss://a.example", "wss://b.example"),
            filter = NostrFilter(),
            onevent = {},
        )
        val initialSends = transport.sends.size
        handle.close()
        val newSends = transport.sends.size - initialSends
        assertEquals(2, newSends)
        val closeMsgs = transport.sends.takeLast(2)
        for (sent in closeMsgs) {
            assertTrue(sent.msg.startsWith("[\"CLOSE\""))
        }
    }

    @Test
    fun `Handle close is idempotent`() = runTest {
        val handle = subscription.subscribe(
            urls = listOf("wss://a.example"),
            filter = NostrFilter(),
            onevent = {},
        )
        handle.close()
        val sendsAfterFirstClose = transport.sends.size
        handle.close()
        assertEquals(sendsAfterFirstClose, transport.sends.size)
    }

    @Test
    fun `events after close do not fire callbacks`() = runTest {
        val received = AtomicInteger(0)
        val handle = subscription.subscribe(
            urls = listOf("wss://a.example"),
            filter = NostrFilter(),
            onevent = { received.incrementAndGet() },
        )
        val subId = transport.sends.first().subId()
        handle.close()
        val evt = sampleEventJson(id = "a".repeat(64))
        tapRegistry.fire("""["EVENT","$subId",$evt]""", "wss://a.example")
        assertEquals(0, received.get())
    }

    @Test
    fun `unsent REQ to disconnected relay triggers immediate EOSE for that relay`() = runTest {
        transport.sendShouldSucceed = { url -> url != "wss://broken.example" }
        val eoseEvents = CopyOnWriteArrayList<Boolean>()
        subscription.subscribe(
            urls = listOf("wss://a.example", "wss://broken.example"),
            filter = NostrFilter(),
            onevent = {},
            oneose = { eoseEvents.add(it) },
        )
        // wss://broken counts as immediate EOSE
        assertEquals(1, eoseEvents.size)
        assertEquals(false, eoseEvents[0])  // 1 of 2
        // Now wss://a EOSEs naturally
        val subId = transport.sends.first().subId()
        tapRegistry.fire("""["EOSE","$subId"]""", "wss://a.example")
        assertEquals(2, eoseEvents.size)
        assertEquals(true, eoseEvents[1])
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun sampleEventJson(
        id: String,
        kind: Int = 1,
        content: String = "test note",
    ): String = """{"id":"$id","pubkey":"${"b".repeat(64)}","kind":$kind,"created_at":1700000000,"content":"$content","tags":[],"sig":"${"c".repeat(128)}"}"""

    private fun FakeRelayTransport.SentMessage.subId(): String {
        // Parse ["REQ","sub-id",{...}] to extract sub-id
        val afterFirst = msg.indexOf('"', msg.indexOf("REQ") + 3)
        val commaAfterReq = msg.indexOf(',', afterFirst)
        val nextQuote = msg.indexOf('"', commaAfterReq + 1)
        val closeQuote = msg.indexOf('"', nextQuote + 1)
        return msg.substring(nextQuote + 1, closeQuote)
    }
}
