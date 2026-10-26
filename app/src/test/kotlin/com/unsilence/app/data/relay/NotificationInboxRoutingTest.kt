package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.RelayList
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotificationInboxRoutingTest {
    @Test fun `live dispatch includes read-only inbox first and replays the exact request`() {
        val f = Fixture()
        f.reads = listOf("wss://inbox.example/", "wss://both.example/")
        f.writes = listOf("both.example", "wss://outbox.example")
        f.registered = setOf("wss://inbox.example", "wss://both.example", "wss://outbox.example")
        assertEquals(3, f.routing.subscribe("account", REQUEST))
        assertEquals(f.registered.toList(), f.sent.map { it.first })
        assertTrue(f.sent.all { it.second == REQUEST })
        f.sent.clear()
        f.reconnect("wss://inbox.example")
        f.reconnect("wss://outbox.example")
        f.reconnect("wss://unrelated.example")
        assertEquals(listOf("wss://inbox.example" to REQUEST, "wss://outbox.example" to REQUEST), f.sent)
        f.routing.close()
        f.sent.clear()
        f.reconnect("wss://inbox.example")
        assertTrue(f.sent.isEmpty())
    }

    @Test fun `backfill dispatch prioritizes declared inbox and retains connected non-indexer sweep in sixes`() = runTest {
        val f = Fixture()
        f.reads = listOf("inbox.example", "wss://both.example/")
        f.writes = listOf("both.example", "wss://outbox.example", "wss://declared-indexer.example")
        f.connected = listOf("wss://indexer.example") + (1..8).map { "wss://other$it.example" }
        f.indexers = listOf("indexer.example/", "declared-indexer.example")
        f.registered = (f.connected + listOf("wss://inbox.example", "wss://both.example", "wss://outbox.example", "wss://declared-indexer.example")).toSet()
        val batches = mutableListOf<List<String>>()
        assertEquals(12, f.routing.backfill("account") { batches += it })
        assertEquals(listOf(6, 6), batches.map { it.size })
        assertEquals(listOf("wss://inbox.example", "wss://both.example", "wss://outbox.example", "wss://declared-indexer.example") + f.connected.drop(1), batches.flatten())
        // Existing declared write targets remain even if also configured as indexers.
    }

    @Test fun `live fallback remains four connected relays and omits unregistered declarations`() {
        val f = Fixture()
        f.reads = listOf("unregistered.example", "ws://cleartext.example", "not a relay")
        f.writes = listOf("missing.example")
        f.connected = (1..6).map { "wss://fallback$it.example" }
        assertEquals(4, f.routing.subscribe("account", REQUEST))
        assertEquals(f.connected.take(4), f.sent.map { it.first })
        f.sent.clear()
        f.reconnect(f.connected[0])
        f.reconnect(f.connected[4])
        assertEquals(listOf(f.connected[0] to REQUEST), f.sent)
    }

    @Test fun `empty routing sends nothing and backfill still sweeps when declarations are missing`() = runTest {
        val f = Fixture()
        assertEquals(0, f.routing.subscribe("account", REQUEST))
        assertEquals(0, f.routing.backfill("account") { fail("no batch expected") })
        f.connected = listOf("wss://other.example")
        val batches = mutableListOf<List<String>>()
        assertEquals(1, f.routing.backfill("account") { batches += it })
        assertEquals(listOf(f.connected), batches)
    }

    @Test fun `close clears old account replay before new account subscribes`() {
        val f = Fixture()
        f.reads = listOf("wss://old.example"); f.registered = f.reads.toSet()
        assertEquals(1, f.routing.subscribe("old", REQUEST))
        f.routing.close()
        assertEquals("""["CLOSE","own-notif-live"]""", f.sent.last().second)
        f.reads = listOf("wss://new.example"); f.registered = f.reads.toSet()
        f.routing.subscribe("new", "new request")
        f.sent.clear()
        f.reconnect("wss://old.example")
        f.reconnect("wss://new.example")
        assertEquals(listOf("wss://new.example" to "new request"), f.sent)
    }

    @Test fun `write-only accounts retain every declared target without using fallback`() {
        val f = Fixture()
        f.writes = (1..7).map { "wss://write$it.example" }
        f.registered = f.writes.toSet()
        f.connected = listOf("wss://fallback.example")
        assertEquals(7, f.routing.subscribe("account", REQUEST))
        assertEquals(f.writes, f.sent.map { it.first })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `cancelled backfill cannot dispatch a later batch`() = runTest {
        val f = Fixture()
        f.connected = (1..14).map { "wss://other$it.example" }
        val batches = mutableListOf<List<String>>()
        val job = launch {
            f.routing.backfill("account") { batch ->
                batches += batch
                awaitCancellation()
            }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(listOf(f.connected.take(6)), batches)
    }

    @Test fun `read-only inbox missing at subscribe time receives live request when it connects`() {
        val f = Fixture()
        f.reads = listOf("wss://late.example")
        assertEquals(0, f.routing.subscribe("account", REQUEST))
        assertTrue(f.sent.isEmpty())
        f.registered = f.reads.toSet()
        f.connected = f.reads
        f.reconnect("wss://late.example")
        assertEquals(listOf("wss://late.example" to REQUEST), f.sent)
    }

    @Test fun `replay uses current declaration instead of an old captured inbox set`() {
        val f = Fixture()
        f.reads = listOf("wss://old.example")
        f.registered = setOf("wss://old.example", "wss://new.example")
        f.routing.subscribe("account", REQUEST)
        f.reads = listOf("wss://new.example")
        f.sent.clear()
        f.reconnect("wss://old.example")
        f.reconnect("wss://new.example")
        assertEquals(listOf("wss://new.example" to REQUEST), f.sent)
    }

    @Test fun `each live or history decision reads one coherent relay-list snapshot`() = runTest {
        val before = RelayList(listOf("wss://read-before.example"), listOf("wss://write-before.example"))
        val after = RelayList(listOf("wss://read-after.example"), listOf("wss://write-after.example"))
        var current = before
        var reads = 0
        val sent = mutableListOf<String>()
        val routing = NotificationInboxRouting(
            relayListFor = { current.also { reads++; current = after } },
            registeredUrls = { (before.read + before.write + after.read + after.write).toSet() },
            connectedUrls = { emptyList() }, indexerUrls = { emptyList() },
            send = { url, _ -> sent += url; Any() },
        )
        routing.subscribe("account", REQUEST)
        assertEquals(1, reads)
        assertEquals(before.read + before.write, sent)
        val history = mutableListOf<String>()
        routing.backfill("account") { history += it }
        assertEquals(2, reads)
        assertEquals(after.read + after.write, history)
    }

    @Test fun `fallback replay stays pinned when connected relay ordering changes`() {
        val f = Fixture()
        val original = (1..6).map { "wss://fallback$it.example" }
        f.connected = original
        f.routing.subscribe("account", REQUEST)
        f.sent.clear()
        f.connected = original.reversed()
        f.reconnect(original[0])
        f.reconnect(original[4])
        assertEquals(listOf(original[0] to REQUEST), f.sent)
    }

    private class Fixture {
        var reads = emptyList<String>(); var writes = emptyList<String>()
        var registered = emptySet<String>(); var connected = emptyList<String>(); var indexers = emptyList<String>()
        val sent = mutableListOf<Pair<String, String>>()
        private val owners = mutableMapOf<String, Any>()
        val routing = NotificationInboxRouting({ RelayList(reads, writes) }, { registered }, { connected }, { indexers }) { url, req ->
            sent += url to req
            owners.getOrPut(url) { Any() }
        }
        fun reconnect(url: String) {
            val owner = Any()
            owners[url] = owner
            routing.replay(url, owner)
        }
    }
    private companion object {
        const val REQUEST = """["REQ","own-notif-live",{"kinds":[1,6,7,9735],"#p":["account"],"since":1234}]"""
    }
}
