package com.unsilence.app.ui.compose

import org.junit.Assert.*
import org.junit.Test

class ComposePublishTrackerTest {
    @Test fun `every own and recipient target starts pending and keeps its outcome`() {
        val targets = resolveComposePublishRelays(listOf("wss://own.example"), true, emptyList(), "aa".repeat(32),
            listOf(listOf("p", "bb".repeat(32))), { listOf("wss://read1.example", "wss://read2.example") })
        val tracker = ComposePublishTracker(targets)
        assertEquals(3, tracker.snapshot().size)
        assertTrue(tracker.snapshot().values.all { it == RelayPublishStatus.Pending })
        assertTrue(tracker.acknowledge("wss://read1.example/", true))
        assertTrue(tracker.acknowledge("wss://read2.example", false))
        assertFalse(tracker.acknowledge("wss://unrelated.example", true))
        assertTrue(tracker.hasPending)
        tracker.expirePending()
        assertFalse(tracker.hasPending)
        assertEquals(mapOf("wss://own.example" to RelayPublishStatus.TimedOut,
            "wss://read1.example" to RelayPublishStatus.Accepted,
            "wss://read2.example" to RelayPublishStatus.Rejected), tracker.snapshot())
    }
}
