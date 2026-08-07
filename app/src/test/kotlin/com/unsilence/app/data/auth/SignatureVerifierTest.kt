package com.unsilence.app.data.auth

import com.unsilence.app.data.relay.SignedEventFixture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureVerifierTest {
    private val verifier = SignatureVerifier()

    @Test
    fun `accepts event with canonical id and valid Schnorr signature`() {
        assertTrue(verifier.verify(SignedEventFixture.event()))
    }

    @Test
    fun `rejects well formed event when one signature nibble is changed`() {
        val corrupted = SignedEventFixture.SIGNATURE.dropLast(1) + "0"

        assertFalse(verifier.verify(SignedEventFixture.event(signature = corrupted)))
    }

    @Test
    fun `rejects well formed event when pubkey is swapped`() {
        val swappedPubkey = "8" + SignedEventFixture.PUBKEY.drop(1)

        assertFalse(verifier.verify(SignedEventFixture.event(pubkey = swappedPubkey)))
    }

    @Test
    fun `rejects event when canonical content no longer matches its id`() {
        val tampered = SignedEventFixture.event().copy(
            content = SignedEventFixture.CONTENT + " tampered",
        )

        assertFalse(verifier.verify(tampered))
    }
}
