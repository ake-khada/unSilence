package com.unsilence.app.data.relay

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.concurrent.thread

class RelayTrafficMetricsTest {
    @Test fun `concurrent counts and repeated snapshots never reset`() {
        val metrics = RelayTrafficMetrics()
        val workers = List(4) { thread { repeat(1000) { metrics.eventFrameReceived() } } }
        workers.forEach { it.join() }
        assertEquals(4000L, metrics.snapshot()["event_frames"])
        assertEquals(metrics.snapshot(), metrics.snapshot())
        metrics.reqAccepted()
        metrics.replayReqAccepted()
        metrics.subscriptionDuplicate()
        metrics.processorDuplicate()
        assertEquals(1L, metrics.snapshot()["req_accepted"])
        assertEquals(1L, metrics.snapshot()["replay_req_accepted"])
        assertEquals(1L, metrics.snapshot()["subscription_duplicates"])
        assertEquals(1L, metrics.snapshot()["processor_duplicates"])
    }

    @Test fun `replay metrics count only accepted sends and preserve exact payloads`() = runTest {
        val transport = FakeRelayTransport()
        val taps = FakeTapRegistration()
        val metrics = RelayTrafficMetrics()
        val subscription = Subscription(transport, taps, FakeReconnectSource(), FakeRelayCapabilitiesStore(), metrics)
        val urls = listOf("wss://a.example", "wss://b.example")
        val handle = subscription.subscribe(urls, NostrFilter(kinds = listOf(1), since = 123, limit = 42), {})
        try {
            val original = transport.sends.first().msg
            assertEquals(0L, metrics.snapshot()["replay_req_accepted"])
            subscription.pauseAll()
            transport.sendShouldSucceed = { it == urls[0] }
            subscription.resumeAll()
            assertEquals(1L, metrics.snapshot()["replay_req_accepted"])
            transport.sendShouldSucceed = { true }
            subscription.resumeRelay(urls[1], nowMs = 10000)
            assertEquals(2L, metrics.snapshot()["replay_req_accepted"])
            subscription.resumeRelay(urls[1], nowMs = 10001)
            assertEquals(2L, metrics.snapshot()["replay_req_accepted"])
            assertTrue(transport.sends.filter { it.msg.startsWith("[\"REQ\"") }.all { it.msg == original })
        } finally { handle.close() }
    }
}
