package com.unsilence.app.data.model

import com.unsilence.app.data.memory.FeedRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [buildVideoRenderModels] — both the insert-time sidecar path
 * and the on-composition fallback consumed by VideoPlaybackScope.
 *
 * This is a SEPARATE code path from ContentParser.tokenize: it has its own
 * kind-6/16 repost unwrap, so it needs its own coverage. The wrapper `tags`
 * are deliberately empty in the repost cases so the video can ONLY be derived
 * by unwrapping the embedded inner-event JSON — a true regression guard.
 */
class VideoRenderModelTest {

    private fun row(content: String, tags: String = "[]") = FeedRow(
        id = "row",
        pubkey = "a".repeat(64),
        kind = 1,
        content = content,
        createdAt = 1L,
        tags = tags,
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        zapTotalSats = 0L,
        authorName = null,
        authorDisplayName = null,
        authorPicture = null,
        authorNip05 = null,
        reactionCount = 0,
        replyCount = 0,
        repostCount = 0,
        zapCount = 0,
    )

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

    // ── Insert-time DoS bounds ────────────────────────────────────────────────

    @Test
    fun `video urls are capped at 8 after dedup`() {
        val content = (1..10).joinToString(" ") { "https://h/v$it.mp4" }
        val models = buildVideoRenderModels(kind = 1, content = content, tags = emptyList())
        assertEquals(8, models.size)
        // Order stable: first occurrence wins, so the FIRST 8 URLs survive.
        assertEquals((1..8).map { "https://h/v$it.mp4" }, models.map { it.videoUrl })
    }

    @Test
    fun `near-duplicate video urls dedup to first occurrence`() {
        val models = buildVideoRenderModels(
            kind = 1,
            content = "https://h/b.mp4 https://h/a.mp4?dl=1 https://h/a.mp4?dl=2 https://h/a.mp4#frag",
            tags = emptyList(),
        )
        assertEquals(2, models.size)
        assertEquals("https://h/b.mp4", models[0].videoUrl)
        assertEquals("https://h/a.mp4?dl=1", models[1].videoUrl)
    }

    @Test
    fun `content beyond the 20k scan cap is not scanned`() {
        // 512KB relay content: the regex pass must be bounded by the scan cap, so a
        // video URL past 20k chars is never seen (and the pathological input returns fast).
        val content = "https://h/first.mp4 " + "x".repeat(512 * 1024) + " https://h/late.mp4"
        val models = buildVideoRenderModels(kind = 1, content = content, tags = emptyList())
        assertEquals(1, models.size)
        assertEquals("https://h/first.mp4", models[0].videoUrl)
    }

    @Test
    fun `feed row fallback uses the same scan and output bounds`() {
        val content = (1..10).joinToString(" ") { "https://h/v$it.mp4" } +
            "x".repeat(20_000) + " https://h/late.mp4"

        val models = buildVideoRenderModels(row(content))

        assertEquals((1..8).map { "https://h/v$it.mp4" }, models.map { it.videoUrl })
    }
}
