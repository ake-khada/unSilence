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
    fun `pager selects playable native rows and rejects an unresolved repost`() {
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
        val selected = selectImmersiveVideoItems(
            rows = rows,
            videoModelsFor = { id ->
                if (id in playable) listOf(video.copy(videoUrl = "https://media.example/$id")) else emptyList()
            },
        )

        assertEquals(playable, selected.map { it.row.id }.toSet())
        assertTrue(selected.none { it.row.id == "kind1-youtube" })
        assertTrue(selected.none { it.row.id == "repost" })
    }

    @Test
    fun `pager resolves repost media by target and dedups to newest occurrence`() {
        val targetId = "target-video"
        val originalAuthor = "b".repeat(64)
        val requestedIds = mutableListOf<String>()
        val rows = listOf(
            row("newest-repost", 16, rootId = targetId, createdAt = 10L),
            row("older-repost", 6, rootId = targetId, createdAt = 9L),
            row(targetId, 22, pubkey = originalAuthor, createdAt = 8L),
        )

        val selected = selectImmersiveVideoItems(
            rows = rows,
            videoModelsFor = { id ->
                requestedIds += id
                if (id == targetId) listOf(video) else emptyList()
            },
            authorPubkeyFor = { id -> originalAuthor.takeIf { id == targetId } },
        )

        assertEquals(listOf(targetId), requestedIds)
        assertEquals(1, selected.size)
        assertEquals("newest-repost", selected.single().row.id)
        assertEquals(targetId, selected.single().contentId)
        assertEquals(originalAuthor, selected.single().authorPubkey)
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
    fun `resilient startup is limited to metadata-proven high bitrate media`() {
        val divine = video.copy(
            sizeBytes = 6_557_078L,
            durationSeconds = 6.0,
            mimeType = "video/mp4",
        )
        val normal = video.copy(sizeBytes = 3_000_000L, durationSeconds = 10.0)

        assertEquals(8_742_770L, estimatedVideoBitrateBps(divine.sizeBytes, divine.durationSeconds))
        assertEquals(1_500L, resilientStartupBufferMs(divine))
        assertTrue(shouldDeferImmersivePreload(divine))
        assertEquals(0L, resilientStartupBufferMs(normal))
        assertFalse(shouldDeferImmersivePreload(normal))
        assertEquals(0L, resilientStartupBufferMs(video))
    }

    @Test
    fun `resilient startup boundaries are deterministic`() {
        assertEquals(
            0L,
            resilientStartupBufferMs(
                video.copy(sizeBytes = 499_999L, durationSeconds = 1.0),
            ),
        )
        assertEquals(
            500L,
            resilientStartupBufferMs(
                video.copy(sizeBytes = 500_000L, durationSeconds = 1.0),
            ),
        )
        assertEquals(
            MAX_RESILIENT_STARTUP_BUFFER_MS,
            resilientStartupBufferMs(
                video.copy(sizeBytes = 20_000_000L, durationSeconds = 20.0),
            ),
        )
        assertNull(estimatedVideoBitrateBps(null, 1.0))
        assertNull(estimatedVideoBitrateBps(1L, 0.0))
    }

    @Test
    fun `immersive session membership is append-only`() {
        val initial = listOf(item("b", 4), item("c", 3))

        assertEquals(
            listOf("a", "b"),
            mergeImmersiveItems(emptyList(), listOf(item("a", 5), item("a", 5), item("b", 4)))
                .map { it.row.id },
        )
        assertEquals(
            listOf("b", "c"),
            mergeImmersiveItems(initial, listOf(item("a", 5), item("b", 4), item("c", 3)))
                .map { it.row.id },
        )
        assertEquals(
            listOf("b", "c", "d", "e"),
            mergeImmersiveItems(
                initial,
                listOf(item("a", 5), item("b", 4), item("c", 3), item("d", 2), item("e", 1)),
            ).map { it.row.id },
        )
        assertEquals(
            listOf("b", "c", "d"),
            mergeImmersiveItems(
                initial,
                listOf(item("b", 4), item("c", 3), item("d", 2), item("d", 2)),
            ).map { it.row.id },
        )
    }

    @Test
    fun `target dedup remains append-only after a newer repost arrives`() {
        val initial = listOf(
            item(id = "stable-repost", createdAt = 5, contentId = "viral"),
            item(id = "shared", createdAt = 4),
        )
        val incoming = listOf(
            item(id = "newer-repost", createdAt = 7, contentId = "viral"),
            item(id = "shared", createdAt = 4),
            item(id = "next-repost", createdAt = 3, contentId = "next"),
            item(id = "duplicate-next", createdAt = 2, contentId = "next"),
        )

        val merged = mergeImmersiveItems(initial, incoming)

        assertEquals(listOf("viral", "shared", "next"), merged.map { it.contentId })
        assertEquals(listOf("stable-repost", "shared", "next-repost"), merged.map { it.row.id })
    }

    @Test
    fun `filter icon mapping is exhaustive and stable`() {
        assertEquals(FilterIconKind.GRID, filterIconKind(ShowType.ALL))
        assertEquals(FilterIconKind.TEXT, filterIconKind(ShowType.TEXT))
        assertEquals(FilterIconKind.IMAGE, filterIconKind(ShowType.IMAGES))
        assertEquals(FilterIconKind.VIDEO, filterIconKind(ShowType.VIDEO))
        assertEquals(FilterIconKind.ARTICLE, filterIconKind(ShowType.ARTICLES))
    }

    private fun row(
        id: String,
        kind: Int,
        rootId: String? = null,
        pubkey: String = "a".repeat(64),
        createdAt: Long = 1L,
    ) = FeedRow(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = "",
        createdAt = createdAt,
        tags = "[]",
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = rootId,
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

    private fun item(
        id: String,
        createdAt: Long,
        contentId: String = id,
    ) = ImmersiveVideoItem(
        row = row(id, 22).copy(createdAt = createdAt),
        video = video.copy(videoUrl = "https://media.example/$id"),
        contentId = contentId,
    )
}
