package com.unsilence.app.ui.compose

import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposePublishRelayPolicyTest {

    @Test
    fun `settled relay list uses configured write relays`() {
        val configured = listOf("wss://nos.lol", "wss://nostr.mom")

        assertEquals(
            configured,
            resolveComposePublishRelays(
                configuredWriteRelays = configured,
                relayListSettled = true,
                fallbackRelays = GLOBAL_RELAY_URLS,
            ),
        )
    }

    @Test
    fun `settled empty relay list uses global fallback`() {
        assertEquals(
            GLOBAL_RELAY_URLS,
            resolveComposePublishRelays(
                configuredWriteRelays = emptyList(),
                relayListSettled = true,
                fallbackRelays = GLOBAL_RELAY_URLS,
            ),
        )
    }

    @Test
    fun `unsettled empty relay list never uses global fallback`() {
        assertTrue(
            resolveComposePublishRelays(
                configuredWriteRelays = emptyList(),
                relayListSettled = false,
                fallbackRelays = GLOBAL_RELAY_URLS,
            ).isEmpty(),
        )
    }

    @Test
    fun `unsettled relay list uses known configured relays after bounded wait`() {
        val configured = listOf("wss://nos.lol")

        assertEquals(
            configured,
            resolveComposePublishRelays(
                configuredWriteRelays = configured,
                relayListSettled = false,
                fallbackRelays = GLOBAL_RELAY_URLS,
            ),
        )
    }
}
