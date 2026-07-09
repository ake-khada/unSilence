package com.unsilence.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSavePolicyTest {

    @Test
    fun filenameUsesSanitizedUrlBasename() {
        assertEquals(
            "my_photo.jpg",
            deriveMediaFilename(
                url = "https://example.com/uploads/my photo.jpg?width=3000",
                mimeType = "image/jpeg",
                kind = SaveMediaKind.IMAGE,
            ),
        )
    }

    @Test
    fun filenameFallsBackToStableHashWhenUrlHasNoBasename() {
        val first = deriveMediaFilename(
            url = "https://example.com/",
            mimeType = "image/png",
            kind = SaveMediaKind.IMAGE,
        )
        val second = deriveMediaFilename(
            url = "https://example.com/",
            mimeType = "image/png",
            kind = SaveMediaKind.IMAGE,
        )

        assertTrue(first.startsWith("image-"))
        assertTrue(first.endsWith(".png"))
        assertEquals(first, second)
    }

    @Test
    fun mimePrefersContentTypeHeader() {
        assertEquals(
            "image/webp",
            resolveMediaMimeType(
                contentTypeHeader = "image/webp; charset=utf-8",
                url = "https://example.com/photo.jpg",
                kind = SaveMediaKind.IMAGE,
            ),
        )
    }

    @Test
    fun mimeFallsBackToUrlExtension() {
        assertEquals(
            "video/quicktime",
            resolveMediaMimeType(
                contentTypeHeader = null,
                url = "https://example.com/clip.mov?download=1",
                kind = SaveMediaKind.VIDEO,
            ),
        )
    }

    @Test
    fun mimeUsesSensibleDefaultWhenUnknown() {
        assertEquals(
            "video/mp4",
            resolveMediaMimeType(
                contentTypeHeader = "application/octet-stream",
                url = "https://example.com/download?id=123",
                kind = SaveMediaKind.VIDEO,
            ),
        )
    }

    @Test
    fun hlsVideoSourcesAreNotSavableByExtensionOrMime() {
        assertFalse(isSavableVideoSource("https://cdn.example.com/live/stream.m3u8?token=abc"))
        assertFalse(
            isSavableVideoSource(
                url = "https://cdn.example.com/live/stream",
                contentTypeHeader = "application/vnd.apple.mpegurl",
            ),
        )
        assertTrue(isSavableVideoSource("https://cdn.example.com/video.mp4"))
    }
}
