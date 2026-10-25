package com.unsilence.app.data.memory

import org.junit.Assert.*
import org.junit.Test

class NoteSearchMatcherTest {
    @Test fun `literal substring matching handles case and does not interpret patterns`() {
        assertTrue(containsNoteSearchText("The LIGHTNING Network", "lightning"))
        assertTrue(containsNoteSearchText("a [NOTE].* b", "[note].*"))
        assertFalse(containsNoteSearchText("a note b", "[note].*"))
        assertFalse(containsNoteSearchText("short", "longer query"))
        assertTrue(containsNoteSearchText("anything", ""))
    }

    @Test fun `Unicode matching handles accented text scripts and emoji`() {
        assertTrue(containsNoteSearchText("CAFÉ — ПРИВЕТ — ΑΘΗΝΑ", "café"))
        assertTrue(containsNoteSearchText("CAFÉ — ПРИВЕТ — ΑΘΗΝΑ", "привет"))
        assertTrue(containsNoteSearchText("CAFÉ — ПРИВЕТ — ΑΘΗΝΑ", "αθηνα"))
        assertTrue(containsNoteSearchText("A 🐈 CAT", "🐈 cat"))
        assertFalse(containsNoteSearchText("café", "cafe"))
        assertFalse(containsNoteSearchText("Straße", "STRASSE"))
    }

    @Test fun `case matching uses character equivalence rather than expanded or normalized text`() {
        assertTrue(containsNoteSearchText("İstanbul", "istanbul"))
        assertTrue(containsNoteSearchText("ΟΣ", "οσ"))
        assertTrue(containsNoteSearchText("ΟΣ", "ος"))
        assertFalse(containsNoteSearchText("café", "cafe\u0301"))
        assertFalse(containsNoteSearchText("İ", "i\u0307"))
    }

    @Test fun `repeated matching does not allocate copies proportional to body size`() {
        val allocations = JvmAllocationCounter()
        val text = "Mixed CASE note body with NO matching term. ".repeat(2_000)
        repeat(100) { sink = containsNoteSearchText(text, "absent-query") }
        val before = allocations.bytes()
        repeat(32) { sink = containsNoteSearchText(text, "absent-query") }
        val bytes = allocations.bytes() - before
        assertFalse(sink)
        assertTrue("Matching allocated $bytes bytes for 32 scans", bytes < 64 * 1024)
    }

    @Test fun `same fixture compares copying and direct matching cost`() {
        val allocations = JvmAllocationCounter()
        val bodies = List(4_000) { i ->
            "Note $i: " + "Mixed CASE café text for a local search. ".repeat(24) +
                if (i % 10 == 0) " TARGET " else ""
        }
        val query = "target"
        fun scan(matcher: (String, String) -> Boolean): Int = bodies.count { matcher(it, query) }
        val old: (String, String) -> Boolean = { content, q -> content.lowercase().contains(q.lowercase()) }
        val current = ::containsNoteSearchText
        repeat(5) { scan(old); scan(current) }
        fun measure(label: String, matcher: (String, String) -> Boolean): Pair<Int, Long> {
            val before = allocations.bytes()
            val start = System.nanoTime()
            val count = scan(matcher)
            val elapsed = System.nanoTime() - start
            val bytes = allocations.bytes() - before
            println("NOTE_SEARCH_FIXTURE mode=$label entries=${bodies.size} chars=${bodies.sumOf { it.length }} matches=$count allocatedBytes=$bytes durationNs=$elapsed")
            return count to bytes
        }
        val baseline = measure("copying", old)
        val candidate = measure("production", current)
        assertEquals(400, candidate.first)
        assertEquals(baseline.first, candidate.first)
        // JVM allocation evidence only; no wall-time threshold or phone-performance claim.
    }

    // Android's compile-time API lacks java.lang.management. This test runs only
    // on the configured HotSpot JVM; reflection keeps the Android bootclasspath intact.
    private class JvmAllocationCounter {
        private val api = Class.forName("com.sun.management.ThreadMXBean")
        private val bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
        private val read = api.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        private val thread = Thread.currentThread().threadId()
        init {
            assertEquals(true, api.getMethod("isThreadAllocatedMemorySupported").invoke(bean))
            api.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType)
                .invoke(bean, true)
        }
        fun bytes(): Long = read.invoke(bean, thread) as Long
    }

    private companion object { @Volatile var sink = false }
}
