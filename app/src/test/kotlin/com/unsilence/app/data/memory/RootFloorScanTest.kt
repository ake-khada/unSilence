package com.unsilence.app.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the displayable-floor scan logic.
 * Models the scan loop without Android context or MES dependencies.
 */
class RootFloorScanTest {

    data class MockEvent(val kind: Int, val isReply: Boolean, val muted: Boolean = false)

    /**
     * Models the displayable-floor scan: iterate events, collect until [floor]
     * displayable roots reached, [scanCap] events scanned, or index exhausted.
     * Returns (collected slice, displayable count, bound label).
     * Displayable root = (kind-6 OR !isReply) AND !muted.
     */
    private fun collectWithDisplayableFloor(
        events: List<MockEvent>,
        floor: Int,
        scanCap: Int,
    ): Triple<List<MockEvent>, Int, String> {
        val result = mutableListOf<MockEvent>()
        var displayable = 0
        var scanned = 0
        var hitFloor = false
        var hitCap = false
        for (event in events) {
            if (displayable >= floor) { hitFloor = true; break }
            if (scanned >= scanCap) { hitCap = true; break }
            scanned++
            result.add(event)
            val isRoot = event.kind == 6 || !event.isReply
            if (isRoot && !event.muted) displayable++
        }
        val bound = when { hitFloor -> "floor"; hitCap -> "cap"; else -> "exhausted" }
        return Triple(result, displayable, bound)
    }

    private fun root(kind: Int = 1, muted: Boolean = false) = MockEvent(kind, isReply = false, muted = muted)
    private fun reply(kind: Int = 1, muted: Boolean = false) = MockEvent(kind, isReply = true, muted = muted)
    private fun repost(muted: Boolean = false) = MockEvent(6, isReply = false, muted = muted)

    @Test
    fun `stops at floor when displayable ratio is healthy`() {
        // 30% displayable: every 3rd event is a non-muted root
        val events = (1..1000).map { i ->
            when (i % 3) {
                0 -> root()           // displayable root
                1 -> reply()          // reply
                else -> root(muted = true)  // muted root
            }
        }
        val (result, displayable, bound) = collectWithDisplayableFloor(events, floor = 100, scanCap = 1000)

        assertEquals(100, displayable)
        assertEquals("floor", bound)
        // ~300 scanned to get 100 displayable (33% ratio)
        assertTrue(result.size in 297..303)
    }

    @Test
    fun `returns what exists when MES exhausts below floor`() {
        // Small MES with 4 displayable roots (1 muted root doesn't count)
        val events = listOf(root(), reply(), root(muted = true), root(), reply(), root(), reply(), root())
        val (result, displayable, bound) = collectWithDisplayableFloor(events, floor = 100, scanCap = 1000)

        assertEquals(4, displayable)
        assertEquals("exhausted", bound)
        assertEquals(8, result.size)
    }

    @Test
    fun `stops at scanCap before floor on a long spammy slice`() {
        // 2% displayable: 1 displayable root per 50 events
        val events = (1..2000).map { i ->
            if (i % 50 == 0) root() else root(muted = true)
        }
        val (result, displayable, bound) = collectWithDisplayableFloor(events, floor = 100, scanCap = 500)

        assertEquals(500, result.size)
        assertEquals(10, displayable)  // 500/50
        assertEquals("cap", bound)
    }

    @Test
    fun `muted roots do not count toward floor but stay in result`() {
        // 6 events: 2 muted roots + 2 displayable roots + reply + extra root after floor
        val events = listOf(root(muted = true), root(muted = true), root(), reply(), root(), reply())
        val (result, displayable, bound) = collectWithDisplayableFloor(events, floor = 2, scanCap = 100)

        assertEquals(2, displayable)
        assertEquals("floor", bound)
        assertEquals(5, result.size)  // scanned up to 2nd displayable root, floor fires on next iteration
        // Muted roots are in the result for toggle/reactivity
        assertTrue(result.any { it.muted })
    }

    @Test
    fun `kind-6 counts as a displayable root`() {
        val events = listOf(reply(), repost(), reply(), root(), reply())
        val (result, displayable, bound) = collectWithDisplayableFloor(events, floor = 2, scanCap = 100)

        assertEquals(2, displayable)
        assertEquals("floor", bound)
        assertEquals(4, result.size)
    }

    @Test
    fun `empty events returns empty exhausted`() {
        val (result, displayable, bound) = collectWithDisplayableFloor(emptyList(), floor = 100, scanCap = 1000)

        assertEquals(0, displayable)
        assertEquals("exhausted", bound)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `all displayable roots stops at floor exactly`() {
        val events = (1..500).map { root() }
        val (result, displayable, bound) = collectWithDisplayableFloor(events, floor = 100, scanCap = 1000)

        assertEquals(100, displayable)
        assertEquals("floor", bound)
        assertEquals(100, result.size)
    }
}
