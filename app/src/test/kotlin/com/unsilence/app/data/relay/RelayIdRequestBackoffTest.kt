package com.unsilence.app.data.relay

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RelayIdRequestBackoffTest {
    private val relay = "wss://lookup.example"
    private val owner = Any()
    private var now = 1_000L
    private val policy = RelayIdRequestBackoff(nowMs = { now })
    private var serial = 0
    private val sent = mutableListOf<String>()

    private fun req(id: String) = """["REQ","$id",{"ids":["event"],"kinds":[1,1111]}]"""

    private fun start(url: String = relay, socket: Any = owner): String {
        val id = "lookup-${++serial}"
        assertNull(policy.send(socket, url, req(id)) { sent += id; true })
        return id
    }

    private fun reject(reason: String = "", url: String = relay): Boolean {
        val id = start(url)
        return policy.onClosed(owner, id, reason)
    }

    @Test fun `third distinct empty CLOSED opens backoff at exact threshold`() {
        repeat(ID_REQUEST_FAILURE_THRESHOLD - 1) {
            assertFalse(reject())
            assertFalse(policy.shouldSkip(relay))
        }
        assertTrue(reject())
        assertTrue(policy.shouldSkip(relay))
        assertEquals(OneShotOutcome.SKIPPED, policy.send(owner, relay, req("blocked")) {
            fail("Backed-off ID REQ must never reach the wire")
            true
        })
        assertEquals(3, sent.size)
    }

    @Test fun `thirty sequential rejecting lookups send three REQs not thirty`() {
        repeat(30) { index ->
            val subId = "burst-$index"
            val outcome = policy.send(owner, relay, req(subId)) { sent += subId; true }
            if (outcome == null) policy.onClosed(owner, subId, "")
        }
        assertEquals(3, sent.size)
        val stats = policy.snapshotMetrics()
        assertEquals(3L, stats.sent)
        assertEquals(27L, stats.skipped)
        assertEquals(1L, stats.opened)
        assertEquals(1, stats.activeRelays)
    }

    @Test fun `expiry admits a retry without needing restart or unrelated traffic`() {
        repeat(3) { reject() }
        now += ID_REQUEST_BACKOFF_MS - 1
        assertTrue(policy.shouldSkip(relay))
        now++
        assertFalse(policy.shouldSkip(relay))
        start()
        assertFalse(reject())
        assertFalse(policy.shouldSkip(relay))
    }

    @Test fun `outstanding rejections do not extend an already open window`() {
        val inFlight = (1..5).map { start() }
        inFlight.take(3).forEach { policy.onClosed(owner, it, "") }
        now += 10_000
        inFlight.drop(3).forEach { assertFalse(policy.onClosed(owner, it, "")) }
        now += ID_REQUEST_BACKOFF_MS - 10_000
        assertFalse(policy.shouldSkip(relay))
    }

    @Test fun `long idle resumes with no pause or stale outstanding observations`() {
        val old = start()
        repeat(3) { reject() }
        now += 8 * 60 * 60_000L
        assertFalse(policy.shouldSkip(relay))
        assertFalse(policy.onClosed(owner, old, ""))
        repeat(2) { assertFalse(reject()) }
        assertTrue(reject())
    }

    @Test fun `old partial streak expires before the next rejection`() {
        repeat(2) { reject() }
        now += ID_REQUEST_FAILURE_WINDOW_MS
        assertFalse(reject())
        assertFalse(policy.shouldSkip(relay))
        assertFalse(reject())
        assertTrue(reject())
    }

    @Test fun `real ID EOSE with no events clears a partial streak`() {
        repeat(2) { reject() }
        policy.onEose(owner, start())
        repeat(2) { assertFalse(reject()) }
        assertFalse(policy.shouldSkip(relay))
        assertTrue(reject())
    }

    @Test fun `real EOSE from an already sent ID request heals an open backoff`() {
        val accepted = start()
        repeat(3) { reject() }
        assertTrue(policy.shouldSkip(relay))
        policy.onEose(owner, accepted)
        assertFalse(policy.shouldSkip(relay))
        assertFalse(reject())
    }

    @Test fun `duplicate CLOSED and late EOSE after CLOSED cannot change the streak`() {
        val id = start()
        repeat(10) { assertFalse(policy.onClosed(owner, id, "")) }
        policy.onEose(owner, id)
        assertFalse(reject())
        assertTrue(reject())
    }

    @Test fun `nonempty CLOSED reasons neither strike nor reset ID backoff`() {
        repeat(2) { reject() }
        for (reason in listOf(
            "auth-required: sign in", "rate-limited: wait", "rate-limit: wait",
            "blocked: filter rejected", "error: unsupported", "restricted: private",
            "too many concurrent subscriptions", "unknown relay text", " ",
        )) {
            assertFalse(reject(reason))
        }
        assertTrue(reject())
    }

    @Test fun `other relays remain eligible and cannot clear this relay's failure`() {
        repeat(3) { reject() }
        val other = "wss://other.example"
        assertFalse(policy.shouldSkip(other))
        policy.onEose(owner, start(other))
        assertTrue(policy.shouldSkip(relay))
    }

    @Test fun `canonical URL variants share a threshold`() {
        assertFalse(reject(url = "lookup.example/"))
        assertFalse(reject(url = relay))
        assertTrue(reject(url = "$relay/"))
        assertTrue(policy.shouldSkip("lookup.example"))
    }

    @Test fun `retired socket callbacks cannot reject or heal its successor`() {
        val old = start()
        policy.forgetOwner(owner)
        val replacement = Any()
        repeat(3) {
            val id = start(socket = replacement)
            policy.onClosed(replacement, id, "")
        }
        assertFalse(policy.onClosed(owner, old, ""))
        policy.onEose(owner, old)
        assertTrue(policy.shouldSkip(relay))
    }

    @Test fun `identical subids on different sockets retain separate ownership`() {
        val other = Any()
        assertNull(policy.send(owner, relay, req("same")) { true })
        assertNull(policy.send(other, relay, req("same")) { true })
        assertFalse(policy.onClosed(owner, "same", ""))
        assertFalse(policy.onClosed(owner, "same", ""))
        assertFalse(policy.onClosed(other, "same", ""))
        assertTrue(reject())
    }

    @Test fun `timeout and cancellation discard observations without teaching a strike or success`() {
        repeat(2) { reject() }
        val cancelled = start()
        policy.forgetSubscription(cancelled)
        policy.onEose(owner, cancelled)
        assertFalse(policy.onClosed(owner, cancelled, ""))
        val timeout = start()
        now += ID_REQUEST_TRACKING_MS
        policy.onEose(owner, timeout)
        assertFalse(policy.onClosed(owner, timeout, ""))
        assertTrue(reject())
    }

    @Test fun `failed sends never teach rejection or success`() {
        repeat(2) { reject() }
        assertEquals(OneShotOutcome.FAILED, policy.send(owner, relay, req("failed")) { false })
        assertFalse(policy.onClosed(owner, "failed", ""))
        policy.onEose(owner, "failed")
        assertTrue(reject())
    }

    @Test fun `logout clear drops both failures and outstanding observations`() {
        val stale = start()
        repeat(3) { reject() }
        policy.clear()
        assertFalse(policy.shouldSkip(relay))
        assertFalse(policy.onClosed(owner, stale, ""))
        repeat(2) { assertFalse(reject()) }
    }

    @Test fun `fresh process has no inherited backoff state`() {
        repeat(3) { reject() }
        assertTrue(policy.shouldSkip(relay))
        assertFalse(RelayIdRequestBackoff(nowMs = { now }).shouldSkip(relay))
    }

    @Test fun `failure state stays bounded under many rejecting relays`() {
        val bounded = RelayIdRequestBackoff(nowMs = { now }, maxRelays = 2)
        for (index in 1..3) {
            repeat(3) { attempt ->
                val id = "relay-$index-$attempt"
                assertNull(bounded.send(owner, "wss://r$index.example", req(id)) { true })
                bounded.onClosed(owner, id, "")
            }
        }
        assertEquals(2, bounded.snapshotMetrics().activeRelays)
        assertFalse(bounded.shouldSkip("wss://r1.example"))
        assertTrue(bounded.shouldSkip("wss://r2.example"))
        assertTrue(bounded.shouldSkip("wss://r3.example"))
    }

    @Test fun `outstanding observations are bounded even if relays never finish`() {
        val bounded = RelayIdRequestBackoff(nowMs = { now }, maxPending = 2)
        for (id in listOf("old", "new1", "new2")) {
            assertNull(bounded.send(owner, relay, req(id)) { true })
        }
        assertFalse(bounded.onClosed(owner, "old", ""))
        assertFalse(bounded.onClosed(owner, "new1", ""))
        assertFalse(bounded.onClosed(owner, "new2", ""))
        assertFalse(bounded.shouldSkip(relay))
        assertNull(bounded.send(owner, relay, req("third")) { true })
        assertTrue(bounded.onClosed(owner, "third", ""))
    }

    @Test fun `concurrent terminal callbacks open one window without lost counts`() {
        val ids = (1..20).map { start() }
        val start = CountDownLatch(1)
        val done = CountDownLatch(ids.size)
        val opened = AtomicInteger()
        ids.forEach { id ->
            Thread {
                try {
                    start.await()
                    if (policy.onClosed(owner, id, "")) opened.incrementAndGet()
                } finally { done.countDown() }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, opened.get())
        assertTrue(policy.shouldSkip(relay))
    }

    @Test fun `metrics reset counters but do not clear cooldown`() {
        repeat(3) { reject() }
        val first = policy.snapshotMetrics()
        assertEquals(3L, first.sent)
        assertEquals(1L, first.opened)
        val next = policy.snapshotMetrics()
        assertEquals(0L, next.sent)
        assertEquals(0L, next.opened)
        assertEquals(1, next.activeRelays)
        now += ID_REQUEST_BACKOFF_MS
        assertEquals(0, policy.snapshotMetrics().activeRelays)
    }
}
