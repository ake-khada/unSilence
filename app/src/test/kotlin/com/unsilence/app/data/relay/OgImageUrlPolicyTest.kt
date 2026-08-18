package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `guarded JPEG response promotes malformed-suffix Blossom link to direct image`() {
        val url = "https://npub1vgvgnhs4rmffcvjzf0a32qrxmsn5szxas4zcnzmzz48zqk2aa26s762aqj.blossom.band/" +
            "20f968408f96eb2f6e71bc4f22a4e799b2acd57d4577e5fd055798eda41d175b.jpgiyg"

        val metadata = OgFetcher.directImageMetadata(url, "image/jpeg; charset=binary")

        assertNotNull(metadata)
        assertEquals(url, metadata!!.imageUrl)
        assertTrue(metadata.isDirectImage)
        assertNull(metadata.title)
    }

    @Test
    fun `HTML SVG and video responses are not promoted to direct images`() {
        val url = "https://media.example/blob"

        assertNull(OgFetcher.directImageMetadata(url, "text/html"))
        assertNull(OgFetcher.directImageMetadata(url, "image/svg+xml"))
        assertNull(OgFetcher.directImageMetadata(url, "video/mp4"))
        assertNull(OgFetcher.directImageMetadata("https://127.0.0.1/blob", "image/jpeg"))
    }
}
