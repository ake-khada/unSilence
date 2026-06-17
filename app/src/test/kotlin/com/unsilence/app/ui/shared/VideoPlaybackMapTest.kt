package com.unsilence.app.ui.shared

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.model.VideoRenderModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure quote-video map-resolution helper used by
 * [rememberVideoPlaybackScope]. Validates the three required cases:
 *  - own-video row maps to its own video,
 *  - quote-only row maps to the quoted video,
 *  - own+quoted row maps to the OWN video (so the shared player binds to the
 *    parent's URL and the nested grid's URL gate keeps the quote a thumbnail).
 *
 * The Compose/player attach + active-video detection are device-only coverage.
 */
class VideoPlaybackMapTest {

    private fun model(url: String) = VideoRenderModel(
        videoUrl = url,
        aspectRatio = 16f / 9f,
        posterUrl = null,
        widthPx = null,
        heightPx = null,
    )

    private val own = listOf(model("https://host/own.mp4"))
    private val quoted = listOf(model("https://host/quoted.mp4"))

    @Test
    fun `own video row maps to own video`() {
        val result = resolveRowVideoModels(
            ownModels = own,
            candidateEventIds = emptyList(),
            videoModelsFor = { emptyList() },
        )
        assertEquals(own, result)
    }

    @Test
    fun `quote-only row maps to quoted video`() {
        val result = resolveRowVideoModels(
            ownModels = emptyList(),
            candidateEventIds = listOf("quoted-id"),
            videoModelsFor = { id -> if (id == "quoted-id") quoted else emptyList() },
        )
        assertEquals(quoted, result)
    }

    @Test
    fun `own plus quoted row maps to own video, never the quote`() {
        val result = resolveRowVideoModels(
            ownModels = own,
            candidateEventIds = listOf("quoted-id"),
            videoModelsFor = { quoted },  // available, but own must win
        )
        assertEquals(own, result)
    }

    @Test
    fun `quote-only row with no quoted video yields empty`() {
        val result = resolveRowVideoModels(
            ownModels = emptyList(),
            candidateEventIds = listOf("quoted-id"),
            videoModelsFor = { emptyList() },
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `picks the first quoted event that has a video`() {
        val result = resolveRowVideoModels(
            ownModels = emptyList(),
            candidateEventIds = listOf("no-video", "has-video"),
            videoModelsFor = { id -> if (id == "has-video") quoted else emptyList() },
        )
        assertEquals(quoted, result)
    }

    @Test
    fun `no own and no quotes yields empty`() {
        val result = resolveRowVideoModels(
            ownModels = emptyList(),
            candidateEventIds = emptyList(),
            videoModelsFor = { quoted },
        )
        assertTrue(result.isEmpty())
    }

    // ── videoSourceCandidateIds (FeedRow-based empty-repost discovery) ────────

    private fun repostRow(kind: Int, content: String, rootId: String?) = FeedRow(
        id = "row", pubkey = "pk", kind = kind, content = content, createdAt = 1L,
        tags = "[]", relayUrl = "wss://r", replyToId = null, rootId = rootId,
        hasContentWarning = false, contentWarningReason = null, zapTotalSats = 0L,
        authorName = null, authorDisplayName = null, authorPicture = null, authorNip05 = null,
        reactionCount = 0, replyCount = 0, repostCount = 0, zapCount = 0,
    )

    @Test
    fun `empty kind-6 repost includes rootId as candidate without a cached model`() {
        val ids = videoSourceCandidateIds(repostRow(6, "", "target-id"), cachedModel = null)
        assertEquals(listOf("target-id"), ids)
    }

    @Test
    fun `empty kind-16 repost includes rootId as candidate`() {
        val ids = videoSourceCandidateIds(repostRow(16, "", "target-id"), cachedModel = null)
        assertEquals(listOf("target-id"), ids)
    }

    @Test
    fun `non-repost row contributes no rootId candidate`() {
        val ids = videoSourceCandidateIds(repostRow(1, "", "target-id"), cachedModel = null)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `embedded repost (non-blank content) contributes no rootId candidate`() {
        // Embedded-JSON reposts render their target inline; their own models are
        // built from the embedded content, so they must not borrow via rootId.
        val ids = videoSourceCandidateIds(repostRow(6, """{"kind":1}""", "target-id"), cachedModel = null)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `empty repost of a NON-video target is not eligible`() {
        val candidates = videoSourceCandidateIds(repostRow(6, "", "text-target"), cachedModel = null)
        val result = resolveRowVideoModels(
            ownModels = emptyList(),
            candidateEventIds = candidates,
            videoModelsFor = { emptyList() },  // target has no video sidecar
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty repost of a video target is eligible (warm target)`() {
        val candidates = videoSourceCandidateIds(repostRow(6, "", "video-target"), cachedModel = null)
        val result = resolveRowVideoModels(
            ownModels = emptyList(),
            candidateEventIds = candidates,
            videoModelsFor = { id -> if (id == "video-target") quoted else emptyList() },
        )
        assertEquals(quoted, result)
        assertFalse(result.isEmpty())
    }
}
