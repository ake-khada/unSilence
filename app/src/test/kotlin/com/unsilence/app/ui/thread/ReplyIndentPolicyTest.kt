package com.unsilence.app.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyIndentPolicyTest {
    @Test
    fun `reply indents use eight dp steps through depth ten`() {
        val expected = mapOf(
            0 to 0,
            1 to 8,
            6 to 48,
            7 to 56,
            8 to 64,
            9 to 72,
            10 to 80,
            11 to 80,
            100 to 80,
        )

        expected.forEach { (depth, indent) ->
            assertEquals("depth $depth", indent, replyIndentDp(depth))
        }
    }
}
