package com.unsilence.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [buildVideoRenderModels] — the insert-time sidecar path that
 * pre-computes video metadata (poster, aspect, dims) consumed by
 * VideoPlaybackScope for autoplay eligibility (`noteIdsWithVideo`).
 *
 * This is a SEPARATE code path from ContentParser.tokenize: it has its own
 * kind-6/16 repost unwrap, so it needs its own coverage. The wrapper `tags`
 * are deliberately empty in the repost cases so the video can ONLY be derived
 * by unwrapping the embedded inner-event JSON — a true regression guard.
 */
class VideoRenderModelTest {

    private val innerVideoEvent = """{"id":"inner","pubkey":"${"b".repeat(64)}","kind":21,""" +
        """"content":"","created_at":900,""" +
        """"tags":[["imeta","url https://vid.host/clip.mp4","m video/mp4","dim 1920x1080","image https://vid.host/poster.jpg"]]}"""

    @Test
    fun `kind 16 embedded repost derives inner video metadata from embedded json`() {
        val models = buildVideoRenderModels(kind = 16, content = innerVideoEvent, tags = emptyList())
        assertEquals(1, models.size)
        val m = models[0]
        assertEquals("https://vid.host/clip.mp4", m.videoUrl)
        assertEquals(1920f / 1080f, m.aspectRatio, 0.01f)
        assertEquals("https://vid.host/poster.jpg", m.posterUrl)
        assertEquals(1920, m.widthPx)
        assertEquals(1080, m.heightPx)
    }

    @Test
    fun `kind 6 embedded repost derives inner video metadata from embedded json`() {
        val models = buildVideoRenderModels(kind = 6, content = innerVideoEvent, tags = emptyList())
        assertEquals(1, models.size)
        assertEquals("https://vid.host/clip.mp4", models[0].videoUrl)
        assertEquals("https://vid.host/poster.jpg", models[0].posterUrl)
    }

    @Test
    fun `native video note reads imeta from its own tags`() {
        val tags = listOf(
            listOf("imeta", "url https://vid.host/native.mp4", "m video/mp4", "dim 1280x720"),
        )
        val models = buildVideoRenderModels(kind = 1, content = "", tags = tags)
        assertEquals(1, models.size)
        assertEquals("https://vid.host/native.mp4", models[0].videoUrl)
        assertEquals(1280f / 720f, models[0].aspectRatio, 0.01f)
    }

    @Test
    fun `kind 22 sidecar marks native and embedded short videos`() {
        val tags = listOf(
            listOf("imeta", "url https://vid.host/short.mp4", "m video/mp4", "dim 720x1280"),
        )
        val native = buildVideoRenderModels(kind = 22, content = "", tags = tags)
        val embedded = """{"id":"short","pubkey":"${"c".repeat(64)}","kind":22,"content":"","tags":[["imeta","url https://vid.host/short.mp4","m video/mp4","dim 720x1280"]]}"""
        val repost = buildVideoRenderModels(kind = 16, content = embedded, tags = emptyList())

        assertTrue(native.single().shortForm)
        assertTrue(repost.single().shortForm)
    }

    @Test
    fun `kind 16 embedded repost of addressable short keeps short-form model`() {
        val embedded = """{"id":"short","pubkey":"${"d".repeat(64)}","kind":34236,"content":"","tags":[["d","clip"],["imeta","url https://media.divine.video/${"e".repeat(64)}","m video/mp4","dim 1080x1920"]]}"""

        val model = buildVideoRenderModels(kind = 16, content = embedded, tags = emptyList()).single()

        assertTrue(model.shortForm)
        assertEquals(1080f / 1920f, model.aspectRatio, 0.01f)
    }

    @Test
    fun `imeta video mime accepts extensionless media url`() {
        val mediaUrl = "https://cdn.example/${"a".repeat(64)}"
        val tags = listOf(
            listOf(
                "imeta",
                "url $mediaUrl",
                "m video/mp4",
                "dim 640x360",
                "image https://cdn.example/poster.jpg",
            ),
        )

        val models = buildVideoRenderModels(kind = 1, content = "", tags = tags)

        assertEquals(1, models.size)
        assertEquals(mediaUrl, models[0].videoUrl)
        assertEquals(640f / 360f, models[0].aspectRatio, 0.01f)
        assertEquals("https://cdn.example/poster.jpg", models[0].posterUrl)
    }

    @Test
    fun `YouTube imeta never enters ExoPlayer sidecar`() {
        val tags = listOf(
            listOf("imeta", "url https://www.youtube.com/watch?v=dQw4w9WgXcQ", "m video/mp4"),
        )

        assertTrue(buildVideoRenderModels(kind = 22, content = "", tags = tags).isEmpty())
    }

    @Test
    fun `content video URL with query and punctuation still reuses imeta metadata`() {
        val tags = listOf(
            listOf(
                "imeta",
                "url https://vid.host/native.mp4",
                "dim 720x1280",
                "image https://vid.host/poster.jpg",
            ),
        )
        val models = buildVideoRenderModels(
            kind = 1,
            content = "watch https://vid.host/native.mp4?download=1.",
            tags = tags,
        )

        assertEquals(1, models.size)
        assertEquals("https://vid.host/native.mp4?download=1", models[0].videoUrl)
        assertEquals(720f / 1280f, models[0].aspectRatio, 0.01f)
        assertEquals("https://vid.host/poster.jpg", models[0].posterUrl)
        assertEquals(720, models[0].widthPx)
        assertEquals(1280, models[0].heightPx)
    }

    @Test
    fun `repost with no inner video yields no models`() {
        val embedded = """{"id":"inner","pubkey":"${"b".repeat(64)}","kind":1,"content":"just text","tags":[]}"""
        val models = buildVideoRenderModels(kind = 16, content = embedded, tags = emptyList())
        assertTrue(models.isEmpty())
    }

    @Test
    fun `malformed embedded json does not crash`() {
        val models = buildVideoRenderModels(kind = 16, content = "not json", tags = emptyList())
        assertNotNull(models)
    }
}
