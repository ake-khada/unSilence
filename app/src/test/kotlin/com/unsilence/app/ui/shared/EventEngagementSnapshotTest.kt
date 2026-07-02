package com.unsilence.app.ui.shared

import com.unsilence.app.ui.feed.NoteActionsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventEngagementSnapshotTest {
    @Test
    fun `forEvent narrows collection state to requested event`() {
        val flash = NoteActionsViewModel.ZapFlashState("event-a", success = true)
        val source = EngagementSnapshot(
            reactedIds = setOf("event-a"),
            repostedIds = setOf("event-b"),
            zappedIds = setOf("event-a"),
            isNwcConfigured = true,
            zapLoadingIds = setOf("event-a"),
            optimisticZapSats = mapOf("event-a" to 21L),
            zapFlash = flash,
        )

        val eventA = source.forEvent("event-a")
        assertTrue(eventA.hasReacted)
        assertFalse(eventA.hasReposted)
        assertTrue(eventA.hasZapped)
        assertTrue(eventA.isNwcConfigured)
        assertTrue(eventA.isZapLoading)
        assertEquals(21L, eventA.extraZapSats)
        assertEquals(flash, eventA.zapFlash)

        val eventB = source.forEvent("event-b")
        assertFalse(eventB.hasReacted)
        assertTrue(eventB.hasReposted)
        assertFalse(eventB.hasZapped)
        assertFalse(eventB.isZapLoading)
        assertEquals(0L, eventB.extraZapSats)
        assertNull(eventB.zapFlash)
    }
}
