package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotProviderDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WotSettingsPolicyTest {

    @Test
    fun `coverage counts only followed subjects that have assertions`() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val c = "c".repeat(64)
        val coverage = computeWotCoverage(
            follows = setOf(a, b, "not-a-pubkey"),
            assertions = mapOf(
                a to WotAssertionEntity(a, providerPubkey = "1".repeat(64), rank = 80),
                c to WotAssertionEntity(c, providerPubkey = "1".repeat(64), rank = 90),
            ),
        )

        assertEquals(1, coverage.scored)
        assertEquals(2, coverage.total)
        assertEquals(0.5f, coverage.fraction, 0.0001f)
    }

    @Test
    fun `selector derivation shows own provider setup and decrypt states`() {
        val defaultPrefs = WotProviderPrefs(
            pubkey = "1".repeat(64),
            relay = "wss://default.example.com",
            source = WotProviderSource.DEFAULT,
        )

        val missing = deriveWotProviderOptions(
            prefs = defaultPrefs,
            ownProvider = null,
            encryptedOwnProviderAvailable = false,
        ).first { it.source == WotProviderSource.OWN_10040 }
        assertFalse(missing.enabled)
        assertEquals("No provider list (kind 10040) found", missing.subtitle)
        assertEquals("Set up", missing.actionHint)

        val encrypted = deriveWotProviderOptions(
            prefs = defaultPrefs,
            ownProvider = null,
            encryptedOwnProviderAvailable = true,
        ).first { it.source == WotProviderSource.OWN_10040 }
        assertFalse(encrypted.enabled)
        assertEquals("Encrypted provider list found", encrypted.subtitle)
        assertEquals("Decrypt", encrypted.actionHint)

        val own = deriveWotProviderOptions(
            prefs = defaultPrefs.copy(source = WotProviderSource.OWN_10040),
            ownProvider = WotProviderDescriptor("2".repeat(64), "wss://nip85.example.com", updatedAt = 10),
            encryptedOwnProviderAvailable = true,
        ).first { it.source == WotProviderSource.OWN_10040 }
        assertTrue(own.selected)
        assertTrue(own.enabled)
        assertNull(own.actionHint)
    }

    @Test
    fun `encrypted 10040 JSON parser accepts tag list and list of tags`() {
        val provider = "2".repeat(64)

        val single = parseEncryptedWotProviderTagsJson(
            """["30382:rank","$provider","nip85.example.com"]""",
            updatedAt = 42,
        )
        assertNotNull(single)
        assertEquals(provider, single!!.providerPubkey)
        assertEquals("wss://nip85.example.com", single.relayHint)
        assertEquals(42, single.updatedAt)

        val many = parseEncryptedWotProviderTagsJson(
            """[["30382:hops","${"3".repeat(64)}","wss://wrong.example.com"],["30382:rank","$provider","wss://right.example.com"]]""",
        )
        assertNotNull(many)
        assertEquals("wss://right.example.com", many!!.relayHint)
    }

    @Test
    fun `provider registry draft replaces target rows and preserves foreign rows and content`() {
        val providerPubkey = "5".repeat(64)
        val provider = WotProviderDescriptor(
            providerPubkey = providerPubkey,
            relayHint = "nip85.example.com",
            updatedAt = 0L,
        )
        val foreignHops = listOf("30382:hops", "old-provider", "wss://old.example.com")
        val foreignPrivate = listOf("encrypted-private-row", "must", "survive")
        val draft = mergeWotProviderRegistryDraft(
            existingTags = listOf(
                foreignHops,
                listOf("30382:rank", "old-provider", "wss://old.example.com"),
                foreignPrivate,
                listOf("30382:followers", "old-provider", "wss://old.example.com"),
            ),
            existingContent = "encrypted-private-content",
            provider = provider,
        )

        assertEquals("encrypted-private-content", draft.content)
        assertTrue(foreignHops in draft.tags)
        assertTrue(foreignPrivate in draft.tags)
        assertEquals(
            listOf("30382:rank", providerPubkey, "wss://nip85.example.com"),
            draft.tags.single { it.firstOrNull() == "30382:rank" },
        )
        assertEquals(
            listOf("30382:followers", providerPubkey, "wss://nip85.example.com"),
            draft.tags.single { it.firstOrNull() == "30382:followers" },
        )
    }

    @Test
    fun `custom provider pubkey input accepts hex and rejects malformed values`() {
        val provider = "4".repeat(64)

        assertEquals(provider, normalizeWotProviderPubkeyInput(provider.uppercase()))
        assertNull(normalizeWotProviderPubkeyInput("not-a-provider"))
    }
}
