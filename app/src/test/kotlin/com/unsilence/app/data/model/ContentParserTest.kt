package com.unsilence.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ContentParser] — the single-pass tokenizer that produces
 * [EventModel] from raw event fields.
 *
 * ContentParser is a pure function (no Android dependencies). Tests run
 * directly on JVM.
 */
class ContentParserTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun parse(
        content: String,
        kind: Int = 1,
        tagsJson: String = "[]",
        id: String = "test-id",
        pubkey: String = "a".repeat(64),
        createdAt: Long = 1000L,
        relayUrl: String = "wss://relay.test",
        replyToId: String? = null,
        rootId: String? = null,
        hasContentWarning: Boolean = false,
        contentWarningReason: String? = null,
    ): EventModel = ContentParser.parse(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        tagsJson = tagsJson,
        createdAt = createdAt,
        relayUrl = relayUrl,
        replyToId = replyToId,
        rootId = rootId,
        hasContentWarning = hasContentWarning,
        contentWarningReason = contentWarningReason,
    )

    // ── Plain text ──────────────────────────────────────────────────────────

    @Test
    fun `plain text produces single Text segment`() {
        val model = parse("Hello, world!")
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.Text)
        assertEquals("Hello, world!", (model.segments[0] as Segment.Text).text)
    }

    @Test
    fun `blank content produces empty segments`() {
        val model = parse("")
        assertTrue(model.segments.isEmpty())
    }

    @Test
    fun `whitespace-only content produces empty segments`() {
        val model = parse("   ")
        assertTrue(model.segments.isEmpty())
    }

    // ── Image URLs ──────────────────────────────────────────────────────────

    @Test
    fun `image URL is parsed as Image segment`() {
        val model = parse("check this https://example.com/photo.jpg neat!")
        assertEquals(3, model.segments.size)
        assertTrue(model.segments[0] is Segment.Text)
        assertTrue(model.segments[1] is Segment.Image)
        assertTrue(model.segments[2] is Segment.Text)
        assertEquals("https://example.com/photo.jpg", (model.segments[1] as Segment.Image).url)
    }

    @Test
    fun `multiple images grouped in manifest`() {
        val content = "https://a.com/1.jpg https://b.com/2.png"
        val model = parse(content)
        assertEquals(2, model.media.images.size)
        assertEquals("https://a.com/1.jpg", model.media.images[0].url)
        assertEquals("https://b.com/2.png", model.media.images[1].url)
    }

    @Test
    fun `nostr build CDN URL is treated as image`() {
        val model = parse("https://image.nostr.build/abc123")
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.Image)
    }

    @Test
    fun `image with query params is parsed`() {
        val model = parse("https://cdn.example.com/photo.webp?w=800&h=600")
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.Image)
    }

    // ── Video URLs ──────────────────────────────────────────────────────────

    @Test
    fun `mp4 URL is parsed as Video segment`() {
        val model = parse("https://video.host/clip.mp4")
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.Video)
        assertEquals("https://video.host/clip.mp4", (model.segments[0] as Segment.Video).model.videoUrl)
    }

    @Test
    fun `HLS m3u8 URL is parsed as Video`() {
        val model = parse("https://live.host/stream.m3u8")
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.Video)
    }

    @Test
    fun `video URL default aspect ratio is 16 by 9`() {
        val model = parse("https://video.host/clip.mp4")
        val video = model.segments[0] as Segment.Video
        assertEquals(16f / 9f, video.model.aspectRatio, 0.01f)
    }

    @Test
    fun `video URL with query and trailing punctuation keeps imeta poster and dimensions`() {
        val tagsJson = """
            [["imeta","url https://video.host/clip.mp4","dim 720x1280","image https://video.host/poster.jpg"]]
        """.trimIndent()
        val model = parse("https://video.host/clip.mp4?download=1.", tagsJson = tagsJson)

        assertEquals(1, model.segments.size)
        val video = model.segments[0] as Segment.Video
        assertEquals("https://video.host/clip.mp4?download=1", video.model.videoUrl)
        assertEquals(720f / 1280f, video.model.aspectRatio, 0.01f)
        assertEquals("https://video.host/poster.jpg", video.model.posterUrl)
        assertEquals(720, video.model.widthPx)
        assertEquals(1280, video.model.heightPx)
    }

    // ── YouTube URLs ────────────────────────────────────────────────────────

    @Test
    fun `youtube watch URL is parsed as YouTube segment`() {
        val model = parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.YouTube)
        assertEquals("dQw4w9WgXcQ", (model.segments[0] as Segment.YouTube).videoId)
    }

    @Test
    fun `youtube shorts URL is parsed as YouTube segment`() {
        val model = parse("https://youtube.com/shorts/dQw4w9WgXcQ")
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.YouTube)
        assertEquals("dQw4w9WgXcQ", (model.segments[0] as Segment.YouTube).videoId)
    }

    @Test
    fun `youtu be short URL is parsed as YouTube segment`() {
        val model = parse("https://youtu.be/dQw4w9WgXcQ")
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.YouTube)
    }

    @Test
    fun `youtube URLs appear in manifest youtubes list`() {
        val model = parse("https://youtu.be/dQw4w9WgXcQ")
        assertEquals(1, model.media.youtubes.size)
    }

    // ── Generic link URLs ───────────────────────────────────────────────────

    @Test
    fun `generic https URL becomes Link segment`() {
        val model = parse("Check out https://example.com/article for details")
        assertEquals(3, model.segments.size)
        assertTrue(model.segments[1] is Segment.Link)
        assertEquals("https://example.com/article", (model.segments[1] as Segment.Link).url)
    }

    @Test
    fun `first link URL becomes ogCandidate in manifest`() {
        val model = parse("See https://example.com/page here")
        assertNotNull(model.media.ogCandidate)
        assertEquals("https://example.com/page", model.media.ogCandidate!!.url)
    }

    @Test
    fun `image URL does not become ogCandidate`() {
        val model = parse("https://example.com/photo.jpg")
        assertNull(model.media.ogCandidate)
    }

    // ── Token precedence ────────────────────────────────────────────────────

    @Test
    fun `youtube takes precedence over generic URL`() {
        val model = parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.YouTube)
    }

    @Test
    fun `image takes precedence over generic URL`() {
        val model = parse("https://example.com/pic.png")
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.Image)
    }

    @Test
    fun `video takes precedence over generic URL`() {
        val model = parse("https://example.com/clip.mp4")
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.Video)
    }

    // ── Mixed content ───────────────────────────────────────────────────────

    @Test
    fun `mixed text image and link preserves order`() {
        val content = "Hello https://img.host/a.jpg world https://example.com done"
        val model = parse(content)
        assertEquals(5, model.segments.size)
        assertTrue(model.segments[0] is Segment.Text)
        assertTrue(model.segments[1] is Segment.Image)
        assertTrue(model.segments[2] is Segment.Text)
        assertTrue(model.segments[3] is Segment.Link)
        assertTrue(model.segments[4] is Segment.Text)
    }

    // ── EventModel field mapping ────────────────────────────────────────────

    @Test
    fun `kind 1 sets effective fields same as source`() {
        val pk = "a".repeat(64)
        val model = parse("hello", kind = 1, pubkey = pk, createdAt = 12345L)
        assertEquals(pk, model.pubkey)
        assertEquals(pk, model.sourcePubkey)
        assertEquals(12345L, model.createdAt)
        assertEquals(12345L, model.sourceCreatedAt)
    }

    @Test
    fun `engagementId is rootId for kind 6 when rootId present`() {
        val model = parse("", kind = 6, rootId = "root-abc", id = "repost-id")
        assertEquals("root-abc", model.engagementId)
    }

    @Test
    fun `engagementId is id for kind 6 when rootId null`() {
        val model = parse("", kind = 6, rootId = null, id = "repost-id")
        assertEquals("repost-id", model.engagementId)
    }

    @Test
    fun `engagementId is rootId for kind 16 when rootId present`() {
        val model = parse("", kind = 16, rootId = "root-xyz", id = "grepost-id")
        assertEquals("root-xyz", model.engagementId)
    }

    @Test
    fun `engagementId is id for kind 16 when rootId null`() {
        val model = parse("", kind = 16, rootId = null, id = "grepost-id")
        assertEquals("grepost-id", model.engagementId)
    }

    @Test
    fun `engagementId is id for kind 1`() {
        val model = parse("hello", kind = 1, id = "note-id")
        assertEquals("note-id", model.engagementId)
    }

    @Test
    fun `thread refs carry replyToId and rootId`() {
        val model = parse("reply", replyToId = "parent", rootId = "root")
        assertEquals("parent", model.thread.replyToId)
        assertEquals("root", model.thread.rootId)
    }

    @Test
    fun `content warning flags pass through`() {
        val model = parse("nsfw", hasContentWarning = true, contentWarningReason = "nudity")
        assertTrue(model.warnings.hasContentWarning)
        assertEquals("nudity", model.warnings.reason)
    }

    // ── Kind 6 repost ───────────────────────────────────────────────────────

    @Test
    fun `kind 6 with embedded JSON extracts inner pubkey and content`() {
        val innerPk = "b".repeat(64)
        val embeddedJson = """{"id":"inner","pubkey":"$innerPk","content":"reposted text","created_at":999,"tags":[]}"""
        val model = parse(
            content = embeddedJson,
            kind = 6,
            tagsJson = """[["e","target-id","wss://hint.relay"]]""",
        )
        assertNotNull(model.repost)
        assertEquals(innerPk, model.pubkey)
        assertEquals(999L, model.createdAt)
        assertTrue(model.segments.any { it is Segment.Text && it.text == "reposted text" })
    }

    @Test
    fun `kind 6 with empty content produces repost from e-tag`() {
        val model = parse(
            content = "",
            kind = 6,
            tagsJson = """[["e","target-abc","wss://relay.hint"]]""",
        )
        assertNotNull(model.repost)
        assertEquals("target-abc", model.repost!!.targetId)
        assertEquals("wss://relay.hint", model.repost!!.relayHint)
    }

    @Test
    fun `Mostr repost retains ActivityPub proxy fallback`() {
        val model = parse(
            content = "",
            kind = 6,
            tagsJson = """[["e","target-abc","wss://relay.ditto.pub"],["proxy","https://misskey.io/notes/abc","activitypub"]]""",
        )

        assertEquals("https://misskey.io/notes/abc", model.repost?.proxyUrl)
    }

    @Test
    fun `repost ignores unsafe proxy URL`() {
        val model = parse(
            content = "",
            kind = 6,
            tagsJson = """[["e","target-abc"],["proxy","javascript:alert(1)","activitypub"]]""",
        )

        assertNull(model.repost?.proxyUrl)
    }

    @Test
    fun `kind 1 has null repost`() {
        val model = parse("hello", kind = 1)
        assertNull(model.repost)
    }

    @Test
    fun `NIP 88 poll parses bounded options and settings`() {
        val model = parse(
            content = "Choose a release name",
            kind = 1068,
            tagsJson = """[["option","a1","Aurora"],["option","b2","Beacon"],["polltype","multiplechoice"],["endsAt","1800000000"],["relay","wss://polls.example"]]""",
        )

        assertEquals(listOf("Aurora", "Beacon"), model.poll?.options?.map { it.label })
        assertTrue(model.poll?.multipleChoice == true)
        assertEquals(1_800_000_000L, model.poll?.endsAt)
        assertEquals(listOf("wss://polls.example"), model.poll?.responseRelays)
    }

    @Test
    fun `invalid poll with fewer than two options is not rendered`() {
        val model = parse(
            content = "Incomplete",
            kind = 1068,
            tagsJson = """[["option","a1","Only option"]]""",
        )

        assertNull(model.poll)
    }

    @Test
    fun `poll accepts deployed UUID option ids and legacy close time`() {
        val model = parse(
            content = "Would you rather",
            kind = 1068,
            tagsJson = """[["option","7c2057ff-2d1e-429a-9364-e3c3009895f6","Tor"],["option","3005565d-b55c-480c-ae02-eeceb602cd0b","I2P"],["polltype","single"],["closed_at","1800000100"]]""",
        )

        assertEquals(2, model.poll?.options?.size)
        assertFalse(model.poll?.multipleChoice == true)
        assertEquals(1_800_000_100L, model.poll?.endsAt)
    }

    // ── Kind 30023 article ──────────────────────────────────────────────────

    @Test
    fun `kind 30023 extracts article info from tags`() {
        val tags = """[["title","My Post"],["summary","A summary"],["image","https://img.com/banner.jpg"],["published_at","1700000000"],["d","my-post"]]"""
        val model = parse("Full article text here", kind = 30023, tagsJson = tags)
        assertNotNull(model.article)
        assertEquals("My Post", model.article!!.title)
        assertEquals("A summary", model.article!!.summary)
        assertEquals("https://img.com/banner.jpg", model.article!!.image)
        assertEquals(1700000000L, model.article!!.publishedAt)
        assertEquals("my-post", model.article!!.dTag)
    }

    @Test
    fun `kind 1 has null article info`() {
        val model = parse("hello", kind = 1)
        assertNull(model.article)
    }

    // ── Effective kind: reposted/wrapped long-form detection ─────────────────

    @Test
    fun `kind 6 wrapping a 30023 detects article from inner tags`() {
        val innerTags = """[["title","Wrapped Long-form"],["summary","sum"],["image","https://i/x.jpg"],["d","wrapped-d"]]"""
        val embedded = """{"id":"inner","pubkey":"${"b".repeat(64)}","kind":30023,"created_at":999,"content":"# Heading\n\nbody","tags":$innerTags}"""
        val model = parse(content = embedded, kind = 6, tagsJson = """[["e","target-id"]]""")
        assertEquals(30023, model.effectiveKind)
        assertNotNull(model.article)
        assertEquals("Wrapped Long-form", model.article!!.title)
        assertEquals("wrapped-d", model.article!!.dTag)
        assertNotNull("still a repost for provenance", model.repost)
    }

    @Test
    fun `boosted article model yields engagement coordinate from inner pubkey and d-tag`() {
        // The boosted/embedded path: the inner kind-30023 is NOT in MES, so the
        // engagement coordinate MUST come from the embedded model. This is exactly
        // CardHydrator.engagementTargetFor's derivation — locking it here prevents a
        // regression where dispatch re-derives from a bare id and loses the coord.
        val innerPk = "b".repeat(64)
        val innerTags = """[["title","T"],["d","wrapped-d"]]"""
        val embedded = """{"id":"inner","pubkey":"$innerPk","kind":30023,"created_at":999,"content":"body","tags":$innerTags}"""
        val model = parse(content = embedded, kind = 6, rootId = "target-id", tagsJson = """[["e","target-id"]]""")
        assertEquals(30023, model.effectiveKind)
        assertEquals(innerPk, model.pubkey)               // target author (not reposter)
        assertEquals("target-id", model.engagementId)     // rootId for a repost
        val coord = model.article?.dTag?.let { "30023:${model.pubkey}:$it" }
        assertEquals("30023:$innerPk:wrapped-d", coord)
    }

    @Test
    fun `kind 16 wrapping a 30023 detects article from inner tags`() {
        val innerTags = """[["title","Generic Reposted Article"],["d","gen-d"]]"""
        val embedded = """{"id":"inner","pubkey":"${"b".repeat(64)}","kind":30023,"created_at":999,"content":"body","tags":$innerTags}"""
        val model = parse(content = embedded, kind = 16, tagsJson = """[["e","target-id"],["k","30023"]]""")
        assertEquals(30023, model.effectiveKind)
        assertNotNull(model.article)
        assertEquals("Generic Reposted Article", model.article!!.title)
        assertNotNull(model.repost)
    }

    @Test
    fun `kind 16 wrapping a kind-1 is a note repost not an article`() {
        val embedded = """{"id":"inner","pubkey":"${"b".repeat(64)}","kind":1,"created_at":999,"content":"just a note","tags":[]}"""
        val model = parse(content = embedded, kind = 16, tagsJson = """[["e","target-id"],["k","1"]]""")
        assertEquals(1, model.effectiveKind)
        assertNull(model.article)
        assertNotNull(model.repost)
        assertTrue(model.segments.any { it is Segment.Text && it.text == "just a note" })
    }

    @Test
    fun `kind 16 with no embedded JSON but k=30023 tag has no blank article shell`() {
        val model = parse(content = "", kind = 16, tagsJson = """[["e","target-abc","wss://hint"],["k","30023"]]""")
        assertEquals(30023, model.effectiveKind) // kind still resolved from the k tag
        assertNotNull(model.repost)
        assertEquals("target-abc", model.repost!!.targetId)
        // No embedded JSON and no inner article tags → no real article data → must NOT
        // emit a blank ArticleInfo shell (which would route an empty card to
        // ArticleLayout). Falls through to the repost note stub until the a-tag/naddr
        // resolver (#5) can fetch the real article.
        assertNull(model.article)
    }

    @Test
    fun `plain kind 30023 has effective kind 30023 and an article`() {
        val model = parse("body", kind = 30023, tagsJson = """[["title","T"],["d","d1"]]""")
        assertEquals(30023, model.effectiveKind)
        assertNotNull(model.article)
    }

    @Test
    fun `plain kind 1 has effective kind 1 and no article`() {
        val model = parse("hello", kind = 1)
        assertEquals(1, model.effectiveKind)
        assertNull(model.article)
    }

    @Test
    fun `kind 6 note-repost without inner kind resolves effective kind 1`() {
        val embedded = """{"id":"inner","pubkey":"${"b".repeat(64)}","content":"reposted text","created_at":999,"tags":[]}"""
        val model = parse(content = embedded, kind = 6, tagsJson = """[["e","target-id"]]""")
        assertEquals(1, model.effectiveKind)
        assertNull(model.article)
        assertNotNull(model.repost)
    }

    // ── Imeta integration ───────────────────────────────────────────────────

    @Test
    fun `imeta provides aspect ratio for image`() {
        val tags = """[["imeta","url https://img.host/a.jpg","dim 1200x800"]]"""
        val model = parse("https://img.host/a.jpg", tagsJson = tags)
        assertEquals(1, model.media.images.size)
        val img = model.media.images[0]
        assertEquals(1200f / 800f, img.imetaAspect!!, 0.01f)
    }

    @Test
    fun `imeta provides aspect ratio for video`() {
        val tags = """[["imeta","url https://vid.host/clip.mp4","dim 1920x1080"]]"""
        val model = parse("https://vid.host/clip.mp4", tagsJson = tags)
        assertEquals(1, model.media.videos.size)
        val vid = model.media.videos[0]
        assertEquals(1920f / 1080f, vid.model.aspectRatio, 0.01f)
    }

    // ── NIP-68 (kind 20/21) ─────────────────────────────────────────────────

    @Test
    fun `kind 20 prepends imeta images even with blank content`() {
        val tags = """[["imeta","url https://img.host/photo.jpg","dim 800x600"]]"""
        val model = parse("", kind = 20, tagsJson = tags)
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.Image)
        assertEquals("https://img.host/photo.jpg", (model.segments[0] as Segment.Image).url)
    }

    @Test
    fun `kind 21 prepends imeta videos even with blank content`() {
        val tags = """[["imeta","url https://vid.host/clip.mp4","m video/mp4","dim 1920x1080"]]"""
        val model = parse("", kind = 21, tagsJson = tags)
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.Video)
    }

    @Test
    fun `kind 21 prepends extensionless imeta video when mime is video`() {
        val mediaUrl = "https://cdn.example/${"b".repeat(64)}"
        val tags = """[["imeta","url $mediaUrl","m video/mp4","dim 1920x1080"]]"""
        val model = parse("", kind = 21, tagsJson = tags)
        val video = model.segments.filterIsInstance<Segment.Video>().singleOrNull()
        assertNotNull(video)
        assertEquals(mediaUrl, video!!.model.videoUrl)
        assertEquals(1920f / 1080f, video.model.aspectRatio, 0.01f)
    }

    // ── Reposted NIP-68: imeta prepend must key off effectiveKind, not raw kind ─
    // A kind-6/16 repost wrapping a blank-content kind-21 carries its video only
    // in the INNER imeta tags. The prepend used to gate on the wrapper's raw kind
    // (6/16) and dropped the video entirely; it now keys off effectiveKind (21).

    @Test
    fun `kind 6 wrapping a NIP-68 video prepends the inner imeta video`() {
        val innerPk = "b".repeat(64)
        val embedded = """{"id":"inner","pubkey":"$innerPk","kind":21,"content":"","created_at":900,""" +
            """"tags":[["imeta","url https://vid.host/clip.mp4","m video/mp4","dim 1920x1080","image https://vid.host/poster.jpg"]]}"""
        val model = parse(content = embedded, kind = 6, tagsJson = """[["e","inner"]]""")
        assertEquals(21, model.effectiveKind)
        val video = model.segments.filterIsInstance<Segment.Video>().firstOrNull()
        assertNotNull("reposted kind-21 must surface its inner imeta video", video)
        assertEquals("https://vid.host/clip.mp4", video!!.model.videoUrl)
        assertEquals(1920f / 1080f, video.model.aspectRatio, 0.01f)
        assertEquals("https://vid.host/poster.jpg", video.model.posterUrl)
    }

    @Test
    fun `kind 16 wrapping a NIP-68 video prepends the inner imeta video`() {
        val innerPk = "c".repeat(64)
        val embedded = """{"id":"inner","pubkey":"$innerPk","kind":21,"content":"","created_at":900,""" +
            """"tags":[["imeta","url https://vid.host/clip.mp4","m video/mp4","dim 1280x720"]]}"""
        val model = parse(content = embedded, kind = 16, tagsJson = """[["e","inner"],["k","21"]]""")
        assertEquals(21, model.effectiveKind)
        val video = model.segments.filterIsInstance<Segment.Video>().firstOrNull()
        assertNotNull("generic-reposted kind-21 must surface its inner imeta video", video)
        assertEquals("https://vid.host/clip.mp4", video!!.model.videoUrl)
        assertEquals(1280f / 720f, video.model.aspectRatio, 0.01f)
    }

    @Test
    fun `kind 16 wrapping a NIP-68 picture prepends the inner imeta image`() {
        val innerPk = "d".repeat(64)
        val embedded = """{"id":"inner","pubkey":"$innerPk","kind":20,"content":"","created_at":900,""" +
            """"tags":[["imeta","url https://img.host/photo.jpg","dim 800x600"]]}"""
        val model = parse(content = embedded, kind = 16, tagsJson = """[["e","inner"],["k","20"]]""")
        assertEquals(20, model.effectiveKind)
        val image = model.segments.filterIsInstance<Segment.Image>().firstOrNull()
        assertNotNull("generic-reposted kind-20 must surface its inner imeta image", image)
        assertEquals("https://img.host/photo.jpg", image!!.url)
    }

    // ── Q-tag relay hints ───────────────────────────────────────────────────

    @Test
    fun `q-tag relay hints are not extracted when no q tags`() {
        val model = parse("hello", tagsJson = """[["p","abc"]]""")
        // No quotes, just text
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.Text)
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Test
    fun `malformed tags json does not crash`() {
        val model = parse("hello", tagsJson = "not valid json")
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.Text)
    }

    @Test
    fun `empty tags json array works`() {
        val model = parse("hello", tagsJson = "[]")
        assertEquals(1, model.segments.size)
    }

    @Test
    fun `navigateId is targetId for kind 6`() {
        val model = parse(
            content = "",
            kind = 6,
            id = "repost-wrapper",
            tagsJson = """[["e","original-id"]]""",
        )
        assertEquals("original-id", model.navigateId)
    }

    @Test
    fun `navigateId is id for non-repost`() {
        val model = parse("hello", kind = 1, id = "my-note")
        assertEquals("my-note", model.navigateId)
    }

    // ── Spam-post DoS bound (H-spam) — synthetic hostile fixtures ─────────────
    // MAX_SEGMENTS=150, MAX_PARSE_CHARS=20_000 (private). Capped output is ≤ 150 + 1
    // truncation marker = 151. Both shapes must parse fast and flag truncated.

    @Test
    fun `wall of thousands of URLs is capped to MAX_SEGMENTS plus marker`() {
        // Mechanism A: segment-count explosion (clickable composables).
        val hostile = (1..5_000).joinToString(" ") { "https://x.co/$it" }
        val start = System.nanoTime()
        val model = parse(content = hostile)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("segments bounded, was ${model.segments.size}", model.segments.size <= 151)
        assertTrue("flagged truncated", model.truncated)
        assertTrue("fast, was ${elapsedMs}ms", elapsedMs < 3_000)
    }

    @Test
    fun `single 200KB URL string is input-truncated, not a regex stall`() {
        // Mechanism B: O(content) regex pass on one giant token.
        val hostile = "https://x.com/" + "a".repeat(200_000)
        val start = System.nanoTime()
        val model = parse(content = hostile)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("segments bounded, was ${model.segments.size}", model.segments.size <= 151)
        assertTrue("flagged truncated", model.truncated)
        assertTrue("fast, was ${elapsedMs}ms", elapsedMs < 3_000)
    }

    @Test
    fun `normal post is not truncated`() {
        val model = parse(content = "A normal note with a link https://example.com and a #hashtag.")
        assertFalse("legit content must never be flagged truncated", model.truncated)
        assertTrue(model.segments.size <= 151)
    }

    @Test
    fun `long-form article over the kind-1 cap is NOT truncated`() {
        // ~84k chars of prose — over the 20k default cap, under the 200k article cap.
        // Prose tokenizes to few segments, so the segment cap doesn't trip either.
        val article = "Lorem ipsum dolor sit amet, consectetur. ".repeat(2_000)
        val model = parse(content = article, kind = 30023)
        assertFalse("kind-30023 gets the larger input cap", model.truncated)
    }

    @Test
    fun `same long content as a kind-1 note IS truncated`() {
        // Identical payload, kind-1 → the 20k default cap applies.
        val text = "Lorem ipsum dolor sit amet, consectetur. ".repeat(2_000)
        val model = parse(content = text, kind = 1)
        assertTrue("non-article over 20k chars is truncated", model.truncated)
    }

    // ── Blockquotes (kind-1 render-only) ─────────────────────────────────────

    @Test
    fun `leading quote line emits one BlockQuote with inner text`() {
        val model = parse("> hello")
        assertEquals(1, model.segments.size)
        val bq = model.segments[0]
        assertTrue(bq is Segment.BlockQuote)
        bq as Segment.BlockQuote
        assertEquals(1, bq.segments.size)
        assertEquals("hello", (bq.segments[0] as Segment.Text).text)
    }

    @Test
    fun `quote line with no space after gt strips just the marker`() {
        val model = parse(">hello")
        val bq = model.segments[0] as Segment.BlockQuote
        assertEquals("hello", (bq.segments[0] as Segment.Text).text)
    }

    @Test
    fun `consecutive quote lines group into one BlockQuote`() {
        val model = parse("> a\n> b")
        assertEquals(1, model.segments.size)
        val bq = model.segments[0] as Segment.BlockQuote
        assertEquals("a\nb", (bq.segments[0] as Segment.Text).text)
    }

    @Test
    fun `plain text before and after quote preserves order`() {
        val model = parse("x\n> q\ny")
        assertEquals(3, model.segments.size)
        assertEquals("x", (model.segments[0] as Segment.Text).text)
        assertTrue(model.segments[1] is Segment.BlockQuote)
        assertEquals("y", (model.segments[2] as Segment.Text).text)
    }

    @Test
    fun `mid-line greater-than is not a blockquote`() {
        val model = parse("hello > world")
        assertEquals(1, model.segments.size)
        assertTrue(model.segments[0] is Segment.Text)
        assertEquals("hello > world", (model.segments[0] as Segment.Text).text)
        assertFalse(model.segments.any { it is Segment.BlockQuote })
    }

    @Test
    fun `blockquote preserves link and hashtag inside`() {
        val model = parse("> see https://example.com #tag")
        val bq = model.segments[0] as Segment.BlockQuote
        assertTrue("link preserved in quote", bq.segments.any { it is Segment.Link })
        assertTrue("hashtag preserved in quote", bq.segments.any { it is Segment.Hashtag })
    }

    @Test
    fun `reposted kind-1 note with a quote line emits BlockQuote`() {
        val embedded = """{"id":"inner","pubkey":"${"b".repeat(64)}","kind":1,"created_at":999,"content":"> quoted","tags":[]}"""
        val model = parse(content = embedded, kind = 6, tagsJson = """[["e","target-id"]]""")
        assertEquals(1, model.effectiveKind)
        assertTrue("reposted kind-1 supports blockquotes", model.segments.any { it is Segment.BlockQuote })
    }

    @Test
    fun `kind 30023 starting with a quote line does not emit BlockQuote`() {
        val model = parse(content = "> not a note quote\n\nbody", kind = 30023,
            tagsJson = """[["title","T"],["d","slug"]]""")
        assertFalse(model.segments.any { it is Segment.BlockQuote })
    }

    @Test
    fun `blockquote inner segments count toward the spam segment cap`() {
        // One consecutive quote group with many link/hashtag-bearing lines → far over
        // MAX_SEGMENTS(150) flat → must trip truncation, NOT hide inside one BlockQuote.
        val body = (1..120).joinToString("\n") { "> line $it https://e$it.example.com #t$it" }
        val model = parse(body)
        assertTrue("a wall of quote lines must trip the segment cap (H-spam)", model.truncated)
    }
}
