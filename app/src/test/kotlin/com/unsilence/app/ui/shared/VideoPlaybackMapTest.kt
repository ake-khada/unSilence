package com.unsilence.app.ui.shared

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.model.VideoRenderModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun `selected video URL wins within a multi-video row`() {
        val second = model("https://host/second.mp4")

        assertEquals(
            second.videoUrl,
            resolveSelectedVideoUrl(own + second, selectedUrl = second.videoUrl),
        )
    }

    @Test
    fun `missing or stale video selection falls back to first model`() {
        assertEquals(own.first().videoUrl, resolveSelectedVideoUrl(own, selectedUrl = null))
        assertEquals(
            own.first().videoUrl,
            resolveSelectedVideoUrl(own, selectedUrl = "https://host/stale.mp4"),
        )
    }

    @Test
    fun `empty video row has no selected URL`() {
        assertEquals(null, resolveSelectedVideoUrl(emptyList(), selectedUrl = null))
    }

    @Test
    fun `fullscreen request selects a registered secondary video`() {
        val second = model("https://host/second.mp4")

        assertEquals(
            second.videoUrl,
            resolvePlaybackVideoUrl(
                models = own + second,
                requestedUrl = second.videoUrl,
            ),
        )
    }

    @Test
    fun `fullscreen request never falls back from an unregistered URL`() {
        assertEquals(
            null,
            resolvePlaybackVideoUrl(
                models = own,
                requestedUrl = "https://host/unregistered.mp4",
            ),
        )
    }

    @Test
    fun `video selections retain only URLs still registered by current rows`() {
        val current = mapOf(
            "visible" to "https://host/visible-second.mp4",
            "stale-url" to "https://host/old.mp4",
            "off-screen" to "https://host/off-screen.mp4",
        )
        val modelsByNote = mapOf(
            "visible" to listOf(
                model("https://host/visible-first.mp4"),
                model("https://host/visible-second.mp4"),
            ),
            "stale-url" to listOf(model("https://host/new.mp4")),
        )

        assertEquals(
            mapOf("visible" to "https://host/visible-second.mp4"),
            retainRegisteredVideoSelections(current, modelsByNote),
        )
    }

    @Test
    fun `unchanged video selections preserve map identity`() {
        val current = mapOf("visible" to "https://host/visible.mp4")
        val modelsByNote = mapOf("visible" to listOf(model("https://host/visible.mp4")))

        assertSame(current, retainRegisteredVideoSelections(current, modelsByNote))
    }

    @Test
    fun `fullscreen open ignores rows without a target URL`() {
        val decision = decideFullscreenPlayback(
            targetUrl = null,
            holderUrl = "https://host/other.mp4",
            mediaItemCount = 1,
        )

        assertEquals(FullscreenPlaybackDecision.Ignore, decision)
    }

    @Test
    fun `fullscreen open resumes when holder URL matches and media is loaded`() {
        val decision = decideFullscreenPlayback(
            targetUrl = "https://host/own.mp4",
            holderUrl = "https://host/own.mp4",
            mediaItemCount = 1,
        )

        assertEquals(FullscreenPlaybackDecision.Resume, decision)
    }

    @Test
    fun `fullscreen open rebinds when holder URL differs`() {
        val decision = decideFullscreenPlayback(
            targetUrl = "https://host/own.mp4",
            holderUrl = "https://host/profile.mp4",
            mediaItemCount = 1,
        )

        assertEquals(FullscreenPlaybackDecision.Rebind, decision)
    }

    @Test
    fun `fullscreen open rebinds when matching holder has no media items`() {
        val decision = decideFullscreenPlayback(
            targetUrl = "https://host/own.mp4",
            holderUrl = "https://host/own.mp4",
            mediaItemCount = 0,
        )

        assertEquals(FullscreenPlaybackDecision.Rebind, decision)
    }

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
    fun `thread parent source prefers direct reply parent`() {
        val row = repostRow(1, "", "root-id").copy(replyToId = "parent-id")
        val ids = threadParentVideoSourceCandidateIds(row, cachedModel = null)
        assertEquals(listOf("parent-id"), ids)
    }

    @Test
    fun `thread parent source falls back to root for direct replies`() {
        val ids = threadParentVideoSourceCandidateIds(repostRow(1, "", "root-id"), cachedModel = null)
        assertEquals(listOf("root-id"), ids)
    }

    @Test
    fun `non-blank reference envelope still contributes its rootId candidate`() {
        // A structurally JSON-looking envelope may still be reference-only
        // after signature verification. Candidate discovery must not key trust
        // off whether the wrapper content happens to be non-blank.
        val ids = videoSourceCandidateIds(repostRow(6, """{"kind":1}""", "target-id"), cachedModel = null)
        assertEquals(listOf("target-id"), ids)
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
