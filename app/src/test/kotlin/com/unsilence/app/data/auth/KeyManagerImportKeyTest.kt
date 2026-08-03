package com.unsilence.app.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyManagerImportKeyTest {

    private val curveOrder = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141"

    @Test
    fun `private scalar validator rejects malformed and out-of-range values`() {
        assertFalse(isValidPrivateKeyScalar(""))
        assertFalse(isValidPrivateKeyScalar("g".repeat(64)))
        assertFalse(isValidPrivateKeyScalar("0".repeat(64)))
        assertFalse(isValidPrivateKeyScalar(curveOrder))
        assertFalse(isValidPrivateKeyScalar("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364142"))
        assertFalse(isValidPrivateKeyScalar("F".repeat(64)))
    }

    @Test
    fun `private scalar validator accepts the valid range boundaries`() {
        assertTrue(isValidPrivateKeyScalar("0".repeat(63) + "1"))
        assertTrue(isValidPrivateKeyScalar("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364140"))
        assertTrue(isValidPrivateKeyScalar("01".repeat(32)))
    }
}
