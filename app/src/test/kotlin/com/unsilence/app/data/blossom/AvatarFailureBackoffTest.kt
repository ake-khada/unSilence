package com.unsilence.app.data.blossom

import org.junit.Assert.*
import org.junit.Test

class AvatarFailureBackoffTest {
    @Test fun `negative entries expire without resets or a background worker`() {
        var now = 0L
        val table = AvatarFailureBackoff({ now }, capacity = 2, ttl = 10)
        table.record("host:dead.example")
        assertTrue(table.contains("host:dead.example"))
        now = 10
        assertFalse(table.contains("host:dead.example"))
    }
    @Test fun `bounded LRU separates host hash and author state and clears on teardown`() {
        val table = AvatarFailureBackoff({ 0L }, capacity = 2)
        table.record("author:a"); table.record("host:b")
        assertTrue(table.contains("author:a"))
        table.record("attempt:c")
        assertFalse(table.contains("host:b"))
        assertTrue(table.contains("attempt:c"))
        table.clear()
        assertFalse(table.contains("author:a")); assertFalse(table.contains("attempt:c"))
    }
}
