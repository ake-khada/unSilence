package com.unsilence.app.ui.compose

import java.util.UUID

/**
 * One unit of compose content. Compose state is a `List<ComposeBlock>`
 * rendered in order. The published kind-1's content + tags are built
 * by walking the list at publish time — text and attachment URLs land
 * in the content string in the order they appear here, and imeta
 * tags are emitted per Attachment block.
 *
 * Each block carries a stable [id] used as the Compose `key()` in the
 * rendering loop. Text generates a fresh UUID at construction;
 * Attachment reuses the AttachmentState's id.
 */
sealed interface ComposeBlock {
    val id: String

    data class Text(
        val content: String,
        override val id: String = UUID.randomUUID().toString(),
    ) : ComposeBlock

    data class Attachment(val state: AttachmentState) : ComposeBlock {
        override val id: String get() = state.id
    }
}
