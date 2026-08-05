package com.unsilence.app.data.memory

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class MesMemoryEstimatorTest {

    @Test
    fun `event estimate stays within five percent of documented ART fixture`() {
        val relay = "wss://relay.one"
        val event = NostrEvent(
            id = "i".repeat(64),
            pubkey = "a".repeat(64),
            kind = 1,
            content = "x".repeat(100),
            createdAt = 1L,
            tags = listOf(
                listOf("e", "target-id", "wss://hint.one"),
                listOf("p", "p".repeat(64), "", "friend"),
                listOf("t", "Ж"),
            ),
            sig = "s".repeat(128),
            relayUrl = relay,
            replyToId = "r".repeat(64),
            rootId = "o".repeat(64),
            hasContentWarning = true,
            contentWarningReason = "warning",
            firstSeenAt = 1L,
            relaysSeen = ConcurrentHashMap.newKeySet<String>().apply {
                add(relay)
                add("wss://relay.two")
            },
        )

        // Hand-audited 64-bit ART fixture using the object-layout table in
        // MesMetrics: event/primary indexes 712 B, scalar strings 832 B,
        // nested tag lists and strings 712 B, relay provenance 264 B.
        // The device HPROF validation is the non-model acceptance test.
        val fixtureBytes = 2_520L
        val estimate = estimateNostrEventRetainedBytes(event)
        val relativeError = abs(estimate - fixtureBytes).toDouble() / fixtureBytes

        assertTrue(
            "estimate=$estimate fixture=$fixtureBytes error=$relativeError",
            relativeError <= 0.05,
        )
    }
}
