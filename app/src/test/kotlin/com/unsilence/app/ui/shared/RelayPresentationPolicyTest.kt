package com.unsilence.app.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPresentationPolicyTest {

    @Test
    fun `provenance normalizes deduplicates and sorts relay hosts`() {
        val result = relayProvenanceItems(
            listOf(
                "wss://z.example/",
                "A.example",
                "wss://z.example",
                "wss://invalid",
                "ws://clear.example",
            ),
            iconUrlFor = { url -> if (url == "wss://z.example") "https://z.example/icon.png" else null },
        )

        assertEquals(
            listOf("wss://A.example", "wss://z.example"),
            result.map { it.url },
        )
        assertEquals(listOf("A.example", "z.example"), result.map { it.host })
        assertEquals(listOf(null, "https://z.example/icon.png"), result.map { it.iconUrl })
    }

    @Test
    fun `zero valid relays produces no provenance row`() {
        assertTrue(relayProvenanceItems(emptyList()).isEmpty())
        assertTrue(relayProvenanceItems(listOf("", "localhost")).isEmpty())
    }

    @Test
    fun `display host preserves relay paths while removing transport chrome`() {
        assertEquals("relay.example/tenant", relayDisplayHost("wss://relay.example/tenant/"))
    }

    @Test
    fun `device relay icon overrides monitor seed and blanks are ignored`() {
        val result = resolveRelayIconUrls(
            relayUrls = listOf("wss://one.example", "wss://two.example", "wss://three.example"),
            monitorIcons = mapOf(
                "wss://one.example" to "https://monitor.example/one.png",
                "wss://two.example" to "https://monitor.example/two.png",
                "wss://three.example" to "",
            ),
            deviceIcons = mapOf(
                "wss://one.example" to "https://relay.example/one.png",
                "wss://two.example" to "",
            ),
        )

        assertEquals("https://relay.example/one.png", result["wss://one.example"])
        assertEquals("https://monitor.example/two.png", result["wss://two.example"])
        assertEquals(false, result.containsKey("wss://three.example"))
    }
}
