package com.unsilence.app.data.memory

import org.junit.Assert.*
import org.junit.Test

class DisplayWindowRetentionTest {
    @Test fun `three covered contexts survive and the fourth retires oldest without invalidating its handle`() {
        val registry = DisplayWindowRetention(Any())
        val leases = (0..3).map { i -> registry.acquire().also { assertTrue(it.update(listOf("row-$i"))) } }
        leases.take(3).forEach { it.setActive(false) }
        assertEquals(2, registry.priority("row-0"))
        assertEquals(mapOf("row-3" to 0), registry.priorities())
        leases.last().setActive(false)
        assertNull(registry.priority("row-0"))
        (1..3).forEach { assertEquals(2, registry.priority("row-$it")) }
        leases.first().setActive(true)
        assertTrue(leases.first().update(listOf("returned")))
        assertEquals(0, registry.priority("returned"))
        assertNull(registry.priority("row-0"))
    }

    @Test fun `resume restores visible and nearby roles before another report`() {
        val registry = DisplayWindowRetention(Any())
        val first = registry.acquire()
        first.update(listOf("visible", "near"), setOf("visible"))
        first.setActive(false)
        val next = registry.acquire()
        next.update(listOf("next"))
        // Navigation pauses B before resuming A.
        next.setActive(false)
        first.setActive(true)
        assertEquals(mapOf("visible" to 0, "near" to 1), registry.priorities())
        assertEquals(2, registry.priority("next"))
        next.close()
        assertNull(registry.priority("next"))
    }

    @Test fun `active acquisition reclaims covered capacity but never exceeds thirty two windows`() {
        val registry = DisplayWindowRetention(Any())
        val leases = (0 until 32).map { i -> registry.acquire().also { it.update(listOf("row-$i")) } }
        val extra = registry.acquire()
        assertFalse(extra.update(listOf("extra")))
        leases.first().setActive(false)
        assertTrue(extra.update(listOf("extra")))
        assertNull(registry.priority("row-0"))
        assertEquals(32, registry.priorities().size)
    }

    @Test fun `retirement is bounded and overlapping active owner keeps highest priority`() {
        val registry = DisplayWindowRetention(Any())
        val first = registry.acquire().also { it.update(listOf("shared", "first")) }
        val second = registry.acquire().also { it.update(listOf("shared", "second")) }
        first.setActive(false)
        assertEquals(0, registry.priority("shared"))
        assertTrue(registry.retireOldestCovered())
        assertFalse(registry.retireOldestCovered())
        assertNull(registry.priority("first"))
        assertEquals(0, registry.priority("shared"))
        second.close()
        assertNull(registry.priority("shared"))
        assertFalse(second.update(listOf("closed")))
    }

    @Test fun `account clear invalidates retired and never registered handles too`() {
        val registry = DisplayWindowRetention(Any())
        val idle = registry.acquire()
        val old = registry.acquire().also { it.update(listOf("old")); it.setActive(false) }
        registry.retireOldestCovered()
        registry.clear()
        val current = registry.acquire().also { it.update(listOf("new")) }
        old.setActive(true)
        assertFalse(old.update(listOf("new", "old")))
        assertFalse(idle.update(listOf("late")))
        old.close()
        assertEquals(mapOf("new" to 0), registry.priorities())
        current.close()
    }

    @Test fun `forget and empty report release ids without leaving covered capacity`() {
        val registry = DisplayWindowRetention(Any())
        val lease = registry.acquire()
        lease.update((0 until 100).map { "row-$it" })
        assertEquals(64, registry.priorities().size)
        registry.forget("row-0")
        assertNull(registry.priority("row-0"))
        lease.setActive(false)
        lease.update(emptyList())
        assertFalse(registry.retireOldestCovered())
        lease.setActive(true)
        assertTrue(lease.update(listOf("next")))
    }
}
