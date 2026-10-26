package com.unsilence.app.data.relay

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubscriptionCompletionTest {
    private val url = "wss://a.example"
    private val transport = FakeRelayTransport()
    private val taps = FakeTapRegistration()
    private val caps = FakeRelayCapabilitiesStore()
    private val sub = Subscription(transport, taps, FakeReconnectSource(), caps)
    private fun id() = NostrJson.parseToJsonElement(transport.sends.last {
        it.msg.startsWith("[\"REQ\"")
    }.msg).jsonArray[1].jsonPrimitive.content
    private fun event(id: String, time: Long = 100) {
        val e = EventDto(id = "a".repeat(64), pubkey = "b".repeat(64), createdAt = time,
            kind = 1, tags = emptyList(), content = "", sig = "c".repeat(128)).toNostrEvent(url)
        taps.registrations.forEach { it.onMessage(RelayTapMessage.VerifiedEvent(id, e)) }
    }

    @Test fun `replay callback precedes provider and failed send does not freeze cursor or bypass debounce`() = runTest {
        val order = mutableListOf<String>()
        var head = 100L
        sub.subscribe(listOf(url), NostrFilter(), {},
            onReplayCycleStarted = { order += "callback" },
            replayFilterProvider = { order += "provider:$head"; NostrFilter(since = head) })
        val initialId = id()
        taps.fire("""["EOSE","$initialId"]""", url)
        transport.sendShouldSucceed = { order += "send"; false }
        sub.resumeRelay(url, 1000)
        assertEquals(listOf("callback", "provider:100", "send"), order)
        order.clear(); head = 200
        sub.resumeRelay(url, 1500)
        assertTrue(order.isEmpty())
        transport.sendShouldSucceed = { order += "send"; true }
        sub.resumeRelay(url, 2000)
        assertEquals(listOf("callback", "provider:200", "send"), order)
        assertTrue(transport.sends.last().msg.contains("\"since\":200"))
        assertEquals(initialId, id())
        order.clear(); head = 300
        sub.pauseAll(); order.clear(); sub.resumeAll()
        assertEquals(listOf("callback", "provider:300", "send"), order)
        sub.resetForTest()
    }

    @Test fun `capability gate and non replayable pages never evaluate provider`() = runTest {
        var calls = 0
        sub.subscribe(listOf(url), NostrFilter(), {}, replayFilterProvider = { calls++; NostrFilter() })
        caps.markSkipped(url)
        sub.pauseAll(); sub.resumeAll(); sub.resumeRelay(url, 1000)
        assertEquals(0, calls)
        sub.resetForTest()
        sub.subscribe(listOf("wss://b.example"), NostrFilter(since = 100, until = 200), {},
            replayable = false, replayFilterProvider = { calls++; NostrFilter() })
        sub.pauseAll(); sub.resumeAll(); sub.resumeRelay("wss://b.example", 2000)
        assertEquals(0, calls)
        sub.resetForTest()
    }

    @Test fun `resumeAll notifies once before frames even if sockets reconnect later`() = runTest {
        var cycles = 0
        sub.subscribe(listOf(url), NostrFilter(limit = 300), {}, onReplayCycleStarted = { cycles++ })
        taps.fire("""["EOSE","${id()}"]""", url)
        sub.pauseAll()
        transport.sendShouldSucceed = { false }
        sub.resumeAll()
        assertEquals(1, cycles)
        transport.sendShouldSucceed = { true }
        transport.onSend = { _, raw -> if (raw.startsWith("[\"REQ\"")) assertEquals(1, cycles) }
        sub.resumeRelay(url, 1000)
        assertEquals(1, cycles)
        sub.resetForTest()
    }

    @Test fun `quiescence is synthetic and a late EOSE upgrades without another display completion`() = runTest {
        sub.watchdogDispatcher = StandardTestDispatcher(testScheduler)
        sub.elapsedRealtime = { testScheduler.currentTime + 1 }
        val reasons = mutableListOf<RelayCompletion>()
        val displays = mutableListOf<Boolean>()
        sub.subscribe(listOf(url), NostrFilter(limit = 300), {}, displays::add, onRelayCompletion = reasons::add)
        event(id()); runCurrent()
        advanceTimeBy(2501); runCurrent()
        assertEquals(listOf(RelayCompletionReason.Watchdog), reasons.map { it.reason })
        assertEquals(listOf(true), displays)
        assertTrue(caps.successfulRequests.isEmpty())
        taps.fire("""["EOSE","${id()}"]""", url)
        taps.fire("""["EOSE","${id()}"]""", url)
        assertEquals(listOf(RelayCompletionReason.Watchdog, RelayCompletionReason.Genuine), reasons.map { it.reason })
        assertEquals(listOf(true), displays)
        assertEquals(listOf(url), caps.successfulRequests)
        sub.resetForTest()
    }

    @Test fun `thirty second ceiling remains synthetic with recent traffic`() = runTest {
        sub.watchdogDispatcher = StandardTestDispatcher(testScheduler)
        var clock = 1L
        sub.elapsedRealtime = { clock }
        val reasons = mutableListOf<RelayCompletion>()
        sub.subscribe(listOf(url), NostrFilter(limit = 300), {}, onRelayCompletion = reasons::add)
        runCurrent()
        clock = 29_999
        event(id())
        clock = 30_001
        advanceTimeBy(1251); runCurrent()
        assertEquals(RelayCompletionReason.Watchdog, reasons.single().reason)
        assertEquals(1, reasons.single().count)
        sub.resetForTest()
    }

    @Test fun `duplicates count toward each replay page but onevent stays deduplicated`() = runTest {
        var delivered = 0
        val pages = mutableListOf<RelayCompletion>()
        sub.subscribe(listOf(url), NostrFilter(limit = 3), { delivered++ }, onRelayCompletion = pages::add)
        repeat(3) { event(id()) }
        taps.fire("""["EOSE","${id()}"]""", url)
        sub.pauseAll(); sub.resumeAll()
        repeat(3) { event(id()) }
        taps.fire("""["EOSE","${id()}"]""", url)
        assertEquals(listOf(3, 3), pages.map { it.count })
        assertEquals(1, delivered)
        sub.resetForTest()
    }

    @Test fun `CLOSED and skipped dispatch release allEosed without certifying coverage`() = runTest {
        val pages = mutableListOf<RelayCompletion>()
        val displays = mutableListOf<Boolean>()
        sub.subscribe(listOf(url), NostrFilter(), {}, displays::add, onRelayCompletion = pages::add)
        taps.fire("""["CLOSED","${id()}",""]""", url)
        caps.markSkipped(url)
        sub.subscribe(listOf(url), NostrFilter(), {}, displays::add, onRelayCompletion = pages::add)
        assertEquals(listOf(RelayCompletionReason.Closed, RelayCompletionReason.Skipped), pages.map { it.reason })
        assertEquals(listOf(true, true), displays)
        assertTrue(caps.successfulRequests.isEmpty())
        sub.resetForTest()
    }

    @Test fun `temporary catch up subscriptions are not resurrected by resume`() = runTest {
        sub.subscribe(listOf(url), NostrFilter(), {}, replayable = false)
        sub.pauseAll(); sub.resumeAll(); sub.resumeRelay(url, 1000)
        assertEquals(1, transport.sends.count { it.msg.startsWith("[\"REQ\"") })
        assertTrue(sub.activeSubIds().isEmpty())
        sub.resetForTest()
    }
}
