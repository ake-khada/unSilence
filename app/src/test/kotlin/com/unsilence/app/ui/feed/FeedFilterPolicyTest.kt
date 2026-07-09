package com.unsilence.app.ui.feed

import com.unsilence.app.data.model.ContentWarnings
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.MediaManifest
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.model.ThreadRefs
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.domain.model.ActivityPreset
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.ShowType
import com.unsilence.app.domain.model.withActivityPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedFilterPolicyTest {
    @Test
    fun `classifies cached image segment before URL fallback`() {
        val model = modelWith(listOf(Segment.Image("https://cdn.example/image", null)))

        assertEquals(KindOneMediaType.IMAGE, classifyKindOneMedia("plain text", model))
    }

    @Test
    fun `classifies cached video segment before URL fallback`() {
        val model = modelWith(
            listOf(Segment.Video(VideoRenderModel("https://cdn.example/video", 16f / 9f, null, null, null))),
        )

        assertEquals(KindOneMediaType.VIDEO, classifyKindOneMedia("plain text", model))
    }

    @Test
    fun `falls back to direct media URL extensions when model is absent`() {
        assertEquals(
            KindOneMediaType.IMAGE,
            classifyKindOneMedia("photo https://cdn.example/a/b/cat.jpg?x=1", model = null),
        )
        assertEquals(
            KindOneMediaType.VIDEO,
            classifyKindOneMedia("video https://cdn.example/a/b/clip.mp4", model = null),
        )
        assertEquals(KindOneMediaType.TEXT, classifyKindOneMedia("just a note", model = null))
    }

    @Test
    fun `show type matching respects kind one media and direct media kinds`() {
        val imagesOnly = FeedFilter(showTypes = setOf(ShowType.IMAGES))
        val textOnly = FeedFilter(showTypes = setOf(ShowType.TEXT))

        assertTrue(matchesShowTypes(1, "https://cdn.example/cat.png", null, imagesOnly))
        assertTrue(matchesShowTypes(20, "", null, imagesOnly))
        assertFalse(matchesShowTypes(1, "https://cdn.example/cat.png", null, textOnly))
        assertFalse(matchesShowTypes(6, "", null, imagesOnly))
    }

    @Test
    fun `repost activity thresholds use target stats instead of wrapper stats`() {
        val filter = FeedFilter().withActivityPreset(ActivityPreset.POPULAR)
        val targetId = activityStatsTargetId(kind = 6, id = "wrapper", rootId = "target")
        val statsById = mapOf(
            "wrapper" to EventStats.EMPTY,
            "target" to EventStats(
                replyCount = 0,
                repostCount = 0,
                reactionCount = 10,
                zapCount = 0,
                zapTotalSats = 0L,
            ),
        )

        assertEquals("target", targetId)
        assertTrue(activityThresholdsPass(filter, statsById.getValue(targetId!!)))
        assertFalse(activityThresholdsPass(filter, statsById.getValue("wrapper")))
    }

    @Test
    fun `kind 16 generic repost activity target is its root id`() {
        assertEquals("article-target", activityStatsTargetId(kind = 16, id = "wrapper", rootId = "article-target"))
        assertEquals(null, activityStatsTargetId(kind = 16, id = "wrapper", rootId = null))
    }

    private fun modelWith(segments: List<Segment>): EventModel = EventModel(
        id = "id",
        pubkey = "pubkey",
        sourcePubkey = "pubkey",
        kind = 1,
        effectiveKind = 1,
        articleContent = null,
        createdAt = 1L,
        sourceCreatedAt = 1L,
        relayUrl = "wss://relay.example",
        engagementId = "id",
        navigateId = "id",
        segments = segments,
        media = MediaManifest(images = emptyList(), videos = emptyList(), ogCandidate = null, youtubes = emptyList()),
        thread = ThreadRefs(replyToId = null, rootId = null),
        repost = null,
        article = null,
        warnings = ContentWarnings(hasContentWarning = false, reason = null),
    )
}
