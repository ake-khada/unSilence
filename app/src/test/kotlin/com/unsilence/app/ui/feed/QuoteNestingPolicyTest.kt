package com.unsilence.app.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteNestingPolicyTest {
    @Test
    fun `two quote levels are full before compact and continuation modes`() {
        assertEquals(QuoteRenderMode.FULL, quoteRenderMode(0))
        assertEquals(QuoteRenderMode.FULL, quoteRenderMode(1))
        assertEquals(QuoteRenderMode.COMPACT, quoteRenderMode(2))
        assertEquals(QuoteRenderMode.CONTINUATION, quoteRenderMode(3))
        assertEquals(QuoteRenderMode.CONTINUATION, quoteRenderMode(20))
    }

    @Test
    fun `cyclic quote traversal reaches the terminal mode`() {
        // A quotes B quotes A quotes B. Rendering stops before resolving B again.
        val modes = (0..3).map(::quoteRenderMode)

        assertEquals(
            listOf(
                QuoteRenderMode.FULL,
                QuoteRenderMode.FULL,
                QuoteRenderMode.COMPACT,
                QuoteRenderMode.CONTINUATION,
            ),
            modes,
        )
    }
}
