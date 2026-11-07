package com.unsilence.app.ui.profile

import org.junit.Assert.*
import org.junit.Test

class ProfileFollowStateTest {
    @Test fun `unknown own list never claims Follow or Following`() {
        val state = profileFollowState("target", "viewer", emptySet(), null)
        assertNull(state.isFollowing)
        assertNull(state.actionLabel)
    }
    @Test fun `unknown target list has no relationship and plain Follow`() {
        val state = profileFollowState("target", "viewer", null, emptySet())
        assertNull(state.followingCount)
        assertNull(state.relationship)
        assertEquals("Follow", state.actionLabel)
    }
    @Test fun `known empty target list is zero not unknown`() {
        val state = profileFollowState("target", "viewer", emptySet(), emptySet())
        assertEquals(0L, state.followingCount)
        assertNull(state.relationship)
    }
    @Test fun `inbound follow and count use the same target list`() {
        val state = profileFollowState("target", "viewer", setOf("viewer", "other"), emptySet())
        assertEquals(2L, state.followingCount)
        assertEquals("Follows you", state.relationship)
        assertEquals("Follow back", state.actionLabel)
    }
    @Test fun `mutual follow is positive only when both lists support it`() {
        val state = profileFollowState("target", "viewer", setOf("viewer"), setOf("target"))
        assertEquals("Mutual follow", state.relationship)
        assertEquals("Following", state.actionLabel)
    }
    @Test fun `outbound only uses button without redundant relationship`() {
        val state = profileFollowState("target", "viewer", null, setOf("target"))
        assertNull(state.relationship)
        assertEquals("Following", state.actionLabel)
    }
    @Test fun `self and absent viewer never claim inbound relationship`() {
        assertNull(profileFollowState("viewer", "viewer", setOf("viewer"), setOf("viewer")).relationship)
        assertNull(profileFollowState("target", null, setOf("viewer"), null).relationship)
    }
}
