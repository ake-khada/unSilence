package com.unsilence.app.ui.thread

import com.unsilence.app.data.memory.EventEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadAncestorWalkTest {
    private fun event(id: String, parent: String? = null) = EventEntity(
        id, "author", 1, "body", 100, "[]", replyToId = parent,
    )

    @Test fun `NIP22 explicit poll root is not replaced by a missing intermediate comment`() = runTest {
        val queried = mutableListOf<String>()
        val root = walkThreadAncestors("child", lookup = { id ->
            queried += id
            when (id) {
                "child" -> event(id, "missing-parent").copy(kind = 1111, rootId = "poll")
                "poll" -> event(id).copy(kind = 1068)
                else -> null
            }
        }, onParent = { _, _ -> })
        assertEquals("poll", root)
        assertEquals(listOf("child", "poll"), queried)
    }

    @Test fun `fallback completing after eight seconds still refines root`() = runTest {
        val hops = mutableListOf<String>()
        val root = walkThreadAncestors("reply", lookup = { id ->
            if (id == "reply") event(id, "parent") else {
                delay(12_000); event(id)
            }
        }, onParent = { _, parent -> hops += parent })
        assertEquals("parent", root)
        assertEquals(12_000L, testScheduler.currentTime)
        assertEquals(listOf("parent"), hops)
    }

    @Test fun `timeout preserves progress and cancels pending lookup`() = runTest {
        var cancelled = false
        val root = walkThreadAncestors("reply", lookup = { id ->
            if (id == "reply") event(id, "parent") else {
                try { awaitCancellation() } finally { cancelled = true }
            }
        }, onParent = { _, _ -> })
        assertEquals("parent", root)
        assertEquals(THREAD_ANCESTOR_BUDGET_MS, testScheduler.currentTime)
        assertTrue(cancelled)
    }

    @Test fun `cycle and hop limits remain bounded`() = runTest {
        var calls = 0
        walkThreadAncestors("a", lookup = { id ->
            calls++; event(id, if (id == "a") "b" else "a")
        }, onParent = { _, _ -> })
        assertEquals(2, calls)
        calls = 0
        walkThreadAncestors("0", lookup = { id ->
            calls++; event(id, (id.toInt() + 1).toString())
        }, onParent = { _, _ -> })
        assertEquals(50, calls)
    }

    @Test fun `leaving thread propagates cancellation instead of applying stale root`() = runTest {
        var applied = false
        val walk = async {
            walkThreadAncestors("reply", lookup = { awaitCancellation() }, onParent = { _, _ -> })
            applied = true
        }
        runCurrent()
        walk.cancelAndJoin()
        assertFalse(applied)
    }
}
