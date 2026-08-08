package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OgImageUrlPolicyTest {
    @Test
    fun `private and special-use OG images are rejected at extraction`() {
        val page = "https://publisher.example/article"
        val rejected = listOf(
            "https://127.0.0.1/image.jpg",
            "https://169.254.169.254/latest/meta-data/",
            "https://10.0.0.1/image.jpg",
            "https://172.16.0.1/image.jpg",
            "https://192.168.1.1/image.jpg",
            "https://100.64.0.1/image.jpg",
            "https://[fe80::1]/image.jpg",
            "https://[fd00::1]/image.jpg",
        )

        rejected.forEach { raw ->
            assertNull("expected $raw to be rejected", OgFetcher.resolveAllowedImageUrl(raw, page))
        }
    }

    @Test
    fun `non-network schemes cannot escape relative URL resolution`() {
        val page = "https://publisher.example/articles/post"
        assertNull(OgFetcher.resolveAllowedImageUrl("file:/etc/passwd", page))
        assertNull(OgFetcher.resolveAllowedImageUrl("content://media/external/images/1", page))
        assertNull(OgFetcher.resolveAllowedImageUrl("javascript:alert(1)", page))
    }

    @Test
    fun `clearnet HTTP is rejected while onion HTTP remains permitted`() {
        assertNull(
            OgFetcher.resolveAllowedImageUrl(
                "http://cdn.example/image.jpg",
                "https://publisher.example/article",
            ),
        )
        assertEquals(
            "http://images.example.onion/image.jpg",
            OgFetcher.resolveAllowedImageUrl(
                "http://images.example.onion/image.jpg",
                "http://publisher.example.onion/article",
            ),
        )
    }

    @Test
    fun `normal public and relative image URLs are preserved`() {
        val absolute = "https://cdn.example/image.jpg?width=1200"
        assertEquals(
            absolute,
            OgFetcher.resolveAllowedImageUrl(absolute, "https://publisher.example/article"),
        )
        assertEquals(
            "https://publisher.example/images/card.jpg",
            OgFetcher.resolveAllowedImageUrl(
                "../images/card.jpg",
                "https://publisher.example/articles/post",
            ),
        )
    }
}
