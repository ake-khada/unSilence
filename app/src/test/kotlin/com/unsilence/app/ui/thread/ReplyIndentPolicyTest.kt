package com.unsilence.app.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyIndentPolicyTest {
    @Test
    fun `reply indents converge and pin at depth eight`() {
        val expected = mapOf(
            0 to 0,
            1 to 12,
            6 to 72,
            7 to 80,
            8 to 85,
            9 to 85,
            100 to 85,
        )

        expected.forEach { (depth, indent) ->
            assertEquals("depth $depth", indent, replyIndentDp(depth))
        }
    }
}
