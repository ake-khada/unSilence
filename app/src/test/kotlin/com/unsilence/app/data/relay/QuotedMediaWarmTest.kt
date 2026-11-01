package com.unsilence.app.data.relay

import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.QuotedMediaPolicy
import com.unsilence.app.domain.model.ShowType
import com.unsilence.app.domain.model.quotedMediaPolicy
import org.junit.Assert.*
import org.junit.Test

class QuotedMediaWarmTest {
    private val image = "https://example.com/image.png"
    private val video = "https://example.com/video.mp4"
    private val poster = "https://example.com/poster.png"
    private val youtube = "https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
    private val mixed = model("$image\n$video\nhttps://www.youtube.com/watch?v=dQw4w9WgXcQ",
        """[["imeta","url $video","image $poster"]]""")

    @Test fun textExcludesImagePosterAndYouTubeCandidates() {
        assertTrue(imageCandidates(mixed, policy(ShowType.TEXT)).isEmpty())
    }

    @Test fun textAndImagesOnlyWarmsImages() {
        assertEquals(listOf(image), imageCandidates(mixed, policy(ShowType.TEXT, ShowType.IMAGES)).map { it.url })
    }

    @Test fun videoOnlyWarmsPostersAndYouTube() {
        assertEquals(listOf(poster, youtube), imageCandidates(mixed, policy(ShowType.VIDEO)).map { it.url })
    }

    @Test fun allAndNonFeedDefaultKeepEveryCandidateAndDimensions() {
        val expected = imageCandidates(mixed)
        assertEquals(listOf(image, poster, youtube), expected.map { it.url })
        assertEquals(expected, imageCandidates(mixed, policy(ShowType.ALL)))
        assertEquals(listOf(false, false, true), expected.map { it.dimensionsKnown })
        assertEquals(listOf(false, false, true), expected.map { it.preview })
    }

    @Test fun quotedArticleArtworkIsOutsideThisFilter() {
        val article = model("Article text", """[["d","fixture"],["image","$image"]]""", kind = 30023)
        assertEquals(listOf(image), imageCandidates(article, policy(ShowType.TEXT)).map { it.url })
    }

    @Test fun everyShowCombinationHasIndependentImageAndVideoDecisions() {
        val types = ShowType.entries
        for (mask in 0 until (1 shl types.size)) {
            val selected = types.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val actual = FeedFilter(selected).quotedMediaPolicy()
            assertEquals(ShowType.ALL in selected || ShowType.IMAGES in selected, actual.images)
            assertEquals(ShowType.ALL in selected || ShowType.VIDEO in selected, actual.videos)
            assertTrue(actual.allows(ShowType.ARTICLES))
        }
    }

    @Test fun lateQuoteUsesLatestFilterWhileRepostOrParentRemainsUnrestricted() {
        var current = policy(ShowType.ALL)
        val quote = referenceAssetPolicy(true) { current }
        val other = referenceAssetPolicy(false) { current }
        current = policy(ShowType.TEXT)
        assertTrue(imageCandidates(mixed, quote()).isEmpty())
        assertFalse(quote().videos) // No first-frame extraction for a late video either.
        assertEquals(imageCandidates(mixed), imageCandidates(mixed, other()))
        current = policy(ShowType.TEXT, ShowType.IMAGES)
        assertEquals(listOf(image), imageCandidates(mixed, quote()).map { it.url })
        current = policy(ShowType.ALL)
        assertEquals(imageCandidates(mixed), imageCandidates(mixed, quote()))
    }

    private fun policy(vararg types: ShowType): QuotedMediaPolicy = FeedFilter(types.toSet()).quotedMediaPolicy()
    private fun model(content: String, tags: String = "[]", kind: Int = 1) = ContentParser.parse(
        id = "a".repeat(64), pubkey = "b".repeat(64), kind = kind, content = content,
        tagsJson = tags, createdAt = 1L, relayUrl = "", replyToId = null, rootId = null,
        hasContentWarning = false, contentWarningReason = null,
    )
}
