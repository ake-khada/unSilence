package com.unsilence.app.ui.shared

import com.unsilence.app.data.model.VideoRenderModel
import org.junit.Assert.assertEquals
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
            quoteEventIds = emptyList(),
            videoModelsFor = { emptyList() },
        )
        assertEquals(own, result)
    }

    @Test
    fun `quote-only row maps to quoted video`() {
        val result = resolveRowVideoModels(
            ownModels = emptyList(),
            quoteEventIds = listOf("quoted-id"),
            videoModelsFor = { id -> if (id == "quoted-id") quoted else emptyList() },
        )
        assertEquals(quoted, result)
    }

    @Test
    fun `own plus quoted row maps to own video, never the quote`() {
        val result = resolveRowVideoModels(
            ownModels = own,
            quoteEventIds = listOf("quoted-id"),
            videoModelsFor = { quoted },  // available, but own must win
        )
        assertEquals(own, result)
    }

    @Test
    fun `quote-only row with no quoted video yields empty`() {
        val result = resolveRowVideoModels(
            ownModels = emptyList(),
            quoteEventIds = listOf("quoted-id"),
            videoModelsFor = { emptyList() },
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `picks the first quoted event that has a video`() {
        val result = resolveRowVideoModels(
            ownModels = emptyList(),
            quoteEventIds = listOf("no-video", "has-video"),
            videoModelsFor = { id -> if (id == "has-video") quoted else emptyList() },
        )
        assertEquals(quoted, result)
    }

    @Test
    fun `no own and no quotes yields empty`() {
        val result = resolveRowVideoModels(
            ownModels = emptyList(),
            quoteEventIds = emptyList(),
            videoModelsFor = { quoted },
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `quoteEventIdsOf null model is empty`() {
        assertTrue(quoteEventIdsOf(null).isEmpty())
    }
}
