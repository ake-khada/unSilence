package com.unsilence.app.data.repository

import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.normalizeRelayUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPublishTargetsTest {
    @Test
    fun `empty write relay set falls back to normalized globals`() {
        val targets = publishTargetsWithGlobalFallback(emptyList())

        assertEquals(GLOBAL_RELAY_URLS.mapNotNull(::normalizeRelayUrl).toSet(), targets)
    }

    @Test
    fun `invalid write relay set cannot suppress fallback`() {
        val targets = publishTargetsWithGlobalFallback(listOf("not a relay", ""))

        assertEquals(GLOBAL_RELAY_URLS.mapNotNull(::normalizeRelayUrl).toSet(), targets)
    }

    @Test
    fun `configured writes and additional indexers are normalized and deduplicated`() {
        val targets = publishTargetsWithGlobalFallback(
            writeRelays = listOf("WSS://relay.example/", "wss://relay.example"),
            additionalRelays = listOf("wss://indexer.example/"),
        )

        assertEquals(setOf("wss://relay.example", "wss://indexer.example"), targets)
        assertFalse(targets.any { it in GLOBAL_RELAY_URLS })
        assertTrue("wss://indexer.example" in targets)
    }
}
