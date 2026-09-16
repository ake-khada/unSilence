package com.unsilence.app.ui.shared

import com.unsilence.app.data.model.VideoRenderModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackPermissionTest {
    private val own = video("own")
    private val sensitive = video("sensitive")

    @Test
    fun `cached sensitive media alone cannot autoplay`() {
        assertTrue(permittedVideoModels(mapOf("row" to listOf(sensitive)), emptyList()).isEmpty())
    }

    @Test
    fun `ordinary wrapper cannot authorize its hidden quoted or reposted target`() {
        val playable = permittedVideoModels(
            mapOf("wrapper" to listOf(sensitive)),
            listOf(VideoPlaybackRegistration("wrapper", listOf(own))),
        )
        assertEquals(listOf(own), playable["wrapper"])
        assertNull(resolvePlaybackVideoUrl(playable["wrapper"].orEmpty(), sensitive.videoUrl))
    }

    @Test
    fun `reveal authorizes exact media under the lazy row owner`() {
        assertEquals(
            mapOf("wrapper" to listOf(sensitive)),
            permittedVideoModels(
                mapOf("wrapper" to listOf(sensitive)),
                listOf(VideoPlaybackRegistration("wrapper", listOf(sensitive))),
            ),
        )
    }

    @Test
    fun `same URL in another row does not inherit consent`() {
        val playable = permittedVideoModels(
            mapOf("revealed" to listOf(sensitive), "hidden" to listOf(sensitive)),
            listOf(VideoPlaybackRegistration("revealed", listOf(sensitive))),
        )
        assertEquals(setOf("revealed"), playable.keys)
    }

    @Test
    fun `re-hiding drops permission even with retained cached models`() {
        val cached = mapOf("row" to listOf(sensitive))
        val registrations = mutableListOf(VideoPlaybackRegistration("row", listOf(sensitive)))
        assertFalse(permittedVideoModels(cached, registrations).isEmpty())
        registrations.clear()
        assertTrue(permittedVideoModels(cached, registrations).isEmpty())
    }

    @Test
    fun `disposing one occurrence does not revoke another rendered occurrence`() {
        val cached = mapOf("row" to listOf(sensitive))
        val first = VideoPlaybackRegistration("row", listOf(sensitive))
        val second = VideoPlaybackRegistration("row", listOf(sensitive))
        assertEquals(cached, permittedVideoModels(cached, listOf(first, second)))
        assertEquals(cached, permittedVideoModels(cached, listOf(second)))
    }

    @Test
    fun `reveal of a late-resolved quote needs no cache or scroll update`() {
        assertEquals(
            mapOf("wrapper" to listOf(sensitive)),
            permittedVideoModels(
                emptyMap(),
                listOf(VideoPlaybackRegistration("wrapper", listOf(sensitive))),
            ),
        )
    }

    @Test
    fun `own-video priority and multi-video selection survive the permission gate`() {
        val second = video("second")
        val playable = permittedVideoModels(
            mapOf("row" to listOf(own, second)),
            listOf(
                VideoPlaybackRegistration("row", listOf(sensitive)),
                VideoPlaybackRegistration("row", listOf(own, second)),
            ),
        )
        assertEquals(listOf(own, second), playable["row"])
        assertEquals(second.videoUrl, resolveSelectedVideoUrl(playable["row"].orEmpty(), second.videoUrl))
        assertNull(resolvePlaybackVideoUrl(playable["row"].orEmpty(), sensitive.videoUrl))
    }

    @Test
    fun `stale selection cannot play a revoked URL`() {
        val playable = permittedVideoModels(
            mapOf("row" to listOf(sensitive, own)),
            listOf(VideoPlaybackRegistration("row", listOf(own))),
        )
        assertEquals(own.videoUrl, resolveSelectedVideoUrl(playable["row"].orEmpty(), sensitive.videoUrl))
    }

    @Test
    fun `empty registration never makes a row eligible`() {
        assertTrue(
            permittedVideoModels(
                mapOf("row" to listOf(sensitive)),
                listOf(VideoPlaybackRegistration("row", emptyList())),
            ).isEmpty(),
        )
    }

    private fun video(id: String) = VideoRenderModel(
        videoUrl = "https://example.invalid/$id.mp4",
        aspectRatio = 16f / 9f,
        posterUrl = null,
        widthPx = null,
        heightPx = null,
    )
}
