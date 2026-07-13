package com.unsilence.app.ui.feed

import androidx.media3.common.Player
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.ShowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveVideoPolicyTest {
    private val video = VideoRenderModel(
        videoUrl = "https://media.example/video",
        aspectRatio = 9f / 16f,
        posterUrl = null,
        widthPx = 1080,
        heightPx = 1920,
    )

    @Test
    fun `video-only filter enters immersive mode`() {
        assertTrue(FeedFilter(showTypes = setOf(ShowType.VIDEO)).isImmersiveVideoMode())
        assertFalse(FeedFilter(showTypes = setOf(ShowType.ALL)).isImmersiveVideoMode())
        assertFalse(
            FeedFilter(showTypes = setOf(ShowType.VIDEO, ShowType.IMAGES)).isImmersiveVideoMode(),
        )
    }

    @Test
    fun `only repeat transitions preserve the rendered frame`() {
        listOf(
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT to false,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO to true,
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK to true,
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED to true,
        ).forEach { (reason, expected) ->
            assertEquals("reason=$reason", expected, shouldClearRenderedFrame(reason))
        }
    }

    @Test
    fun `pager selects playable native and kind-one rows only`() {
        val rows = listOf(
            row("kind1-video", 1),
            row("kind1-youtube", 1),
            row("normal", 21),
            row("short", 22),
            row("addressable", 34235),
            row("addressable-short", 34236),
            row("repost", 16),
        )
        val playable = setOf("kind1-video", "normal", "short", "addressable", "addressable-short")
        val selected = selectImmersiveVideoItems(rows) { id ->
            if (id in playable) listOf(video.copy(videoUrl = "https://media.example/$id")) else emptyList()
        }

        assertEquals(playable, selected.map { it.row.id }.toSet())
        assertTrue(selected.none { it.row.id == "kind1-youtube" })
        assertTrue(selected.none { it.row.id == "repost" })
    }

    @Test
    fun `preload policy warms exactly the next item`() {
        assertEquals(1, immersivePreloadIndex(currentIndex = 0, itemCount = 3, isPowerSaveMode = false))
        assertEquals(2, immersivePreloadIndex(currentIndex = 1, itemCount = 3, isPowerSaveMode = false))
        assertNull(immersivePreloadIndex(currentIndex = 2, itemCount = 3, isPowerSaveMode = false))
        assertNull(immersivePreloadIndex(currentIndex = 0, itemCount = 3, isPowerSaveMode = true))
        assertNull(immersivePreloadIndex(currentIndex = -1, itemCount = 3, isPowerSaveMode = false))
    }

    @Test
    fun `filter icon mapping is exhaustive and stable`() {
        assertEquals(FilterIconKind.GRID, filterIconKind(ShowType.ALL))
        assertEquals(FilterIconKind.TEXT, filterIconKind(ShowType.TEXT))
        assertEquals(FilterIconKind.IMAGE, filterIconKind(ShowType.IMAGES))
        assertEquals(FilterIconKind.VIDEO, filterIconKind(ShowType.VIDEO))
        assertEquals(FilterIconKind.ARTICLE, filterIconKind(ShowType.ARTICLES))
    }

    private fun row(id: String, kind: Int) = FeedRow(
        id = id,
        pubkey = "a".repeat(64),
        kind = kind,
        content = "",
        createdAt = 1L,
        tags = "[]",
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        zapTotalSats = 0,
        authorName = null,
        authorDisplayName = null,
        authorPicture = null,
        authorNip05 = null,
        reactionCount = 0,
        replyCount = 0,
        repostCount = 0,
        zapCount = 0,
    )
}
