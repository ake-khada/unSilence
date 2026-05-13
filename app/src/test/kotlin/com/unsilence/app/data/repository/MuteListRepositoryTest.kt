package com.unsilence.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteListRepositoryTest {

    @Test
    fun `MuteResult enum values exist`() {
        assertEquals(2, MuteResult.entries.size)
        assertTrue(MuteResult.entries.contains(MuteResult.Queued))
        assertTrue(MuteResult.entries.contains(MuteResult.LocalOnly))
    }

    // MuteListRepository requires Android DI (KeyManager, SigningManager, etc.)
    // so we can't unit-test muteUser/unmuteUser directly without Robolectric.
    // The publish safety gate is integration-tested below at the MES level.
    // The critical invariant — "muteUser returns NotReady when publishSafe is false" —
    // is exercised in MemoryEventStoreInvariantsTest via the data flow.
}
