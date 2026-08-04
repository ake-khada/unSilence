package com.unsilence.app.data.repository

import com.unsilence.app.data.relay.NostrJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMetadataPolicyTest {
    @Test
    fun `merge preserves every unknown JSON value while known fields win`() {
        val existing = """{
            "name":"Old",
            "lud06":"lnurl-byte-for-byte",
            "bot":true,
            "custom":{"nested":[1,"two"]}
        }""".trimIndent()

        val merged = mergeProfileMetadata(
            existingContent = existing,
            edited = fields(name = " New ", website = " https://example.com "),
        )

        val before = NostrJson.parseToJsonElement(existing).jsonObject
        val after = NostrJson.parseToJsonElement(requireNotNull(merged)).jsonObject
        assertEquals("New", after["name"]?.jsonPrimitive?.content)
        assertEquals("https://example.com", after["website"]?.jsonPrimitive?.content)
        assertEquals(before["lud06"], after["lud06"])
        assertEquals(before["bot"], after["bot"])
        assertEquals(before["custom"], after["custom"])
    }

    @Test
    fun `blank known fields remove canonical keys and parser aliases`() {
        val existing = """{
            "display_name":"canonical",
            "displayName":"legacy",
            "picture":"canonical-picture",
            "avatar":"legacy-avatar",
            "image":"legacy-image",
            "website":"https://old.example",
            "custom":"keep"
        }""".trimIndent()

        val after = NostrJson.parseToJsonElement(
            requireNotNull(mergeProfileMetadata(existing, fields())),
        ).jsonObject

        assertFalse("display_name" in after)
        assertFalse("displayName" in after)
        assertFalse("picture" in after)
        assertFalse("avatar" in after)
        assertFalse("image" in after)
        assertFalse("website" in after)
        assertEquals("keep", after["custom"]?.jsonPrimitive?.content)
    }

    @Test
    fun `all eight form fields are written canonically`() {
        val after = NostrJson.parseToJsonElement(
            requireNotNull(
                mergeProfileMetadata(
                    "{}",
                    EditableProfileMetadata(
                        name = "name",
                        displayName = "display",
                        about = "about",
                        picture = "picture",
                        banner = "banner",
                        nip05 = "nip05",
                        lud16 = "lud16",
                        website = "website",
                    ),
                ),
            ),
        ).jsonObject

        assertEquals(
            setOf("name", "display_name", "about", "picture", "banner", "nip05", "lud16", "website"),
            after.keys,
        )
    }

    @Test
    fun `non-object or malformed retained metadata is refused`() {
        assertNull(mergeProfileMetadata("[]", fields(name = "new")))
        assertNull(mergeProfileMetadata("not-json", fields(name = "new")))
    }

    @Test
    fun `untouched stale form fields retain relay-fresh known and unknown values`() {
        val original = fields(name = "Old", website = "")
        val edited = original.copy(name = "New")
        val fresh = """{
            "name":"Old",
            "website":"https://added-elsewhere.example",
            "pronouns":"preserve test"
        }""".trimIndent()

        val after = NostrJson.parseToJsonElement(
            requireNotNull(mergeProfileMetadata(fresh, edited, original)),
        ).jsonObject

        assertEquals("New", after["name"]?.jsonPrimitive?.content)
        assertEquals("https://added-elsewhere.example", after["website"]?.jsonPrimitive?.content)
        assertEquals("preserve test", after["pronouns"]?.jsonPrimitive?.content)
    }

    @Test
    fun `known field explicitly cleared after form opened is removed`() {
        val original = fields(website = "https://old.example")
        val edited = original.copy(website = "")

        val after = NostrJson.parseToJsonElement(
            requireNotNull(
                mergeProfileMetadata(
                    "{\"website\":\"https://old.example\",\"custom\":true}",
                    edited,
                    original,
                ),
            ),
        ).jsonObject

        assertFalse("website" in after)
        assertTrue(after["custom"]?.jsonPrimitive?.content == "true")
    }

    private fun fields(
        name: String = "",
        website: String = "",
    ) = EditableProfileMetadata(
        name = name,
        displayName = "",
        about = "",
        picture = "",
        banner = "",
        nip05 = "",
        lud16 = "",
        website = website,
    )
}
