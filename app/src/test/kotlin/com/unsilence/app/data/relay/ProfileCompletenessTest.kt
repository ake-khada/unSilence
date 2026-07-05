package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.UserEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCompletenessTest {

    @Test
    fun `missing profile needs picture fallback`() {
        assertTrue(profileMissingPicture(null))
    }

    @Test
    fun `blank picture needs picture fallback`() {
        assertTrue(profileMissingPicture(UserEntity(pubkey = "pk", picture = "")))
        assertTrue(profileMissingPicture(UserEntity(pubkey = "pk", picture = "   ")))
    }

    @Test
    fun `nonblank picture does not need picture fallback`() {
        assertFalse(profileMissingPicture(UserEntity(pubkey = "pk", picture = "https://example.com/avatar.png")))
    }
}
