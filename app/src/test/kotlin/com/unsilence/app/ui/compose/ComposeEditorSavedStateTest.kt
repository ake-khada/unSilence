package com.unsilence.app.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeEditorSavedStateTest {

    @Test
    fun `text poll and sensitive editor state round trip`() {
        val editor = SavedComposeEditor(
            blocks = listOf(
                SavedComposeBlock(id = "text-1", text = "still composing"),
                SavedComposeBlock(id = "text-2", text = "second block"),
            ),
            poll = SavedComposePoll(
                enabled = true,
                options = listOf(
                    SavedComposePollOption("a", "Alpha"),
                    SavedComposePollOption("b", "Beta"),
                ),
                multipleChoice = true,
                durationSeconds = 3_600L,
            ),
            isSensitive = true,
            selectedEmojiUrls = mapOf("wave" to "https://emoji.example/wave.webp"),
        )

        val encoded = encodeComposeEditor(editor)

        assertNotNull(encoded)
        assertEquals(editor, decodeComposeEditor(encoded!!))
    }

    @Test
    fun `long body plus attachment descriptors stays below saved state ceiling`() {
        val attachment = SavedComposeAttachment(
            uri = "content://media/picker/item",
            displayName = "clip.mp4",
            originalBytes = 25_000_000L,
            quality = "HIGH",
            status = SavedAttachmentStatus.UPLOADED,
            blob = SavedComposeBlob(
                url = "https://media.example/${"u".repeat(1_000)}.mp4",
                sha256 = "a".repeat(64),
                sizeBytes = 4_000_000L,
                mimeType = "video/mp4",
                width = 1080,
                height = 1920,
                thumbnailUrl = "https://media.example/${"t".repeat(1_000)}.webp",
                durationMs = 12_000L,
            ),
        )
        val editor = SavedComposeEditor(
            blocks = listOf(
                SavedComposeBlock(id = "body", text = "x".repeat(8_000)),
            ) + (1..8).map { index ->
                SavedComposeBlock(id = "attachment-$index", attachment = attachment)
            },
        )

        val encoded = encodeComposeEditor(editor)

        assertNotNull(encoded)
        assertTrue(encoded!!.toByteArray(Charsets.UTF_8).size < COMPOSE_SAVED_STATE_MAX_BYTES)
    }

    @Test
    fun `oversized editor snapshot is excluded from the activity bundle`() {
        val editor = SavedComposeEditor(
            blocks = listOf(
                SavedComposeBlock(
                    id = "body",
                    text = "x".repeat(COMPOSE_SAVED_STATE_MAX_BYTES + 1),
                ),
            ),
        )

        assertNull(encodeComposeEditor(editor))
    }
}
