package com.unsilence.app.ui.common

import com.unsilence.app.ui.feed.relativeTime
import org.junit.Assert.assertEquals
import org.junit.Test

class MinuteClockTest {
    @Test fun delayIsAlignedAndAlwaysPositive() {
        assertEquals(60_000L, millisUntilNextMinute(0))
        assertEquals(1L, millisUntilNextMinute(59_999))
        assertEquals(60_000L, millisUntilNextMinute(120_000))
        assertEquals(1L, millisUntilNextMinute(-1))
    }

    @Test fun relativeLabelsAdvanceWithoutAnotherCardUpdate() {
        val created = 1_700_000_000L
        val start = created * 1000
        assertEquals("now", relativeTime(created, start + 59_999))
        assertEquals("1m", relativeTime(created, start + 60_000))
        assertEquals("59m", relativeTime(created, start + 3_599_999))
        assertEquals("1h", relativeTime(created, start + 3_600_000))
        assertEquals("1d", relativeTime(created, start + 86_400_000))
        assertEquals("now", relativeTime(created, start - 60_000))
    }
}
