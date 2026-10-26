package com.unsilence.app.ui.feed

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.auth.SignatureVerifier
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import com.unsilence.app.ui.compose.resolveComposePublishRelays
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OriginalEventRecipientRoutingTest {
    @Test fun `deletion retains own fallback and applies recipient limits and blocking`() = runTest {
        val crypto = ZapCryptoTestFixture()
        val original = crypto.signedEvent(crypto.sender, 1,
            listOf(listOf("p", crypto.recipient.pubkey), listOf("p", crypto.recipient.pubkey)), "")
            .toNostrEvent("local")
        val inboxes = (1..7).map { "wss://inbox$it.example" }
        val result = originalEventDeletionRelays(original, emptyList(), { inboxes }, setOf("wss://inbox1.example/"))
        assertEquals(com.unsilence.app.data.relay.GLOBAL_RELAY_URLS + inboxes.drop(1).take(4), result)
        assertFalse(buildDeletionRequestTags(listOf(original)).any { it.firstOrNull() == "p" })
    }

    @Test fun `signed compose and original deletion reach same cached verified recipient inboxes`() = runTest {
        val crypto = ZapCryptoTestFixture()
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        try {
            val metadata = crypto.signedEvent(crypto.recipient, 10002,
                listOf(listOf("r", "wss://inbox.example", "read"), listOf("r", "wss://recipient-write.example", "write")), "")
                .toNostrEvent("wss://fixture.example")
            assertTrue(SignatureVerifier().verify(metadata))
            store.insert(metadata)
            val original = crypto.signedEvent(crypto.sender, 1,
                listOf(listOf("p", crypto.recipient.pubkey)), "Signed synthetic post, no network publishing")
                .toNostrEvent("local")
            assertTrue(SignatureVerifier().verify(original))
            val expected = listOf("wss://own.example", "wss://inbox.example")
            assertEquals(expected, resolveComposePublishRelays(listOf("wss://own.example"), true, emptyList(),
                original.pubkey, original.tags, store::readRelaysFor))
            assertEquals(expected, originalEventDeletionRelays(original, listOf("wss://own.example"), store::readRelaysFor, emptySet()))
            val deletionTags = buildDeletionRequestTags(listOf(original)).map { it.toList() }
            assertEquals(listOf(listOf("e", original.id), listOf("k", "1")), deletionTags)
            // Destination expansion never changes signed deletion content or adds recipient p tags.
            val deletion = crypto.signedEvent(crypto.sender, 5, deletionTags, "").toNostrEvent("local")
            assertTrue(SignatureVerifier().verify(deletion))
            assertFalse(deletion.tags.any { it.firstOrNull() == "p" })
        } finally { store.clear() }
    }
}
