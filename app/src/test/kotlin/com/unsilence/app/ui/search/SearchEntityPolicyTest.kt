package com.unsilence.app.ui.search

import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.navigation.DeepLinkTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEntityPolicyTest {
    private val pubkey = "11".repeat(32)
    private val eventId = "22".repeat(32)
    private val profile = DeepLinkTarget.Profile(pubkey)
    private val targets = mapOf(
        "nostr:npub1value" to profile,
        "nostr:nprofile1value" to DeepLinkTarget.Profile(pubkey, listOf("wss://hint.example")),
        "nostr:note1value" to DeepLinkTarget.Note(eventId),
        "nostr:nevent1value" to DeepLinkTarget.Note(eventId, listOf("wss://hint.example")),
        "nostr:naddr1value" to DeepLinkTarget.Address(34236, pubkey, "clip"),
    )
    private val resolver: (String) -> DeepLinkTarget? = targets::get

    @Test
    fun `all navigable entity types resolve with and without nostr prefix`() {
        targets.forEach { (uri, target) ->
            val bare = uri.removePrefix("nostr:")
            assertEquals(SearchEntityInput.Resolved(target), detectSearchEntityInput(bare, resolver))
            assertEquals(SearchEntityInput.Resolved(target), detectSearchEntityInput(uri, resolver))
            assertEquals(
                SearchEntityInput.Resolved(target),
                detectSearchEntityInput("NOSTR:$bare", resolver),
            )
        }
    }

    @Test
    fun `bare and prefixed hex pubkeys resolve as profiles`() {
        val upper = pubkey.uppercase()
        assertEquals(SearchEntityInput.Resolved(profile), detectSearchEntityInput(upper))
        assertEquals(SearchEntityInput.Resolved(profile), detectSearchEntityInput("nostr:$upper"))
    }

    @Test
    fun `secret entities are rejected without invoking the resolver`() {
        var resolverCalled = false
        val trackingResolver: (String) -> DeepLinkTarget? = {
            resolverCalled = true
            null
        }

        assertEquals(
            SearchEntityInput.RejectedSecret,
            detectSearchEntityInput("nsec1supersecret", trackingResolver),
        )
        assertEquals(
            SearchEntityInput.RejectedSecret,
            detectSearchEntityInput("nostr:nsec1supersecret", trackingResolver),
        )
        assertTrue(!resolverCalled)
    }

    @Test
    fun `ordinary and hex-like text stays in normal search`() {
        val negatives = listOf(
            "11".repeat(31) + "1",
            "deadbeef-not-a-key",
            "g".repeat(64),
            "alice",
            "npuberty",
            "nostr enthusiast",
            "",
        )
        negatives.forEach { query ->
            assertEquals(SearchEntityInput.None, detectSearchEntityInput(query, resolver))
        }
    }

    @Test
    fun `resolved profile is pinned and removed from people results below it`() {
        val direct = UserEntity(pubkey = pubkey, name = "Direct")
        val other = UserEntity(pubkey = "33".repeat(32), name = "Other")

        assertEquals(listOf(other), peopleBelowEntityResult(profile, listOf(direct, other)))
        assertEquals(
            listOf(direct, other),
            peopleBelowEntityResult(DeepLinkTarget.Note(eventId), listOf(direct, other)),
        )
    }
}
