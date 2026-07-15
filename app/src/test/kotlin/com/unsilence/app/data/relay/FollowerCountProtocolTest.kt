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

    @Test
    fun `Primal explore people parser follows paging order and tolerates garbage`() {
        val pubkeyA = "a".repeat(64)
        val pubkeyB = "b".repeat(64)
        val frames = listOf(
            "garbage",
            """["EVENT","people",{"kind":0,"pubkey":"$pubkeyA","content":"{\"name\":\"Alice\",\"picture\":\"https://a.example/avatar\"}"}]""",
            """["EVENT","people",{"kind":0,"pubkey":"$pubkeyB","content":"{\"display_name\":\"Bob\"}"}]""",
            """["EVENT","people",{"kind":10000133,"content":"{\"$pubkeyA\":42,\"$pubkeyB\":99}"}]""",
            """["EVENT","people",{"kind":10000113,"content":"{\"elements\":[\"$pubkeyB\",\"$pubkeyA\"]}"}]""",
        )

        val parsed = parsePrimalSuggestedProfiles(frames)

        assertEquals(listOf(pubkeyB, pubkeyA), parsed.map { it.pubkey })
        assertEquals("Bob", parsed[0].displayName)
        assertEquals(99L, parsed[0].followerCount)
        assertEquals("Alice", parsed[1].name)
        assertEquals(42L, parsed[1].followerCount)
    }
}
