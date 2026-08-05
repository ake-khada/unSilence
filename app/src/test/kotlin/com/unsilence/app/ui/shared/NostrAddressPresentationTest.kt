package com.unsilence.app.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
