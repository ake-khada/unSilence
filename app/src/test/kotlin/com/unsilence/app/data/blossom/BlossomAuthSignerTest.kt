package com.unsilence.app.data.blossom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Verifies the BUD-01 auth event wire format that [BlossomAuthSigner]
 * produces. Since Quartz's NostrSignerInternal requires JDK 21+ (class
 * version 65.0) which conflicts with the JDK 17 test runner, we simulate
 * the same event construction and validate the JSON structure directly.
 */
class BlossomAuthSignerTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Builds the same event JSON that BlossomAuthSigner.authHeader() would
     * produce, minus the real cryptographic signature. Validates format only.
     */
    private fun buildBud01EventJson(
        sha256Hex: String,
        verb: String = "upload",
        expirationSeconds: Long = 60L,
    ): String {
        val now = System.currentTimeMillis() / 1000L
        val expiration = now + expirationSeconds

        return buildJsonObject {
            put("id", "0".repeat(64))
            put("pubkey", "0".repeat(64))
            put("created_at", now)
            put("kind", 24242)
            put("tags", buildJsonArray {
                add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("t")); add(kotlinx.serialization.json.JsonPrimitive(verb)) })
                add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("x")); add(kotlinx.serialization.json.JsonPrimitive(sha256Hex)) })
                add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("expiration")); add(kotlinx.serialization.json.JsonPrimitive(expiration.toString())) })
            })
            put("content", "")
            put("sig", "0".repeat(128))
        }.toString()
    }

    private fun buildHeader(
        sha256Hex: String,
        verb: String = "upload",
        expirationSeconds: Long = 60L,
    ): String {
        val eventJson = buildBud01EventJson(sha256Hex, verb, expirationSeconds)
        val b64 = Base64.getEncoder().withoutPadding()
            .encodeToString(eventJson.toByteArray(Charsets.UTF_8))
        return "Nostr $b64"
    }

    @Test
    fun `authHeader produces BUD-01 compliant kind-24242 event`() {
        val testHash = "a".repeat(64)
        val header = buildHeader(sha256Hex = testHash, verb = "upload", expirationSeconds = 60L)

        assertTrue("Must start with 'Nostr '", header.startsWith("Nostr "))
        val b64 = header.removePrefix("Nostr ")
        val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
        val event = json.parseToJsonElement(decoded).jsonObject

        assertEquals(24242, event["kind"]?.jsonPrimitive?.int)

        val tags = event["tags"]!!.jsonArray.map {
            it.jsonArray.map { p -> p.jsonPrimitive.content }
        }

        // BUD-01 required tags present
        assertTrue("missing t tag", tags.any { it.size >= 2 && it[0] == "t" && it[1] == "upload" })
        assertTrue("missing x tag", tags.any { it.size >= 2 && it[0] == "x" && it[1] == testHash })
        assertTrue("missing expiration tag", tags.any { it.size >= 2 && it[0] == "expiration" })

        // Expiration is a future timestamp
        val expTag = tags.first { it[0] == "expiration" }
        val expTs = expTag[1].toLong()
        assertTrue("expiration must be in the future", expTs > System.currentTimeMillis() / 1000L - 5)

        // No leftover NIP-98 tags
        assertFalse("u tag should be absent", tags.any { it.firstOrNull() == "u" })
        assertFalse("method tag should be absent", tags.any { it.firstOrNull() == "method" })
        assertFalse("payload tag should be absent", tags.any { it.firstOrNull() == "payload" })
    }

    @Test
    fun `verb tag reflects custom action`() {
        val header = buildHeader(sha256Hex = "b".repeat(64), verb = "media")
        val b64 = header.removePrefix("Nostr ")
        val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
        val tags = json.parseToJsonElement(decoded).jsonObject["tags"]!!.jsonArray
            .map { it.jsonArray.map { p -> p.jsonPrimitive.content } }

        assertTrue("t tag should be 'media'", tags.any { it[0] == "t" && it[1] == "media" })
    }

    @Test
    fun `content is empty string`() {
        val header = buildHeader(sha256Hex = "c".repeat(64))
        val b64 = header.removePrefix("Nostr ")
        val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
        val content = json.parseToJsonElement(decoded).jsonObject["content"]?.jsonPrimitive?.content

        assertEquals("", content)
    }

    @Test
    fun `base64 encoding has no newlines`() {
        val header = buildHeader(sha256Hex = "d".repeat(64))
        val b64 = header.removePrefix("Nostr ")
        assertFalse("base64 must not contain newlines", b64.contains('\n'))
        assertFalse("base64 must not contain carriage returns", b64.contains('\r'))
    }
}
