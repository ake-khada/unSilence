package com.unsilence.app.ui.compose

import androidx.core.net.toUri
import com.unsilence.app.data.blossom.AttachmentQuality
import com.unsilence.app.data.drafts.DraftBlock
import com.unsilence.app.data.drafts.toDraftAttachment
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

internal fun ComposeBlock.toDraftBlock(): DraftBlock = when (this) {
    is ComposeBlock.Text -> DraftBlock.Text(content)
    is ComposeBlock.Attachment -> if (state is AttachmentState.Uploaded) {
        state.blob.toDraftAttachment()
    } else {
        DraftBlock.LocalMedia(
            id = state.id,
            uri = state.uri.toString(),
            displayName = state.displayName,
            originalBytes = state.originalBytes,
            quality = state.quality.name,
            failure = (state as? AttachmentState.Failed)?.message?.take(512),
        )
    }
}

internal fun DraftBlock.LocalMedia.restoreMedia(hasAccess: Boolean): AttachmentState {
    val source = uri.toUri()
    val selected = AttachmentQuality.entries.find { it.name == quality } ?: AttachmentQuality.ORIGINAL
    val message = if (hasAccess) failure else "File unavailable. Remove it and choose it again."
    return if (message == null) AttachmentState.Idle(source, id, displayName, originalBytes, selected)
    else AttachmentState.Failed(source, id, displayName, originalBytes, selected, message)
}
