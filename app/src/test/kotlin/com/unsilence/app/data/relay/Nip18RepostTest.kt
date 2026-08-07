package com.unsilence.app.data.relay

import com.unsilence.app.data.model.RepostPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class Nip18RepostTest {
    @Test
    fun `valid signed embedded event becomes verified`() {
        val verified = parse(SignedEventFixture.wireJson())

        assertTrue(verified.payload is RepostPayload.VerifiedEmbedded)
        val inner = (verified.payload as RepostPayload.VerifiedEmbedded).event
        assertEquals(SignedEventFixture.ID, inner.id)
        assertEquals(SignedEventFixture.PUBKEY, inner.pubkey)
    }

    @Test
    fun `complete embedded event with corrupted signature degrades to reference only`() {
        val corrupted = SignedEventFixture.SIGNATURE.dropLast(1) + "0"
        val signatureForgery = parse(SignedEventFixture.wireJson(signature = corrupted))

        assertTrue(signatureForgery.payload is RepostPayload.ReferenceOnly)
        assertEquals(SignedEventFixture.ID, signatureForgery.targetId)
        assertNull(signatureForgery.targetAuthorHint)
    }

    @Test
    fun `complete embedded event with swapped pubkey degrades to reference only`() {
        val swappedPubkey = "8" + SignedEventFixture.PUBKEY.drop(1)
        val authorForgery = parse(SignedEventFixture.wireJson(pubkey = swappedPubkey))

        assertTrue(authorForgery.payload is RepostPayload.ReferenceOnly)
        assertEquals(SignedEventFixture.ID, authorForgery.targetId)
        assertNull(authorForgery.targetAuthorHint)
    }

    @Test
    fun `only verified inner content may drive downstream text scans`() {
        val validInfo = parse(SignedEventFixture.wireJson())
        val invalidInfo = parse(
            SignedEventFixture.wireJson(
                signature = SignedEventFixture.SIGNATURE.dropLast(1) + "0",
            ),
        )
        val wrapper = SignedEventFixture.event().copy(
            kind = 16,
            content = SignedEventFixture.wireJson(),
            tags = listOf(listOf("e", SignedEventFixture.ID), listOf("k", "1")),
        )

        assertEquals(
            SignedEventFixture.CONTENT,
            wrapper.copy(repostInfo = validInfo).authenticatedContentOrNull(),
        )
        assertNull(wrapper.copy(repostInfo = invalidInfo).authenticatedContentOrNull())
    }

    @Test
    fun `mismatched wrapper reference is rejected before crypto`() {
        var verifierCalls = 0
        val wrapperTarget = "a".repeat(64)
        val result = requireNotNull(
            parseRepostInfo(
                rawKind = 16,
                content = SignedEventFixture.wireJson(),
                tags = listOf(listOf("e", wrapperTarget), listOf("k", "1")),
                verifyEmbedded = {
                    verifierCalls += 1
                    true
                },
            ),
        )

        assertEquals(RepostPayload.ReferenceOnly, result.payload)
        assertEquals(wrapperTarget, result.targetId)
        assertEquals(0, verifierCalls)
    }

    @Test
    fun `embedded object missing required tags is only a reference hint`() {
        var verifierCalls = 0
        val complete = NostrJson.parseToJsonElement(SignedEventFixture.wireJson()).jsonObject
        val withoutTags = JsonObject(complete.filterKeys { it != "tags" }).toString()

        val result = requireNotNull(
            parseRepostInfo(
                rawKind = 16,
                content = withoutTags,
                tags = listOf(listOf("e", SignedEventFixture.ID), listOf("k", "1")),
                verifyEmbedded = {
                    verifierCalls += 1
                    true
                },
            ),
        )

        assertEquals(RepostPayload.ReferenceOnly, result.payload)
        assertEquals(SignedEventFixture.ID, result.targetId)
        assertEquals(0, verifierCalls)
    }

    @Test
    fun `uppercase address tag is not accepted as a NIP-18 target`() {
        val author = "b".repeat(64)
        val result = requireNotNull(
            parseRepostInfo(
                rawKind = 16,
                content = "",
                tags = listOf(listOf("A", "30023:$author:article"), listOf("k", "30023")),
            ),
        )

        assertNull(result.addressCoordinate)
        assertEquals(RepostPayload.ReferenceOnly, result.payload)
    }

    private fun parse(content: String) = requireNotNull(
        parseRepostInfo(
            rawKind = 16,
            content = content,
            tags = listOf(
                listOf("e", SignedEventFixture.ID, "wss://nos.lol"),
                listOf("k", "1"),
            ),
        ),
    )
}
