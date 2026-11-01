package com.unsilence.app.data.blossom

import org.junit.Assert.*
import org.junit.Test

class AvatarFallbackPolicyTest {
    private val hash = "ab".repeat(32)
    private val original = "https://original.example/$hash.gif"
    private val blob = AvatarFallbackPolicy.blob(original)!!

    @Test fun `stored author servers precede opted in servers`() {
        assertEquals(listOf("https://declared.example/$hash.gif", "https://chosen.example/$hash.gif"),
            AvatarFallbackPolicy.candidates(blob, original, listOf("https://declared.example"),
                AvatarFallbackPreferences(true, listOf("https://chosen.example"))))
    }
    @Test fun `default off applies only to local fallback and destinations are deduplicated`() {
        assertFalse(AvatarFallbackPreferences().enabled)
        assertEquals(listOf("https://author.example/$hash.gif"), AvatarFallbackPolicy.candidates(blob, original,
            listOf("https://original.example", "https://author.example", "https://author.example"), AvatarFallbackPreferences()))
    }
    @Test fun `hash is a complete filename and BUD10 URIs are outside this slice`() {
        assertEquals(hash, blob.hash)
        for (url in listOf("https://a.example/image?x=$hash", "https://a.example/prefix$hash.jpg",
            "https://a.example/$hash.jpg/path", "https://a.example/${hash.dropLast(1)}.jpg", "file:///$hash.jpg",
            "blossom:$hash.gif?xs=hint.example")) assertNull(url, AvatarFallbackPolicy.blob(url))
    }
    @Test fun `server inputs reject bare labels private endpoints credentials paths queries and controls`() {
        for (url in listOf("primal", "https://primal", "http://public.example", "https://localhost", "https://127.0.0.1",
            "https://u:p@a.example", "https://a.example/path", "https://a.example/?x=1", "https://a.example/#x", "https://a.example\n")) {
            assertNull(url, AvatarFallbackPolicy.server(url))
        }
        assertEquals("https://xn--bcher-kva.example", AvatarFallbackPolicy.server("https://bücher.example/"))
    }
    @Test fun `untrusted lists never exceed six candidate budget`() {
        val many = (1..100).map { "https://s$it.example" }
        assertEquals(6, AvatarFallbackPolicy.candidates(blob, original, many, AvatarFallbackPreferences(true, many.reversed())).size)
    }
}
