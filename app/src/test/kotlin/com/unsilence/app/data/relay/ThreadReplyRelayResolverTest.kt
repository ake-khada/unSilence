package com.unsilence.app.data.relay

import org.junit.Assert.*
import org.junit.Test

class ThreadReplyRelayResolverTest {
    private val metadata = object : RelayMetadataSource {
        override fun readRelaysFor(pubkey: String) = (1..20).map { "wss://$pubkey-in$it.test" }
        override fun writeRelaysFor(pubkey: String) = (1..20).map { "wss://$pubkey-out$it.test" }
    }

    @Test fun `inbox budget is shared fairly across only three author roles`() {
        val targets = OutboxRelayResolver(metadata).resolveThreadReplyRelays(
            "root", listOf("tapped", "parent", "extra"),
            (1..10).map { "wss://own$it.test" }, (1..10).map { "wss://hint$it.test" },
        )
        assertEquals(MAX_THREAD_REPLY_RELAYS, targets.size)
        assertEquals(listOf("wss://root-in1.test", "wss://tapped-in1.test", "wss://parent-in1.test", "wss://root-in10.test"), targets.take(4))
        assertEquals(3, targets.count { "hint" in it })
        assertEquals(4, targets.count { "-out" in it })
        assertEquals(2, targets.count { "own" in it })
        assertFalse(targets.any { "extra" in it })
    }

    @Test fun `normalization blocking and duplicates are resolved before the inbox budget`() {
        val source = object : RelayMetadataSource {
            override fun writeRelaysFor(pubkey: String) = emptyList<String>()
            override fun readRelaysFor(pubkey: String) = listOf("ws://bad.test", "wss://blocked.test/", "wss://same.test/", "wss://same.test", "wss://good.test")
        }
        val targets = OutboxRelayResolver(source).resolveThreadReplyRelays(
            "root", listOf("root", "tapped"), listOf("wss://own.test"),
            listOf("wss://blocked.test", "wss://hint.test/"), setOf("wss://blocked.test/"),
        )
        assertEquals(listOf("wss://good.test", "wss://same.test", "wss://hint.test", "wss://own.test"), targets)
    }

    @Test fun `unknown authors retain bounded own read then global fallback without discovery`() {
        val resolver = OutboxRelayResolver(object : RelayMetadataSource {
            override fun writeRelaysFor(pubkey: String): List<String> = error("Unknown root has no author")
        })
        assertEquals(listOf("wss://own.test"), resolver.resolveThreadReplyRelays(null, emptyList(), listOf("wss://own.test"), emptyList()))
        assertEquals(GLOBAL_RELAY_URLS, resolver.resolveThreadReplyRelays(null, emptyList(), emptyList(), emptyList()))
        assertTrue(resolver.resolveThreadReplyRelays(null, emptyList(), emptyList(), emptyList(), GLOBAL_RELAY_URLS.toSet()).isEmpty())
    }
}
