package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDemandPolicyTest {
    @Test
    fun `profile network demand stops while network is unavailable`() {
        assertFalse(profileNetworkDemandAllowed(NetworkState.OFFLINE, isNetworkDown = true))
        assertFalse(profileNetworkDemandAllowed(NetworkState.UNKNOWN, isNetworkDown = false))
        assertFalse(profileNetworkDemandAllowed(NetworkState.ONLINE, isNetworkDown = true))
        assertTrue(profileNetworkDemandAllowed(NetworkState.ONLINE, isNetworkDown = false))
    }

    @Test
    fun `engagement relay selection prioritizes configured then source coverage`() {
        val selected = selectProfileEngagementRelays(
            preferredRelays = listOf("wss://index.example", "wss://write.example"),
            sourceRelaysByEvent = listOf(
                listOf("wss://popular.example", "wss://rare.example"),
                listOf("wss://popular.example"),
                listOf("wss://popular.example", "wss://second.example"),
                listOf("wss://second.example"),
            ),
            maxRelays = 4,
        )

        assertEquals(
            listOf(
                "wss://index.example",
                "wss://write.example",
                "wss://popular.example",
                "wss://second.example",
            ),
            selected,
        )
    }

    @Test
    fun `engagement relay selection is deterministic deduped and capped`() {
        val selected = selectProfileEngagementRelays(
            preferredRelays = listOf("wss://a.example/", "wss://a.example"),
            sourceRelaysByEvent = listOf(
                listOf("wss://c.example", "wss://b.example", "wss://b.example"),
            ),
            maxRelays = 2,
        )

        assertEquals(listOf("wss://a.example", "wss://b.example"), selected)
    }
}
