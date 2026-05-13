package com.unsilence.app.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningManagerNip44Test {

    // ── isValidNip44V2Ciphertext ────────────────────────────────────────────

    @Test
    fun `rejects empty string`() {
        assertFalse(SigningManager.isValidNip44V2Ciphertext(""))
    }

    @Test
    fun `rejects Amber error string 'Could not encrypt the message'`() {
        assertFalse(SigningManager.isValidNip44V2Ciphertext("Could not encrypt the message"))
    }

    @Test
    fun `rejects Amber error string 'Could not decrypt the message'`() {
        assertFalse(SigningManager.isValidNip44V2Ciphertext("Could not decrypt the message"))
    }

    @Test
    fun `rejects strings under 132 chars`() {
        // 131 chars of valid base64
        val short = "A".repeat(131) + "="
        assertFalse(SigningManager.isValidNip44V2Ciphertext(short))
    }

    @Test
    fun `rejects strings with non-base64 chars`() {
        // 132 chars but contains spaces
        val bad = "A".repeat(60) + " " + "B".repeat(71)
        assertFalse(SigningManager.isValidNip44V2Ciphertext(bad))
    }

    @Test
    fun `rejects strings with JSON brackets`() {
        val json = "[" + "A".repeat(131) + "]"
        assertFalse(SigningManager.isValidNip44V2Ciphertext(json))
    }

    @Test
    fun `rejects EncryptedInfo struct toString pattern`() {
        val struct = "EncryptedInfo(nonce=[1,2,3], ciphertext=[4,5,6], mac=[7,8,9])"
        assertFalse(SigningManager.isValidNip44V2Ciphertext(struct))
    }

    // ── isValidNip04Ciphertext ──────────────────────────────────────────────

    @Test
    fun `NIP-04 rejects string without iv separator`() {
        assertFalse(SigningManager.isValidNip04Ciphertext("aGVsbG8="))
    }

    @Test
    fun `NIP-04 rejects empty ciphertext before iv`() {
        assertFalse(SigningManager.isValidNip04Ciphertext("?iv=AAAA"))
    }

    @Test
    fun `NIP-04 accepts valid NIP-04 wire format`() {
        assertTrue(SigningManager.isValidNip04Ciphertext("aGVsbG8=?iv=d29ybGQ="))
    }

    @Test
    fun `NIP-04 rejects non-base64 in ciphertext part`() {
        assertFalse(SigningManager.isValidNip04Ciphertext("hello world?iv=AAAA"))
    }

    @Test
    fun `NIP-04 rejects non-base64 in iv part`() {
        assertFalse(SigningManager.isValidNip04Ciphertext("aGVsbG8=?iv=not base64!"))
    }
}
