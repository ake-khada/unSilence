package com.unsilence.app.data.relay

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.auth.SignatureVerifier
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real signature verification and real drainers; only the small cache is expired explicitly. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventProcessorPendingReplayTest {
    private val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
    private val checks = AtomicInteger()
    private val processor = EventProcessor(store, object : SignatureVerifier() {
        override fun verify(event: NostrEvent): Boolean {
            checks.incrementAndGet()
            return super.verify(event)
        }
    })
    private val connections = mutableListOf<RelayConnection>()
    private val delivered = mutableListOf<RelayTapMessage.VerifiedEvent>()
    private val crypto = ZapCryptoTestFixture()
    private lateinit var event: EventDto

    private suspend fun TestScope.start() {
        event = crypto.signedEvent(crypto.sender, 1, emptyList(), "Verified pending note")
        processor.setTestScope(backgroundScope)
        processor.registerTap(RelayMessageTap { if (it is RelayTapMessage.VerifiedEvent) delivered += it })
        processor.start()
    }
    private fun connection(name: String, kind: Int = 1): RelayConnection =
        AdmissionSocket().connection("wss://$name.example").also {
            connections += it
            assertTrue(it.send("""["REQ","$name",{"kinds":[$kind]}]"""))
        }
    private fun wire(id: String, dto: EventDto = event) = """["EVENT","$id",${NostrJson.encodeToString(dto)}]"""
    private fun expireSmallVerifiedCache() {
        val field = EventProcessor::class.java.getDeclaredField("verifiedEvents").apply { isAccessible = true }
        (field.get(processor) as MutableMap<*, *>).clear()
    }
    @After fun stop() { processor.stop(); connections.forEach { it.close(); it.messages.cancel() } }

    @Test fun `pending cache miss delivers to overlapping subscriptions without repeating crypto`() = runTest {
        start()
        val first = connection("first")
        val second = connection("second")
        assertTrue(processor.process(wire("first"), first))
        assertEquals(1, processor.pendingContentCount)
        assertNull(store.getNostrEvent(event.id))
        repeat(100) {
            expireSmallVerifiedCache() // Controlled repeated cache misses, not a phone workload estimate.
            assertTrue(processor.process(wire("second"), second))
        }
        assertEquals("A verified queued payload must not repeat decode/signature work", 1, checks.get())
        assertEquals(101, delivered.size)
        assertEquals(setOf("first", "second"), delivered.map { it.subscriptionId }.toSet())
        runCurrent()
        assertEquals(event.content, store.getNostrEvent(event.id)!!.content)
        assertEquals(setOf(first.url, second.url), store.getNostrEvent(event.id)!!.relaysSeen)
        assertEquals(0, processor.pendingContentCount)
    }

    @Test fun `reuse delivers only canonical verified fields despite a changed incoming envelope`() = runTest {
        start()
        val first = connection("first")
        val second = connection("second")
        assertTrue(processor.process(wire("first"), first))
        expireSmallVerifiedCache()
        val forgedCopy = event.copy(content = "Unverified replacement", sig = "00".repeat(64))
        assertTrue(processor.process(wire("second", forgedCopy), second))
        assertEquals(1, checks.get())
        assertEquals(listOf(event.content, event.content), delivered.map { it.event.content })
        assertTrue(delivered.all { it.event.sig == event.sig && it.event.pubkey == event.pubkey && it.event.tags == event.tags })
        runCurrent()
        assertEquals(event.content, store.getNostrEvent(event.id)!!.content)
    }

    @Test fun `pending payload still respects changed filters and closed socket ownership`() = runTest {
        start()
        val first = connection("first")
        assertTrue(processor.process(wire("first"), first))
        expireSmallVerifiedCache()
        val other = connection("other", kind = 7)
        assertFalse(processor.process(wire("other"), other))
        assertTrue(first.send("""["REQ","first",{"authors":["${"00".repeat(32)}"]}]"""))
        assertFalse(processor.process(wire("first"), first))
        other.close()
        assertFalse(processor.process(wire("other"), other))
        assertEquals(1, checks.get())
        assertEquals(1, delivered.size)
    }

    @Test fun `released and evicted payload needs verification again then restores exactly once`() = runTest {
        start()
        val conn = connection("first")
        assertTrue(processor.process(wire("first"), conn))
        runCurrent()
        assertEquals(0, processor.pendingContentCount)
        expireSmallVerifiedCache()
        store.clear()
        assertTrue(processor.process(wire("first"), conn))
        assertEquals(2, checks.get())
        runCurrent()
        assertEquals(1, store.eventsByIds(setOf(event.id)).size)
        assertEquals(0, processor.pendingContentCount)
    }

    @Test fun `stop drops pending proof and a new processing session verifies again`() = runTest {
        start()
        val conn = connection("first")
        assertTrue(processor.process(wire("first"), conn))
        expireSmallVerifiedCache()
        processor.stop()
        assertEquals(0, processor.pendingContentCount)
        processor.setTestScope(backgroundScope)
        processor.start()
        assertTrue(processor.process(wire("first"), conn))
        assertEquals(2, checks.get())
    }
}
