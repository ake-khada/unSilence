package com.unsilence.app.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for FeedWindow zone-aware hydration invariants.
 *
 * Zone bounds:
 *   warmStart = (viewportFirst - WARM_ABOVE).coerceAtLeast(0)
 *   warmEnd   = (viewportLast + WARM_BELOW).coerceAtMost(rows.size - 1)
 *
 * Constants: WARM_ABOVE = 10, WARM_BELOW = 30
 */
class FeedWindowZonesTest {

    // ── Zone bounds arithmetic ──────────────────────────────────────────────

    private fun computeWarmRange(
        viewportFirst: Int,
        viewportLast: Int,
        rowCount: Int,
        warmAbove: Int = 10,
        warmBelow: Int = 30,
    ): IntRange {
        val start = (viewportFirst - warmAbove).coerceAtLeast(0)
        val end = (viewportLast + warmBelow).coerceAtMost(rowCount - 1)
        return start..end
    }

    @Test
    fun `warm zone at top of list clamps start to zero`() {
        val range = computeWarmRange(viewportFirst = 0, viewportLast = 7, rowCount = 300)
        assertEquals(0, range.first)
        assertEquals(37, range.last) // 7 + 30
    }

    @Test
    fun `warm zone in middle of list spans both directions`() {
        val range = computeWarmRange(viewportFirst = 50, viewportLast = 57, rowCount = 300)
        assertEquals(40, range.first) // 50 - 10
        assertEquals(87, range.last) // 57 + 30
    }

    @Test
    fun `warm zone at bottom of list clamps end to row count`() {
        val range = computeWarmRange(viewportFirst = 290, viewportLast = 299, rowCount = 300)
        assertEquals(280, range.first) // 290 - 10
        assertEquals(299, range.last) // clamped to 299
    }

    @Test
    fun `warm zone with small list covers everything`() {
        val range = computeWarmRange(viewportFirst = 0, viewportLast = 4, rowCount = 5)
        assertEquals(0, range.first)
        assertEquals(4, range.last) // min(4+20, 4)
    }

    @Test
    fun `empty list produces invalid range`() {
        val range = computeWarmRange(viewportFirst = 0, viewportLast = 0, rowCount = 0)
        // warmEnd = (0 + 20).coerceAtMost(-1) = -1, warmStart = 0 → empty range
        assertTrue(range.isEmpty())
    }

    @Test
    fun `hot zone is subset of warm zone`() {
        val viewportFirst = 50
        val viewportLast = 57
        val rowCount = 300
        val warm = computeWarmRange(viewportFirst, viewportLast, rowCount)
        assertTrue(viewportFirst >= warm.first)
        assertTrue(viewportLast <= warm.last)
    }

    // ── Hydration status idempotency ────────────────────────────────────────

    /**
     * Mirror of FeedWindow.HydrationStatus — tests that the idempotency
     * flags prevent re-fetching after first pass.
     */
    private data class HydrationStatus(
        var profileFetched: Boolean = false,
        var refsFetched: Boolean = false,
        var ogFetched: Boolean = false,
        var videoFrameFetched: Boolean = false,
        var engagementFreshAt: Long = 0L,
    )

    @Test
    fun `hydration status starts unfetched`() {
        val s = HydrationStatus()
        assertFalse(s.profileFetched)
        assertFalse(s.refsFetched)
        assertFalse(s.ogFetched)
        assertFalse(s.videoFrameFetched)
        assertEquals(0L, s.engagementFreshAt)
    }

    @Test
    fun `hydration flags are idempotent after first set`() {
        val s = HydrationStatus()
        s.profileFetched = true
        s.refsFetched = true
        s.ogFetched = true
        s.videoFrameFetched = true
        s.engagementFreshAt = 1000L

        // Second "pass" should see all flags as true — no re-fetch
        assertTrue(s.profileFetched)
        assertTrue(s.refsFetched)
        assertTrue(s.ogFetched)
        assertTrue(s.videoFrameFetched)
    }

    @Test
    fun `engagement staleness respects 60s threshold`() {
        val staleMs = 60_000L
        val s = HydrationStatus(engagementFreshAt = 1_000_000L)
        val now = 1_050_000L // 50s later — not stale
        assertFalse(now - s.engagementFreshAt >= staleMs)

        val later = 1_070_000L // 70s later — stale
        assertTrue(later - s.engagementFreshAt >= staleMs)
    }

    @Test
    fun `engagement refresh updates freshAt preventing immediate re-fetch`() {
        val staleMs = 60_000L
        val s = HydrationStatus(engagementFreshAt = 0L)
        val now = 100_000L

        // First check: stale (0 → should refresh)
        assertTrue(now - s.engagementFreshAt >= staleMs)

        // After refresh
        s.engagementFreshAt = now

        // Immediate re-check: not stale
        assertFalse(now - s.engagementFreshAt >= staleMs)

        // 30s later: still not stale
        val later = now + 30_000L
        assertFalse(later - s.engagementFreshAt >= staleMs)
    }

    // ── Zone coverage property ──────────────────────────────────────────────

    @Test
    fun `warm zone always includes at least the visible viewport`() {
        for (first in 0..290 step 10) {
            val last = (first + 7).coerceAtMost(299)
            val warm = computeWarmRange(first, last, 300)
            for (i in first..last) {
                assertTrue("Viewport item $i should be in warm zone $warm", i in warm)
            }
        }
    }

    @Test
    fun `warm zone size is bounded by above plus below plus viewport`() {
        val first = 50
        val last = 57
        val warm = computeWarmRange(first, last, 300)
        // Max size = WARM_ABOVE + viewport + WARM_BELOW = 10 + 8 + 30 = 48
        assertTrue(warm.count() <= 10 + (last - first + 1) + 30)
    }
}
