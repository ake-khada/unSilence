package com.unsilence.app.ui.feed

import com.unsilence.app.data.model.ContentWarnings
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.MediaManifest
import com.unsilence.app.data.model.RepostInfo
import com.unsilence.app.data.model.RepostPayload
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.model.ThreadRefs
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.data.model.VerifiedRepostEvent
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
        assertTrue(matchesShowTypes(22, "", null, FeedFilter(showTypes = setOf(ShowType.VIDEO))))
        assertTrue(matchesShowTypes(34235, "", null, FeedFilter(showTypes = setOf(ShowType.VIDEO))))
        assertTrue(matchesShowTypes(34236, "", null, FeedFilter(showTypes = setOf(ShowType.VIDEO))))
        assertFalse(matchesShowTypes(1, "https://cdn.example/cat.png", null, textOnly))
        assertFalse(matchesShowTypes(6, "", null, imagesOnly))
    }

    @Test
    fun `reposts match only their target content class`() {
        val video = Segment.Video(
            VideoRenderModel("https://cdn.example/video.mp4", 16f / 9f, null, null, null),
        )
        val cases = listOf(
            Triple(
                modelWith(
                    segments = emptyList(),
                    kind = 16,
                    effectiveKind = 22,
                    repost = repostInfo(embedded = true),
                ),
                null,
                ShowType.VIDEO,
            ),
            Triple(
                modelWith(
                    segments = emptyList(),
                    kind = 16,
                    effectiveKind = 20,
                    repost = repostInfo(embedded = false),
                ),
                ResolvedRepostTarget(
                    content = "",
                    model = modelWith(emptyList(), kind = 20, effectiveKind = 20),
                ),
                ShowType.IMAGES,
            ),
            Triple(
                modelWith(
                    segments = emptyList(),
                    kind = 6,
                    effectiveKind = 1,
                    repost = repostInfo(embedded = false),
                ),
                ResolvedRepostTarget(
                    content = "a clip",
                    model = modelWith(listOf(video), kind = 1, effectiveKind = 1),
                ),
                ShowType.VIDEO,
            ),
            Triple(
                modelWith(
                    segments = listOf(Segment.Text("original text")),
                    kind = 6,
                    effectiveKind = 1,
                    repost = repostInfo(embedded = true),
                ),
                null,
                ShowType.TEXT,
            ),
            Triple(
                modelWith(
                    segments = emptyList(),
                    kind = 16,
                    effectiveKind = 30023,
                    repost = repostInfo(embedded = true),
                ),
                null,
                ShowType.ARTICLES,
            ),
        )
        val specificTypes = listOf(ShowType.TEXT, ShowType.IMAGES, ShowType.VIDEO, ShowType.ARTICLES)

        cases.forEachIndexed { index, (wrapper, target, expectedType) ->
            specificTypes.forEach { showType ->
                assertEquals(
                    "case=$index show=$showType",
                    showType == expectedType,
                    matchesShowTypes(
                        kind = wrapper.kind,
                        content = "wrapper content",
                        model = wrapper,
                        filter = FeedFilter(showTypes = setOf(showType)),
                        resolvedRepostTarget = target,
                    ),
                )
            }
        }
    }

    @Test
    fun `unresolved repost is excluded from specific views but all is unchanged`() {
        val unresolved = modelWith(
            segments = emptyList(),
            kind = 16,
            effectiveKind = 22,
            repost = repostInfo(embedded = false),
        )

        listOf(ShowType.TEXT, ShowType.IMAGES, ShowType.VIDEO, ShowType.ARTICLES).forEach { showType ->
            assertFalse(
                matchesShowTypes(
                    kind = 16,
                    content = "",
                    model = unresolved,
                    filter = FeedFilter(showTypes = setOf(showType)),
                ),
            )
        }
        assertTrue(matchesShowTypes(16, "", unresolved, FeedFilter()))
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

    private fun repostInfo(embedded: Boolean): RepostInfo = RepostInfo(
        targetId = "target",
        relayHint = null,
        addressCoordinate = null,
        addressRelayHint = null,
        targetAuthorHint = null,
        proxyUrl = null,
        payload = if (embedded) {
            RepostPayload.VerifiedEmbedded(
                VerifiedRepostEvent("target", "pubkey", 1, "", 1L, emptyList()),
            )
        } else {
            RepostPayload.ReferenceOnly
        },
    )

    private fun modelWith(
        segments: List<Segment>,
        kind: Int = 1,
        effectiveKind: Int = kind,
        repost: RepostInfo? = null,
    ): EventModel = EventModel(
        id = "id",
        pubkey = "pubkey",
        sourcePubkey = "pubkey",
        kind = kind,
        effectiveKind = effectiveKind,
        displayContent = "",
        articleContent = null,
        createdAt = 1L,
        sourceCreatedAt = 1L,
        relayUrl = "wss://relay.example",
        engagementId = "id",
        navigateId = "id",
        segments = segments,
        media = MediaManifest(images = emptyList(), videos = emptyList(), ogCandidate = null, youtubes = emptyList()),
        thread = ThreadRefs(replyToId = null, rootId = null),
        repost = repost,
        article = null,
        warnings = ContentWarnings(hasContentWarning = false, reason = null),
    )
}
