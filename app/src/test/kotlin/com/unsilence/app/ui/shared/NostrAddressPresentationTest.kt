package com.unsilence.app.ui.shared

import com.unsilence.app.data.network.Nip05VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrAddressPresentationTest {
    @Test
    fun `self-declared address is plain text without a verification claim`() {
        val presentation = selfDeclaredNostrAddressPresentation(
            "cashier@bitrefill.com",
            NostrAddressDisplay.FULL,
        )

        requireNotNull(presentation)
        assertEquals("cashier@bitrefill.com", presentation.visibleText)
        assertEquals("Nostr address: cashier@bitrefill.com", presentation.accessibilityLabel)
        assertFalse(presentation.accessibilityLabel.contains("verified", ignoreCase = true))
    }

    @Test
    fun `compact address keeps the domain but accessibility retains the identifier`() {
        val presentation = selfDeclaredNostrAddressPresentation(
            "cashier@bitrefill.com",
            NostrAddressDisplay.DOMAIN,
        )

        requireNotNull(presentation)
        assertEquals("bitrefill.com", presentation.visibleText)
        assertEquals("Nostr address: cashier@bitrefill.com", presentation.accessibilityLabel)
    }

    @Test
    fun `compact display still uses the existing nip05 parser`() {
        assertNull(selfDeclaredNostrAddressPresentation("not-an-address", NostrAddressDisplay.DOMAIN))
        assertEquals(
            "not-an-address",
            selfDeclaredNostrAddressPresentation(
                "not-an-address",
                NostrAddressDisplay.FULL,
            )?.visibleText,
        )
    }

    @Test
    fun `blank address has no presentation`() {
        assertNull(selfDeclaredNostrAddressPresentation("   ", NostrAddressDisplay.FULL))
        assertNull(selfDeclaredNostrAddressPresentation(null, NostrAddressDisplay.DOMAIN))
    }

    @Test
    fun `root identifier displays as its bare domain`() {
        assertEquals(
            "example.com",
            selfDeclaredNostrAddressPresentation(
                "_@example.com",
                NostrAddressDisplay.FULL,
            )?.visibleText,
        )
        assertEquals(
            "example.com",
            selfDeclaredNostrAddressPresentation(
                "example.com",
                NostrAddressDisplay.FULL,
            )?.visibleText,
        )
    }

    @Test
    fun `only a verified resolution earns the badge and verified semantics`() {
        val presentation = requireNotNull(
            selfDeclaredNostrAddressPresentation("alice@example.com", NostrAddressDisplay.FULL),
        )

        assertTrue(shouldShowNip05VerifiedBadge(Nip05VerificationStatus.VERIFIED))
        assertFalse(shouldShowNip05VerifiedBadge(Nip05VerificationStatus.UNVERIFIED))
        assertFalse(shouldShowNip05VerifiedBadge(Nip05VerificationStatus.UNKNOWN))
        assertEquals(
            "Verified Nostr address: alice@example.com",
            nip05AccessibilityLabel(presentation, Nip05VerificationStatus.VERIFIED),
        )
        assertEquals(
            "Nostr address: alice@example.com",
            nip05AccessibilityLabel(presentation, Nip05VerificationStatus.UNVERIFIED),
        )
    }
}
