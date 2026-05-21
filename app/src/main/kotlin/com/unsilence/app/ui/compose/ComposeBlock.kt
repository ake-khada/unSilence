package com.unsilence.app.ui.compose

/**
 * One unit of compose content. Compose state is a `List<ComposeBlock>`
 * rendered in order. The published kind-1's content + tags are built
 * by walking the list at publish time — text and attachment URLs land
 * in the content string in the order they appear here, and imeta
 * tags are emitted per Attachment block.
 *
 * For Sprint A commit #1, only Text and Attachment exist. Future
 * commits add Embed (nostr:nevent references rendered as feed-style
 * cards) and UrlPreview (OG cards inline).
 */
sealed interface ComposeBlock {
    data class Text(val content: String) : ComposeBlock

    data class Attachment(val state: AttachmentState) : ComposeBlock
}
