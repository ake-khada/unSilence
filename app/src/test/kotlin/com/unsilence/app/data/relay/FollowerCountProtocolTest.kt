package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowerCountProtocolTest {
    @Test
    fun `Primal profile frame exposes follower count`() {
        val frame =
            """["EVENT","profile-1",{"kind":10000105,"content":"{\"followers_count\":199068}"}]"""

        assertEquals(199_068L, parsePrimalFollowerCountFrame(frame))
    }

    @Test
    fun `Primal parser tolerates unrelated and malformed frames`() {
        assertNull(parsePrimalFollowerCountFrame("not json"))
        assertNull(
            parsePrimalFollowerCountFrame(
                """["EVENT","profile-1",{"kind":10000105,"content":"not json"}]""",
            ),
        )
        assertNull(
            parsePrimalFollowerCountFrame(
                """["EVENT","profile-1",{"kind":1,"content":"{\"followers_count\":500}"}]""",
            ),
        )
    }

    @Test
    fun `NIP-45 parser retains limited integrity flag`() {
        val limited = parseNip45CountFrame(
            """["COUNT","count-1",{"count":10000,"limited":true}]""",
        )
        val exact = parseNip45CountFrame(
            """["COUNT","count-2",{"count":365}]""",
        )

        assertEquals("count-1", limited?.subId)
        assertEquals(10_000L, limited?.result?.count)
        assertTrue(limited?.result?.limited == true)
        assertEquals(365L, exact?.result?.count)
        assertFalse(exact?.result?.limited == true)
        assertNull(parseNip45CountFrame("garbage"))
    }
}
