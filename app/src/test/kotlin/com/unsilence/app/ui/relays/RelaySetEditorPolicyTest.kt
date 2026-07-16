package com.unsilence.app.ui.relays

import com.unsilence.app.data.memory.RelaySet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySetEditorPolicyTest {

    @Test
    fun `metadata edit preserves relay markers extensions and foreign tags exactly`() {
        val originalRelayTags = listOf(
            listOf("relay", "wss://read.example", "read", "client-extension"),
            listOf("relay", "wss://write.example", "write"),
        )
        val foreignTags = listOf(
            listOf("client", "other-app", "v2"),
            listOf("alt", "A curated set"),
        )
        val draft = relaySetEditorDraft(
            RelaySet(
                dTag = "stable-id",
                ownerPubkey = "owner",
                title = "Old name",
                members = listOf("wss://read.example", "wss://write.example"),
                relayTags = originalRelayTags,
                foreignTags = foreignTags,
            ),
        ).copy(title = "Renamed")

        val payload = buildRelaySetPublishPayload(draft.dTag!!, draft)

        assertEquals("stable-id", payload.dTag)
        assertEquals(originalRelayTags, payload.tags.filter { it.firstOrNull() == "relay" })
        assertTrue(payload.tags.containsAll(foreignTags))
        assertTrue(payload.tags.contains(listOf("title", "Renamed")))
    }

    @Test
    fun `blank optional metadata is omitted and set metadata is present`() {
        val blank = buildRelaySetPublishPayload(
            "set-id",
            RelaySetEditorDraft(
                title = "Set",
                description = "  ",
                image = "",
                members = listOf("wss://relay.example"),
            ),
        )
        assertFalse(blank.tags.any { it.firstOrNull() == "description" })
        assertFalse(blank.tags.any { it.firstOrNull() == "image" })

        val set = buildRelaySetPublishPayload(
            "set-id",
            RelaySetEditorDraft(
                title = "Set",
                description = "  Useful relays  ",
                image = " https://cdn.example/set.webp ",
                members = listOf("wss://relay.example"),
            ),
        )
        assertTrue(set.tags.contains(listOf("description", "Useful relays")))
        assertTrue(set.tags.contains(listOf("image", "https://cdn.example/set.webp")))
    }

    @Test
    fun `edit prefill maps every modeled and passthrough field`() {
        val relayTags = listOf(listOf("relay", "wss://relay.example", "write"))
        val foreignTags = listOf(listOf("x-custom", "kept"))
        val draft = relaySetEditorDraft(
            RelaySet(
                dTag = "unchanged-coordinate",
                ownerPubkey = "owner",
                title = "Display Name",
                description = "Description",
                image = "https://cdn.example/image.webp",
                members = listOf("wss://relay.example"),
                relayTags = relayTags,
                foreignTags = foreignTags,
            ),
        )

        assertEquals("unchanged-coordinate", draft.dTag)
        assertEquals("Display Name", draft.title)
        assertEquals("Description", draft.description)
        assertEquals("https://cdn.example/image.webp", draft.image)
        assertEquals(listOf("wss://relay.example"), draft.members)
        assertEquals(relayTags, draft.relayTags)
        assertEquals(foreignTags, draft.foreignTags)
    }

    @Test
    fun `renaming never changes the d tag or mangles the display name`() {
        val draft = RelaySetEditorDraft(
            dTag = "opaque-id-from-another-client",
            title = "Human-friendly name",
            members = listOf("wss://relay.example"),
        )
        val payload = buildRelaySetPublishPayload(draft.dTag!!, draft)

        assertEquals("opaque-id-from-another-client", payload.dTag)
        assertEquals(listOf("d", "opaque-id-from-another-client"), payload.tags.first())
        assertTrue(payload.tags.contains(listOf("title", "Human-friendly name")))
    }

    @Test
    fun `new d tag base is stable and has a non-ascii fallback`() {
        assertEquals("bitcoin-relays", relaySetDTagBase("  Bitcoin  Relays! "))
        assertEquals("relay-set", relaySetDTagBase("\u041c\u043e\u0438 \u0440\u0435\u043b\u0435\u0438"))
    }
}
